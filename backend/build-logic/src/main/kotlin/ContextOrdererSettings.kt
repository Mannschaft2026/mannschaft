/**
 * CMP-261111-0508（甲）: テストクラスのコンテキスト構成ごとの並べ替え（ContextGroupClassOrderer）と
 * コンテキスト起動の計測（ContextKeyTestExecutionListener の `[ci-ctx]` 行）を、どの Test タスクに効かせるかの決定。
 *
 * - `:test` だけに Orderer と計測ログを効かせる。`-PcontextOrderer=false` は Orderer だけを外す（計測は残す）。
 * - archTest / archUnitTest / archUnitFreezeStoreIntegrityTest ほか `:test` 以外には何も渡さない
 *   （Listener は spring.factories 経由で全 Spring テストに載るため、ログ出力の有効化は system property で `:test` に限る）。
 */
object ContextOrdererSettings {
    /** JUnit Jupiter の既定 ClassOrderer を指定する設定キー。 */
    const val ORDER_PROPERTY = "junit.jupiter.testclass.order.default"

    /** Orderer の完全修飾名（backend/src/test/java 配下に実在すること）。 */
    const val ORDERER_CLASS = "com.mannschaft.app.common.testing.ContextGroupClassOrderer"

    /** Listener が `[ci-ctx]` 行を出すかどうかの system property。`:test` だけ "true"。 */
    const val LOG_PROPERTY = "mannschaft.ci.ctx.log"

    /** Orderer を外すための Gradle property 名（`-PcontextOrderer=false`）。 */
    const val GRADLE_PROPERTY = "contextOrderer"

    /**
     * タスク名と `-PcontextOrderer` の値から、Test タスクへ渡す system property を返す。
     * 戻り値が空なら何も設定しない。
     */
    fun systemProperties(taskName: String, contextOrdererProperty: String?): Map<String, String> {
        if (taskName != TEST_TASK_NAME) return emptyMap()
        val result = linkedMapOf<String, String>()
        // 無効化は "false"（大文字小文字を問わない）だけ。未指定・他の値は有効のまま（既定で効く）。
        // K-10 対照（一時）: 既定で無効。-PcontextOrderer=true のときだけ有効（後で revert する）。
        if (contextOrdererProperty.equals("true", ignoreCase = true)) {
            result[ORDER_PROPERTY] = ORDERER_CLASS
        }
        result[LOG_PROPERTY] = "true"
        return result
    }

    /** Orderer と計測ログを効かせる唯一のタスク名。 */
    const val TEST_TASK_NAME = "test"
}
