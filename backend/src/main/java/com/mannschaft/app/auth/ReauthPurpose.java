package com.mannschaft.app.auth;

/**
 * 再認証の用途（CMP-261021-1339 A7）。再認証開始時のチャレンジと再認証済みフラグに束ね、
 * ある用途の再認証を別用途の操作に流用できないようにする。
 *
 * <p>試練スタブ: 用途の定義だけを先に置く（束ねる処理は出陣で実装する）。</p>
 */
public enum ReauthPurpose {
    /** F18 ポイントカード提示モードの開始。 */
    POINTCARD_PRESENT,
    /** パスキーの登録・削除（CMP-261021-1338）。 */
    PASSKEY_MANAGE
}
