package com.mannschaft.app.membership.domain;

/**
 * 加入期間（memberships 行）が終わったときの、チーム共有の扱い（memberships.effort_share_disposition）。
 *
 * <p>NULL は在籍中、または移行前に終了した行（共有許可なし）。F06.4.1 データ設計 §8。</p>
 */
public enum EffortShareDisposition {

    /** 明示脱退・管理者による所属解除。共有は即時に読めなくなる。退会整理より優先する。 */
    REVOKED,

    /** 退会処理に伴う所属整理だけで終わった。共有本文を残し著者を匿名化する（既決 §13.13）。 */
    RETAINED_ACCOUNT_WITHDRAWAL
}
