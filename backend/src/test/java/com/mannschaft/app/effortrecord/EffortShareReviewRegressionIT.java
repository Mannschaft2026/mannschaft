package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.effortrecord.event.EffortShareRevokedEvent;
import com.mannschaft.app.effortrecord.service.EffortShareCoordinator;
import com.mannschaft.app.effortrecord.service.EffortShareMaintenanceService;
import com.mannschaft.app.membership.event.TeamEffortMembershipRevokedEvent;
import com.mannschaft.app.moderation.dto.CreateReportRequest;
import com.mannschaft.app.moderation.service.ContentReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-3b 検分指摘の回帰（CMP-261115-2145）。
 *
 * <ul>
 *   <li>本人取消の後処理イベントが再共有の後に遅れて届いても、新しい共有（同じ share UUID の再利用）を REVOKED にせず、
 *       タイムラインの投稿も隠さない（取消イベントの版より新しい共有には適用しない。AC-35・AC-63 の趣旨）。</li>
 *   <li>許される最大入力（15 項目 × 2000 字の valueOverride）で発行した previewToken でも共有 PUT が受理される
 *       （token は値を詰めずに束縛し、発行と受理を整合させる）。</li>
 *   <li>定義候補は先頭 1000 履歴で打ち切らない（それより古い履歴にしか可視の履歴が無い定義も候補に出す）。</li>
 *   <li>通報で努力由来の投稿を非表示にした後、effort への反映（AFTER_COMMIT）が欠けても、F00 は投稿の非表示状態を読んで
 *       一覧・詳細・タイムラインから隠し、本人の編集でも復活させない（データ設計 §7.2）。</li>
 *   <li>加入期間の終了で team 行を REVOKED にした後、owner・timeline の後処理が失敗しても、イベントの再送と保守コマンドで
 *       失効済みの行の後処理を冪等に再実行して収束させる（データ設計 §8）。</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 検分指摘の回帰")
class EffortShareReviewRegressionIT extends EffortTeamShareItSupport {

    @Autowired private EffortShareCoordinator coordinator;
    @Autowired private ContentReportService reports;
    @Autowired private EffortShareMaintenanceService maintenance;

