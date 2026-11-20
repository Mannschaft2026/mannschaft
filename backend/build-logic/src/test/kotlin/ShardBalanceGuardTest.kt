import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CMP-261111-0508 試練: 重み表の鮮度・偏りの番人（AC-5）。
 * 閾値は ShardBalanceGuard の定数を参照し、テスト側に値を重複させない。
 */
class ShardBalanceGuardTest {

    private val max = ShardBalanceGuard.MAX_UNLISTED_CLASSES
    private val ratio = ShardBalanceGuard.MAX_IMBALANCE_RATIO

    @Test
    fun `AC-5i 未掲載数は閾値ちょうどで合格・閾値+1で失敗し再生成手順を示す`() {
        assertNull(ShardBalanceGuard.unlistedViolation(0))
        assertNull(ShardBalanceGuard.unlistedViolation(max))
        val message = assertNotNull(ShardBalanceGuard.unlistedViolation(max + 1))
        assertTrue(message.contains(ShardBalanceGuard.REGEN_COMMAND), message)
        assertEquals("backend/scripts/regen-shard-weights.sh", ShardBalanceGuard.REGEN_COMMAND)
    }

    @Test
    fun `AC-5i 数える対象はトップレベルの実行対象クラスのみ（ネスト・抽象・ヘルパーは数えない）`() {
        val files = listOf(
            CompiledTestClassFile("a/ListedTest.class", isAbstract = false, hasTestMethods = true),
            CompiledTestClassFile("a/NewTest.class", isAbstract = false, hasTestMethods = true),
            CompiledTestClassFile("a/NewTest\$Inner.class", isAbstract = false, hasTestMethods = true),
            CompiledTestClassFile("a/NewTest\$Inner\$Deeper.class", isAbstract = false, hasTestMethods = true),
            CompiledTestClassFile("a/AbstractBaseTest.class", isAbstract = true, hasTestMethods = true),
            CompiledTestClassFile("a/TestHelper.class", isAbstract = false, hasTestMethods = false),
            CompiledTestClassFile("a/HelperWithNestedFixture\$Data.class", isAbstract = false, hasTestMethods = false),
            CompiledTestClassFile("a/HelperWithNestedFixture.class", isAbstract = false, hasTestMethods = false),
            CompiledTestClassFile("a/NestedOnly.class", isAbstract = false, hasTestMethods = false),
            CompiledTestClassFile("a/NestedOnly\$Case.class", isAbstract = false, hasTestMethods = true),
            CompiledTestClassFile("a/InheritsTests.class", isAbstract = false, hasTestMethods = true),
        )
        val executable = ShardAssignment.executableTopLevelClasses(files)
        assertEquals(setOf("a.ListedTest", "a.NewTest", "a.NestedOnly", "a.InheritsTests"), executable)
        assertEquals(3, ShardBalanceGuard.unlistedCount(executable, mapOf("a.ListedTest" to 1.0, "a.Removed" to 9.0)))
    }

    @Test
    fun `AC-5i 番人本体は未掲載が閾値ちょうどなら合格・閾値+1なら失敗`() {
        val table = mapOf("a.Listed" to 1.0) // 既定重み 1.0 → 全クラス同重みで偏りなし
        val atLimit = setOf("a.Listed") + (0 until max).map { "u.Unlisted$it" }
        assertEquals(emptyList(), ShardBalanceGuard.violations(atLimit, table, 2))
        val overLimit = atLimit + "u.UnlistedExtra"
        val violations = ShardBalanceGuard.violations(overLimit, table, 2)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains(ShardBalanceGuard.REGEN_COMMAND), violations.single())
    }

    @Test
    fun `AC-5ii 予測max-avg比は閾値ちょうどで合格・超過で失敗し再生成手順を示す`() {
        assertNull(ShardBalanceGuard.imbalanceViolation(ShardBalanceGuard.PROFILE_FULL, 1.0))
        assertNull(ShardBalanceGuard.imbalanceViolation(ShardBalanceGuard.PROFILE_FULL, ratio))
        val message = assertNotNull(ShardBalanceGuard.imbalanceViolation(ShardBalanceGuard.PROFILE_FULL, Math.nextUp(ratio)))
        assertTrue(message.contains(ShardBalanceGuard.REGEN_COMMAND), message)
        assertTrue(message.contains(ShardBalanceGuard.PROFILE_FULL), message)
    }

    @Test
    fun `AC-5ii 予測比はLPT割り当てと既定重みで計算する`() {
        // A=3, B=1 を 2 shard → [3, 1]、avg 2 → 1.5
        assertEquals(1.5, ShardBalanceGuard.predictedImbalanceRatio(setOf("a.A", "a.B"), mapOf("a.A" to 3.0, "a.B" to 1.0), 2))
        // 未掲載 X は既定重み 2.0（中央値）で LPT に参加 → [3, 2+1] → 1.0
        assertEquals(1.0, ShardBalanceGuard.predictedImbalanceRatio(setOf("a.A", "a.B", "x.X"), mapOf("a.A" to 3.0, "a.B" to 1.0), 2))
        assertEquals(1.0, ShardBalanceGuard.predictedImbalanceRatio(emptySet(), emptyMap(), 8))
    }

    @Test
    fun `AC-5ii full集合だけ偏っていればfullプロファイルとして失敗する`() {
        // full: Flyway10, A1, B1 → [10, 2] → 10/6 ≒ 1.67。migration 除外: A1, B1 → [1, 1] → 1.0
        val table = mapOf("m.migration.FlywayReplayTest" to 10.0, "a.A" to 1.0, "a.B" to 1.0)
        val violations = ShardBalanceGuard.violations(table.keys, table, 2)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains(ShardBalanceGuard.PROFILE_FULL), violations.single())
        assertTrue(!violations.single().contains(ShardBalanceGuard.PROFILE_MIGRATION_EXCLUDED), violations.single())
        assertTrue(violations.single().contains(ShardBalanceGuard.REGEN_COMMAND), violations.single())
    }

    @Test
    fun `AC-5ii migration除外集合だけ偏っていればmigration-excludedプロファイルとして失敗する`() {
        // full: A10, Flyway9, B1 → [10, 10] → 1.0。migration 除外: A10, B1 → [10, 1] → 10/5.5 ≒ 1.82
        val table = mapOf("a.A" to 10.0, "m.migration.FlywayReplayTest" to 9.0, "a.B" to 1.0)
        val violations = ShardBalanceGuard.violations(table.keys, table, 2)
        assertEquals(1, violations.size, violations.toString())
        assertTrue(violations.single().contains(ShardBalanceGuard.PROFILE_MIGRATION_EXCLUDED), violations.single())
        assertTrue(violations.single().contains(ShardBalanceGuard.REGEN_COMMAND), violations.single())
    }
}
