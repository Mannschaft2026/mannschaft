package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder;

/**
 * CMP-261111-0508 追撃① 試練 AC-2(c)（実 context）: 旧「基底のみ」と旧「MockMvc=true」の既存 IT が、第一陣 A の後に
 * 実際に同じ ApplicationContext を共有し、MockMvc とその builder が構築に成功することを確かめる。
 *
 * <p>手本は {@code R2MockContextSharingIT}（専用 Spring 構成を作らず、既存 IT の実 metadata で context を得る）。
 * Docker 不在時は skip。起動失敗はそのまま失敗として扱う。<b>A の前は red</b>（2つの別 context になる）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261111-0508 追撃① AC-2(c) 基底のみと MockMvc=true の context 共有（実 context）")
class MockMvcContextUnificationIT {

    @Test
    @DisplayName("AC-2(c): 基底のみと MockMvc=true が同じ context を共有し、MockMvc と builder の Bean が構築される（A の前は red）")
    void 同じcontextを共有し追加Beanが構築される() throws ClassNotFoundException {
        ApplicationContext baseOnly = context(MockMvcContextUnificationTest.BASE_ONLY);
        ApplicationContext mockMvc = context(MockMvcContextUnificationTest.MOCK_MVC_TRUE);

        assertThat(baseOnly).as("同じ context を共有する（A の前は別 context）").isSameAs(mockMvc);
        assertThat(baseOnly.getBeansOfType(MockMvc.class)).as("MockMvc の Bean").hasSize(1);
        assertThat(baseOnly.getBeansOfType(DefaultMockMvcBuilder.class)).as("MockMvc の builder").hasSize(1);
    }

    private static ApplicationContext context(String name) throws ClassNotFoundException {
        return BootstrapUtils.resolveTestContextBootstrapper(MockMvcContextUnificationTest.existingClass(name))
                .buildTestContext().getApplicationContext();
    }
}
