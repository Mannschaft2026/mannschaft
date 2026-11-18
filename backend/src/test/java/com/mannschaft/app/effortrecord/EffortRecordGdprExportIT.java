package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 個人データエクスポート（GDPR）に effort 原本が含まれることの契約。
 * 本人の原本のみ・削除済みと purge 済みは除外・他人の原本と内部 hash / receipt は出さない。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 GDPR 個人データエクスポート（effort_records）")
class EffortRecordGdprExportIT extends EffortRecordItSupport {

    @Autowired
    private PersonalDataCollector collector;

    @Test
    @DisplayName("本人の原本だけが出力され、削除済み・purge 済み・他人・内部情報は含まれない")
    void 本人の原本だけをエクスポートする() throws Exception {
        Long owner = user("エクスポート本人");
        Long other = user("エクスポート他人");
        Instant created = Instant.parse("2026-11-01T01:00:00Z");

        UUID kept = seedRecord(owner, STUDY, LocalDate.of(2026, 11, 2), TOKYO,
                values("subject", "数学", "duration", "45"), created);
        UUID deleted = seedRecord(owner, STUDY, LocalDate.of(2026, 11, 3), TOKYO,
                values("subject", "削除済み", "duration", "10"), created);
        UUID purged = seedRecord(owner, STUDY, LocalDate.of(2026, 11, 4), TOKYO,
                values("subject", "purge済み", "duration", "20"), created);
        UUID others = seedRecord(other, STUDY, LocalDate.of(2026, 11, 2), TOKYO,
                values("subject", "他人の科目", "duration", "99"), created);
        tx(() -> {
            em.createNativeQuery("UPDATE effort_records SET private_note = :n WHERE id = :id")
                    .setParameter("n", "本人のメモ").setParameter("id", kept).executeUpdate();
            em.createNativeQuery("UPDATE effort_records SET deleted_at = NOW(6) WHERE id = :id")
                    .setParameter("id", deleted).executeUpdate();
            em.createNativeQuery("UPDATE effort_records SET purged_at = NOW(6) WHERE id = :id")
                    .setParameter("id", purged).executeUpdate();
            return null;
        });

        Map<String, String> result = collector.collect(owner, Set.of("effort_records"));

        assertThat(result).containsOnlyKeys("effort_records.json");
        String raw = result.get("effort_records.json");
        JsonNode array = mapper.readTree(raw);
        assertThat(array.isArray()).isTrue();
        assertThat(array).as("本人の有効な原本 1 件だけ").hasSize(1);
        JsonNode entry = array.get(0);
        assertThat(entry.path("id").asText()).isEqualTo(kept.toString());
        assertThat(entry.path("definitionUuid").asText()).isEqualTo(STUDY.toString());
        assertThat(entry.path("definitionRevision").asInt()).isEqualTo(1);
        assertThat(entry.path("definitionName").asText()).isNotBlank();
        assertThat(entry.path("recordedOn").toString()).contains("2026").contains("11").contains("2");
        assertThat(entry.path("recordTimezone").asText()).isEqualTo(TOKYO);
        assertThat(entry.path("values").path("subject").asText()).isEqualTo("数学");
        assertThat(entry.path("values").path("duration").asText()).isEqualTo("45");
        assertThat(entry.path("note").asText()).isEqualTo("本人のメモ");
        assertThat(entry.has("createdAt")).isTrue();
        assertThat(entry.has("updatedAt")).isTrue();

        assertThat(raw).doesNotContain("削除済み").doesNotContain("purge済み").doesNotContain("他人の科目")
                .doesNotContain(others.toString());
        for (String internal : new String[] {"hash", "Hash", "receipt", "ownerUserId", "shareId", "definitionSnapshot"}) {
            assertThat(raw).as("内部情報 " + internal + " を出さない").doesNotContain(internal);
        }
    }

    @Test
    @DisplayName("原本が無い本人は空配列")
    void 原本が無ければ空配列() throws Exception {
        Long owner = user("エクスポート空");
        Map<String, String> result = collector.collect(owner, Set.of("effort_records"));
        assertThat(mapper.readTree(result.get("effort_records.json"))).isEmpty();
    }
}
