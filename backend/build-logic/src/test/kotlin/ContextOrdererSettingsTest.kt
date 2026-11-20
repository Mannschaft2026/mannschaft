import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CMP-261111-0508 試練（甲）K-6: Orderer・計測ログの有効範囲。
 *
 * - `:test` では Orderer と計測ログが効き、`-PcontextOrderer=false` で Orderer だけ外れる（計測は常に有効）。
 * - archTest / archUnitTest / archUnitFreezeStoreIntegrityTest（ほか :test 以外）には何も効かない。
 * - 規約プラグインが実際にこの決定を全 Test タスクの設定に使っていること（配線の検査）。
 */
class ContextOrdererSettingsTest {

    private val orderer = ContextOrdererSettings.ORDER_PROPERTY to ContextOrdererSettings.ORDERER_CLASS
    private val log = ContextOrdererSettings.LOG_PROPERTY to "true"

    @Test
    fun `K-6 test タスクは既定で Orderer と計測ログの両方が効く`() {
        assertEquals(mapOf(orderer, log), ContextOrdererSettings.systemProperties("test", null))
        assertEquals(mapOf(orderer, log), ContextOrdererSettings.systemProperties("test", "true"))
    }

    @Test
    fun `K-6 contextOrderer=false は Orderer だけを外し計測ログは残す`() {
        assertEquals(mapOf(log), ContextOrdererSettings.systemProperties("test", "false"))
        assertEquals(mapOf(log), ContextOrdererSettings.systemProperties("test", "FALSE"))
    }

    @Test
    fun `K-6 test 以外のタスクには Orderer も計測ログも効かない`() {
        for (task in listOf(
            "archTest", "archUnitTest", "archUnitFreezeStoreIntegrityTest",
            "perfTest", "generateErDiagrams", "checkErDiagrams",
        )) {
            for (property in listOf(null, "true", "false")) {
                assertEquals(
                    emptyMap(), ContextOrdererSettings.systemProperties(task, property),
                    "$task (-PcontextOrderer=$property) に Orderer・計測ログを効かせてはならない",
                )
            }
        }
    }

    @Test
    fun `K-6 規約プラグインが全 Test タスクで ContextOrdererSettings を使い -PcontextOrderer を読む`() {
        val script = File("src/main/kotlin/mannschaft.backend-conventions.gradle.kts").readText()
        val withType = script.substringAfter("tasks.withType<Test> {", missingDelimiterValue = "")
        assertTrue(withType.isNotEmpty(), "tasks.withType<Test> ブロックが見つからない")
        assertTrue(
            withType.contains("ContextOrdererSettings.systemProperties(name"),
            "withType<Test> でタスク名を渡して ContextOrdererSettings.systemProperties を呼ぶこと",
        )
        assertTrue(
            script.contains("gradleProperty(ContextOrdererSettings.GRADLE_PROPERTY)") ||
                script.contains("gradleProperty(\"contextOrderer\")"),
            "-PcontextOrderer を Gradle property として読むこと",
        )
    }

    @Test
    fun `K-6 Orderer の完全修飾名が backend のテストソースに実在する`() {
        val path = "../src/test/java/" + ContextOrdererSettings.ORDERER_CLASS.replace('.', '/') + ".java"
        assertTrue(File(path).isFile, "Orderer クラスが見つからない: $path")
    }
}
