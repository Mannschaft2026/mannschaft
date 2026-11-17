package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.dto.EffortStatsCriteria;
import com.mannschaft.app.effortrecord.dto.EffortStatsResponse;
import com.mannschaft.app.effortrecord.enums.EffortAggregation;
import com.mannschaft.app.effortrecord.enums.EffortChartType;
import com.mannschaft.app.effortrecord.enums.EffortFieldType;
import com.mannschaft.app.effortrecord.enums.EffortGrain;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.enums.EffortStatsEmptyReason;
import com.mannschaft.app.effortrecord.repository.EffortRecordQueryRepository;
import com.mannschaft.app.effortrecord.repository.EffortStatsRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F06.4.1 個人集計 Service。グラフ許可表（API 契約 §5.1）に従い BAR/LINE/PIE を集計する。
 *
 * <p>集計は {@link BigDecimal} で行い内部では丸めない。出力だけ小数 2 桁の 10 進文字列にする（AC-17）。
 * 記録日は {@code recordedOn}（LocalDate）のまま扱い、TZ・DST・閏日の影響を受けない（AC-14）。
 * series は「定義 UUID × field key × semantic × unit」で、原本が凍結した revision の同 field が
 * 現行の field と semantic・unit とも一致する行だけを同一 series として合算する（異なる series は合算しない）。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortStatsService {

    /** 「科目未指定」カテゴリの reserved key。実在の科目 key は {@code subject:} 接頭辞を付けるため衝突しない。 */
    public static final String UNSPECIFIED_SUBJECT_KEY = "__unspecified__";
    private static final String SUBJECT_KEY_PREFIX = "subject:";
    private static final int MAX_DAY_GRAIN_DAYS = 62;
    private static final int MAX_DAYS = 366;

    private final EffortRecordQueryRepository queries;
    private final EffortDefinitionService definitions;
    private final EffortSnapshotCodec codec;
    private final EffortRecordMetrics metrics;

    /** 本人の記録を条件どおり集計する。chart と semantic の不適合・期間上限超過は COMMON_001（400）。 */
    @Transactional(readOnly = true)
    public EffortStatsResponse aggregate(Long ownerUserId, EffortStatsCriteria criteria) {
        return metrics.measure("stats", () -> compute(ownerUserId, criteria));
    }

    private EffortStatsResponse compute(Long ownerUserId, EffortStatsCriteria c) {
        long days = ChronoUnit.DAYS.between(c.dateFrom(), c.dateTo()) + 1;
        if (days < 1) {
            throw invalid("date_from", "date_from は date_to 以前で指定してください");
        }
        if (days > MAX_DAYS) {
            throw invalid("date_to", "集計期間は最大 " + MAX_DAYS + " 日です");
        }
        EffortGrain grain = c.grain() != null ? c.grain() : (days <= MAX_DAY_GRAIN_DAYS ? EffortGrain.DAY : EffortGrain.MONTH);
        if (grain == EffortGrain.DAY && days > MAX_DAY_GRAIN_DAYS) {
            throw invalid("grain", "日単位の集計期間は最大 " + MAX_DAY_GRAIN_DAYS + " 日です");
        }

        EffortDefinitionService.ResolvedDefinition resolved = definitions.findCurrent(c.definitionUuid())
                .filter(d -> d.definition().getSourceType() == EffortSourceType.PRESET
                        || queries.existsForOwner(ownerUserId, c.definitionUuid()))
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        EffortDefinitionSnapshot snapshot = resolved.snapshot();
        EffortFieldDefinition field = snapshot.fields().stream()
                .filter(f -> f.fieldKey().equals(c.fieldKey())).findFirst()
                .orElseThrow(() -> invalid("field_key", "定義にない項目です"));
        EffortFieldDefinition subjectField = requireChartAllowed(c.chart(), snapshot, field);

        List<EffortStatsRow> rows = queries.findStatsRows(ownerUserId, c.definitionUuid(), c.dateFrom(), c.dateTo());
        Map<Integer, EffortDefinitionSnapshot> revisions = new HashMap<>();
        revisions.put(resolved.revision(), snapshot);

        List<Sample> samples = new ArrayList<>();
        for (EffortStatsRow row : rows) {
            EffortDefinitionSnapshot frozen = revisions.computeIfAbsent(row.definitionRevision(),
                    revision -> definitions.findRevision(c.definitionUuid(), revision).orElse(null));
            if (frozen == null || !sameSeries(frozen, field)) {
                continue;
            }
            Map<String, String> values = codec.parseValues(row.valuesJson());
            BigDecimal number = parseNumber(values.get(field.fieldKey()));
            samples.add(new Sample(row, number, subjectField == null ? null : values.get(subjectField.fieldKey()),
                    subjectField == null ? null : frozen));
        }

        return switch (c.chart()) {
            case BAR -> bucketed(c, grain, field, samples, false);
            case LINE -> bucketed(c, grain, field, samples, true);
            case PIE -> pie(c, grain, field, subjectField, samples);
        };
    }

    // ───────────────────────────── 許可表 §5.1 ─────────────────────────────

    /** chart × semantic の可否を検査する。PIE のとき科目 field を返す（それ以外は null）。 */
    private EffortFieldDefinition requireChartAllowed(EffortChartType chart, EffortDefinitionSnapshot snapshot,
                                                      EffortFieldDefinition field) {
        EffortSemantic semantic = field.semantic();
        boolean numeric = field.type() == EffortFieldType.NUMBER;
        switch (chart) {
            case BAR -> {
                boolean allowed = numeric && (semantic == EffortSemantic.DURATION
                        || semantic == EffortSemantic.NUMBER && field.aggregation() == EffortAggregation.SUM);
                if (!allowed) {
                    throw notAllowed();
                }
                return null;
            }
            case LINE -> {
                if (!(numeric && semantic == EffortSemantic.WEIGHT)) {
                    throw notAllowed();
                }
                return null;
            }
            case PIE -> {
                boolean isDuration = numeric && semantic == EffortSemantic.DURATION
                        && field.fieldKey().equals(snapshot.durationFieldKey());
                EffortFieldDefinition subject = snapshot.subjectFieldKey() == null ? null
                        : snapshot.fields().stream().filter(f -> f.fieldKey().equals(snapshot.subjectFieldKey()))
                        .findFirst().orElse(null);
                if (!isDuration || subject == null || subject.semantic() != EffortSemantic.SUBJECT) {
                    throw notAllowed();
                }
                return subject;
            }
            default -> throw notAllowed();
        }
    }

    private static boolean sameSeries(EffortDefinitionSnapshot frozen, EffortFieldDefinition current) {
        return frozen.fields().stream().anyMatch(f -> f.fieldKey().equals(current.fieldKey())
                && f.semantic() == current.semantic() && f.type() == current.type()
                && java.util.Objects.equals(f.unit(), current.unit()));
    }

    // ───────────────────────────── BAR / LINE ─────────────────────────────

    private EffortStatsResponse bucketed(EffortStatsCriteria c, EffortGrain grain, EffortFieldDefinition field,
                                         List<Sample> samples, boolean latest) {
        List<LocalDate[]> ranges = ranges(c.dateFrom(), c.dateTo(), grain);
        BigDecimal[] sums = new BigDecimal[ranges.size()];
        long[] counts = new long[ranges.size()];
        Sample[] newest = new Sample[ranges.size()];
        Comparator<Sample> order = Comparator.comparing((Sample s) -> s.row().createdAt())
                .thenComparing(s -> s.row().id());
        for (Sample sample : samples) {
            if (sample.number() == null) {
                continue;
            }
            int index = indexOf(c.dateFrom(), sample.row().recordedOn(), grain);
            counts[index]++;
            sums[index] = sums[index] == null ? sample.number() : sums[index].add(sample.number());
            if (newest[index] == null || order.compare(sample, newest[index]) > 0) {
                newest[index] = sample;
            }
        }
        List<EffortStatsResponse.Bucket> buckets = new ArrayList<>(ranges.size());
        BigDecimal total = null;
        long totalCount = 0;
        for (int i = 0; i < ranges.size(); i++) {
            BigDecimal value = counts[i] == 0 ? null : (latest ? newest[i].number() : sums[i]);
            buckets.add(new EffortStatsResponse.Bucket(ranges.get(i)[0], ranges.get(i)[1], format(value), counts[i]));
            totalCount += counts[i];
            if (!latest && sums[i] != null) {
                total = total == null ? sums[i] : total.add(sums[i]);
            }
        }
        EffortStatsEmptyReason reason = totalCount == 0 ? EffortStatsEmptyReason.NO_DATA
                : (!latest && total.signum() == 0 ? EffortStatsEmptyReason.ZERO_TOTAL : null);
        return new EffortStatsResponse(c.definitionUuid(), c.fieldKey(), field.unit(), field.semantic().name(),
                c.chart(), grain, c.dateFrom(), c.dateTo(), totalCount, buckets, List.of(), format(total), reason);
    }

    // ───────────────────────────── PIE ─────────────────────────────

    private EffortStatsResponse pie(EffortStatsCriteria c, EffortGrain grain, EffortFieldDefinition field,
                                    EffortFieldDefinition subjectField, List<Sample> samples) {
        Map<String, CategoryAccumulator> categories = new LinkedHashMap<>();
        BigDecimal total = null;
        long totalCount = 0;
        for (Sample sample : samples) {
            if (sample.number() == null) {
                continue;
            }
            String subject = sample.subject() == null || sample.subject().isBlank() ? null : sample.subject();
            String key = subject == null ? UNSPECIFIED_SUBJECT_KEY : SUBJECT_KEY_PREFIX + subject;
            CategoryAccumulator accumulator = categories.computeIfAbsent(key,
                    k -> new CategoryAccumulator(subject == null ? "" : label(sample.frozen(), subjectField, subject)));
            accumulator.add(sample.number());
            total = total == null ? sample.number() : total.add(sample.number());
            totalCount++;
        }
        List<EffortStatsResponse.Category> list = new ArrayList<>();
        categories.forEach((key, acc) -> list.add(new EffortStatsResponse.Category(key, acc.label,
                format(acc.sum), acc.count)));
        list.sort(Comparator.comparing((EffortStatsResponse.Category x) -> new BigDecimal(x.value())).reversed()
                .thenComparing(EffortStatsResponse.Category::key));
        EffortStatsEmptyReason reason = totalCount == 0 ? EffortStatsEmptyReason.NO_DATA
                : (total.signum() == 0 ? EffortStatsEmptyReason.ZERO_TOTAL : null);
        return new EffortStatsResponse(c.definitionUuid(), c.fieldKey(), field.unit(), field.semantic().name(),
                c.chart(), grain, c.dateFrom(), c.dateTo(), totalCount, List.of(), list, format(total), reason);
    }

    /** 科目の表示名。SELECT は当時の option label、TEXT は入力値そのもの。 */
    private static String label(EffortDefinitionSnapshot frozen, EffortFieldDefinition subjectField, String value) {
        if (frozen == null) {
            return value;
        }
        return frozen.fields().stream().filter(f -> f.fieldKey().equals(subjectField.fieldKey())).findFirst()
                .flatMap(f -> f.options() == null ? Optional.empty() : f.options().stream()
                        .filter(o -> o.key().equals(value)).findFirst())
                .map(EffortFieldDefinition.Option::label).orElse(value);
    }

    // ───────────────────────────── bucket ─────────────────────────────

    private static List<LocalDate[]> ranges(LocalDate from, LocalDate to, EffortGrain grain) {
        List<LocalDate[]> ranges = new ArrayList<>();
        if (grain == EffortGrain.DAY) {
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                ranges.add(new LocalDate[]{d, d});
            }
        } else {
            LocalDate start = from;
            while (!start.isAfter(to)) {
                LocalDate monthEnd = start.withDayOfMonth(start.lengthOfMonth());
                ranges.add(new LocalDate[]{start, monthEnd.isAfter(to) ? to : monthEnd});
                start = monthEnd.plusDays(1);
            }
        }
        return ranges;
    }

    private static int indexOf(LocalDate from, LocalDate day, EffortGrain grain) {
        return grain == EffortGrain.DAY
                ? (int) ChronoUnit.DAYS.between(from, day)
                : (int) ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(day));
    }

    // ───────────────────────────── 値 ─────────────────────────────

    private static BigDecimal parseNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 出力は小数 2 桁の 10 進文字列（内部は無丸め。入力は最大 2 桁のため丸めは起きない）。 */
    private static String format(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BusinessException notAllowed() {
        return invalid("chart", "この項目は指定のグラフで集計できません");
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
    }

    private record Sample(EffortStatsRow row, BigDecimal number, String subject, EffortDefinitionSnapshot frozen) {
    }

    private static final class CategoryAccumulator {
        private final String label;
        private BigDecimal sum = BigDecimal.ZERO;
        private long count;

        private CategoryAccumulator(String label) {
            this.label = label;
        }

        private void add(BigDecimal value) {
            sum = sum.add(value);
            count++;
        }
    }
}
