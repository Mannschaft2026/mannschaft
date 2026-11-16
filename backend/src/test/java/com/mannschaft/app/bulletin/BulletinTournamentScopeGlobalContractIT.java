package com.mannschaft.app.bulletin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
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
import org.springframework.test.web.servlet.ResultActions;
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
 * CMP-261115-2058 掲示板グローバル方式 API の大会スコープ（TOURNAMENT / TOURNAMENT_DIVISION）受け入れテスト（試練・red）。
 *
 * <p><b>背景</b>: 大会の連絡スペース画面（FE）は #4547 で契約どおり
 * {@code GET /api/v1/bulletin/threads?scope_type=TOURNAMENT&scope_id=<大会ID>}・
 * {@code POST /api/v1/bulletin/threads}・{@code POST /api/v1/bulletin/threads/read-all} を
 * {@code scope_type=TOURNAMENT / TOURNAMENT_DIVISION} で叩くようになったが、実機ではすべて 400 になった。
 * {@code bulletin.ScopeType#fromPathSegment} が両値を受理せず、
 * {@code GlobalBulletinThreadController#parseScopeType} が {@code COMMON_001}（400）へ変換するため。</p>
 *
 * <p><b>認可の正本</b>: {@code docs/features/F08.7.1_tournament_extensions/01_communication.md} §4
 * （{@code TournamentContactAccessService}）。閲覧=公開スペース／参加チーム（REGISTERED/ACTIVE）のメンバー／
 * 主催組織 ADMIN／SYSTEM_ADMIN、投稿=参加チームの ADMIN・DEPUTY_ADMIN／主催組織 ADMIN／SYSTEM_ADMIN。
 * 公開スペースでも投稿権は広がらない（§4.2・§5.2）。</p>
 *
 * <p><b>権限なしの応答（AC-7〜AC-10）</b>: 現行仕様どおり 403（閲覧=TOUR_030 / 投稿=TOUR_031）とする。
 * 存在秘匿（不在の大会／ディビジョン ID と同じ 404 に畳む）は別の戦役で扱い、本戦役は 400 の解消に絞る
 * （マスター裁可 2026-11-16）。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL（TEST_CONVENTION §3.1.2。認可を検査するため {@code addFilters=false} を使わない）。
 * fixture は別 TX で commit し、所有行だけを後始末する。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261115-2058 掲示板グローバル API の大会スコープ受け入れ（実JWT/filter）")
class BulletinTournamentScopeGlobalContractIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しない大会／ディビジョン ID（存在秘匿の対照群）。 */
    private static final long ABSENT_ID = 9_000_000_000_001L;

    private static final String THREADS = "/api/v1/bulletin/threads";
    private static final String READ_ALL = "/api/v1/bulletin/threads/read-all";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private BulletinThreadRepository threadRepository;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> tournamentIds = new ArrayList<>();
    private final List<Long> divisionIds = new ArrayList<>();

    // --- スコープ ---
    /** 主催組織。 */
    private Long organizerOrgId;
    /** 他テナント（別組織）。 */
    private Long foreignOrgId;
    /** 参加チーム（大会 T のディビジョン D に REGISTERED）。 */
    private Long participantTeamId;
    /** 大会と無関係のチーム（TEAM スコープ退行確認用）。 */
    private Long plainTeamId;
    /** 非公開スペースの大会。 */
    private Long tournamentId;
    /** 大会 T のディビジョン。 */
    private Long divisionId;
    /** 公開スペース（is_public=true）の別大会。 */
    private Long publicTournamentId;

    // --- 人物 ---
    /** 主催組織 ADMIN（「大会の主催者／管理者」）。 */
    private Long organizerAdminId;
    /** 主催組織の一般会員（ADMIN でも参加チームの一員でもない）。 */
    private Long organizerMemberId;
    /** 他テナント組織の ADMIN。 */
    private Long foreignAdminId;
    /** 参加チームの代表（ADMIN）。 */
    private Long participantAdminId;
    /** 参加チームの一般メンバー（閲覧のみ可・投稿不可）。 */
    private Long participantMemberId;
    /** どこにも所属しない部外者。 */
    private Long outsiderId;
    /** 大会と無関係のチームのメンバー（TEAM スコープ退行確認用）。 */
    private Long plainTeamMemberId;

    /** 大会 T の既存スレッド表題。 */
    private static final String T_TITLE = "CMP2058 大会T 既存スレッド";
    /** ディビジョン D の既存スレッド表題。 */
    private static final String D_TITLE = "CMP2058 ディビジョンD 既存スレッド";
    /** 公開大会の既存スレッド表題（大会 T の一覧へ混入してはならない）。 */
    private static final String PUB_TITLE = "CMP2058 公開大会 既存スレッド";
    /** 無関係チームの既存スレッド表題。 */
    private static final String TEAM_TITLE = "CMP2058 チーム 既存スレッド";
    /** 主催組織の既存スレッド表題。 */
    private static final String ORG_TITLE = "CMP2058 組織 既存スレッド";

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

            organizerOrgId = organization("CMP2058 主催組織");
            foreignOrgId = organization("CMP2058 他組織");
            participantTeamId = team("CMP2058 参加チーム");
            plainTeamId = team("CMP2058 無関係チーム");

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
            tournamentId = tournament(organizerOrgId, "CMP2058 非公開大会 " + suffix, organizerAdminId);
            divisionId = division(tournamentId, "CMP2058 ディビジョン " + suffix);
            participant(divisionId, participantTeamId);
            publicTournamentId = tournament(organizerOrgId, "CMP2058 公開大会 " + suffix, organizerAdminId);

            contactSpace(ContactSpaceScopeType.TOURNAMENT, tournamentId, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, publicTournamentId, true);
            return null;
        });
        thread(com.mannschaft.app.bulletin.ScopeType.TOURNAMENT, tournamentId, T_TITLE);
        thread(com.mannschaft.app.bulletin.ScopeType.TOURNAMENT_DIVISION, divisionId, D_TITLE);
        thread(com.mannschaft.app.bulletin.ScopeType.TOURNAMENT, publicTournamentId, PUB_TITLE);
        thread(com.mannschaft.app.bulletin.ScopeType.TEAM, plainTeamId, TEAM_TITLE);
        thread(com.mannschaft.app.bulletin.ScopeType.ORGANIZATION, organizerOrgId, ORG_TITLE);
    }

    @AfterEach
    void 所有行だけ片付ける() {
        try {
            List<Long> tScopes = new ArrayList<>(tournamentIds);
            deleteThreads("TOURNAMENT", tScopes);
            deleteThreads("TOURNAMENT_DIVISION", divisionIds);
            deleteThreads("TEAM", teamIds);
            deleteThreads("ORGANIZATION", orgIds);
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
    // AC-1〜3: TOURNAMENT スコープで主催者／管理者が取得・投稿・一括既読できる
    // ========================================================================

    @Test
    @DisplayName("AC-1: 主催組織ADMINは TOURNAMENT の掲示板スレッド一覧を 200 で取得でき、自大会のスレッドだけが返る")
    void ac1_主催組織ADMINはTOURNAMENTの一覧を取得できる() throws Exception {
        MvcResult result = http(list("TOURNAMENT", tournamentId), organizerAdminId).andReturn();

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        List<String> titles = titles(result);
        assertThat(titles).contains(T_TITLE).doesNotContain(PUB_TITLE, D_TITLE, TEAM_TITLE, ORG_TITLE);
    }

    @Test
    @DisplayName("AC-2: 主催組織ADMINは TOURNAMENT へスレッドを投稿でき 201、DB に scope_type=TOURNAMENT で保存される")
    void ac2_主催組織ADMINはTOURNAMENTへ投稿できる() throws Exception {
        MvcResult result = http(create("TOURNAMENT", tournamentId, "CMP2058 主催者投稿"), organizerAdminId).andReturn();

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(201);
        assertThat(countThreads("TOURNAMENT", tournamentId, "CMP2058 主催者投稿")).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-3: 主催組織ADMINは TOURNAMENT の一括既読ができ 200、未読スレッドが既読化される")
    void ac3_主催組織ADMINはTOURNAMENTを一括既読できる() throws Exception {
        MvcResult result = http(readAll("TOURNAMENT", tournamentId), organizerAdminId).andReturn();

        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
        assertThat(json(result).path("data").path("markedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-4: 参加チーム代表は TOURNAMENT へ投稿でき、参加チーム一般メンバーは一覧取得のみ可（投稿は 403 TOUR_031）")
    void ac4_参加チーム代表は投稿でき一般メンバーは閲覧のみ() throws Exception {
        MvcResult adminPost = http(create("TOURNAMENT", tournamentId, "CMP2058 代表投稿"), participantAdminId).andReturn();
        assertThat(adminPost.getResponse().getStatus()).as(body(adminPost)).isEqualTo(201);

        MvcResult memberList = http(list("TOURNAMENT", tournamentId), participantMemberId).andReturn();
        assertThat(memberList.getResponse().getStatus()).as(body(memberList)).isEqualTo(200);
        assertThat(titles(memberList)).contains(T_TITLE);

        MvcResult memberPost = http(create("TOURNAMENT", tournamentId, "CMP2058 一般投稿"), participantMemberId).andReturn();
        assertThat(memberPost.getResponse().getStatus()).as(body(memberPost)).isEqualTo(403);
        assertThat(errorCode(memberPost)).isEqualTo("TOUR_031");
        assertThat(countThreads("TOURNAMENT", tournamentId, "CMP2058 一般投稿")).isZero();
    }

    // ========================================================================
    // AC-5〜6: TOURNAMENT_DIVISION でも同様
    // ========================================================================

    @Test
    @DisplayName("AC-5: 主催組織ADMINは TOURNAMENT_DIVISION の一覧取得 200・投稿 201・一括既読 200 ができる")
    void ac5_主催組織ADMINはTOURNAMENT_DIVISIONで取得投稿既読できる() throws Exception {
        MvcResult listed = http(list("TOURNAMENT_DIVISION", divisionId), organizerAdminId).andReturn();
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        assertThat(titles(listed)).contains(D_TITLE).doesNotContain(T_TITLE, PUB_TITLE);

        MvcResult created = http(create("TOURNAMENT_DIVISION", divisionId, "CMP2058 ディビジョン主催者投稿"), organizerAdminId)
                .andReturn();
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);
        assertThat(countThreads("TOURNAMENT_DIVISION", divisionId, "CMP2058 ディビジョン主催者投稿")).isEqualTo(1);

        MvcResult read = http(readAll("TOURNAMENT_DIVISION", divisionId), organizerAdminId).andReturn();
        assertThat(read.getResponse().getStatus()).as(body(read)).isEqualTo(200);
        assertThat(json(read).path("data").path("markedCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-6: 参加チーム一般メンバーは TOURNAMENT_DIVISION の一覧を 200 で取得し一括既読できる")
    void ac6_参加チーム一般メンバーはTOURNAMENT_DIVISIONを閲覧既読できる() throws Exception {
        MvcResult listed = http(list("TOURNAMENT_DIVISION", divisionId), participantMemberId).andReturn();
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        assertThat(titles(listed)).contains(D_TITLE);

        MvcResult read = http(readAll("TOURNAMENT_DIVISION", divisionId), participantMemberId).andReturn();
        assertThat(read.getResponse().getStatus()).as(body(read)).isEqualTo(200);
    }

    // ========================================================================
    // AC-7〜10: 権限なしは現行仕様どおり 403（TOUR_030 閲覧 / TOUR_031 投稿）
    // 存在秘匿（不在 ID と同じ 404 に畳む）は別戦役で扱う。本戦役は 400 の解消に絞る。
    // ========================================================================

    /**
     * AC-7: 閲覧権限のない者の TOURNAMENT 一覧は 403 TOUR_030。
     * 存在秘匿（不在 ID と同一応答）は別戦役で扱う。
     */
    @Test
    @DisplayName("AC-7: 部外者・主催組織の一般会員・他テナントADMINの TOURNAMENT 一覧は 403 TOUR_030")
    void ac7_権限なしのTOURNAMENT一覧は403() throws Exception {
        for (Long actor : List.of(outsiderId, organizerMemberId, foreignAdminId)) {
            assertForbidden(list("TOURNAMENT", tournamentId), actor, "TOUR_030");
        }
    }

    /**
     * AC-8: 投稿権限のない者の TOURNAMENT 投稿は 403 TOUR_031 で、DB に何も作られない。
     * 存在秘匿（不在 ID と同一応答）は別戦役で扱う。
     */
    @Test
    @DisplayName("AC-8: 部外者・主催組織の一般会員・他テナントADMINの TOURNAMENT 投稿は 403 TOUR_031 で、DB に何も作られない")
    void ac8_権限なしのTOURNAMENT投稿は403() throws Exception {
        for (Long actor : List.of(outsiderId, organizerMemberId, foreignAdminId)) {
            assertForbidden(create("TOURNAMENT", tournamentId, "CMP2058 越境投稿"), actor, "TOUR_031");
        }
        assertThat(countThreads("TOURNAMENT", tournamentId, "CMP2058 越境投稿")).isZero();
    }

    /**
     * AC-9: 閲覧権限のない者の TOURNAMENT 一括既読は 403 TOUR_030 で、既読行が作られない。
     * 存在秘匿（不在 ID と同一応答）は別戦役で扱う。
     */
    @Test
    @DisplayName("AC-9: 部外者・主催組織の一般会員・他テナントADMINの TOURNAMENT 一括既読は 403 TOUR_030 で、既読行が作られない")
    void ac9_権限なしのTOURNAMENT一括既読は403() throws Exception {
        for (Long actor : List.of(outsiderId, organizerMemberId, foreignAdminId)) {
            assertForbidden(readAll("TOURNAMENT", tournamentId), actor, "TOUR_030");
            Integer reads = jdbc.queryForObject("SELECT COUNT(*) FROM bulletin_read_status WHERE user_id = ?",
                    Integer.class, actor);
            assertThat(reads).isZero();
        }
    }

    /**
     * AC-10: 権限のない者の TOURNAMENT_DIVISION 一覧・一括既読は 403 TOUR_030、投稿は 403 TOUR_031。
     * 存在秘匿（不在 ID と同一応答）は別戦役で扱う。
     */
    @Test
    @DisplayName("AC-10: 部外者・他テナントADMINの TOURNAMENT_DIVISION 一覧・一括既読は 403 TOUR_030、投稿は 403 TOUR_031")
    void ac10_権限なしのTOURNAMENT_DIVISIONは403() throws Exception {
        for (Long actor : List.of(outsiderId, foreignAdminId)) {
            assertForbidden(list("TOURNAMENT_DIVISION", divisionId), actor, "TOUR_030");
            assertForbidden(create("TOURNAMENT_DIVISION", divisionId, "CMP2058 越境D投稿"), actor, "TOUR_031");
            assertForbidden(readAll("TOURNAMENT_DIVISION", divisionId), actor, "TOUR_030");
        }
        assertThat(countThreads("TOURNAMENT_DIVISION", divisionId, "CMP2058 越境D投稿")).isZero();
    }

    @Test
    @DisplayName("AC-11: 未認証は実在・不在の大会IDとも同一の 401（一覧・投稿・一括既読）")
    void ac11_未認証は実在不在とも同一の401() throws Exception {
        assertSameRejection(list("TOURNAMENT", tournamentId), list("TOURNAMENT", ABSENT_ID), null, 401);
        assertSameRejection(create("TOURNAMENT", tournamentId, "CMP2058 未認証"),
                create("TOURNAMENT", ABSENT_ID, "CMP2058 未認証"), null, 401);
        assertSameRejection(readAll("TOURNAMENT", tournamentId), readAll("TOURNAMENT", ABSENT_ID), null, 401);
    }

    // ========================================================================
    // AC-12: 公開スペース（is_public=true）
    // ========================================================================

    @Test
    @DisplayName("AC-12: 公開スペースの大会は部外者でも一覧 200 で読めるが、投稿は 403 TOUR_031 で拒否される（PUBLIC は read-only）")
    void ac12_公開スペースは部外者も閲覧可だが投稿不可() throws Exception {
        MvcResult listed = http(list("TOURNAMENT", publicTournamentId), outsiderId).andReturn();
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        assertThat(titles(listed)).contains(PUB_TITLE).doesNotContain(T_TITLE);

        MvcResult posted = http(create("TOURNAMENT", publicTournamentId, "CMP2058 公開越境投稿"), outsiderId).andReturn();
        assertThat(posted.getResponse().getStatus()).as(body(posted)).isEqualTo(403);
        assertThat(errorCode(posted)).isEqualTo("TOUR_031");
        assertThat(countThreads("TOURNAMENT", publicTournamentId, "CMP2058 公開越境投稿")).isZero();
    }

    // ========================================================================
    // AC-13〜14: 既存 TEAM / ORGANIZATION スコープの退行なし
    // ========================================================================

    @Test
    @DisplayName("AC-13: TEAM スコープはメンバーが一覧 200・投稿 201・一括既読 200、非メンバーは一覧 403 のまま")
    void ac13_TEAMスコープは退行しない() throws Exception {
        MvcResult listed = http(list("TEAM", plainTeamId), plainTeamMemberId).andReturn();
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        assertThat(titles(listed)).contains(TEAM_TITLE).doesNotContain(T_TITLE, ORG_TITLE);

        MvcResult created = http(create("TEAM", plainTeamId, "CMP2058 チーム投稿"), plainTeamMemberId).andReturn();
        assertThat(created.getResponse().getStatus()).as(body(created)).isEqualTo(201);

        MvcResult read = http(readAll("TEAM", plainTeamId), plainTeamMemberId).andReturn();
        assertThat(read.getResponse().getStatus()).as(body(read)).isEqualTo(200);

        MvcResult denied = http(list("TEAM", plainTeamId), outsiderId).andReturn();
        assertThat(denied.getResponse().getStatus()).as(body(denied)).isEqualTo(403);
    }

    @Test
    @DisplayName("AC-14: ORGANIZATION スコープは組織メンバーが一覧 200 で自組織スレッドを取得できる")
    void ac14_ORGANIZATIONスコープは退行しない() throws Exception {
        MvcResult listed = http(list("ORGANIZATION", organizerOrgId), organizerMemberId).andReturn();
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        assertThat(titles(listed)).contains(ORG_TITLE).doesNotContain(T_TITLE, TEAM_TITLE);
    }

    // ========================================================================
    // 補助
    // ========================================================================

    /** 拒否応答が 403 かつ期待のエラーコードであることを確かめる。 */
    private void assertForbidden(MockHttpServletRequestBuilder request, Long actor, String expectedCode) throws Exception {
        MvcResult r = http(request, actor).andReturn();
        assertThat(r.getResponse().getStatus()).as("actor=" + actor + " " + body(r)).isEqualTo(403);
        assertThat(errorCode(r)).as("actor=" + actor + " " + body(r)).isEqualTo(expectedCode);
    }

    private void assertSameRejection(MockHttpServletRequestBuilder existing, MockHttpServletRequestBuilder absent,
                                     Long actor, int expectedStatus) throws Exception {
        MvcResult a = http(existing, actor).andReturn();
        MvcResult b = http(absent, actor).andReturn();
        String label = "actor=" + actor + " existing=" + body(a) + " absent=" + body(b);
        assertThat(b.getResponse().getStatus()).as(label).isEqualTo(expectedStatus);
        assertThat(a.getResponse().getStatus()).as(label).isEqualTo(b.getResponse().getStatus());
        if (expectedStatus != 401) {
            assertThat(errorCode(a)).as(label).isNotBlank().isEqualTo(errorCode(b));
            assertThat(json(a).path("error").path("message").asText()).as(label)
                    .isEqualTo(json(b).path("error").path("message").asText());
        }
    }

    private MockHttpServletRequestBuilder list(String scopeType, Long scopeId) {
        return get(THREADS).param("scope_type", scopeType).param("scope_id", String.valueOf(scopeId));
    }

    private MockHttpServletRequestBuilder create(String scopeType, Long scopeId, String title) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("scopeId", String.valueOf(scopeId));
        body.put("title", title);
        body.put("body", "CMP2058 本文");
        return post(THREADS).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private MockHttpServletRequestBuilder readAll(String scopeType, Long scopeId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId);
        return post(READ_ALL).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private ResultActions http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mvc.perform(request);
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

    private List<String> titles(MvcResult result) throws Exception {
        List<String> titles = new ArrayList<>();
        json(result).path("data").forEach(n -> titles.add(n.path("title").asText()));
        return titles;
    }

    private int countThreads(String scopeType, Long scopeId, String title) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bulletin_threads WHERE scope_type = ? AND scope_id = ? AND title = ?",
                Integer.class, scopeType, scopeId, title);
        return n == null ? 0 : n;
    }

    private void deleteThreads(String scopeType, List<Long> scopeIds) {
        for (Long id : scopeIds) {
            jdbc.update("DELETE FROM bulletin_read_status WHERE thread_id IN "
                    + "(SELECT t.id FROM (SELECT id FROM bulletin_threads WHERE scope_type = ? AND scope_id = ?) t)",
                    scopeType, id);
            jdbc.update("DELETE FROM bulletin_threads WHERE scope_type = ? AND scope_id = ?", scopeType, id);
        }
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp2058-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long organization(String name) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("cmp2058-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        orgIds.add(id);
        return id;
    }

    private Long team(String name) {
        Long id = teams.save(TeamEntity.builder().slug("cmp2058-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    /** 大会を seed する（列は TournamentScopeContractIT#insertTournament と同一・TournamentEntity ⇔ DDL 突合済み）。 */
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

    /** 既存スレッドを Repository で seed する（API の修正有無に依らず一覧の中身を観測するため）。 */
    private void thread(com.mannschaft.app.bulletin.ScopeType scopeType, Long scopeId, String title) {
        tx(() -> threadRepository.save(BulletinThreadEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).authorId(organizerAdminId)
                .title(title).body("CMP2058 seed 本文").build()));
    }
}
