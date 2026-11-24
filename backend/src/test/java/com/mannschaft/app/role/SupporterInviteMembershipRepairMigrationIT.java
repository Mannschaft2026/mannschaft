package com.mannschaft.app.role;

import com.mannschaft.app.role.entity.InviteTokenEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0157 の既存データ是正 migration（{@value #MIGRATION}）の試練。
 *
 * <p>SUPPORTER 招待で参加し、memberships が MEMBER・user_roles に SUPPORTER 行が残った在籍だけを
 * SUPPORTER に直し、当該 user_roles 行を消す。確保記録で SUPPORTER 招待の承諾が裏付けられない在籍
 * （管理者のロール割当、MEMBER 招待、権限ロール保持者、退会済み）は触らない。2回流しても結果は同じ（冪等）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("SUPPORTER 招待の在籍是正 migration（CMP-261124-0157）")
class SupporterInviteMembershipRepairMigrationIT extends InviteAcceptanceItSupport {

    static final String MIGRATION = "db/migration/V267.20261124005850__fix_supporter_invite_memberships_and_user_roles.sql";

    @Autowired private DataSource dataSource;

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("是正対象は SUPPORTER に直り user_roles 行が消える。非対象は変わらない。再実行しても同じ")
    void repairsOnlyBackedSupporterInvites(String type) throws Exception {
        Scope scope = scope(type);
        Long supporterRoleId = roleId(scope, "SUPPORTER", 5);
        Long adminRoleId = roleId(scope, "ADMIN", 2);
        InviteTokenEntity supporterInvite = token(scope, 0);
        jdbc.update("UPDATE invite_tokens SET role_id = ? WHERE id = ?", supporterRoleId, supporterInvite.getId());
        InviteTokenEntity memberInvite = token(scope, 0);

        // 対象: SUPPORTER 招待を承諾（確保記録 GRANTED）し、MEMBER 在籍＋SUPPORTER 行になった人
        Long target = user();
        insertMembership(scope, target, "MEMBER");
        insertRole(scope, target, supporterRoleId);
        insertClaim(supporterInvite.getId(), target, "GRANTED", utcNow(), true);

        // 対象: 確保記録が CLAIMED のまま付与印（grant_committed_at）だけ立った人
        Long committedOnly = user();
        insertMembership(scope, committedOnly, "MEMBER");
        insertRole(scope, committedOnly, supporterRoleId);
        var committedClaim = insertClaim(supporterInvite.getId(), committedOnly, "CLAIMED", utcNow(), true);
        jdbc.update("UPDATE invite_token_claims SET grant_committed_at = UTC_TIMESTAMP(6) WHERE id = UNHEX(?)",
                committedClaim.toString().replace("-", ""));

        // 非対象: 確保記録の無い SUPPORTER 行（管理者のロール割当など他経路）
        Long assignedOnly = user();
        insertMembership(scope, assignedOnly, "MEMBER");
        insertRole(scope, assignedOnly, supporterRoleId);

        // 非対象: MEMBER 招待で参加した人に、後から SUPPORTER 行が付いた（招待の裏付けは MEMBER）
        Long memberInvitee = user();
        insertMembership(scope, memberInvitee, "MEMBER");
        insertRole(scope, memberInvitee, supporterRoleId);
        insertClaim(memberInvite.getId(), memberInvitee, "GRANTED", utcNow(), true);

        // 非対象: SUPPORTER 招待の確保が戻された（RELEASED）人
        Long released = user();
        insertMembership(scope, released, "MEMBER");
        insertRole(scope, released, supporterRoleId);
        insertClaim(supporterInvite.getId(), released, "RELEASED", utcNow(), false);

        // 非対象: 権限ロール（ADMIN）を持つ MEMBER 在籍（SUPPORTER 招待の確保記録があっても権限行は消さない）
        Long admin = user();
        insertMembership(scope, admin, "MEMBER");
        insertRole(scope, admin, adminRoleId);
        insertClaim(supporterInvite.getId(), admin, "GRANTED", utcNow(), true);

        // 非対象: 退会済み（left_at あり）の在籍
        Long left = user();
        insertMembership(scope, left, "MEMBER");
        jdbc.update("UPDATE memberships SET left_at = UTC_TIMESTAMP() WHERE user_id = ?", left);
        insertRole(scope, left, supporterRoleId);
        insertClaim(supporterInvite.getId(), left, "GRANTED", utcNow(), true);

        runMigration();
        runMigration();

        assertThat(roleKinds(target, scope, true)).containsExactly("SUPPORTER");
        assertThat(roleNames(target, scope)).isEmpty();
        assertThat(roleKinds(committedOnly, scope, true)).containsExactly("SUPPORTER");
        assertThat(roleNames(committedOnly, scope)).isEmpty();

        assertThat(roleKinds(assignedOnly, scope, true)).containsExactly("MEMBER");
        assertThat(roleNames(assignedOnly, scope)).containsExactly("SUPPORTER");
        assertThat(roleKinds(memberInvitee, scope, true)).containsExactly("MEMBER");
        assertThat(roleNames(memberInvitee, scope)).containsExactly("SUPPORTER");
        assertThat(roleKinds(released, scope, true)).containsExactly("MEMBER");
        assertThat(roleNames(released, scope)).containsExactly("SUPPORTER");
        assertThat(roleKinds(admin, scope, true)).containsExactly("MEMBER");
        assertThat(roleNames(admin, scope)).containsExactly("ADMIN");
        assertThat(roleKinds(left, scope, false)).containsExactly("MEMBER");
        assertThat(roleNames(left, scope)).containsExactly("SUPPORTER");
    }

    private void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
        }
    }

    private void insertRole(Scope scope, Long userId, Long roleId) {
        String column = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
        jdbc.update("INSERT INTO user_roles (user_id, role_id, " + column + ", granted_by, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())", userId, roleId, scope.id(), userId);
    }

    private List<String> roleKinds(Long userId, Scope scope, boolean active) {
        return jdbc.queryForList("SELECT role_kind FROM memberships WHERE user_id = ? AND scope_type = ? AND scope_id = ? "
                + "AND left_at IS " + (active ? "NULL" : "NOT NULL"), String.class, userId, scope.type(), scope.id());
    }

    private List<String> roleNames(Long userId, Scope scope) {
        String column = "TEAM".equals(scope.type()) ? "team_id" : "organization_id";
        return jdbc.queryForList("SELECT r.name FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.user_id = ? AND ur." + column + " = ?", String.class, userId, scope.id());
    }
}
