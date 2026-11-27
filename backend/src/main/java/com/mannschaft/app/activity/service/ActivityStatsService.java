package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityStatsGranularity;
import com.mannschaft.app.activity.dto.ActivityFieldStatsResponse;
import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 活動記録統計サービス。統計・集計・CSVエクスポートを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivityStatsService {

    private final ActivityResultRepository resultRepository;
    private final ActivityTemplateFieldRepository fieldRepository;
    private final ActivityTemplateRepository templateRepository;
    private final ActivityParticipantRepository participantRepository;
    private final NameResolverService nameResolverService;
    private final TeamTimezoneResolver teamTimezoneResolver;
    private final Clock clock;

    /** 統計期間の上限（両端を含む日数）。F06.4 §15.2「3 年」= 1,096 日。 */
    static final long MAX_RANGE_DAYS = 1096;
    /** 上位参加者の最大人数。 */
    static final int TOP_PARTICIPANTS_LIMIT = 10;

    /**
     * 活動統計を集計する（F06.4 §15.2・CMP-261124-0159）。認可（MEMBER 以上・上位参加者の可視判定）は呼び出し側で済ませる。
     *
     * <p>閲覧者によらず全員共通の数字。数えるのは PUBLISHED・未削除の記録だけ（visibility・自動生成の別を問わない）。
     * 問い合わせは (1) 総数＋テンプレート別 (2) 活動日別 (3) 延べ・実人数 (4) 上位参加者 (5) 名前の一括解決 の最大 5 本で、
     * 記録件数・参加者数に比例しない。どの問い合わせの失敗も握りつぶさず伝播させる（部分集計を返さない）。</p>
     *
     * <p><b>トランザクションを張らない理由</b>: 既定期間の「今日」（チームのタイムゾーン）と上位参加者の名前は、共通基盤の
     * {@code TeamTimezoneResolver}・{@code NameResolverService} を通じて team / auth ドメインの表を読む。活動ドメインの
     * {@code @Transactional} をここに張るとドメインをまたぐトランザクション入口になる（CLAUDE.md 原則 5・番人 D-3T）。
     * 各問い合わせは読み取りだけで、それぞれのリポジトリ呼び出しの読み取りトランザクションで実行する。</p>
     *
     * @param topParticipantsVisible この閲覧者に上位参加者を見せるか（false なら (4)(5) を実行せず {@code topParticipants=null}）
     */
    public ActivityStatsResponse getStats(ActivityScopeType scopeType, Long scopeId, Long templateId,
                                          LocalDate dateFrom, LocalDate dateTo, String granularity,
                                          boolean topParticipantsVisible) {
        ActivityStatsGranularity unit = ActivityStatsGranularity.parse(granularity);
        LocalDate to = dateTo != null ? dateTo : today(scopeType, scopeId);
        LocalDate from = dateFrom != null ? dateFrom : to.minusMonths(11).withDayOfMonth(1);
        validateRange(from, to);
        if (templateId != null) {
            requireTemplateInScope(scopeType, scopeId, templateId);
        }

        // (1) 総数＋テンプレート別
        List<Object[]> templateRows = resultRepository.countStatsByTemplate(scopeType, scopeId, from, to, templateId);
        long totalActivities = 0;
        List<ActivityStatsResponse.TemplateCount> byTemplate = new ArrayList<>();
        for (Object[] row : templateRows) {
            long count = ((Number) row[2]).longValue();
            totalActivities += count;
            byTemplate.add(new ActivityStatsResponse.TemplateCount((Long) row[0], (String) row[1], count));
        }
        byTemplate.sort(Comparator.comparingLong(ActivityStatsResponse.TemplateCount::getCount).reversed()
                .thenComparing(ActivityStatsResponse.TemplateCount::getTemplateId,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        // (2) 活動日別 → バケットへ畳み込み、期間内の全バケットを 0 埋め
        Map<LocalDate, long[]> buckets = new LinkedHashMap<>();
        for (LocalDate start = unit.bucketStart(from); !start.isAfter(to); start = unit.next(start)) {
            buckets.put(start, new long[2]);
        }
        for (Object[] row : resultRepository.countStatsByActivityDate(scopeType, scopeId, from, to, templateId)) {
            long[] bucket = buckets.get(unit.bucketStart((LocalDate) row[0]));
            bucket[0] += ((Number) row[1]).longValue();
            bucket[1] += ((Number) row[2]).longValue();
        }
        List<ActivityStatsResponse.PeriodCount> byPeriod = buckets.entrySet().stream()
                .map(e -> new ActivityStatsResponse.PeriodCount(e.getKey(), unit.label(e.getKey()),
                        e.getValue()[0], e.getValue()[1]))
                .toList();

        // (3) 延べ・実人数
        List<Object[]> participantRows = participantRepository.countStatsParticipants(
                scopeType, scopeId, from, to, templateId);
        Object[] participantRow = participantRows.isEmpty() ? new Object[]{0L, 0L} : participantRows.get(0);
        long totalParticipations = participantRow[0] == null ? 0 : ((Number) participantRow[0]).longValue();
        long uniqueParticipants = participantRow[1] == null ? 0 : ((Number) participantRow[1]).longValue();
        BigDecimal average = totalActivities == 0 ? null
                : BigDecimal.valueOf(totalParticipations).divide(BigDecimal.valueOf(totalActivities), 1, RoundingMode.HALF_UP);

        // (4)(5) 上位参加者と名前の一括解決（見せない閲覧者には実行しない）
        List<ActivityStatsResponse.TopParticipant> topParticipants = topParticipantsVisible
                ? topParticipants(scopeType, scopeId, from, to, templateId) : null;

        return ActivityStatsResponse.builder()
                .scopeType(scopeType.name())
                .scopeId(scopeId)
                .dateFrom(from)
                .dateTo(to)
                .granularity(unit.name())
                .totalActivities(totalActivities)
                .totalParticipations(totalParticipations)
                .uniqueParticipants(uniqueParticipants)
                .averageParticipants(average)
                .byTemplate(byTemplate)
                .byPeriod(byPeriod)
                .topParticipantsVisible(topParticipantsVisible)
                .topParticipants(topParticipants)
                .build();
    }

    private List<ActivityStatsResponse.TopParticipant> topParticipants(ActivityScopeType scopeType, Long scopeId,
            LocalDate from, LocalDate to, Long templateId) {
        List<Object[]> rows = participantRepository.findStatsTopParticipants(scopeType, scopeId, from, to, templateId,
                PageRequest.of(0, TOP_PARTICIPANTS_LIMIT));
        List<Long> userIds = rows.stream().map(r -> (Long) r[0]).toList();
        // 退会受付済み（匿名化済み）の利用者は名前解決の結果に現れない（users の論理削除）。userId も伏せる
        Map<Long, String> names = userIds.isEmpty() ? Map.of() : nameResolverService.resolveUserFullNames(userIds);
        return rows.stream().map(r -> {
            Long userId = (Long) r[0];
            long count = ((Number) r[1]).longValue();
            String name = names.get(userId);
            return name == null
                    ? new ActivityStatsResponse.TopParticipant(null, null, count)
                    : new ActivityStatsResponse.TopParticipant(userId, name, count);
        }).toList();
    }

    /** 期間の検証。日数 = date_to − date_from + 1（両端含む）。 */
    public static void validateRange(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BusinessException(ActivityErrorCode.STATS_DATE_RANGE_INVERTED);
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ActivityErrorCode.STATS_DATE_RANGE_TOO_LONG);
        }
    }

    /** スコープのタイムゾーンでの今日（チームは teams.timezone、未設定・組織・委員会は Asia/Tokyo）。 */
    private LocalDate today(ActivityScopeType scopeType, Long scopeId) {
        ZoneId zone = scopeType == ActivityScopeType.TEAM
                ? teamTimezoneResolver.resolveZone(scopeId)
                : ZoneId.of(TeamTimezoneResolver.DEFAULT_TIMEZONE);
        return LocalDate.now(clock.withZone(zone));
    }

    /** テンプレートがこのスコープのものであること（他スコープは存在しない ID と同一の 404 ACTIVITY_002）。 */
    private void requireTemplateInScope(ActivityScopeType scopeType, Long scopeId, Long templateId) {
        templateRepository.findById(templateId)
                .filter(t -> t.getScopeType() == scopeType && Objects.equals(t.getScopeId(), scopeId))
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * カスタムフィールドの集計データを取得する。
     */
    @Transactional(readOnly = true)
    public ActivityFieldStatsResponse getFieldStats(ActivityScopeType scopeType, Long scopeId,
                                                     Long templateId, String fieldKey, String period) {
        // field_key のバリデーション（SQL Injection 防止）
        if (!fieldKey.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("不正なfield_keyです");
        }

        // CMP-261124-0159 F06.4 §15.4: template がこのスコープのものであることを、フィールド定義を読む前に確かめる。
        // 他スコープのテンプレートは存在しない ID と同一の 404（ACTIVITY_002）にする（存在とフィールド名を漏らさない）
        requireTemplateInScope(scopeType, scopeId, templateId);

        // フィールド定義の取得
        ActivityTemplateFieldEntity field = fieldRepository.findByTemplateIdAndFieldKey(templateId, fieldKey)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.TEMPLATE_NOT_FOUND));

        // 将来実装: JSON_EXTRACTを使ったカスタムフィールド統計
        return ActivityFieldStatsResponse.builder()
                .fieldKey(field.getFieldKey())
                .fieldLabel(field.getFieldLabel())
                .unit(field.getUnit())
                .aggregation(new ActivityFieldStatsResponse.Aggregation(
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0))
                .trend(Collections.emptyList())
                .build();
    }

    /**
     * 活動記録をCSVエクスポートする。
     */
    @Transactional(readOnly = true)
    public void exportCsv(ActivityScopeType scopeType, Long scopeId,
                           Long templateId, LocalDate dateFrom, LocalDate dateTo,
                           HttpServletResponse response) {
        csv(scopeType, scopeId, templateId, dateFrom, dateTo, response, null);
    }

    /**
     * 認証済み CSV エクスポート（CMP-261124-0158）。manual 行は認証済み一覧と同じ可視性述語
     * （{@code visibilities} と DRAFT の作成者本人／SystemAdmin）で、auto 行は認可段取り役が判定した
     * {@code automaticIds} で絞る。閲覧者が一覧で見られない記録の題名を書き出さない。
     */
    @Transactional(readOnly = true)
    public void exportCsvWithAutomaticAccess(ActivityScopeType scopeType, Long scopeId, Long templateId,
            LocalDate dateFrom, LocalDate dateTo, HttpServletResponse response, java.util.Set<Long> automaticIds,
            java.util.Set<com.mannschaft.app.activity.ActivityVisibility> visibilities, Long viewerUserId,
            boolean viewerIsSystemAdmin) {
        List<ActivityResultEntity> results = resultRepository.findVisibleForExportWithAutomaticIds(
                scopeType, scopeId, templateId, dateFrom, dateTo, visibilities, viewerUserId, viewerIsSystemAdmin,
                automaticIds.isEmpty() ? java.util.Set.of(-1L) : automaticIds, PageRequest.of(0, 5000));
        writeCsv(scopeType, scopeId, results, response);
    }

    private void csv(ActivityScopeType scopeType, Long scopeId, Long templateId, LocalDate dateFrom,
            LocalDate dateTo, HttpServletResponse response, java.util.Set<Long> automaticIds) {
        List<ActivityResultEntity> results = automaticIds == null ? resultRepository.findForExport(
                scopeType, scopeId, templateId, dateFrom, dateTo, PageRequest.of(0, 5000))
                : resultRepository.findForExportWithAutomaticIds(scopeType, scopeId, templateId, dateFrom, dateTo,
                        automaticIds, PageRequest.of(0, 5000));
        writeCsv(scopeType, scopeId, results, response);
    }

    private void writeCsv(ActivityScopeType scopeType, Long scopeId, List<ActivityResultEntity> results,
            HttpServletResponse response) {

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=activities_" +
                scopeType.name() + "_" + scopeId + ".csv");

        try {
            PrintWriter writer = response.getWriter();
            // BOM for Excel
            writer.write('\uFEFF');

            // ヘッダー
            writer.println("日付,タイトル,参加者数,作成者");

            for (ActivityResultEntity result : results) {
                long participantCount = participantRepository.countByActivityResultId(result.getId());
                writer.printf("%s,%s,%d,%s%n",
                        result.getActivityDate(),
                        escapeCsv(result.getTitle()),
                        participantCount,
                        result.getCreatedBy() != null ? result.getCreatedBy().toString() : "退会済みユーザー");
            }

            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException("CSVエクスポートに失敗しました", e);
        }
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
