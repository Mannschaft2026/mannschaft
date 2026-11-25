package com.mannschaft.app.effortrecord;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.role.event.RolePurgeScopeExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F06.4.1 PR-3a 試練: memberships 共通コアの回帰（E17 / AC-68。CMP-261115-2145）。
 *
 * <p>加入期間の終了理由の列（effort_share_disposition）と endCause の伝播を入れても、既存の5経路
 * （自己脱退・管理者による除名・退会時の整理・承継・最後の ADMIN 保護）の HTTP 応答・本文・DB 状態が変わらないことを、
 * 経路ごとに別の試験で固定する。どの試験も effort の原本・共有を一切作らない（本機能を使っていない状態でも同じ結果）。
 * 既存の観測点（status・本文・left_at・leave_reason・user_roles・在籍判定）に加え、改修で増える観測点
 * （disposition と失効イベント）を同じ試験で固定する。</p>
 *
 * <p>実 JWT・実 Security・実 Controller・実 MySQL（{@link EffortShareItSupport}）。DB・認可・自前 Bean はモックしない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3a memberships 共通コアの回帰（AC-68）")
class EffortMembershipRegressionIT extends EffortShareItSupport {

    @Autowired private AccessControlService accessControlService;
    @Autowired private RolePurgeScopeExecutor rolePurgeScopeExecutor;

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    // ───────────────────────────── 自己脱退 ─────────────────────────────