    private List<Long> teamFeedPostIds(Long viewer, Long teamId) throws Exception {
        JsonNode body = expect(http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                .param("scopeId", String.valueOf(teamId)), viewer), 200);
        List<Long> ids = new ArrayList<>();
        body.path("data").path("posts").forEach(p -> ids.add(p.path("id").asLong()));
        return ids;
    }

    private void awaitTeamState(String shareId, String state) {
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> state.equals(teamShareRow(shareId).get("state")));
    }

    private long revokedTimelineSources(String shareId) {
        return count("SELECT COUNT(*) FROM timeline_effort_sources WHERE share_uuid = UNHEX(REPLACE(?1, '-', '')) "
                + "AND is_revoked = TRUE", shareId);
    }

    @Test
    @DisplayName("通報で投稿を非表示にした後、effort への反映が欠けても一覧・詳細・タイムラインから見えず、本人の編集でも復活しない")
    void 通報非表示の反映が欠けても共有は見えない() throws Exception {
        World w = world("RVRPT");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        // AFTER_COMMIT の反映（team 行の MODERATED 化）を失敗させる＝通知の欠落
        failOn("effort_team_shares", "BEFORE UPDATE", "NEW.team_id = " + w.team() + " AND NEW.state = 'MODERATED'");

        Long reportId = reports.createReport(new CreateReportRequest("TIMELINE_POST", postId, "SPAM", null), w.viewer())
                .getId();
        reports.hideContent(reportId);
        Thread.sleep(1500);
        assertThat(teamShareRow(sid).get("state")).as("反映が欠けて team 行は VISIBLE のまま").isEqualTo("VISIBLE");
        assertThat(ownerShareState(w.owner(), id(visible))).as("owner 行も VISIBLE のまま").isEqualTo("VISIBLE");

        assertThat(listedShareIds(w.viewer(), w.team())).as("チーム一覧に出さない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        assertThat(teamFeedPostIds(w.viewer(), w.team())).as("タイムラインに出さない").doesNotContain(postId);
        expect(http(get(TIMELINE + "/posts/{id}", postId), w.viewer()), 404);

        // 本人の編集（新しい版の同期）でも復活しない
        expect(putRecord(w.owner(), ownerRecord(w.owner(), id(visible)), "50", "腕立て", null), 200);
        awaitShareState(w.owner(), id(visible), "VISIBLE");
        assertThat(listedShareIds(w.viewer(), w.team())).as("編集後も一覧に出さない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        assertThat(teamFeedPostIds(w.viewer(), w.team())).doesNotContain(postId);
    }

    @Test
    @DisplayName("加入期間の終了で team 行を REVOKED にした後 owner 更新が失敗しても、イベントの再送で owner・timeline の後処理を再実行する")
    void 加入期間終了の後処理が途中で失敗してもイベント再送で収束する() throws Exception {
        World w = world("RVMEMEV");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        Long m1 = activeMembershipId(w.owner(), w.team());
        String blocker = failOn("effort_records", "BEFORE UPDATE",
                "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'REVOKED'");

        expect(leaveTeamApi(w.owner(), w.team()), 204);
        awaitTeamState(sid, "REVOKED");
        Thread.sleep(1000);
        assertThat(ownerRow(id(visible)).get("shareState")).as("owner 更新が失敗して旧状態のまま").isEqualTo("VISIBLE");
        assertThat(revokedTimelineSources(sid)).as("timeline の後処理まで届いていない").isZero();
        dropTrigger(blocker);

        // イベントの再送（AFTER_COMMIT の再配送と同じ入口）
        tx(() -> {
            eventPublisher.publishEvent(new TeamEffortMembershipRevokedEvent(w.team(), w.owner(), m1));
            return null;
        });
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> "REVOKED".equals(ownerRow(id(visible)).get("shareState")));
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100)).until(() -> revokedTimelineSources(sid) == 1);
        assertThat(postRow(postId).get("deleted")).as("対応投稿も論理削除する").isEqualTo(true);
        assertThat(coordinator.revokeForMembership(w.team(), w.owner(), m1)).as("再実行は冪等").isZero();
    }

    @Test
    @DisplayName("加入期間の終了で team・owner を REVOKED にした後 timeline 更新が失敗しても、保守コマンドが失効済みの行の後処理を再実行して収束させる")
    void 加入期間終了の後処理が途中で失敗しても保守コマンドで収束する() throws Exception {
        World w = world("RVMEMMT");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        String blocker = failOn("timeline_effort_sources", "BEFORE UPDATE", "NEW.team_id = " + w.team());

        expect(leaveTeamApi(w.owner(), w.team()), 204);
        awaitTeamState(sid, "REVOKED");
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> "REVOKED".equals(ownerRow(id(visible)).get("shareState")));
        Thread.sleep(1000);
        assertThat(revokedTimelineSources(sid)).as("timeline 更新が失敗して未失効のまま").isZero();
        assertThat(postRow(postId).get("deleted")).isEqualTo(false);
        dropTrigger(blocker);

        assertThat(maintenance.reconcileRevokedMemberships()).as("失効済みの行の後処理を補う").isGreaterThanOrEqualTo(1);
        assertThat(revokedTimelineSources(sid)).isEqualTo(1);
        assertThat(postRow(postId).get("deleted")).isEqualTo(true);
        assertThat(teamShareRow(sid).get("state")).isEqualTo("REVOKED");
        assertThat(maintenance.reconcileRevokedMemberships()).as("再実行は冪等").isZero();
    }

    @Test
    @DisplayName("取消イベントが再共有の後に遅れて届いても、同じ share UUID の新しい共有は VISIBLE のまま・投稿も隠さない")
    void 遅れて届いた取消イベントは新しい共有に適用しない() throws Exception {
        World w = world("RVLATE");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);

        JsonNode revoked = revoke(w.owner(), visible);
        long revokedShareVersion = shareVersion(revoked);
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> "REVOKED".equals(teamShareRow(sid).get("state")));

        JsonNode reshared = shareVisible(w, ownerRecord(w.owner(), id(visible)), "時間");
        assertThat(shareId(reshared)).as("同じチームへの再共有は share UUID を再利用する").isEqualTo(sid);
        assertThat(shareVersion(reshared)).isGreaterThan(revokedShareVersion);
        Long postId = effortPostId(sid);
        assertThat(postRow(postId).get("deleted")).isEqualTo(false);

        // 取消の後処理イベントの遅延配送（再共有より前の版）
        coordinator.revoked(new EffortShareRevokedEvent(UUID.fromString(id(visible)), w.owner(), UUID.fromString(sid),
                w.team(), revokedShareVersion, true));

        assertThat(teamShareRow(sid).get("state")).as("新しい共有の team 行は VISIBLE のまま").isEqualTo("VISIBLE");
        assertThat(ownerShareState(w.owner(), id(visible))).isEqualTo("VISIBLE");
        assertThat(listedShareIds(w.viewer(), w.team())).as("チームから見え続ける").containsExactly(sid);
        assertThat(postRow(postId).get("deleted")).as("タイムラインの投稿も隠さない").isEqualTo(false);
    }

    @Test
    @DisplayName("15 項目 × 2000 字の valueOverride（許される最大入力）で preview した token でも共有 PUT が 202 で通り VISIBLE になる")
    void 最大入力のpreviewTokenでも共有できる() throws Exception {
        Long team = team("RVBIG");
        Long admin = user("RVBIG管理者");
        teamAdmin(admin, team);
        Long owner = user("RVBIG本人");
        member(owner, team);
        List<FieldSpec> fields = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            fields.add(FieldSpec.text("t" + i, "テキスト" + i, i));
        }
        Long template = teamTemplate(admin, team, "RVBIG練習", fields);
        JsonNode snap = snapshot(owner, team, template);
        Map<String, String> byLabel = new LinkedHashMap<>();
        for (int i = 0; i < 15; i++) {
            byLabel.put("テキスト" + i, "x");
        }
        JsonNode record = expect(postCreate(owner, teamCreateBody(snap, DAY, byLabel, null), UUID.randomUUID().toString()),
                201).path("data");

        Map<String, Object> body = previewBody(record, team, snapshot(owner, team, template));
        List<Map<String, Object>> mappings = new ArrayList<>();
        List<String> selected = new ArrayList<>();
        for (JsonNode field : snap.at("/definition/fields")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetFieldKey", field.path("fieldKey").asText());
            m.put("sourceFieldKey", null);
            m.put("valueOverride", "y".repeat(2000));
            mappings.add(m);
            selected.add(field.path("fieldKey").asText());
        }
        body.put("mappings", mappings);
        body.put("selectedFieldKeys", selected);
        JsonNode preview = expect(postPreview(owner, id(record), body), 200).path("data");

        share(owner, id(record), version(record), preview.path("previewToken").asText());
        JsonNode visible = awaitShareState(owner, id(record), "VISIBLE");
        assertThat(sharedRecord(owner, team, shareId(visible)).path("fields")).hasSize(15);
    }

    @Test
    @DisplayName("定義候補は先頭 1000 履歴で打ち切らない: 新しい 1000 行が F00 で拒否されても、その奥の可視履歴の定義を返す")
    void 定義候補は先頭1000行で打ち切らない() throws Exception {
        World w = world("RVDEF");
        JsonNode old = teamRecord(w.owner(), w.team(), w.template(), DAY.minusDays(30), "30", "腕立て", null);
        JsonNode visible = shareVisible(w, old, "時間");
        String sid = shareId(visible);
        String definitionHex = tx(() -> String.valueOf(em.createNativeQuery("SELECT LOWER(HEX(definition_id)) FROM effort_team_shares "
                + "WHERE id = UNHEX(REPLACE(:id, '-', ''))").setParameter("id", sid).getSingleResult()));
        String definitionUuid = definitionHex.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5");

        // 可視の履歴より新しい 1000 行（原本の無い＝F00 で拒否される VISIBLE 行）を同じ定義で積む
        tx(() -> em.createNativeQuery("INSERT INTO effort_team_shares (id, team_id, owner_user_id, source_record_id, "
                        + "source_version, grant_membership_id, share_version, definition_id, recorded_on, projection_json, "
                        + "projection_hash, state, version, created_at, updated_at) "
                        + "WITH RECURSIVE seq(n) AS (SELECT 0 UNION ALL SELECT n + 1 FROM seq WHERE n < 999) "
                        + "SELECT UUID_TO_BIN(UUID()), :team, :owner, UUID_TO_BIN(UUID()), 1, 1, 1, UNHEX(:definition), :day, "
                        + "JSON_OBJECT('fields', JSON_ARRAY()), REPEAT('0', 64), 'VISIBLE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) "
                        + "FROM seq")
                .setParameter("team", w.team()).setParameter("owner", w.viewer()).setParameter("definition", definitionHex)
                .setParameter("day", DAY).executeUpdate());
        assertThat(count("SELECT COUNT(*) FROM effort_team_shares WHERE team_id = ?1", w.team())).isEqualTo(1001);

        JsonNode data = expect(teamDefinitions(w.viewer(), w.team()), 200).path("data");
        List<String> uuids = new ArrayList<>();
        data.forEach(d -> uuids.add(d.path("definitionUuid").asText()));
        assertThat(uuids).as("先頭 1000 行の外にしか可視履歴が無い定義も候補に出す").containsExactly(definitionUuid);
    }
}
