package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.support.test.MembershipTestHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261126-1558 試練: 下書き API の経路・認可・所有者/スコープ分離を実 MySQL + MockMvc で検証する。
 *
 * <h2>AC ↔ テスト</h2>
 * <ul>
 *   <li>AC8: {@link Routing} — {@code /drafts} と既存の {@code /{notificationId}} を取り違えない</li>
 *   <li>AC10: {@link Authorization} — ADMIN と SEND_NOTIFICATION を持つ DEPUTY は許可。
 *       持たない DEPUTY・MEMBER・非メンバーは全メソッドで 403（チーム版・組織版）</li>
 *   <li>AC11: {@link OwnerIsolation} — 同じスコープの別ユーザーの下書きは GET・PUT・DELETE とも 404、一覧にも出ない</li>
 *   <li>AC12: {@link ScopeIsolation} — 別スコープ、および同じ番号の Team と Org の下書きが混ざらない</li>
 *   <li>AC2/AC3 の HTTP 対応: {@link StatusMapping} — 上限超過・版競合は 409 と各 errorCode</li>
 * </ul>
 *
 * <p>金型は {@link ConfirmableRecipientGroupAndSendAuthzIT}（{@code addFilters=false}・手動 SecurityContext・
 * V216 migration 本文でカタログ行を作る手法）。下書きは本 API を経由せず native INSERT で用意し、
 * 落ちた原因が「検証したい振る舞い」そのものになるようにする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261126-1558 下書き API 試練（AC8・AC10・AC11・AC12）")
class ConfirmableNotificationDraftApiAuthzIT extends AbstractMySqlIntegrationTest {

    private static final String PERMISSION = "SEND_NOTIFICATION";
    private static final String MIGRATION_RESOURCE =
            "db/migration/V216.20260918083734__add_send_notification_permission.sql";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long orgId;

    /** 下書き API の基底パス。 */
    private record Scope(String type, Long id) {
        String base() {
            return ("TEAM".equals(type) ? "/api/v1/teams/" : "/api/v1/organizations/") + id
                    + "/confirmable-notifications/drafts";
        }
    }

    private Scope team() {
        return new Scope("TEAM", teamId);
    }

    private Scope org() {
        return new Scope("ORGANIZATION", orgId);
    }

    @BeforeEach
    void setUp() {
        seedRoles();
        seedSendNotificationFromMigration();
        teamId = insertTeam(null);
        orgId = insertOrganization();
        em.flush();
    }

    // =====================================================================
    // AC8
    // =====================================================================

    @Nested
    @DisplayName("AC8: /drafts と /{notificationId} を取り違えない")
    class Routing {

        @Test
        @DisplayName("GET .../drafts は下書き一覧（200・配列）を返し、notificationId=\"drafts\" として扱わない（チーム・組織）")
        void listIsRoutedToDrafts() throws Exception {
            for (Scope scope : List.of(team(), org())) {
                Long admin = adminOf(scope);
                insertDraft(scope, admin, "一覧に出る");
                setAuth(admin);

                MvcResult result = mockMvc.perform(get(scope.base())).andReturn();

                assertThat(result.getResponse().getStatus()).as("%s GET /drafts", scope.type()).isEqualTo(200);
                JsonNode data = data(result);
                assertThat(data.isArray()).as("data は配列").isTrue();
                assertThat(data).hasSize(1);
                assertThat(data.get(0).path("title").asText()).isEqualTo("一覧に出る");
            }
        }

