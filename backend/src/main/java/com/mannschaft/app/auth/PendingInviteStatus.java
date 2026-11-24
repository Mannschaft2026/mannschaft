package com.mannschaft.app.auth;

/**
 * 招待付き新規登録の保留承認（registration_pending_invites.status）の状態（CMP-261107-1833 設計 §1）。
 *
 * <p>PENDING→ACCEPTING→JOINED/EXPIRED/UNAVAILABLE（原因不明の失敗は PENDING に戻す）、PENDING→DECLINED、
 * EXPIRED→REISSUE_REQUESTED、EXPIRED→DISMISSED、未終了→DISCARDED（依頼済みの REISSUE_REQUESTED は閉じられない。実機 D-1）。
 * PR3（再招待の受け手側）: REISSUE_REQUESTED→PENDING（再招待の確定）、REISSUE_REQUESTED→REISSUE_DECLINED（見送り）、
 * REISSUE_DECLINED→DISMISSED（依頼者が閉じる）。REISSUE_DECLINED は未終了（破棄の対象）に含めない。</p>
 */
public enum PendingInviteStatus {
    PENDING,
    ACCEPTING,
    JOINED,
    EXPIRED,
    UNAVAILABLE,
    DECLINED,
    REISSUE_REQUESTED,
    /** 招待権限者が再招待を見送った（CMP-261107-1833 PR3）。 */
    REISSUE_DECLINED,
    DISMISSED,
    DISCARDED
}
