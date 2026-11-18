/**
 * CMP-261111-0508（甲）: テストクラスのコンテキスト構成ごとの並べ替え（ContextGroupClassOrderer）と
 * コンテキスト起動の計測（ContextKeyTestExecutionListener の `[ci-ctx]` 行）を、どの Test タスクに効かせるかの決定。
 *
 * - `:test` だけに Orderer と計測ログを効かせる。`-PcontextOrderer=false` は Orderer だけを外す（計測は残す）。
 * - archTest / archUnitTest / archUnitFreezeStoreIntegrityTest ほか `:test` 以外には何も渡さない
 *   （Listener は spring.factories 経由で全 Spring テストに載るため、ログ出力の有効化は system property で `:test` に限る）。
 *
 * 試練（red）段階のスタブ。出陣で実装する。
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
    fun systemProperties(taskName: String, contextOrdererProperty: String?): Map<String, String> =
        throw UnsupportedOperationException("CMP-261111-0508: 未実装（出陣で実装する）")
}
