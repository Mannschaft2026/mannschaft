package com.mannschaft.app.common.sysadminbypass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: 分類表 {@code src/test/resources/z_c_1/classification.tsv} と
 * 番人 SysAdminBypassGuardTest の基準線（{@code sysadmin_bypass/baseline.txt}）・FE 番人 G2 の基準線
 * （{@code frontend/tests/unit/guards/sysadmin-bypass-baseline.json}）を突き合わせる受け入れテスト。
 *
 * <ul>
 *   <li>A（許可型）の行の基準線キーは、除去後に基準線から<b>消えている</b>（実装前は残っているので赤）。
 *       実装役は除去後に {@code SYSADMIN_BASELINE_REGENERATE=1} で基準線を下げる。</li>
 *   <li>B（拒否型）・C（運営業務）の行の基準線キーは<b>残っている</b>（誤除去の検出。本陣では B は 0 行）。</li>
 *   <li>分類表の形式（8 列・分類値・根拠と処置の必須）を固定する。B の誤除去は権限を広げるので根拠を空にさせない。</li>
 * </ul>
 */
@DisplayName("Z-C1: 分類表と SYSTEM_ADMIN 基準線の突き合わせ")
class Zc1ClassificationBaselineContractTest {

    private static final Path CLASSIFICATION = Path.of("src/test/resources/z_c_1/classification.tsv");
    private static final Path BE_BASELINE = Path.of("src/test/resources/sysadmin_bypass/baseline.txt");
    private static final Path FE_BASELINE = Path.of("../frontend/tests/unit/guards/sysadmin-bypass-baseline.json");
    private static final Set<String> CLASSES = Set.of("A", "B", "C", "対象外");
    private static final String FE_PREFIX = "frontend:app/";
    private static final String FE_KEY_PREFIX = "FE:";

    private record Row(int lineNo, String file, String line, String method, String code, String cls,
                       String reason, String action, List<String> keys) {
        boolean backend() {
            return file.startsWith("com/");
        }

        boolean frontend() {
            return file.startsWith(FE_PREFIX);
        }
    }

    private static List<Row> rows() throws IOException {
        List<String> lines = Files.readAllLines(CLASSIFICATION, StandardCharsets.UTF_8);
        List<Row> rows = new ArrayList<>();
        boolean headerSeen = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank() || l.startsWith("#")) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true; // 1 行目（非コメント）は見出し
                continue;
            }
            String[] c = l.split("\t", -1);
            assertThat(c).as("classification.tsv:%d は 8 列", i + 1).hasSize(8);
            List<String> keys = new ArrayList<>();
            for (String k : c[7].split(" ; ")) {
                if (!k.isBlank()) {
                    keys.add(k.trim());
                }
            }
            rows.add(new Row(i + 1, c[0], c[1], c[2], c[3], c[4], c[5], c[6], keys));
        }
        return rows;
    }

    private static Set<String> backendBaseline() throws IOException {
        Set<String> keys = new HashSet<>();
        for (String l : Files.readAllLines(BE_BASELINE, StandardCharsets.UTF_8)) {
            if (l.isBlank() || l.startsWith("#")) {
                continue;
            }
            String[] c = l.split("\t");
            keys.add(c[0] + "\t" + c[1]);
        }
        return keys;
    }

    private static boolean frontendBaselineHas(String file, String key) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(FE_BASELINE, StandardCharsets.UTF_8));
        JsonNode counts = root.get(file.substring(FE_PREFIX.length()));
        return counts != null && counts.has(key.substring(FE_KEY_PREFIX.length()));
    }

    @Test
    @DisplayName("分類表の形式: 8 列・分類は A/B/C/対象外・根拠と処置は必須・A/B/C の BE 行は基準線キーを持つ")
    void classificationIsWellFormed() throws IOException {
        List<Row> rows = rows();
        assertThat(rows).as("分類表が空でない").isNotEmpty();
        for (Row r : rows) {
            String at = "classification.tsv:" + r.lineNo();
            assertThat(CLASSES).as(at + " 分類値").contains(r.cls());
            assertThat(r.reason()).as(at + " 根拠").isNotBlank();
            assertThat(r.action()).as(at + " 処置").isNotBlank();
            if (r.backend() && (r.cls().equals("A") || r.cls().equals("B"))) {
                assertThat(r.keys()).as(at + " 基準線キー").isNotEmpty();
                assertThat(Files.exists(Path.of("src/main/java").resolve(r.file())))
                        .as(at + " 本番ファイルが実在する: " + r.file()).isTrue();
            }
            for (String k : r.keys()) {
                assertThat(k).as(at + " キー形式").matches(r.frontend() ? "FE:.+#.+" : ".+ @ .+");
            }
        }
    }

    @Test
    @DisplayName("A（許可型）の行の基準線キーは BE 基準線から消えている（除去して基準線を下げた）")
    void permitTypeKeysAreRemovedFromBackendBaseline() throws IOException {
        Set<String> baseline = backendBaseline();
        List<String> remaining = new ArrayList<>();
        for (Row r : rows()) {
            if (!r.backend() || !r.cls().equals("A")) {
                continue;
            }
            for (String k : r.keys()) {
                if (baseline.contains(r.file() + "\t" + k)) {
                    remaining.add(r.file() + "  " + k + "  (" + r.method() + ":" + r.line() + ")");
                }
            }
        }
        assertThat(remaining).as("基準線に残っている許可型の直書き（Z-C1 で除去する）").isEmpty();
    }

    @Test
    @DisplayName("A（許可型）の FE 行のキーは FE 基準線から消えている")
    void permitTypeKeysAreRemovedFromFrontendBaseline() throws IOException {
        List<String> remaining = new ArrayList<>();
        for (Row r : rows()) {
            if (!r.frontend() || !r.cls().equals("A")) {
                continue;
            }
            for (String k : r.keys()) {
                if (frontendBaselineHas(r.file(), k)) {
                    remaining.add(r.file() + "  " + k);
                }
            }
        }
        assertThat(remaining).as("FE 基準線に残っている許可型の直書き（Z-C1 で除去する）").isEmpty();
    }

    @Test
    @DisplayName("B（拒否型）・C（運営業務）の行の基準線キーは BE 基準線に残っている（誤除去の検出）")
    void denyAndOperationKeysStayInBackendBaseline() throws IOException {
        Set<String> baseline = backendBaseline();
        List<String> missing = new ArrayList<>();
        for (Row r : rows()) {
            if (!r.backend() || !(r.cls().equals("B") || r.cls().equals("C"))) {
                continue;
            }
            for (String k : r.keys()) {
                if (!baseline.contains(r.file() + "\t" + k)) {
                    missing.add(r.file() + "  " + k + "  (" + r.cls() + ")");
                }
            }
        }
        assertThat(missing).as("拒否型・運営業務の判定が基準線から消えている（権限の拡大か、移設の申告漏れ）").isEmpty();
    }
}
