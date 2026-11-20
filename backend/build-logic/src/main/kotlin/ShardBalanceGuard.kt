// =============================================================================
// ShardBalanceGuard: 重み表の鮮度と shard 偏りの番人（CMP-261111-0508）
// -----------------------------------------------------------------------------
// (i) 重み表に載っていない実行対象トップレベルクラスの数が閾値を超えたら失敗。
// (ii) 予測 max/avg 比（LPT 割り当て後の shard 合計重みの最大 / 平均）が閾値を超えたら失敗。
//      full（migration 込み）と migration 除外の両集合で評価する。
// 失敗メッセージには重み表の再生成手順（REGEN_COMMAND）を含める。
// 閾値はここの定数1か所に置き、テストもこの定数を参照する。
// 試練段階: シグネチャのみ。本体は出陣で実装する。
// =============================================================================
object ShardBalanceGuard {
    /** 重み表に未掲載の実行対象トップレベルクラス数の上限（この値ちょうどは合格）。 */
    const val MAX_UNLISTED_CLASSES: Int = 100

    /** 予測 max/avg 比の上限（この値ちょうどは合格）。 */
    const val MAX_IMBALANCE_RATIO: Double = 1.10

    /** 失敗メッセージに含める重み表の再生成手順。 */
    const val REGEN_COMMAND: String = "backend/scripts/regen-shard-weights.sh"

    /** 評価プロファイル名（失敗メッセージに含める）。 */
    const val PROFILE_FULL: String = "full"
    const val PROFILE_MIGRATION_EXCLUDED: String = "migration-excluded"

    /** 実行対象集合のうち重み表に載っていないクラス数。 */
    fun unlistedCount(executableTopLevelClasses: Set<String>, weights: Map<String, Double>): Int =
        executableTopLevelClasses.count { it !in weights }

    /** ShardAssignment.assignShards と同じ割り当て・同じ既定重みで求めた予測 max/avg 比。集合が空なら 1.0。 */
    fun predictedImbalanceRatio(
        executableTopLevelClasses: Set<String>,
        weights: Map<String, Double>,
        shardTotal: Int
    ): Double {
        if (executableTopLevelClasses.isEmpty()) return 1.0
        val fallback = ShardAssignment.defaultWeight(weights)
        val assignment = ShardAssignment.assignShards(executableTopLevelClasses, weights, shardTotal)
        val totals = DoubleArray(shardTotal)
        assignment.forEach { (fqcn, idx) -> totals[idx] += weights[fqcn] ?: fallback }
        val avg = totals.sum() / shardTotal
        if (avg <= 0.0) return 1.0
        return totals.max() / avg
    }

    /** 未掲載数の判定。合格なら null、不合格なら再生成手順を含むメッセージ。 */
    fun unlistedViolation(unlistedCount: Int): String? =
        if (unlistedCount <= MAX_UNLISTED_CLASSES) null
        else "重み表に未掲載の実行対象クラスが $unlistedCount 件あり、上限 $MAX_UNLISTED_CLASSES 件を超えています。" +
            "重み表を再生成してください: $REGEN_COMMAND"

    /** 偏り比の判定。合格なら null、不合格ならプロファイル名と再生成手順を含むメッセージ。 */
    fun imbalanceViolation(profile: String, ratio: Double): String? =
        if (ratio <= MAX_IMBALANCE_RATIO) null
        else "[$profile] 予測 max/avg 比 %.3f が上限 %.2f を超えています。".format(ratio, MAX_IMBALANCE_RATIO) +
            "重み表を再生成してください: $REGEN_COMMAND"

    /**
     * 番人本体。未掲載数（full 集合で1回）と、full / migration 除外の両集合の偏り比を評価し、
     * 違反メッセージの一覧を返す（空なら合格）。migration 判定は ShardAssignment.isMigrationReplayClass。
     */
    fun violations(
        executableTopLevelClasses: Set<String>,
        weights: Map<String, Double>,
        shardTotal: Int
    ): List<String> {
        val result = mutableListOf<String>()
        unlistedViolation(unlistedCount(executableTopLevelClasses, weights))?.let { result += it }
        imbalanceViolation(
            PROFILE_FULL,
            predictedImbalanceRatio(executableTopLevelClasses, weights, shardTotal)
        )?.let { result += it }
        val excluded = executableTopLevelClasses.filterNot { ShardAssignment.isMigrationReplayClass(it) }.toSet()
        imbalanceViolation(
            PROFILE_MIGRATION_EXCLUDED,
            predictedImbalanceRatio(excluded, weights, shardTotal)
        )?.let { result += it }
        return result
    }
}
