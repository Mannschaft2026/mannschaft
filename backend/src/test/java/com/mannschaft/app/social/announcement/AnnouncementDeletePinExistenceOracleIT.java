package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261103-0822 お知らせフィードの削除（DELETE）・ピン留め切替（PATCH /{id}/pin）の存在オラクル封じ（試練 red）。
 *
 * <p><b>契約</b>: 閲覧資格のない者が指定した実在お知らせ ID（他チーム・他組織のもの、またはパスのスコープ内でも
 * 既読系 {@link AnnouncementReadCommandService} と同基準で不可視のもの）への削除・ピン留めの応答は、
 * 不在 ID を指定したときと <b>status・Content-Type・本文全体（error.code・message 含む）まで同一</b>とする。
 * 不在の正解は既読（POST /{id}/read）が不在 ID に返す応答（status・error.code・error.message）と一致させる。
 * 是正前は越境で 403 {@code ANNOUNCE_002}（または著者なら越境 URL でも削除成功）、不在で 404 {@code ANNOUNCE_001} で、
 * お知らせ ID の実在が判別できた。</p>
 *
 * <p>閲覧資格がある者の認可（著者本人の削除、ADMIN のピン留め、非著者・非 ADMIN の 403）は従来どおり（AC6）。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL で検査する（TEST_CONVENTION §3.1.2。認可を検査するため
 * {@code addFilters=false} を使わない）。fixture は独立 tx で commit し、所有行だけを後始末する。
 * 拒否された要求の DB 不変は、所有お知らせ行の全列スナップショットの前後一致で確かめる。</p>
 *
 * <p>補足: {@code announcement_feeds.visibility} は NOT NULL のため、既読系が fail-closed にする
 * 「visibility null」は、同じく {@link AnnouncementVisibility#isVisibleTo} が不可とする未知値で代替する。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261103-0822 お知らせ削除・ピン留めの存在オラクル（越境/不可視は不在と同一応答・実JWT/filter）")
class AnnouncementDeletePinExistenceOracleIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しないお知らせ ID。 */
    private static final long MISSING_ID = Long.MAX_VALUE;

    /** 既読系が不可とする未知の visibility（NOT NULL 制約のため null の代替）。 */
    private static final String UNKNOWN_VISIBILITY = "LEGACY_UNKNOWN";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> feedIds = new ArrayList<>();
    private final List<Long> paymentItemIds = new ArrayList<>();
    private final List<Long> blogPostIds = new ArrayList<>();
    private Set<String> rolesBefore;

    private String key;

    private Long teamAId;
    private Long teamBId;
    private Long orgAId;
    private Long orgBId;

    /** teamA / orgA の ADMIN。 */
    private Long adminAId;
    /** teamA / orgA のメンバー（著者役）。 */
    private Long memberAId;
    /** teamA / orgA のメンバー（非著者・非 ADMIN）。 */
    private Long memberA2Id;
    /** teamA の応援者。 */
    private Long supporterAId;
    /** teamB / orgB の ADMIN。 */
    private Long adminBId;
    /** どこにも所属しない部外者。 */
    private Long outsiderId;

    /** teamA の内輪限定（著者 memberA）。 */
    private Long annAId;
    /** teamA の課金 HIDDEN（PUBLIC・著者 memberA・未購入）。 */
    private Long annAHiddenId;
    /** teamA の未知 visibility（著者 memberA）。 */
    private Long annAUnknownVisId;
    /** teamB の内輪限定（著者 adminB）。 */
    private Long annBId;
    /** teamB の内輪限定だが著者は memberA（越境 URL で著者判定が素通りしないことの確認用）。 */
    private Long annBByMemberAId;

    /** orgA の内輪限定（著者 memberA）。 */
    private Long orgAnnAId;
    /** orgA の課金 HIDDEN（PUBLIC・著者 memberA・未購入）。 */
    private Long orgAnnAHiddenId;
    /** orgA の未知 visibility（著者 memberA）。 */
    private Long orgAnnAUnknownVisId;
    /** orgB の内輪限定（著者 adminB）。 */
    private Long orgAnnBId;
    /** orgB の内輪限定だが著者は memberA。 */
    private Long orgAnnBByMemberAId;

    @BeforeEach
    void 所有行を独立txでcommitする() {
        key = UUID.randomUUID().toString().substring(0, 8);
        rolesBefore = Set.copyOf(tx(() -> jdbc.queryForList("SELECT name FROM roles", String.class)));
        tx(() -> {
            teamAId = insertTeam("ANNORACLE チームA " + key);
            teamBId = insertTeam("ANNORACLE チームB " + key);
            orgAId = insertOrganization("ANNORACLE 組織A " + key);
            orgBId = insertOrganization("ANNORACLE 組織B " + key);

            adminAId = insertUser("admin-a");
            memberAId = insertUser("member-a");
            memberA2Id = insertUser("member-a2");
            supporterAId = insertUser("supporter-a");
            adminBId = insertUser("admin-b");
            outsiderId = insertUser("outsider");

            MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
            MembershipTestHelper.insertMembership(em, adminAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", null, orgAId);

            MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, memberAId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, memberA2Id, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, memberA2Id, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, supporterAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);

            MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
            MembershipTestHelper.insertMembership(em, adminBId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", null, orgBId);

            annAId = saveFeed(AnnouncementScopeType.TEAM, teamAId, memberAId, AnnouncementVisibility.MEMBERS_AND_ABOVE);
            annAHiddenId = saveFeed(AnnouncementScopeType.TEAM, teamAId, memberAId, AnnouncementVisibility.PUBLIC);
            annAUnknownVisId = saveFeed(AnnouncementScopeType.TEAM, teamAId, memberAId, UNKNOWN_VISIBILITY);
            annBId = saveFeed(AnnouncementScopeType.TEAM, teamBId, adminBId, AnnouncementVisibility.MEMBERS_AND_ABOVE);
            annBByMemberAId = saveFeed(AnnouncementScopeType.TEAM, teamBId, memberAId,
                    AnnouncementVisibility.MEMBERS_AND_ABOVE);

            orgAnnAId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgAId, memberAId,
                    AnnouncementVisibility.MEMBERS_AND_ABOVE);
            orgAnnAHiddenId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgAId, memberAId,
                    AnnouncementVisibility.PUBLIC);
            orgAnnAUnknownVisId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgAId, memberAId, UNKNOWN_VISIBILITY);
            orgAnnBId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgBId, adminBId,
                    AnnouncementVisibility.MEMBERS_AND_ABOVE);
            orgAnnBByMemberAId = saveFeed(AnnouncementScopeType.ORGANIZATION, orgBId, memberAId,
                    AnnouncementVisibility.MEMBERS_AND_ABOVE);

            // 課金 HIDDEN（未購入かつタイトル非表示のゲート）。支払い項目はお知らせと同じスコープに置く。
            hide(annAHiddenId, savePaymentItem(teamAId, null));
            hide(orgAnnAHiddenId, savePaymentItem(null, orgAId));
            em.flush();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM announcement_feeds WHERE id = ?",
                    Long.class, MISSING_ID)).isZero();
            return null;
        });
    }

    @AfterEach
    void 所有行だけ削除して共有認証状態を復元する() {
        try {
            tx(() -> {
                if (!feedIds.isEmpty()) {
                    named("DELETE FROM content_payment_gates WHERE content_type = 'ANNOUNCEMENT' AND content_id IN (:ids)",
                            feedIds);
                    named("DELETE FROM announcement_read_status WHERE announcement_feed_id IN (:ids)", feedIds);
                    named("DELETE FROM announcement_feeds WHERE id IN (:ids)", feedIds);
                }
                if (!blogPostIds.isEmpty()) {
                    named("DELETE FROM blog_posts WHERE id IN (:ids)", blogPostIds);
                }
                if (!paymentItemIds.isEmpty()) {
                    named("DELETE FROM payment_items WHERE id IN (:ids)", paymentItemIds);
                }
                if (!userIds.isEmpty()) {
                    named("DELETE FROM memberships WHERE user_id IN (:ids)", userIds);
                    named("DELETE FROM user_roles WHERE user_id IN (:ids)", userIds);
                }
                if (!teamIds.isEmpty()) {
                    named("DELETE FROM teams WHERE id IN (:ids)", teamIds);
                }
                if (!orgIds.isEmpty()) {
                    named("DELETE FROM organizations WHERE id IN (:ids)", orgIds);
                }
                if (!userIds.isEmpty()) {
                    named("DELETE FROM users WHERE id IN (:ids)", userIds);
                }
                for (String name : jdbc.queryForList("SELECT name FROM roles", String.class)) {
                    if (!rolesBefore.contains(name)) {
                        jdbc.update("DELETE FROM roles WHERE name = ?", name);
                    }
                }
                return null;
            });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC1 チーム delete
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC1 チーム DELETE /teams/{teamId}/announcements/{id}")
    class Ac1TeamDelete {

        @Test
        @DisplayName("AC1 自チームURLに他チームのお知らせIDを差し込んだADMINの削除: 越境と不在で応答が同一・DB不変")
        void ac1_越境IDを自チームURLで削除_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, teamReadMissing(),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annBId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC1 他チームのお知らせの著者でも自チームURL経由の削除: 越境と不在で応答が同一・削除されない")
        void ac1_他チームのお知らせの著者が越境URLで削除_不在と同一() throws Exception {
            assertSameAsMissing(memberAId, teamReadMissing(),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annBByMemberAId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC1 他チームの者がそのチームのURLで削除: 実在と不在で応答が同一・DB不変")
        void ac1_非所属チームURLで削除_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, teamReadMissingIn(teamBId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamBId, annBId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamBId, MISSING_ID));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC2 チーム pin
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC2 チーム PATCH /teams/{teamId}/announcements/{id}/pin")
    class Ac2TeamPin {

        @Test
        @DisplayName("AC2 自チームURLに他チームのお知らせIDを差し込んだADMINのピン留め: 越境と不在で応答が同一・DB不変")
        void ac2_越境IDを自チームURLでピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, teamReadMissing(),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, annBId)),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC2 他チームの者がそのチームのURLでピン留め: 実在と不在で応答が同一・DB不変")
        void ac2_非所属チームURLでピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, teamReadMissingIn(teamBId),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamBId, annBId)),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamBId, MISSING_ID)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC3 組織 delete
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC3 組織 DELETE /organizations/{orgId}/announcements/{id}")
    class Ac3OrgDelete {

        @Test
        @DisplayName("AC3 自組織URLに他組織のお知らせIDを差し込んだADMINの削除: 越境と不在で応答が同一・DB不変")
        void ac3_越境IDを自組織URLで削除_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, orgReadMissing(),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, orgAnnBId),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC3 他組織のお知らせの著者でも自組織URL経由の削除: 越境と不在で応答が同一・削除されない")
        void ac3_他組織のお知らせの著者が越境URLで削除_不在と同一() throws Exception {
            assertSameAsMissing(memberAId, orgReadMissing(),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, orgAnnBByMemberAId),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC3 他組織の者がその組織のURLで削除: 実在と不在で応答が同一・DB不変")
        void ac3_非所属組織URLで削除_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, orgReadMissingIn(orgBId),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgBId, orgAnnBId),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgBId, MISSING_ID));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC4 組織 pin
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC4 組織 PATCH /organizations/{orgId}/announcements/{id}/pin")
    class Ac4OrgPin {

        @Test
        @DisplayName("AC4 自組織URLに他組織のお知らせIDを差し込んだADMINのピン留め: 越境と不在で応答が同一・DB不変")
        void ac4_越境IDを自組織URLでピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, orgReadMissing(),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, orgAnnBId)),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC4 他組織の者がその組織のURLでピン留め: 実在と不在で応答が同一・DB不変")
        void ac4_非所属組織URLでピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, orgReadMissingIn(orgBId),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgBId, orgAnnBId)),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgBId, MISSING_ID)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC5 閲覧資格のない（既読系と同基準で不可視の）お知らせ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC5 既読系と同基準で不可視のお知らせへの delete/pin は不在と同一")
    class Ac5Invisible {

        @Test
        @DisplayName("AC5 チーム 課金HIDDENを著者が削除: 不在と同一・削除されない")
        void ac5_チーム課金HIDDENの著者削除_不在と同一() throws Exception {
            assertSameAsMissing(memberAId, teamReadMissing(),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annAHiddenId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC5 チーム 課金HIDDENをADMINがピン留め: 不在と同一・DB不変")
        void ac5_チーム課金HIDDENのADMINピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, teamReadMissing(),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, annAHiddenId)),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC5 チーム 未知visibility（null相当）を著者が削除: 不在と同一・削除されない")
        void ac5_チーム未知visibilityの著者削除_不在と同一() throws Exception {
            assertSameAsMissing(memberAId, teamReadMissing(),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annAUnknownVisId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC5 チーム 未知visibility（null相当）をADMINがピン留め: 不在と同一・DB不変")
        void ac5_チーム未知visibilityのADMINピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, teamReadMissing(),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, annAUnknownVisId)),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC5 チーム 応援者が内輪限定を削除: 不在と同一・DB不変")
        void ac5_応援者の内輪限定削除_不在と同一() throws Exception {
            assertSameAsMissing(supporterAId, teamReadMissing(),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annAId),
                    delete("/api/v1/teams/{t}/announcements/{id}", teamAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC5 チーム 部外者が内輪限定をピン留め: 不在と同一・DB不変")
        void ac5_部外者の内輪限定ピン留め_不在と同一() throws Exception {
            assertSameAsMissing(outsiderId, teamReadMissing(),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, annAId)),
                    pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC5 組織 課金HIDDENを著者が削除: 不在と同一・削除されない")
        void ac5_組織課金HIDDENの著者削除_不在と同一() throws Exception {
            assertSameAsMissing(memberAId, orgReadMissing(),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, orgAnnAHiddenId),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, MISSING_ID));
        }

        @Test
        @DisplayName("AC5 組織 課金HIDDENをADMINがピン留め: 不在と同一・DB不変")
        void ac5_組織課金HIDDENのADMINピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, orgReadMissing(),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, orgAnnAHiddenId)),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC5 組織 未知visibility（null相当）をADMINがピン留め: 不在と同一・DB不変")
        void ac5_組織未知visibilityのADMINピン留め_不在と同一() throws Exception {
            assertSameAsMissing(adminAId, orgReadMissing(),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, orgAnnAUnknownVisId)),
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, MISSING_ID)));
        }

        @Test
        @DisplayName("AC5 組織 部外者が内輪限定を削除: 不在と同一・DB不変")
        void ac5_組織部外者の内輪限定削除_不在と同一() throws Exception {
            assertSameAsMissing(outsiderId, orgReadMissing(),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, orgAnnAId),
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, MISSING_ID));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC6 閲覧資格がある場合の認可は従来どおり
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC6 閲覧資格がある者の認可は従来どおり（越境ではないので 403 可）")
    class Ac6Authorized {

        @Test
        @DisplayName("AC6 チーム 著者本人の削除は204・行が消える")
        void ac6_チーム著者本人の削除は204() throws Exception {
            MvcResult result = perform(memberAId, delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annAId));
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(feedExists(annAId)).isFalse();
        }

        @Test
        @DisplayName("AC6 チーム 非著者・非ADMINの削除は403 ANNOUNCE_002・行は残る")
        void ac6_チーム非著者非ADMINの削除は403() throws Exception {
            String before = snapshot();
            assertError(perform(memberA2Id, delete("/api/v1/teams/{t}/announcements/{id}", teamAId, annAId)),
                    403, "ANNOUNCE_002");
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC6 チーム ADMINのピン留めは200・is_pinnedが立つ")
        void ac6_チームADMINのピン留めは200() throws Exception {
            MvcResult result = perform(adminAId, pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, annAId)));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(isPinned(annAId)).isTrue();
        }

        @Test
        @DisplayName("AC6 チーム 著者でも非ADMINのピン留めは403 ANNOUNCE_002・DB不変")
        void ac6_チーム著者非ADMINのピン留めは403() throws Exception {
            String before = snapshot();
            assertError(perform(memberAId, pin(patch("/api/v1/teams/{t}/announcements/{id}/pin", teamAId, annAId))),
                    403, "ANNOUNCE_002");
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC6 組織 著者本人の削除は204・行が消える")
        void ac6_組織著者本人の削除は204() throws Exception {
            MvcResult result = perform(memberAId,
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, orgAnnAId));
            assertThat(result.getResponse().getStatus()).isEqualTo(204);
            assertThat(feedExists(orgAnnAId)).isFalse();
        }

        @Test
        @DisplayName("AC6 組織 非著者・非ADMINの削除は403 ANNOUNCE_002・行は残る")
        void ac6_組織非著者非ADMINの削除は403() throws Exception {
            String before = snapshot();
            assertError(perform(memberA2Id,
                    delete("/api/v1/organizations/{o}/announcements/{id}", orgAId, orgAnnAId)), 403, "ANNOUNCE_002");
            assertThat(snapshot()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC6 組織 ADMINのピン留めは200・is_pinnedが立つ")
        void ac6_組織ADMINのピン留めは200() throws Exception {
            MvcResult result = perform(adminAId,
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, orgAnnAId)));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(isPinned(orgAnnAId)).isTrue();
        }

        @Test
        @DisplayName("AC6 組織 著者でも非ADMINのピン留めは403 ANNOUNCE_002・DB不変")
        void ac6_組織著者非ADMINのピン留めは403() throws Exception {
            String before = snapshot();
            assertError(perform(memberAId,
                    pin(patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgAId, orgAnnAId))),
                    403, "ANNOUNCE_002");
            assertThat(snapshot()).isEqualTo(before);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 不在 ID への要求が既読系の不在応答（status・error.code・error.message）と一致し、
     * 実在 ID への要求が不在 ID への要求と status・Content-Type・本文全体まで一致し、
     * 所有お知らせ行（全列）が要求の前後で変わらないことを検証する。
     */
    private void assertSameAsMissing(Long actorId, MockHttpServletRequestBuilder readMissing,
                                     MockHttpServletRequestBuilder actual,
                                     MockHttpServletRequestBuilder missing) throws Exception {
        String before = snapshot();
        try {
            MvcResult readMissingResult = perform(actorId, readMissing);
            MvcResult missingResult = perform(actorId, missing);
            assertThat(missingResult.getResponse().getStatus())
                    .as("不在IDの status は既読系の不在応答と同一であること")
                    .isEqualTo(readMissingResult.getResponse().getStatus());
            assertThat(json(missingResult).path("error").path("code").asText())
                    .as("不在IDの error.code は既読系の不在応答と同一であること")
                    .isEqualTo(json(readMissingResult).path("error").path("code").asText());
            assertThat(json(missingResult).path("error").path("message").asText())
                    .as("不在IDの error.message は既読系の不在応答と同一であること")
                    .isEqualTo(json(readMissingResult).path("error").path("message").asText());

            MvcResult actualResult = perform(actorId, actual);
            assertThat(actualResult.getResponse().getStatus())
                    .as("実在IDの status は不在IDと同一であること（存在オラクル）")
                    .isEqualTo(missingResult.getResponse().getStatus());
            assertThat(actualResult.getResponse().getContentType())
                    .as("実在IDの Content-Type は不在IDと同一であること")
                    .isEqualTo(missingResult.getResponse().getContentType());
            assertThat(json(actualResult))
                    .as("実在IDの本文（error.code・message 含む）は不在IDと同一であること（存在オラクル）")
                    .isEqualTo(json(missingResult));
        } finally {
            assertThat(snapshot()).as("拒否された要求で所有お知らせ行が変わらないこと").isEqualTo(before);
        }
    }

    private MockHttpServletRequestBuilder teamReadMissing() {
        return teamReadMissingIn(teamAId);
    }

    private MockHttpServletRequestBuilder teamReadMissingIn(Long teamId) {
        return post("/api/v1/teams/{t}/announcements/{id}/read", teamId, MISSING_ID);
    }

    private MockHttpServletRequestBuilder orgReadMissing() {
        return orgReadMissingIn(orgAId);
    }

    private MockHttpServletRequestBuilder orgReadMissingIn(Long orgId) {
        return post("/api/v1/organizations/{o}/announcements/{id}/read", orgId, MISSING_ID);
    }

    private MockHttpServletRequestBuilder pin(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}");
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(status);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json(result).path("error").path("code").asText()).isEqualTo(code);
    }

    private MvcResult perform(Long actorId, MockHttpServletRequestBuilder request) throws Exception {
        request.header("Accept-Language", "ja");
        request.header("Authorization", "Bearer " + tokens.issueAccessToken(actorId, List.of("USER")));
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 所有お知らせ行の全列を文字列化したスナップショット。 */
    private String snapshot() {
        return tx(() -> String.valueOf(new NamedParameterJdbcTemplate(jdbc).queryForList(
                "SELECT * FROM announcement_feeds WHERE id IN (:ids) ORDER BY id",
                new MapSqlParameterSource("ids", feedIds))));
    }

    private boolean feedExists(Long feedId) {
        return tx(() -> jdbc.queryForObject("SELECT COUNT(*) FROM announcement_feeds WHERE id = ?",
                Long.class, feedId)) > 0;
    }

    private boolean isPinned(Long feedId) {
        return Boolean.TRUE.equals(tx(() -> jdbc.queryForObject(
                "SELECT is_pinned FROM announcement_feeds WHERE id = ?", Boolean.class, feedId)));
    }

    private void named(String sql, List<Long> ids) {
        new NamedParameterJdbcTemplate(jdbc).update(sql, new MapSqlParameterSource("ids", ids));
    }

    private <T> T tx(Supplier<T> work) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> work.get());
    }

    private Long saveFeed(AnnouncementScopeType scopeType, Long scopeId, Long authorId, String visibility) {
        // 元は公開済みの実在記事（元が公開済みでないお知らせは ID 指定の経路でも不在扱い。N9）
        long sourceId = AnnouncementSourceFixtures.publishedBlog(em, scopeType, scopeId, authorId);
        blogPostIds.add(sourceId);
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(sourceId)
                .authorId(authorId)
                .titleCache("ANNORACLE お知らせ " + key + " " + sourceId)
                .visibility(visibility)
                .build();
        em.persist(feed);
        em.flush();
        feedIds.add(feed.getId());
        return feed.getId();
    }

    private Long savePaymentItem(Long teamId, Long organizationId) {
        PaymentItemEntity item = PaymentItemEntity.builder().teamId(teamId).organizationId(organizationId)
                .name("ANNORACLE 購読 " + key).type(PaymentItemType.MONTHLY_FEE)
                .amount(BigDecimal.valueOf(100)).build();
        em.persist(item);
        em.flush();
        paymentItemIds.add(item.getId());
        return item.getId();
    }

    /** 未購入かつタイトル非表示＝HIDDEN の課金ゲートを付ける。 */
    private void hide(Long feedId, Long paymentItemId) {
        em.createNativeQuery("INSERT INTO content_payment_gates (payment_item_id, content_type, content_id, "
                        + "is_title_hidden, created_by, created_at) VALUES (:item, 'ANNOUNCEMENT', :fid, 1, :author, NOW())")
                .setParameter("item", paymentItemId).setParameter("fid", feedId).setParameter("author", adminAId)
                .executeUpdate();
    }

    private Long insertUser(String label) {
        String email = "annoracle-" + label + "-" + key + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'ANNORACLE', 'テスト', 'ANNORACLE テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
        userIds.add(id);
        return id;
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('annor-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        teamIds.add(id);
        return id;
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('annoro-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        orgIds.add(id);
        return id;
    }
}
