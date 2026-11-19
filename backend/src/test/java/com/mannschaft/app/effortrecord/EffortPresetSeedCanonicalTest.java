package com.mannschaft.app.effortrecord;

import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.service.EffortSnapshotCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 標準プリセット seed の定義 JSON が Service の canonical JSON と 1 文字も違わないことの番人（骨格不備④）。
 *
 * <p>seed の {@code definition_hash} は migration 内で {@code SHA2(@effort_*_json, 256)} として計算される。
 * その入力文字列が {@link EffortSnapshotCodec#canonicalJson} の出力と同一なら、seed の hash は Service が
 * 計算する hash と一致する（DB を使わず一致を保証する）。seed を直したら本テストが落ちるので、
 * canonical の文字列をそのまま migration へ貼り直すこと。</p>
 */
@DisplayName("F06.4.1 プリセット seed の canonical JSON 一致（骨格不備④）")
class EffortPresetSeedCanonicalTest {

    private static final Pattern SEED = Pattern.compile("SET @(effort_\\w+_json) = '(.*?)';", Pattern.DOTALL);
    private static final Pattern HASH_USE = Pattern.compile("SHA2\\(@(effort_\\w+_json), 256\\)");

    private final EffortSnapshotCodec codec = new EffortSnapshotCodec();

    @Test
    @DisplayName("seed の定義 JSON は canonical JSON と同一で、definition_hash は同じ変数の SHA2 で計算される")
    void seedの定義JSONはcanonicalと同一() throws IOException {
        String sql = Files.readString(migration(), StandardCharsets.UTF_8);
        Matcher matcher = SEED.matcher(sql);
        List<String> variables = new ArrayList<>();
        while (matcher.find()) {
            variables.add(matcher.group(1));
            String literal = matcher.group(2);
            EffortDefinitionSnapshot snapshot = codec.parseSnapshot(literal);
            assertThat(codec.canonicalJson(snapshot)).as(matcher.group(1)).isEqualTo(literal);
            assertThat(codec.hash(snapshot)).hasSize(64).isEqualTo(codec.hash(literal));
        }
        assertThat(variables).as("プリセット 3 種").hasSize(3);

        Matcher hashes = HASH_USE.matcher(sql);
        List<String> hashed = new ArrayList<>();
        while (hashes.find()) {
            hashed.add(hashes.group(1));
        }
        assertThat(hashed).as("definition_hash はいずれも定義 JSON の変数に対する SHA2")
                .containsExactlyInAnyOrderElementsOf(variables);
    }

    private static Path migration() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("src/main/resources/db/migration"))) {
            List<Path> found = files.filter(p -> p.getFileName().toString().endsWith("__create_effort_records_tables.sql")).toList();
            assertThat(found).as("effort 表の migration がちょうど1本ある").hasSize(1);
            return found.get(0);
        }
    }
}
