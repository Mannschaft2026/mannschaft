package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 PR-3b の API 契約成果物（試練 / red 先行。CMP-261115-2145）。AC-58（E15）。
 *
 * <ul>
 *   <li>EFFORT_003 の既定文言を「この投稿には操作できません。共有をやめるときは、記録の『共有を取り消す』から行ってください」へ改め、
 *       de・en・es・ko・zh の {@code messages_{lang}.properties} の {@code error.effort.003} も同じ意味へ更新する（API 契約 §6）。</li>
 *   <li>正規生成物 {@code docs/openapi.json} と FE 生成型に共有 API・チーム API が載り、一覧は cursor（data+meta）、
 *       query は snake_case（実装 PR で再生成するまで red）。</li>
 * </ul>
 */
@DisplayName("F06.4.1 PR-3b API 契約成果物（AC-58）")
class EffortShareApiContractArtifactsTest {

    private static final Path OPENAPI = Path.of("../docs/openapi.json");
    private static final Path GENERATED = Path.of("../frontend/app/types/generated/index.ts");
    private static final String ME = "/api/v1/effort-records/me/{id}/team-share";
    private static final String TEAM = "/api/v1/teams/{teamId}/effort-records";

    /** PR-1 の文言（全操作に通じない旧文言）。置き換わっていること。 */
    private static final Map<String, String> OLD_003 = Map.of(
            "en", "This post cannot be operated on",
            "de", "Dieser Beitrag kann nicht bearbeitet werden");

    private static Properties messages(String lang) throws IOException {
        Properties properties = new Properties();
        try (InputStream in = EffortShareApiContractArtifactsTest.class.getResourceAsStream("/messages_" + lang + ".properties")) {
            assertThat(in).as("messages_" + lang + ".properties").isNotNull();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    @Test
    @DisplayName("AC-58: EFFORT_003 の既定文言は取消への誘導を含む全操作共通の文言")
    void AC58_EFFORT003の既定文言() {
        assertThat(EffortErrorCode.EFFORT_003.getMessage())
                .isEqualTo("この投稿には操作できません。共有をやめるときは、記録の『共有を取り消す』から行ってください");
    }

    @Test
    @DisplayName("AC-58: de・en・es・ko・zh の error.effort.003 が新しい文言（取消への誘導を含む）へ更新され、日本語を残さない")
    void AC58_EFFORT003の5言語() throws IOException {
        Map<String, Properties> byLang = new LinkedHashMap<>();
        for (String lang : List.of("de", "en", "es", "ko", "zh")) {
            byLang.put(lang, messages(lang));
        }
        for (Map.Entry<String, Properties> e : byLang.entrySet()) {
            String value = e.getValue().getProperty("error.effort.003");
            assertThat(value).as(e.getKey() + " に error.effort.003 がある").isNotBlank();
            String old = OLD_003.get(e.getKey());
            if (old != null) {
                assertThat(value).as(e.getKey() + " は旧文言から改める").isNotEqualTo(old);
            }
            assertThat(value).as(e.getKey() + " に日本語の仮名を残さない")
                    .doesNotContainPattern("[\\p{IsHiragana}\\p{IsKatakana}]");
        }
        assertThat(byLang.get("en").getProperty("error.effort.003").toLowerCase()).as("en は共有の取消へ誘導する").contains("shar");
    }

    @Test
    @DisplayName("AC-58: openapi.json に共有 API・チーム API が載り、一覧の query は snake_case")
    void AC58_OpenAPIに共有APIとチームAPI() throws IOException {
        JsonNode paths = new ObjectMapper().readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8)).path("paths");
        assertThat(paths.path(ME + "/preview").has("post")).as("preview").isTrue();
        assertThat(paths.path(ME).has("put") && paths.path(ME).has("delete")).as("共有 PUT・取消").isTrue();
        assertThat(paths.path(ME + "/retry").has("post")).as("retry").isTrue();
        assertThat(paths.path(TEAM).has("get")).as("チーム一覧").isTrue();
        assertThat(paths.path(TEAM + "/{shareId}").has("get") && paths.path(TEAM + "/{shareId}").has("delete"))
                .as("チーム詳細・管理者非表示").isTrue();
        assertThat(paths.path(TEAM + "/definitions").has("get")).as("定義候補").isTrue();
        List<String> listParams = new ArrayList<>();
        // 殿裁定 2026-11-24: パスパラメータ（teamId）は既存規約どおり camelCase。snake_case の判定は query パラメータだけ
        paths.path(TEAM).path("get").path("parameters").forEach(p -> {
            if ("query".equals(p.path("in").asText())) {
                listParams.add(p.path("name").asText());
            }
        });
        assertThat(listParams).contains("date_from", "date_to", "definition_uuid", "cursor", "limit", "user_id")
                .noneMatch(name -> name.matches(".*[A-Z].*"));
        List<String> revokeParams = new ArrayList<>();
        paths.path(ME).path("delete").path("parameters").forEach(p -> revokeParams.add(p.path("name").asText()));
        assertThat(revokeParams).contains("version", "share_version", "Idempotency-Key");
    }

    @Test
    @DisplayName("AC-58: FE 生成型に共有 API・チーム API の path が載っている（openapi-typescript 再生成済み）")
    void AC58_FE生成型の再生成() throws IOException {
        String generated = Files.readString(GENERATED, StandardCharsets.UTF_8);
        for (String path : List.of(ME + "/preview", ME, ME + "/retry", TEAM, TEAM + "/{shareId}", TEAM + "/definitions")) {
            assertThat(generated).as(path).contains("\"" + path + "\"");
        }
    }
}
