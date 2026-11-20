package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * 単体テストが SecurityContextHolder に認証を残しても、次のテストへ漏れないことの回帰テスト（共通拡張 SecurityContextClearExtension が担う）。
 *
 * <p>1つ目のメソッドは認証をセットしたまま終わる（漏れ元を再現）。2つ目のメソッドは認証が空であることを検証する。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SecurityContextAutoClearTest {

    @Test
    @Order(1)
    void leaksAuthenticationOnPurpose() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("leaker", "n/a"));
        TestSecurityContextHolder.setAuthentication(
                new UsernamePasswordAuthenticationToken("leaker", "n/a"));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @Order(2)
    void authenticationIsClearedAfterPreviousTest() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(TestSecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
