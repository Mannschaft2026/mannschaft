import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CMP-261111-0508 試練: 実行集合ベースの LPT 割り当て（AC-3a / AC-3b / AC-3c / AC-4）。
 */
class ShardAssignmentTest {

    private fun cls(path: String, isAbstract: Boolean = false, hasTests: Boolean = true) =
        CompiledTestClassFile(path, isAbstract, hasTests)

    /** 実行側・guard 側の双方が使う想定の .class ファイル群（ネスト・抽象・ヘルパー入り）。 */
    private val classFiles = listOf(
        cls("com/example/AlphaTest.class"),
        cls("com/example/AlphaTest\$Inner.class"),
        cls("com/example/BetaIT.class"),
        cls("com/example/GammaTest.class"),
        cls("com/example/AbstractBaseTest.class", isAbstract = true),
        cls("com/example/TestFixtures.class", hasTests = false),
        cls("com/example/OuterOnlyNested.class", hasTests = false),
        cls("com/example/OuterOnlyNested\$Case.class"),
        cls("com/example/migration/FlywayReplayTest.class"),
    )

    private val weights = mapOf(
        "com.example.AlphaTest" to 30.0,
        "com.example.BetaIT" to 10.0,
        "com.example.migration.FlywayReplayTest" to 50.0,
    )

    @Test
    fun `AC-3a 実行側とcoverage guardが同じ集合を同じ関数に渡すと同じ割り当てになり全クラスがちょうど1shardで走る`() {
        val total = 8
        // 実行側（Test.exclude 側）
        val executionSet = ShardAssignment.executableTopLevelClasses(classFiles)
        val executionAssignment = ShardAssignment.assignShards(executionSet, weights, total)
        // coverage guard 側（別に .class を列挙した想定。列挙順は異なる）
        val guardSet = ShardAssignment.executableTopLevelClasses(classFiles.reversed())
        val guardAssignment = ShardAssignment.assignShards(guardSet, weights, total)

        assertEquals(executionSet, guardSet)
        assertEquals(executionAssignment, guardAssignment)
        assertEquals(executionSet, executionAssignment.keys)
        assertTrue(executionAssignment.values.all { it in 0 until total })

        // 実行対象トップレベルクラスに属する .class（ネスト含む）は、述語でちょうど1つの shard に入り、
        // その shard は割り当てと一致する（ネストはトップレベルと同じ shard）。
        classFiles
            .filter { ShardAssignment.fqcnTopLevelFromClassPath(it.path) in executionSet }
            .forEach { f ->
                val included = (0 until total).filter { k ->
                    !ShardAssignment.shouldExcludeForAssignedShard(f.path, false, k, total, executionAssignment)
                }
                assertEquals(
                    listOf(executionAssignment.getValue(ShardAssignment.fqcnTopLevelFromClassPath(f.path))),
                    included,
                    "${f.path} はちょうど1つの割り当て済み shard でだけ実行されること",
                )
            }
        // ディレクトリは除外しない
        assertTrue((0 until total).none { k ->
            ShardAssignment.shouldExcludeForAssignedShard("com/example", true, k, total, executionAssignment)
        })
    }

    @Test
    fun `AC-3b 列挙順だけ変えても割り当てが一致する（中央値で同重みの未掲載クラスが多数）`() {
        val table = mapOf("a.Heavy" to 40.0, "a.Mid" to 5.0, "a.Light" to 1.0) // 中央値 5.0
        // 未掲載 30 件（既定重み 5.0）＋掲載 a.Mid（5.0）で同重みが多数並ぶ
        val classes = table.keys + (0 until 30).map { "b.Unlisted%02d".format(it) }
        val base = ShardAssignment.assignShards(classes.toList(), table, 8)
        assertEquals(classes.toSet(), base.keys)
        val orders = listOf(
            classes.reversed(),
            classes.sortedBy { it.reversed() },
            classes.shuffled(Random(1)),
            classes.shuffled(Random(42)),
        )
        orders.forEach { order ->
            assertEquals(base, ShardAssignment.assignShards(order, table, 8), "列挙順 $order で割り当てが変わった")
            assertEquals(base, ShardAssignment.assignShards(LinkedHashSet(order), table, 8))
        }
    }

