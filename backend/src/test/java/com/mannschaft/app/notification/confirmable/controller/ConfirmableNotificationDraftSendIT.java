package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.membership.domain.RoleKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261126-1558 試練 AC7: draftId 付きの送信が成功すると下書きが DB から消え、失敗すると残る。
 *
 * <p>送信と下書き削除が「同じトランザクション」で確定することを実コミットで測るため、テストメソッドを
 * {@code @Transactional} で包まない（MockMvc の1リクエストがそのまま1トランザクションでコミット／ロールバック
 * される）。作ったデータは {@link #cleanUp()} で自分の分だけ消す（金型: {@code ConfirmableNotificationCreateAtomicityIT}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261126-1558 AC7: draftId 付き送信と下書きの削除（実コミット）")
class ConfirmableNotificationDraftSendIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private Long orgId;
    private Long adminUserId;
    private Long memberUserId;
    private final List<Long> userIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        seedRoles();
        orgId = insertOrganization();
        adminUserId = insertUser();
        grantRole(adminUserId, "ADMIN", orgId);
        // 送信者本人は展開結果から除かれるため、受信者になる MEMBER を1名置く（RECIPIENTS_EMPTY を避ける）。
        memberUserId = insertUser();
        grantRole(memberUserId, "MEMBER", orgId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminUserId.toString(), null, List.of()));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> notificationIds = jdbc.queryForList(
                "SELECT id FROM confirmable_notifications WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                Long.class, orgId);
        for (Long id : notificationIds) {
            jdbc.update("DELETE FROM notification_fanout_jobs WHERE scope_type = 'CONFIRMABLE_TARGETS' AND scope_ref = ?",
                    String.valueOf(id));
            jdbc.update("DELETE FROM confirmable_notification_targets WHERE confirmable_notification_id = ?", id);
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", id);
        }
        jdbc.update("DELETE FROM confirmable_notification_settings WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                orgId);
        for (Long userId : userIds) {
            jdbc.update("DELETE FROM notification_fanout_jobs WHERE scope_type = 'CONFIRMABLE_TARGETS' AND actor_id = ?",
                    userId);
            jdbc.update("DELETE FROM confirmable_notification_drafts WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    @DisplayName("draftId 付きの送信が 202 で成功すると、その下書きは DB から消える")
    void successfulSendDeletesDraft() throws Exception {
        UUID draftId = insertDraft(adminUserId, orgId, "送信する下書き");
        UUID otherDraft = insertDraft(adminUserId, orgId, "別の下書き");

        MvcResult result = send(body(draftId, null));

        assertThat(result.getResponse().getStatus()).as("前提: 送信は受け付けられる").isEqualTo(202);
        assertThat(countDraft(draftId)).as("送信元の下書きは消える").isZero();
        assertThat(countDraft(otherDraft)).as("同じ利用者の別の下書きは残る").isEqualTo(1);
    }

    @Test
    @DisplayName("送信が失敗（過去の deadlineAt で 400 DEADLINE_IN_PAST）すると、下書きは残る")
    void failedSendKeepsDraft() throws Exception {
        UUID draftId = insertDraft(adminUserId, orgId, "失敗する送信の下書き");
        String pastDeadline = LocalDateTime.now().minusHours(1).atOffset(ZoneOffset.ofHours(9)).toString();

        MvcResult result = send(body(draftId, pastDeadline));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText())
                .isEqualTo("CONFIRMABLE_NOTIFICATION_DEADLINE_IN_PAST");
        assertThat(countDraft(draftId)).as("失敗した送信では下書きを消さない").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notifications WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                Long.class, orgId)).as("前提: 失敗した送信は通知も作らない").isZero();
    }

    @Test
    @DisplayName("他人（同じ組織の別の管理者）の draftId を付けて送信しても、その下書きは消えない")
    void othersDraftIsNotDeleted() throws Exception {
        Long otherAdmin = insertUser();
        grantRole(otherAdmin, "ADMIN", orgId);
        UUID othersDraft = insertDraft(otherAdmin, orgId, "他人の下書き");

        MvcResult result = send(body(othersDraft, null));

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(countDraft(othersDraft)).as("他人の下書きは消えない").isEqualTo(1);
    }

    @Test
    @DisplayName("別スコープ（自分が管理者の別組織）の draftId を付けて送信しても、その下書きは消えない")
    void otherScopeDraftIsNotDeleted() throws Exception {
        Long otherOrg = insertOrganization();
        try {
            grantRole(adminUserId, "ADMIN", otherOrg);
            UUID otherScopeDraft = insertDraft(adminUserId, otherOrg, "別組織の下書き");

            MvcResult result = send(body(otherScopeDraft, null));

            assertThat(result.getResponse().getStatus()).isEqualTo(202);
            assertThat(countDraft(otherScopeDraft)).as("別スコープの下書きは消えない").isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM confirmable_notification_drafts WHERE scope_type = 'ORGANIZATION' AND scope_id = ?",
                    otherOrg);
            jdbc.update("DELETE FROM user_roles WHERE organization_id = ?", otherOrg);
            jdbc.update("DELETE FROM memberships WHERE scope_type = 'ORGANIZATION' AND scope_id = ?", otherOrg);
            jdbc.update("DELETE FROM organizations WHERE id = ?", otherOrg);
        }
    }

    @Test
    @DisplayName("draftId を省略した送信は従来どおり成功し、下書きに触れない")
    void sendWithoutDraftIdKeepsDrafts() throws Exception {
        UUID draftId = insertDraft(adminUserId, orgId, "触れない下書き");

        MvcResult result = send(body(null, null));

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(countDraft(draftId)).isEqualTo(1);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private Map<String, Object> body(UUID draftId, String deadlineAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "AC7 下書きからの送信");
        if (draftId != null) {
            body.put("draftId", draftId.toString());
        }
        if (deadlineAt != null) {
            body.put("deadlineAt", deadlineAt);
        }
        return body;
    }

    private MvcResult send(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/v1/organizations/{id}/confirmable-notifications", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    private UUID insertDraft(Long userId, Long scopeId, String title) {
        UUID id = UuidV7.generate();
        jdbc.update("INSERT INTO confirmable_notification_drafts "
                        + "(id, scope_type, scope_id, user_id, title, priority, audience_mode, version, created_at, updated_at) "
                        + "VALUES (UNHEX(?), 'ORGANIZATION', ?, ?, ?, 'NORMAL', 'DEFAULT', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id.toString().replace("-", ""), scopeId, userId, title);
        return id;
    }

    private long countDraft(UUID id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM confirmable_notification_drafts WHERE id = UNHEX(?)",
                Long.class, id.toString().replace("-", ""));
        return n == null ? 0 : n;
    }

    private void seedRoles() {
        String[][] roles = {{"SYSTEM_ADMIN", "1"}, {"ADMIN", "2"}, {"DEPUTY_ADMIN", "3"},
                {"MEMBER", "4"}, {"SUPPORTER", "5"}, {"GUEST", "6"}};
        for (String[] role : roles) {
            jdbc.update("INSERT IGNORE INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                    + "VALUES (?, ?, ?, 1, NOW(), NOW())", role[0], role[0], Integer.parseInt(role[1]));
        }
    }

    private Long insertUser() {
        String email = "cnd-send-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users (email, last_name, first_name, display_name, status, "
                + "is_searchable, handle_searchable, contact_approval_required, "
                + "online_visibility, dm_receive_from, encryption_key_version, "
                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, created_at, updated_at) "
                + "VALUES (?, 'CND', '送信', 'CND 送信', 'ACTIVE', 1, 1, 1, "
                + "'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())", email);
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        userIds.add(id);
        return id;
    }

    private Long insertOrganization() {
        String name = "CND送信組織-" + UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                + "supporter_enabled, version, slug, created_at, updated_at) "
                + "VALUES (?, 'OTHER', 'PUBLIC', 'NONE', 1, 0, CONCAT('cnd-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())",
                name);
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class, name);
    }

    private void grantRole(Long userId, String roleName, Long orgIdParam) {
        if (!"MEMBER".equals(roleName)) {
            // 一般メンバーは memberships のみで表現する（user_roles へは張らない）
            grantUserRole(userId, roleName, orgIdParam);
        }
        jdbc.update("INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, created_at, updated_at) "
                + "SELECT ?, 'ORGANIZATION', ?, ?, NOW(), NOW(), NOW() "
                + "WHERE NOT EXISTS (SELECT 1 FROM memberships m WHERE m.user_id = ? "
                + "AND m.scope_type = 'ORGANIZATION' AND m.scope_id = ? AND m.left_at IS NULL)",
                userId, orgIdParam, RoleKind.MEMBER.name(), userId, orgIdParam);
    }

    private void grantUserRole(Long userId, String roleName, Long orgIdParam) {
        jdbc.update("INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                + "SELECT ?, r.id, NULL, ?, NOW(), NOW() FROM roles r WHERE r.name = ?", userId, orgIdParam, roleName);
    }
}
