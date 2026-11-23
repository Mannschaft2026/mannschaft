package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.effortrecord.service.EffortShareMaintenanceService;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.membership.event.TeamEffortMembershipRevokedEvent;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.service.ContentReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-3b 共有のライフサイクル（試練 / red 先行。CMP-261115-2145）。
 * AC-36・37・47・50・51・52・66（モデレーション）・69（E05・E13・E14・E16・E25 の API 側）。
 *
 * <p>取消・非表示・脱退・再加入は「待たずに」全 read 経路で deny になる（後処理を止めても同じ）。MODERATED は終端で、
 * 本人の retry・編集でも、owner への通知が欠けても復活しない。退会は共有本文を残し、purge は tombstone を残して冪等。
 * チーム削除は即 deny で effort_team_shares を論理削除する。イベント欠落は保守コマンドで grant_membership_id ごとに補う。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 共有のライフサイクル")
class EffortShareLifecycleIT extends EffortTeamShareItSupport {

    @Autowired private EffortShareMaintenanceService maintenance;
    @Autowired private ContentReportService reports;
    @Autowired private UserService userService;

    /** team 側・timeline 側の後処理（失効の反映）を止める。 */
    private void stallTeamSideUpdates(World w) throws Exception {
        failOn("effort_team_shares", "BEFORE UPDATE", "NEW.team_id = " + w.team());
        failOn("timeline_effort_sources", "BEFORE UPDATE", "NEW.team_id = " + w.team());
        failOn("timeline_posts", "BEFORE UPDATE", "NEW.scope_type = 'TEAM' AND NEW.scope_id = " + w.team());
    }