        @Test
        @DisplayName("GET .../drafts/{draftId} は下書き詳細を返し、存在しない UUID は DRAFT_NOT_FOUND（型不一致の400にしない）")
        void detailIsRoutedToDrafts() throws Exception {
            Scope scope = team();
            Long admin = adminOf(scope);
            UUID draftId = insertDraft(scope, admin, "詳細");
            setAuth(admin);

            MvcResult found = mockMvc.perform(get(scope.base() + "/" + draftId)).andReturn();
            assertThat(found.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(found).path("id").asText()).isEqualTo(draftId.toString());
            assertThat(data(found).has("version")).as("詳細は version を含む").isTrue();

            MvcResult missing = mockMvc.perform(get(scope.base() + "/" + UuidV7.generate())).andReturn();
            assertError(missing, 404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
        }

        @Test
        @DisplayName("既存の GET .../{notificationId} は確認通知の詳細のまま（下書き経路に吸われない）")
        void notificationDetailStillWorks() throws Exception {
            Scope scope = org();
            Long admin = adminOf(scope);
            Long member = insertUser();
            grantRole(member, "MEMBER", scope);
            em.flush();
            setAuth(admin);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "AC8 既存経路");
            MvcResult sent = mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn();
            assertThat(sent.getResponse().getStatus()).as("前提: 既存の送信が受け付けられる").isEqualTo(202);
            long notificationId = data(sent).path("id").asLong();

            MvcResult detail = mockMvc.perform(
                    get("/api/v1/organizations/{id}/confirmable-notifications/{nid}", orgId, notificationId)).andReturn();
            assertThat(detail.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(detail).path("title").asText()).isEqualTo("AC8 既存経路");

            MvcResult drafts = mockMvc.perform(get(scope.base())).andReturn();
            assertThat(drafts.getResponse().getStatus()).isEqualTo(200);
            assertThat(data(drafts).isArray()).isTrue();
        }
    }

    // =====================================================================
    // AC10
    // =====================================================================

    @Nested
    @DisplayName("AC10: SEND_NOTIFICATION 認可（全メソッド）")
    class Authorization {

        @Test
        @DisplayName("ADMIN は 一覧200・作成201・取得200・更新200・削除204（チーム・組織）")
        void adminIsAllowed() throws Exception {
            for (Scope scope : List.of(team(), org())) {
                Long admin = insertUser();
                grantRole(admin, "ADMIN", scope);
                em.flush();
                assertAllowed(scope, admin);
            }
        }

        @Test
        @DisplayName("SEND_NOTIFICATION を持つ DEPUTY_ADMIN は許可される（チーム・組織）")
        void deputyWithPermissionIsAllowed() throws Exception {
            for (Scope scope : List.of(team(), org())) {
                Long deputy = insertUser();
                grantRole(deputy, "DEPUTY_ADMIN", scope);
                em.flush();
                assertAllowed(scope, deputy);
            }
        }

        @Test
        @DisplayName("SEND_NOTIFICATION を外された DEPUTY_ADMIN は全メソッド 403（チーム・組織）")
        void deputyWithoutPermissionIsForbidden() throws Exception {
            revokeDeputySendNotification();
            for (Scope scope : List.of(team(), org())) {
                Long deputy = insertUser();
                grantRole(deputy, "DEPUTY_ADMIN", scope);
                em.flush();
                assertForbiddenEverywhere(scope, deputy);
            }
        }

        @Test
        @DisplayName("MEMBER は全メソッド 403（チーム・組織）")
        void memberIsForbidden() throws Exception {
            for (Scope scope : List.of(team(), org())) {
                Long member = insertUser();
                grantRole(member, "MEMBER", scope);
                em.flush();
                assertForbiddenEverywhere(scope, member);
            }
        }

        @Test
        @DisplayName("非メンバーは全メソッド 403（チーム・組織）")
        void outsiderIsForbidden() throws Exception {
            for (Scope scope : List.of(team(), org())) {
                assertForbiddenEverywhere(scope, insertUser());
            }
        }

        private void assertAllowed(Scope scope, Long userId) throws Exception {
            setAuth(userId);
            assertThat(status(get(scope.base()))).as("%s 一覧", scope.type()).isEqualTo(200);

            MvcResult created = mockMvc.perform(json(post(scope.base()), Map.of("title", "認可試練"))).andReturn();
            assertThat(created.getResponse().getStatus()).as("%s 作成", scope.type()).isEqualTo(201);
            String draftId = data(created).path("id").asText();
            long version = data(created).path("version").asLong();
            // 作成直後の応答でも createdAt / updatedAt が入っている（save だけだと flush 前で null になる）
            assertThat(data(created).path("createdAt").isNull()).as("%s 作成応答の createdAt", scope.type()).isFalse();
            assertThat(data(created).path("updatedAt").isNull()).as("%s 作成応答の updatedAt", scope.type()).isFalse();

            assertThat(status(get(scope.base() + "/" + draftId))).as("%s 取得", scope.type()).isEqualTo(200);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("title", "更新");
            update.put("baseVersion", version);
            assertThat(status(json(put(scope.base() + "/" + draftId), update)))
                    .as("%s 更新", scope.type()).isEqualTo(200);
            assertThat(status(delete(scope.base() + "/" + draftId))).as("%s 削除", scope.type()).isEqualTo(204);
        }

