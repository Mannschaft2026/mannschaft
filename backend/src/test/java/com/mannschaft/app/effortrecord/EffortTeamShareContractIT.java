package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-3b 共有 API の契約（試練 / red 先行。CMP-261115-2145）。
 * AC-7・8・9・24・25・26・27・28・29・30・31・32・63（E02・E03・E04・E05・E09・E10・E15）。
 *
 * <p>API 契約 §4: preview（DB 不変）→ 共有 PUT（202 PENDING）→ team VISIBLE → TEAM タイムライン upsert → owner ack。
 * チーム一覧・詳細は F00 を通った許可 snapshot だけを返し、私的 note・未選択の値・private UUID・加入期間 ID を出さない。
 * 冪等は Idempotency-Key（同 key 同 hash は同応答・別 hash は 409 COMMON_003・処理中は 409 + Retry-After:1・欠落は 400）。
 * 共有の後処理を待つ箇所は owner の GET で VISIBLE を待つ（{@link #awaitShareState}）。</p>
 *
 * <p>AC-27 の「リンク展開」: リンク展開の BE 機能は現存しないので、投稿詳細 GET（{@code /timeline/posts/{id}}）で代替する。
 * エラーコードは一つに固定する（定義 token の差替は 400 COMMON_001・field=definitionToken、preview 後の定義変更・廃止は
 * 409 EFFORT_002、COMMON_003 は preview 失効・version 競合・key 不一致・別チームの有効共有に限る）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 共有 API の契約")
class EffortTeamShareContractIT extends EffortTeamShareItSupport {

    private long teamShares(Long teamId) {
        return count("SELECT COUNT(*) FROM effort_team_shares WHERE team_id = ?1", teamId);
    }

    private long receipts(Long actor) {
        return count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id = ?1", actor);
    }

    private String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }

    // ═══════════════════════════════ AC-7（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-7: 私的 note だけの編集は contentRevision+1・shareVersion 不変。team 一覧・詳細・TL の JSON に note が出ない")
    void AC7_note編集はcontentRevisionだけ増え共有にnoteは出ない() throws Exception {
        World w = world("AC7");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", "AC7NOTE-OLD-9311"), "科目", "時間");
        long revision = visible.path("contentRevision").asLong();
        long shareVersion = shareVersion(visible);

        JsonNode edited = expect(putRecord(w.owner(), visible, "30", "腕立て", "AC7NOTE-NEW-9311"), 200).path("data");
        assertThat(edited.path("contentRevision").asLong()).as("note の編集でも内容版は増える").isEqualTo(revision + 1);
        assertThat(shareVersion(edited)).as("共有の版は変えない").isEqualTo(shareVersion);
        JsonNode resynced = awaitShareState(w.owner(), id(edited), "VISIBLE");
        assertThat(shareVersion(resynced)).isEqualTo(shareVersion);

        String sid = shareId(resynced);
        List<String> canaries = List.of("AC7NOTE-OLD-9311", "AC7NOTE-NEW-9311", "privateNote");
        assertNoCanary("チーム一覧", body(teamList(w.viewer(), w.team())), canaries);
        assertNoCanary("チーム詳細", body(teamDetail(w.viewer(), w.team(), sid)), canaries);
        assertNoCanary("TEAM タイムライン", body(http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                .param("scopeId", String.valueOf(w.team())), w.viewer())), canaries);
        assertThat(listedShareIds(w.viewer(), w.team())).as("note 編集後も同じ共有が見える").containsExactly(sid);
    }

    // ═══════════════════════════════ AC-8（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-8: 共有中の原本を DELETE すると 200 DELETED。同じ TX で share_state=REVOKED、B の一覧から消え詳細は 404 EFFORT_001")
    void AC8_共有中の原本削除で同TXに取消() throws Exception {
        World w = world("AC8");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);

        JsonNode deleted = expect(http(delete(ME + "/{id}", id(visible)).param("version", String.valueOf(version(visible)))
                .header("Idempotency-Key", UUID.randomUUID().toString()), w.owner()), 200).path("data");
        assertThat(deleted.path("state").asText()).isEqualTo("DELETED");
        assertThat(ownerRow(id(visible)).get("shareState")).as("削除と同じ TX で REVOKED（後処理を待たない）").isEqualTo("REVOKED");

        assertThat(listedShareIds(w.viewer(), w.team())).as("削除の直後から一覧に出ない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
    }

    // ═══════════════════════════════ AC-9（E04/E15） ═══════════════════════════════

    @Test
    @DisplayName("AC-9: 共有 PUT を同じ key・同じ body で再送すると同じ status と body を返し、共有・投稿・receipt は1行のまま")
    void AC9_共有PUTの同key同bodyは同応答() throws Exception {
        World w = world("AC9a");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        String key = UUID.randomUUID().toString();
        long receiptsBefore = receipts(w.owner());

        MockHttpServletResponse first = putShare(w.owner(), id(record), version(record), preview.path("previewToken").asText(), key);
        JsonNode firstBody = expect(first, 202);
        MockHttpServletResponse second = putShare(w.owner(), id(record), version(record), preview.path("previewToken").asText(), key);
        assertThat(second.getStatus()).isEqualTo(202);
        assertThat(json(second)).as("完了後の再送は保存済み応答").isEqualTo(firstBody);

        awaitShareState(w.owner(), id(record), "VISIBLE");
        assertThat(teamShares(w.team())).isEqualTo(1);
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
        assertThat(receipts(w.owner()) - receiptsBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9: retry・本人取消・管理者非表示も同じ key・同じ body の再送は同じ status と body（行は1行のまま）")
    void AC9_retryと取消と非表示の同key再送() throws Exception {
        World w = world("AC9b");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        String trigger = failOn("timeline_effort_sources", "BEFORE INSERT", "NEW.team_id = " + w.team());
        JsonNode preview = preview(w.owner(), w, record, "時間");
        share(w.owner(), id(record), version(record), preview.path("previewToken").asText());
        JsonNode failed = awaitShareState(w.owner(), id(record), "FAILED");
        dropTrigger(trigger);

        String retryKey = UUID.randomUUID().toString();
        JsonNode r1 = expect(postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), retryKey), 202);
        MockHttpServletResponse r2 = postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), retryKey);
        assertThat(r2.getStatus()).isEqualTo(202);
        assertThat(json(r2)).isEqualTo(r1);
        JsonNode visible = awaitShareState(w.owner(), id(record), "VISIBLE");
        assertThat(effortSourceCount(w.team())).as("retry の再送で投稿は増えない").isEqualTo(1);

        // 管理者非表示（TEAM の receipt）
        Map<String, Object> row = teamShareRow(shareId(visible));
        String modKey = UUID.randomUUID().toString();
        JsonNode m1 = expect(moderateApi(w.admin(), w.team(), shareId(visible), (Long) row.get("version"), modKey), 200);
        MockHttpServletResponse m2 = moderateApi(w.admin(), w.team(), shareId(visible), (Long) row.get("version"), modKey);
        assertThat(m2.getStatus()).isEqualTo(200);
        assertThat(json(m2)).isEqualTo(m1);
        assertThat(m1.at("/data/state").asText()).isEqualTo("MODERATED");
        assertThat(count("SELECT COUNT(*) FROM effort_operation_receipts WHERE route_kind = 'TEAM' AND route_id = ?1 "
                + "AND actor_user_id = ?2", w.team(), w.admin())).as("TEAM receipt の route_id は teamId").isEqualTo(1);

        // 本人取消（別の原本で）
        JsonNode other = shareVisible(w, teamRecord(w.owner(), w, "40", "走る", null), "時間");
        String revokeKey = UUID.randomUUID().toString();
        JsonNode d1 = expect(deleteShare(w.owner(), id(other), version(other), shareVersion(other), revokeKey), 200);
        MockHttpServletResponse d2 = deleteShare(w.owner(), id(other), version(other), shareVersion(other), revokeKey);
        assertThat(d2.getStatus()).isEqualTo(200);
        assertThat(json(d2)).isEqualTo(d1);
        assertThat(d1.at("/data/share/state").asText()).isEqualTo("REVOKED");
    }

    @Test
    @DisplayName("AC-9: 同じ key で別 body は 409 COMMON_003、key 欠落は 400 COMMON_001（共有は作らない）")
    void AC9_別bodyは409_key欠落は400() throws Exception {
        World w = world("AC9c");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode p1 = preview(w.owner(), w, record, "時間");
        expectError(putShare(w.owner(), id(record), version(record), p1.path("previewToken").asText(), null), 400, "COMMON_001");
        assertThat(teamShares(w.team())).isZero();
        assertThat(ownerRow(id(record)).get("shareState")).as("key 欠落で共有状態を作らない").isNull();

        String key = UUID.randomUUID().toString();
        expect(putShare(w.owner(), id(record), version(record), p1.path("previewToken").asText(), key), 202);
        JsonNode p2 = preview(w.owner(), w, ownerRecord(w.owner(), id(record)), "時間", "距離");
        expectError(putShare(w.owner(), id(record), version(record), p2.path("previewToken").asText(), key), 409, "COMMON_003");
        expectError(postRetry(w.owner(), id(record), version(record), 99, null), 400, "COMMON_001");
        expectError(moderateApi(w.admin(), w.team(), UUID.randomUUID().toString(), 0, null), 400, "COMMON_001");
    }

    @Test
    @DisplayName("AC-9: 処理中の同 key は 409 COMMON_003 と Retry-After: 1（先行の完了を待たない）")
    void AC9_処理中の同keyは409とRetryAfter() throws Exception {
        World w = world("AC9d");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        slowOn("effort_records", "BEFORE UPDATE", "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'PENDING'", 3);
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<MockHttpServletResponse> leading = CompletableFuture.supplyAsync(() -> {
                try {
                    return putShare(w.owner(), id(record), version(record), preview.path("previewToken").asText(), key);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool);
            Thread.sleep(800);
            MockHttpServletResponse follower = putShare(w.owner(), id(record), version(record),
                    preview.path("previewToken").asText(), key);
            expectError(follower, 409, "COMMON_003");
            assertThat(follower.getHeader("Retry-After")).isEqualTo("1");
            expect(leading.get(20, TimeUnit.SECONDS), 202);
        } finally {
            pool.shutdownNow();
        }
    }

    // ═══════════════════════════════ AC-24（E02） ═══════════════════════════════

    @Test
    @DisplayName("AC-24: preview は DB を変えず、projection は allowlist の項目だけ。allowlist が空なら 400 COMMON_001")
    void AC24_previewはDB不変でallowlistだけ() throws Exception {
        World w = world("AC24");
        JsonNode record = teamRecord(w.owner(), w, "30", "AC24MEMO-UNSEL", "AC24NOTE");
        Map<String, Object> before = ownerRow(id(record));
        long postsBefore = livePostCount(w.team());

        JsonNode preview = preview(w.owner(), w, record, "科目", "時間");
        assertThat(preview.path("previewToken").asText()).isNotBlank();
        assertThat(preview.path("contentRevision").asLong()).isEqualTo(record.path("contentRevision").asLong());
        assertThat(preview.path("recordVersion").asLong()).isEqualTo(version(record));
        JsonNode projection = preview.path("projection");
        assertThat(projection.path("version").asLong()).as("予約 share の version は 0").isZero();
        List<String> labels = new ArrayList<>();
        projection.path("fields").forEach(f -> labels.add(f.path("label").asText()));
        assertThat(labels).as("allowlist の定義順だけ").containsExactly("科目", "時間");
        assertNoCanary("preview projection", projection.toString(), List.of("AC24MEMO-UNSEL", "AC24NOTE", id(record)));

        assertThat(ownerRow(id(record))).as("preview は原本を変えない").isEqualTo(before);
        assertThat(teamShares(w.team())).isZero();
        assertThat(livePostCount(w.team())).isEqualTo(postsBefore);

        JsonNode snap = snapshot(w.owner(), w.team(), w.template());
        expectError(postPreview(w.owner(), id(record), previewBody(record, w.team(), snap)), 400, "COMMON_001");
        assertThat(ownerRow(id(record))).isEqualTo(before);
    }

    // ═══════════════════════════════ AC-25（E02） ═══════════════════════════════

    @ParameterizedTest(name = "AC-25: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-25: 正しい token の共有 PUT は 202 PENDING。owner・team が VISIBLE になり TEAM TL に1件（share_with_friends=false・転送なし）")
    void AC25_共有PUTで202PENDINGからVISIBLEとTL1件(TeamShape shape) throws Exception {
        World w = world("AC25", shape);
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "科目", "時間");
        JsonNode pending = share(w.owner(), id(record), version(record), preview.path("previewToken").asText());
        assertThat(pending.path("share").path("state").asText()).isEqualTo("PENDING");
        assertThat(pending.path("share").path("id").asText()).as("preview で予約した share UUID を使う")
                .isEqualTo(preview.at("/projection/id").asText());
        assertThat(pending.path("share").path("teamId").asLong()).isEqualTo(w.team());

        JsonNode visible = awaitShareState(w.owner(), id(record), "VISIBLE");
        String sid = shareId(visible);
        assertThat(teamShareRow(sid).get("state")).isEqualTo("VISIBLE");
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
        Map<String, Object> post = postRow(effortPostId(sid));
        assertThat(post.get("scopeType")).isEqualTo("TEAM");
        assertThat(post.get("scopeId")).isEqualTo(w.team());
        assertThat(post.get("shareWithFriends")).isEqualTo(false);
        assertThat(post.get("forwardSourcePostId")).isNull();
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
        JsonNode shared = sharedRecord(w.viewer(), w.team(), sid);
        assertThat(shared.path("interactions").toString()).doesNotContain("true");
        assertThat(shared.path("teamId").asLong()).isEqualTo(w.team());
    }

    // ═══════════════════════════════ AC-26（E02/E10） ═══════════════════════════════

    @ParameterizedTest(name = "AC-26: 親組織={0}")
    @EnumSource(TeamShape.class)
    @DisplayName("AC-26: 別チームの template への差替は拒否、他人の原本の preview は 404、非所属は 403、previewToken の流用は 409")
    void AC26_差替と他人と非所属とtoken流用を拒否(TeamShape shape) throws Exception {
        World w = world("AC26", shape);
        // 別チーム（同じ親組織に加盟させる。親組織0件なら独立チーム同士）
        Long team2 = team("AC26別チーム");
        parentOrganizations(w.team()).forEach(org -> attachToOrganization(team2, org));
        Long admin2 = user("AC26別管理者");
        teamAdmin(admin2, team2);
        member(w.owner(), team2);
        Long template2 = teamTemplate(admin2, team2, "AC26別練習", shareFields());
        Long outsider = user("AC26非所属");

        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        // (1) team=1 のまま team2 の定義 token へ差し替える
        JsonNode snap2 = snapshot(w.owner(), team2, template2);
        MockHttpServletResponse swapped = postPreview(w.owner(), id(record), previewBody(record, w.team(), snap2, "時間"));
        JsonNode swapError = expectError(swapped, 400, "COMMON_001");
        assertThat(swapError.at("/error/fieldErrors/0/field").asText()).as("拒否理由は definitionToken（API 契約 §3）")
                .isEqualTo("definitionToken");
        // (2) 他人（同じチームの B）が A の原本を preview → 404（存在を区別しない）
        JsonNode snapB = snapshot(w.viewer(), w.team(), w.template());
        expectError(postPreview(w.viewer(), id(record), previewBody(record, w.team(), snapB, "時間")), 404, "EFFORT_001");
        // (3) 非所属は 403（本人の原本でも共有先に所属していなければ）
        JsonNode outsiderRecord = create(outsider, STUDY, DAY, values("subject", "数学", "duration", "30"), null);
        JsonNode snapA = snapshot(w.owner(), w.team(), w.template());
        Map<String, Object> outsiderBody = previewBody(outsiderRecord, w.team(), snapA, "時間");
        expectError(postPreview(outsider, id(outsiderRecord), outsiderBody), 403, "COMMON_002");

        // (4) previewToken の流用: 別の原本・別の利用者・別のチーム
        JsonNode other = teamRecord(w.owner(), w, "40", "走る", null);
        JsonNode p1 = preview(w.owner(), w, record, "時間");
        expectError(putShare(w.owner(), id(other), version(other), p1.path("previewToken").asText(),
                UUID.randomUUID().toString()), 409, "COMMON_003");
        JsonNode bRecord = teamRecord(w.viewer(), w, "50", "泳ぐ", null);
        expectError(putShare(w.viewer(), id(bRecord), version(bRecord), p1.path("previewToken").asText(),
                UUID.randomUUID().toString()), 409, "COMMON_003");
        JsonNode team2Record = teamRecord(w.owner(), team2, template2, DAY, "20", "歩く", null);
        JsonNode p2 = preview(w.owner(), team2, template2, team2Record, "時間");
        expectError(putShare(w.owner(), id(record), version(record), p2.path("previewToken").asText(),
                UUID.randomUUID().toString()), 409, "COMMON_003");
        assertThat(teamShares(w.team()) + teamShares(team2)).as("拒否された要求は共有を作らない").isZero();

        // 対照: 本人の正しい token なら共有できる
        share(w.owner(), id(record), version(record), p1.path("previewToken").asText());
        awaitShareState(w.owner(), id(record), "VISIBLE");
    }

    // ═══════════════════════════════ AC-27（E03） ═══════════════════════════════

    @Test
    @DisplayName("AC-27: note・未選択の値・private UUID・grantMembershipId はチーム一覧・詳細・TEAM TL・自分のフィード・全体検索・投稿詳細に出ない")
    void AC27_canaryがどのJSONにも出ない() throws Exception {
        World w = world("AC27");
        String note = "zqcanarynote" + UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9-]", "x");
        String memo = "zqcanarymemo" + UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9-]", "x");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", memo, note), "科目", "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        List<String> canaries = List.of(note, memo, id(visible), "grantMembershipId", "grant_membership_id", "privateNote");

        assertNoCanary("チーム一覧", body(teamList(w.viewer(), w.team())), canaries);
        assertNoCanary("チーム詳細", body(teamDetail(w.viewer(), w.team(), sid)), canaries);
        assertNoCanary("TEAM TL", body(http(get(TIMELINE + "/feed").param("scopeType", "TEAM")
                .param("scopeId", String.valueOf(w.team())), w.viewer())), canaries);
        assertNoCanary("本人のフィード", body(http(get(TIMELINE + "/my"), w.owner())), canaries);
        assertNoCanary("B のフィード", body(http(get(TIMELINE + "/my"), w.viewer())), canaries);
        assertNoCanary("投稿詳細", body(http(get(TIMELINE + "/posts/{id}", postId), w.viewer())), canaries);
        for (String q : List.of(note, memo)) {
            assertNoCanary("全体検索", body(http(get("/api/v1/search").param("q", q), w.viewer())), canaries);
            assertNoCanary("timeline 検索", body(http(get(TIMELINE + "/search").param("q", q), w.viewer())), canaries);
        }
        // 対照: 共有自体は見えている（漏れないことは見える状態で確かめる）
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
        assertThat(sharedRecord(w.viewer(), w.team(), sid).path("fields")).hasSize(2);
    }

    // ═══════════════════════════════ AC-28（E04） ═══════════════════════════════

    @Test
    @DisplayName("AC-28: 別チームに有効な共有があれば新たな共有は 409。別 key の並行送信は1件だけ成功し、もう1件は 409")
    void AC28_同時共有は1件だけ() throws Exception {
        World w = world("AC28");
        Long team2 = team("AC28別チーム");
        Long admin2 = user("AC28別管理者");
        teamAdmin(admin2, team2);
        member(w.owner(), team2);
        Long template2 = teamTemplate(admin2, team2, "AC28別練習", shareFields());

        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        MockHttpServletResponse second = postPreview(w.owner(), id(visible),
                previewBody(visible, team2, snapshot(w.owner(), team2, template2), "時間"));
        if (second.getStatus() == 200) {
            second = putShare(w.owner(), id(visible), version(visible), json(second).at("/data/previewToken").asText(),
                    UUID.randomUUID().toString());
        }
        expectError(second, 409, "COMMON_003");
        assertThat(teamShares(team2)).isZero();

        // 別 key の並行送信
        JsonNode record = teamRecord(w.owner(), w, "40", "走る", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<MockHttpServletResponse>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return putShare(w.owner(), id(record), version(record), preview.path("previewToken").asText(),
                                UUID.randomUUID().toString());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, pool));
            }
            List<Integer> statuses = new ArrayList<>();
            for (CompletableFuture<MockHttpServletResponse> call : calls) {
                statuses.add(call.get(30, TimeUnit.SECONDS).getStatus());
            }
            assertThat(statuses).containsExactlyInAnyOrder(202, 409);
        } finally {
            pool.shutdownNow();
        }
        awaitShareState(w.owner(), id(record), "VISIBLE");
        assertThat(teamShares(w.team())).isEqualTo(2);
        assertThat(effortSourceCount(w.team())).isEqualTo(2);
    }

    // ═══════════════════════════════ AC-29（E04） ═══════════════════════════════

    /** 時間=DURATION/MINUTE/SUM・科目=SUBJECT・メモ=MENU・距離=NUMBER/KILOMETER/SUM に設定する（PRESET→TEAM の対応用）。 */
    private void configureDuration(World w) throws Exception {
        String url = "/api/v1/teams/" + w.team() + "/effort-records/template-settings/" + w.template();
        JsonNode current = expect(http(get(url), w.admin()), 200).path("data");
        JsonNode d = current.path("definition");
        List<Map<String, Object>> fields = List.of(
                setting(keyOf(d, "科目"), "SUBJECT", "NONE", "NONE"),
                setting(keyOf(d, "時間"), "DURATION", "MINUTE", "SUM"),
                setting(keyOf(d, "メモ"), "MENU", "NONE", "NONE"),
                setting(keyOf(d, "距離"), "NUMBER", "KILOMETER", "SUM"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", current.path("version").asLong());
        body.put("fields", fields);
        body.put("durationFieldKey", keyOf(d, "時間"));
        body.put("subjectFieldKey", keyOf(d, "科目"));
        expect(http(put(url).contentType(MediaType.APPLICATION_JSON).content(write(body)), w.admin()), 200);
    }

    private static Map<String, Object> setting(String fieldKey, String semantic, String unit, String aggregation) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("fieldKey", fieldKey);
        s.put("semantic", semantic);
        s.put("unit", unit);
        s.put("aggregation", aggregation);
        return s;
    }

    private Map<String, Object> presetMapping(JsonNode presetRecord, Long teamId, JsonNode snap, String source,
                                              String override) {
        Map<String, Object> body = previewBody(presetRecord, teamId, snap, "時間");
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (JsonNode field : snap.at("/definition/fields")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetFieldKey", field.path("fieldKey").asText());
            boolean duration = field.path("label").asText().equals("時間");
            m.put("sourceFieldKey", duration ? source : null);
            m.put("valueOverride", duration ? override : null);
            mappings.add(m);
        }
        body.put("mappings", mappings);
        return body;
    }

    @Test
    @DisplayName("AC-29: PRESET→TEAM の mapping は原本1行のまま SUM が重複しない。source と valueOverride の同時指定・note の mapping 元は 400")
    void AC29_PRESETからTEAMへのmapping() throws Exception {
        World w = world("AC29");
        configureDuration(w);
        JsonNode preset = create(w.owner(), STUDY, DAY, values("subject", "数学", "duration", "30"), "AC29NOTE");
        JsonNode snap = snapshot(w.owner(), w.team(), w.template());

        expectError(postPreview(w.owner(), id(preset), presetMapping(preset, w.team(), snap, "duration", "30")),
                400, "COMMON_001");
        expectError(postPreview(w.owner(), id(preset), presetMapping(preset, w.team(), snap, "privateNote", null)),
                400, "COMMON_001");

        JsonNode preview = expect(postPreview(w.owner(), id(preset), presetMapping(preset, w.team(), snap, "duration", null)),
                200).path("data");
        assertThat(preview.at("/projection/fields/0/value").asText()).isEqualTo("30");
        share(w.owner(), id(preset), version(preset), preview.path("previewToken").asText());
        awaitShareState(w.owner(), id(preset), "VISIBLE");

        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1 AND deleted_at IS NULL", w.owner()))
                .as("共有しても原本は増えない").isEqualTo(1);
        JsonNode stats = expect(http(get(ME + "/stats").param("date_from", DAY.toString()).param("date_to", DAY.toString())
                .param("definition_uuid", STUDY.toString()).param("field_key", "duration").param("chart", "BAR"), w.owner()), 200);
        assertThat(stats.at("/data/total").asText()).as("個人の SUM は重複しない").isEqualTo("30.00");
        assertThat(teamShares(w.team())).isEqualTo(1);
    }

    // ═══════════════════════════════ AC-30（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-30: 原本の訂正は同じ share UUID・post id のまま値を更新し createdAt は不変。後から追加した field は共有しない。PENDING 中は旧値も新値も見えない")
    void AC30_原本訂正は同じ共有と投稿を更新() throws Exception {
        World w = world("AC30");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "科目", "時間");
        String sid = shareId(visible);
        Long postId = effortPostId(sid);
        String shareCreatedAt = (String) teamShareRow(sid).get("createdAt");
        String postCreatedAt = (String) postRow(postId).get("createdAt");

        // 共有後にテンプレートへ項目を足す（凍結した共有定義には入らない）
        List<FieldSpec> extended = new ArrayList<>(shareFields());
        extended.add(FieldSpec.number("extra", "追加項目", 4));
        updateTeamTemplate(w.admin(), w.template(), "AC30練習", extended);

        // timeline の更新を止めて訂正 → PENDING/FAILED の間は旧値も新値も見えない
        String trigger = failOn("timeline_effort_sources", "BEFORE UPDATE", "NEW.team_id = " + w.team());
        JsonNode corrected = expect(putRecord(w.owner(), visible, "45", "腕立て", null), 200).path("data");
        assertThat(corrected.path("share").path("state").asText()).isEqualTo("PENDING");
        assertThat(listedShareIds(w.viewer(), w.team())).as("訂正の同期中は一覧に出ない").isEmpty();
        expectError(teamDetail(w.viewer(), w.team(), sid), 404, "EFFORT_001");
        JsonNode failed = awaitShareState(w.owner(), id(visible), "FAILED");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
        dropTrigger(trigger);

        expect(postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), UUID.randomUUID().toString()), 202);
        JsonNode resynced = awaitShareState(w.owner(), id(visible), "VISIBLE");
        assertThat(shareId(resynced)).isEqualTo(sid);
        assertThat(effortPostId(sid)).as("同じ投稿を更新する").isEqualTo(postId);
        assertThat(effortSourceCount(w.team())).isEqualTo(1);
        assertThat(teamShareRow(sid).get("createdAt")).isEqualTo(shareCreatedAt);
        assertThat(postRow(postId).get("createdAt")).as("並び順の createdAt は初回のまま").isEqualTo(postCreatedAt);
        JsonNode shared = sharedRecord(w.viewer(), w.team(), sid);
        List<String> labels = new ArrayList<>();
        shared.path("fields").forEach(f -> labels.add(f.path("label").asText()));
        assertThat(labels).as("後から追加した field は共有しない").containsExactly("科目", "時間");
        assertThat(shared.at("/fields/1/value").asText()).isEqualTo("45");
    }

    // ═══════════════════════════════ AC-31（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-31: allowlist の拡張・mapping の変更は新しい preview が無ければ 409。成功すると shareVersion+1・contentRevision は不変")
    void AC31_許可の変更は新previewが必須() throws Exception {
        World w = world("AC31");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode firstPreview = preview(w.owner(), w, record, "時間");
        share(w.owner(), id(record), version(record), firstPreview.path("previewToken").asText());
        JsonNode visible = awaitShareState(w.owner(), id(record), "VISIBLE");
        long revision = visible.path("contentRevision").asLong();
        long sv = shareVersion(visible);

        expectError(putShare(w.owner(), id(visible), version(visible), firstPreview.path("previewToken").asText(),
                UUID.randomUUID().toString()), 409, "COMMON_003");

        JsonNode wider = preview(w.owner(), w, visible, "時間", "距離");
        share(w.owner(), id(visible), version(visible), wider.path("previewToken").asText());
        JsonNode after = awaitShareState(w.owner(), id(visible), "VISIBLE");
        assertThat(shareVersion(after)).isEqualTo(sv + 1);
        assertThat(after.path("contentRevision").asLong()).isEqualTo(revision);
        assertThat(sharedRecord(w.viewer(), w.team(), shareId(after)).path("fields")).hasSize(2);
    }

    // ═══════════════════════════════ AC-32（E09） ═══════════════════════════════

    @Test
    @DisplayName("AC-32: preview の後に template が変われば 409。廃止後の新規共有も取消後の再共有も 409 EFFORT_002")
    void AC32_定義変更と廃止で新規共有を拒否() throws Exception {
        World w = world("AC32");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        JsonNode preview = preview(w.owner(), w, record, "時間");
        List<FieldSpec> renamed = new ArrayList<>(shareFields());
        renamed.set(3, renamed.get(3).withLabel("走行距離"));
        updateTeamTemplate(w.admin(), w.template(), "AC32練習", renamed);
        MockHttpServletResponse changed = putShare(w.owner(), id(record), version(record),
                preview.path("previewToken").asText(), UUID.randomUUID().toString());
        expectError(changed, 409, "EFFORT_002");
        assertThat(teamShares(w.team())).isZero();

        // 廃止後の新規共有・取消後の再共有: preview は廃止前に取り、共有 PUT を廃止後に送る
        JsonNode shared = shareVisible(w, ownerRecord(w.owner(), id(record)), "時間");
        JsonNode revoked = revoke(w.owner(), shared);
        JsonNode reshare = preview(w.owner(), w, revoked, "時間");
        JsonNode fresh = teamRecord(w.owner(), w, "40", "走る", null);
        JsonNode freshPreview = preview(w.owner(), w, fresh, "時間");
        retireTeamTemplate(w.admin(), w.template());
        expectError(postSnapshot(w.owner(), w.team(), w.template()), 409, "EFFORT_002");
        expectError(putShare(w.owner(), id(fresh), version(fresh), freshPreview.path("previewToken").asText(),
                UUID.randomUUID().toString()), 409, "EFFORT_002");
        expectError(putShare(w.owner(), id(revoked), version(revoked), reshare.path("previewToken").asText(),
                UUID.randomUUID().toString()), 409, "EFFORT_002");
        assertThat(listedShareIds(w.viewer(), w.team())).as("廃止後に新しい共有は出ない").isEmpty();
    }

    // ═══════════════════════════════ AC-63（E05） ═══════════════════════════════

    @Test
    @DisplayName("AC-63: A→取消→B→取消→A の再共有は A の UUID を再利用し shareVersion+1。TL は重複しない")
    void AC63_同じチームへの再共有はUUIDを再利用() throws Exception {
        World a = world("AC63A");
        Long teamB = team("AC63B");
        Long adminB = user("AC63B管理者");
        teamAdmin(adminB, teamB);
        member(a.owner(), teamB);
        Long templateB = teamTemplate(adminB, teamB, "AC63B練習", shareFields());

        JsonNode record = teamRecord(a.owner(), a, "30", "腕立て", null);
        JsonNode toA = shareVisible(a, record, "時間");
        String shareA = shareId(toA);
        Long postA = effortPostId(shareA);
        JsonNode revokedA = revoke(a.owner(), toA);
        JsonNode toB = shareVisible(a.owner(), teamB, templateB, revokedA, "時間");
        assertThat(shareId(toB)).as("別チームへは新しい share UUID").isNotEqualTo(shareA);
        assertThat(shareVersion(toB)).isGreaterThan(shareVersion(revokedA));
        JsonNode revokedB = revoke(a.owner(), toB);
        JsonNode backToA = shareVisible(a, revokedB, "時間");

        assertThat(shareId(backToA)).as("同じチームへの再共有は A の UUID を再利用").isEqualTo(shareA);
        assertThat(shareVersion(backToA)).isEqualTo(shareVersion(revokedB) + 1);
        assertThat(effortSourceCount(a.team())).as("TL は重複しない").isEqualTo(1);
        assertThat(effortPostId(shareA)).isEqualTo(postA);
        assertThat(listedShareIds(a.viewer(), a.team())).containsExactly(shareA);
    }
}