    @Test
    fun `AC-3c 未掲載クラスの既定重みは重み表の中央値（奇数件）`() {
        assertEquals(2.0, ShardAssignment.defaultWeight(mapOf("a.A" to 9.0, "a.B" to 1.0, "a.C" to 2.0)))
    }

    @Test
    fun `AC-3c 未掲載クラスの既定重みは重み表の中央値（偶数件は中央2値の平均）`() {
        assertEquals(
            3.0,
            ShardAssignment.defaultWeight(mapOf("a.A" to 9.0, "a.B" to 1.0, "a.C" to 2.0, "a.D" to 4.0)),
        )
    }

    @Test
    fun `AC-3c 未掲載クラスはハッシュではなく既定重み付きでLPTに参加し最も軽いshardへ入る`() {
        // 中央値 = (4 + 5) / 2 = 4.5。LPT 順: A100, C5, X4.5, D4, B3
        val table = mapOf("a.A" to 100.0, "a.B" to 3.0, "a.C" to 5.0, "a.D" to 4.0)
        // X が入るべき shard は 2（A→0, C→1 の後、最も軽いのは 2）。ハッシュでは 2 にならない名前を選ぶ。
        val unlisted = (0 until 50).map { "x.Unlisted$it" }.first { ShardAssignment.hashBucket(it, 3) != 2 }
        val assignment = ShardAssignment.assignShards(table.keys + unlisted, table, 3)
        assertEquals(2, assignment[unlisted], "未掲載クラスは LPT で最も軽い shard へ入ること")
        assertNotEquals(ShardAssignment.hashBucket(unlisted, 3), assignment[unlisted])
        assertEquals(mapOf("a.A" to 0, "a.C" to 1, unlisted to 2, "a.D" to 2, "a.B" to 1), assignment)
    }

    @Test
    fun `AC-4 重み表が空なら既定重みは固定値1点0`() {
        assertEquals(ShardAssignment.FALLBACK_DEFAULT_WEIGHT, ShardAssignment.defaultWeight(emptyMap()))
        assertEquals(1.0, ShardAssignment.FALLBACK_DEFAULT_WEIGHT)
    }

    @Test
    fun `AC-4 全重み0なら既定重みは固定値1点0`() {
        assertEquals(1.0, ShardAssignment.defaultWeight(mapOf("a.A" to 0.0, "a.B" to 0.0, "a.C" to 0.0)))
    }

    @Test
    fun `AC-4 負値・NaN・Infinityを含む重み表の読み込みはクラス名入りで例外`(@TempDir dir: File) {
        val cases = mapOf(
            "com.example.NegativeTest" to "-1.5",
            "com.example.NanTest" to "NaN",
            "com.example.InfTest" to "Infinity",
            "com.example.NegInfTest" to "-Infinity",
        )
        cases.forEach { (fqcn, value) ->
            val f = File(dir, "${fqcn}.properties")
            f.writeText("# header\ncom.example.OkTest=1.000\n$fqcn=$value\n")
            val e = assertFailsWith<IllegalArgumentException>("$fqcn=$value は拒否されること") {
                ShardAssignment.loadWeights(f)
            }
            assertTrue(e.message.orEmpty().contains(fqcn), "例外メッセージにクラス名 $fqcn が含まれること: ${e.message}")
        }
    }

    @Test
    fun `AC-4 実行集合0件で例外にならず空の割り当て`() {
        assertEquals(emptyMap(), ShardAssignment.assignShards(emptySet(), mapOf("a.A" to 1.0), 8))
        assertEquals(emptyMap(), ShardAssignment.assignShards(emptyList(), emptyMap(), 8))
    }

    @Test
    fun `AC-4 shard数がクラス数より多くても例外にならず空shardが生じてよい`() {
        val assignment = ShardAssignment.assignShards(listOf("a.A", "a.B", "a.C"), mapOf("a.A" to 2.0), 8)
        assertEquals(setOf("a.A", "a.B", "a.C"), assignment.keys)
        assertTrue(assignment.values.all { it in 0 until 8 })
        assertEquals(3, assignment.values.toSet().size, "3 クラスは別々の shard に入る（残り5 shard は空）")
    }

