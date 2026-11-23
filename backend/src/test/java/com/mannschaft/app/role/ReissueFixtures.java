package com.mannschaft.app.role;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.entity.InviteTokenEntity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * CMP-261107-1833 PR3（再招待依頼の受け手側）の実 DB 試練が共有する fixture・観測（継承ではなく委譲で使う）。
 *
 * <p>契約 IT（{@link InviteAcceptanceItSupport} 直下）と故障注入 IT（{@link InviteAcceptanceSpyItSupport} 直下）の
 * 両方から使うため、基底クラスではなく同じパッケージの補助クラスにした（基底の protected は同パッケージから見える）。
 * 構成キーを増やさない（Spring の宣言を持たない）。</p>
 *
 * <p>API（設計 i）: {@code GET /api/v1/{teams|organizations}/{slug}/invite-reissue-requests?page&size}、
 * {@code POST …/{id}/reissue}、{@code POST …/{id}/decline}。一覧は {@code data.content[]}・{@code data.totalElements}
 * （参加申請の審査一覧と同じ形）、要素は {@code {id, requesterDisplayName, requesterAvatarUrl?, requestedAt, roleName}}。</p>
 */
final class ReissueFixtures {

    static final String PENDING_TABLE = "registration_pending_invites";
    static final String INVITE_MEMBERS = "INVITE_MEMBERS";

    private final InviteAcceptanceItSupport it;
    private final AuthTokenService authTokens;
    private final List<Long> pendingUsers = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> touchedUsers = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> groups = Collections.synchronizedList(new ArrayList<>());

    ReissueFixtures(InviteAcceptanceItSupport it, AuthTokenService authTokens) {
        this.it = it;
        this.authTokens = authTokens;
    }

    // ------------------------------------------------------------------
    // 場面
    // ------------------------------------------------------------------

    /** 再招待依頼が 1 件ある場面（依頼者・招待権限者・元の招待・保留）。 */
    record Case(InviteGenerationItSupport.Scope scope, String slug, Long admin, Long requester, String requesterName,
                InviteTokenEntity original, UUID pendingId) {
    }

    /** 非公開の招待先に、失効した MEMBER の招待から登録した依頼者の REISSUE_REQUESTED を 1 件作る。 */
    Case reissueRequested(String type) {
        return reissueRequested(type, "MEMBER");
    }

    /** 元の招待のロールを指定する版（{@code null} なら元の招待を引けない保留を作る）。 */
    Case reissueRequested(String type, String originalRole) {
        InviteGenerationItSupport.Scope scope = it.scope(type);
        makePrivate(scope);
        Long admin = it.admin(scope);
        return requestIn(scope, admin, originalRole, it.utcNow().minusHours(1));
    }

    /** 既存スコープに依頼をもう 1 件足す。 */
    Case requestIn(InviteGenerationItSupport.Scope scope, Long admin, String originalRole, LocalDateTime requestedAt) {
        Long requester = it.user();
        String name = "依頼者" + UUID.randomUUID().toString().substring(0, 8);
        it.jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", name, requester);
        InviteTokenEntity original = it.token(scope, 0, null, 3, 0, null, it.utcNow().minusDays(1));
        String hash;
        if (originalRole == null) {
            hash = new SecretTokenVault().hash(UUID.randomUUID().toString());
        } else {
            if (!"MEMBER".equals(originalRole)) {
                it.jdbc.update("UPDATE invite_tokens SET role_id = ? WHERE id = ?",
                        it.roleId(scope, originalRole, priority(originalRole)), original.getId());
            }
            hash = new SecretTokenVault().hash(it.plaintext(original));
        }
        UUID pendingId = insertPending(requester, scope, "REISSUE_REQUESTED", hash, requestedAt);
        return new Case(scope, slug(scope), admin, requester, name, original, pendingId);
    }

    static int priority(String role) {
        return switch (role) {
            case "ADMIN" -> 2;
            case "DEPUTY_ADMIN" -> 3;
            case "MEMBER" -> 4;
            case "SUPPORTER" -> 5;
            default -> 6;
        };
    }

