package com.mannschaft.app.signage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.signage.service.SignageAccessTokenService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261124-0159 PR-P: サイネージの親チーム上限（F00 §20.5.3 サイネージ行・AC-0159-08 / AC-0159-26b）。
 *
 * <ul>
 *   <li>管理画面の読み取り 4 経路（画面一覧・画面詳細・スロット一覧・緊急メッセージ履歴）は、非公開チームでは
 *       ADMIN / DEPUTY_ADMIN / MEMBER だけが本戦役前どおり 200、それ以外は部外者と同一応答（status・code・message）。</li>
 *   <li>非公開チームの画面へのトークン発行は 403 {@code SIGNAGE_009}（既存の認可を通った後に判定）。</li>
 *   <li>公開中に発行したトークンは、チームを非公開にすると表示 API で無効トークンと同一応答になり、
 *       画面名・スロットを返さない。公開に戻すと再び表示できる。</li>
 *   <li>一括無効化 {@link SignageAccessTokenService#deactivateTokensForTeam} は期限なしを含むそのチームの
 *       トークンだけを無効にする（非公開化フロー PR-T が呼ぶ）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 サイネージの親チーム上限（管理画面・トークン発行・表示・一括無効化）")
class SignageMembersOnlyTeamCeilingIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SignageAccessTokenService tokenService;

    @PersistenceContext
    private EntityManager em;

    private ScopeContentFixture fixture;
    private Long screenId;
    private String screenName;

    /** 応答の要約（部外者と同一応答の比較に使う）。 */
    private record Resp(int status, String code, String message, String body) {
        boolean sameAs(Resp other) {
            return status == other.status && Objects.equals(code, other.code)
                    && Objects.equals(message, other.message);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        fixture = ScopeContentFixture.create(em);
        screenName = "上限試練サイネージ画面-" + UUID.randomUUID();
        screenId = createScreen(fixture.teamId, screenName);
        broadcastEmergency(screenId);
        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── 管理画面の読み取り 4 経路 ─────────────────────────────

    @Test
    @DisplayName("非公開チーム: 画面一覧・画面詳細・スロット一覧・緊急履歴は MEMBER 以上だけ 200、他は部外者と同一応答")
    void 管理画面の読み取りは正式メンバー以上だけ() throws Exception {
        SoftAssertions soft = new SoftAssertions();
        for (String path : readPaths()) {
            Resp outsider = perform(get(path), fixture.user(Actor.OUTSIDER));
            soft.assertThat(outsider.status()).as("%s 部外者は 403", path).isEqualTo(403);
            for (Actor allowed : List.of(Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.MEMBER)) {
                soft.assertThat(perform(get(path), fixture.user(allowed)).status())
                        .as("%s %s は本戦役前どおり 200", path, allowed).isEqualTo(200);
            }
            for (Actor denied : List.of(Actor.SUPPORTER, Actor.GUEST, Actor.OTHER_TEAM_MEMBER,
                    Actor.OTHER_ORG_MEMBER, Actor.PARENT_ORG_ADMIN)) {
                Resp actual = perform(get(path), fixture.user(denied));
                soft.assertThat(actual.sameAs(outsider))
                        .as("%s %s は部外者と同一応答: actual=%s outsider=%s", path, denied, actual, outsider)
                        .isTrue();
                soft.assertThat(actual.body()).as("%s %s の本文に画面名を含めない", path, denied)
                        .doesNotContain(screenName);
            }
        }
        soft.assertAll();
    }

    @Test
    @DisplayName("公開チーム（SUPPORTERS_AND_ABOVE）では在籍サポーターの管理画面の読み取りは本戦役前どおり 200")
    void 公開チームではサポーターの結果は不変() throws Exception {
        setVisibility("SUPPORTERS_AND_ABOVE");
        for (String path : readPaths()) {
            assertThat(perform(get(path), fixture.user(Actor.SUPPORTER)).status()).as(path).isEqualTo(200);
        }
    }

    // ─── トークン発行 ─────────────────────────────────────

    @Test
    @DisplayName("非公開チームの画面へのトークン発行は ADMIN でも 403 SIGNAGE_009、トークンは作られない")
    void 非公開チームの画面にはトークンを発行しない() throws Exception {
        Resp resp = perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                .contentType(MediaType.APPLICATION_JSON).content(tokenJson("発行拒否", null)), fixture.user(Actor.ADMIN));

        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.code()).isEqualTo("SIGNAGE_009");
        assertThat(countTokens(screenId)).isZero();
    }

    @Test
    @DisplayName("非公開チームでも管理者でない者のトークン発行は従来どおり 403 COMMON_002（判定順: 既存の認可 → 公開範囲）")
    void 権限なしの発行は従来の拒否() throws Exception {
        Resp resp = perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                .contentType(MediaType.APPLICATION_JSON).content(tokenJson("権限なし", null)), fixture.user(Actor.MEMBER));

        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.code()).isEqualTo("COMMON_002");
    }

    @Test
    @DisplayName("公開チームの画面へのトークン発行は従来どおり 201")
    void 公開チームでは発行できる() throws Exception {
        setVisibility("PUBLIC");
        Resp resp = perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                .contentType(MediaType.APPLICATION_JSON).content(tokenJson("公開", null)), fixture.user(Actor.ADMIN));
        assertThat(resp.status()).isEqualTo(201);
    }

    // ─── 表示 API ─────────────────────────────────────────

    @Test
    @DisplayName("公開中に発行したトークン（期限なし）は、非公開化後の表示 API で無効トークンと同一応答・画面名もスロットも返さず、公開に戻すと 200")
    void 非公開チームのトークンは表示で無効と同一応答() throws Exception {
        setVisibility("PUBLIC");
        String token = issueToken("期限なし", null);
        assertThat(perform(get("/signage/{token}", token), null).status()).isEqualTo(200);

        setVisibility("MEMBERS_AND_ABOVE");
        Resp absent = perform(get("/signage/{token}", UUID.randomUUID().toString()), null);
        Resp actual = perform(get("/signage/{token}", token), null);
        assertThat(actual.sameAs(absent)).as("actual=%s absent=%s", actual, absent).isTrue();
        assertThat(actual.body()).doesNotContain(screenName).doesNotContain("上限試練スロット");

        setVisibility("PUBLIC");
        assertThat(perform(get("/signage/{token}", token), null).status()).isEqualTo(200);
    }

    // ─── 一括無効化 ───────────────────────────────────────

    @Test
    @DisplayName("deactivateTokensForTeam はそのチームの画面のトークン（期限なしを含む）だけを無効にし、件数を返す")
    void チーム単位の一括無効化() throws Exception {
        setVisibility("PUBLIC");
        issueToken("期限なし", null);
        issueToken("期限あり", LocalDateTime.now().plusDays(7));

        setVisibilityOf(fixture.otherTeamId, "PUBLIC");
        Long otherScreen = createScreenAs(fixture.otherTeamId, "他チーム画面-" + UUID.randomUUID(),
                fixture.user(Actor.OTHER_TEAM_MEMBER));
        issueTokenAs(otherScreen, "他チーム", fixture.user(Actor.OTHER_TEAM_MEMBER));
        em.flush();

        int deactivated = tokenService.deactivateTokensForTeam(fixture.teamId);
        em.flush();
        em.clear();

        assertThat(deactivated).isEqualTo(2);
        assertThat(countActiveTokens(screenId)).isZero();
        assertThat(countActiveTokens(otherScreen)).isEqualTo(1L);
    }

    // ─── ヘルパー ─────────────────────────────────────────

    private List<String> readPaths() {
        return List.of(
                "/api/v1/signage/screens?scopeType=TEAM&scopeId=" + fixture.teamId,
                "/api/v1/signage/screens/" + screenId,
                "/api/v1/signage/screens/" + screenId + "/slots",
                "/api/v1/signage/screens/" + screenId + "/emergency");
    }

    private Resp perform(MockHttpServletRequestBuilder builder, Long userId) throws Exception {
        if (userId == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = null;
        String message = null;
        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode error = objectMapper.readTree(body).path("error");
            code = error.path("code").isMissingNode() ? null : error.path("code").asText();
            message = error.path("message").isMissingNode() ? null : error.path("message").asText();
        }
        return new Resp(result.getResponse().getStatus(), code, message, body);
    }

    private void setVisibility(String visibility) {
        setVisibilityOf(fixture.teamId, visibility);
    }

    private void setVisibilityOf(Long teamId, String visibility) {
        ScopeContentFixture.setTeamVisibility(em, teamId, visibility);
        em.flush();
        em.clear();
    }

    private Long createScreen(Long teamId, String name) throws Exception {
        // 画面の作成は ADMIN の書き込み（非公開チームでも従来どおり可能）
        Long id = createScreenAs(teamId, name, fixture.user(Actor.ADMIN));
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("slotType", "FREE_TEXT");
        slot.put("contentSourceId", "上限試練スロット");
        Resp resp = perform(post("/api/v1/signage/screens/{id}/slots", id).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(slot)), fixture.user(Actor.ADMIN));
        assertThat(resp.status()).as("スロット追加: %s", resp).isBetween(200, 201);
        return id;
    }

    private Long createScreenAs(Long teamId, String name, Long userId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", teamId);
        body.put("name", name);
        Resp resp = perform(post("/api/v1/signage/screens").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)), userId);
        assertThat(resp.status()).as("画面作成: %s", resp).isEqualTo(201);
        return objectMapper.readTree(resp.body()).path("data").path("id").asLong();
    }

    private void broadcastEmergency(Long id) throws Exception {
        Resp resp = perform(post("/api/v1/signage/screens/{id}/emergency", id).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("message", "上限試練の緊急連絡"))),
                fixture.user(Actor.ADMIN));
        assertThat(resp.status()).as("緊急配信: %s", resp).isEqualTo(200);
    }

    private String issueToken(String name, LocalDateTime expiredAt) throws Exception {
        return issueTokenAs(screenId, name, fixture.user(Actor.ADMIN), expiredAt);
    }

    private String issueTokenAs(Long targetScreenId, String name, Long userId) throws Exception {
        return issueTokenAs(targetScreenId, name, userId, null);
    }

    private String issueTokenAs(Long targetScreenId, String name, Long userId, LocalDateTime expiredAt)
            throws Exception {
        Resp resp = perform(post("/api/v1/signage/screens/{id}/tokens", targetScreenId)
                .contentType(MediaType.APPLICATION_JSON).content(tokenJson(name, expiredAt)), userId);
        assertThat(resp.status()).as("トークン発行: %s", resp).isEqualTo(201);
        return objectMapper.readTree(resp.body()).path("data").path("token").asText();
    }

    private String tokenJson(String name, LocalDateTime expiredAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (expiredAt != null) {
            body.put("expiredAt", expiredAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        return objectMapper.writeValueAsString(body);
    }

    private long countTokens(Long id) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM signage_access_tokens WHERE screen_id = :id")
                .setParameter("id", id).getSingleResult()).longValue();
    }

    private long countActiveTokens(Long id) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM signage_access_tokens WHERE screen_id = :id AND is_active = TRUE")
                .setParameter("id", id).getSingleResult()).longValue();
    }
}
