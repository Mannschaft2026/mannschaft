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

    // ---- 検分の差し戻し対応（CMP-261111-0508） ----

    private val test = "Lorg/junit/jupiter/api/Test;"

    private fun m(name: String, vararg annotations: String, access: Int = 1) =
        CompiledTestClassScanner.MethodInfo(name, "()V", annotations.toSet(), access)

    private fun rawInfo(
        name: String,
        sup: String? = null,
        methods: List<CompiledTestClassScanner.MethodInfo> = emptyList(),
        classAnnotations: Set<String> = emptySet(),
        isAnnotation: Boolean = false,
        isAbstract: Boolean = false,
    ) = "$name.class" to CompiledTestClassScanner.RawClassInfo(
        name, sup, emptyList(), isAbstract, false, false, emptySet(), methods, classAnnotations, isAnnotation,
    )

    @Test
    fun `実在クラスからメソッドの名前と注釈を読める`() {
        val info = parse("ShardAssignmentTest.class")
        assertTrue(info.methods.any { it.annotationTypes.contains(test) })
    }

    @Test
    fun `検分P1 Testをメタアノテーションに持つ独自注釈だけを付けたクラスもテストありと読める`() {
        val files = CompiledTestClassScanner.resolve(
            listOf(
                rawInfo("a/FastTest", classAnnotations = setOf(test), isAnnotation = true, isAbstract = true),
                rawInfo("a/SlowTest", classAnnotations = setOf("La/FastTest;"), isAnnotation = true, isAbstract = true),
                rawInfo("a/UsesFast", methods = listOf(m("x", "La/FastTest;"))),
                rawInfo("a/UsesSlow", methods = listOf(m("x", "La/SlowTest;"))),
                rawInfo("a/UsesOther", methods = listOf(m("x", "La/Unrelated;"))),
            )
        ).associateBy { it.path }
        assertTrue(files.getValue("a/UsesFast.class").hasTestMethods)
        assertTrue(files.getValue("a/UsesSlow.class").hasTestMethods, "メタ注釈の連鎖も辿る")
        assertFalse(files.getValue("a/UsesOther.class").hasTestMethods)
    }

    @Test
    fun `検分P3a 親のテストメソッドを注釈なしでoverrideした子は、他にテストが無ければテストなし`() {
        val files = CompiledTestClassScanner.resolve(
            listOf(
                rawInfo("a/Base", methods = listOf(m("t", test)), isAbstract = true),
                rawInfo("a/Overrides", "a/Base", methods = listOf(m("t"))),
                rawInfo("a/OverridesAndAnother", "a/Base", methods = listOf(m("t"), m("u", test))),
                rawInfo("a/ReAnnotated", "a/Base", methods = listOf(m("t", test))),
                rawInfo("a/Inherits", "a/Base"),
            )
        ).associateBy { it.path }
        assertFalse(files.getValue("a/Overrides.class").hasTestMethods)
        assertTrue(files.getValue("a/OverridesAndAnother.class").hasTestMethods)
        assertTrue(files.getValue("a/ReAnnotated.class").hasTestMethods)
        assertTrue(files.getValue("a/Inherits.class").hasTestMethods)
    }

    @Test
    fun `privateやstaticのTestメソッドはテストとして数えない`() {
        val files = CompiledTestClassScanner.resolve(
            listOf(
                rawInfo("a/Priv", methods = listOf(m("t", test, access = 0x0002))),
                rawInfo("a/Stat", methods = listOf(m("t", test, access = 0x0009))),
            )
        ).associateBy { it.path }
        assertFalse(files.getValue("a/Priv.class").hasTestMethods)
        assertFalse(files.getValue("a/Stat.class").hasTestMethods)
    }

    @Test
    fun `AnalyzeClassesだけのクラスはhasTestMethodsだがhasJupiterTestsではない`() {
        val files = CompiledTestClassScanner.resolve(
            listOf(
                "a/Arch.class" to CompiledTestClassScanner.RawClassInfo(
                    "a/Arch", null, emptyList(), false, false, true, emptySet(),
                ),
            )
        )
        assertTrue(files.single().hasTestMethods)
        assertFalse(files.single().hasJupiterTests)
    }
}
