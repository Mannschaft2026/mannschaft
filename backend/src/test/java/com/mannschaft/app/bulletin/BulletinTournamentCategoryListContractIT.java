package com.mannschaft.app.bulletin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261117-0900 掲示板カテゴリ一覧（グローバル方式 {@code GET /api/v1/bulletin/categories}）の
 * 大会スコープ（TOURNAMENT / TOURNAMENT_DIVISION）受け入れテスト（試練・red）。
 *
 * <p><b>背景</b>: CMP-261115-2058 でスレッド系 API は大会スコープを受理するようになったが、カテゴリ一覧は
 * {@code BulletinScopeIdResolver#resolve} と {@code BulletinAccessGuard#checkMembership} の fail-closed に落ち、
 * 主催組織 ADMIN でも 403 COMMON_002 になる（実機）。</p>
 *
 * <p><b>判定の基準</b>: 認可の正本は {@code TournamentContactAccessService#checkView}（スレッド一覧と同一）。
 * 閲覧権なし・不在・未ログインの応答（AC-4〜AC-8）は、同じ条件のスレッド一覧
 * {@code GET /api/v1/bulletin/threads} の応答を基準として status・{@code error.code} まで一致することで判定する
 * （存在秘匿の方針がスレッド側で変わっても自動追従するため）。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL（TEST_CONVENTION §3.1.2。{@code addFilters=false} を使わない）。
 * fixture は別 TX で commit し、所有行だけを後始末する。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261117-0900 掲示板カテゴリ一覧の大会スコープ受け入れ（実JWT/filter）")
class BulletinTournamentCategoryListContractIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しない大会／ディビジョン ID。 */
    private static final long ABSENT_ID = 9_000_000_000_002L;

    private static final String CATEGORIES = "/api/v1/bulletin/categories";
    private static final String THREADS = "/api/v1/bulletin/threads";

    private static final String T_CAT = "CMP0900 大会Tカテゴリ";
    private static final String D_CAT = "CMP0900 ディビジョンDカテゴリ";
    private static final String PUB_CAT = "CMP0900 公開大会カテゴリ";
    private static final String TEAM_CAT = "CMP0900 チームカテゴリ";
    private static final String ORG_CAT = "CMP0900 組織カテゴリ";
    private static final String PERSONAL_CAT = "CMP0900 個人カテゴリ";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private BulletinCategoryRepository categoryRepository;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> tournamentIds = new ArrayList<>();
    private final List<Long> divisionIds = new ArrayList<>();

    // --- スコープ ---
    private Long organizerOrgId;
    private Long foreignOrgId;
    private Long participantTeamId;
    private Long plainTeamId;
    /** 非公開スペースの大会。 */
    private Long tournamentId;
    /** 大会 T のディビジョン。 */
    private Long divisionId;
    /** 公開スペースの大会。 */
    private Long publicTournamentId;
    /** 連絡スペースを持たない大会（スペース不在）。 */
    private Long spacelessTournamentId;
    /** 連絡スペースが論理削除された大会。 */
    private Long archivedTournamentId;

    // --- 人物 ---
    private Long organizerAdminId;
    private Long organizerMemberId;
    private Long foreignAdminId;
    private Long participantAdminId;
    private Long participantMemberId;
    private Long outsiderId;
    private Long plainTeamMemberId;

    @BeforeEach
    void fixtureを別TXでcommitする() {
        tx(() -> {
            organizerAdminId = user("主催管理者");
            organizerMemberId = user("主催一般");
            foreignAdminId = user("他組織管理者");
            participantAdminId = user("参加代表");
            participantMemberId = user("参加一般");
            outsiderId = user("部外者");
            plainTeamMemberId = user("無関係チーム員");

            organizerOrgId = organization("CMP0900 主催組織");
            foreignOrgId = organization("CMP0900 他組織");
            participantTeamId = team("CMP0900 参加チーム");
            plainTeamId = team("CMP0900 無関係チーム");

            MembershipTestHelper.insertMembership(em, organizerAdminId, ScopeType.ORGANIZATION, organizerOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, organizerAdminId, "ADMIN", null, organizerOrgId);
            MembershipTestHelper.insertMembership(em, organizerMemberId, ScopeType.ORGANIZATION, organizerOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, foreignAdminId, ScopeType.ORGANIZATION, foreignOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, foreignAdminId, "ADMIN", null, foreignOrgId);
            MembershipTestHelper.insertMembership(em, participantAdminId, ScopeType.TEAM, participantTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, participantAdminId, "ADMIN", participantTeamId, null);
            MembershipTestHelper.insertMembership(em, participantMemberId, ScopeType.TEAM, participantTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, plainTeamMemberId, ScopeType.TEAM, plainTeamId, RoleKind.MEMBER);

            String suffix = UUID.randomUUID().toString().substring(0, 8);
            tournamentId = tournament(organizerOrgId, "CMP0900 非公開大会 " + suffix, organizerAdminId);
            divisionId = division(tournamentId, "CMP0900 ディビジョン " + suffix);
            participant(divisionId, participantTeamId);
            publicTournamentId = tournament(organizerOrgId, "CMP0900 公開大会 " + suffix, organizerAdminId);
            spacelessTournamentId = tournament(organizerOrgId, "CMP0900 スペース無し大会 " + suffix, organizerAdminId);
            archivedTournamentId = tournament(organizerOrgId, "CMP0900 スペース削除済み大会 " + suffix, organizerAdminId);

            contactSpace(ContactSpaceScopeType.TOURNAMENT, tournamentId, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, publicTournamentId, true);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, archivedTournamentId, false);
            return null;
        });
        jdbc.update("UPDATE tournament_contact_space SET deleted_at = NOW() WHERE scope_type = 'TOURNAMENT' AND scope_id = ?",
                archivedTournamentId);

        category(com.mannschaft.app.bulletin.ScopeType.TOURNAMENT, tournamentId, T_CAT);
        category(com.mannschaft.app.bulletin.ScopeType.TOURNAMENT_DIVISION, divisionId, D_CAT);
        category(com.mannschaft.app.bulletin.ScopeType.TOURNAMENT, publicTournamentId, PUB_CAT);
        category(com.mannschaft.app.bulletin.ScopeType.TEAM, plainTeamId, TEAM_CAT);
        category(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION, organizerOrgId, ORG_CAT);
        category(com.mannschaft.app.bulletin.ScopeType.PERSONAL, plainTeamMemberId, PERSONAL_CAT);
    }

    @AfterEach
    void 所有行だけ片付ける() {
        try {
            deleteCategories("TOURNAMENT", tournamentIds);
            deleteCategories("TOURNAMENT_DIVISION", divisionIds);
            deleteCategories("TEAM", teamIds);
            deleteCategories("ORGANIZATION", orgIds);
            deleteCategories("PERSONAL", userIds);
            for (Long id : tournamentIds) {
                jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT' AND scope_id = ?", id);
            }
            for (Long id : divisionIds) {
                jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT_DIVISION' AND scope_id = ?", id);
                jdbc.update("DELETE FROM tournament_participants WHERE division_id = ?", id);
                jdbc.update("DELETE FROM tournament_divisions WHERE id = ?", id);
            }
            for (Long id : tournamentIds) {
                jdbc.update("DELETE FROM tournaments WHERE id = ?", id);
            }
            for (Long id : userIds) {
                jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
                jdbc.update("DELETE FROM memberships WHERE user_id = ?", id);
            }
            for (Long id : teamIds) {
                jdbc.update("DELETE FROM teams WHERE id = ?", id);
            }
            for (Long id : orgIds) {
                jdbc.update("DELETE FROM organizations WHERE id = ?", id);
            }
            for (Long id : userIds) {
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ========================================================================
    // AC-1〜3: 閲覧権のある者は大会スコープのカテゴリ一覧を 200 で取得できる
    // ========================================================================

    @Test
    @DisplayName("AC-1: 主催組織ADMINは TOURNAMENT のカテゴリ一覧を 200 で取得でき、自大会のカテゴリだけが返る")
    void ac1_主催組織ADMINはTOURNAMENTのカテゴリ一覧を取得できる() throws Exception {
        MvcResult r = call(categories("TOURNAMENT", String.valueOf(tournamentId)), organizerAdminId);

        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(names(r)).contains(T_CAT).doesNotContain(D_CAT, PUB_CAT, TEAM_CAT, ORG_CAT);
    }

    @Test
    @DisplayName("AC-1: カテゴリ未登録の大会でも主催組織ADMINは 200・data は空配列")
    void ac1_カテゴリ未登録の大会は200で空配列() throws Exception {
        contactSpaceInTx(ContactSpaceScopeType.TOURNAMENT, spacelessTournamentId, false);
        MvcResult r = call(categories("TOURNAMENT", String.valueOf(spacelessTournamentId)), organizerAdminId);

        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(json(r).path("data").isArray()).as(body(r)).isTrue();
        assertThat(json(r).path("data").size()).as(body(r)).isZero();
    }

    @Test
    @DisplayName("AC-2: 主催組織ADMINは TOURNAMENT_DIVISION のカテゴリ一覧を 200 で取得できる")
    void ac2_主催組織ADMINはTOURNAMENT_DIVISIONのカテゴリ一覧を取得できる() throws Exception {
        MvcResult r = call(categories("TOURNAMENT_DIVISION", String.valueOf(divisionId)), organizerAdminId);

        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(names(r)).contains(D_CAT).doesNotContain(T_CAT, PUB_CAT);
    }

    @Test
    @DisplayName("AC-3: 参加チームの代表・一般メンバーは TOURNAMENT / TOURNAMENT_DIVISION のカテゴリ一覧を 200 で取得できる")
    void ac3_参加チームのアクティブメンバーはカテゴリ一覧を取得できる() throws Exception {
        for (Long actor : List.of(participantAdminId, participantMemberId)) {
            MvcResult t = call(categories("TOURNAMENT", String.valueOf(tournamentId)), actor);
            assertThat(status(t)).as("actor=" + actor + " " + body(t)).isEqualTo(200);
            assertThat(names(t)).contains(T_CAT);

            MvcResult d = call(categories("TOURNAMENT_DIVISION", String.valueOf(divisionId)), actor);
            assertThat(status(d)).as("actor=" + actor + " " + body(d)).isEqualTo(200);
            assertThat(names(d)).contains(D_CAT);
        }
    }

    // ========================================================================
    // AC-4〜8: 公開・閲覧権なし・不在・未ログインはスレッド一覧と同一応答
    // ========================================================================

    @Test
    @DisplayName("AC-4: 公開スペースは部外者でも 200 でカテゴリを取得でき、未ログインはスレッド一覧と同一応答")
    void ac4_公開スペースはスレッド一覧と同じく閲覧できる() throws Exception {
        MvcResult r = call(categories("TOURNAMENT", String.valueOf(publicTournamentId)), outsiderId);
        assertThat(status(r)).as(body(r)).isEqualTo(200);
        assertThat(names(r)).contains(PUB_CAT).doesNotContain(T_CAT);

        assertSameAsThreads("TOURNAMENT", String.valueOf(publicTournamentId), outsiderId);
        assertSameAsThreads("TOURNAMENT", String.valueOf(publicTournamentId), null);
    }

    @Test
    @DisplayName("AC-5: 部外者・主催組織の一般会員のカテゴリ一覧はスレッド一覧と同一の拒否応答（status・error.code）")
    void ac5_閲覧権なしはスレッド一覧と同一応答() throws Exception {
        for (Long actor : List.of(outsiderId, organizerMemberId)) {
            assertRejectedSameAsThreads("TOURNAMENT", String.valueOf(tournamentId), actor);
            assertRejectedSameAsThreads("TOURNAMENT_DIVISION", String.valueOf(divisionId), actor);
        }
    }

    @Test
    @DisplayName("AC-6: 他テナントADMINのカテゴリ一覧はスレッド一覧と同一、かつ部外者（AC-5）と同一の拒否応答")
    void ac6_他テナントADMINは部外者と同一応答() throws Exception {
        assertRejectedSameAsThreads("TOURNAMENT", String.valueOf(tournamentId), foreignAdminId);
        assertRejectedSameAsThreads("TOURNAMENT_DIVISION", String.valueOf(divisionId), foreignAdminId);

        MvcResult foreign = call(categories("TOURNAMENT", String.valueOf(tournamentId)), foreignAdminId);
        MvcResult outsider = call(categories("TOURNAMENT", String.valueOf(tournamentId)), outsiderId);
        String label = "foreign=" + body(foreign) + " outsider=" + body(outsider);
        assertThat(status(foreign)).as(label).isEqualTo(status(outsider));
        assertThat(errorCode(foreign)).as(label).isEqualTo(errorCode(outsider));
    }

    @Test
    @DisplayName("AC-7: 不在の大会・ディビジョン、スペース不在、スペース論理削除済みはスレッド一覧と同一の拒否応答")
    void ac7_不在はスレッド一覧と同一応答() throws Exception {
        assertRejectedSameAsThreads("TOURNAMENT", String.valueOf(ABSENT_ID), organizerAdminId);
        assertRejectedSameAsThreads("TOURNAMENT_DIVISION", String.valueOf(ABSENT_ID), organizerAdminId);
        assertRejectedSameAsThreads("TOURNAMENT", String.valueOf(spacelessTournamentId), organizerAdminId);
        assertRejectedSameAsThreads("TOURNAMENT", String.valueOf(archivedTournamentId), organizerAdminId);
    }

    @Test
    @DisplayName("AC-8: 未ログインで非公開スペースのカテゴリ一覧はスレッド一覧と同一の拒否応答")
    void ac8_未ログインの非公開はスレッド一覧と同一応答() throws Exception {
        assertRejectedSameAsThreads("TOURNAMENT", String.valueOf(tournamentId), null);
        assertRejectedSameAsThreads("TOURNAMENT_DIVISION", String.valueOf(divisionId), null);
    }

    // ========================================================================
    // AC-9: scope_id 非数値は 400（500 を出さない）
    // ========================================================================

    @Test
    @DisplayName("AC-9: 大会スコープで scope_id が非数値なら 400（スレッド一覧と同一の status・error.code）")
    void ac9_非数値scope_idは400() throws Exception {
        for (String type : List.of("TOURNAMENT", "TOURNAMENT_DIVISION")) {
            MvcResult r = call(categories(type, "not-a-number"), organizerAdminId);
            assertThat(status(r)).as(type + " " + body(r)).isEqualTo(400);
            assertSameAsThreads(type, "not-a-number", organizerAdminId);
        }
    }

    // ========================================================================
    // AC-10: 回帰 — TEAM / ORGANIZATION / PERSONAL は従来どおり
    // ========================================================================

    @Test
    @DisplayName("AC-10: TEAM・ORGANIZATION・PERSONAL のカテゴリ一覧はメンバー（本人）で 200、TEAM 非メンバーは 403 のまま")
    void ac10_既存スコープのカテゴリ一覧は退行しない() throws Exception {
        MvcResult team = call(categories("TEAM", String.valueOf(plainTeamId)), plainTeamMemberId);
        assertThat(status(team)).as(body(team)).isEqualTo(200);
        assertThat(names(team)).contains(TEAM_CAT).doesNotContain(T_CAT, ORG_CAT);

        MvcResult org = call(categories("ORGANIZATION", String.valueOf(organizerOrgId)), organizerMemberId);
        assertThat(status(org)).as(body(org)).isEqualTo(200);
        assertThat(names(org)).contains(ORG_CAT).doesNotContain(T_CAT, TEAM_CAT);

        MvcResult personal = call(categories("PERSONAL", String.valueOf(plainTeamMemberId)), plainTeamMemberId);
        assertThat(status(personal)).as(body(personal)).isEqualTo(200);
        assertThat(names(personal)).contains(PERSONAL_CAT);

        MvcResult denied = call(categories("TEAM", String.valueOf(plainTeamId)), outsiderId);
        assertThat(status(denied)).as(body(denied)).isEqualTo(403);
    }

    // ========================================================================
    // AC-11: 大会スコープのカテゴリ作成は対象外として fail-closed 維持
    // ========================================================================

    @Test
    @DisplayName("AC-11: 主催組織ADMINでも TOURNAMENT のカテゴリ作成は 403 COMMON_002 で、DB に何も作られない")
    void ac11_大会スコープのカテゴリ作成はfail_closedを維持する() throws Exception {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("scopeType", "TOURNAMENT");
        req.put("scopeId", tournamentId);
        req.put("name", "CMP0900 作成禁止カテゴリ");
        MvcResult r = call(post(CATEGORIES).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(req)), organizerAdminId);

        assertThat(status(r)).as(body(r)).isEqualTo(403);
        assertThat(errorCode(r)).as(body(r)).isEqualTo("COMMON_002");
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bulletin_categories WHERE scope_type = 'TOURNAMENT' AND scope_id = ? AND name = ?",
                Integer.class, tournamentId, "CMP0900 作成禁止カテゴリ");
        assertThat(n).isZero();
    }

    // ========================================================================
    // 補助
    // ========================================================================

    /** カテゴリ一覧がスレッド一覧と同じ status・error.code を返すことを確かめる。 */
    private void assertSameAsThreads(String scopeType, String scopeId, Long actor) throws Exception {
        MvcResult cat = call(categories(scopeType, scopeId), actor);
        MvcResult thr = call(get(THREADS).param("scope_type", scopeType).param("scope_id", scopeId), actor);
        String label = "scope=" + scopeType + ":" + scopeId + " actor=" + actor
                + " categories=" + body(cat) + " threads=" + body(thr);
        assertThat(status(cat)).as(label).isEqualTo(status(thr));
        assertThat(errorCode(cat)).as(label).isEqualTo(errorCode(thr));
    }

    /** 拒否であること（2xx でない）＋スレッド一覧と同一応答であることを確かめる。 */
    private void assertRejectedSameAsThreads(String scopeType, String scopeId, Long actor) throws Exception {
        MvcResult thr = call(get(THREADS).param("scope_type", scopeType).param("scope_id", scopeId), actor);
        assertThat(status(thr)).as("基準（スレッド一覧）が拒否であること " + body(thr)).isGreaterThanOrEqualTo(400);
        assertSameAsThreads(scopeType, scopeId, actor);
    }

    private MockHttpServletRequestBuilder categories(String scopeType, String scopeId) {
        return get(CATEGORIES).param("scope_type", scopeType).param("scope_id", scopeId);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mvc.perform(request).andReturn();
    }

    private int status(MvcResult r) {
        return r.getResponse().getStatus();
    }

    private JsonNode json(MvcResult result) throws Exception {
        String s = body(result);
        return s.isBlank() ? mapper.createObjectNode() : mapper.readTree(s);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String errorCode(MvcResult result) throws Exception {
        return json(result).path("error").path("code").asText();
    }

    private List<String> names(MvcResult result) throws Exception {
        List<String> names = new ArrayList<>();
        json(result).path("data").forEach(n -> names.add(n.path("name").asText()));
        return names;
    }

    private void deleteCategories(String scopeType, List<Long> scopeIds) {
        for (Long id : scopeIds) {
            jdbc.update("DELETE FROM bulletin_categories WHERE scope_type = ? AND scope_id = ?", scopeType, id);
        }
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp0900-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long organization(String name) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("cmp0900-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        orgIds.add(id);
        return id;
    }

    private Long team(String name) {
        Long id = teams.save(TeamEntity.builder().slug("cmp0900-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    /** 大会を seed する（列は BulletinTournamentScopeGlobalContractIT#tournament と同一）。 */
    private Long tournament(Long orgId, String name, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO tournaments (organization_id, name, format, sport, "
                                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                                + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                                + "VALUES (:orgId, :name, 'LEAGUE', 'SOCCER', "
                                + "3, 1, 0, 1, 0, "
                                + "0, 0, '点', 'SINGLE', "
                                + "1, 'PUBLIC', 'OPEN', 0, :createdBy, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        tournamentIds.add(id);
        return id;
    }

    private Long division(Long tid, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", tid)
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        divisionIds.add(id);
        return id;
    }

    private void participant(Long divId, Long teamId) {
        em.createNativeQuery(
                        "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                + "VALUES (:divId, :teamId, 'REGISTERED', NOW())")
                .setParameter("divId", divId)
                .setParameter("teamId", teamId)
                .executeUpdate();
    }

    private void contactSpace(ContactSpaceScopeType scopeType, Long scopeId, boolean isPublic) {
        // エンティティの created_at は DB 既定値任せ（insertable=false）だが、テスト DB 側に既定値が無いため明示する。
        em.createNativeQuery(
                        "INSERT INTO tournament_contact_space (id, scope_type, scope_id, space_kind, ref_id, is_public, "
                                + "created_at, updated_at) "
                                + "VALUES (UNHEX(REPLACE(UUID(), '-', '')), :st, :sid, :kind, 0, :pub, NOW(), NOW())")
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .setParameter("kind", ContactSpaceKind.BULLETIN.name())
                .setParameter("pub", isPublic)
                .executeUpdate();
    }

    private void contactSpaceInTx(ContactSpaceScopeType scopeType, Long scopeId, boolean isPublic) {
        tx(() -> {
            contactSpace(scopeType, scopeId, isPublic);
            return null;
        });
    }

    /** カテゴリを Repository で seed する（API の修正有無に依らず一覧の中身を観測するため）。 */
    private void category(com.mannschaft.app.bulletin.ScopeType scopeType, Long scopeId, String name) {
        tx(() -> categoryRepository.save(BulletinCategoryEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).name(name).createdBy(organizerAdminId).build()));
    }
}
