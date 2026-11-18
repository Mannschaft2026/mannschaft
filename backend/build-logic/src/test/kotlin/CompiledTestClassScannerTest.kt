import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CMP-261111-0508: コンパイル済みクラスの走査（実行集合の入力）が、実在のクラスファイルを正しく読めること。
 */
class CompiledTestClassScannerTest {

    private fun parse(resource: String) =
        CompiledTestClassScanner.parse(javaClass.classLoader.getResourceAsStream(resource)!!)

    @Test
    fun `JUnitのTestメソッドを持つクラスはテストありと読める`() {
        val info = parse("ShardAssignmentTest.class")
        assertEquals("ShardAssignmentTest", info.internalName)
        assertTrue(info.ownTests)
        assertFalse(info.isAbstract)
    }

    @Test
    fun `テストメソッドを持たないクラスと抽象クラスは区別できる`() {
        val helper = parse("ShardCoverageClassFile.class")
        assertFalse(helper.ownTests)
        assertFalse(helper.isAbstract)
        val iface = parse("java/lang/Runnable.class")
        assertTrue(iface.isAbstract)
    }

    @Test
    fun `継承したテストメソッドと専用タスク用タグ付きクラスを解決する`() {
        fun raw(name: String, sup: String?, tests: Boolean, tags: Set<String> = emptySet(), analyze: Boolean = false) =
            "$name.class" to CompiledTestClassScanner.RawClassInfo(name, sup, emptyList(), false, tests, analyze, tags)
        val files = CompiledTestClassScanner.resolve(
            listOf(
                raw("a/Base", null, true),
                raw("a/Child", "a/Base", false),
                raw("a/Plain", null, false),
                raw("a/Perf", null, true, setOf("perf")),
                raw("a/Perf\$Inner", null, true),
                raw("a/Arch", null, false, setOf("archunit"), analyze = true),
            )
        )
        val byPath = files.associateBy { it.path }
        assertTrue(byPath.getValue("a/Child.class").hasTestMethods)
        assertFalse(byPath.getValue("a/Plain.class").hasTestMethods)
        assertTrue("a/Perf.class" !in byPath && "a/Perf\$Inner.class" !in byPath, "perf タグのクラスは通常の割り当て対象外")
        assertTrue(byPath.getValue("a/Arch.class").hasTestMethods, "@AnalyzeClasses は archUnitTest で走るため対象")
    }
}
