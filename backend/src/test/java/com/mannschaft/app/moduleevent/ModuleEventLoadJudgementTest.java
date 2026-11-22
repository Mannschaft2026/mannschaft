package com.mannschaft.app.moduleevent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** M18判定の純関数テスト。外れ値1件では通り、p99超過・未供給多数では落ちることを固定する。 */
class ModuleEventLoadJudgementTest {
    private static final long SLOT = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    private static long[][] population(int n, int[] lateIndexes, long lateMs, int[] missingIndexes) {
        long[] planned = new long[n];
        long[] arrived = new long[n];
        for (int i = 0; i < n; i++) { planned[i] = 1_000L + i * SLOT; arrived[i] = planned[i] + 5 * MS; }
        for (int i : lateIndexes) arrived[i] = planned[i] + lateMs * MS;
        for (int i : missingIndexes) arrived[i] = 0;
        return new long[][] {planned, arrived};
    }

    @Test
    void 外れ値1件131msでも3000件のp99は通る() {
        long[][] p = population(3000, new int[] {1500}, 131, new int[0]);
        assertThat(ModuleEventLoadJudgement.supplyWithinSlot(p[0], p[1], SLOT)).isTrue();
        assertThat(ModuleEventLoadJudgement.lateCount(p[0], p[1], SLOT)).isEqualTo(1);
    }

    @Test
    void 遅延が全体の1パーセントを超えるとp99超過で落ちる() {
        int[] late = new int[31];
        for (int i = 0; i < late.length; i++) late[i] = i * 10;
        long[][] p = population(3000, late, 131, new int[0]);
        assertThat(ModuleEventLoadJudgement.supplyWithinSlot(p[0], p[1], SLOT)).isFalse();
    }

    @Test
    void 未供給は母集団に残り1パーセント超で落ちる() {
        long[][] ok = population(3000, new int[0], 0, new int[] {7});
        assertThat(ModuleEventLoadJudgement.supplyWithinSlot(ok[0], ok[1], SLOT)).isTrue();
        int[] missing = new int[31];
        for (int i = 0; i < missing.length; i++) missing[i] = i * 10;
        long[][] ng = population(3000, new int[0], 0, missing);
        assertThat(ModuleEventLoadJudgement.supplyWithinSlot(ng[0], ng[1], SLOT)).isFalse();
    }

    @Test
    void 予定より早い到着は供給不正で落ちる() {
        long[][] p = population(100, new int[0], 0, new int[0]);
        p[1][3] = p[0][3] - 1;
        assertThat(ModuleEventLoadJudgement.supplyWithinSlot(p[0], p[1], SLOT)).isFalse();
    }

    @Test
    void 遅延1件の隣bucketへのずれは許容しそれ以外のずれは許さない() {
        long[] buckets = new long[300];
        java.util.Arrays.fill(buckets, 10L);
        assertThat(ModuleEventLoadJudgement.bucketsMatch(buckets, 10, 0)).isTrue();
        buckets[5] = 9; buckets[6] = 11;
        assertThat(ModuleEventLoadJudgement.bucketsMatch(buckets, 10, 0)).isFalse();
        assertThat(ModuleEventLoadJudgement.bucketsMatch(buckets, 10, 1)).isTrue();
        buckets[7] = 12; buckets[8] = 8;
        assertThat(ModuleEventLoadJudgement.bucketsMatch(buckets, 10, 1)).isFalse();
    }

    @Test
    void bucket合計が欠けると落ちる() {
        long[] buckets = new long[300];
        java.util.Arrays.fill(buckets, 10L);
        buckets[0] = 9;
        assertThat(ModuleEventLoadJudgement.bucketsMatch(buckets, 10, 1)).isFalse();
    }

    @Test
    void nearestRankのp99は3000件で2970番目() {
        long[] sorted = new long[3000];
        for (int i = 0; i < sorted.length; i++) sorted[i] = i + 1;
        assertThat(ModuleEventLoadJudgement.percentile(sorted, 0.99)).isEqualTo(2970L);
    }
}
