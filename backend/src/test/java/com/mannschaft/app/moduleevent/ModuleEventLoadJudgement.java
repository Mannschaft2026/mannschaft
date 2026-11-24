package com.mannschaft.app.moduleevent;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

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
     * 窓[start, start+seconds秒)の到着を秒bucketへ数え、窓の終端以後の到着は末尾の超過bucket（index=seconds）に保持する。
     * 末尾slotの遅延到着を捨てると合計が欠けて単発遅延で落ちるため、母集団から落とさない。
     * 未供給（arrived==0）と開始前の到着はどのbucketにも入らない（合計の厳密一致で落ちる）。
     */
    static long[] arrivalBuckets(long start, long[] arrived, int seconds) {
        long[] buckets = new long[seconds + 1];
        long second = TimeUnit.SECONDS.toNanos(1);
        for (long a : arrived) {
            if (a == 0 || a < start) continue;
            buckets[(int) Math.min((a - start) / second, seconds)]++;
        }
        return buckets;
    }

    /**
     * 各秒bucketが毎秒perSecond件であること（bucketsは{@link #arrivalBuckets}の戻り値。末尾は窓外の超過bucket）。
     * 遅れた1件は隣のbucket（末尾slotなら超過bucket）へ1件ずらすだけなので、窓内各bucketのずれと超過件数の許容は
     * 遅延件数lateCountに限る（それ以外のずれは供給不良として落とす）。超過を含む合計は常に厳密一致。
     */
    static boolean bucketsMatch(long[] buckets, long perSecond, int lateCount) {
        int seconds = buckets.length - 1;
        long sum = 0;
        for (int i = 0; i < seconds; i++) {
            if (Math.abs(buckets[i] - perSecond) > lateCount) return false;
            sum += buckets[i];
        }
        long overflow = buckets[seconds];
        return overflow <= lateCount && sum + overflow == perSecond * seconds;
    }
}
