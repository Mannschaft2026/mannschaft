import java.io.File

// =============================================================================
// ShardAssignment: CI テストシャード振り分けの単一実装（実行フィルタと検証タスクで共用）
// -----------------------------------------------------------------------------
// 【なぜ object に一本化するか（改訂履歴あり）】
//   第1版: 「実際にテストを除外するロジック」と「検証タスク（verifyShardCoverage）の
//   割当計算ロジック」が別実装で存在し、sum==総数 が構造的に自明な偽 green だった。
//
//   第2版（本版）: 第1版の是正で isIncluded() を共用関数化したが、実行側は
//   「path 正規化 → isIncluded 呼び出し → 結果を外側で ! 反転」という組み立てを
//   Test.exclude { } 側にまだ残していたため、正規化のバグや ! の反転そのものは
//   検証タスク側の別実装（自前で FQCN を復元）では検出できなかった。
//   本版では **shouldExcludeForShard() / shouldExcludeMigrationReplay() が
//   「パス正規化 → bucket 判定 → 否定」を丸ごと1つの関数に含み、
//   Test.exclude { } はこの関数の戻り値をそのまま返すだけ**にする。
//   verifyShardCoverage もこの同じ関数を、実際にコンパイルされた .class ファイルの
//   生パス文字列に対して直接呼ぶ。これにより正規化・反転のどちらが壊れても
//   実行側・検証側が同じ壊れ方を共有し、検証が確実に fail する。
// =============================================================================
object ShardAssignment {
    /** 安定ハッシュ関数（重み表に無いクラスのフォールバック用） */
    fun hashBucket(fqcnTopLevel: String, shardTotal: Int): Int =
        ((fqcnTopLevel.hashCode().toLong() and 0xFFFFFFFFL) % shardTotal).toInt()

