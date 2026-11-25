package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.MfaVerificationMethod;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * パスキー登録・削除前の再認証（パスワード／2FA 要素／メールOTP 経路）のリクエスト（CMP-261021-1338 B5）。
 *
 * <p>試練スタブ: 形だけを先に置く。</p>
 */
@Getter
@RequiredArgsConstructor
public class PasskeyManageReauthRequest {

    /** 現在のパスワード（パスワード保有者のみ）。 */
    private final String currentPassword;

    /** 2FA 要素またはメールOTP の種別（EMAIL は本人確認用 REAUTH コード）。 */
    private final MfaVerificationMethod method;

    /** 2FA 要素またはメールOTP のコード。 */
    private final String code;
}
