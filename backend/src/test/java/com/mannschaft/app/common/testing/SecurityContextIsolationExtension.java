package com.mannschaft.app.common.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * テスト間の SecurityContext 持ち越し汚染を断つ JUnit 拡張（{@code junit-platform.properties} の自動検出で全テストに適用）。
 *
 * <p>{@code SecurityContextHolder} は ThreadLocal で、同一 JVM・同一ワーカースレッドの後続テストに残る。
 * Mockito 単体テストが {@code @BeforeEach} で認証を設定して後始末しないと、後で走る Spring MockMvc テストの
 * 「未認証」リクエストがその認証を拾い、401 のはずが 200 になる
 * （ReservationGroupAuthorizationEnforcementTest.anonymous_getGroup_unauthorized の断続失敗の根因）。
 * 各テスト・各クラスの終了時に必ず空へ戻す。</p>
 *
 * <p>開始時には消さない: {@code @WithMockUser} や {@code @BeforeEach} で設定した認証をテスト本体に届けるため。
 * {@link TestSecurityContextHolder#clearContext()} は {@code SecurityContextHolder} 側も同時に空にする。</p>
 */
public class SecurityContextIsolationExtension implements AfterEachCallback, AfterAllCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        TestSecurityContextHolder.clearContext();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        TestSecurityContextHolder.clearContext();
    }
}
