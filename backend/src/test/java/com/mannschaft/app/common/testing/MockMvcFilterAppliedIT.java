package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMP-261111-0508 追撃① 試練 AC-2(b) true 側: 旧「基底のみ」の派生（本クラスは {@code @AutoConfigureMockMvc} を宣言しない）でも、
 * 第一陣 A の後は MockMvc が Security フィルタ込み（addFilters=true）で構成されることを、フィルタでしか起きない応答で確かめる。
 *
 * <ul>
 *   <li>未認証で保護 API（{@code GET /api/v1/users/me}）を叩くと、認証フィルタ側の entry point（HttpStatusEntryPoint）が
 *       <b>本文なしの 401</b> を返し、HeaderWriterFilter のセキュリティヘッダー（X-Frame-Options: DENY）が付く。
 *       コントローラ・例外ハンドラ由来の 401 は JSON 本文を持つので区別できる。単なる 403 は証拠にしない
 *       （addFilters=false でも 403 は起こり得る。PaymentScopeContractIT 参照）。</li>
 *   <li>有効なアクセストークン（JwtAuthenticationFilter が検証する Bearer）では 200 になる。</li>
 * </ul>
 *
 * <p><b>A の前は red</b>（基底に MockMvc の自動構成が無く、{@link MockMvc} の Bean が無い）。
 * {@code ObjectProvider} で受けるので、起動失敗ではなく「MockMvc が無い」という理由で落ちる。</p>
 */
@Transactional
@DisplayName("CMP-261111-0508 追撃① AC-2(b) 基底のみの派生で MockMvc に Security フィルタが掛かる")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MockMvcFilterAppliedIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ObjectProvider<MockMvc> mockMvcProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthTokenService authTokenService;

    private MockMvc mockMvc() {
        MockMvc mockMvc = mockMvcProvider.getIfAvailable();
        assertThat(mockMvc).as("基底のみの派生に MockMvc の Bean が無い（第一陣 A: 基底への @AutoConfigureMockMvc が未実装）")
                .isNotNull();
        return mockMvc;
    }

    @Test
    @DisplayName("AC-2(b) true 側: 未認証の保護 API は認証フィルタの entry point が本文なしの 401 を返し、セキュリティヘッダーが付く")
    void 未認証は認証フィルタの401() throws Exception {
        MockHttpServletResponse response = mockMvc().perform(get("/api/v1/users/me")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).as("HttpStatusEntryPoint は本文を書かない（コントローラ由来の 401 と区別する）")
                .isEmpty();
        assertThat(response.getHeader("X-Frame-Options")).as("HeaderWriterFilter が動いた証拠").isEqualTo("DENY");
    }

    @Test
    @DisplayName("AC-2(b) true 側: 有効なアクセストークンでは JwtAuthenticationFilter を通って 200 になる")
    void 有効な認証では成功する() throws Exception {
        Long userId = userRepository.save(UserEntity.builder()
                .email("ctxkey-filter-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("フィルタ").displayName("フィルタ試練").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        String token = authTokenService.issueAccessToken(userId, List.of("MEMBER"));

        MockHttpServletResponse response = mockMvc().perform(get("/api/v1/users/me")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    }
}
