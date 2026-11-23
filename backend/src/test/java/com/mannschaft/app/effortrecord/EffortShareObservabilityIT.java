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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F06.4.1 PR-3b 共有の観測・監査（試練 / red 先行。CMP-261115-2145）。AC-61（E21）。
 *
 * <p>親設計 §5: share/update/revoke/moderate の成功失敗数・pending 滞留・F00 deny 理由別件数・同期失敗件数を観測する。
 * ログ・メトリクス・監査に値・note・snapshot・token を出さず、userId・shareId を metrics label にしない。
 * 監査（共有取消・管理者非表示）は AFTER_COMMIT で記録し、監査の失敗で業務 TX を巻き戻さない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 共有の観測・監査（AC-61）")
class EffortShareObservabilityIT extends EffortTeamShareItSupport {

    private static final String NOTE = "CANARYSHARENOTE4410";
    private static final String MEMO = "CANARYSHAREMEMO4410";
    private static final String MINUTES = "987";

    @Autowired private MeterRegistry meters;

    private long audits(Long actor) {
        return count("SELECT COUNT(*) FROM audit_logs WHERE user_id = ?1 AND event_type LIKE '%EFFORT%'", actor);
    }

    @Test
    @DisplayName("AC-61: preview・共有・同期失敗・retry・取消・非表示・一覧で、値・note・token がログとメトリクス tag に出ず、userId・shareId を label にしない")
    void AC61_共有操作のログとメトリクスに値とtokenを出さない() throws Exception {
        World w = world("AC61");
        JsonNode record = teamRecord(w.owner(), w, MINUTES, MEMO, NOTE);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        Logger effort = (Logger) LoggerFactory.getLogger("com.mannschaft.app.effortrecord");
        Level previous = effort.getLevel();
        appender.start();
        root.addAppender(appender);
        effort.setLevel(Level.DEBUG);
        List<String> tokens = new ArrayList<>();
        String sid;
        try {
            JsonNode snap = snapshot(w.owner(), w.team(), w.template());
            tokens.add(snap.path("definitionToken").asText());
            String trigger = failOn("timeline_effort_sources", "BEFORE INSERT", "NEW.team_id = " + w.team());
            JsonNode preview = expect(postPreview(w.owner(), id(record), previewBody(record, w.team(), snap, "時間", "メモ")), 200)
                    .path("data");
            tokens.add(preview.path("previewToken").asText());
            share(w.owner(), id(record), version(record), preview.path("previewToken").asText());
            JsonNode failed = awaitShareState(w.owner(), id(record), "FAILED");
            dropTrigger(trigger);
            expect(postRetry(w.owner(), id(failed), version(failed), shareVersion(failed), UUID.randomUUID().toString()), 202);
            JsonNode visible = awaitShareState(w.owner(), id(record), "VISIBLE");
            sid = shareId(visible);
            expect(teamList(w.viewer(), w.team()), 200);
            expect(teamDetail(w.viewer(), w.team(), sid), 200);
            revoke(w.owner(), visible);
            JsonNode second = shareVisible(w, teamRecord(w.owner(), w, MINUTES, MEMO, NOTE), "時間");
            expect(moderateApi(w.admin(), w.team(), shareId(second), (Long) teamShareRow(shareId(second)).get("version"),
                    UUID.randomUUID().toString()), 200);
            // 検証エラー（拒否値に canary を含む）
            expectError(postPreview(w.owner(), id(record), java.util.Map.of("version", 0, "teamId", w.team(),
                    "definitionToken", NOTE, "mappings", List.of(), "selectedFieldKeys", List.of(MEMO))), 400, "COMMON_001");
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
        List<String> canaries = new ArrayList<>(List.of(NOTE, MEMO, "\"" + MINUTES + "\"", "projection_json", "selectedFieldKeys"));
        canaries.addAll(tokens);
        for (String canary : canaries) {
            assertThat(lines).as("ログに値・token を出さない: " + canary).noneMatch(line -> line.contains(canary));
        }

        List<Meter> shareMeters = meters.getMeters().stream()
                .filter(m -> m.getId().getName().toLowerCase().contains("effort")
                        && m.getId().getName().toLowerCase().contains("share")).toList();
        assertThat(shareMeters).as("共有の成否・同期失敗・pending 滞留のメトリクスがある（親設計 §5）").isNotEmpty();
        Set<String> forbiddenKeys = Set.of("userid", "user_id", "user", "owner", "owneruserid", "shareid", "share_id", "share",
                "recordid", "record_id");
        for (Meter meter : shareMeters) {
            for (Tag tag : meter.getId().getTags()) {
                assertThat(forbiddenKeys).as(meter.getId() + " の tag key").doesNotContain(tag.getKey().toLowerCase());
                assertThat(tag.getValue()).as(meter.getId() + " の tag 値に shareId を入れない").doesNotContain(sid);
                for (String canary : canaries) {
                    assertThat(tag.getValue()).doesNotContain(canary);
                }
            }
        }
    }

    @Test
    @DisplayName("AC-61: 本人取消・管理者非表示は AFTER_COMMIT で監査に残り、metadata に値・note を含めない。拒否された操作は監査しない")
    void AC61_取消と非表示の監査() throws Exception {
        World w = world("AC61監査");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, MINUTES, MEMO, NOTE), "時間", "メモ");
        long ownerBefore = audits(w.owner());
        long adminBefore = audits(w.admin());

        expectError(deleteShare(w.owner(), id(visible), version(visible) + 5, shareVersion(visible),
                UUID.randomUUID().toString()), 409, "COMMON_003");
        Thread.sleep(500);
        assertThat(audits(w.owner())).as("巻き戻った操作は監査しない（AFTER_COMMIT）").isEqualTo(ownerBefore);

        revoke(w.owner(), visible);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(audits(w.owner())).isGreaterThan(ownerBefore));
        JsonNode second = shareVisible(w, teamRecord(w.owner(), w, MINUTES, MEMO, NOTE), "時間", "メモ");
        expect(moderateApi(w.admin(), w.team(), shareId(second), (Long) teamShareRow(shareId(second)).get("version"),
                UUID.randomUUID().toString()), 200);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(audits(w.admin())).isGreaterThan(adminBefore));
        assertThat(count("SELECT COUNT(*) FROM audit_logs WHERE user_id IN (?1, ?2) AND (CAST(metadata AS CHAR) LIKE ?3 "
                + "OR CAST(metadata AS CHAR) LIKE ?4)", w.owner(), w.admin(), "%" + NOTE + "%", "%" + MEMO + "%"))
                .as("監査 metadata に値・note を含めない").isZero();
    }

    @Test
    @DisplayName("AC-61: 監査の書込が失敗しても管理者非表示は 200 で確定し巻き戻らない")
    void AC61_監査失敗で業務TXは巻き戻らない() throws Exception {
        World w = world("AC61監査失敗");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        failOn("audit_logs", "BEFORE INSERT", "NEW.user_id = " + w.admin());
        expect(moderateApi(w.admin(), w.team(), shareId(visible), (Long) teamShareRow(shareId(visible)).get("version"),
                UUID.randomUUID().toString()), 200);
        assertThat(teamShareRow(shareId(visible)).get("state")).as("監査失敗でも非表示は確定").isEqualTo("MODERATED");
        assertThat(listedShareIds(w.viewer(), w.team())).isEmpty();
    }
}
