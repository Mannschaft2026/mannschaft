package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.effortrecord.event.EffortShareRevokedEvent;
import com.mannschaft.app.effortrecord.service.EffortShareCoordinator;
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

/**
 * F06.4.1 PR-3b 検分指摘の回帰（CMP-261115-2145）。
 *
 * <ul>
 *   <li>本人取消の後処理イベントが再共有の後に遅れて届いても、新しい共有（同じ share UUID の再利用）を REVOKED にせず、
 *       タイムラインの投稿も隠さない（取消イベントの版より新しい共有には適用しない。AC-35・AC-63 の趣旨）。</li>
 *   <li>許される最大入力（15 項目 × 2000 字の valueOverride）で発行した previewToken でも共有 PUT が受理される
 *       （token は値を詰めずに束縛し、発行と受理を整合させる）。</li>
 *   <li>定義候補は先頭 1000 履歴で打ち切らない（それより古い履歴にしか可視の履歴が無い定義も候補に出す）。</li>
 * </ul>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 検分指摘の回帰")
class EffortShareReviewRegressionIT extends EffortTeamShareItSupport {

    @Autowired private EffortShareCoordinator coordinator;

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