    /** shard-weights.properties を読み込む（存在しなければ空 map） */
    fun loadWeights(weightsFile: File): Map<String, Double> {
        if (!weightsFile.exists()) return emptyMap()
        return weightsFile.readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 0) return@mapNotNull null
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().toDoubleOrNull() ?: return@mapNotNull null
                // 負値・NaN・Infinity は LPT を壊す（偏り・不定動作）ので、黙って通さずクラス名付きで拒否する
                require(value.isFinite() && value >= 0.0) {
                    "shard-weights の値が不正です（0 以上の有限値のみ許可）: $key=$value (${weightsFile.name})"
                }
                key to value
            }
            .toMap()
    }

    /**
     * 貪欲法（Longest Processing Time first）で重み表のクラスを shard へ割り付ける。
     * 重い順にソートし、その時点で合計時間が最小の shard へ割り当てる
     * （理論上界は最適解の 4/3 倍）。weights が空なら空 map を返す
     * （＝呼び出し側は shouldExcludeForShard() で自動的に安定ハッシュへフォールバックする）。
     *
     * 【呼び出し側の責務】渡す weights は「実際にその CI 実行で走るクラス集合」に
     * 限定すること（-PexcludeMigrationTests=true の場合は migration+Flyway クラスを
     * 事前に除いた weights を渡す）。除外予定のクラスまで貪欲法の対象に含めると、
     * それらが消費する「重み予算」が実行対象クラスの均等化を妨げ、実際に走る
     * プロファイルの偏りが悪化する（migration 込みの重みだけで最適化すると、
     * migration 除外時＝大多数の通常 PR で逆に不均等化する回帰を招く）。
     */
    fun buildGreedyAssignment(weights: Map<String, Double>, shardTotal: Int): Map<String, Int> {
        if (weights.isEmpty()) return emptyMap()
        val shardTotals = DoubleArray(shardTotal)
        val assignment = LinkedHashMap<String, Int>()
        weights.entries
            .sortedByDescending { it.value }
            .forEach { (fqcn, seconds) ->
                var minIdx = 0
                for (i in 1 until shardTotal) {
                    if (shardTotals[i] < shardTotals[minIdx]) minIdx = i
                }
                assignment[fqcn] = minIdx
                shardTotals[minIdx] += seconds
            }
        return assignment
    }

    /** 与えられたトップレベル FQCN が属する shard index（0..shardTotal-1）を決定する。 */
    private fun bucketOf(fqcnTopLevel: String, shardTotal: Int, weightedBucketOf: Map<String, Int>): Int =
        weightedBucketOf[fqcnTopLevel] ?: hashBucket(fqcnTopLevel, shardTotal)

    /** .class ファイルの相対 path（"/" 区切り）からトップレベル FQCN を復元する。 */
    fun fqcnTopLevelFromClassPath(path: String): String =
        path.removeSuffix(".class").replace('/', '.').substringBefore('$')

    /**
     * 【単一の述語関数・その1】CI テストシャード分割の exclude 判定。
     * パス正規化 → bucket 判定 → 否定までを丸ごと含む。build.gradle.kts の
     * Test.exclude { } はこの関数の戻り値をそのまま返すだけにし、外側で
     * 追加の "!" 等を書かない（別実装・反転漏れの温床を断つ）。
     * verifyShardCoverage も実クラスファイルの生パスに対して直接この関数を呼ぶ。
     */
    fun shouldExcludeForShard(
        path: String,
        isDirectory: Boolean,
        shardIndex: Int,
        shardTotal: Int,
        weightedBucketOf: Map<String, Int>
    ): Boolean {
        if (isDirectory) return false
        if (!path.endsWith(".class")) return false
        val fqcnTopLevel = fqcnTopLevelFromClassPath(path)
        return bucketOf(fqcnTopLevel, shardTotal, weightedBucketOf) != shardIndex
    }

    /** Flyway マイグレーション再生テストか（"migration" パッケージ配下 かつ クラス名に Flyway を含む）。 */
    fun isMigrationReplayClass(fqcnTopLevel: String): Boolean {
        val simpleName = fqcnTopLevel.substringAfterLast('.')
        return fqcnTopLevel.contains(".migration.") && simpleName.contains("Flyway")
    }

    /**
     * 【単一の述語関数・その2】Flyway マイグレーション再生テストの exclude 判定。
     * -PexcludeMigrationTests=true のときに使う。こちらもパス正規化を関数内に含み、
     * Test.exclude { } と verifyShardCoverage の両方が直接呼ぶ。
     */
    fun shouldExcludeMigrationReplay(path: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        if (!path.endsWith(".class")) return false
        return isMigrationReplayClass(fqcnTopLevelFromClassPath(path))
    }

    // -------------------------------------------------------------------------
    // CMP-261111-0508: 実行集合ベースの割り当て（試練で置いたシグネチャ。本体は出陣で実装）
    // 呼び出し側（Test.exclude 側と verifyShardCoverage）は「実行対象のトップレベルクラス集合」を
    // executableTopLevelClasses() で同じ方法で作り、assignShards() に重み表と共に渡す。
    // 重み表に無いクラスはハッシュではなく既定重み（defaultWeight）付きで LPT に参加させる。
    // -------------------------------------------------------------------------

    /** 重み表が空、または全重みが 0 のときに使う既定重み（秒）。 */
    const val FALLBACK_DEFAULT_WEIGHT: Double = 1.0

    /**
     * 未掲載クラスの既定重み = 重み表の値の中央値（件数が偶数なら中央2値の平均）。
     * 重み表が空・全重み 0 のときは [FALLBACK_DEFAULT_WEIGHT]。
     */
    fun defaultWeight(weights: Map<String, Double>): Double {
        val sorted = weights.values.sorted()
        if (sorted.isEmpty()) return FALLBACK_DEFAULT_WEIGHT
        val n = sorted.size
        val median = if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        return if (median > 0.0) median else FALLBACK_DEFAULT_WEIGHT
    }

    /**
     * 実行対象のトップレベルクラス集合を LPT で shardTotal 個へ割り付ける。
     * 重みは weights[fqcn]、未掲載なら [defaultWeight]。
     * 結果は集合の列挙順に依存しない（同重みは FQCN 昇順、合計が同じ shard は index の小さい方を選ぶ）。
     * 戻り値のキーは executableTopLevelClasses と一致し、値は 0..shardTotal-1。
     * 集合が空なら空 map。shardTotal がクラス数より大きければ空 shard が生じてよい。
     */
    fun assignShards(
        executableTopLevelClasses: Collection<String>,
        weights: Map<String, Double>,
        shardTotal: Int
    ): Map<String, Int> {
        val classes = executableTopLevelClasses.toSet()
        if (classes.isEmpty()) return emptyMap()
        val fallback = defaultWeight(weights)
        val shardTotals = DoubleArray(shardTotal)
        val assignment = LinkedHashMap<String, Int>()
        // 重い順、同重みは FQCN 昇順（列挙順に依存させない）
        classes
            .map { it to (weights[it] ?: fallback) }
            .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first })
            .forEach { (fqcn, seconds) ->
                var minIdx = 0
                for (i in 1 until shardTotal) {
                    if (shardTotals[i] < shardTotals[minIdx]) minIdx = i
                }
                assignment[fqcn] = minIdx
                shardTotals[minIdx] += seconds
            }
        return assignment
    }

    /**
     * [assignShards] の結果を使う exclude 述語（Test.exclude と verifyShardCoverage が共用する）。
     * パス正規化（ネストクラスはトップレベルへ寄せる）→ 割り当て参照 → 否定 までを含む。
     *
     * 【どのクラスも必ずただ 1 つの shard】実行対象集合に入らなかったクラス（走査の取りこぼし・抽象・ヘルパー・
     * 別タスクが実行するクラス）は、割り当て表に無い。以前はこれを「どの shard でも除外しない」としていたため、
     * 走査が取りこぼした本物のテストが全 shard で重複実行されて、番人にも検出されなかった（検分P1）。
     * 今は割り当て表に無いクラスも従来の FQCN ハッシュ（[hashBucket]）でちょうど 1 つの shard へ決定的に入れる。
     */
    fun shouldExcludeForAssignedShard(
        path: String,
        isDirectory: Boolean,
        shardIndex: Int,
        shardTotal: Int,
        assignment: Map<String, Int>
    ): Boolean {
        if (isDirectory) return false
        if (!path.endsWith(".class")) return false
        val fqcnTopLevel = fqcnTopLevelFromClassPath(path)
        return bucketOf(fqcnTopLevel, shardTotal, assignment) != shardIndex
    }

    /**
     * コンパイル済みテストクラスファイル群から「実行対象のトップレベルクラス集合」を作る。
     * 数え方: トップレベルクラス自身が抽象でなく、かつ自身またはそのネストクラスのうち**抽象でない**ものが
     * テストメソッドを持つもの（抽象 static ネストクラスだけにテストがある外側は JUnit が検出しないので数えない。
     * 具象のサブクラスが継承したテストは、そのサブクラスの hasTestMethods として数える）。
     * ネストクラスは個別に数えない。抽象クラス・ヘルパー（テストメソッドなし）は含めない。
     * [jupiterOnly] が true なら、ArchUnit の @AnalyzeClasses だけで「テストあり」になるクラスを数えない
     * （通常の `:test` は junit-jupiter エンジンのみで、@AnalyzeClasses は `archUnitTest` が実行するため）。
     */
    fun executableTopLevelClasses(
        classFiles: Collection<CompiledTestClassFile>,
        jupiterOnly: Boolean = false
    ): Set<String> {
        val byTop = classFiles.groupBy { fqcnTopLevelFromClassPath(it.path) }
        return byTop.filter { (top, files) ->
            val topFile = files.firstOrNull { fqcnTopLevelFromClassPath(it.path) == top && !it.path.contains('$') }
            topFile != null && !topFile.isAbstract &&
                files.any { !it.isAbstract && (if (jupiterOnly) it.hasJupiterTests else it.hasTestMethods) }
        }.keys.toSortedSet()
    }

    /** 全 runner 必須の専用 stage（archUnitFreezeStoreIntegrityTest）として別走するため、通常の shard 割り当てから外すクラス。 */
    const val FREEZE_INTEGRITY_CLASS: String = "com.mannschaft.app.common.architecture.ArchUnitFreezeStoreIntegrityTest"

    /**
     * 「この実行で実際に走るトップレベルクラス集合」。Test.exclude 側と verifyShardCoverage が必ずこの関数で作る。
     * [executableTopLevelClasses] から専用 stage のクラスを外し、-PexcludeMigrationTests=true なら
     * migration+Flyway クラスも外す（従来の「除外予定のクラスを貪欲法の対象に含めない」意味を保つ）。
     */
    fun runnableTopLevelClasses(
        classFiles: Collection<CompiledTestClassFile>,
        excludeMigrationTests: Boolean,
        archUnitTask: Boolean = false
    ): Set<String> =
        // 通常 :test（archUnitTask=false）は @AnalyzeClasses だけのクラスを数えない（archUnitTest が実行する）。
        // archUnitTest 側の集合は従来どおり（@AnalyzeClasses も数える）。
        executableTopLevelClasses(classFiles, jupiterOnly = !archUnitTask)
            .filter { it != FREEZE_INTEGRITY_CLASS }
            .filter { !excludeMigrationTests || !isMigrationReplayClass(it) }
            .toSortedSet()
}

/**
 * 実行集合の算出に使う「コンパイル済みテストクラスファイル1件」の情報（CMP-261111-0508）。
 * path は Gradle FileTreeElement.path と同じ "/" 区切りの相対パス（".class" 付き）。
 * hasTestMethods は継承したテストメソッドも含めて判定した値（@AnalyzeClasses 付きも true）。
 * hasJupiterTests は JUnit Jupiter 系のテストメソッドだけを数えた値（既定は hasTestMethods と同じ）。
 */
data class CompiledTestClassFile(
    val path: String,
    val isAbstract: Boolean,
    val hasTestMethods: Boolean,
    val hasJupiterTests: Boolean = hasTestMethods
)

/** verifyShardCoverage が扱う「コンパイル済み .class ファイル1件」の生パスとトップレベルFQCN。 */
class ShardCoverageClassFile(val path: String, val topLevelFqcn: String)