    private List<Long> teamFeedPostIds(Long viewer, Long teamId) throws Exception {
        JsonNode body = expect(http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                .param("scopeId", String.valueOf(teamId)), viewer), 200);
        List<Long> ids = new java.util.ArrayList<>();
        body.path("data").path("posts").forEach(p -> ids.add(p.path("id").asLong()));
        return ids;
    }

    private void awaitTeamState(String shareId, String state) {
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> state.equals(teamShareRow(shareId).get("state")));
    }

    // ═══════════════════════════════ AC-36（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-36: 本人取消は 200 REVOKED。後処理の前から一覧・詳細・TL・投稿詳細で読めず、原本と集計は残る")
    void AC36_本人取消は後処理の前から全経路deny() throws Exception {
        World w = world("AC36");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        stallTeamSideUpdates(w);

        JsonNode revoked = revoke(w.owner(), visible);
        assertThat(revoked.path("share").path("state").asText()).isEqualTo("REVOKED");
        assertThat(shareVersion(revoked)).isEqualTo(shareVersion(visible) + 1);
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        assertThat(teamFeedPostIds(w.viewer(), w.team())).doesNotContain(postId);
        expect(http(get(TIMELINE + "/posts/{id}", postId), w.viewer()), 404);

        JsonNode original = ownerRecord(w.owner(), id(visible));
        assertThat(valueOf(original, keyOf(original.path("definition"), "時間")).asText()).as("原本は残る").isEqualTo("30");
    }

    @Test
    @DisplayName("AC-36: 脱退した後でも本人は取消できる（owner 照合だけで、現在所属を要求しない）")
    void AC36_脱退後も本人は取消できる() throws Exception {
        World w = world("AC36脱退");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        expect(leaveTeamApi(w.owner(), w.team()), 204);
        JsonNode current = ownerRecord(w.owner(), id(visible));
        JsonNode revoked = expect(deleteShare(w.owner(), id(current), version(current), shareVersion(current),
                UUID.randomUUID().toString()), 200).path("data");
        assertThat(revoked.path("share").path("state").asText()).isEqualTo("REVOKED");
    }

    // ═══════════════════════════════ AC-37（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-37: ADMIN・DEPUTY_ADMIN の非表示は 200 MODERATED、MEMBER は 403。本人の retry・編集で復活しない")
    void AC37_管理者非表示と復活しない() throws Exception {
        World w = world("AC37");
        Long deputy = user("AC37副管理者");
        teamDeputy(deputy, w.team());
        JsonNode byAdmin = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        JsonNode byDeputy = shareVisible(w, teamRecord(w.owner(), w, "40", "走る", null), "時間");

        String sidAdmin = shareId(byAdmin);
        expectError(moderateApi(w.viewer(), w.team(), sidAdmin, (Long) teamShareRow(sidAdmin).get("version"),
                UUID.randomUUID().toString()), 403, "COMMON_002");
        assertThat(teamShareRow(sidAdmin).get("state")).isEqualTo("VISIBLE");

        JsonNode moderated = expect(moderateApi(w.admin(), w.team(), sidAdmin, (Long) teamShareRow(sidAdmin).get("version"),
                UUID.randomUUID().toString()), 200).path("data");
        assertThat(moderated.path("state").asText()).isEqualTo("MODERATED");
        assertThat(moderated.path("id").asText()).isEqualTo(sidAdmin);
        String sidDeputy = shareId(byDeputy);
        expect(moderateApi(deputy, w.team(), sidDeputy, (Long) teamShareRow(sidDeputy).get("version"),
                UUID.randomUUID().toString()), 200);
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();

        JsonNode owner = awaitShareState(w.owner(), id(byAdmin), "MODERATED");
        expectError(postRetry(w.owner(), id(owner), version(owner), shareVersion(owner), UUID.randomUUID().toString()),
                409, "COMMON_003");
        expect(putRecord(w.owner(), owner, "50", "腕立て", null), 200);
        Thread.sleep(1500);
        assertThat(teamShareRow(sidAdmin).get("state")).as("MODERATED は終端").isEqualTo("MODERATED");
        assertThat(listedShareIds(w.viewer(), w.team())).as("編集しても復活しない").isEmpty();
    }

    // ═══════════════════════════════ AC-47（E14） ═══════════════════════════════

    @Test
    @DisplayName("AC-47: m1 の失効イベントが遅れて届いても、m2 で新しく preview した共有と TL は変わらない")
    void AC47_旧加入期間の遅延イベントは新しい共有を失効させない() throws Exception {
        World w = world("AC47");
        Long m1 = activeMembershipId(w.owner(), w.team());
        JsonNode first = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        expect(leaveTeamApi(w.owner(), w.team()), 204);
        Long m2 = rejoin(w.owner(), w.team());
        assertThat(m2).isNotEqualTo(m1);

        JsonNode current = ownerRecord(w.owner(), id(first));
        JsonNode reshared = shareVisible(w, current, "時間");
        String sid = shareId(reshared);
        assertThat(teamShareRow(sid).get("grantMembershipId")).isEqualTo(m2);
        Long postId = effortPostId(sid);

        tx(() -> {
            eventPublisher.publishEvent(new TeamEffortMembershipRevokedEvent(w.team(), w.owner(), m1));
            return null;
        });
        Thread.sleep(1500);
        assertThat(teamShareRow(sid).get("state")).as("m2 の共有は失効しない").isEqualTo("VISIBLE");
        assertThat(ownerShareState(w.owner(), id(first))).isEqualTo("VISIBLE");
        assertThat(postRow(postId).get("deleted")).isEqualTo(false);
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
    }

    // ═══════════════════════════════ AC-50（E13） ═══════════════════════════════

    /** 退会申請前に発行した既存トークンで private API を呼ぶ（http() は呼び出しごとに新しいトークンを発行するため使わない）。 */
    private MockHttpServletResponse withToken(String token, String recordId) throws Exception {
        return mvc.perform(get(ME + "/{id}", recordId).header("Authorization", "Bearer " + token)).andReturn().getResponse();
    }

    /**
     * 退会申請が private API を止める現行の仕組みに沿って検査する。UserService#requestWithdrawal は
     * AuthTokenService#setUserInvalidationTimestamp で Valkey に user_invalidated_at を書き、以後の検証で
     * それより前に発行された access token を拒否する（401）。Valkey は外部境界のため基底の Mock
     * （StringRedisTemplate）を使い、書かれた値を読み出しに返して実 Valkey の振る舞いを再現する（構成キーは増やさない）。
     * 撤回（cancelWithdrawal）は無効化時刻を消さないので、既存トークンは拒否のまま、撤回後に発行したトークン
     * （再ログイン相当）で private API が戻る。
     */
    @Test
    @DisplayName("AC-50: 退会申請で既存トークンの private API は 401 になり、共有の read は続く。撤回後の新しいトークンで戻り、再投稿はしない")
    void AC50_退会申請と撤回() throws Exception {
        World w = world("AC50撤回");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        String issuedBefore = tokens.issueAccessToken(w.owner(), List.of("MEMBER"));
        expect(withToken(issuedBefore, id(visible)), 200);
        Thread.sleep(1100); // iat（秒）を無効化時刻より前にする

        userService.requestWithdrawal(w.owner(), new RequestWithdrawalRequest(null));
        String key = "mannschaft:auth:user_invalidated_at:" + w.owner();
        org.mockito.ArgumentCaptor<String> invalidatedAt = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(redisTemplate.opsForValue()).set(org.mockito.ArgumentMatchers.eq(key),
                invalidatedAt.capture(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.when(redisTemplate.opsForValue().get(key)).thenReturn(invalidatedAt.getValue());

        expect(withToken(issuedBefore, id(visible)), 401);
        assertThat(listedShareIds(w.viewer(), w.team())).as("退会申請中も共有の read は続く").containsExactly(sid);

        userService.cancelWithdrawal(w.owner());
        assertThat(ownerShareState(w.owner(), id(visible))).as("撤回後に発行したトークンで private API が戻る")
                .isEqualTo("VISIBLE");
        Thread.sleep(1000);
        assertThat(effortSourceCount(w.team())).as("撤回で再投稿しない").isEqualTo(1);
        assertThat(effortPostId(sid)).isEqualTo(postId);
        assertThat(livePostCount(w.team())).isEqualTo(1);
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
    }

    @Test
    @DisplayName("AC-50: 30 日後の purge は本文を消して tombstone を残し（selected_field_keys・last_error_code も NULL）、共有の read は続く。purge は冪等")
    void AC50_purgeはtombstoneを残し冪等() throws Exception {
        World w = world("AC50purge");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", "AC50NOTE"), "時間");
        String sid = shareId(visible);
        Map<String, Object> before = ownerRow(id(visible));

        tx(() -> {
            eventPublisher.publishEvent(new AccountPurgedEvent(w.owner(), "hash"));
            return null;
        });
        // purge リスナーは purge-pool の非同期（殿裁定 2026-11-24）。完了を上限付きで待ってから検査する（検査内容は変えない）
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(ownerRow(id(visible)).get("purged")));
        Map<String, Object> purged = ownerRow(id(visible));
        assertThat(purged.get("purged")).isEqualTo(true);
        assertThat(String.valueOf(purged.get("values"))).isEqualTo("{}");
        assertThat(purged.get("definitionSnapshot")).isEqualTo("{}");
        for (String column : List.of("privateNote", "shareMapping", "shareDefinition", "desiredProjection",
                "selectedFieldKeys", "lastErrorCode")) {
            assertThat(purged.get(column)).as("purge で消す列: " + column).isNull();
        }
        for (String column : List.of("shareState", "shareVersion", "contentRevision", "grantMembershipId", "shareIdHex",
                "shareTeamId", "desiredHash", "syncedSourceVersion")) {
            assertThat(purged.get(column)).as("tombstone に残す列: " + column).isEqualTo(before.get(column));
        }
        assertThat(count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id = ?1", w.owner())).isZero();

        JsonNode shared = sharedRecord(w.viewer(), w.team(), sid);
        assertThat(shared.path("fields").toString()).as("共有本文は残る").contains("\"30\"");
        assertThat(shared.path("author").path("displayName").asText()).as("著者は匿名化").doesNotContain("AC50purge本人");

        tx(() -> {
            eventPublisher.publishEvent(new AccountPurgedEvent(w.owner(), "hash"));
            return null;
        });
        // 2 回目の purge も非同期。実行の機会を与えたうえで、行が 1 回目と変わらないことを上限付きで確かめる
        await().pollDelay(Duration.ofSeconds(1)).atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(ownerRow(id(visible))).as("purge は冪等").isEqualTo(purged));
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
    }

    // ═══════════════════════════════ AC-51（E13） ═══════════════════════════════

    @Test
    @DisplayName("AC-51: チーム削除で即 deny、TeamDeletedEvent の後処理で effort_team_shares.deleted_at を立てる（物理削除しない）。原本は残り retry できない")
    void AC51_チーム削除() throws Exception {
        World w = world("AC51", TeamShape.TWO_PARENT_ORGS);
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);

        // 親組織の離脱は共有の可否に使わない（チーム削除だけが対象）
        detachFromAllOrganizations(w.team());
        assertThat(listedShareIds(w.viewer(), w.team())).as("親組織が0件になっても見える").containsExactly(sid);

        expect(http(delete(TEAMS + "/{slug}", slug(w.team())), w.admin()), 204);
        assertThat(teamList(w.viewer(), w.team()).getStatus()).as("削除の直後から deny（存在しないチームと同じ 404）").isEqualTo(404);
        assertThat(teamDetail(w.viewer(), w.team(), sid).getStatus()).as("詳細も存在しないチームと同じ 404").isEqualTo(404);
        await().atMost(SYNC_WAIT).until(() -> Boolean.TRUE.equals(teamShareRow(sid).get("deleted")));
        assertThat(teamShareRow(sid)).as("物理削除しない").isNotEmpty();

        JsonNode original = ownerRecord(w.owner(), id(visible));
        assertThat(valueOf(original, keyOf(original.path("definition"), "時間")).asText()).isEqualTo("30");
        MockHttpServletResponse retry = postRetry(w.owner(), id(original), version(original), shareVersion(original),
                UUID.randomUUID().toString());
        expectError(retry, 409, "COMMON_003");
    }

    // ═══════════════════════════════ AC-52（E14） ═══════════════════════════════

    @Test
    @DisplayName("AC-52: 失効イベントが欠落しても、保守コマンドが REVOKED の disposition と未失効の共有を grant_membership_id で突き合わせて失効させる")
    void AC52_保守コマンドで欠落を補う() throws Exception {
        World w = world("AC52");
        Long stayer = anotherMember(w, "AC52残留者");
        JsonNode leaverShare = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        JsonNode stayerShare = shareVisible(stayer, w.team(), w.template(), teamRecord(stayer, w, "20", "歩く", null), "時間");
        Long m1 = activeMembershipId(w.owner(), w.team());
        // イベントを出さずに加入期間を REVOKED で終わらせる（失効イベントの欠落）
        tx(() -> em.createNativeQuery("UPDATE memberships SET left_at = UTC_TIMESTAMP(), effort_share_disposition = 'REVOKED' "
                + "WHERE id = :id").setParameter("id", m1).executeUpdate());

        int reconciled = maintenance.reconcileRevokedMemberships();
        assertThat(reconciled).isGreaterThanOrEqualTo(1);
        assertThat(teamShareRow(shareId(leaverShare)).get("state")).isEqualTo("REVOKED");
        assertThat(ownerRow(id(leaverShare)).get("shareState")).isEqualTo("REVOKED");
        assertThat(count("SELECT COUNT(*) FROM timeline_effort_sources WHERE share_uuid = UNHEX(REPLACE(?1, '-', '')) "
                + "AND is_revoked = TRUE", shareId(leaverShare))).isEqualTo(1);
        assertThat(teamShareRow(shareId(stayerShare)).get("state")).as("別の加入期間の共有には触れない").isEqualTo("VISIBLE");

        assertThat(maintenance.reconcileRevokedMemberships()).as("再実行は冪等").isZero();
    }

    // ═══════════════════════════ AC-66（E16 モデレーション） ═══════════════════════════

    @Test
    @DisplayName("AC-66: 既存の通報・モデレーションで努力由来の投稿を非表示にすると MODERATED になり、本人の retry・編集で復活しない")
    void AC66_通報による非表示はMODERATED() throws Exception {
        World w = world("AC66");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);

        Long reportId = reports.createReport(new CreateReportRequest("TIMELINE_POST", postId, "SPAM", null), w.viewer()).getId();
        reports.hideContent(reportId);
        awaitTeamState(sid, "MODERATED");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        JsonNode owner = awaitShareState(w.owner(), id(visible), "MODERATED");

        expectError(postRetry(w.owner(), id(owner), version(owner), shareVersion(owner), UUID.randomUUID().toString()),
                409, "COMMON_003");
        expect(putRecord(w.owner(), owner, "50", "腕立て", null), 200);
        Thread.sleep(1500);
        assertThat(teamShareRow(sid).get("state")).isEqualTo("MODERATED");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
    }

    @Test
    @DisplayName("AC-66: 管理者非表示の owner への通知が欠けても MODERATED は即 deny のまま、本人の retry・編集で復活しない")
    void AC66_通知欠落でもMODERATEDは復活しない() throws Exception {
        World w = world("AC66通知");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        failOn("effort_records", "BEFORE UPDATE", "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'MODERATED'");

        expect(moderateApi(w.admin(), w.team(), sid, (Long) teamShareRow(sid).get("version"), UUID.randomUUID().toString()), 200);
        assertThat(listedShareIds(w.viewer(), w.team())).as("team の commit で即 deny").isEmpty();
        Thread.sleep(1500);
        assertThat(ownerRow(id(visible)).get("shareState")).as("通知が欠けて owner 行は古いまま").isEqualTo("VISIBLE");

        JsonNode owner = ownerRecord(w.owner(), id(visible));
        MockHttpServletResponse retry = postRetry(w.owner(), id(owner), version(owner), shareVersion(owner),
                UUID.randomUUID().toString());
        expectError(retry, 409, "COMMON_003");
        expect(putRecord(w.owner(), owner, "50", "腕立て", null), 200);
        Thread.sleep(1500);
        assertThat(teamShareRow(sid).get("state")).isEqualTo("MODERATED");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
    }

    // ═══════════════════════════════ AC-69（E25 の API 側） ═══════════════════════════════

    @Test
    @DisplayName("AC-69(a): 取消・非表示・脱退・再加入の直後（待機なし・後処理を止めても）に B の一覧から消える")
    void AC69a_直後に一覧から消える() throws Exception {
        World w = world("AC69a");
        JsonNode toRevoke = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        JsonNode toModerate = shareVisible(w, teamRecord(w.owner(), w, "40", "走る", null), "時間");
        Long leaver = anotherMember(w, "AC69a脱退者");
        JsonNode toLeave = shareVisible(leaver, w.team(), w.template(), teamRecord(leaver, w, "50", "泳ぐ", null), "時間");
        assertThat(listedShareIds(w.viewer(), w.team())).hasSize(3);
        stallTeamSideUpdates(w);

        revoke(w.owner(), toRevoke);
        assertThat(listedShareIds(w.viewer(), w.team())).doesNotContain(shareId(toRevoke));
        // 非表示は team TX が先に commit するため、team 行の更新を止める TRIGGER を外してから送る
        for (String t : List.copyOf(triggers)) {
            dropTrigger(t);
        }
        expect(moderateApi(w.admin(), w.team(), shareId(toModerate), (Long) teamShareRow(shareId(toModerate)).get("version"),
                UUID.randomUUID().toString()), 200);
        assertThat(listedShareIds(w.viewer(), w.team())).doesNotContain(shareId(toModerate));
        stallTeamSideUpdates(w);
        expect(leaveTeamApi(leaver, w.team()), 204);
        assertThat(listedShareIds(w.viewer(), w.team())).doesNotContain(shareId(toLeave));
        rejoin(leaver, w.team());
        assertThat(listedShareIds(w.viewer(), w.team())).as("再加入で旧共有は戻らない").isEmpty();
    }

    @Test
    @DisplayName("AC-69(b): 取消・非表示・脱退・再加入の直後（待機なし）に詳細は 404 EFFORT_001")
    void AC69b_直後に詳細は404() throws Exception {
        World w = world("AC69b");
        JsonNode toRevoke = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        JsonNode toModerate = shareVisible(w, teamRecord(w.owner(), w, "40", "走る", null), "時間");
        Long leaver = anotherMember(w, "AC69b脱退者");
        JsonNode toLeave = shareVisible(leaver, w.team(), w.template(), teamRecord(leaver, w, "50", "泳ぐ", null), "時間");

        revoke(w.owner(), toRevoke);
        expectError(teamDetail(w.viewer(), w.team(), shareId(toRevoke)), 404, "EFFORT_001");
        expect(moderateApi(w.admin(), w.team(), shareId(toModerate), (Long) teamShareRow(shareId(toModerate)).get("version"),
                UUID.randomUUID().toString()), 200);
        expectError(teamDetail(w.viewer(), w.team(), shareId(toModerate)), 404, "EFFORT_001");
        expect(leaveTeamApi(leaver, w.team()), 204);
        expectError(teamDetail(w.viewer(), w.team(), shareId(toLeave)), 404, "EFFORT_001");
        rejoin(leaver, w.team());
        expectError(teamDetail(w.viewer(), w.team(), shareId(toLeave)), 404, "EFFORT_001");
    }

    @Test
    @DisplayName("AC-69(c): 閲覧者が脱退した直後（待機なし）から、その人の一覧・詳細・定義候補は 403 COMMON_002")
    void AC69c_脱退者は直後から403() throws Exception {
        World w = world("AC69c");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(shareId(visible));

        expect(leaveTeamApi(w.viewer(), w.team()), 204);
        expectError(teamList(w.viewer(), w.team()), 403, "COMMON_002");
        expectError(teamDetail(w.viewer(), w.team(), shareId(visible)), 403, "COMMON_002");
        expectError(teamDefinitions(w.viewer(), w.team()), 403, "COMMON_002");
        // 対照: 残っている本人には見える
        assertThat(listedShareIds(w.owner(), w.team())).containsExactly(shareId(visible));
    }
}
