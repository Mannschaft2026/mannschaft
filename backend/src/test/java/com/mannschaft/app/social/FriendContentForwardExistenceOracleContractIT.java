package com.mannschaft.app.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.role.authorization.RolePriority;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261103-0823「フレンドの転送入口に存在オラクルが残る」受け入れ試験（試練・red 先行）。
 *
 * <p><b>契約</b>: {@code POST /api/v1/teams/{id}/friend-feed/{postId}/forward} で、転送を
 * 許可されない転送元投稿（不在・越境・閲覧不可・非フレンド）は、すべて<b>不在と同じ</b>
 * {@code 404 SOCIAL_122}（status・error.code・error.message まで一致）で返す。どれか 1 つでも
 * 別応答だと、投稿 ID の実在・共有設定・フレンド関係が外から判別できてしまう。</p>
 *
 * <p>あわせて管理者フィード（{@code GET /api/v1/teams/{teamId}/friend-feed}）が
 * {@code scope_type = TEAM} 以外の投稿（scope_id がたまたまフレンドチーム ID と一致する組織投稿など）を
 * 出さないことを固定する。フィードに出るものだけが転送でき、転送できるものだけがフィードに出る。</p>
 *
 * <p>手本: {@code SocialAnnouncementScopeContractIT}（CMP-261103-0822 / #4536）。ただし本 IT は
 * TEST_CONVENTION §3.1.2 に従い、<b>実 Security フィルタ</b>（{@code addFilters=false} を使わない）で
 * 認証を {@code .with(user(...))} で与える。自分の Bean・DB・認可はモックしない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261103-0823 フレンド転送入口の存在オラクル封じ（受け入れ試験）")
class FriendContentForwardExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    private static final String FORWARD_PATH = "/api/v1/teams/{id}/friend-feed/{postId}/forward";
    private static final String FEED_PATH = "/api/v1/teams/{teamId}/friend-feed";
    private static final String BODY = "{\"target\":\"MEMBER\"}";
    /** 実在しない投稿 ID（不在の対照群）。 */
    private static final long MISSING_POST_ID = 9_999_999_999L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TimelinePostRepository timelinePostRepository;
    @Autowired
    private TeamFriendRepository teamFriendRepository;
    @PersistenceContext
    private EntityManager em;

    /** 転送を実行する側のチーム。 */
    private Long teamAId;
    /** teamA のフレンドチーム。 */
    private Long teamBId;
    /** teamA とフレンド関係にないチーム。 */
    private Long teamCId;

    /** teamA の ADMIN（MANAGE_FRIEND_TEAMS 付与済み・正当な転送者）。 */
    private Long adminAId;
    /** teamA の一般メンバー（転送権限なし）。 */
    private Long memberAId;

    /** teamB の共有投稿（正当に転送できる唯一の投稿）。 */
    private Long postBSharedId;
    /** teamB の非共有投稿（share_with_friends=FALSE）。 */
    private Long postBPrivateId;
    /** teamB の下書き（share_with_friends=TRUE だが未公開）。 */
    private Long postBDraftId;
    /** teamB の非表示投稿（share_with_friends=TRUE だが HIDDEN）。 */
    private Long postBHiddenId;
    /** teamB の論理削除済み共有投稿。 */
    private Long postBDeletedId;
    /** teamC（非フレンド）の共有投稿。 */
    private Long postCSharedId;
    /** 自チーム teamA の共有投稿（自分自身とはフレンド関係にない）。 */
    private Long postASharedId;
    /** scope_type=ORGANIZATION だが scope_id が teamB の ID と一致する共有投稿（スコープ越境）。 */
    private Long postOrgAsBId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("FWDORC チームA");
        teamBId = insertTeam("FWDORC チームB");
        teamCId = insertTeam("FWDORC チームC");

        adminAId = insertUser("fwdorc-admin-a@example.com");
        memberAId = insertUser("fwdorc-member-a@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // test プロファイルは Flyway 無効で role_permissions が空のため明示 seed する。
        grantPermissionToRole("ADMIN", "MANAGE_FRIEND_TEAMS");

        // A と B だけがフレンド（正規化ペア a < b）。
        teamFriendRepository.save(TeamFriendEntity.builder()
                .teamAId(Math.min(teamAId, teamBId))
                .teamBId(Math.max(teamAId, teamBId))
                .establishedAt(LocalDateTime.now())
                .aFollowId(0L)
                .bFollowId(0L)
                .build());

        postBSharedId = savePost(PostScopeType.TEAM, teamBId, true, PostStatus.PUBLISHED);
        postBPrivateId = savePost(PostScopeType.TEAM, teamBId, false, PostStatus.PUBLISHED);
        postBDraftId = savePost(PostScopeType.TEAM, teamBId, true, PostStatus.DRAFT);
        postBHiddenId = savePost(PostScopeType.TEAM, teamBId, true, PostStatus.HIDDEN);
        postBDeletedId = savePost(PostScopeType.TEAM, teamBId, true, PostStatus.PUBLISHED);
        postCSharedId = savePost(PostScopeType.TEAM, teamCId, true, PostStatus.PUBLISHED);
        postASharedId = savePost(PostScopeType.TEAM, teamAId, true, PostStatus.PUBLISHED);
        postOrgAsBId = savePost(PostScopeType.ORGANIZATION, teamBId, true, PostStatus.PUBLISHED);

        em.flush();
        TimelinePostEntity deleted = timelinePostRepository.findById(postBDeletedId).orElseThrow();
        deleted.softDelete();
        timelinePostRepository.save(deleted);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1〜AC-5: 許可されない転送元は、すべて不在と同じ 404 SOCIAL_122
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("許可されない転送元は不在と同一応答（404 SOCIAL_122）")
    class Denied {

        @Test
        @DisplayName("AC-1: 不在の転送元 → 404 SOCIAL_122（基準応答）")
        void ac1_不在の転送元は404_SOCIAL_122() throws Exception {
            JsonNode body = forwardAs(adminAId, teamAId, MISSING_POST_ID)
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString().transform(this::readTree);
            assertThat(body.path("error").path("code").asText()).isEqualTo("SOCIAL_122");
            assertThat(body.path("error").path("message").asText()).isNotBlank();
        }

        @Test
        @DisplayName("AC-1b: 論理削除済みの転送元 → 不在と同一応答")
        void ac1b_削除済みの転送元は不在と同一() throws Exception {
            assertSameAsMissing(postBDeletedId);
        }

        @Test
        @DisplayName("AC-2: 非フレンド（teamC）の共有投稿 → 不在と同一応答（旧: 404 SOCIAL_124）")
        void ac2_非フレンドチームの投稿は不在と同一() throws Exception {
            assertSameAsMissing(postCSharedId);
        }

        @Test
        @DisplayName("AC-2b: 自チームの共有投稿（自分とはフレンド関係にない）→ 不在と同一応答")
        void ac2b_自チームの投稿は不在と同一() throws Exception {
            assertSameAsMissing(postASharedId);
        }

        @Test
        @DisplayName("AC-3: スコープ越境（ORGANIZATION 投稿で scope_id がフレンドチーム ID と一致）→ 不在と同一応答（旧: 201 転送成立）")
        void ac3_スコープ越境の投稿は不在と同一() throws Exception {
            assertSameAsMissing(postOrgAsBId);
        }

        @Test
        @DisplayName("AC-4: 存在するが非共有（share_with_friends=FALSE）→ 不在と同一応答（旧: 400 SOCIAL_123）")
        void ac4_非共有投稿は不在と同一() throws Exception {
            assertSameAsMissing(postBPrivateId);
        }

        @Test
        @DisplayName("AC-4b: 存在するが下書き（DRAFT）→ 不在と同一応答（旧: 201 転送成立）")
        void ac4b_下書き投稿は不在と同一() throws Exception {
            assertSameAsMissing(postBDraftId);
        }

        @Test
        @DisplayName("AC-4c: 存在するが非表示（HIDDEN）→ 不在と同一応答（旧: 201 転送成立）")
        void ac4c_非表示投稿は不在と同一() throws Exception {
            assertSameAsMissing(postBHiddenId);
        }

        @Test
        @DisplayName("AC-5: 拒否時は転送履歴・転送先投稿を 1 件も作らない")
        void ac5_拒否時は副作用ゼロ() throws Exception {
            for (Long postId : List.of(postCSharedId, postASharedId, postOrgAsBId,
                    postBPrivateId, postBDraftId, postBHiddenId)) {
                forwardAs(adminAId, teamAId, postId);
            }
            assertThat(countForwards(teamAId)).isZero();
            assertThat(countForwardedPosts(teamAId)).isZero();
        }

        /**
         * 指定投稿への転送応答が、不在 ID への応答と status・error.code・error.message まで
         * 完全に一致することを検査する。
         */
        private void assertSameAsMissing(Long postId) throws Exception {
            MvcResult missing = forwardAs(adminAId, teamAId, MISSING_POST_ID).andReturn();
            MvcResult target = forwardAs(adminAId, teamAId, postId).andReturn();

            assertThat(target.getResponse().getStatus())
                    .as("status は不在と同じ 404 であること")
                    .isEqualTo(missing.getResponse().getStatus())
                    .isEqualTo(404);
            JsonNode missingBody = readTree(missing.getResponse().getContentAsString());
            JsonNode targetBody = readTree(target.getResponse().getContentAsString());
            assertThat(targetBody.path("error").path("code").asText())
                    .as("error.code は不在と同じ SOCIAL_122 であること")
                    .isEqualTo("SOCIAL_122");
            assertThat(targetBody.path("error").path("message").asText())
                    .as("error.message は不在と同一であること")
                    .isEqualTo(missingBody.path("error").path("message").asText());
            assertThat(targetBody).as("応答本文全体が不在と同一であること").isEqualTo(missingBody);
            assertThat(countForwards(teamAId)).as("転送履歴を作らないこと").isZero();
        }

        private JsonNode readTree(String s) {
            return FriendContentForwardExistenceOracleContractIT.this.readTree(s);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-6〜AC-8: 回帰（正当な転送・未認証・権限なし）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("回帰: 正当な転送・未認証・権限なし")
    class Regression {

        @Test
        @DisplayName("AC-6: フレンドチームの共有・公開済み投稿は 201 で転送でき、履歴が 1 件できる")
        void ac6_正当な転送は201() throws Exception {
            forwardAs(adminAId, teamAId, postBSharedId).andExpect(status().isCreated());
            assertThat(countForwards(teamAId)).isEqualTo(1);
            assertThat(countForwardedPosts(teamAId)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-6b: 同一投稿の二重転送は 409 SOCIAL_121（冪等性は維持）")
        void ac6b_二重転送は409() throws Exception {
            forwardAs(adminAId, teamAId, postBSharedId).andExpect(status().isCreated());
            String body = forwardAs(adminAId, teamAId, postBSharedId)
                    .andExpect(status().isConflict())
                    .andReturn().getResponse().getContentAsString();
            assertThat(readTree(body).path("error").path("code").asText()).isEqualTo("SOCIAL_121");
        }

        @Test
        @DisplayName("AC-7: 未認証は実在・不在を問わず 401（転送しない）")
        void ac7_未認証は401() throws Exception {
            mockMvc.perform(post(FORWARD_PATH, teamAId, postBSharedId)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(post(FORWARD_PATH, teamAId, MISSING_POST_ID)
                            .contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized());
            assertThat(countForwards(teamAId)).isZero();
        }

        @Test
        @DisplayName("AC-8: 転送権限のない一般メンバーは、実在・不在を問わず同一の 403（投稿の実在を漏らさない）")
        void ac8_権限なしは投稿の実在に関わらず同一応答() throws Exception {
            MvcResult existing = forwardAs(memberAId, teamAId, postBSharedId).andReturn();
            MvcResult missing = forwardAs(memberAId, teamAId, MISSING_POST_ID).andReturn();
            assertThat(existing.getResponse().getStatus()).isEqualTo(403);
            assertThat(missing.getResponse().getStatus()).isEqualTo(403);
            assertThat(readTree(existing.getResponse().getContentAsString()))
                    .isEqualTo(readTree(missing.getResponse().getContentAsString()));
            assertThat(countForwards(teamAId)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9: 管理者フィードは scope_type=TEAM の投稿だけを出す
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("管理者フィードのスコープ条件")
    class Feed {

        @Test
        @DisplayName("AC-9: フィードにはフレンドチームの TEAM 共有投稿だけが出る（組織投稿・非共有・下書き・非フレンドは出ない）")
        void ac9_フィードはTEAMスコープの共有公開投稿のみ() throws Exception {
            String body = mockMvc.perform(get(FEED_PATH, teamAId)
                            .param("limit", "50")
                            .with(user(String.valueOf(adminAId))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<Long> postIds = new ArrayList<>();
            readTree(body).path("data").forEach(n -> postIds.add(n.path("postId").asLong()));

            assertThat(postIds).as("陽性対照: 正当な共有投稿は出る").contains(postBSharedId);
            assertThat(postIds).as("scope_id が一致するだけの組織投稿は出ない")
                    .doesNotContain(postOrgAsBId);
            assertThat(postIds).doesNotContain(postBPrivateId, postBDraftId, postBHiddenId,
                    postBDeletedId, postCSharedId, postASharedId);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private ResultActions forwardAs(Long userId, Long teamId, Long postId) throws Exception {
        return mockMvc.perform(post(FORWARD_PATH, teamId, postId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY)
                .with(user(String.valueOf(userId))));
    }

    private JsonNode readTree(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException("応答本文が JSON ではない: " + s, e);
        }
    }

    private Long savePost(PostScopeType scopeType, Long scopeId, boolean share, PostStatus status) {
        return timelinePostRepository.save(TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .userId(adminAId)
                .postedAsType(PostedAsType.TEAM)
                .postedAsId(scopeId)
                .content("FWDORC 投稿 " + scopeType + " " + scopeId + " " + status + " " + share)
                .status(status)
                .shareWithFriends(share)
                .build()).getId();
    }

    private long countForwards(Long forwardingTeamId) {
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM friend_content_forwards WHERE forwarding_team_id = :tid")
                .setParameter("tid", forwardingTeamId)
                .getSingleResult()).longValue();
    }

    private long countForwardedPosts(Long forwardingTeamId) {
        em.flush();
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM timeline_posts "
                                + "WHERE scope_type = 'FRIEND_FORWARD' AND scope_id = :tid")
                .setParameter("tid", forwardingTeamId)
                .getSingleResult()).longValue();
    }

    private void grantPermissionToRole(String roleName, String permissionName) {
        Long roleId = resolveOrInsertRoleId(roleName);
        Long permissionId = resolveOrInsertPermissionId(permissionName);
        em.createNativeQuery(
                        "INSERT IGNORE INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "VALUES (:rid, :pid, 1, NOW())")
                .setParameter("rid", roleId)
                .setParameter("pid", permissionId)
                .executeUpdate();
    }

    private Long resolveOrInsertRoleId(String roleName) {
        try {
            return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "VALUES (:name, :name, :priority, 0, NOW(), NOW())")
                    .setParameter("name", roleName)
                    .setParameter("priority", RolePriority.priority(roleName))
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                    .setParameter("name", roleName)
                    .getSingleResult()).longValue();
        }
    }

    private Long resolveOrInsertPermissionId(String permissionName) {
        try {
            return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                    .setParameter("name", permissionName)
                    .getSingleResult()).longValue();
        } catch (NoResultException e) {
            em.createNativeQuery(
                            "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                    + "VALUES (:name, :name, 'TEAM', NOW(), NOW())")
                    .setParameter("name", permissionName)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :name")
                    .setParameter("name", permissionName)
                    .getSingleResult()).longValue();
        }
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
                                + "VALUES (:email, 'FWDORC', 'テスト', 'FWDORC テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('fwdorc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
