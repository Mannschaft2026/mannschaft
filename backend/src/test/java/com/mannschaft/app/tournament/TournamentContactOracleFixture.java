package com.mannschaft.app.tournament;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * CMP-261116-0726 大会連絡スペースの存在オラクル是正 — 受け入れテスト共通フィクスチャ（試練）。
 *
 * <p>設計書 §5「試練隊向け」のデータ構成をそのまま実 MySQL に別 TX で commit する
 * （{@code BulletinTournamentScopeGlobalContractIT} の fixture を範とし、参加ステータスとスペース種別を引数化した）。
 * 呼び出し側 IT は {@code @Transactional(propagation = NOT_SUPPORTED)} で、自分が作った子行（フォルダ・ファイル・
 * 監査ログ等）を先に消してから {@link #cleanup()} を呼ぶ。</p>
 *
 * <ul>
 *   <li>主催組織 O（orgAdmin, orgMember）／他組織 F（foreignAdmin）／ SYSTEM_ADMIN（sysAdmin）／部外者（outsider）</li>
 *   <li>参加チーム P（REGISTERED: pAdmin=ADMIN, pDeputy=DEPUTY_ADMIN, pMember=一般）</li>
 *   <li>W（WITHDRAWN: wAdmin）／ X（DISQUALIFIED: xAdmin）— 資格喪失チーム。閲覧権なし側</li>
 *   <li>T1: BULLETIN/CHAT とも非公開。D1 も非公開。D1 に P/W/X が参加</li>
 *   <li>T2: BULLETIN 公開・CHAT 非公開 ／ T3: BULLETIN 非公開・CHAT 公開（AC-17）。いずれも P 参加</li>
 *   <li>T4m: BULLETIN スペース行なし（CHAT のみ）／ T4d: BULLETIN スペース論理削除（AC-19）。いずれも P 参加</li>
 *   <li>T5: O の別大会（T1 の D1 を T5 配下パスで叩く AC-18）／ TF: F の大会</li>
 * </ul>
 */
public class TournamentContactOracleFixture {

    /** 確実に存在しない ID の基点（不在の対照群）。用途ごとに {@code ABSENT_ID - n} を使う。 */
    public static final long ABSENT_ID = Long.MAX_VALUE - 7;

    private final EntityManager em;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager txManager;
    private final UserRepository users;
    private final OrganizationRepository organizations;
    private final TeamRepository teams;
    private final String tag;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> tournamentIds = new ArrayList<>();
    private final List<Long> divisionIds = new ArrayList<>();
    private final Map<String, UUID> spaceIds = new HashMap<>();

    // --- スコープ ---
    public Long organizerOrgId;
    public Long foreignOrgId;
    public Long participantTeamId;
    public Long withdrawnTeamId;
    public Long disqualifiedTeamId;

    // --- 大会・ディビジョン ---
    public Long t1;
    public Long d1;
    public Long t2;
    public Long d2;
    public Long t3;
    public Long d3;
    public Long t4Missing;
    public Long d4Missing;
    public Long t4Deleted;
    public Long d4Deleted;
    public Long t5;
    public Long tf;

    // --- 人物 ---
    public Long orgAdminId;
    public Long orgMemberId;
    public Long sysAdminId;
    public Long foreignAdminId;
    public Long pAdminId;
    public Long pDeputyId;
    public Long pMemberId;
    public Long wAdminId;
    public Long xAdminId;
    public Long outsiderId;

    public TournamentContactOracleFixture(EntityManager em, JdbcTemplate jdbc, PlatformTransactionManager txManager,
                                          UserRepository users, OrganizationRepository organizations,
                                          TeamRepository teams, String tag) {
        this.em = em;
        this.jdbc = jdbc;
        this.txManager = txManager;
        this.users = users;
        this.organizations = organizations;
        this.teams = teams;
        this.tag = tag;
    }

    /** 閲覧権なし（存在を知り得ない）利用者。秘匿の主役。 */
    public List<Long> nonViewers() {
        return List.of(outsiderId, orgMemberId, foreignAdminId, wAdminId, xAdminId);
    }

    /** 全データを別 TX で commit する。 */
    public void setUp() {
        tx(() -> {
            orgAdminId = user("主催管理者");
            orgMemberId = user("主催一般");
            sysAdminId = user("システム管理者");
            foreignAdminId = user("他組織管理者");
            pAdminId = user("参加代表");
            pDeputyId = user("参加副代表");
            pMemberId = user("参加一般");
            wAdminId = user("辞退チーム代表");
            xAdminId = user("失格チーム代表");
            outsiderId = user("部外者");

            organizerOrgId = organization(tag + " 主催組織");
            foreignOrgId = organization(tag + " 他組織");
            participantTeamId = team(tag + " 参加チーム");
            withdrawnTeamId = team(tag + " 辞退チーム");
            disqualifiedTeamId = team(tag + " 失格チーム");

            MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, organizerOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, organizerOrgId);
            MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, organizerOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, sysAdminId, "SYSTEM_ADMIN", null, null);
            MembershipTestHelper.insertMembership(em, foreignAdminId, ScopeType.ORGANIZATION, foreignOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, foreignAdminId, "ADMIN", null, foreignOrgId);
            MembershipTestHelper.insertMembership(em, pAdminId, ScopeType.TEAM, participantTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, pAdminId, "ADMIN", participantTeamId, null);
            MembershipTestHelper.insertMembership(em, pDeputyId, ScopeType.TEAM, participantTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, pDeputyId, "DEPUTY_ADMIN", participantTeamId, null);
            MembershipTestHelper.insertMembership(em, pMemberId, ScopeType.TEAM, participantTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, wAdminId, ScopeType.TEAM, withdrawnTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, wAdminId, "ADMIN", withdrawnTeamId, null);
            MembershipTestHelper.insertMembership(em, xAdminId, ScopeType.TEAM, disqualifiedTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, xAdminId, "ADMIN", disqualifiedTeamId, null);

            String s = UUID.randomUUID().toString().substring(0, 8);
            // T1: 両スペース非公開。D1 も非公開。P/W/X が D1 に参加。
            t1 = tournament(organizerOrgId, tag + " T1 " + s);
            d1 = division(t1, tag + " D1 " + s);
            participant(d1, participantTeamId, "REGISTERED");
            participant(d1, withdrawnTeamId, "WITHDRAWN");
            participant(d1, disqualifiedTeamId, "DISQUALIFIED");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t1, ContactSpaceKind.BULLETIN, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t1, ContactSpaceKind.CHAT, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, d1, ContactSpaceKind.BULLETIN, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, d1, ContactSpaceKind.CHAT, false, false);

            // T2: BULLETIN 公開・CHAT 非公開（AC-17）
            t2 = tournament(organizerOrgId, tag + " T2 " + s);
            d2 = division(t2, tag + " D2 " + s);
            participant(d2, participantTeamId, "REGISTERED");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t2, ContactSpaceKind.BULLETIN, true, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t2, ContactSpaceKind.CHAT, false, false);

            // T3: BULLETIN 非公開・CHAT 公開（AC-17）
            t3 = tournament(organizerOrgId, tag + " T3 " + s);
            d3 = division(t3, tag + " D3 " + s);
            participant(d3, participantTeamId, "REGISTERED");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t3, ContactSpaceKind.BULLETIN, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t3, ContactSpaceKind.CHAT, true, false);

            // T4m: BULLETIN スペース行なし（CHAT のみ）（AC-19）
            t4Missing = tournament(organizerOrgId, tag + " T4m " + s);
            d4Missing = division(t4Missing, tag + " D4m " + s);
            participant(d4Missing, participantTeamId, "REGISTERED");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t4Missing, ContactSpaceKind.CHAT, false, false);

            // T4d: BULLETIN スペース論理削除（AC-19）
            t4Deleted = tournament(organizerOrgId, tag + " T4d " + s);
            d4Deleted = division(t4Deleted, tag + " D4d " + s);
            participant(d4Deleted, participantTeamId, "REGISTERED");
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t4Deleted, ContactSpaceKind.BULLETIN, false, true);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t4Deleted, ContactSpaceKind.CHAT, false, false);

            // T5: O の別大会（AC-18）／ TF: 他組織 F の大会
            t5 = tournament(organizerOrgId, tag + " T5 " + s);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t5, ContactSpaceKind.BULLETIN, false, false);
            tf = tournament(foreignOrgId, tag + " TF " + s);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, tf, ContactSpaceKind.BULLETIN, false, false);
            return null;
        });
    }

    /** 本フィクスチャが作った行だけを消す（呼び出し側の子行は先に消しておくこと）。 */
    public void cleanup() {
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
            jdbc.update("DELETE FROM audit_logs WHERE user_id = ?", id);
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
    }

    /** 連絡スペース ID（UUIDv7 相当の BINARY(16)）を返す。 */
    public UUID spaceId(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind) {
        UUID id = spaceIds.get(key(scopeType, scopeId, kind));
        if (id == null) {
            throw new IllegalStateException("fixture にスペースが無い: " + key(scopeType, scopeId, kind));
        }
        return id;
    }

    /** スペースの is_public 現在値（論理削除済みを含む）。 */
    public boolean isPublic(UUID spaceId) {
        Boolean v = jdbc.queryForObject(
                "SELECT is_public FROM tournament_contact_space WHERE id = UNHEX(REPLACE(?, '-', ''))",
                Boolean.class, spaceId.toString());
        return Boolean.TRUE.equals(v);
    }

    public List<Long> userIds() {
        return List.copyOf(userIds);
    }

    public List<Long> tournamentIds() {
        return List.copyOf(tournamentIds);
    }

    public List<Long> divisionIds() {
        return List.copyOf(divisionIds);
    }

    public <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    // ------------------------------------------------------------------------

    private static String key(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind) {
        return scopeType.name() + ":" + scopeId + ":" + kind.name();
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp0726-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long organization(String name) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("cmp0726-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        orgIds.add(id);
        return id;
    }

    private Long team(String name) {
        Long id = teams.save(TeamEntity.builder().slug("cmp0726-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    /** 大会を seed する（列は BulletinTournamentScopeGlobalContractIT#tournament と同一）。 */
    private Long tournament(Long orgId, String name) {
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
                .setParameter("createdBy", orgAdminId)
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

    private void participant(Long divId, Long teamId, String status) {
        em.createNativeQuery(
                        "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                + "VALUES (:divId, :teamId, :status, NOW())")
                .setParameter("divId", divId)
                .setParameter("teamId", teamId)
                .setParameter("status", status)
                .executeUpdate();
    }

    private void contactSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind,
                              boolean isPublic, boolean deleted) {
        UUID id = UUID.randomUUID();
        // created_at はエンティティ側 insertable=false・テスト DB に既定値が無いため明示する。
        em.createNativeQuery(
                        "INSERT INTO tournament_contact_space (id, scope_type, scope_id, space_kind, ref_id, is_public, "
                                + "created_at, updated_at, deleted_at) "
                                + "VALUES (UNHEX(REPLACE(:id, '-', '')), :st, :sid, :kind, 0, :pub, NOW(), NOW(), "
                                + (deleted ? "NOW()" : "NULL") + ")")
                .setParameter("id", id.toString())
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .setParameter("kind", kind.name())
                .setParameter("pub", isPublic)
                .executeUpdate();
        spaceIds.put(key(scopeType, scopeId, kind), id);
    }
}
