package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.effortrecord.event.EffortShareRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-3b 共有の途中失敗・順逆配送（試練 / red 先行。CMP-261115-2145）。
 * EFFORT_005・AC-33・AC-34・AC-35（E05・E09・E11）。
 *
 * <p>データ設計 §3・§4: 表示は owner と team がともに VISIBLE で版が一致するときだけ。途中失敗は所有 fixture の行だけに効く
 * 一時 TRIGGER で注入し（モック・Spy を使わない）、各段の失敗で「旧値も新値も見えない」「同じ command の retry で
 * 同じ share・投稿1件へ収束する」ことを確かめる。順逆配送は {@link EffortShareRequestedEvent} を実 TX の
 * AFTER_COMMIT で遅れて届け、owner 行の desired 状態が正本であることを確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 共有の途中失敗と順逆配送")
class EffortShareSyncFailureIT extends EffortTeamShareItSupport {

    private List<Long> teamFeedPostIds(Long viewer, Long teamId) throws Exception {
        JsonNode body = expect(http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                .param("scopeId", String.valueOf(teamId)), viewer), 200);
        List<Long> ids = new ArrayList<>();
        body.path("data").path("posts").forEach(p -> ids.add(p.path("id").asLong()));
        body.path("data").path("pinned").forEach(p -> ids.add(p.path("id").asLong()));
        return ids;
    }

    /** 遅延イベントを実 TX の AFTER_COMMIT で届ける。 */
    private void deliverLate(EffortShareRequestedEvent event) throws InterruptedException {
        tx(() -> {
            eventPublisher.publishEvent(event);
            return null;
        });
        Thread.sleep(1500);
    }

    private EffortShareRequestedEvent eventOf(Long owner, JsonNode ownerRecord, Long teamId) {
        Map<String, Object> row = ownerRow(id(ownerRecord));
        return new EffortShareRequestedEvent(UUID.fromString(id(ownerRecord)), owner,
                UUID.fromString(shareId(ownerRecord)), teamId, ownerRecord.path("contentRevision").asLong(),
                shareVersion(ownerRecord), (Long) row.get("grantMembershipId"));
    }

    // ═══════════════════════════ 保存前の失敗（EFFORT_005） ═══════════════════════════

    @Test
    @DisplayName("EFFORT_005: 共有要求の保存前に失敗すると 503 EFFORT_005。原本・receipt・共有は rollback され、同じ key の再送で正常に共有できる")
    void EFFORT005_保存前の失敗はrollback() throws Exception {
        World w = world("E005");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        Map<String, Object> before = ownerRow(id(record));
        String trigger = failOn("effort_records", "BEFORE UPDATE",
                "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'PENDING'");
        String key = UUID.randomUUID().toString();

        expectError(putShare(w.owner(), id(record), version(record), preview.path("previewToken").asText(), key),
                503, "EFFORT_005");
        assertThat(ownerRow(id(record))).as("原本の共有列は変わらない").isEqualTo(before);
        assertThat(count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id = ?1 AND operation_key = ?2",
                w.owner(), key)).as("失敗した要求の receipt を残さない").isZero();
        assertThat(count("SELECT COUNT(*) FROM effort_team_shares WHERE team_id = ?1", w.team())).isZero();
        assertThat(ownerRecord(w.owner(), id(record)).path("values")).as("保存済み原本は残る").isNotEmpty();

        dropTrigger(trigger);
        expect(putShare(w.owner(), id(record), version(record), preview.path("previewToken").asText(), key), 202);
        awaitShareState(w.owner(), id(record), "VISIBLE");
    }

    // ═══════════════════════════════ AC-33（E09/E11） ═══════════════════════════════

    @Test
    @DisplayName("AC-33: 定義変更→廃止→訂正→TL 障害で FAILED→同じ command の retry で、同じ share と投稿1件に新しい値が入る。障害中は旧値も見えない")
    void AC33_廃止後の訂正と障害からのretry() throws Exception {
        World w = world("AC33");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "科目", "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);

        List<FieldSpec> renamed = new ArrayList<>(shareFields());
        renamed.set(1, renamed.get(1).withLabel("練習時間"));
        updateTeamTemplate(w.admin(), w.template(), "AC33練習", renamed);
        retireTeamTemplate(w.admin(), w.template());

        String trigger = failOn("timeline_effort_sources", "BEFORE UPDATE", "NEW.team_id = " + w.team());
        JsonNode corrected = expect(putRecord(w.owner(), visible, "45", "腕立て", null), 200).path("data");
        assertThat(corrected.path("share").path("state").asText()).as("廃止後でも既存許可の訂正は同期要求になる")
                .isEqualTo("PENDING");
        JsonNode failed = awaitShareState(w.owner(), id(visible), "FAILED");
        assertThat(listedShareIds(w.viewer(), w.team())).as("障害中は旧値も新値も出ない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        assertThat(teamFeedPostIds(w.viewer(), w.team())).doesNotContain(postId);
        dropTrigger(trigger);

        expect(postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), UUID.randomUUID().toString()), 202);
        JsonNode resynced = awaitShareState(w.owner(), id(visible), "VISIBLE");
        assertThat(shareId(resynced)).isEqualTo(sid);
        assertThat(effortPostId(sid)).isEqualTo(postId);
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
        JsonNode shared = sharedRecord(w.viewer(), w.team(), sid);
        assertThat(shared.path("fields").toString()).contains("\"45\"").doesNotContain("\"30\"");
    }

    // ═══════════════════════════════ AC-34（E11） ═══════════════════════════════

    @Test
    @DisplayName("AC-34(a): team の upsert の後に TL が失敗すると owner は FAILED、チーム一覧・詳細・TL のどこにも出ない。retry で投稿1件に収束")
    void AC34a_teamの後にTLが失敗() throws Exception {
        World w = world("AC34a");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        String trigger = failOn("timeline_effort_sources", "BEFORE INSERT", "NEW.team_id = " + w.team());
        JsonNode preview = preview(w.owner(), w, record, "時間");
        String sid = share(w.owner(), id(record), version(record), preview.path("previewToken").asText())
                .path("share").path("id").asText();
        JsonNode failed = awaitShareState(w.owner(), id(record), "FAILED");
        assertThat(teamShareRow(sid).get("state")).as("team 側は upsert 済み").isEqualTo("VISIBLE");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        assertThat(effortSourceCount(w.team())).isZero();
        dropTrigger(trigger);

        expect(postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), UUID.randomUUID().toString()), 202);
        awaitShareState(w.owner(), id(record), "VISIBLE");
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
    }

    @Test
    @DisplayName("AC-34(b): TL 成功の後に owner ack が失敗すると、team と TL の行はあっても一覧・詳細・TL に出ない。retry で1件に収束")
    void AC34b_ackが失敗() throws Exception {
        World w = world("AC34b");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        String trigger = failOn("effort_records", "BEFORE UPDATE",
                "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'VISIBLE'");
        JsonNode preview = preview(w.owner(), w, record, "時間");
        String sid = share(w.owner(), id(record), version(record), preview.path("previewToken").asText())
                .path("share").path("id").asText();
        JsonNode failed = awaitShareState(w.owner(), id(record), "FAILED");
        assertThat(teamShareRow(sid).get("state")).isEqualTo("VISIBLE");
        Long postId = effortPostId(sid);
        assertThat(postId).as("TL の upsert までは済んでいる").isNotNull();
        assertThat(listedShareIds(w.viewer(), w.team())).as("owner 未確定なので非表示").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        assertThat(teamFeedPostIds(w.viewer(), w.team())).doesNotContain(postId);
        expect(http(get(TIMELINE + "/posts/{id}", postId), w.viewer()), 404);
        dropTrigger(trigger);

        expect(postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), UUID.randomUUID().toString()), 202);
        awaitShareState(w.owner(), id(record), "VISIBLE");
        assertThat(effortPostId(sid)).isEqualTo(postId);
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
        assertThat(teamFeedPostIds(w.viewer(), w.team())).contains(postId);
    }

    /** team upsert と owner の状態記録をともに止め、イベントが届かず PENDING のまま残った状態を作る。 */
    private List<String> stallPipeline(World w) throws Exception {
        List<String> stall = new ArrayList<>();
        stall.add(failOn("effort_team_shares", "BEFORE INSERT", "NEW.team_id = " + w.team()));
        stall.add(failOn("effort_records", "BEFORE UPDATE", "NEW.owner_user_id = " + w.owner()
                + " AND NEW.share_state IN ('VISIBLE', 'FAILED')"));
        return stall;
    }

    @Test
    @DisplayName("AC-34(c): イベントが止まって PENDING が残っても一覧・詳細・TL に出ず、60 秒経過後の retry で VISIBLE に収束")
    void AC34c_イベント停止でPENDINGが残る() throws Exception {
        World w = world("AC34c");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        List<String> stall = stallPipeline(w);
        String sid = share(w.owner(), id(record), version(record), preview.path("previewToken").asText())
                .path("share").path("id").asText();
        Thread.sleep(1500);
        JsonNode pending = ownerRecord(w.owner(), id(record));
        assertThat(pending.path("share").path("state").asText()).isEqualTo("PENDING");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        for (String t : stall) {
            dropTrigger(t);
        }
        ageShare(id(record), 61);
        expect(postRetry(w.owner(), id(pending), version(pending), shareVersion(pending), UUID.randomUUID().toString()), 202);
        awaitShareState(w.owner(), id(record), "VISIBLE");
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-34(d): 60 秒未満の PENDING への retry は 409 で状態を変えない（FAILED は直ちに retry できる）")
    void AC34d_60秒未満のretryは409() throws Exception {
        World w = world("AC34d");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        List<String> stall = stallPipeline(w);
        share(w.owner(), id(record), version(record), preview.path("previewToken").asText());
        Thread.sleep(1000);
        JsonNode pending = ownerRecord(w.owner(), id(record));
        Map<String, Object> before = ownerRow(id(record));
        expectError(postRetry(w.owner(), id(pending), version(pending), shareVersion(pending), UUID.randomUUID().toString()),
                409, "COMMON_003");
        assertThat(ownerRow(id(record))).as("409 の retry は状態を変えない").isEqualTo(before);
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        for (String t : stall) {
            dropTrigger(t);
        }
    }

    // ═══════════════════════════════ AC-35（E05/E11） ═══════════════════════════════

    @Test
    @DisplayName("AC-35(a): v2 の後に v1 のイベントが届いても何もしない（最新値を上書きしない）")
    void AC35a_古い版のイベントはnoop() throws Exception {
        World w = world("AC35a");
        JsonNode v1 = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        EffortShareRequestedEvent stale = eventOf(w.owner(), v1, w.team());
        expect(putRecord(w.owner(), v1, "45", "腕立て", null), 200);
        JsonNode v2 = awaitShareState(w.owner(), id(v1), "VISIBLE");
        Map<String, Object> teamBefore = teamShareRow(shareId(v2));

        deliverLate(stale);
        assertThat(teamShareRow(shareId(v2))).as("v1 の遅延イベントは team 行を変えない").isEqualTo(teamBefore);
        assertThat(teamShareRow(shareId(v2)).get("sourceVersion")).isEqualTo(v2.path("contentRevision").asLong());
        assertThat(sharedRecord(w.viewer(), w.team(), shareId(v2)).path("fields").toString()).contains("\"45\"");
        assertThat(ownerShareState(w.owner(), id(v1))).isEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("AC-35(b): 取消の後に create・update のイベントが遅れて届いても復活しない")
    void AC35b_取消後の遅延イベントで復活しない() throws Exception {
        World w = world("AC35b");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        EffortShareRequestedEvent create = eventOf(w.owner(), visible, w.team());
        JsonNode revoked = revoke(w.owner(), visible);
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();

        deliverLate(create);
        deliverLate(new EffortShareRequestedEvent(create.recordId(), create.ownerUserId(), create.shareId(), create.teamId(),
                create.sourceVersion() + 1, create.shareVersion(), create.grantMembershipId()));
        assertThat(listedShareIds(w.viewer(), w.team())).as("遅延イベントで復活しない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), shareId(visible)), 404, "EFFORT_001");
        assertThat(ownerShareState(w.owner(), id(revoked))).isEqualTo("REVOKED");
        assertThat(teamShareRow(shareId(visible)).get("state")).isNotEqualTo("VISIBLE");
    }

    @Test
    @DisplayName("AC-35(c): 別チームへ移った後に旧チーム宛てのイベントが届いても旧チームでは見えない")
    void AC35c_旧チーム宛ての遅延イベントで見えない() throws Exception {
        World w = world("AC35c");
        Long team2 = team("AC35c移籍先");
        Long admin2 = user("AC35c移籍先管理者");
        teamAdmin(admin2, team2);
        member(w.owner(), team2);
        Long template2 = teamTemplate(admin2, team2, "AC35c移籍先練習", shareFields());

        JsonNode toOld = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        EffortShareRequestedEvent oldTeam = eventOf(w.owner(), toOld, w.team());
        JsonNode revoked = revoke(w.owner(), toOld);
        JsonNode toNew = shareVisible(w.owner(), team2, template2, revoked, "時間");

        deliverLate(oldTeam);
        assertThat(listedShareIds(w.viewer(), w.team())).as("旧チームでは見えない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), shareId(toOld)), 404, "EFFORT_001");
        assertThat(ownerRecord(w.owner(), id(toNew)).path("share").path("teamId").asLong()).isEqualTo(team2);
        MockHttpServletResponse newTeam = teamList(w.owner(), team2);
        assertThat(json(newTeam).path("data")).hasSize(1);
    }
}
