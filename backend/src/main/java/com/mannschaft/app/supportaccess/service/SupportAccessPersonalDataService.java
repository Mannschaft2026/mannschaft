package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.supportaccess.dto.SupportAccessGrantExportView;
import com.mannschaft.app.supportaccess.dto.SupportAccessLogView;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F10.9 本人の閲覧記録（設定画面と GDPR エクスポートの両方で使う）。運営者は表示名だけを出し、メールは出さない。
 */
@Service
public class SupportAccessPersonalDataService {

    private final SupportAccessLogRepository logRepository;
    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;

    public SupportAccessPersonalDataService(SupportAccessLogRepository logRepository,
                                            SupportAccessGrantRepository grantRepository,
                                            SupportAccessUserDirectory userDirectory) {
        this.logRepository = logRepository;
        this.grantRepository = grantRepository;
        this.userDirectory = userDirectory;
    }

    /**
     * GDPR エクスポート用: 本人宛の依頼・許可と、保護者として関わった依頼・許可（新しい順）。
     * 依頼理由（運営者が入力したプレーンテキスト）と運営者の表示名を含め、運営者のメールは含めない。
     */
    public List<SupportAccessGrantExportView> listGrantsForExport(Long userId) {
        Map<Long, String> names = new HashMap<>();
        List<SupportAccessGrantExportView> views = new ArrayList<>();
        for (SupportAccessGrantEntity g : grantRepository.findByTargetUserIdOrderByRequestedAtDesc(userId)) {
            views.add(toExportView(g, SupportAccessGrantExportView.ROLE_TARGET, names));
        }
        for (SupportAccessGrantEntity g : grantRepository.findByGuardianUserIdOrderByRequestedAtDesc(userId)) {
            if (!userId.equals(g.getTargetUserId())) {
                views.add(toExportView(g, SupportAccessGrantExportView.ROLE_GUARDIAN, names));
            }
        }
        views.sort(Comparator.comparing(SupportAccessGrantExportView::requestedAt).reversed());
        return views;
    }

    private SupportAccessGrantExportView toExportView(SupportAccessGrantEntity g, String role, Map<Long, String> names) {
        return new SupportAccessGrantExportView(
                g.getId(), role, g.getStatus().name(), g.getRequestReason(),
                names.computeIfAbsent(g.getOperatorUserId(), this::displayNameOf),
                g.getReferenceType(), g.getReferenceId(),
                g.getRequestedAt(), g.getRequestExpiresAt(), g.getDecidedAt(), g.getDenyComment(),
                g.getGuardianDecidedAt(), g.getExpiresAt(), g.getRevokedAt(),
                g.getEndedReason() == null ? null : g.getEndedReason().name(),
                g.getLastSessionStartedAt());
    }

    /** 本人の閲覧記録をすべて返す（新しい順・GDPR エクスポート用）。 */
    public List<SupportAccessLogView> listLogsForTarget(Long targetUserId) {
        return toViews(logRepository.findByTargetUserIdOrderByOccurredAtDesc(targetUserId));
    }

    /**
     * 本人の閲覧記録を1ページ返す（設定画面用・新しい順）。
     *
     * @param grantId 絞り込む grant（null で全件）
     * @param before  この日時より前（null で先頭から）
     * @param limit   取得件数
     */
    public List<SupportAccessLogView> listLogsForTarget(Long targetUserId, UUID grantId, Instant before, int limit) {
        Instant cursor = before != null ? before : Instant.parse("9999-01-01T00:00:00Z");
        PageRequest page = PageRequest.of(0, limit);
        List<SupportAccessLogEntity> rows = grantId == null
                ? logRepository.findByTargetUserIdAndOccurredAtBeforeOrderByOccurredAtDesc(targetUserId, cursor, page)
                : logRepository.findByTargetUserIdAndGrantIdAndOccurredAtBeforeOrderByOccurredAtDesc(
                        targetUserId, grantId, cursor, page);
        return toViews(rows);
    }

    /** 運営側の詳細用: grant 単位の閲覧記録（新しい順）。 */
    public List<SupportAccessLogView> listLogsForGrant(UUID grantId, Instant before, int limit) {
        Instant cursor = before != null ? before : Instant.parse("9999-01-01T00:00:00Z");
        return toViews(logRepository.findByGrantIdAndOccurredAtBeforeOrderByOccurredAtDesc(
                grantId, cursor, PageRequest.of(0, limit)));
    }

    private List<SupportAccessLogView> toViews(List<SupportAccessLogEntity> rows) {
        Map<Long, String> names = new HashMap<>();
        return rows.stream()
                .map(row -> new SupportAccessLogView(
                        row.getGrantId(),
                        row.getOccurredAt(),
                        names.computeIfAbsent(row.getOperatorUserId(), this::displayNameOf),
                        row.getEventKind().name(),
                        row.getRequestPath(),
                        row.getScopeType(),
                        row.getScopeId()))
                .toList();
    }

    private String displayNameOf(Long userId) {
        return userDirectory.findUser(userId).map(SupportAccessUserSnapshot::displayName).orElse(null);
    }
}
