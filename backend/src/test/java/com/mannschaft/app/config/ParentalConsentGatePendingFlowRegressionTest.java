package com.mannschaft.app.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261123-1124 試練 AC7: FE の同意待ち導線を直しても BE ゲート（真の防衛線）の許可リストは広げない回帰。
 *
 * <p>pending 画面が使う経路は通り、FE の背景処理（受信箱・参加待ち招待・公開フラグ・ナビ設定・外観設定）
 * が叩く経路は 403 AUTH_070 のまま。ACTIVE はすべて通り、未認証ではゲートは発火しない
 * （未認証の 401 は後段の SecurityConfig が返す。ゲートが 403 で横取りしないこと）。</p>
 */
@DisplayName("保護者同意ゲート: 同意待ち導線の回帰（CMP-261123-1124 AC7）")
class ParentalConsentGatePendingFlowRegressionTest {

    private final ParentalConsentGateFilter filter = new ParentalConsentGateFilter();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(boolean ppc) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "100", null, List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        auth.setDetails(Map.of("jti", "test-jti", "ppc", ppc));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private record Outcome(MockHttpServletResponse response, boolean proceeded) { }

    private Outcome invoke(String method, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setServletPath(path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return new Outcome(response, chain.getRequest() != null);
    }

    @ParameterizedTest(name = "AC7: 同意待ちの {0} {1} は通る（pending 画面・認証維持に必要）")
    @CsvSource({
            "GET,/api/v1/users/me",
            "GET,/api/v1/parental-consent/invitations",
            "POST,/api/v1/parental-consent/invitations",
            "GET,/api/v1/parental-consent/parents",
            "POST,/api/v1/auth/refresh",
            "POST,/api/v1/auth/logout",
    })
    void pending_allowedPaths_proceed(String method, String path) throws Exception {
        authenticate(true);
        Outcome outcome = invoke(method, path);
        assertThat(outcome.proceeded()).isTrue();
        assertThat(outcome.response().getStatus()).isNotEqualTo(403);
    }

    @ParameterizedTest(name = "AC7: 同意待ちの {0} {1} は 403 AUTH_070 のまま（許可リストを広げない）")
    @CsvSource({
            "GET,/api/v1/inbox/summary",
            "GET,/api/v1/me/pending-invite",
            "GET,/api/v1/feature-flags",
            "GET,/api/v1/settings/appearance",
            "GET,/api/v1/settings/nav",
            "GET,/api/v1/teams",
            "PATCH,/api/v1/users/me",
    })
    void pending_backgroundPaths_blocked(String method, String path) throws Exception {
        authenticate(true);
        Outcome outcome = invoke(method, path);
        assertThat(outcome.proceeded()).isFalse();
        assertThat(outcome.response().getStatus()).isEqualTo(403);
        assertThat(outcome.response().getContentAsString()).contains("AUTH_070");
    }

    @ParameterizedTest(name = "AC7: ACTIVE の {0} {1} は通常どおり通る")
    @CsvSource({
            "GET,/api/v1/inbox/summary",
            "GET,/api/v1/feature-flags",
            "GET,/api/v1/teams",
            "PATCH,/api/v1/users/me",
    })
    void active_everythingProceeds(String method, String path) throws Exception {
        authenticate(false);
        Outcome outcome = invoke(method, path);
        assertThat(outcome.proceeded()).isTrue();
    }

    @Test
    @DisplayName("AC7: 未認証ではゲートは発火しない（401 は後段の SecurityConfig の責務。403 で横取りしない）")
    void unauthenticated_gateDoesNotFire() throws Exception {
        SecurityContextHolder.clearContext();
        Outcome outcome = invoke("GET", "/api/v1/teams");
        assertThat(outcome.proceeded()).isTrue();
        assertThat(outcome.response().getStatus()).isNotEqualTo(403);
    }
}
