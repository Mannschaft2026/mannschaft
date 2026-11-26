package com.mannschaft.app.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261123-1124 アリシゼーション ALIC-3: 保護者同意系の ErrorCode は日本語（原文）で定義する。
 * ja ロケールは enum 文言をそのまま返すため、英語のままだと日本語の利用者に英語が出る。
 */
@DisplayName("保護者同意系 ErrorCode の原文は日本語（CMP-261123-1124 ALIC-3）")
class ParentalConsentErrorCodeMessageTest {

    private static final Pattern JAPANESE = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");

    @Test
    @DisplayName("AUTH_050〜AUTH_070（保護者同意）の原文に日本語が含まれる")
    void messagesAreJapanese() {
        List<AuthErrorCode> codes = List.of(
                AuthErrorCode.AUTH_050, AuthErrorCode.AUTH_051, AuthErrorCode.AUTH_052, AuthErrorCode.AUTH_053,
                AuthErrorCode.AUTH_060, AuthErrorCode.AUTH_062, AuthErrorCode.AUTH_063, AuthErrorCode.AUTH_064,
                AuthErrorCode.AUTH_065, AuthErrorCode.AUTH_066, AuthErrorCode.AUTH_067, AuthErrorCode.AUTH_068,
                AuthErrorCode.AUTH_069, AuthErrorCode.AUTH_070);

        for (AuthErrorCode code : codes) {
            assertThat(JAPANESE.matcher(code.getMessage()).find())
                    .as("%s の原文が日本語であること: %s", code.getCode(), code.getMessage())
                    .isTrue();
        }
    }
}
