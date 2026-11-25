package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * CMP-261111-0508 追撃① 試練 AC-2(c)（metadata）: 旧「基底のみ」の派生と旧「MockMvc=true」の派生が、第一陣 A の後に同じ MCC
 * （＝同じテストコンテキストのキャッシュキー）になることを、コンテキストを起動せずに確かめる。
 *
 * <p>代表は追加の override・注釈を持たない既存クラス（{@link #BASE_ONLY}・{@link #MOCK_MVC_TRUE}）。手本は
 * {@code R2MockContextConfigurationTest}。<b>A の前は red</b>（MockMvc の自動構成の有無で MCC が分かれる）。</p>
 */
@DisplayName("CMP-261111-0508 追撃① AC-2(c) 基底のみと MockMvc=true の MCC 統合（metadata）")
class MockMvcContextUnificationTest {

    /** 旧「基底のみ」の代表（AbstractMySqlIntegrationTest 派生・@AutoConfigureMockMvc なし・追加 override なし）。 */
    static final String BASE_ONLY = "actionmemo.ActionMemoIntegrationTest";
    /** 旧「基底のみ」の2本目（同じグループであることの対照）。 */
    static final String BASE_ONLY_2 = "admin.service.BatchJobLogLatestRowOrderingIT";
    /** 旧「MockMvc=true」の代表（@AutoConfigureMockMvc のみ・追加 override なし）。 */
    static final String MOCK_MVC_TRUE = "auth.AuthLogoutScopeContractIT";

    @Test
    @DisplayName("対照: 基底のみ同士は同じ MCC（A の前から green）")
    void 基底のみ同士は同じMCC() throws ClassNotFoundException {
        assertThat(configuration(BASE_ONLY)).isEqualTo(configuration(BASE_ONLY_2));
    }

    @Test
    @DisplayName("AC-2(c): 基底のみと MockMvc=true の MCC と構成キーが等しい（A の前は red）")
    void 基底のみとMockMvc有りは同じMCC() throws ClassNotFoundException {
        MergedContextConfiguration baseOnly = configuration(BASE_ONLY);
        MergedContextConfiguration mockMvc = configuration(MOCK_MVC_TRUE);

        assertThat(ContextGroupClassOrderer.contextKey(baseOnly))
                .as("構成キー（A の前は MockMvc の自動構成の有無で分かれる）")
                .isEqualTo(ContextGroupClassOrderer.contextKey(mockMvc));
        assertThat(baseOnly).isEqualTo(mockMvc);
    }

    static Class<?> existingClass(String name) throws ClassNotFoundException {
        return Class.forName("com.mannschaft.app." + name, false, MockMvcContextUnificationTest.class.getClassLoader());
    }

    private static MergedContextConfiguration configuration(String name) throws ClassNotFoundException {
        return ContextOverrideCensus.mergedConfiguration(existingClass(name));
    }
}
