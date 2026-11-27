package com.mannschaft.app.common.i18n;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.equipment.EquipmentStatus;
import com.mannschaft.app.equipment.entity.EquipmentItemEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261113-1507 試練: BE エラーメッセージの多言語化を実 HTTP 境界（Security フィルタ鎖・UserLocaleFilter・
 * DispatcherServlet の LocaleResolver・GlobalExceptionHandler）で固定する。
 *
 * <p>{@code addFilters} は既定（true）のまま使う。言語解決は {@code UserLocaleFilter}（Security より後段）と
 * {@code UserLocaleResolver} の組で行われるため、フィルタを外すと本欠陥も早期拒否経路の欠陥も再現しない
 * （正本の型: {@link UserLocaleResolutionIT}）。</p>
 *
 * <p>ログイン済みの利用者は {@code JwtAuthenticationFilter} が作るのと同じ形（principal=userId の生 String）の
 * Authentication を {@code authentication(...)} で差す。言語の優先順位は「ログイン済みは users.locale、
 * 未ログインは Accept-Language」。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261113-1507 エラーメッセージ多言語化の HTTP 試練")
class ErrorMessageLocaleHttpIT extends AbstractMySqlIntegrationTest {

    /** 日本語（ひらがな・カタカナ・漢字・半角カナ）を1文字でも含むか。 */
    private static final Pattern JAPANESE = Pattern.compile("[\\u3040-\\u30FF\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uFF66-\\uFF9F]");

    private static final String REGISTER = "/api/v1/auth/register";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private UserLocaleCache userLocaleCache;
    @PersistenceContext private EntityManager em;

    /**
     * 同一 JVM でキャッシュ済み Spring コンテキストが再利用される一方、別コンテキストの起動（ddl-auto=create）で
     * users の AUTO_INCREMENT が 1 に戻り userId が再利用される。userId キーの UserLocaleCache に
     * 旧コンテキスト由来の値（例: ja）が残ると users.locale=en が無視されるため、各テスト前に空にする。
     */
    @BeforeEach
    void clearUserLocaleCache() {
        userLocaleCache.clear();
    }

    // =====================================================================================
    // AC-1 / AC-2 / AC-4 / AC-5 未ログインの業務例外（登録 API・AUTH_004）
    // =====================================================================================

    @Nested
    @DisplayName("未ログイン・登録 API の AUTH_004（メール重複）")
    class 未ログイン登録 {

