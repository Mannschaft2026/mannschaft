package com.mannschaft.app.common.security;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 戦役Z-B: 本人非公開 API の入口は、サポート閲覧（代理）の文脈では拒否する（旧・管理者変身の拒否の置き換え）。
 */
@DisplayName("戦役Z-B PrivateSelfAccessGuard（サポート閲覧中は拒否）")
class PrivateSelfAccessGuardTest {

    private final PrivateSelfAccessGuard guard = new PrivateSelfAccessGuard();

    @AfterEach
    void tearDown() {
        SupportAccessContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("通常の本人アクセスは本人 ID を返し、no-store を付ける")
    void 通常は本人IDを返す() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("42", null, List.of()));
        MockHttpServletResponse response = new MockHttpServletResponse();

        Long userId = guard.requireSelfAccess(new MockHttpServletRequest(), response);

        assertThat(userId).isEqualTo(42L);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    @DisplayName("サポート閲覧中は本人私有 API を拒否する")
    void 代理中は拒否する() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("42", null, List.of()));
        SupportAccessContextHolder.set(new SupportAccessContext(
                UUID.randomUUID(), 9001L, 42L, Instant.parse("2026-11-27T10:00:00Z")));

        assertThatThrownBy(() -> guard.requireSelfAccess(new MockHttpServletRequest(), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(UserOperationErrorCode.NOT_ALLOWED));
    }
}