    @Test
    @DisplayName("AC-68: 自己脱退（招待参加の MEMBER）は 204・本文なし、left_at と leave_reason=SELF、user_roles 削除、他の在籍は不変。disposition=REVOKED")
    void AC68_自己脱退_招待参加MEMBER() throws Exception {
        Long team = team("回帰-自己脱退");
        Long admin = user("回帰管理者");
        Long member = user("回帰メンバー");
        teamAdmin(admin, team);
        invitedMember(admin, member, team);
        Long m1 = activeMembershipId(member, team);
        Long adminMembership = activeMembershipId(admin, team);

        MockHttpServletResponse response = leaveTeamApi(member, team);

        assertThat(response.getStatus()).as(body(response)).isEqualTo(204);
        assertThat(body(response)).isEmpty();
        Map<String, Object> row = membershipRow(m1);
        assertThat(row.get("left")).isEqualTo(true);
        assertThat(row.get("leaveReason")).isEqualTo("SELF");
        assertThat(row.get("roleKind")).isEqualTo("MEMBER");
        assertThat(teamRoles(member, team)).isEmpty();
        assertThat(accessControlService.isMember(member, team, "TEAM")).isFalse();
        // 他の在籍（管理者）は変わらない。
        assertThat(membershipRow(adminMembership).get("left")).isEqualTo(false);
        assertThat(membershipRow(adminMembership).get("disposition")).isNull();
        assertThat(teamRoles(admin, team)).containsExactly("ADMIN");
        // 改修で増える観測点: 明示脱退は REVOKED。
        assertThat(row.get("disposition")).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("AC-68: 自己脱退（user_roles を持たない MEMBER 在籍のみ）は 204、leave_reason=SELF。disposition=REVOKED")
    void AC68_自己脱退_在籍のみMEMBER() throws Exception {
        Long team = team("回帰-在籍のみ脱退");
        Long admin = user("回帰管理者");
        Long member = user("回帰在籍のみ");
        teamAdmin(admin, team);
        member(member, team);
        Long m1 = activeMembershipId(member, team);

        MockHttpServletResponse response = leaveTeamApi(member, team);

        assertThat(response.getStatus()).as(body(response)).isEqualTo(204);
        assertThat(body(response)).isEmpty();
        Map<String, Object> row = membershipRow(m1);
        assertThat(row.get("left")).isEqualTo(true);
        assertThat(row.get("leaveReason")).isEqualTo("SELF");
        assertThat(accessControlService.isMember(member, team, "TEAM")).isFalse();
        assertThat(row.get("disposition")).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("AC-68: 応援者（SUPPORTER）の自己脱退 API は従来どおり 422 ROLE_015 で、在籍も disposition も変えない")
    void AC68_応援者の自己脱退は従来どおり拒否() throws Exception {
        Long team = team("回帰-応援者");
        Long admin = user("回帰管理者");
        Long fan = user("回帰応援者");
        teamAdmin(admin, team);
        supporter(fan, team);
        Long m1 = activeMembershipId(fan, team);

        expectError(leaveTeamApi(fan, team), 422, "ROLE_015");

        Map<String, Object> row = membershipRow(m1);
        assertThat(row.get("left")).isEqualTo(false);
        assertThat(row.get("disposition")).isNull();
        assertThat(revokedEvents).isEmpty();
    }

    // ───────────────────────────── 管理者による除名 ─────────────────────────────

    @Test
    @DisplayName("AC-68: 管理者による除名（RoleService.removeMember）は 204・本文なし、leave_reason=REMOVED、user_roles 削除。disposition=REVOKED")
    void AC68_管理者による除名() throws Exception {
        Long team = team("回帰-除名");
        Long admin = user("回帰管理者");
        Long member = user("回帰除名対象");
        teamAdmin(admin, team);
        invitedMember(admin, member, team);
        Long m1 = activeMembershipId(member, team);

        MockHttpServletResponse response = removeMemberApi(admin, team, member);

        assertThat(response.getStatus()).as(body(response)).isEqualTo(204);
        assertThat(body(response)).isEmpty();
        Map<String, Object> row = membershipRow(m1);
        assertThat(row.get("left")).isEqualTo(true);
        assertThat(row.get("leaveReason")).isEqualTo("REMOVED");
        assertThat(teamRoles(member, team)).isEmpty();
        assertThat(accessControlService.isMember(member, team, "TEAM")).isFalse();
        assertThat(teamRoles(admin, team)).containsExactly("ADMIN");
        assertThat(row.get("disposition")).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("AC-68: 管理者でない者の除名 API は従来どおり 403 で、対象の在籍・disposition・user_roles を変えない")
    void AC68_非管理者の除名は従来どおり拒否() throws Exception {
        Long team = team("回帰-除名拒否");
        Long admin = user("回帰管理者");
        Long member = user("回帰メンバー");
        Long other = user("回帰別メンバー");
        teamAdmin(admin, team);
        invitedMember(admin, member, team);
        invitedMember(admin, other, team);
        Long m1 = activeMembershipId(member, team);

        assertThat(removeMemberApi(other, team, member).getStatus()).isEqualTo(403);

        assertThat(membershipRow(m1).get("left")).isEqualTo(false);
        assertThat(membershipRow(m1).get("disposition")).isNull();
        assertThat(teamRoles(member, team)).containsExactly("MEMBER");
        assertThat(revokedEvents).isEmpty();
    }

    // ───────────────────────────── 退会時の整理 ─────────────────────────────

    @Test
    @DisplayName("AC-68: 退会時の整理（AccountPurged → RolePurgeScopeExecutor）は従来どおり在籍を終え leave_reason=REMOVED・user_roles 削除。disposition=RETAINED_ACCOUNT_WITHDRAWAL で失効イベントは出ない")
    void AC68_退会時の整理() {
        Long team = team("回帰-退会整理");
        Long admin = user("回帰管理者");
        Long member = user("回帰退会者");
        teamAdmin(admin, team);
        invitedMember(admin, member, team);
        Long m1 = activeMembershipId(member, team);

        rolePurgeScopeExecutor.processScope(member, team, "TEAM", false, UUID.randomUUID());

        Map<String, Object> row = membershipRow(m1);
        assertThat(row.get("left")).isEqualTo(true);
        assertThat(row.get("leaveReason")).isEqualTo("REMOVED");
        assertThat(teamRoles(member, team)).isEmpty();
        assertThat(accessControlService.isMember(member, team, "TEAM")).isFalse();
        assertThat(row.get("disposition")).isEqualTo("RETAINED_ACCOUNT_WITHDRAWAL");
        assertThat(revokedEvents).as("退会だけでは共有を失効させない").isEmpty();
        assertThat(revokedOutsideTx).isEmpty();
    }

    // ───────────────────────────── 承継 ─────────────────────────────

    @Test
    @DisplayName("AC-68: 承継（最後の ADMIN の退会整理）は従来どおり MEMBER を ADMIN へ昇格し、承継者の加入期間 ID と disposition は不変、退会者は RETAINED_ACCOUNT_WITHDRAWAL")
    void AC68_退会時の承継() {
        Long team = team("回帰-承継");
        Long admin = user("回帰唯一の管理者");
        Long heir = user("回帰承継者");
        teamAdmin(admin, team);
        invitedMember(admin, heir, team);
        Long adminMembership = activeMembershipId(admin, team);
        Long heirMembership = activeMembershipId(heir, team);

        rolePurgeScopeExecutor.processScope(admin, team, "TEAM", true, UUID.randomUUID());

        assertThat(teamRoles(heir, team)).containsExactly("ADMIN");
        assertThat(activeMembershipId(heir, team)).as("承継者の加入期間は同じ行のまま").isEqualTo(heirMembership);
        assertThat(membershipRow(heirMembership).get("left")).isEqualTo(false);
        assertThat(membershipRow(heirMembership).get("disposition")).isNull();
        assertThat(teamRoles(admin, team)).isEmpty();
        assertThat(membershipRow(adminMembership).get("left")).isEqualTo(true);
        assertThat(membershipRow(adminMembership).get("leaveReason")).isEqualTo("REMOVED");
        assertThat(membershipRow(adminMembership).get("disposition")).isEqualTo("RETAINED_ACCOUNT_WITHDRAWAL");
        assertThat(revokedEvents).isEmpty();
    }

    @Test
    @DisplayName("AC-68: 承継（オーナー譲渡 transferOwnership）はロールだけを入れ替え、両者の加入期間 ID・在籍・disposition を変えず失効イベントも出ない")
    void AC68_オーナー譲渡() {
        Long team = team("回帰-譲渡");
        Long admin = user("回帰譲渡元");
        Long heir = user("回帰譲渡先");
        teamAdmin(admin, team);
        invitedMember(admin, heir, team);
        Long adminMembership = activeMembershipId(admin, team);
        Long heirMembership = activeMembershipId(heir, team);

        roleService.transferOwnership(team, "TEAM", admin, heir);

        assertThat(teamRoles(heir, team)).containsExactly("ADMIN");
        assertThat(teamRoles(admin, team)).containsExactly("MEMBER");
        assertThat(activeMembershipId(admin, team)).isEqualTo(adminMembership);
        assertThat(activeMembershipId(heir, team)).isEqualTo(heirMembership);
        assertThat(disposition(adminMembership)).isNull();
        assertThat(disposition(heirMembership)).isNull();
        assertThat(revokedEvents).isEmpty();
        assertThat(revokedOutsideTx).isEmpty();
    }

    // ───────────────────────────── 最後の ADMIN 保護 ─────────────────────────────

    @Test
    @DisplayName("AC-68: 最後の ADMIN の自己脱退 API は従来どおり 409 ROLE_004 で、在籍・user_roles・disposition を変えず失効イベントも出ない")
    void AC68_最後のADMINの自己脱退は409() throws Exception {
        Long team = team("回帰-最後のADMIN");
        Long admin = user("回帰唯一の管理者");
        Long member = user("回帰メンバー");
        teamAdmin(admin, team);
        invitedMember(admin, member, team);
        Long adminMembership = activeMembershipId(admin, team);

        expectError(leaveTeamApi(admin, team), 409, "ROLE_004");

        assertThat(membershipRow(adminMembership).get("left")).isEqualTo(false);
        assertThat(membershipRow(adminMembership).get("leaveReason")).isNull();
        assertThat(disposition(adminMembership)).isNull();
        assertThat(teamRoles(admin, team)).containsExactly("ADMIN");
        assertThat(revokedEvents).isEmpty();
        assertThat(revokedOutsideTx).isEmpty();
    }

    @Test
    @DisplayName("AC-68: 最後の ADMIN を MembershipService.leave で終えようとすると従来どおり MEMBERSHIP_LAST_ADMIN_BLOCKED（409）で、行も disposition も変えない")
    void AC68_最後のADMINの在籍終了はLAST_ADMIN_BLOCKED() {
        Long team = team("回帰-LAST_ADMIN_BLOCKED");
        Long admin = user("回帰唯一の管理者");
        teamAdmin(admin, team);
        Long adminMembership = activeMembershipId(admin, team);
        MembershipLeaveRequest request = new MembershipLeaveRequest();
        request.setLeaveReason(LeaveReason.SELF);

        assertThatThrownBy(() -> membershipService.leave(adminMembership, request))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode().getCode()).isEqualTo("MEMBERSHIP_LAST_ADMIN_BLOCKED"));

        assertThat(membershipRow(adminMembership).get("left")).isEqualTo(false);
        assertThat(disposition(adminMembership)).isNull();
        assertThat(revokedEvents).isEmpty();
        assertThat(revokedRolledBack).as("ロールバックした終了 TX から失効を出さない（出してもコミットされない）").isEmpty();
    }

    @Test
    @DisplayName("AC-68: 本機能を使っていない（effort の原本・共有が無い）チームでも、上の経路は effort 表へ何も書かない")
    void AC68_effort未使用でもeffort表に書かない() throws Exception {
        Long team = team("回帰-effort未使用");
        Long admin = user("回帰管理者");
        Long member = user("回帰メンバー");
        teamAdmin(admin, team);
        invitedMember(admin, member, team);

        assertThat(leaveTeamApi(member, team).getStatus()).isEqualTo(204);

        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id IN (?1, ?2)", admin, member)).isZero();
        assertThat(count("SELECT COUNT(*) FROM effort_team_shares WHERE team_id = ?1", team)).isZero();
        assertThat(count("SELECT COUNT(*) FROM timeline_effort_sources WHERE team_id = ?1", team)).isZero();
        assertThat(count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id IN (?1, ?2)", admin, member)).isZero();
    }
}