        @ParameterizedTest(name = "AC-1 Accept-Language: {0}")
        @ValueSource(strings = {"en", "en-US"})
        @DisplayName("AC-1: 未ログイン・Accept-Language: en / en-US で AUTH_004 の message が英語（日本語を含まない）")
        void AC1_未ログイン_en_AUTH004が英語(String acceptLanguage) throws Exception {
            JsonNode error = registerDuplicate(acceptLanguage);
            assertThat(error.path("code").asText()).isEqualTo("AUTH_004");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-2: 未ログイン・Accept-Language: de で AUTH_004 の message がドイツ語（日本語を含まない）")
        void AC2_未ログイン_de_AUTH004がドイツ語() throws Exception {
            JsonNode de = registerDuplicate("de");
            JsonNode en = registerDuplicate("en");
            assertThat(de.path("code").asText()).isEqualTo("AUTH_004");
            assertThat(de.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
            assertThat(de.path("message").asText()).as("de は en と別の訳であること").isNotEqualTo(en.path("message").asText());
        }

        @ParameterizedTest(name = "AC-4 Accept-Language: [{0}]")
        @ValueSource(strings = {"ja", "ja-JP", "<none>"})
        @DisplayName("AC-4: Accept-Language: ja と未指定は enum の日本語文言と一致する（回帰防止）")
        void AC4_jaと未指定はenumの日本語と一致(String acceptLanguage) throws Exception {
            JsonNode error = registerDuplicate("<none>".equals(acceptLanguage) ? null : acceptLanguage);
            assertThat(error.path("code").asText()).isEqualTo("AUTH_004");
            assertThat(error.path("message").asText()).isEqualTo(AuthErrorCode.AUTH_004.getMessage());
        }

        @ParameterizedTest(name = "AC-5 Accept-Language: [{0}]")
        @ValueSource(strings = {"fr", "", "*", "zz-ZZ;q=0.5"})
        @DisplayName("AC-5: 非対応言語（fr）・空文字・不正値は 500 にならず日本語が返る")
        void AC5_非対応言語と空文字は日本語で500にならない(String acceptLanguage) throws Exception {
            MvcResult result = mockMvc.perform(withLang(post(REGISTER), acceptLanguage)
                    .contentType(MediaType.APPLICATION_JSON).content(registerBody(existingEmail()))).andReturn();
            assertThat(result.getResponse().getStatus()).isLessThan(500);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("AUTH_004");
            assertThat(error.path("message").asText()).isEqualTo(AuthErrorCode.AUTH_004.getMessage());
        }
    }

    // =====================================================================================
    // AC-3 ログイン済みは users.locale が Accept-Language より優先
    // =====================================================================================

    @Test
    @DisplayName("AC-3: users.locale=en のログイン済み利用者が GDPR_003 を起こすと Accept-Language: ja でも英語")
    void AC3_ログイン済みenユーザーはAcceptLanguage_jaでもGDPR003が英語() throws Exception {
        UserEntity user = user("en");
        MvcResult result = mockMvc.perform(get("/api/v1/account/data-export/status")
                .with(authentication(auth(user))).header("Accept-Language", "ja")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        JsonNode error = body(result).path("error");
        assertThat(error.path("code").asText()).isEqualTo("GDPR_003");
        assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
    }

    @Test
    @DisplayName("AC-3: users.locale=ja のログイン済み利用者は Accept-Language: en でも GDPR_003 が日本語（回帰防止）")
    void AC3_ログイン済みjaユーザーはAcceptLanguage_enでもGDPR003が日本語() throws Exception {
        UserEntity user = user("ja");
        MvcResult result = mockMvc.perform(get("/api/v1/account/data-export/status")
                .with(authentication(auth(user))).header("Accept-Language", "en")).andReturn();
        JsonNode error = body(result).path("error");
        assertThat(error.path("code").asText()).isEqualTo("GDPR_003");
        assertThat(error.path("message").asText()).isEqualTo("エクスポートデータが見つかりません");
    }

    // =====================================================================================
    // AC-10 ErrorResponse.of(...) を直接返す経路
    // =====================================================================================

    @Nested
    @DisplayName("AC-10: GlobalExceptionHandler の ErrorResponse.of 直返し経路")
    class 直返し経路 {

        @Test
        @DisplayName("AC-10: Bean Validation 失敗（COMMON_001）が Accept-Language: en で英語")
        void AC10_バリデーション失敗COMMON001がenで英語() throws Exception {
            MvcResult result = mockMvc.perform(post(REGISTER).header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("COMMON_001");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-10: JSON パース不能（COMMON_001）が Accept-Language: en で英語")
        void AC10_JSONパース不能COMMON001がenで英語() throws Exception {
            MvcResult result = mockMvc.perform(post(REGISTER).header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON).content("{not-json")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("COMMON_001");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-10: 未マップパス（COMMON_005）が users.locale=en で英語")
        void AC10_未マップパスCOMMON005がenで英語() throws Exception {
            UserEntity user = user("en");
            MvcResult result = mockMvc.perform(get("/api/v1/cmp1507-no-such-endpoint")
                    .with(authentication(auth(user))).header("Accept-Language", "en")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(404);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("COMMON_005");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-10: Bean Validation 失敗（COMMON_001）は Accept-Language: ja で従来の日本語（回帰防止）")
        void AC10_バリデーション失敗COMMON001はjaで日本語() throws Exception {
            MvcResult result = mockMvc.perform(post(REGISTER).header("Accept-Language", "ja")
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("COMMON_001");
            assertThat(error.path("message").asText()).isEqualTo(CommonErrorCode.COMMON_001.getMessage());
        }
    }

    // =====================================================================================
    // AC-12 Security / 前段フィルタの早期拒否（UserLocaleFilter より前で応答が書かれる経路）
    // =====================================================================================

    @Nested
    @DisplayName("AC-12: Security・前段フィルタの早期拒否")
    class 早期拒否 {

        @Test
        @DisplayName("AC-12: AccessDeniedHandler（URL 単位 hasRole の 403 COMMON_002）が users.locale=en で英語（Accept-Language: ja でも）")
        void AC12_AccessDeniedHandlerがログイン済みenで英語() throws Exception {
            UserEntity user = user("en");
            MvcResult result = mockMvc.perform(get("/api/v1/admin/users/1")
                    .with(authentication(auth(user))).header("Accept-Language", "ja")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("COMMON_002");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-12: AccessDeniedHandler は users.locale=ja なら Accept-Language: en でも日本語（優先順位の回帰防止）")
        void AC12_AccessDeniedHandlerはログイン済みjaなら日本語() throws Exception {
            UserEntity user = user("ja");
            MvcResult result = mockMvc.perform(get("/api/v1/admin/users/1")
                    .with(authentication(auth(user))).header("Accept-Language", "en")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            assertThat(body(result).path("error").path("message").asText()).isEqualTo(CommonErrorCode.COMMON_002.getMessage());
        }

        @Test
        @DisplayName("AC-12: 未認証の AuthenticationEntryPoint（401）は Accept-Language: en で日本語の本文を返さない"
                + "（本文を付ける契約変更はしない＝殿判断。現状の空本文のままでよい）")
        void AC12_未認証401がenで日本語の本文を返さない() throws Exception {
            MvcResult result = mockMvc.perform(get("/api/v1/users/me").header("Accept-Language", "en")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(raw).doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-12: 保護者同意ゲート（ParentalConsentGateFilter・403 AUTH_070）が users.locale=en で英語")
        void AC12_保護者同意ゲートがenで英語() throws Exception {
            UserEntity user = user("en");
            UsernamePasswordAuthenticationToken token = auth(user);
            token.setDetails(Map.of("ppc", true));
            MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                    .with(authentication(token)).header("Accept-Language", "en")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(403);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("AUTH_070");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-12: サポート閲覧フィルタ（SupportAccessFilter・旧ヘッダーの 400 SUPPORT_ACCESS_012）が users.locale=en で日本語を含まない")
        void AC12_管理者変身フィルタがenで日本語を含まない() throws Exception {
            UserEntity user = user("en");
            MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                    .with(authentication(auth(user))).header("Accept-Language", "en")
                    .header(com.mannschaft.app.supportaccess.SupportAccessHeaders.LEGACY_IMPERSONATE, "1")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("SUPPORT_ACCESS_012");
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-12: 代理入力フィルタ（ProxyInputContextFilter・ヘッダー不備の 400）が users.locale=en で日本語を含まない")
        void AC12_代理入力フィルタがenで日本語を含まない() throws Exception {
            UserEntity user = user("en");
            MvcResult result = mockMvc.perform(get("/api/v1/users/me")
                    .with(authentication(auth(user))).header("Accept-Language", "en")
                    .header("X-Proxy-For-User-Id", "1").header("X-Proxy-Consent-Id", "1")).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isNotBlank().doesNotContainPattern(JAPANESE);
        }

        @Test
        @DisplayName("AC-12: loyalty 本文上限フィルタ（LoyaltyBodyLimitFilter・400 COMMON_001）が users.locale=en で英語")
        void AC12_loyalty本文上限フィルタがenで英語() throws Exception {
            UserEntity user = user("en");
            String oversized = "{\"x\":\"" + "a".repeat(5_000) + "\"}";
            MvcResult result = mockMvc.perform(post("/api/v1/loyalty/cmp1507")
                    .with(authentication(auth(user))).header("Accept-Language", "en")
                    .contentType(MediaType.APPLICATION_JSON).content(oversized)).andReturn();
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            JsonNode error = body(result).path("error");
            assertThat(error.path("code").asText()).isEqualTo("COMMON_001");
            assertThat(error.path("message").asText()).isNotBlank().doesNotContainPattern(JAPANESE);
        }
    }

    // =====================================================================================
    // AC-17 存在秘匿の契約は言語で割れない
    // =====================================================================================

    @Test
    @DisplayName("AC-17: 他テナントの実在 ID と存在しない ID で、言語（users.locale×Accept-Language＝ja/en/de）を変えても"
            + "ステータス・コードが同一で、message 以外の応答が同一")
    void AC17_他テナントIDと不在IDの応答は言語によらずmessage以外同一() throws Exception {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_FACILITY_ENABLED");
        TeamEntity team = team();
        EquipmentItemEntity present = item(team.getId());
        EquipmentItemEntity missing = item(team.getId());
        long missingId = missing.getId();
        em.remove(missing);
        em.flush();
        em.clear();

        List<String> langs = List.of("ja", "en", "de");
        Map<String, JsonNode> withoutMessage = new LinkedHashMap<>();
        Map<String, Integer> statuses = new LinkedHashMap<>();
        Map<String, Map<String, String>> messagesByLang = new LinkedHashMap<>();
        for (String userLocale : langs) {
            UserEntity outsider = user(userLocale);
            for (String header : langs) {
                for (Map.Entry<String, Long> target : Map.of("other-tenant", present.getId(), "missing", missingId).entrySet()) {
                    String url = "/api/v1/teams/" + team.getId() + "/equipment/" + target.getValue();
                    MvcResult result = mockMvc.perform(get(url).servletPath(url)
                            .with(authentication(auth(outsider))).header("Accept-Language", header)).andReturn();
                    String label = "user=" + userLocale + ",header=" + header + "," + target.getKey();
                    statuses.put(label, result.getResponse().getStatus());
                    JsonNode json = body(result);
                    messagesByLang.computeIfAbsent(userLocale + "/" + header, k -> new LinkedHashMap<>())
                            .put(target.getKey(), json.path("error").path("message").asText());
                    ((ObjectNode) json.path("error")).remove("message");
                    withoutMessage.put(label, json);
                }
            }
        }

        assertThat(statuses.values()).as("ステータスが言語・対象で割れている: " + statuses).containsOnly(404);
        assertThat(new ArrayList<>(withoutMessage.values()).stream().distinct().count())
                .as("message 以外の応答が言語・対象で割れている: " + withoutMessage).isEqualTo(1);
        messagesByLang.forEach((lang, byTarget) -> assertThat(byTarget.get("other-tenant"))
                .as("同一言語内で他テナントと不在の message が割れている（存在オラクル）: " + lang)
                .isEqualTo(byTarget.get("missing")));
    }

    // =====================================================================================
    // 補助
    // =====================================================================================

    private JsonNode registerDuplicate(String acceptLanguage) throws Exception {
        MvcResult result = mockMvc.perform(withLang(post(REGISTER), acceptLanguage)
                .contentType(MediaType.APPLICATION_JSON).content(registerBody(existingEmail()))).andReturn();
        return body(result).path("error");
    }

    private String existingEmail() {
        return user("ja").getEmail();
    }

    private String registerBody(String email) throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("email", email);
        req.put("password", "Cmp1507Pass!x");
        req.put("lastName", "試練");
        req.put("firstName", "多言語");
        req.put("nickname", "cmp1507");
        req.put("privacyPolicyAccepted", true);
        req.put("privacyPolicyVersion", "2026-01-01");
        return objectMapper.writeValueAsString(req);
    }

    private static MockHttpServletRequestBuilder withLang(MockHttpServletRequestBuilder builder, String acceptLanguage) {
        return acceptLanguage == null ? builder : builder.header("Accept-Language", acceptLanguage);
    }

    private UserEntity user(String locale) {
        UserEntity user = UserEntity.builder().email("cmp1507-" + UUID.randomUUID() + "@example.com")
                .passwordHash("{noop}unused").lastName("試練").firstName("多言語")
                .displayName("多言語試練").status(UserEntity.UserStatus.ACTIVE).isSearchable(false)
                .locale(locale).timezone("Asia/Tokyo").build();
        em.persist(user);
        em.flush();
        return user;
    }

    private TeamEntity team() {
        TeamEntity team = TeamEntity.builder().name("多言語試練")
                .slug("cmp1507-" + UUID.randomUUID().toString().substring(0, 20))
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build();
        em.persist(team);
        return team;
    }

    private EquipmentItemEntity item(long teamId) {
        EquipmentItemEntity item = EquipmentItemEntity.builder().teamId(teamId).name("多言語試練")
                .quantity(1).assignedQuantity(0).status(EquipmentStatus.AVAILABLE).isConsumable(false)
                .qrCode("cmp1507-" + UUID.randomUUID()).build();
        em.persist(item);
        em.flush();
        return item;
    }

    private static UsernamePasswordAuthenticationToken auth(UserEntity user) {
        return new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), null, List.of());
    }

    private JsonNode body(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return raw.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(raw);
    }
}