    UUID insertPending(Long userId, InviteGenerationItSupport.Scope scope, String status, String tokenHash,
                       LocalDateTime reissueRequestedAt) {
        UUID id = UUID.randomUUID();
        String failure = List.of("EXPIRED", "REISSUE_REQUESTED", "REISSUE_DECLINED").contains(status) ? "ROLE_002" : null;
        it.jdbc.update("INSERT INTO " + PENDING_TABLE + " (id, user_id, token_hash, scope_type, scope_id, status, "
                        + "failure_code, reissue_requested_at, version, created_at, updated_at) VALUES (UNHEX(?), ?, ?, ?, ?, ?, ?, ?, 0, "
                        + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                id.toString().replace("-", ""), userId, tokenHash, scope.type(), scope.id(), status, failure,
                reissueRequestedAt);
        pendingUsers.add(userId);
        touchedUsers.add(userId);
        return id;
    }

    void makePrivate(InviteGenerationItSupport.Scope scope) {
        it.jdbc.update("UPDATE " + scope.table() + " SET visibility = ? WHERE id = ?",
                "TEAM".equals(scope.type()) ? "MEMBERS_AND_ABOVE" : "PRIVATE", scope.id());
    }

    String slug(InviteGenerationItSupport.Scope scope) {
        return it.jdbc.queryForObject("SELECT slug FROM " + scope.table() + " WHERE id = ?", String.class, scope.id());
    }

    static String base(InviteGenerationItSupport.Scope scope, String slug) {
        return "/api/v1/" + ("TEAM".equals(scope.type()) ? "teams" : "organizations") + "/" + slug
                + "/invite-reissue-requests";
    }

    static String base(Case c) {
        return base(c.scope(), c.slug());
    }

    static String reissuePath(Case c) {
        return base(c) + "/" + c.pendingId() + "/reissue";
    }

    static String declinePath(Case c) {
        return base(c) + "/" + c.pendingId() + "/decline";
    }

    static String invitesRoute(Case c) {
        return "/" + ("TEAM".equals(c.scope().type()) ? "teams" : "organizations") + "/" + c.slug()
                + "/invites#reissue-requests";
    }

    String bearer(Long userId) {
        touchedUsers.add(userId);
        return "Bearer " + authTokens.issueAccessToken(userId, List.of("MEMBER"), false);
    }

    /** INVITE_MEMBERS を持つ DEPUTY_ADMIN（権限グループで付与）。 */
    Long deputyWithInviteMembers(InviteGenerationItSupport.Scope scope) {
        Long deputy = it.actorWithRole(scope, "DEPUTY_ADMIN", 3);
        grantInviteMembers(scope, deputy);
        return deputy;
    }

    void grantInviteMembers(InviteGenerationItSupport.Scope scope, Long userId) {
        it.jdbc.update("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                + "SELECT ?, '招待', ?, UTC_TIMESTAMP(), UTC_TIMESTAMP() FROM DUAL "
                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = ?)", INVITE_MEMBERS, scope.type(), INVITE_MEMBERS);
        String name = "再招待試練" + UUID.randomUUID().toString().substring(0, 8);
        it.jdbc.update("INSERT INTO permission_groups (team_id, organization_id, target_role, name, created_at, updated_at) "
                        + "VALUES (?, ?, 'DEPUTY_ADMIN', ?, UTC_TIMESTAMP(), UTC_TIMESTAMP())",
                "TEAM".equals(scope.type()) ? scope.id() : null, "TEAM".equals(scope.type()) ? null : scope.id(), name);
        Long group = it.jdbc.queryForObject("SELECT id FROM permission_groups WHERE name = ?", Long.class, name);
        groups.add(group);
        it.jdbc.update("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                + "SELECT ?, id, UTC_TIMESTAMP() FROM permissions WHERE name = ?", group, INVITE_MEMBERS);
        it.jdbc.update("INSERT INTO user_permission_groups (user_id, group_id, created_at) VALUES (?, ?, UTC_TIMESTAMP())",
                userId, group);
    }

    void revokeInviteMembers(Long userId) {
        it.jdbc.update("DELETE FROM user_permission_groups WHERE user_id = ?", userId);
    }

    // ------------------------------------------------------------------
    // 観測
    // ------------------------------------------------------------------

    Map<String, Object> pendingRow(Long userId) {
        return it.jdbc.queryForMap("SELECT LOWER(HEX(id)) AS id, token_hash, status, failure_code, "
                + "HEX(accept_attempt_id) AS accept_attempt_id, accepting_started_at, reissue_requested_at FROM "
                + PENDING_TABLE + " WHERE user_id = ?", userId);
    }

    String pendingStatus(Long userId) {
        return it.jdbc.queryForObject("SELECT status FROM " + PENDING_TABLE + " WHERE user_id = ?", String.class, userId);
    }

    long pendingRows(Long userId) {
        return it.jdbc.queryForObject("SELECT COUNT(*) FROM " + PENDING_TABLE + " WHERE user_id = ?", Long.class, userId);
    }

    /** 依頼者宛ての招待（元の共有招待は宛先なしなので含まない）。 */
    List<Map<String, Object>> tokensFor(Case c) {
        String column = "TEAM".equals(c.scope().type()) ? "team_id" : "organization_id";
        return it.jdbc.queryForList("SELECT id, token_hash, role_id, created_by, expires_at, max_uses, used_count, "
                + "target_user_id, revoked_at, created_at, scope_closure_generation FROM invite_tokens WHERE " + column
                + " = ? AND target_user_id = ? ORDER BY id", c.scope().id(), c.requester());
    }

    /** 有効（失効していない）な依頼者宛ての招待の件数。 */
    long activeTokens(Case c) {
        return tokensFor(c).stream().filter(t -> t.get("revoked_at") == null).count();
    }

    String roleName(Object roleId) {
        return it.jdbc.queryForObject("SELECT name FROM roles WHERE id = ?", String.class, roleId);
    }

    long notifications(Long userId, String type) {
        return it.jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND notification_type = ?",
                Long.class, userId, type);
    }

