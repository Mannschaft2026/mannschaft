package com.mannschaft.app.support.test.scopecontent;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CMP-261124-0159: 親チーム上限の IT 共通フィクスチャ（F00 §20.6・§20.9 区分の略記）。
 *
 * <p>非公開チーム（{@code MEMBERS_AND_ABOVE}）と、その親組織・別チーム・別組織を作り、閲覧者区分ごとの利用者を置く。
 * 在籍サポーター・GUEST は正規の UI では作れない「是正前データ」を模したフィクスチャ
 * （SUPPORTER は memberships だけ・GUEST は user_roles だけ）。</p>
 *
 * <p>呼び出し側のトランザクション内で {@link #create} し、終了時に {@link #delete} で作成した行だけを消す
 * （テスト作成データの後始末。既存データに触れない）。</p>
 */
public final class ScopeContentFixture {

    /** 閲覧者区分（Matrix 表の 8 区分＋等価性の番人で使う 2 区分）。 */
    public enum Actor {
        ADMIN, DEPUTY_ADMIN, MEMBER, SUPPORTER, GUEST, OUTSIDER, OTHER_TEAM_MEMBER, OTHER_ORG_MEMBER,
        PARENT_ORG_ADMIN, SYSTEM_ADMIN
    }

    public final Long parentOrgId;
    public final Long otherOrgId;
    public final Long teamId;
    public final String teamSlug;
    public final Long otherTeamId;
    public final Map<Actor, Long> users;

    private ScopeContentFixture(Long parentOrgId, Long otherOrgId, Long teamId, String teamSlug, Long otherTeamId,
                                Map<Actor, Long> users) {
        this.parentOrgId = parentOrgId;
        this.otherOrgId = otherOrgId;
        this.teamId = teamId;
        this.teamSlug = teamSlug;
        this.otherTeamId = otherTeamId;
        this.users = users;
    }

    public Long user(Actor actor) {
        return users.get(actor);
    }

    /** 非公開チーム（{@code MEMBERS_AND_ABOVE}）を中心にフィクスチャを作る。 */
    public static ScopeContentFixture create(EntityManager em) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Long parentOrg = TeamOrgFixtureHelper.insertOrganization(em, "上限試練親組織" + suffix, "c59p-" + suffix);
        Long otherOrg = TeamOrgFixtureHelper.insertOrganization(em, "上限試練他組織" + suffix, "c59o-" + suffix);
        String slug = "c59t-" + suffix;
        Long team = TeamOrgFixtureHelper.insertTeam(em, "上限試練チーム" + suffix, slug);
        Long otherTeam = TeamOrgFixtureHelper.insertTeam(em, "上限試練他チーム" + suffix, "c59x-" + suffix);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, team, parentOrg, "ACTIVE", LocalDateTime.now());
        setTeamVisibility(em, team, "MEMBERS_AND_ABOVE");

        long base = 9_590_000_000L + ThreadLocalRandom.current().nextLong(0, 9_000_000L) * 20;
        Map<Actor, Long> users = new EnumMap<>(Actor.class);
        for (Actor actor : Actor.values()) {
            Long id = base + actor.ordinal();
            MembershipTestHelper.insertActiveUser(em, id);
            users.put(actor, id);
        }
        MembershipTestHelper.insertMembership(em, users.get(Actor.ADMIN), ScopeType.TEAM, team, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, users.get(Actor.ADMIN), "ADMIN", team, null);
        MembershipTestHelper.insertMembership(em, users.get(Actor.DEPUTY_ADMIN), ScopeType.TEAM, team, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, users.get(Actor.DEPUTY_ADMIN), "DEPUTY_ADMIN", team, null);
        MembershipTestHelper.insertMembership(em, users.get(Actor.MEMBER), ScopeType.TEAM, team, RoleKind.MEMBER);
        // 是正前データを模したフィクスチャ（正規の UI では作れない状態）
        MembershipTestHelper.insertMembership(em, users.get(Actor.SUPPORTER), ScopeType.TEAM, team, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, users.get(Actor.GUEST), "GUEST", team, null);
        MembershipTestHelper.insertMembership(em, users.get(Actor.OTHER_TEAM_MEMBER), ScopeType.TEAM, otherTeam,
                RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, users.get(Actor.OTHER_TEAM_MEMBER), "ADMIN", otherTeam, null);
        MembershipTestHelper.insertMembership(em, users.get(Actor.OTHER_ORG_MEMBER), ScopeType.ORGANIZATION, otherOrg,
                RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, users.get(Actor.PARENT_ORG_ADMIN), ScopeType.ORGANIZATION, parentOrg,
                RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, users.get(Actor.PARENT_ORG_ADMIN), "ADMIN", null, parentOrg);
        MembershipTestHelper.insertUserRole(em, users.get(Actor.SYSTEM_ADMIN), "SYSTEM_ADMIN", null, null);
        em.flush();
        return new ScopeContentFixture(parentOrg, otherOrg, team, slug, otherTeam, users);
    }

    /** チームの公開範囲を書き換える（公開→非公開・非公開→公開の即時性の検証に使う）。 */
    public static void setTeamVisibility(EntityManager em, Long teamId, String visibility) {
        em.createNativeQuery("UPDATE teams SET visibility = :v WHERE id = :id")
                .setParameter("v", visibility).setParameter("id", teamId).executeUpdate();
    }

    /** 作成した行だけを消す（テスト作成データの後始末）。 */
    public void delete(EntityManager em) {
        for (Long scope : new Long[]{teamId, otherTeamId}) {
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id")
                    .setParameter("id", scope).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id = :id").setParameter("id", scope)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :id").setParameter("id", scope)
                    .executeUpdate();
        }
        for (Long scope : new Long[]{parentOrgId, otherOrgId}) {
            em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'ORGANIZATION' AND scope_id = :id")
                    .setParameter("id", scope).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE organization_id = :id").setParameter("id", scope)
                    .executeUpdate();
        }
        em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)").setParameter("ids", users.values())
                .executeUpdate();
        em.createNativeQuery("DELETE FROM teams WHERE id IN (:ids)").setParameter("ids", java.util.List.of(teamId,
                otherTeamId)).executeUpdate();
        em.createNativeQuery("DELETE FROM organizations WHERE id IN (:ids)").setParameter("ids", java.util.List.of(
                parentOrgId, otherOrgId)).executeUpdate();
        em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)").setParameter("ids", users.values())
                .executeUpdate();
    }
}
