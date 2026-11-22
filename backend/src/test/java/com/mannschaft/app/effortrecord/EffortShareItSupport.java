package com.mannschaft.app.effortrecord;

import com.mannschaft.app.membership.domain.EffortShareDisposition;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.event.TeamEffortMembershipRevokedEvent;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * F06.4.1 PR-3a（資格境界・memberships・Flyway）試練の共通土台（CMP-261115-2145）。
 *
 * <p>{@link EffortRecordItSupport} の実 JWT・実 Security・実 MySQL 構成をそのまま継承し、構成キーは増やさない
 * （{@code @MockitoBean} / {@code @DynamicPropertySource} / {@code @Import} などは宣言しない。ContextOverrideCensusGuardTest）。
 * 失効イベントは Spring の event multicaster へ試験中だけリスナーを足して観測する（Bean を差し替えない）。</p>
 */
abstract class EffortShareItSupport extends EffortRecordItSupport {

    static final String TEAMS = "/api/v1/teams";

    @Autowired protected MembershipService membershipService;
    @Autowired protected RoleService roleService;
    @Autowired private ApplicationEventMulticaster multicaster;

    /** 観測した失効イベント（発行時に TX 同期が有効なら、コミット後に committed 状態を添えて記録する）。 */
    protected final List<CapturedRevoke> revokedEvents = new CopyOnWriteArrayList<>();
    /** TX の外で発行された失効イベント（AFTER_COMMIT にならない発行）。 */
    protected final List<TeamEffortMembershipRevokedEvent> revokedOutsideTx = new CopyOnWriteArrayList<>();
    /** 発行されたがロールバックで捨てられた失効イベント。 */
    protected final List<TeamEffortMembershipRevokedEvent> revokedRolledBack = new CopyOnWriteArrayList<>();

    private ApplicationListener<ApplicationEvent> revokeListener;

    /**
     * 観測した失効イベント。committedDisposition / committedLeftAtSet はコミット直後に別接続で読んだ memberships の値。
     */
    protected record CapturedRevoke(TeamEffortMembershipRevokedEvent event, String committedDisposition,
                                    boolean committedLeftAtSet) {
    }

    @BeforeEach
    void 失効イベントの観測を始める() {
        revokeListener = this::onEvent;
        multicaster.addApplicationListener(revokeListener);
    }

    @AfterEach
    void 失効イベントの観測を止める() {
        multicaster.removeApplicationListener(revokeListener);
        revokedEvents.clear();
        revokedOutsideTx.clear();
        revokedRolledBack.clear();
    }