        private void assertForbiddenEverywhere(Scope scope, Long userId) throws Exception {
            // 本人名義の下書きを置き、403 が「対象が無い」ではなく認可で起きていることを確かめる。
            UUID existing = insertDraft(scope, userId, "本人名義でも権限が無ければ403");
            em.flush();
            setAuth(userId);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("title", "x");
            update.put("baseVersion", 0);

            assertThat(status(get(scope.base()))).as("%s 一覧", scope.type()).isEqualTo(403);
            assertThat(status(json(post(scope.base()), Map.of("title", "x")))).as("%s 作成", scope.type()).isEqualTo(403);
            assertThat(status(get(scope.base() + "/" + existing))).as("%s 取得", scope.type()).isEqualTo(403);
            assertThat(status(json(put(scope.base() + "/" + existing), update))).as("%s 更新", scope.type()).isEqualTo(403);
            assertThat(status(delete(scope.base() + "/" + existing))).as("%s 削除", scope.type()).isEqualTo(403);
            assertThat(countDraft(existing)).as("403 のとき下書きは消えない").isEqualTo(1);
        }
    }

    // =====================================================================
    // AC11
    // =====================================================================

    @Nested
    @DisplayName("AC11: 同じスコープの別ユーザーの下書き")
    class OwnerIsolation {

