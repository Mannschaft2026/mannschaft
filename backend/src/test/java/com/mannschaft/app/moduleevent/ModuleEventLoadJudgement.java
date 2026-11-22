package com.mannschaft.app.moduleevent;

import java.util.Arrays;

/**
 * M18の統計判定（純関数）。外れ値1件で落とさず、仕様の指標（nearest-rank p99）で判定する。
 * 根拠: docs/evidence/CMP-261028-2251/m18-remote-ci-30744.md「percentileと計測範囲」
 * （全母集団nearest-rank、未供給・未完了はLong.MAX_VALUEで母集団に残す）。
 * 供給（到着遅延）もlatencyと同じ全母集団p99で判定する。1件の遅延（CIランナーのGC/CPU競合）で全体を落とさない。
 */
final class ModuleEventLoadJudgement {
    private ModuleEventLoadJudgement() { }

    /** 昇順ソート済み配列のnearest-rank。ceil(N*q)-1番目。 */
    static long percentile(long[] sorted, double rank) {
        return sorted[(int) Math.ceil(rank * sorted.length) - 1];
    }

    /** 未供給（arrived==0）はLong.MAX_VALUEとして母集団に残した到着遅延の昇順配列を作る。 */
    static long[] sortedArrivalDelays(long[] planned, long[] arrived) {
        long[] delays = new long[planned.length];
        for (int i = 0; i < planned.length; i++) delays[i] = arrived[i] == 0 ? Long.MAX_VALUE : arrived[i] - planned[i];
        Arrays.sort(delays);
        return delays;
    }

    /** 全母集団の到着遅延p99がslot幅未満で、かつ全件が予定時刻以後に到着していること。 */
    static boolean supplyWithinSlot(long[] planned, long[] arrived, long slotNs) {
        long[] delays = sortedArrivalDelays(planned, arrived);
        return delays[0] >= 0 && percentile(delays, 0.99) < slotNs;
    }

    /** slot幅以上遅れた件数（未供給を含む）。 */
    static int lateCount(long[] planned, long[] arrived, long slotNs) {
        int late = 0;
        for (long delay : sortedArrivalDelays(planned, arrived)) if (delay >= slotNs) late++;
        return late;
    }

    /**
     * 各秒bucketが毎秒perSecond件であること。遅れた1件は隣のbucketへ1件ずらすだけなので、
     * 許容は遅延件数lateCountに限る（それ以外のずれは供給不良として落とす）。合計は常に厳密一致。
     */
    static boolean bucketsMatch(long[] buckets, long perSecond, int lateCount) {
        long sum = 0;
        for (long b : buckets) {
            if (Math.abs(b - perSecond) > lateCount) return false;
            sum += b;
        }
        return sum == perSecond * buckets.length;
    }
}
