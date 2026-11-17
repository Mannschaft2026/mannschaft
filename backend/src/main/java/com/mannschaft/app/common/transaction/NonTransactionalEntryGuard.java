package com.mannschaft.app.common.transaction;

/**
 * 段取り役（越境 TX を持たない入口）の全公開メソッドの<b>最初の文</b>で呼ぶ番人（CMP-260909-2152 確定設計 r12 §9）。
 *
 * <p>{@code TransactionSynchronizationManager.isActualTransactionActive()} が真なら
 * {@link IllegalStateException} を投げる。ログ・監査・失敗記録より前に置き、TX 内から呼ばれたときの副作用を0にする。</p>
 *
 * <p>試練段階の骨組み。中身は出陣で実装する。</p>
 */
public final class NonTransactionalEntryGuard {

    private NonTransactionalEntryGuard() {
    }

    /** 実 TX が有効なら {@link IllegalStateException}。 */
    public static void assertNoActiveTransaction() {
        throw new UnsupportedOperationException("CMP-260909-2152 試練: 未実装");
    }
}