        @Test
        @DisplayName("別ユーザー（同じスコープの ADMIN）の下書きは GET・PUT・DELETE とも 404 で、一覧にも出ない（チーム・組織）")
        void othersDraftIsInvisible() throws Exception {
            for (Scope scope : List.of(team(), org())) {
                Long me = adminOf(scope);
                Long other = adminOf(scope);
                UUID othersDraft = insertDraft(scope, other, "他人の下書き");
                UUID mine = insertDraft(scope, me, "自分の下書き");
                em.flush();
                setAuth(me);

                MvcResult list = mockMvc.perform(get(scope.base())).andReturn();
                assertThat(list.getResponse().getStatus()).isEqualTo(200);
                List<String> ids = new ArrayList<>();
                data(list).forEach(n -> ids.add(n.path("id").asText()));
                assertThat(ids).as("%s 一覧は自分の分だけ", scope.type()).containsExactly(mine.toString());

                Map<String, Object> update = new LinkedHashMap<>();
                update.put("title", "乗っ取り");
                update.put("baseVersion", 0);
                MvcResult missingPut = mockMvc.perform(json(put(scope.base() + "/" + UuidV7.generate()), update)).andReturn();
                assertError(mockMvc.perform(get(scope.base() + "/" + othersDraft)).andReturn(),
                        404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
                MvcResult othersPut = mockMvc.perform(json(put(scope.base() + "/" + othersDraft), update)).andReturn();
                assertError(othersPut, 404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
                assertThat(errorOf(othersPut))
                        .as("他人の下書きへの PUT は、存在しない ID への PUT と同じ応答（存在を推測させない）")
                        .isEqualTo(errorOf(missingPut));
                assertError(mockMvc.perform(delete(scope.base() + "/" + othersDraft)).andReturn(),
                        404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");

                assertThat(countDraft(othersDraft)).as("他人の下書きは消えない").isEqualTo(1);
                assertThat(titleOf(othersDraft)).as("他人の下書きは書き換わらない").isEqualTo("他人の下書き");
            }
        }
    }

    // =====================================================================
    // AC12
    // =====================================================================

    @Nested
    @DisplayName("AC12: スコープの分離")
    class ScopeIsolation {

        @Test
        @DisplayName("自分が ADMIN の別チーム・別組織の下書きは、こちらのスコープの一覧に出ず、draftId 指定でも 404")
        void otherScopeOfSameUserIsIsolated() throws Exception {
            for (String type : List.of("TEAM", "ORGANIZATION")) {
                Scope here = "TEAM".equals(type) ? team() : org();
                Scope there = "TEAM".equals(type) ? new Scope("TEAM", insertTeam(null))
                        : new Scope("ORGANIZATION", insertOrganization());
                Long me = adminOf(here);
                grantRole(me, "ADMIN", there);
                UUID inThere = insertDraft(there, me, "別スコープ");
                em.flush();
                setAuth(me);

                MvcResult list = mockMvc.perform(get(here.base())).andReturn();
                assertThat(list.getResponse().getStatus()).isEqualTo(200);
                assertThat(data(list)).as("%s 別スコープの下書きは出ない", type).isEmpty();
                assertError(mockMvc.perform(get(here.base() + "/" + inThere)).andReturn(),
                        404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
                assertError(mockMvc.perform(delete(here.base() + "/" + inThere)).andReturn(),
                        404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
                assertThat(countDraft(inThere)).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("同じ番号の Team と Org の下書きは混ざらない（チーム側からも組織側からも）")
        void sameNumberTeamAndOrgDoNotMix() throws Exception {
            Long sameId = nextFreeIdForBoth();
            insertOrganizationWithId(sameId);
            insertTeam(sameId);
            Scope teamScope = new Scope("TEAM", sameId);
            Scope orgScope = new Scope("ORGANIZATION", sameId);
            Long me = insertUser();
            grantRole(me, "ADMIN", teamScope);
            grantRole(me, "ADMIN", orgScope);
            UUID teamDraft = insertDraft(teamScope, me, "チームの下書き");
            UUID orgDraft = insertDraft(orgScope, me, "組織の下書き");
            em.flush();
            setAuth(me);

            MvcResult teamList = mockMvc.perform(get(teamScope.base())).andReturn();
            MvcResult orgList = mockMvc.perform(get(orgScope.base())).andReturn();
            assertThat(teamList.getResponse().getStatus()).isEqualTo(200);
            assertThat(orgList.getResponse().getStatus()).isEqualTo(200);
            List<String> teamIds = new ArrayList<>();
            data(teamList).forEach(n -> teamIds.add(n.path("id").asText()));
            List<String> orgIds = new ArrayList<>();
            data(orgList).forEach(n -> orgIds.add(n.path("id").asText()));
            assertThat(teamIds).containsExactly(teamDraft.toString());
            assertThat(orgIds).containsExactly(orgDraft.toString());

            assertError(mockMvc.perform(get(teamScope.base() + "/" + orgDraft)).andReturn(),
                    404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
            assertError(mockMvc.perform(get(orgScope.base() + "/" + teamDraft)).andReturn(),
                    404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("title", "混線");
            update.put("baseVersion", 0);
            assertError(mockMvc.perform(json(put(teamScope.base() + "/" + orgDraft), update)).andReturn(),
                    404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
            assertError(mockMvc.perform(delete(orgScope.base() + "/" + teamDraft)).andReturn(),
                    404, "CONFIRMABLE_NOTIFICATION_DRAFT_NOT_FOUND");
            assertThat(countDraft(teamDraft)).isEqualTo(1);
            assertThat(countDraft(orgDraft)).isEqualTo(1);
            assertThat(titleOf(orgDraft)).isEqualTo("組織の下書き");
        }

        @Test
        @DisplayName("上限10件は スコープ×作成者 ごと: 同じ番号の Team に10件あっても Org 側には作成できる")
        void limitIsPerScope() throws Exception {
            Long sameId = nextFreeIdForBoth();
            insertOrganizationWithId(sameId);
            insertTeam(sameId);
            Scope teamScope = new Scope("TEAM", sameId);
            Scope orgScope = new Scope("ORGANIZATION", sameId);
            Long me = insertUser();
            grantRole(me, "ADMIN", teamScope);
            grantRole(me, "ADMIN", orgScope);
            for (int i = 0; i < 10; i++) {
                insertDraft(teamScope, me, "チーム" + i);
            }
            em.flush();
            setAuth(me);

            assertError(mockMvc.perform(json(post(teamScope.base()), Map.of("title", "11件目"))).andReturn(),
                    409, "CONFIRMABLE_NOTIFICATION_DRAFT_LIMIT_EXCEEDED");
            assertThat(status(json(post(orgScope.base()), Map.of("title", "組織1件目")))).isEqualTo(201);
        }
    }

    // =====================================================================
    // AC2 / AC3 の HTTP ステータス対応
    // =====================================================================

    @Nested
    @DisplayName("AC2・AC3: 409 の対応付け")
    class StatusMapping {

        @Test
        @DisplayName("11件目の POST は 409 CONFIRMABLE_NOTIFICATION_DRAFT_LIMIT_EXCEEDED")
        void limitExceededIs409() throws Exception {
            Scope scope = team();
            Long admin = adminOf(scope);
            for (int i = 0; i < 10; i++) {
                insertDraft(scope, admin, "下書き" + i);
            }
            em.flush();
            setAuth(admin);

            assertError(mockMvc.perform(json(post(scope.base()), Map.of("title", "11件目"))).andReturn(),
                    409, "CONFIRMABLE_NOTIFICATION_DRAFT_LIMIT_EXCEEDED");
            assertThat(countOwn(scope, admin)).isEqualTo(10);
        }

        @Test
        @DisplayName("baseVersion 不一致の PUT は 409 CONFIRMABLE_NOTIFICATION_DRAFT_VERSION_CONFLICT")
        void versionConflictIs409() throws Exception {
            Scope scope = org();
            Long admin = adminOf(scope);
            UUID draftId = insertDraft(scope, admin, "保存済み");
            em.createNativeQuery("UPDATE confirmable_notification_drafts SET version = 5 WHERE id = UNHEX(:id)")
                    .setParameter("id", hex(draftId)).executeUpdate();
            em.flush();
            setAuth(admin);
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("title", "古い版");
            update.put("baseVersion", 4);

            assertError(mockMvc.perform(json(put(scope.base() + "/" + draftId), update)).andReturn(),
                    409, "CONFIRMABLE_NOTIFICATION_DRAFT_VERSION_CONFLICT");
            assertThat(titleOf(draftId)).isEqualTo("保存済み");
        }

        @Test
        @DisplayName("上限を超える入力（title 201文字）は 400")
        void validationIs400() throws Exception {
            Scope scope = team();
            Long admin = adminOf(scope);
            setAuth(admin);

            assertThat(status(json(post(scope.base()), Map.of("title", "a".repeat(201))))).isEqualTo(400);
            assertThat(countOwn(scope, admin)).isZero();
        }
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private int status(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Object body) throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedCode) throws Exception {
        assertThat(result.getResponse().getStatus()).as("HTTP ステータス").isEqualTo(expectedStatus);
        String content = result.getResponse().getContentAsString();
        String code = content == null || content.isBlank() ? "<empty>"
                : objectMapper.readTree(content).path("error").path("code").asText("<no-error-code>");
        assertThat(code).as("errorCode").isEqualTo(expectedCode);
    }

    /** ステータス・errorCode・message の組（時刻等の可変項目は除く）。 */
    private String errorOf(MvcResult result) throws Exception {
        JsonNode error = objectMapper.readTree(result.getResponse().getContentAsString()).path("error");
        return result.getResponse().getStatus() + "|" + error.path("code").asText() + "|" + error.path("message").asText();
    }

    private Long adminOf(Scope scope) {
        Long admin = insertUser();
        grantRole(admin, "ADMIN", scope);
        em.flush();
        return admin;
    }

    private static String hex(UUID id) {
        return id.toString().replace("-", "");
    }

    private UUID insertDraft(Scope scope, Long userId, String title) {
        UUID id = UuidV7.generate();
        em.createNativeQuery("INSERT INTO confirmable_notification_drafts "
                        + "(id, scope_type, scope_id, user_id, title, body, priority, audience_mode, version, "
                        + "created_at, updated_at) VALUES (UNHEX(:id), :st, :sid, :uid, :title, '本文', 'NORMAL', "
                        + "'DEFAULT', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
                .setParameter("id", hex(id))
                .setParameter("st", scope.type())
                .setParameter("sid", scope.id())
                .setParameter("uid", userId)
                .setParameter("title", title)
                .executeUpdate();
        return id;
    }

    private long countDraft(UUID id) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM confirmable_notification_drafts WHERE id = UNHEX(:id)")
                .setParameter("id", hex(id)).getSingleResult()).longValue();
    }

    private long countOwn(Scope scope, Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM confirmable_notification_drafts "
                        + "WHERE scope_type = :st AND scope_id = :sid AND user_id = :uid")
                .setParameter("st", scope.type()).setParameter("sid", scope.id()).setParameter("uid", userId)
                .getSingleResult()).longValue();
    }

    private String titleOf(UUID id) {
        return (String) em.createNativeQuery(
                        "SELECT title FROM confirmable_notification_drafts WHERE id = UNHEX(:id)")
                .setParameter("id", hex(id)).getSingleResult();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private void seedRoles() {
        for (String[] role : new String[][] {{"SYSTEM_ADMIN", "1"}, {"ADMIN", "2"}, {"DEPUTY_ADMIN", "3"},
                {"MEMBER", "4"}, {"SUPPORTER", "5"}, {"GUEST", "6"}}) {
            em.createNativeQuery(
                            "INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, :priority, 1, NOW(), NOW())")
                    .setParameter("name", role[0])
                    .setParameter("priority", Integer.parseInt(role[1]))
                    .executeUpdate();
        }
        em.flush();
    }

    /** CMP-260909-1141 の migration 本文をそのまま実行してカタログ行・DEPUTY_ADMIN 既定付与行を作る。 */
    private void seedSendNotificationFromMigration() {
        String raw;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION_RESOURCE)) {
            assertThat(in).as("migration ファイルが classpath 上に存在すること: " + MIGRATION_RESOURCE).isNotNull();
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("migration ファイルの読み出しに失敗した: " + MIGRATION_RESOURCE, e);
        }
        StringBuilder stripped = new StringBuilder();
        for (String line : raw.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("--")) {
                stripped.append(line).append('\n');
            }
        }
        for (String part : stripped.toString().split(";")) {
            String sql = part.trim();
            if (!sql.isEmpty()) {
                em.createNativeQuery(sql).executeUpdate();
            }
        }
        em.flush();
    }

    /** 「運用で DEPUTY_ADMIN から送信権限を外した」状態（role_permissions はロール単位のグローバル表）。 */
    private void revokeDeputySendNotification() {
        em.createNativeQuery(
                        "DELETE rp FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name = :perm")
                .setParameter("perm", PERMISSION)
                .executeUpdate();
        em.flush();
    }

    private Long insertUser() {
        int n = SEQ.incrementAndGet();
        String email = "cnd-authz-" + n + "-" + UUID.randomUUID() + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, 'CND', :fn, :dn, 'ACTIVE', 1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("fn", "利用者" + n)
                .setParameter("dn", "CND 利用者" + n)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    /** チームを作る。{@code id} を渡すとその番号で作る（同じ番号の Team と Org を作るため）。 */
    private Long insertTeam(Long id) {
        String name = "CNDチーム-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO teams (" + (id != null ? "id, " : "")
                                + "name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at) "
                                + "VALUES (" + (id != null ? id + ", " : "")
                                + ":name, 'PUBLIC', 1, 0, 0, CONCAT('cnd-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    private Long insertOrganization() {
        return insertOrganizationWithId(null);
    }

    private Long insertOrganizationWithId(Long id) {
        String name = "CND組織-" + SEQ.incrementAndGet() + "-" + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO organizations (" + (id != null ? "id, " : "")
                                + "name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (" + (id != null ? id + ", " : "")
                                + ":name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cnd-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    /** teams と organizations のどちらでも未使用の番号。 */
    private Long nextFreeIdForBoth() {
        long maxTeam = ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM teams")
                .getSingleResult()).longValue();
        long maxOrg = ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM organizations")
                .getSingleResult()).longValue();
        return Math.max(maxTeam, maxOrg) + 1000;
    }

    /** ロール付与＋当該スコープへの ACTIVE membership。 */
    private void grantRole(Long userId, String roleName, Scope scope) {
        boolean isTeam = "TEAM".equals(scope.type());
        if ("MEMBER".equals(roleName)) {
            // 一般メンバーは memberships のみで表現する（user_roles へは張らない）
            MembershipTestHelper.insertMembership(em, userId,
                    isTeam ? com.mannschaft.app.membership.domain.ScopeType.TEAM
                            : com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION,
                    scope.id(), com.mannschaft.app.membership.domain.RoleKind.MEMBER);
            return;
        }
        MembershipTestHelper.insertUserRole(em, userId, roleName,
                isTeam ? scope.id() : null, isTeam ? null : scope.id());
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                                + "SELECT :uid, :st, :sid, 'MEMBER', NOW(), NOW(), NOW() "
                                + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = :uid "
                                + "AND m.scope_type = :st AND m.scope_id = :sid AND m.left_at IS NULL)")
                .setParameter("uid", userId)
                .setParameter("st", scope.type())
                .setParameter("sid", scope.id())
                .executeUpdate();
    }
}
