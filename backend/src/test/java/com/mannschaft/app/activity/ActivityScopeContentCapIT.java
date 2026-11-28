package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261124-0159 PR-A1a: 活動記録（F06.4）の読み取り経路に親チーム上限（共通の門）が効くことを、
 * 実 HTTP 境界と実 MySQL で固定する受け入れテスト（F00 §20.3・§20.5.2 activity 行・F06.4 §15.4/§15.5）。
 *
 * <p>非公開チーム（{@code MEMBERS_AND_ABOVE}）で、権限あり（ADMIN / DEPUTY_ADMIN / MEMBER）は本戦役前と同じ結果、
 * 権限なし（在籍サポーター（是正前データのフィクスチャ）・GUEST・他チーム・他組織・非在籍の親組織 ADMIN）は
 * 部外者（ログイン済み非所属）と status・{@code error.code}・{@code error.message} まで完全一致する。
 * 記録そのものは PUBLIC でも権限なしには返らない（AC-0159-13）。</p>
 *
 * <p>期待値の権限あり側は origin/main（本戦役前）の実挙動の記録（スナップショット）。上限の追加で変わるのは
 * 権限なし側だけ（{@link #公開チームではサポーターの結果が本戦役前と同一} で公開チームの回帰も固定する）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 活動記録の親チーム上限（AC-0159-13・80・81・81b）")
class ActivityScopeContentCapIT extends AbstractMySqlIntegrationTest {

    /** 権限あり（本戦役前どおり）。 */
    private static final Set<Actor> ALLOWED = EnumSet.of(Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.MEMBER);
    /** 権限なし・他テナント（部外者と同一応答）。 */
    private static final Set<Actor> DENIED = EnumSet.of(Actor.SUPPORTER, Actor.GUEST, Actor.OTHER_TEAM_MEMBER,
            Actor.OTHER_ORG_MEMBER, Actor.PARENT_ORG_ADMIN);
    private static final long NONEXISTENT_TEMPLATE_ID = 9_999_999_981L;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private ScopeContentFixture fixture;
    private String suffix;
    private Long activityId;
    private Long templateId;
    private Long otherTeamTemplateId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(t -> {
            fixture = ScopeContentFixture.create(em);
            templateId = template(fixture.teamId, "自チームの型-" + suffix, "score", "自チームの得点-" + suffix);
            otherTeamTemplateId = template(fixture.otherTeamId, "他チームの型-" + suffix, "score",
                    "他チームの秘密の項目-" + suffix);
            ActivityResultEntity activity = ActivityResultEntity.builder().scopeType(ActivityScopeType.TEAM)
                    .scopeId(fixture.teamId).createdBy(fixture.user(Actor.ADMIN)).title("公開の記録-" + suffix)
                    .activityDate(LocalDate.of(2026, 10, 15)).visibility(ActivityVisibility.PUBLIC)
                    .status(ActivityStatus.PUBLISHED).build();
            em.persist(activity);
            em.flush();
            activityId = activity.getId();
            em.persist(ActivityCommentEntity.builder().activityResultId(activityId)
                    .userId(fixture.user(Actor.MEMBER)).body("メンバーのコメント-" + suffix).build());
            em.flush();
        });
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        tx.executeWithoutResult(t -> {
            for (Long scopeId : List.of(fixture.teamId, fixture.otherTeamId)) {
                String inResults = " IN (SELECT id FROM activity_results WHERE scope_type = 'TEAM' AND scope_id = :id)";
                em.createNativeQuery("DELETE FROM activity_comments WHERE activity_result_id" + inResults)
                        .setParameter("id", scopeId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_participants WHERE activity_result_id" + inResults)
                        .setParameter("id", scopeId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_results WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", scopeId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_template_fields WHERE template_id IN "
                                + "(SELECT id FROM activity_templates WHERE scope_type = 'TEAM' AND scope_id = :id)")
                        .setParameter("id", scopeId).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_templates WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", scopeId).executeUpdate();
            }
            fixture.delete(em);
        });
    }

    // ───────── AC-0159-13 活動記録（一覧・詳細・コメント。PUBLIC 記録も権限なしには返らない） ─────────

    @Test
    @DisplayName("AC-0159-13 一覧: 権限ありは 200、権限なし・他テナントは部外者と同一応答で、PUBLIC 記録の題名も返らない")
    void 一覧() throws Exception {
        assertCapped(() -> get("/api/v1/activities").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()), "公開の記録-" + suffix);
    }

    @Test
    @DisplayName("AC-0159-13 詳細: PUBLIC 記録でも権限なし・他テナントには部外者と同一応答で、本文に題名を含まない")
    void 詳細() throws Exception {
        assertCapped(() -> get("/api/v1/activities/" + activityId), "公開の記録-" + suffix);
    }

    @Test
    @DisplayName("AC-0159-13 コメント一覧: 権限なし・他テナントは部外者と同一応答で、コメント本文を含まない")
    void コメント一覧() throws Exception {
        assertCapped(() -> get("/api/v1/activities/" + activityId + "/comments"), "メンバーのコメント-" + suffix);
    }

    @Test
    @DisplayName("AC-0159-13 テンプレート一覧・詳細: 権限なし・他テナントは部外者と同一応答")
    void テンプレート() throws Exception {
        assertCapped(() -> get("/api/v1/activity-templates").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()), "自チームの型-" + suffix);
        assertCapped(() -> get("/api/v1/activity-templates/" + templateId), "自チームの型-" + suffix);
    }

    @Test
    @DisplayName("AC-0159-13 複製（中身を返す書き込み）: 権限なしは部外者と同一応答で、複製行も作られない。ADMIN は従来どおり作成できる")
    void 複製() throws Exception {
        Response outsider = perform(post("/api/v1/activities/" + activityId + "/duplicate")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), Actor.OUTSIDER);
        assertThat(isRejection(outsider)).as("部外者は拒否: %s", outsider).isTrue();
        for (Actor actor : DENIED) {
            Response actual = perform(post("/api/v1/activities/" + activityId + "/duplicate")
                    .contentType(MediaType.APPLICATION_JSON).content("{}"), actor);
            assertSameAsOutsider(actor, actual, outsider);
            assertThat(actual.body()).doesNotContain("公開の記録-" + suffix);
        }
        Number copies = tx.execute(t -> (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM activity_results WHERE scope_type = 'TEAM' AND scope_id = :id AND created_by IN (:u)")
                .setParameter("id", fixture.teamId)
                .setParameter("u", DENIED.stream().map(fixture::user).toList()).getSingleResult());
        assertThat(copies.longValue()).as("権限なしの複製行").isZero();
        Response admin = perform(post("/api/v1/activities/" + activityId + "/duplicate")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), Actor.ADMIN);
        assertThat(admin.status()).as("ADMIN の複製は本戦役前どおり成功").isBetween(200, 299);
    }

    @Test
    @DisplayName("AC-0159-13（門）統計: 非公開チームの権限なし・他テナントは部外者と同一の 403 COMMON_002、権限ありは 200")
    void 統計の門() throws Exception {
        assertCapped(() -> get("/api/v1/activities/stats").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()), null);
    }

    @Test
    @DisplayName("AC-0159-13 公開チーム（SUPPORTERS_AND_ABOVE）ではサポーターの一覧・詳細・コメントの結果が本戦役前と同一（200）")
    void 公開チームではサポーターの結果が本戦役前と同一() throws Exception {
        tx.executeWithoutResult(t -> ScopeContentFixture.setTeamVisibility(em, fixture.teamId, "SUPPORTERS_AND_ABOVE"));
        Response list = perform(get("/api/v1/activities").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()), Actor.SUPPORTER);
        assertThat(list.status()).isEqualTo(200);
        assertThat(list.body()).contains("公開の記録-" + suffix);
        Response detail = perform(get("/api/v1/activities/" + activityId), Actor.SUPPORTER);
        assertThat(detail.status()).isEqualTo(200);
        assertThat(perform(get("/api/v1/activities/" + activityId + "/comments"), Actor.SUPPORTER).status())
                .isEqualTo(200);
    }

    // ───────── AC-0159-80 /stats/fields の template 帰属確認 ─────────

    @Test
    @DisplayName("AC-0159-80 /stats/fields: 他スコープの template_id は存在しない ID と本文まで完全一致の 404 ACTIVITY_002 で、フィールド名・ラベルが出ない")
    void 統計フィールドは他スコープのテンプレートを不在と同一に扱う() throws Exception {
        Response otherScope = perform(fieldStats(otherTeamTemplateId), Actor.ADMIN);
        Response missing = perform(fieldStats(NONEXISTENT_TEMPLATE_ID), Actor.ADMIN);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.code()).isEqualTo("ACTIVITY_002");
        assertThat(otherScope.status()).isEqualTo(404);
        assertThat(otherScope.code()).isEqualTo(missing.code());
        assertThat(otherScope.message()).isEqualTo(missing.message());
        assertThat(otherScope.body()).doesNotContain("他チームの秘密の項目-" + suffix);
    }

    @Test
    @DisplayName("AC-0159-80 /stats/fields: 同スコープのテンプレートは 200、サポーター・部外者は 403 COMMON_002")
    void 統計フィールドの認可() throws Exception {
        Response own = perform(fieldStats(templateId), Actor.ADMIN);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body()).contains("自チームの得点-" + suffix);
        Response outsider = perform(fieldStats(templateId), Actor.OUTSIDER);
        assertThat(outsider.status()).isEqualTo(403);
        assertThat(outsider.code()).isEqualTo("COMMON_002");
        assertSameAsOutsider(Actor.SUPPORTER, perform(fieldStats(templateId), Actor.SUPPORTER), outsider);
    }

    // ───────── AC-0159-81 /export は管理者限定 ─────────

    @Test
    @DisplayName("AC-0159-81 /export: ADMIN・DEPUTY_ADMIN は 200、MEMBER・サポーター・部外者・他テナントは 403 COMMON_002")
    void エクスポートは管理者限定() throws Exception {
        for (Actor actor : List.of(Actor.ADMIN, Actor.DEPUTY_ADMIN)) {
            Response ok = perform(export(), actor);
            assertThat(ok.status()).as("%s の CSV", actor).isEqualTo(200);
            assertThat(ok.body()).contains("公開の記録-" + suffix);
        }
        for (Actor actor : List.of(Actor.MEMBER, Actor.SUPPORTER, Actor.GUEST, Actor.OUTSIDER,
                Actor.OTHER_TEAM_MEMBER, Actor.OTHER_ORG_MEMBER, Actor.PARENT_ORG_ADMIN)) {
            Response denied = perform(export(), actor);
            assertThat(denied.status()).as("%s の CSV", actor).isEqualTo(403);
            assertThat(denied.code()).as("%s の CSV", actor).isEqualTo("COMMON_002");
            assertThat(denied.body()).doesNotContain("公開の記録-" + suffix);
        }
    }

    // ───────── AC-0159-81b DEPUTY_ADMIN 回帰 ─────────

    @Test
    @DisplayName("AC-0159-81b DEPUTY_ADMIN は権限グループの内容によらず、記録の更新・削除・テンプレート管理・統計閲覧が本戦役前と同じく成功する")
    void 副管理者の回帰() throws Exception {
        Actor deputy = Actor.DEPUTY_ADMIN;
        assertThat(perform(get("/api/v1/activities/stats").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()), deputy).status()).as("統計閲覧").isEqualTo(200);
        String update = json.writeValueAsString(Map.of("title", "副管理者が更新-" + suffix, "activityDate", "2026-10-16"));
        assertThat(perform(put("/api/v1/activities/" + activityId).contentType(MediaType.APPLICATION_JSON)
                .content(update), deputy).status()).as("記録の更新").isBetween(200, 299);
        String createTemplate = json.writeValueAsString(Map.of("name", "副管理者の型-" + suffix));
        Response created = perform(post("/api/v1/activity-templates").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(createTemplate), deputy);
        assertThat(created.status()).as("テンプレート作成").isBetween(200, 299);
        String updateTemplate = json.writeValueAsString(Map.of("name", "副管理者が改名-" + suffix));
        assertThat(perform(put("/api/v1/activity-templates/" + templateId).contentType(MediaType.APPLICATION_JSON)
                .content(updateTemplate), deputy).status()).as("テンプレート更新").isBetween(200, 299);
        assertThat(perform(delete("/api/v1/activities/" + activityId), deputy).status()).as("記録の削除")
                .isBetween(200, 299);
    }

    // ───────── helpers ─────────

    private interface RequestFactory {
        MockHttpServletRequestBuilder create();
    }

    /** 権限ありは 2xx（本戦役前どおり）、権限なし・他テナントは部外者と同一応答で本文に中身を含まない。 */
    private void assertCapped(RequestFactory request, String secret) throws Exception {
        Response outsider = perform(request.create(), Actor.OUTSIDER);
        assertThat(isRejection(outsider)).as("部外者の応答は拒否であること: %s", outsider).isTrue();
        for (Actor actor : ALLOWED) {
            Response actual = perform(request.create(), actor);
            assertThat(actual.status()).as("%s は本戦役前どおり 2xx: %s", actor, actual).isBetween(200, 299);
            if (secret != null) {
                assertThat(actual.body()).as("%s には中身が返る", actor).contains(secret);
            }
        }
        for (Actor actor : DENIED) {
            Response actual = perform(request.create(), actor);
            assertSameAsOutsider(actor, actual, outsider);
            if (secret != null) {
                assertThat(actual.body()).as("%s の本文に中身を含まない", actor).doesNotContain(secret);
            }
        }
    }

    private static void assertSameAsOutsider(Actor actor, Response actual, Response outsider) {
        assertThat(actual.status()).as("%s の status は部外者と同一: actual=%s outsider=%s", actor, actual, outsider)
                .isEqualTo(outsider.status());
        assertThat(actual.code()).as("%s の error.code は部外者と同一", actor).isEqualTo(outsider.code());
        assertThat(actual.message()).as("%s の error.message は部外者と同一", actor).isEqualTo(outsider.message());
    }

    private static boolean isRejection(Response response) {
        return (response.status() == 403 || response.status() == 404) && response.code() != null;
    }

    private MockHttpServletRequestBuilder fieldStats(Long template) {
        return get("/api/v1/activities/stats/fields").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()).param("template_id", template.toString())
                .param("field_key", "score");
    }

    private MockHttpServletRequestBuilder export() {
        return get("/api/v1/activities/export").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString());
    }

    private Response perform(MockHttpServletRequestBuilder builder, Actor actor) throws Exception {
        MvcResult result = mvc.perform(builder.with(user(String.valueOf(fixture.user(actor))))).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = null;
        String message = null;
        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode error = json.readTree(body).path("error");
            code = error.path("code").isMissingNode() ? null : error.path("code").asText();
            message = error.path("message").isMissingNode() ? null : error.path("message").asText();
        }
        return new Response(result.getResponse().getStatus(), code, message, body);
    }

    private Long template(Long teamId, String name, String fieldKey, String fieldLabel) {
        ActivityTemplateEntity template = ActivityTemplateEntity.builder().scopeType(ActivityScopeType.TEAM)
                .scopeId(teamId).name(name).isParticipantRequired(false)
                .defaultVisibility(ActivityVisibility.PUBLIC).sortOrder(0)
                .createdBy(fixture.user(Actor.ADMIN)).build();
        em.persist(template);
        em.flush();
        em.persist(ActivityTemplateFieldEntity.builder().templateId(template.getId()).fieldKey(fieldKey)
                .fieldLabel(fieldLabel).fieldType(FieldType.NUMBER).isRequired(false).isAggregatable(true)
                .sortOrder(0).build());
        em.flush();
        return template.getId();
    }

    private record Response(int status, String code, String message, String body) {
        @Override
        public String toString() {
            return status + " " + code + " " + message;
        }
    }
}
