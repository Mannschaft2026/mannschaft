package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * テスト間の SecurityContext 持ち越し汚染を {@link SecurityContextIsolationExtension} が断つことの番人。
 *
 * <p>背景: Mockito 単体テスト（例 RepairPlanScenarioServiceTest）が {@code @BeforeEach} で
 * {@code SecurityContextHolder} に認証を設定し、後始末をしないと、同一 JVM・同一スレッドで後に走る
 * Spring MockMvc テストの「未認証」リクエストが、その認証を拾って 401 ではなく 200 を返す
 * （ReservationGroupAuthorizationEnforcementTest の断続失敗の根因）。</p>
 *
 * <p>1 本目が後始末をせず認証を残し（汚染役）、2 本目が「前のテストの認証が見えない」ことを確認する。
 * 拡張が無ければ 2 本目は失敗する。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("SecurityContext 持ち越し汚染の遮断")
class SecurityContextIsolationExtensionTest {

    @Test
    @Order(1)
    @DisplayName("汚染役: 認証を設定したまま後始末せずに終わる")
    void leaker_leavesAuthenticationBehind() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("leaked-user", "n/a", java.util.List.of()));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @Order(2)
    @DisplayName("次のテストには前のテストの認証が持ち越されない")
    void next_test_sees_no_leaked_authentication() {
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("前のテストが残した SecurityContext が見えてはならない")
                .isNull();
    }
}