    @Test
    fun `AC-4 total=1なら全クラスがshard0`() {
        val classes = listOf("a.A", "a.B", "a.C", "a.migration.FlywayX")
        val assignment = ShardAssignment.assignShards(classes, mapOf("a.A" to 5.0, "a.B" to 1.0), 1)
        assertEquals(classes.associateWith { 0 }, assignment)
    }

    // ---- 検分の差し戻し対応（CMP-261111-0508） ----

    @Test
    fun `検分P1 実行集合に入らないクラスもちょうど1つのshardにだけ含まれる（全shardで重複実行されない）`() {
        val total = 8
        // 走査が取りこぼした本物のテスト（メタアノテーションだけを持つ想定）・抽象・ヘルパーは実行集合に入らない
        val files = listOf(
            cls("com/example/AlphaTest.class"),
            cls("com/example/MissedByScanTest.class", hasTests = false),
            cls("com/example/MissedByScanTest\$Inner.class", hasTests = false),
            cls("com/example/AbstractBaseTest.class", isAbstract = true),
            cls("com/example/TestFixtures.class", hasTests = false),
        )
        val set = ShardAssignment.runnableTopLevelClasses(files, false)
        assertEquals(setOf("com.example.AlphaTest"), set)
        val assignment = ShardAssignment.assignShards(set, emptyMap(), total)
        files.forEach { f ->
            val included = (0 until total).filter { k ->
                !ShardAssignment.shouldExcludeForAssignedShard(f.path, false, k, total, assignment)
            }
            assertEquals(1, included.size, "${f.path} はちょうど1つの shard だけで実行されること: $included")
        }
        // 集合外のクラスは従来どおり FQCN ハッシュの shard
        val missed = (0 until total).filter { k ->
            !ShardAssignment.shouldExcludeForAssignedShard("com/example/MissedByScanTest.class", false, k, total, assignment)
        }
        assertEquals(listOf(ShardAssignment.hashBucket("com.example.MissedByScanTest", total)), missed)
        // ネストクラスはトップレベルと同じ shard
        val nested = (0 until total).filter { k ->
            !ShardAssignment.shouldExcludeForAssignedShard("com/example/MissedByScanTest\$Inner.class", false, k, total, assignment)
        }
        assertEquals(missed, nested)
    }

    @Test
    fun `検分P2 通常testの実行集合はAnalyzeClassesだけのクラスを数えず、archUnitTestの集合は従来どおり数える`() {
        val files = listOf(
            cls("com/example/AlphaTest.class"),
            // @AnalyzeClasses だけ（hasTestMethods=true だが jupiter のテストはない）
            CompiledTestClassFile("com/example/SomeArchTest.class", false, hasTestMethods = true, hasJupiterTests = false),
            // @AnalyzeClasses かつ jupiter のテストも持つ
            CompiledTestClassFile("com/example/BothTest.class", false, hasTestMethods = true, hasJupiterTests = true),
        )
        assertEquals(
            setOf("com.example.AlphaTest", "com.example.BothTest"),
            ShardAssignment.runnableTopLevelClasses(files, false),
        )
        assertEquals(
            setOf("com.example.AlphaTest", "com.example.SomeArchTest", "com.example.BothTest"),
            ShardAssignment.runnableTopLevelClasses(files, false, archUnitTask = true),
        )
        // 通常 test で外したクラスも、割り当て表外としてちょうど1 shard に入る（全 shard で重複しない）
        val assignment = ShardAssignment.assignShards(ShardAssignment.runnableTopLevelClasses(files, false), emptyMap(), 8)
        val included = (0 until 8).filter { k ->
            !ShardAssignment.shouldExcludeForAssignedShard("com/example/SomeArchTest.class", false, k, 8, assignment)
        }
        assertEquals(1, included.size)
    }

    @Test
    fun `検分P3b 抽象ネストクラスだけにテストがある外側は数えず、具象ネストのテストは数える`() {
        val files = listOf(
            cls("com/example/OnlyAbstractNested.class", hasTests = false),
            cls("com/example/OnlyAbstractNested\$Base.class", isAbstract = true),
            cls("com/example/NestedConcrete.class", hasTests = false),
            cls("com/example/NestedConcrete\$Case.class"),
        )
        assertEquals(setOf("com.example.NestedConcrete"), ShardAssignment.runnableTopLevelClasses(files, false))
    }
}
