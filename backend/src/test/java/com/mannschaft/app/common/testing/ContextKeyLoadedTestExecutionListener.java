package com.mannschaft.app.common.testing;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * CMP-261111-0508（甲）: {@link ContextKeyTestExecutionListener} の確定側。
 *
 * <p>順序は 2100: DependencyInjectionTestExecutionListener（2000）の後（コンテキストのロードは prepareTestInstance、
 * @DirtiesContext 後の再ロードは beforeTestMethod の再注入で、いずれも 2000 で起きる）、かつ
 * TransactionalTestExecutionListener（4000）とユーザーの @BeforeEach より前。観測専用で何も起動しない。</p>
 */
public class ContextKeyLoadedTestExecutionListener extends AbstractTestExecutionListener {

    public static final int ORDER = 2100;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void prepareTestInstance(TestContext testContext) {
        ContextKeyTestExecutionListener.finish(testContext, true);
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        ContextKeyTestExecutionListener.finish(testContext, false);
    }
}
