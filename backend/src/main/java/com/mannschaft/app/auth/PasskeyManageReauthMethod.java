package com.mannschaft.app.auth;

/**
 * パスキー登録・削除前の再認証手段（CMP-261021-1338 B5）。利用者の状態から自動選択する。
 */
public enum PasskeyManageReauthMethod {
    /** 既存パスキー保有者: パスキー再認証（UV 必須）。 */
    PASSKEY,
    /** パスキー未保有・パスワードあり・2FA 未設定: パスワードのみ。 */
    PASSWORD,
    /** パスキー未保有・パスワードあり・2FA 有効: パスワード＋2FA 要素。 */
    PASSWORD_AND_SECOND_FACTOR,
    /** パスキー・パスワード・2FA のいずれも無い（SNS ログインのみ）: 本人確認用メールOTP（REAUTH）。 */
    EMAIL_OTP
}
