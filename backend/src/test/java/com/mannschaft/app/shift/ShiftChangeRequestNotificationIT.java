package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * シフト変更依頼の通知（作成・審査・取り下げ）が、仕様どおりの宛先にだけ通知行を作ることの実 DB 検証（試練）。
 *
 * <p>仕様: {@code docs/features/F03.5_shift/01_db_design.md}（shift_change_requests「通知」）と
 * {@code 02_api_design.md}（変更依頼 POST / review / DELETE）。</p>
 * <ul>
 *   <li>作成（OPEN）: 管理者全員（ADMIN ＋ MANAGE_SHIFTS を持つ DEPUTY_ADMIN）へ。依頼者本人（管理者が自分で出した）は除く。</li>
 *   <li>承認・却下: 依頼者へ。却下は審査コメントを本文に含める。</li>
 *   <li>取り下げ: 管理者全員へ。依頼者本人（管理者）は除く。</li>
 *   <li>他チームの管理者・一般メンバー・MANAGE_SHIFTS 無しの副管理者には作られない。</li>
 * </ul>
 *
 * <p>クラスに {@code @Transactional} は付けない（コミットが起きないと AFTER_COMMIT が発火せず偽の緑になる）。
 * フィクスチャは {@link TransactionTemplate} で明示的にコミットする。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト変更依頼の通知（作成・審査・取り下げ）の宛先検証（試練）")
class ShiftChangeRequestNotificationIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/shifts/change-requests";
    private static final String TYPE_CREATED = "SHIFT_CHANGE_REQUEST_CREATED";
    private static final String TYPE_REVIEWED = "SHIFT_CHANGE_REQUEST_REVIEWED";
    private static final String TYPE_WITHDRAWN = "SHIFT_CHANGE_REQUEST_WITHDRAWN";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftChangeRequestRepository changeRequestRepository;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private TransactionTemplate transactionTemplate;
    @PersistenceContext private EntityManager em;

    private Long teamId;
    private String teamSlug;
    private Long adminId;            // チーム ADMIN（宛先）
    private Long deputyWithId;       // DEPUTY_ADMIN ＋ MANAGE_SHIFTS（宛先）
    private Long deputyWithoutId;    // DEPUTY_ADMIN だが MANAGE_SHIFTS 無し（宛先外）
    private Long memberId;           // 依頼者になる一般メンバー
    private Long otherMemberId;      // 同じチームの別の一般メンバー（宛先外）
    private Long otherTeamAdminId;   // 他チームの ADMIN（宛先外）
    private Long leftAdminId;        // ロールだけ残って退会済み（memberships.left_at 設定済み）の ADMIN（審査できるので宛先。CMP-261125-2114）
    private Long scheduleId;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        transactionTemplate.executeWithoutResult(tx -> {
            String sfx = String.valueOf(System.nanoTime());
            teamId = insertTeam("cr-notify-team-" + sfx);
            Long otherTeamId = insertTeam("cr-notify-other-" + sfx);
            teamSlug = (String) em.createNativeQuery("SELECT slug FROM teams WHERE id = :id")
                    .setParameter("id", teamId).getSingleResult();
            adminId = insertUser("cr-admin-" + sfx + "@example.com");
            deputyWithId = insertUser("cr-deputy-with-" + sfx + "@example.com");
            deputyWithoutId = insertUser("cr-deputy-without-" + sfx + "@example.com");
            memberId = insertUser("cr-member-" + sfx + "@example.com");
            otherMemberId = insertUser("cr-member2-" + sfx + "@example.com");
            otherTeamAdminId = insertUser("cr-other-admin-" + sfx + "@example.com");
            leftAdminId = insertUser("cr-left-admin-" + sfx + "@example.com");

            for (Long uid : List.of(adminId, deputyWithId, deputyWithoutId, memberId, otherMemberId)) {
                MembershipTestHelper.insertMembership(em, uid, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            }
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertUserRole(em, deputyWithId, "DEPUTY_ADMIN", teamId, null);
            MembershipTestHelper.insertUserRole(em, deputyWithoutId, "DEPUTY_ADMIN", teamId, null);
            // 退会済み ADMIN: user_roles は残っているが所属（memberships）は left_at 済み。ADMIN の判定は user_roles だけを見る
            // （AccessControlService#isAdmin）ので審査はできる。通知の受信者を審査できる人と一致させるため宛先に含める。
            MembershipTestHelper.insertMembership(em, leftAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            em.createNativeQuery("UPDATE memberships SET left_at = NOW() WHERE user_id = :uid AND scope_id = :tid")
                    .setParameter("uid", leftAdminId).setParameter("tid", teamId).executeUpdate();
            MembershipTestHelper.insertUserRole(em, leftAdminId, "ADMIN", teamId, null);
            grantManageShiftsViaGroup(deputyWithId, teamId, sfx);

            MembershipTestHelper.insertMembership(em, otherTeamAdminId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, otherTeamAdminId, "ADMIN", otherTeamId, null);
            grantManageShiftsViaGroup(otherTeamAdminId, otherTeamId, sfx + "o");

            scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                    .teamId(teamId)
                    .title("変更依頼通知 " + sfx)
                    .periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 11, 1))
                    .endDate(LocalDate.of(2026, 11, 7))
                    .status(ShiftScheduleStatus.COLLECTING)
                    .createdBy(adminId)
                    .build()).getId();
        });
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("作成: 管理者全員（ADMIN と MANAGE_SHIFTS 付き副管理者）にだけ通知行が作られ、依頼者・他者・他チームには作られない")
    void createNotifiesAdminsOnly() throws Exception {
        setAuth(memberId);
        Long requestId = create(memberId);

        assertRecipients(TYPE_CREATED, requestId, Set.of(adminId, deputyWithId, leftAdminId));
        assertActionUrl(TYPE_CREATED, requestId, adminId);
    }

    @Test
    @DisplayName("作成: 管理者が自分で出した依頼では、依頼者本人（自己通知）には作られず、他の管理者にだけ作られる")
    void createByAdminDoesNotSelfNotify() throws Exception {
        setAuth(adminId);
        Long requestId = create(adminId);

        assertRecipients(TYPE_CREATED, requestId, Set.of(deputyWithId, leftAdminId));
    }

    @Test
    @DisplayName("承認: 依頼者だけに通知行が作られる（審査者・他の管理者・他者には作られない）")
    void acceptNotifiesRequesterOnly() throws Exception {
        Long requestId = insertRequest(memberId);

        setAuth(adminId);
        review(requestId, "ACCEPTED", "調整しました");

        assertRecipients(TYPE_REVIEWED, requestId, Set.of(memberId));
        assertActionUrl(TYPE_REVIEWED, requestId, memberId);
        // コメントあり: bodyWithNote（コメントを含む）
        assertThat(bodyOf(TYPE_REVIEWED, requestId, memberId))
                .contains("承認されました").contains("調整しました");
    }

    @Test
    @DisplayName("承認（コメントなし）: 本文はコメントなしの文言（コメント区切りを含まない）")
    void acceptWithoutCommentUsesPlainBody() throws Exception {
        Long requestId = insertRequest(memberId);

        setAuth(adminId);
        review(requestId, "ACCEPTED", null);

        assertRecipients(TYPE_REVIEWED, requestId, Set.of(memberId));
        assertThat(bodyOf(TYPE_REVIEWED, requestId, memberId)).isEqualTo("シフトの変更依頼が承認されました。");
    }

    @Test
    @DisplayName("管理者の宛先: role_permissions（is_default=1）経由で MANAGE_SHIFTS を持つ副管理者にも作られる")
    void deputyViaDefaultRolePermissionIsNotified() throws Exception {
        grantDefaultRolePermission();
        try {
            setAuth(memberId);
            Long requestId = create(memberId);

            // MANAGE_SHIFTS 無しだった副管理者も、ロール定義で既定付与されたので宛先になる。
            assertRecipients(TYPE_CREATED, requestId, Set.of(adminId, deputyWithId, deputyWithoutId, leftAdminId));
        } finally {
            revokeDefaultRolePermission();
        }
    }

    @Test
    @DisplayName("却下: 依頼者だけに通知行が作られ、本文に審査コメントが含まれる")
    void rejectNotifiesRequesterOnlyWithComment() throws Exception {
        Long requestId = insertRequest(memberId);

        setAuth(deputyWithId);
        review(requestId, "REJECTED", "人数が足りないため却下します");

        assertRecipients(TYPE_REVIEWED, requestId, Set.of(memberId));
        assertThat(bodyOf(TYPE_REVIEWED, requestId, memberId)).contains("人数が足りないため却下します");
    }

    @Test
    @DisplayName("取り下げ: 管理者全員にだけ通知行が作られ、依頼者・他者・他チームには作られない")
    void withdrawNotifiesAdminsOnly() throws Exception {
        Long requestId = insertRequest(memberId);

        setAuth(memberId);
        mockMvc.perform(delete(BASE + "/{id}", requestId)).andExpect(status().isNoContent());

        assertRecipients(TYPE_WITHDRAWN, requestId, Set.of(adminId, deputyWithId, leftAdminId));
    }

    @Test
    @DisplayName("取り下げ: 管理者が自分の依頼を取り下げても自己通知は作られず、他の管理者にだけ作られる")
    void withdrawByAdminDoesNotSelfNotify() throws Exception {
        Long requestId = insertRequest(adminId);

        setAuth(adminId);
        mockMvc.perform(delete(BASE + "/{id}", requestId)).andExpect(status().isNoContent());

        assertRecipients(TYPE_WITHDRAWN, requestId, Set.of(deputyWithId, leftAdminId));
    }

    @Test
    @DisplayName("拒否された操作（自己審査 403）では通知行は作られない")
    void deniedSelfReviewCreatesNoNotification() throws Exception {
        Long requestId = insertRequest(adminId);

        setAuth(adminId);
        mockMvc.perform(patch(BASE + "/{id}/review", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewBody("ACCEPTED", 0, "自己審査"))))
                .andExpect(status().isForbidden());

        await().during(2, TimeUnit.SECONDS).atMost(4, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(recipientsOf(TYPE_REVIEWED, requestId)).isEmpty());
    }

    // ── 検証ヘルパー ──

    /** 期待する宛先と完全一致するまで待ち、さらに少し待って余計な宛先が増えないことを確かめる。 */
    private void assertRecipients(String type, Long requestId, Set<Long> expected) {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(recipientsOf(type, requestId)).containsExactlyInAnyOrderElementsOf(expected));
        await().during(2, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(recipientsOf(type, requestId)).containsExactlyInAnyOrderElementsOf(expected));
    }

    private void assertActionUrl(String type, Long requestId, Long recipient) {
        String url = actionUrlOf(type, requestId, recipient);
        assertThat(url).isEqualTo("/teams/" + teamSlug + "/shifts/" + scheduleId + "/change-requests");
    }

    @SuppressWarnings("unchecked")
    private List<Long> recipientsOf(String type, Long requestId) {
        return transactionTemplate.execute(tx -> {
            List<Number> rows = em.createNativeQuery(
                            "SELECT user_id FROM notifications WHERE notification_type = :t "
                                    + "AND source_type = 'SHIFT_CHANGE_REQUEST' AND source_id = :sid")
                    .setParameter("t", type).setParameter("sid", requestId).getResultList();
            return rows.stream().map(Number::longValue).toList();
        });
    }

    private String actionUrlOf(String type, Long requestId, Long recipient) {
        return transactionTemplate.execute(tx -> (String) em.createNativeQuery(
                        "SELECT action_url FROM notifications WHERE notification_type = :t "
                                + "AND source_type = 'SHIFT_CHANGE_REQUEST' AND source_id = :sid AND user_id = :uid")
                .setParameter("t", type).setParameter("sid", requestId).setParameter("uid", recipient)
                .getSingleResult());
    }

    private String bodyOf(String type, Long requestId, Long recipient) {
        return transactionTemplate.execute(tx -> (String) em.createNativeQuery(
                        "SELECT body FROM notifications WHERE notification_type = :t "
                                + "AND source_type = 'SHIFT_CHANGE_REQUEST' AND source_id = :sid AND user_id = :uid")
                .setParameter("t", type).setParameter("sid", requestId).setParameter("uid", recipient)
                .getSingleResult());
    }

    // ── 操作ヘルパー ──

    private Long create(Long requester) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", scheduleId);
        body.put("requestType", "PRE_CONFIRM_EDIT");
        body.put("reason", "通知試練");
        MvcResult result = mockMvc.perform(post(BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.data.id")).longValue();
    }

    private void review(Long requestId, String decision, String comment) throws Exception {
        mockMvc.perform(patch(BASE + "/{id}/review", requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reviewBody(decision, 0, comment))))
                .andExpect(status().isOk());
    }

    private static Map<String, Object> reviewBody(String decision, int version, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("reviewComment", comment);
        body.put("version", version);
        return body;
    }

    private Long insertRequest(Long requester) {
        return transactionTemplate.execute(tx -> changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleId)
                .requestType(ChangeRequestType.PRE_CONFIRM_EDIT)
                .requestedBy(requester)
                .reason("通知試練")
                .build()).getId());
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ── フィクスチャ ──

    /** DEPUTY_ADMIN に権限グループ経由で MANAGE_SHIFTS を付与する。 */
    private void grantManageShiftsViaGroup(Long userId, Long team, String tag) {
        em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                        + "SELECT 'MANAGE_SHIFTS', 'MANAGE_SHIFTS', 'TEAM', NOW(), NOW() FROM DUAL "
                        + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_SHIFTS')")
                .executeUpdate();
        em.createNativeQuery("INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) VALUES (:tid, NULL, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", team)
                .setParameter("name", "cr-pg-" + tag)
                .executeUpdate();
        Long groupId = ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", "cr-pg-" + tag).getSingleResult()).longValue();
        em.createNativeQuery("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                        + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = 'MANAGE_SHIFTS'")
                .setParameter("gid", groupId)
                .executeUpdate();
        em.createNativeQuery("INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                        + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .executeUpdate();
    }

    private void grantDefaultRolePermission() {
        transactionTemplate.executeWithoutResult(tx -> {
            em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                            + "SELECT 'MANAGE_SHIFTS', 'MANAGE_SHIFTS', 'TEAM', NOW(), NOW() FROM DUAL "
                            + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_SHIFTS')")
                    .executeUpdate();
            em.createNativeQuery("INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                            + "SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p "
                            + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'MANAGE_SHIFTS' "
                            + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                            + "WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                    .executeUpdate();
        });
    }

    /** 他の IT に影響しないよう、本テストが足した DEPUTY_ADMIN の既定付与を必ず外す。 */
    private void revokeDefaultRolePermission() {
        transactionTemplate.executeWithoutResult(tx -> em.createNativeQuery(
                        "DELETE rp FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name = 'MANAGE_SHIFTS'")
                .executeUpdate());
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, '通知', 'テスト', '通知 テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
