package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/**
 * CMP-261111-0508 追撃① 試練 AC-2(b) false 側: {@code @AutoConfigureMockMvc(addFilters = false)} を宣言する派生は、
 * 第一陣 A（基底が addFilters=true を宣言）の後もフィルタが適用されないことを確かめる。
 *
 * <p>未認証で保護 API を叩いても、認証フィルタの entry point による「本文なしの 401」にならず、
 * HeaderWriterFilter のセキュリティヘッダー（X-Frame-Options・X-Content-Type-Options）も付かない。
 * 構成は既存の addFilters=false 派生と同じ（追加の override なし）なので、構成キーを増やさない。A の前から green。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("CMP-261111-0508 追撃① AC-2(b) addFilters=false の派生にはフィルタが掛からない")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MockMvcFilterNotAppliedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("AC-2(b) false 側: 未認証でも認証フィルタの 401 にならず、セキュリティヘッダーも付かない")
    void フィルタが適用されない() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/api/v1/users/me")).andReturn().getResponse();

        assertThat(response.getHeader("X-Frame-Options")).as("HeaderWriterFilter は動かない").isNull();
        assertThat(response.getHeader("X-Content-Type-Options")).isNull();
        assertThat(response.getStatus() == 401 && response.getContentAsString().isEmpty())
                .as("認証フィルタの entry point（本文なしの 401）に到達しない: status=%d body=%s",
                        response.getStatus(), response.getContentAsString())
                .isFalse();
    }
}
