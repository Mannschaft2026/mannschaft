package com.mannschaft.app.common.architecture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.architecture.TaskListLedger.Kind;
import com.mannschaft.app.common.architecture.TaskListLedger.Ledger;
import com.mannschaft.app.common.architecture.TaskListLedger.Row;
import com.mannschaft.app.common.architecture.TaskListLedger.Violation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 番人: {@code docs/task-list.md}（戦役横断の課題正本）に登録された CMP-ID が重複しないこと。
 *
 * <h2>背景（2026-08-14 CMP-028 消失事故）</h2>
 * <p>{@code docs/task-list.md} は複数の並行セッションが日常的に書き換える共有台帳である。
 * 旧採番方式（{@code origin/main} 上の最大 CMP 番号 +1）では、並行セッションが同時に
 * {@code origin/main} を fetch すると<b>双方が同じ最大番号を読み、同じ番号を採ってしまう</b>。
 * 挿入位置がわずかに異なると git は競合を検知できず両方を自動マージしてしまうため、
 * <b>重複したまま静かに main へ入る</b>（CMP-030 の重複が実在した）。</p>
 *
 * <h2>採番方式の移行（2026-08-19）</h2>
 * <p>衝突が例外ではなく常態化した（1行の追加に対し採番を3度やり直した実績。CMP-050 → 102 → 110)
 * ため、新規行の ID は<b>日時形式 {@code CMP-YYMMDD-HHMM}（JST）</b>へ移行した。
 * 生成は {@code date '+%y%m%d-%H%M'}。既存の連番形式 {@code CMP-NNN} の行は一切変更しないため、
 * 台帳には<b>両形式が共存</b>する。本番人は両形式を等しく走査・重複判定する。</p>
 *
 * <h2>重複が見つかったら</h2>
 * <ol>
 *   <li>どちらが後から {@code origin/main} へ merge されたか（コミット日時・PR番号）を確認する。</li>
 *   <li><b>後から入った側</b>が採番し直す — 新形式なら {@code date '+%y%m%d-%H%M'} を採り直す
 *       （同一分に別セッションと衝突した場合のみ起こりうる）。旧形式の行同士の重複なら
 *       後から入った側を新形式へ振り直す。</li>
 *   <li>行の内容（戦役名・状態・証拠）はそのまま、ID のみ振り直して commit・PR を作成する。</li>
 * </ol>
 * <p><b>本番人の検出を緩めて通す（重複IDを許容リストに追加する等）ことは禁止</b>する。
 * CMP-ID は他の記憶ファイルからの参照キーであり、重複を許すと索引が壊れる。</p>
 *
 * <h2>台帳の両配置（2026-11-25 CMP-261124-1222 段A）</h2>
 * <p>台帳は旧配置（{@code docs/task-list.md} 1ファイル）と新配置（索引 {@code docs/task-list.md}＋
 * {@code docs/task-list/YYYY-MM.md}・{@code docs/task-list/legacy.md}）のどちらでも読めるよう、読み取りと判定を
 * {@link TaskListLedger} に集約した。重複はファイル跨ぎも数え、新配置のときだけ配置違反（月違い・legacy 違い・
 * 不正なファイル名・索引の CMP 行）も検出する。Node 側（{@code scripts/task-list-structure-check.mjs}）と同じ判定であることを
 * 共有 fixture {@code scripts/fixtures/task-list-ledger-cases.json} で固定する。</p>
 *
 * <h2>変更申告（2026-11-27 CMP-261124-1222 ④）</h2>
 * <p>{@code docs/task-list/changes/*.md} の申告を base 連鎖で適用した実効台帳を検査する。申告の形式・名前・サイズ・ID 一致・
 * fork・孤児・重複・changes/ の不正なものも違反（共有 fixture の「申告:」case で Node と同じ実効台帳・同じ判定）。</p>
 *
 * <h2>本テストは ArchUnit ではない</h2>
 * <p>Markdown ドキュメントの走査であり、ソースコードのバイトコード解析ではないため、
 * {@link PagingTotalCountSizeGuardTest} と同じ<b>ソース（ドキュメント）走査型</b>で書いた。
 * ArchUnit 凍結ストア（{@code src/test/resources/archunit_store}）は一切使わない。</p>
 *
 * <h2>自己検証（走査経路の健全性）【必読】</h2>
 * <p>「重複が見つからない」ことと「そもそも CMP-ID を1件も抽出できていない」ことは別事象である。
 * ファイルパスの解決ミス・正規表現の破損・table形式の変更等で抽出が0件になった場合、
 * 「重複なし」という誤った緑を返してはならない。よって本テストは重複判定とは独立に、
 * <b>厳密パターンの抽出件数が緩いパターンの計数と一致するか</b>を検証する。
 * <b>件数のしきい値は持たない</b>（行のアーカイブで件数が減っても誤検知しないため）。</p>
 *
 * <h2>状態列の件数集計（合否条件ではない）</h2>
 * <p>ID が連番でなくなった以上、「番号を見れば規模が分かる」という性質は失われる
 * （そもそも欠番・焼き捨てにより番号と件数は既に一致していなかった）。代わりに本番人は
 * 状態列の件数を<b>標準出力に出す</b>。しきい値を置くと行の増減で壊れるため、
 * 上記の自己検証と同じ思想で<b>合否条件にはしない</b>。</p>
 */
@DisplayName("番人: docs/task-list.md のCMP-IDが重複していないこと")
class TaskListCmpIdDuplicateGuardTest {

    /** 表の行から ID・状態列（第3列）を取り出す（状態別件数の表示用）。 */
    private static final Pattern CMP_ROW_WITH_STATUS = Pattern.compile(
            "(?m)^\\|\\s*(" + TaskListLedger.CMP_ID_FORM + ")\\s*\\|([^|]*)\\|([^|]*)\\|");

    /** {@code docs/task-list.md} の「状態の語彙」節に定義された値。 */
    private static final List<String> STATUS_VOCABULARY =
            List.of("未着手", "設計中", "実装中", "実機検証待ち", "完了", "凍結");

    private static final String HEADER = "| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |\n|---|---|---|---|---|---|---|";

    private static List<Violation> realViolations(Set<Kind> kinds) {
        Ledger ledger = TaskListLedger.readRepository(false);
        return TaskListLedger.check(ledger).stream().filter(v -> kinds.contains(v.kind())).toList();
    }

    private static String describe(String headline, String guidance, List<Violation> violations) {
        StringBuilder sb = new StringBuilder(headline).append("\n").append(guidance).append("\n違反一覧:\n");
        for (Violation v : violations) {
            sb.append("  ✗✗ ").append(v.message()).append("\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("CMP-IDに重複が無く（ファイル跨ぎを含む）、かつ走査自体が機能していること")
    void cmpIdは重複しない() {
        Ledger ledger = TaskListLedger.readRepository(false);
        printStatusBreakdown(TaskListLedger.concatenatedContent(ledger));
        List<Violation> violations = realViolations(EnumSet.of(Kind.MISSING, Kind.EMPTY, Kind.SCAN, Kind.DUPLICATE, Kind.CHANGE));
        assertThat(violations).as(describe(
                "台帳（" + ledger.layout() + " 配置）に重複した CMP-ID、または走査経路の破損が見つかりました。",
                "原因: 同一の ID が二重に登録されている（旧・連番形式では並行セッションが同じ最大番号を採り、git が競合検知できずに"
                        + "両方が自動マージされた。2026-08-14 CMP-028 消失事故と同根）。または、緩い計数と厳密抽出の件数が一致せず"
                        + "「重複なし」の判定自体が信用できない（ファイルパスの解決ミス・表形式の変更）。\n"
                        + "対処: 後から origin/main へ merge された側が採番し直すこと（新形式 CMP-YYMMDD-HHMM（JST・date '+%y%m%d-%H%M'）を"
                        + "採り直し、行の内容はそのまま ID のみ振り直す）。新規行は必ず表の末尾に追加すること。"
                        + "本番人の検出を緩めて通す（重複IDをそのまま許容する等）ことは禁止。",
                violations)).isEmpty();
    }

    @Test
    @DisplayName("CMP-IDを持つ表の行がすべて7列であること（列ズレ検出）")
    void cmpIdの行は列数が7である() {
        assertThat(TaskListLedger.readRepository(true).rows()).isNotEmpty();
        List<Violation> violations = realViolations(EnumSet.of(Kind.COLUMNS));
        assertThat(violations).as(describe(
                "台帳に列数が7でない行が見つかりました（期待列数: ID / 戦役 / 状態 / 依存 / 完了条件 / 証拠(PR/テスト) / 台帳 の7列）。",
                "原因: セル内に書いた `|` をエスケープ（`\\|`）せずに書いた、指示文の「注記」をそのまま列として書き足した等。\n"
                        + "対処: 内容を消さずに正しい7列へ戻す。余った列の文章は意味の合う既存列（多くは「証拠(PR/テスト)」）へ畳み込み、"
                        + "セル内で本物の `|` を使う場合は `\\|` へエスケープすること（コードスパンの中でも必要）。",
                violations)).isEmpty();
    }

    @Test
    @DisplayName("CMP-IDを持つ行の状態セルが P1〜P5 で始まる、または完了系の語で始まること（優先度必須）")
    void cmp行の状態セルは優先度か完了系で始まる() {
        List<Violation> violations = realViolations(EnumSet.of(Kind.PRIORITY));
        assertThat(violations).as(describe(
                "台帳に、優先度の無い未完の行が見つかりました。",
                "規約: 未完の行は状態セルの先頭に P1〜P5 を必ず付ける（例: `P1 未着手`、`P3 保留（理由…）`）。完了系の行は付けなくてよい。\n"
                        + "基準（利用者への被害の大きさ）: P1 認可漏れ・情報漏えい（存在オラクル含む）・データ破損/消失・課金/金額の誤り / "
                        + "P2 主要機能が動かない・画面から到達できない（回避策なし） / P3 機能の不備・誤表示・未実装（回避策あり） / "
                        + "P4 技術負債・番人テスト・性能・開発基盤・CI・運用インフラ / P5 文言や見た目の軽微な崩れ・将来構想・調査メモ・方針の検討。\n"
                        + "対処: 該当行の状態セルの先頭に優先度を書き足すこと。検出を緩めて通すことは禁止。",
                violations)).isEmpty();
    }

    @Test
    @DisplayName("新配置では各行が自分の月のファイル（旧形式は legacy.md）にあり、索引に CMP 行が無いこと")
    void 新配置の配置違反が無い() {
        List<Violation> violations = realViolations(EnumSet.of(Kind.PLACEMENT));
        assertThat(violations).as(describe(
                "台帳の配置違反が見つかりました。",
                "規約: ID CMP-YYMMDD-HHMM の行は docs/task-list/20YY-MM.md、旧形式 ID は docs/task-list/legacy.md に置く。"
                        + "索引 docs/task-list.md には行を置かない。docs/task-list/ には YYYY-MM.md と legacy.md 以外を置かない。",
                violations)).isEmpty();
    }

    @TestFactory
    @DisplayName("共有 fixture（scripts/fixtures/task-list-ledger-cases.json）で Node の構造検査と同じ判定を返す")
    Stream<DynamicTest> 共有fixtureでNodeと同じ判定() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(
                TaskListLedger.resolveRepoRoot().resolve("scripts/fixtures/task-list-ledger-cases.json"), StandardCharsets.UTF_8));
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.get("cases")) {
            String name = c.get("name").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> {
                Map<String, String> files = new LinkedHashMap<>();
                c.get("files").fields().forEachRemaining(e -> files.put(e.getKey(), e.getValue().asText()));
                Ledger ledger = TaskListLedger.read(TaskListLedger.inMemory(files), false);
                List<String> messages = TaskListLedger.check(ledger).stream().map(Violation::message).toList();
                boolean expectOk = c.get("expectOk").asBoolean();
                assertThat(messages.isEmpty()).as(name + " → " + messages).isEqualTo(expectOk);
                if (!expectOk && c.hasNonNull("reason")) {
                    assertThat(String.join("\n", messages)).as(name).contains(c.get("reason").asText());
                }
                if (c.hasNonNull("expectRows")) {
                    List<List<String>> expected = new ArrayList<>();
                    c.get("expectRows").forEach(r -> expected.add(List.of(r.get(0).asText(), r.get(1).asText())));
                    assertThat(ledger.rows().stream().map(r -> List.of(r.id(), r.file())).toList()).as(name).isEqualTo(expected);
                }
                // 申告（docs/task-list/changes/）を含む case（CMP-261124-1222 ④）: 理由はすべて含む・実効行・実効の月別ファイル本文
                if (c.hasNonNull("reasons")) {
                    c.get("reasons").forEach(r -> assertThat(String.join("\n", messages)).as(name).contains(r.asText()));
                }
                if (c.hasNonNull("expectLines")) {
                    Map<String, String> rowMap = TaskListLedger.rowMap(ledger);
                    c.get("expectLines").fields().forEachRemaining(e -> assertThat(rowMap.get(e.getKey())).as(name + " " + e.getKey())
                            .isEqualTo(e.getValue().asText()));
                }
                if (c.hasNonNull("expectFiles")) {
                    c.get("expectFiles").fields().forEachRemaining(e -> assertThat(ledger.files().stream()
                            .filter(f -> f.path().equals(e.getKey())).map(TaskListLedger.LedgerFile::content).findFirst().orElse(null))
                            .as(name + " " + e.getKey()).isEqualTo(e.getValue().asText()));
                }
            }));
        }
        assertThat(tests).hasSizeGreaterThanOrEqualTo(40);
        return tests.stream();
    }

    @Test
    @DisplayName("申告0件の実台帳は、実効台帳と生の本文が完全一致する（CMP-261124-1222 ④ AC-1）")
    void 申告0件なら実効台帳と生の本文が一致する() {
        Ledger effective = TaskListLedger.readRepository(true);
        Ledger raw = TaskListLedger.read(TaskListLedger.fileSystem(TaskListLedger.resolveRepoRoot()), true, true);
        assertThat(effective.changes().entries()).isEmpty();
        assertThat(effective.changes().problems()).isEmpty();
        assertThat(effective.files()).isEqualTo(raw.files());
        assertThat(effective.rows()).isEqualTo(raw.rows());
        assertThat(TaskListLedger.concatenatedContent(effective)).isEqualTo(TaskListLedger.concatenatedContent(raw));
    }

    @Test
    @DisplayName("申告の base は対象行（CR 除去・UTF-8）の sha256、上限は 16KiB（CMP-261124-1222 ④）")
    void 申告のbaseとサイズ上限() {
        String line = "| CMP-261124-1222 | 戦役 | P2 実装中 | — | 条件 | 証拠 | ledger |";
        assertThat(TaskListLedger.changeRowHash(line)).isEqualTo(TaskListLedger.changeRowHash(line + "\r")).startsWith("sha256:").hasSize(7 + 64);
        assertThat(TaskListLedger.MAX_CHANGE_BYTES).isEqualTo(16384);
    }

    @Test
    @DisplayName("取得失敗と0件・不在を区別する（失敗は空にしない）")
    void 取得失敗と0件を区別する() {
        TaskListLedger.Source failing = new TaskListLedger.Source() {
            @Override
            public List<String> listDir(String dir) throws IOException {
                throw new IOException("disk error");
            }

            @Override
            public String readFile(String path) {
                return "";
            }
        };
        assertThatThrownBy(() -> TaskListLedger.read(failing, true)).hasMessageContaining("取得に失敗").hasMessageContaining("disk error");
        assertThatThrownBy(() -> TaskListLedger.read(TaskListLedger.inMemory(Map.of()), true)).hasMessageContaining("存在しない");
        assertThatThrownBy(() -> TaskListLedger.read(TaskListLedger.inMemory(Map.of("docs/task-list.md", "# 台帳\n")), true))
                .hasMessageContaining("1件も読めなかった");
        assertThat(TaskListLedger.expectedFile("CMP-261130-2359")).isEqualTo("docs/task-list/2026-11.md");
        assertThat(TaskListLedger.expectedFile("CMP-261201-0000")).isEqualTo("docs/task-list/2026-12.md");
        assertThat(TaskListLedger.expectedFile("CMP-003")).isEqualTo("docs/task-list/legacy.md");
    }

    /** 旧配置の検体（実台帳から独立。月境界・旧形式・証拠リンク・エスケープを含む）。 */
    static final String SAMPLE_SINGLE = "# 台帳\n\n前置き\n\n" + HEADER + "\n"
            + "| CMP-003 | 旧 | 完了 | — | 条件 | 証拠 | l |\n"
            + "| CMP-261130-2359 | 月末 | 完了 | — | `a \\| b` | [証拠](evidence/CMP-261130-2359.json) | l |\n"
            + "| CMP-261201-0000 | 月初 | P2 実装中 | — | 条件 | [証拠](./evidence/CMP-261201-0000.json) | l |\n"
            + "| CMP-260101-0101 | 年初 | P5 未着手 | — | 条件 | 証拠 | l |\n";

    @Test
    @DisplayName("独立した旧配置の検体と、そこから生成した新配置が同じ行集合・同じ債務注釈の登録判定を返す")
    void 検体の両配置で同じ行集合と登録判定() {
        Ledger split = assertSameRows(SAMPLE_SINGLE, splitForTest(SAMPLE_SINGLE));
        assertThat(split.rows().stream().map(r -> List.of(r.id(), r.file())).toList()).containsExactly(
                List.of("CMP-260101-0101", "docs/task-list/2026-01.md"),
                List.of("CMP-261130-2359", "docs/task-list/2026-11.md"),
                List.of("CMP-261201-0000", "docs/task-list/2026-12.md"),
                List.of("CMP-003", "docs/task-list/legacy.md"));
    }

    @Test
    @DisplayName("実リポジトリの台帳（配置は問わない）をもう一方の配置に写しても同じ行集合・同じ債務注釈の登録判定を返す")
    void 実台帳の両配置で同じ行集合と登録判定() {
        Ledger real = TaskListLedger.readRepository(true);
        assertThat(real.layout()).isIn("single", "split");
        assertThat(TaskListLedger.check(real)).isEmpty();
        Map<String, String> files = new LinkedHashMap<>();
        real.files().forEach(f -> files.put(f.path(), f.content()));
        String singleText = real.split() ? joinForTest(files) : files.get(TaskListLedger.INDEX_PATH);
        Map<String, String> splitFiles = real.split() ? files : splitForTest(singleText);
        assertSameRows(singleText, splitFiles);
    }

    /** 旧配置の本文と新配置の files が同じ行集合・同じ検査結果・同じ債務注釈の登録判定を返すことを確かめる。 */
    private static Ledger assertSameRows(String singleText, Map<String, String> splitFiles) {
        Ledger single = TaskListLedger.read(TaskListLedger.inMemory(Map.of(TaskListLedger.INDEX_PATH, singleText)), true);
        Ledger split = TaskListLedger.read(TaskListLedger.inMemory(splitFiles), true);
        assertThat(single.layout()).isEqualTo("single");
        assertThat(split.layout()).isEqualTo("split");
        assertThat(TaskListLedger.check(single)).isEmpty();
        assertThat(TaskListLedger.check(split)).isEmpty();
        Map<String, String> singleLines = single.rows().stream().collect(Collectors.toMap(Row::id, r -> normalizeEvidence(r.line())));
        assertThat(split.rows()).hasSameSizeAs(single.rows());
        for (Row r : split.rows()) {
            assertThat(normalizeEvidence(r.line())).as(r.id()).isEqualTo(singleLines.get(r.id()));
            assertThat(r.file()).as(r.id()).isEqualTo(TaskListLedger.expectedFile(r.id()));
        }
        // 債務注釈の登録判定（PlaintextSecretTokenGuardRules）の両配置一致は ArchUnit タグ付きの PlaintextSecretTokenGuardConditionTest で検証する
        return split;
    }

    /** 試験専用: 新配置の files から旧配置の docs/task-list.md 1ファイルを一時生成する（../evidence/ は evidence/ に戻す）。 */
    static String joinForTest(Map<String, String> files) {
        String index = files.getOrDefault(TaskListLedger.INDEX_PATH, "");
        List<String> lines = new ArrayList<>();
        files.keySet().stream().filter(p -> !TaskListLedger.INDEX_PATH.equals(p)).sorted().forEach(p ->
                TaskListLedger.extractRows(files.get(p), p).forEach(r -> lines.add(r.line().replace("](../evidence/", "](evidence/"))));
        return (index.endsWith("\n") ? index : index + "\n") + "\n" + HEADER + "\n" + String.join("\n", lines) + "\n";
    }

    private static String normalizeEvidence(String line) {
        return line.replaceAll("\\]\\((?:\\.\\.?/)?evidence/", "](evidence/");
    }

    /** 試験専用: 旧配置の本文から新配置（索引＋月別・legacy）を一時生成する（本番の移行は段Bで作る）。 */
    static Map<String, String> splitForTest(String single) {
        List<Row> rows = TaskListLedger.extractRows(single, TaskListLedger.INDEX_PATH);
        Set<Integer> rowLines = rows.stream().map(Row::lineNumber).collect(Collectors.toSet());
        String[] lines = single.split("\n", -1);
        StringBuilder index = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (!rowLines.contains(i + 1)) {
                index.append(lines[i]).append(i + 1 < lines.length ? "\n" : "");
            }
        }
        Map<String, List<String>> groups = new TreeMap<>();
        for (Row r : rows) {
            groups.computeIfAbsent(TaskListLedger.expectedFile(r.id()), k -> new ArrayList<>())
                    .add(r.line().replaceAll("\\]\\((?:\\./)?evidence/", "](../evidence/"));
        }
        Map<String, String> files = new LinkedHashMap<>();
        files.put(TaskListLedger.INDEX_PATH, index.toString());
        groups.forEach((file, group) -> files.put(file, "# 台帳 " + file.substring(file.lastIndexOf('/') + 1, file.length() - 3)
                + "\n\n" + HEADER + "\n" + String.join("\n", group) + "\n"));
        return files;
    }

    /**
     * 状態列ごとの件数を標準出力に出す（<b>合否条件にはしない</b>）。しきい値を固定すると行の増減で壊れるため判定には使わない。
     * 状態列には注記が付く値が多いため、語彙の前方一致で分類し、どれにも当てはまらない行は「その他」として出す。
     */
    private static void printStatusBreakdown(String content) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String vocabulary : STATUS_VOCABULARY) {
            counts.put(vocabulary, 0);
        }
        counts.put("その他", 0);
        List<String> unclassified = new ArrayList<>();
        Matcher row = CMP_ROW_WITH_STATUS.matcher(content);
        int total = 0;
        while (row.find()) {
            total++;
            String id = row.group(1);
            String status = row.group(3).replace("*", "").trim();
            String matched = STATUS_VOCABULARY.stream()
                    .filter(status::startsWith)
                    .findFirst()
                    .orElseGet(() -> STATUS_VOCABULARY.stream().filter(status::contains).findFirst().orElse(null));
            if (matched == null) {
                counts.merge("その他", 1, Integer::sum);
                unclassified.add(id + " → \"" + status + "\"");
            } else {
                counts.merge(matched, 1, Integer::sum);
            }
        }
        System.out.println("=== 台帳 状態別件数（合否条件ではない・規模の把握用） ===");
        System.out.println("  総行数: " + total + " 件");
        counts.forEach((status, count) -> System.out.println("  " + status + ": " + count + " 件"));
        if (!unclassified.isEmpty()) {
            System.out.println("  ※「その他」の内訳（状態の語彙に一致しなかった行）:");
            unclassified.forEach(entry -> System.out.println("    - " + entry));
        }
        System.out.println("=== ここまで ===");
    }
}
