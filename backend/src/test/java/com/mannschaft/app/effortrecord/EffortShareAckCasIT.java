package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.effortrecord.dto.EffortFieldValue;
import com.mannschaft.app.effortrecord.dto.UpdateEffortRecordRequest;
import com.mannschaft.app.effortrecord.service.EffortRecordTxService;
import com.mannschaft.app.effortrecord.service.EffortShareCommand;
import com.mannschaft.app.effortrecord.service.EffortShareTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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

    private static final ZoneId OWNER_ZONE = ZoneId.of("Asia/Tokyo");

    @Autowired private EffortShareTxService ownerTx;
    @Autowired private EffortRecordTxService recordTx;

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

    // ───────────────────────────── 訂正と非同期 ack の直列化（Codex 4 巡目 P1） ─────────────────────────────

    @Test
    @DisplayName("P1: 訂正が PENDING の原本を読んだ後・保存の前に旧 command の ack が VISIBLE を確定しても、訂正は新しい revision の PENDING を確定する")
    void 訂正の読込後に旧commandのackが確定しても訂正はPENDINGを確定する() throws Exception {
        World w = world("CAS-訂正後ack");
        Pending p = pendingCorrection(w);

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                // 訂正 TX が先に原本を読む（ロックを取らない一貫性読みなら、ここで PENDING の snapshot が固定される）
                assertThat(snapshotShareState(p.recordId())).as("訂正 TX の読込時点は PENDING（試験の前提）")
                        .isEqualTo("PENDING");
                // 読込の後・保存の前に、旧 command の ack が別 TX で VISIBLE を確定する
                Optional<Duration> acked = get(other.submit(() -> ownerTx.ack(p.inFlight())));
                assertThat(acked).as("旧 command の ack は訂正の前に確定する（試験の前提）").isPresent();
                recordTx.update(w.owner(), UUID.fromString(p.recordId()), p.correction(), OWNER_ZONE, true);
            });
        } finally {
            other.shutdownNow();
        }

        Map<String, Object> after = ownerRow(p.recordId());
        assertThat(after.get("contentRevision")).isEqualTo((Long) p.row().get("contentRevision") + 1);
        assertThat(after.get("shareState"))
                .as("訂正の新しい revision は PENDING で確定する（古い同期版の VISIBLE を残すと以後の同期要求が破棄され、retry もできない）")
                .isEqualTo("PENDING");
        assertThat(after.get("desiredHash")).isNotEqualTo(p.row().get("desiredHash"));
    }

    @Test
    @DisplayName("P1: 訂正が原本の行ロックを持つ間、旧 command の ack は待ち、訂正の commit 後に古い要求として何もしない（デッドロックしない）")
    void 訂正の行ロック中の旧commandのackは待って捨てられる() throws Exception {
        World w = world("CAS-訂正中ack");
        Pending p = pendingCorrection(w);

        ExecutorService correction = Executors.newSingleThreadExecutor();
        ExecutorService acker = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> corrected = correction.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
                recordTx.update(w.owner(), UUID.fromString(p.recordId()), p.correction(), OWNER_ZONE, true);
                locked.countDown();
                try {
                    assertThat(release.await(SYNC_WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(locked.await(SYNC_WAIT.toSeconds(), TimeUnit.SECONDS)).as("訂正 TX が commit 前で止まる").isTrue();

            Future<Optional<Duration>> acked = acker.submit(() -> ownerTx.ack(p.inFlight()));
            // ack が訂正の行ロックで待っている状態を InnoDB から観測してから訂正を commit する（時間待ちに頼らない）
            await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(50)).until(this::ackIsWaitingForRowLock);
            assertThat(acked.isDone()).as("訂正の commit 前に ack は確定しない").isFalse();
            release.countDown();

            get(corrected);
            assertThat(get(acked)).as("訂正の commit 後、旧 command の ack は古い要求として何もしない").isEmpty();
        } finally {
            release.countDown();
            correction.shutdownNow();
            acker.shutdownNow();
        }

        Map<String, Object> after = ownerRow(p.recordId());
        assertThat(after.get("contentRevision")).isEqualTo((Long) p.row().get("contentRevision") + 1);
        assertThat(after.get("shareState")).isEqualTo("PENDING");
        assertThat(after.get("syncedSourceVersion")).isEqualTo(p.row().get("syncedSourceVersion"));
    }

    /** VISIBLE の共有を訂正で PENDING にし（同期は止める）、その in-flight command と次の訂正の本文を返す。 */
    private Pending pendingCorrection(World w) throws Exception {
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        // 新しい command を PENDING のまま止める（team の更新と FAILED の記録を止める。ack の VISIBLE は止めない）
        failOn("effort_team_shares", "BEFORE UPDATE", "NEW.team_id = " + w.team());
        failOn("effort_records", "BEFORE UPDATE", "NEW.owner_user_id = " + w.owner() + " AND NEW.share_state = 'FAILED'");
        expect(putRecord(w.owner(), visible, "45", "腕立て", null), 200);
        Map<String, Object> row = ownerRow(id(visible));
        assertThat(row.get("shareState")).isEqualTo("PENDING");
        EffortShareCommand inFlight = new EffortShareCommand(UUID.fromString(id(visible)), w.owner(),
                UUID.fromString(shareId(visible)), w.team(), (Long) row.get("shareVersion"), (Long) row.get("contentRevision"),
                (Long) row.get("grantMembershipId"), UUID.randomUUID(), DAY, "{}", String.valueOf(row.get("desiredHash")));
        return new Pending(id(visible), row, inFlight, correction(ownerRecord(w.owner(), id(visible)), "60"));
    }

    private record Pending(String recordId, Map<String, Object> row, EffortShareCommand inFlight,
                           UpdateEffortRecordRequest correction) {
    }

    /** 原本の全置換本文（時間だけを差し替える。他の項目は現在値、任意未入力は明示 null）。 */
    private static UpdateEffortRecordRequest correction(JsonNode record, String minutes) {
        List<EffortFieldValue> values = new ArrayList<>();
        for (JsonNode field : record.path("definition").path("fields")) {
            String key = field.path("fieldKey").asText();
            String value = null;
            for (JsonNode v : record.path("values")) {
                if (v.path("fieldKey").asText().equals(key)) {
                    value = v.path("value").isNull() ? null : v.path("value").asText();
                }
            }
            if ("時間".equals(field.path("label").asText())) {
                value = minutes;
            }
            values.add(new EffortFieldValue(key, value));
        }
        return new UpdateEffortRecordRequest(version(record), LocalDate.parse(record.path("recordedOn").asText()), values,
                null);
    }

    /** 現在の TX の一貫性読み（ロックなし）で原本の共有状態を読む。 */
    private String snapshotShareState(String recordId) {
        Object state = em.createNativeQuery("SELECT share_state FROM effort_records WHERE id = UNHEX(REPLACE(:id, '-', ''))")
                .setParameter("id", recordId).getSingleResult();
        return state == null ? null : String.valueOf(state);
    }

    /** ack の行ロック取得（SELECT ... FOR UPDATE）が InnoDB の LOCK WAIT にあるか（root 接続で INNODB_TRX を読む）。 */
    private boolean ackIsWaitingForRowLock() throws Exception {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.INNODB_TRX "
                     + "WHERE trx_state = 'LOCK WAIT' AND trx_query LIKE '%share_updated_at%effort_records%FOR UPDATE%'");
             var rs = statement.executeQuery()) {
            return rs.next() && rs.getLong(1) > 0;
        }
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get(SYNC_WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