    private void onEvent(ApplicationEvent event) {
        Object payload = event instanceof PayloadApplicationEvent<?> p ? p.getPayload() : event;
        if (!(payload instanceof TeamEffortMembershipRevokedEvent revoked)) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            revokedOutsideTx.add(revoked);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    revokedRolledBack.add(revoked);
                    return;
                }
                Map<String, Object> row = committedMembership(revoked.endedMembershipId());
                revokedEvents.add(new CapturedRevoke(revoked, (String) row.get("disposition"),
                        row.get("left_at") != null));
            }
        });
    }

    /** 別接続（autocommit）で committed の memberships 行を読む。 */
    protected Map<String, Object> committedMembership(Long membershipId) {
        Map<String, Object> row = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT left_at, effort_share_disposition FROM memberships WHERE id = ?")) {
            ps.setLong(1, membershipId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    row.put("left_at", rs.getObject(1));
                    row.put("disposition", rs.getString(2));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return row;
    }

    // ───────────────────────────── fixture ─────────────────────────────

    protected String slug(Long teamId) {
        return tx(() -> teams.findById(teamId).orElseThrow().getSlug());
    }

    protected Long roleId(String name) {
        return count("SELECT id FROM roles WHERE name = ?1", name);
    }

    /**
     * 招待参加と同じ形（user_roles の MEMBER と memberships）で在籍させる。実 RoleService#assignRole を通す
     * （MEMBER の user_roles を試験ヘルパーで直接作らない。MembershipTestHelperGuardTest）。
     */
    protected void invitedMember(Long adminId, Long userId, Long teamId) {
        // roles の MEMBER 行は teamAdmin(adminId, teamId) の所属作成時に seed 済み（MembershipTestHelper）。
        roleService.assignRole(teamId, "TEAM", userId, roleId("MEMBER"), adminId);
    }

    /** 応援者（SUPPORTER）として直接所属させる。 */
    protected void supporter(Long userId, Long teamId) {
        tx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
            return null;
        });
    }

    /** プラットフォームの SYSTEM_ADMIN ロールを持たせる（チームには所属させない）。 */
    protected Long platformSystemAdmin(String label) {
        Long id = systemAdmin(label);
        tx(() -> {
            MembershipTestHelper.insertUserRole(em, id, "SYSTEM_ADMIN", null, null);
            return null;
        });
        return id;
    }

    /** 実 MembershipService#join で（再）加入させ、新しい加入期間 ID を返す。 */
    protected Long rejoin(Long userId, Long teamId) {
        MembershipCreateRequest request = new MembershipCreateRequest();
        request.setUserId(userId);
        request.setScopeType(ScopeType.TEAM);
        request.setScopeId(teamId);
        request.setRoleKind(RoleKind.MEMBER);
        request.setSource("EFFORT_PR3A_TEST");
        membershipService.join(request);
        return activeMembershipId(userId, teamId);
    }

    protected Long activeMembershipId(Long userId, Long teamId) {
        return count("SELECT id FROM memberships WHERE user_id = ?1 AND scope_type = 'TEAM' AND scope_id = ?2 "
                + "AND left_at IS NULL", userId, teamId);
    }

    /** memberships 行の観測値（left_at の有無・leave_reason・role_kind・disposition・user/scope）。 */
    protected Map<String, Object> membershipRow(Long membershipId) {
        Object[] row = tx(() -> (Object[]) em.createNativeQuery("SELECT user_id, scope_type, scope_id, role_kind, "
                        + "left_at IS NOT NULL, leave_reason, effort_share_disposition FROM memberships WHERE id = :id")
                .setParameter("id", membershipId).getSingleResult());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("userId", ((Number) row[0]).longValue());
        map.put("scopeType", String.valueOf(row[1]));
        map.put("scopeId", ((Number) row[2]).longValue());
        map.put("roleKind", String.valueOf(row[3]));
        map.put("left", ((Number) row[4]).intValue() == 1);
        map.put("leaveReason", row[5] == null ? null : String.valueOf(row[5]));
        map.put("disposition", row[6] == null ? null : String.valueOf(row[6]));
        return map;
    }

    protected EffortShareDisposition disposition(Long membershipId) {
        Object value = membershipRow(membershipId).get("disposition");
        return value == null ? null : EffortShareDisposition.valueOf((String) value);
    }

    /** そのチームでの user_roles のロール名（名前順）。 */
    protected List<String> teamRoles(Long userId, Long teamId) {
        List<?> rows = tx(() -> em.createNativeQuery("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                        + "WHERE ur.user_id = :u AND ur.team_id = :t ORDER BY r.name")
                .setParameter("u", userId).setParameter("t", teamId).getResultList());
        List<String> names = new ArrayList<>();
        rows.forEach(r -> names.add(String.valueOf(r)));
        return names;
    }

    // ───────────────────────────── HTTP（実 Controller） ─────────────────────────────

    /** 自己脱退（DELETE /api/v1/teams/{slug}/me）。 */
    protected MockHttpServletResponse leaveTeamApi(Long actor, Long teamId) throws Exception {
        return http(delete(TEAMS + "/{slug}/me", slug(teamId)), actor);
    }

    /** 管理者による除名（DELETE /api/v1/teams/{slug}/members/{userId}。RoleService#removeMember）。 */
    protected MockHttpServletResponse removeMemberApi(Long admin, Long teamId, Long target) throws Exception {
        return http(delete(TEAMS + "/{slug}/members/{userId}", slug(teamId), target), admin);
    }

    /** ロール変更（PATCH /api/v1/teams/{slug}/members/{userId}/role）。 */
    protected MockHttpServletResponse changeRoleApi(Long admin, Long teamId, Long target, String roleName) throws Exception {
        return http(patch(TEAMS + "/{slug}/members/{userId}/role", slug(teamId), target)
                .contentType(MediaType.APPLICATION_JSON).content("{\"roleId\":" + roleId(roleName) + "}"), admin);
    }
}
