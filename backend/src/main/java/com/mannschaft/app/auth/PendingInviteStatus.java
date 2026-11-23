package com.mannschaft.app.auth;

/**
 * 招待付き新規登録の保留承認（registration_pending_invites.status）の状態（CMP-261107-1833 設計 §1）。
 *
 * <p>PENDING→ACCEPTING→JOINED/EXPIRED/UNAVAILABLE（原因不明の失敗は PENDING に戻す）、PENDING→DECLINED、
 * EXPIRED→REISSUE_REQUESTED、EXPIRED/REISSUE_REQUESTED→DISMISSED、未終了→DISCARDED。</p>
 */
public enum PendingInviteStatus {
    PENDING,
    ACCEPTING,
    JOINED,
    EXPIRED,
    UNAVAILABLE,
    DECLINED,
    REISSUE_REQUESTED,
    DISMISSED,
    DISCARDED
}
