package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.effortrecord.service.EffortShareCommand;
import com.mannschaft.app.effortrecord.service.EffortShareTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 PR-3b 後処理の条件付き UPDATE（CMP-261115-2145。殿裁定 2026-11-24 で追加。green の確認）。
 *
 * <p>ack・失敗記録は本人の @Version を進めない代わりに、desired command（shareId・team・shareVersion・contentRevision・
 * 加入期間 ID・hash）と一致する PENDING 行だけを更新する（データ設計 §8.2）。原本の訂正で新しい command が PENDING に
 * なった後に、古い command の ack・失敗記録が遅れて届いても、新しい command を上書きしないことを確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 後処理の条件付き UPDATE は新しい command を古い ack で上書きしない")
class EffortShareAckCasIT extends EffortTeamShareItSupport {

    @Autowired private EffortShareTxService ownerTx;

    @Test
    @DisplayName("訂正で PENDING になった新しい command に、古い command の ack・失敗記録が届いても行は変わらない")
    void 古いcommandのackは新しいcommandを上書きしない() throws Exception {
        World w = world("CAS");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        Map<String, Object> v1 = ownerRow(id(visible));
        EffortShareCommand old = new EffortShareCommand(UUID.fromString(id(visible)), w.owner(),
                UUID.fromString(shareId(visible)), w.team(), (Long) v1.get("shareVersion"), (Long) v1.get("contentRevision"),
                (Long) v1.get("grantMembershipId"), UUID.randomUUID(), DAY, "{}", String.valueOf(v1.get("desiredHash")));

        // 新しい command を PENDING のまま止める（team の更新と FAILED の記録を止める）
        failOn("effort_team_shares", "BEFORE UPDATE", "NEW.team_id = " + w.team());
        failOn("effort_records", "BEFORE UPDATE", "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'FAILED'");
        expect(putRecord(w.owner(), visible, "45", "腕立て", null), 200);
        Thread.sleep(1500);
        Map<String, Object> pending = ownerRow(id(visible));
        assertThat(pending.get("shareState")).isEqualTo("PENDING");
        assertThat(pending.get("contentRevision")).isEqualTo((Long) v1.get("contentRevision") + 1);
        assertThat(pending.get("desiredHash")).isNotEqualTo(v1.get("desiredHash"));

        assertThat(ownerTx.ack(old)).as("古い command の ack は何もしない").isEmpty();
        assertThat(ownerTx.recordFailed(old)).as("古い command の失敗記録も何もしない").isFalse();
        assertThat(ownerRow(id(visible))).as("新しい command の PENDING 行は不変").isEqualTo(pending);
    }
}
