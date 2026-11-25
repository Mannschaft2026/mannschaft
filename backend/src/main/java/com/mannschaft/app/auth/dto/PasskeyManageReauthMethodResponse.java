package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.PasskeyManageReauthMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * パスキー登録・削除前の再認証手段レスポンス（CMP-261021-1338 B5）。
 * 利用者の状態から自動選択された手段を返し、FE は手段に応じた再認証ダイアログを出す。
 */
@Getter
@RequiredArgsConstructor
public class PasskeyManageReauthMethodResponse {

    @Schema(description = "再認証手段（PASSKEY / PASSWORD / PASSWORD_AND_SECOND_FACTOR / SECOND_FACTOR / EMAIL_OTP）")
    private final PasskeyManageReauthMethod method;
}
