package com.mannschaft.app.effortrecord;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-1 の観測・監査（試練 / red 先行。CMP-261115-2145）。AC-61。
 *
 * <p>親設計 §5: ログは requestId・操作・owner ID・version・結果だけとし、体重・科目・メニュー・note・snapshot・token を
 * 記録しない。メトリクス label に userId を使わない。監査（削除）は AFTER_COMMIT で記録し、監査失敗で業務 TX を巻き戻さない。</p>
 *
 * <p>canary 値で作成・更新・一覧・集計・削除・検証エラーを一通り実行し、アプリ全体のログ（effortrecord は DEBUG まで）・
 * effort 系メトリクスの tag・監査 metadata に canary が出ないことを確認する。「漏れない」ことは処理が成功して初めて
 * 意味を持つため、各操作の成功も同時に要求する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 ログ・メトリクス・監査（AC-61）")
class EffortRecordObservabilityIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);
    private static final String SUBJECT = "CANARYSUBJ7731";
    private static final String MENU = "CANARYMENU7731";
    private static final String NOTE = "CANARYNOTE7731";
    private static final String WEIGHT_VALUE = "987.65";

    @Autowired private MeterRegistry meters;

    private final List<String> triggers = new ArrayList<>();

    @AfterEach
    void 一時TRIGGERを撤去する() throws SQLException {
        for (String trigger : triggers) {
            triggerDdl("DROP TRIGGER IF EXISTS " + trigger);
        }
        triggers.clear();
    }

    @Test
    @DisplayName("AC-61: 業務値・note・snapshot がログとメトリクス tag に出ず、userId を label にしない")
    void AC61_ログとメトリクスに業務値を出さない() throws Exception {
        Long owner = user("観測");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger effort = (Logger) LoggerFactory.getLogger("com.mannschaft.app.effortrecord");
        Level previous = effort.getLevel();
        appender.start();
        root.addAppender(appender);
        effort.setLevel(Level.DEBUG);
        try {
            JsonNode study = create(owner, STUDY, DAY, values("subject", SUBJECT, "duration", "30"), NOTE);
            JsonNode exercise = create(owner, EXERCISE, DAY, values("duration", "20", "intensity", "6", "menu", MENU), NOTE);
            create(owner, WEIGHT, DAY, values("weight", WEIGHT_VALUE), null);

            Map<String, Object> update = new LinkedHashMap<>();
            update.put("version", 0);
            update.put("recordedOn", DAY.toString());
            update.put("values", valueList(values("subject", SUBJECT, "duration", "35")));
            update.put("privateNote", NOTE + "-2");
            expect(http(put(ME + "/{id}", study.path("id").asText()).contentType(MediaType.APPLICATION_JSON)
                    .content(write(update)), owner), 200);
            expect(http(get(ME), owner), 200);
            expect(http(get(ME + "/stats").param("date_from", "2026-11-01").param("date_to", "2026-11-01")
                    .param("definition_uuid", STUDY.toString()).param("field_key", "duration").param("chart", "PIE"), owner), 200);
            expect(http(delete(ME + "/{id}", exercise.path("id").asText()).param("version", "0")
                    .header("Idempotency-Key", UUID.randomUUID().toString()), owner), 200);
            // 検証エラー（拒否値に canary を含む）も値をログへ出さない。
            expectError(postCreate(owner, createBody(STUDY, DAY, TOKYO, values("subject", SUBJECT + "x".repeat(100),
                    "duration", "30"), NOTE), UUID.randomUUID().toString()), 400, "COMMON_001");
        } finally {
            root.detachAppender(appender);
            effort.setLevel(previous);
        }

        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            lines.add(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                lines.add(String.valueOf(t.getMessage()));
            }
            lines.add(String.valueOf(event.getMDCPropertyMap()));
        }
        for (String canary : List.of(SUBJECT, MENU, NOTE, WEIGHT_VALUE, "\"definitionUuid\"")) {
            assertThat(lines).as("ログに業務値・snapshot を出さない: " + canary).noneMatch(line -> line.contains(canary));
        }

        List<Meter> effortMeters = meters.getMeters().stream()
                .filter(m -> m.getId().getName().toLowerCase().contains("effort")).toList();
        assertThat(effortMeters).as("save/update/delete の成否メトリクスがある（親設計 §5 観測項目）").isNotEmpty();
        Set<String> forbiddenKeys = Set.of("userid", "user_id", "user", "owner", "owneruserid", "shareid", "share_id");
        for (Meter meter : effortMeters) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(forbiddenKeys).as(meter.getId() + " の tag key").doesNotContain(tag.getKey().toLowerCase());
                assertThat(tag.getValue()).as(meter.getId() + " の tag value")
                        .isNotEqualTo(String.valueOf(owner)).doesNotContain(SUBJECT).doesNotContain(MENU).doesNotContain(WEIGHT_VALUE);
            }
        }
    }

    @Test
    @DisplayName("AC-61: 削除は AFTER_COMMIT で監査され metadata に業務値を含まない")
    void AC61_削除は監査され業務値を含まない() throws Exception {
        Long owner = user("監査");
        String id = create(owner, STUDY, DAY, values("subject", SUBJECT, "duration", "30"), NOTE).path("id").asText();
        expect(http(delete(ME + "/{id}", id).param("version", "0").header("Idempotency-Key", UUID.randomUUID().toString()), owner), 200);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE user_id = ?1 AND event_type LIKE '%EFFORT%'", owner))
                        .isGreaterThanOrEqualTo(1));
        long leaked = count("SELECT COUNT(*) FROM audit_logs WHERE user_id = ?1 AND (CAST(metadata AS CHAR) LIKE ?2 "
                + "OR CAST(metadata AS CHAR) LIKE ?3)", owner, "%" + SUBJECT + "%", "%" + NOTE + "%");
        assertThat(leaked).as("監査 metadata に科目・note を含めない").isZero();
    }

    @Test
    @DisplayName("AC-61: 監査の書込が失敗しても削除は200で確定し巻き戻らない")
    void AC61_監査失敗で業務TXは巻き戻らない() throws Exception {
        Long owner = user("監査失敗");
        String id = create(owner, WEIGHT, DAY, values("weight", "60.00"), null).path("id").asText();
        String trigger = "trg_effort_audit_fail_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        triggers.add(trigger);
        triggerDdl("CREATE TRIGGER " + trigger + " BEFORE INSERT ON audit_logs FOR EACH ROW BEGIN IF NEW.user_id = " + owner
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'effort-audit-injected'; END IF; END");

        expect(http(delete(ME + "/{id}", id).param("version", "0").header("Idempotency-Key", UUID.randomUUID().toString()), owner), 200);
        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE id = UNHEX(REPLACE(?1,'-','')) AND deleted_at IS NOT NULL", id))
                .as("監査失敗でも削除は確定").isEqualTo(1);
        expectError(http(get(ME + "/{id}", id), owner), 404, "EFFORT_001");
    }

    /** TRIGGER 名は試験が生成した固定形式だけ。binary log 有効時の作成は root 接続を使う。 */
    private void triggerDdl(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
