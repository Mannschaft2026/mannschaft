package com.mannschaft.app.common.testing;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;

/**
 * 全テストの後で SecurityContextHolder / TestSecurityContextHolder を空にする JUnit 5 拡張（ServiceLoader による自動検出）。
 *
 * <p>非 Spring の単体テストが setAuthentication したまま終わると、同じテスト JVM で後続に走る Spring IT
 * （MockMvc の実 Security）の未認証リクエストが認証済み扱いになり、401 期待が 403/200 になる。これを根治する。
 *
 * <p>【設計判断】beforeEach / beforeAll では消さない。@BeforeAll で組み立てた認証や、
 * Spring の WithSecurityContextTestExecutionListener が設定する @WithMockUser を、
 * 拡張が先に走って消す事故を避けるため。後始末（afterEach / afterAll）のみで漏れを断つ。
 * 自動検出された拡張の afterEach は宣言的な SpringExtension より後に走るため、Spring 側の後始末とも衝突しない。
 */
public class SecurityContextClearExtension implements AfterEachCallback, AfterAllCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        clear();
    }

    @Override
    public void afterAll(ExtensionContext context) {
        clear();
    }

    private static void clear() {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
    }
}
