package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockHttpServletResponse;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-1 冪等 receipt と TX 境界（試練 / red 先行。CMP-261115-2145）。AC-9 / AC-62。
 *
 * <p>Idempotency-Key は create と各 DELETE に必須。同 key 同 hash は同 status/body で1行、別 body は409
 * {@code COMMON_003}、処理中は409 + {@code Retry-After: 1}、ユーザー間で独立（API 契約 §4.1）。
 * 「処理中」と「commit 前の失敗」は所有 scope に限定した一時 TRIGGER で実 MySQL に注入する
 * （内部 Bean・DB はモックしない）。24 時間失効は時計を差し替える {@code EffortRecordTemporalIT} で検査する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 冪等 receipt と TX 境界（AC-9/62）")
class EffortRecordIdempotencyIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    @Autowired private ApplicationEventPublisher events;

    private final List<String> triggers = new ArrayList<>();

    @AfterEach
    void 一時TRIGGERを撤去する() throws SQLException {
        for (String trigger : triggers) {
            triggerDdl("DROP TRIGGER IF EXISTS " + trigger);
        }
        triggers.clear();
    }

    // ═══════════════════════════════ AC-9 ═══════════════════════════════

    @Test
    @DisplayName("AC-9: 同 key 同 body の再送は同 status/body を返し原本・receipt は各1行")
    void AC9_同key同bodyは同応答で1行() throws Exception {
        Long owner = user("冪等");
        String key = UUID.randomUUID().toString();
        Object body = createBody(STUDY, DAY, TOKYO, values("subject", "数学", "duration", "30"), "メモ");

        MockHttpServletResponse first = postCreate(owner, body, key);
        JsonNode firstBody = expect(first, 201);
        MockHttpServletResponse second = postCreate(owner, body, key);
        JsonNode secondBody = expect(second, 201);

        assertThat(secondBody).as("完了後の再送は保存済み応答をそのまま返す").isEqualTo(firstBody);
        assertThat(rows(owner)).isEqualTo(1);
        assertThat(receipts(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9: 同 key 別 body は409 COMMON_003 で原本を増やさない")
    void AC9_同key別bodyは409() throws Exception {
        Long owner = user("別body");
        String key = UUID.randomUUID().toString();
        expect(postCreate(owner, createBody(WEIGHT, DAY, TOKYO, values("weight", "60.00"), null), key), 201);
        expectError(postCreate(owner, createBody(WEIGHT, DAY, TOKYO, values("weight", "61.00"), null), key), 409, "COMMON_003");
        assertThat(rows(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9: Idempotency-Key 欠落・UUID 以外は400で原本を作らない（付ければ201）")
    void AC9_key欠落は400() throws Exception {
        Long owner = user("key欠落");
        Object body = createBody(WEIGHT, DAY, TOKYO, values("weight", "60.00"), null);
        expectError(postCreate(owner, body, null), 400, "COMMON_001");
        expectError(postCreate(owner, body, "not-a-uuid"), 400, "COMMON_001");
        assertThat(rows(owner)).isZero();
        expect(postCreate(owner, body, UUID.randomUUID().toString()), 201);
        assertThat(rows(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9: 同じ key と body でも別ユーザーは独立に作成できる")
    void AC9_ユーザー間で独立() throws Exception {
        Long alice = user("A");
        Long bob = user("B");
        String key = UUID.randomUUID().toString();
        Object body = createBody(WEIGHT, DAY, TOKYO, values("weight", "60.00"), null);
        String aliceId = expect(postCreate(alice, body, key), 201).at("/data/id").asText();
        String bobId = expect(postCreate(bob, body, key), 201).at("/data/id").asText();
        assertThat(bobId).isNotEqualTo(aliceId);
        assertThat(rows(alice)).isEqualTo(1);
        assertThat(rows(bob)).isEqualTo(1);
        // 他人の receipt で他人の原本 UUID を返さない。
        expectError(http(get(ME + "/{id}", aliceId), bob), 404, "EFFORT_001");
    }

    @Test
    @DisplayName("AC-9: DELETE も同 key 再送は同応答、同 key で別 version は409")
    void AC9_DELETEの冪等() throws Exception {
        Long owner = user("削除冪等");
        String id = create(owner, WEIGHT, DAY, values("weight", "60.00"), null).path("id").asText();
        String key = UUID.randomUUID().toString();
        JsonNode first = expect(http(delete(ME + "/{id}", id).param("version", "0").header("Idempotency-Key", key), owner), 200);
        JsonNode again = expect(http(delete(ME + "/{id}", id).param("version", "0").header("Idempotency-Key", key), owner), 200);
        assertThat(again).isEqualTo(first);
        expectError(http(delete(ME + "/{id}", id).param("version", "1").header("Idempotency-Key", key), owner), 409, "COMMON_003");
        expectError(http(delete(ME + "/{id}", id).param("version", "0")
                .header("Idempotency-Key", UUID.randomUUID().toString()), owner), 404, "EFFORT_001");
        expectError(http(delete(ME + "/{id}", id).param("version", "0"), owner), 400, "COMMON_001");
    }

    @Test
    @DisplayName("AC-9: 同 key が処理中なら後続は待たずに409 + Retry-After: 1、先行は201で1行に収束")
    void AC9_処理中は409とRetryAfter() throws Exception {
        Long owner = user("処理中");
        String key = UUID.randomUUID().toString();
        Object body = createBody(WEIGHT, DAY, TOKYO, values("weight", "60.00"), null);
        // 先行要求の原本 INSERT を所有ユーザーに限り 6 秒止め、receipt 確保済み・未 commit の窓を作る。
        String trigger = trigger("ins_pause");
        triggerDdl("CREATE TRIGGER " + trigger + " BEFORE INSERT ON effort_records FOR EACH ROW "
                + "BEGIN IF NEW.owner_user_id = " + owner + " THEN SET @effort_pause = SLEEP(6); END IF; END");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<MockHttpServletResponse> leading = CompletableFuture.supplyAsync(() -> {
                try {
                    return postCreate(owner, body, key);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool);
            await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100))
                    .until(() -> count("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE STATE = 'User sleep'") > 0);

            long started = System.nanoTime();
            MockHttpServletResponse follower = postCreate(owner, body, key);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            expectError(follower, 409, "COMMON_003");
            assertThat(follower.getHeader("Retry-After")).isEqualTo("1");
            assertThat(elapsedMillis).as("処理中の同 key は先行完了を待たない").isLessThan(4000);

            expect(leading.get(20, TimeUnit.SECONDS), 201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(rows(owner)).isEqualTo(1);
        assertThat(receipts(owner)).isEqualTo(1);
    }

    // ═══════════════════════════════ AC-62 ═══════════════════════════════

    @Test
    @DisplayName("AC-62: 原本 INSERT が commit 前に失敗すると原本も receipt も残らず、同 key 再送で正常作成できる")
    void AC62_原本書込失敗で原本もreceiptも残らない() throws Exception {
        Long owner = user("途中失敗1");
        String key = UUID.randomUUID().toString();
        Object body = createBody(WEIGHT, DAY, TOKYO, values("weight", "60.00"), null);
        String trigger = trigger("ins_fail");
        triggerDdl("CREATE TRIGGER " + trigger + " BEFORE INSERT ON effort_records FOR EACH ROW "
                + "BEGIN IF NEW.owner_user_id = " + owner
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'effort-injected'; END IF; END");

        MockHttpServletResponse failed = postCreate(owner, body, key);
        assertThat(failed.getStatus()).as(failed.getContentAsString()).isGreaterThanOrEqualTo(500);
        assertThat(rows(owner)).isZero();
        assertThat(receipts(owner)).as("receipt 確保を別 TX で先に commit しない").isZero();

        triggerDdl("DROP TRIGGER " + trigger);
        triggers.remove(trigger);
        expect(postCreate(owner, body, key), 201);
        assertThat(rows(owner)).isEqualTo(1);
        assertThat(receipts(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-62: 応答保存（receipt 確定）が失敗すると原本も rollback される")
    void AC62_応答保存失敗で原本もrollback() throws Exception {
        Long owner = user("途中失敗2");
        String key = UUID.randomUUID().toString();
        Object body = createBody(WEIGHT, DAY, TOKYO, values("weight", "60.00"), null);
        String insert = trigger("rcpt_ins");
        String update = trigger("rcpt_upd");
        String guard = "IF NEW.actor_user_id = " + owner + " AND NEW.response_status IS NOT NULL "
                + "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'effort-receipt-injected'; END IF;";
        triggerDdl("CREATE TRIGGER " + insert + " BEFORE INSERT ON effort_operation_receipts FOR EACH ROW BEGIN " + guard + " END");
        triggerDdl("CREATE TRIGGER " + update + " BEFORE UPDATE ON effort_operation_receipts FOR EACH ROW BEGIN " + guard + " END");

        MockHttpServletResponse failed = postCreate(owner, body, key);
        assertThat(failed.getStatus()).as(failed.getContentAsString()).isGreaterThanOrEqualTo(500);
        assertThat(rows(owner)).as("応答保存の失敗で業務更新も残らない（同一 TX）").isZero();
        assertThat(receipts(owner)).isZero();

        for (String t : List.of(insert, update)) {
            triggerDdl("DROP TRIGGER " + t);
            triggers.remove(t);
        }
        JsonNode created = expect(postCreate(owner, body, key), 201);
        assertThat(rows(owner)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id = ?1 AND response_status = 201 "
                + "AND response_json IS NOT NULL", owner)).as("確定 receipt は status と body を保持: " + created).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-62: AccountPurgedEvent で当該ユーザーの receipt を消去し、他人の receipt は残す")
    void AC62_purgeで本人のreceiptを消去() throws Exception {
        Long purged = user("退会済み");
        Long other = user("他人");
        create(purged, WEIGHT, DAY, values("weight", "60.00"), "消えるべきメモ");
        create(other, WEIGHT, DAY, values("weight", "60.00"), null);
        assertThat(receipts(purged)).isEqualTo(1);
        assertThat(receipts(other)).isEqualTo(1);

        tx(() -> {
            events.publishEvent(new AccountPurgedEvent(purged, "hash"));
            return null;
        });
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(receipts(purged)).isZero());
        assertThat(receipts(other)).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────

    private long rows(Long owner) {
        return count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", owner);
    }

    private long receipts(Long actor) {
        return count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id = ?1", actor);
    }

    /** TRIGGER 名は試験が生成した固定形式だけで、利用者入力を SQL へ連結しない。 */
    private String trigger(String purpose) {
        String name = "trg_effort_" + purpose + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        triggers.add(name);
        return name;
    }

    /** binary log 有効時の TRIGGER 作成は共有 Testcontainer の root 接続を使う（既存 ScopeFeatureDisplayPolicyCommitIT と同じ）。 */
    private void triggerDdl(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
