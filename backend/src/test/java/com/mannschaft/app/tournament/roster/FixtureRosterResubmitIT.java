package com.mannschaft.app.tournament.roster;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberEntity;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateMemberRepository;
import com.mannschaft.app.tournament.entry.TournamentEntryTemplateRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * <b>大会メンバー表の再提出・テンプレ上書き適用が、既存の名簿行と UNIQUE(match_id, user_id) で衝突しないことを
 * 実 MySQL の上で API 経由で検証する結合テスト（CMP-261127-2135）。</b>
 *
 * <h2>欠陥の仕組み</h2>
 * <p>派生 delete（{@code deleteBy...}）は行を読んで remove を予約するだけで、DB への DELETE は flush まで遅れる。
 * 一方 {@code tournament_match_rosters} は IDENTITY 主キーのため、続く {@code saveAll} は persist の時点で INSERT を即時に流す。
 * その結果、旧行の DELETE より先に新行の INSERT が DB へ届き、同じ選手を含む再提出が {@code uq_tmr_match_user} に当たって
 * 500 COMMON_999 になっていた。テンプレ上書き適用（overwriteExisting=true）も同じ「delete → saveAll」の型で同じく落ちていた。</p>
 *
 * <p>実 DB の一意制約は Flyway（V8.046）にあるが、テスト環境は {@code ddl-auto=create} で Entity からスキーマを作るため、
 * 以前は Entity 側に一意制約の宣言が無く原理的に再現しなかった。本修正で Entity にも {@code uq_tmr_match_user} を宣言し、
 * 共通基底 {@link AbstractMySqlIntegrationTest} の構成のまま再現できるようにした（構成キーを増やさない）。</p>
 *
 * <p>金型: {@code TournamentEntryScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL +
 * 手動 SecurityContext + {@code MembershipTestHelper}）。チーム ADMIN は {@code isAdminOrAbove}（user_roles）と
 * {@code isMember}（memberships）の両系統に seed する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("大会メンバー表 再提出・テンプレ上書き適用 の一意制約衝突（CMP-261127-2135）")
class FixtureRosterResubmitIT extends AbstractMySqlIntegrationTest {

    private static final String ROSTER_ME = "/api/v1/tournaments/{tId}/matches/{matchId}/rosters/me";
    private static final String APPLY_TEMPLATE = ROSTER_ME + "/apply-template";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private TournamentEntryTemplateRepository templateRepository;
    @Autowired
    private TournamentEntryTemplateMemberRepository templateMemberRepository;
    @Autowired
    private TournamentEntryTemplateStaffRepository templateStaffRepository;
    @PersistenceContext
    private EntityManager em;

    private String suffix;
    private Long teamId;
    private Long teamAdminId;
    private Long player1;
    private Long player2;
    private Long player3;
    private Long tournamentId;
    private Long participantId;
    private Long matchId;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        insertRoleIfAbsent("MEMBER", "メンバー", 4);

        Long orgId = insertOrganization("名簿再提出ORG" + suffix);
        teamId = insertTeam("名簿再提出TEAM" + suffix);
        Long opponentTeamId = insertTeam("名簿再提出相手" + suffix);
        Long orgAdminId = insertUser("roster-orgadmin-" + suffix + "@example.com");
        teamAdminId = insertUser("roster-teamadmin-" + suffix + "@example.com");
        player1 = insertUser("roster-p1-" + suffix + "@example.com");
        player2 = insertUser("roster-p2-" + suffix + "@example.com");
        player3 = insertUser("roster-p3-" + suffix + "@example.com");

        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        tournamentId = insertTournament(orgId, "名簿再提出大会" + suffix, orgAdminId);
        Long divisionId = insertDivision(tournamentId, "名簿再提出部" + suffix);
        participantId = insertParticipant(divisionId, teamId);
        Long opponentParticipantId = insertParticipant(divisionId, opponentTeamId);
        Long matchdayId = insertMatchday(divisionId);
        matchId = insertMatch(matchdayId, participantId, opponentParticipantId);

        em.flush();
        em.clear();
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("(a) 名簿行がある状態で同じ内容を PUT しても200で全置換される")
    void a_同じ内容の再提出が200で全置換される() throws Exception {
        setAuth(teamAdminId);
        Map<String, Object> body = rosterBody(List.of(player(player1, 10), player(player2, 7)), List.of());
        assertOk(putRoster(body), "1 回目の PUT");

        assertOk(putRoster(body), "2 回目の PUT（同じ内容）");

        assertThat(rosterUserIds()).containsExactlyInAnyOrder(player1, player2);
    }

    @Test
    @DisplayName("(b) 一部が重なる内容を PUT しても200で、旧行は残らず新しい内容に全置換される")
    void b_一部重複の再提出が200で全置換される() throws Exception {
        setAuth(teamAdminId);
        assertOk(putRoster(rosterBody(List.of(player(player1, 10), player(player2, 7)),
                List.of(staff("監督", "旧監督")))), "1 回目の PUT");

        MvcResult second = putRoster(rosterBody(List.of(player(player2, 8), player(player3, 9)),
                List.of(staff("監督", "新監督"))));
        assertOk(second, "2 回目の PUT（player2 が重なる）");

        // 応答にも旧行（player1・旧監督）が混ざらず、新しい内容だけが返る
        assertThat(responsePlayerUserIds(second)).containsExactlyInAnyOrder(player2, player3);
        assertThat(responseStaffNames(second)).containsExactly("新監督");
        assertThat(rosterUserIds()).containsExactlyInAnyOrder(player2, player3);
        Number jersey = (Number) em.createNativeQuery("SELECT jersey_number FROM tournament_match_rosters "
                        + "WHERE match_id = :mid AND user_id = :uid")
                .setParameter("mid", matchId)
                .setParameter("uid", player2)
                .getSingleResult();
        assertThat(jersey.intValue()).isEqualTo(8);
    }

    @Test
    @DisplayName("(c) テンプレ適用（overwriteExisting=true）の後に、テンプレと重なる内容を PUT しても200")
    void c_テンプレ適用後の提出が200() throws Exception {
        setAuth(teamAdminId);
        UUID templateId = createTemplate(List.of(player1, player2), List.of("監督テンプレ"));
        assertOk(applyTemplate(templateId, true), "テンプレ適用");
        assertThat(rosterUserIds()).containsExactlyInAnyOrder(player1, player2);

        assertOk(putRoster(rosterBody(List.of(player(player1, 11), player(player3, 12)), List.of())),
                "テンプレ適用後の PUT");

        assertThat(rosterUserIds()).containsExactlyInAnyOrder(player1, player3);
    }

    @Test
    @DisplayName("(d) 既存の名簿行がある状態で overwriteExisting=true のテンプレ適用が200で全置換される")
    void d_既存行がある状態のテンプレ上書き適用が200() throws Exception {
        setAuth(teamAdminId);
        assertOk(putRoster(rosterBody(List.of(player(player1, 10), player(player2, 7)),
                List.of(staff("監督", "旧監督")))), "既存行の PUT");
        UUID templateId = createTemplate(List.of(player1, player3), List.of("テンプレ監督"));

        MvcResult applied = applyTemplate(templateId, true);
        assertOk(applied, "テンプレ上書き適用");

        // 応答にも旧行（player2・旧監督）が混ざらず、テンプレの内容だけが返る
        assertThat(responsePlayerUserIds(applied)).containsExactlyInAnyOrder(player1, player3);
        assertThat(responseStaffNames(applied)).containsExactly("テンプレ監督");
        assertThat(rosterUserIds()).containsExactlyInAnyOrder(player1, player3);
    }

    @Test
    @DisplayName("(e) ベンチ役員も、再提出・テンプレ上書き適用のたびに旧行が残らず全置換される")
    void e_ベンチ役員も全置換される() throws Exception {
        setAuth(teamAdminId);
        assertOk(putRoster(rosterBody(List.of(player(player1, 10)),
                List.of(staff("監督", "旧監督"), staff("コーチ", "旧コーチ")))), "1 回目の PUT");
        assertThat(staffNames()).containsExactlyInAnyOrder("旧監督", "旧コーチ");

        assertOk(putRoster(rosterBody(List.of(player(player1, 10)), List.of(staff("監督", "新監督")))),
                "2 回目の PUT");
        assertThat(staffNames()).containsExactly("新監督");

        UUID templateId = createTemplate(List.of(player1), List.of("テンプレ監督", "テンプレコーチ"));
        assertOk(applyTemplate(templateId, true), "テンプレ上書き適用");
        assertThat(staffNames()).containsExactlyInAnyOrder("テンプレ監督", "テンプレコーチ");
        assertThat(rosterUserIds()).containsExactly(player1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MvcResult putRoster(Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(ROSTER_ME, tournamentId, matchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private MvcResult applyTemplate(UUID templateId, boolean overwriteExisting) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateId", templateId.toString());
        body.put("overwriteExisting", overwriteExisting);
        return mockMvc.perform(post(APPLY_TEMPLATE, tournamentId, matchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body))).andReturn();
    }

    private static void assertOk(MvcResult result, String label) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("%s の本文: %s", label, result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    /** 応答 JSON の data.players[].userId。 */
    private List<Long> responsePlayerUserIds(MvcResult result) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode p : objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/players")) {
            ids.add(p.get("userId").asLong());
        }
        return ids;
    }

    /** 応答 JSON の data.staff[].name。 */
    private List<String> responseStaffNames(MvcResult result) throws Exception {
        List<String> names = new ArrayList<>();
        for (JsonNode s : objectMapper.readTree(result.getResponse().getContentAsString()).at("/data/staff")) {
            names.add(s.get("name").asText());
        }
        return names;
    }

    /** 自チームのエントリーテンプレ（選手・ベンチ役員）を作る。 */
    private UUID createTemplate(List<Long> memberUserIds, List<String> staffNames) {
        UUID templateId = templateRepository.save(TournamentEntryTemplateEntity.builder()
                .teamId(teamId).name("名簿テンプレ" + UUID.randomUUID()).sortOrder((short) 0)
                .createdBy(teamAdminId)
                .build()).getId();
        short order = 0;
        for (Long userId : memberUserIds) {
            templateMemberRepository.save(TournamentEntryTemplateMemberEntity.builder()
                    .templateId(templateId).userId(userId).jerseyNumber(20 + order).position("FW")
                    .sortOrder(order++)
                    .build());
        }
        short staffOrder = 0;
        for (String name : staffNames) {
            templateStaffRepository.save(TournamentEntryTemplateStaffEntity.builder()
                    .templateId(templateId).role("監督").name(name).sortOrder(staffOrder++)
                    .build());
        }
        em.flush();
        return templateId;
    }

    private List<Long> rosterUserIds() {
        em.flush();
        @SuppressWarnings("unchecked")
        List<Number> rows = em.createNativeQuery("SELECT user_id FROM tournament_match_rosters "
                        + "WHERE match_id = :mid AND participant_id = :pid")
                .setParameter("mid", matchId)
                .setParameter("pid", participantId)
                .getResultList();
        return rows.stream().map(Number::longValue).toList();
    }

    private List<String> staffNames() {
        em.flush();
        @SuppressWarnings("unchecked")
        List<String> rows = em.createNativeQuery("SELECT name FROM match_roster_staff "
                        + "WHERE match_id = :mid AND participant_id = :pid")
                .setParameter("mid", matchId)
                .setParameter("pid", participantId)
                .getResultList();
        return rows;
    }

    private static Map<String, Object> player(Long userId, Integer jersey) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", userId);
        p.put("isStarter", true);
        p.put("jerseyNumber", jersey);
        p.put("position", "FW");
        return p;
    }

    private static Map<String, Object> staff(String role, String name) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("role", role);
        s.put("name", name);
        return s;
    }

    private static Map<String, Object> rosterBody(List<Map<String, Object>> players, List<Map<String, Object>> staff) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("players", players);
        body.put("staff", staff);
        return body;
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** roles はグローバル参照テーブルのため deleteAll せず name で引く idempotent seed。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, '名簿', 'テスト', '名簿テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('rs-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('rs-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /**
     * 大会を seed する。entity の NOT NULL 列（@Builder.Default は DDL デフォルトを生成しない）を
     * すべて明示指定する（TournamentScopeContractIT 踏襲）。
     */
    private Long insertTournament(Long orgId, String name, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO tournaments (organization_id, name, format, sport, "
                                + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                                + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                                + "VALUES (:orgId, :name, 'LEAGUE', 'SOCCER', 3, 1, 0, 1, 0, 0, 0, '点', 'SINGLE', "
                                + "1, 'PUBLIC', 'OPEN', 0, :createdBy, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertDivision(Long tId, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", tId)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertParticipant(Long divisionId, Long tmId) {
        em.createNativeQuery(
                        "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                + "VALUES (:divId, :teamId, 'REGISTERED', NOW())")
                .setParameter("divId", divisionId)
                .setParameter("teamId", tmId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM tournament_participants WHERE division_id = :divId")
                .setParameter("divId", divisionId)
                .getSingleResult()).longValue();
    }

    private Long insertMatchday(Long divisionId) {
        em.createNativeQuery(
                        "INSERT INTO tournament_matchdays (division_id, name, matchday_number, status, "
                                + "created_at, updated_at) VALUES (:divId, :name, 1, 'SCHEDULED', NOW(), NOW())")
                .setParameter("divId", divisionId)
                .setParameter("name", "第1節" + suffix)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM tournament_matchdays WHERE division_id = :divId")
                .setParameter("divId", divisionId)
                .getSingleResult()).longValue();
    }

    private Long insertMatch(Long matchdayId, Long homeParticipantId, Long awayParticipantId) {
        em.createNativeQuery(
                        "INSERT INTO tournament_matches (matchday_id, match_number, home_participant_id, "
                                + "away_participant_id, result, leg, status, version, created_at, updated_at) "
                                + "VALUES (:mdId, 1, :home, :away, 'PENDING', 1, 'SCHEDULED', 0, NOW(), NOW())")
                .setParameter("mdId", matchdayId)
                .setParameter("home", homeParticipantId)
                .setParameter("away", awayParticipantId)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM tournament_matches WHERE matchday_id = :mdId")
                .setParameter("mdId", matchdayId)
                .getSingleResult()).longValue();
    }
}