    List<Map<String, Object>> notificationRows(Long userId, String type) {
        return it.jdbc.queryForList("SELECT title, body, action_url FROM notifications WHERE user_id = ? "
                + "AND notification_type = ? ORDER BY id", userId, type);
    }

    long audits(String eventType, Long targetUserId) {
        return it.jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE event_type = ? AND target_user_id = ?",
                Long.class, eventType, targetUserId);
    }

    List<String> auditMetadata(String eventType, Long targetUserId) {
        return it.jdbc.queryForList("SELECT CAST(metadata AS CHAR) FROM audit_logs WHERE event_type = ? "
                + "AND target_user_id = ?", String.class, eventType, targetUserId);
    }

    /** 非同期の配送（通知・監査）を最大 10 秒待つ。 */
    static void await(LongSupplier actual, long expected, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (actual.getAsLong() >= expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(what + " が 10 秒以内に " + expected + " 件に達しない（実測 " + actual.getAsLong() + "）");
    }

    /** 「出ないこと」を確かめる前に、非同期の配送が追いつく時間を置く。 */
    static void settle() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(1500);
    }

    static String scopeEvent(Case c, String suffix) {
        return ("TEAM".equals(c.scope().type()) ? "TEAM_" : "ORGANIZATION_") + suffix;
    }

    // ------------------------------------------------------------------
    // 後始末（基底の users・invite_tokens 削除より先に走る）
    // ------------------------------------------------------------------

    void cleanup() {
        for (Long userId : List.copyOf(pendingUsers)) {
            it.jdbc.update("DELETE FROM " + PENDING_TABLE + " WHERE user_id = ?", userId);
            if (it.claimsTableExists()) {
                it.jdbc.update("DELETE FROM " + InviteAcceptanceItSupport.CLAIMS + " WHERE user_id = ?", userId);
            }
        }
        for (Long userId : List.copyOf(touchedUsers)) {
            it.jdbc.update("DELETE FROM notifications WHERE user_id = ? OR actor_id = ?", userId, userId);
            it.jdbc.update("DELETE FROM audit_logs WHERE user_id = ? OR target_user_id = ?", userId, userId);
            it.jdbc.update("DELETE FROM user_permission_groups WHERE user_id = ?", userId);
        }
        for (Long group : List.copyOf(groups)) {
            it.jdbc.update("DELETE FROM user_permission_groups WHERE group_id = ?", group);
            it.jdbc.update("DELETE FROM permission_group_permissions WHERE group_id = ?", group);
            it.jdbc.update("DELETE FROM permission_groups WHERE id = ?", group);
        }
        pendingUsers.clear();
        touchedUsers.clear();
        groups.clear();
    }

    /** 管理者・依頼者以外にも通知・監査を観測する利用者を後始末の対象に加える。 */
    void track(Long... userIds) {
        touchedUsers.addAll(List.of(userIds));
    }
}
