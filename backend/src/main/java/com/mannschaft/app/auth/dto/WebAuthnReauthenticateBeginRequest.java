package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.ReauthPurpose;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * WebAuthn 再認証開始リクエスト DTO（CMP-261021-1339 A7）。
 *
 * <p>再認証の用途をチャレンジに束ねる。F18 提示モード用の再認証ではパスキーの登録・削除はできず、逆も不可。</p>
 */
@Getter
@RequiredArgsConstructor
public class WebAuthnReauthenticateBeginRequest {

    @NotNull
    @Schema(description = "再認証の用途（POINTCARD_PRESENT=ポイントカード提示モード / PASSKEY_MANAGE=パスキーの登録・削除）")
    private final ReauthPurpose purpose;
}
