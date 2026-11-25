package com.mannschaft.app.membership.domain;

/**
 * 加入期間を終える理由の区分（F06.4.1 データ設計 §8）。leave_reason とは独立に、終了窓口へ明示的に伝える。
 *
 * <p>ACCOUNT_WITHDRAWAL は退会処理の内部経路だけが渡す。外部リクエストの任意 reason からは指定させない。</p>
 */
public enum MembershipEndCause {

    /** 自己脱退・管理者による所属解除など、明示的に加入期間を終える。disposition は REVOKED。 */
    EXPLICIT_MEMBERSHIP_END,

    /** 退会処理に伴う所属整理。disposition は RETAINED_ACCOUNT_WITHDRAWAL（明示終了があればそちらを優先）。 */
    ACCOUNT_WITHDRAWAL
}
