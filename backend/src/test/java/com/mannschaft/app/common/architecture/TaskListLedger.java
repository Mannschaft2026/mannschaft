package com.mannschaft.app.common.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 台帳（戦役横断の課題正本）の共通読み取りと構造検査（CMP-261124-1222 段A）。番人（{@link TaskListCmpIdDuplicateGuardTest}・
 * {@link PlaintextSecretTokenGuardRules}）が共有する。
 *
 * <h2>両配置</h2>
 * <ul>
 *   <li>旧配置（single）: {@code docs/task-list.md} 1ファイルに前置きと7列表の全行がある。</li>
 *   <li>新配置（split）: {@code docs/task-list.md} は索引（前置きのみ・表の行なし）。行は
 *       {@code docs/task-list/YYYY-MM.md}（ID {@code CMP-YYMMDD-HHMM} の YYMM の月）と
 *       {@code docs/task-list/legacy.md}（旧形式 ID）に置く。</li>
 * </ul>
 * <p>判定は「{@code docs/task-list/} に YYYY-MM.md か legacy.md が1つでもある」こと。
 * Node 側の同じ実装は {@code scripts/task-list-ledger.mjs}（readLedger）と {@code scripts/task-list-structure-check.mjs}、
 * 両者が同じ判定を返すことを共有 fixture {@code scripts/fixtures/task-list-ledger-cases.json} で固定する。
 * 規則を変えるときは両方と fixture を同時に直すこと。</p>
 *
 * <h2>変更申告（CMP-261124-1222 ④）</h2>
 * <p>{@code docs/task-list/changes/*.md} の申告を base 連鎖で適用した実効台帳を {@link Ledger#files()} に返す（{@code read(source, requireRows, true)} で生の本文）。
 * 申告の形式・名前・サイズ・ID 一致・fork・孤児・重複・changes/ の不正なものは {@link Kind#CHANGE} の違反。</p>
 *
 * <p>ArchUnit 凍結ストアは使わない（Markdown の走査）。</p>
 */
final class TaskListLedger {

    static final String INDEX_PATH = "docs/task-list.md";
    static final String DIR = "docs/task-list";
    static final String LEGACY_FILE_NAME = "legacy.md";
    static final String CMP_ID_FORM = "CMP-\\d{6}-\\d{4}|CMP-\\d+";
    static final int EXPECTED_COLUMN_COUNT = 7;

    private static final Pattern MONTH_FILE_NAME = Pattern.compile("^\\d{4}-(?:0[1-9]|1[0-2])\\.md$");
    private static final Pattern NEW_FORM_ID = Pattern.compile("^CMP-(\\d{2})(\\d{2})\\d{2}-\\d{4}$");
    private static final Pattern OLD_FORM_ID = Pattern.compile("^CMP-\\d+$");
    private static final Pattern CMP_ID_EXACT = Pattern.compile("^(?:" + CMP_ID_FORM + ")$");
    private static final Pattern CMP_ID_PREFIX = Pattern.compile("^(?:" + CMP_ID_FORM + ")");
    /** 表の行頭に現れる CMP-ID（例: {@code | CMP-028 | ...}）。 */
    private static final Pattern CMP_ID_ROW = Pattern.compile("(?m)^\\|\\s*(" + CMP_ID_FORM + ")\\s*\\|");
    /** 走査経路の自己検証用の緩い計数（件数のしきい値は持たない）。 */
    private static final Pattern CMP_ID_ROW_LOOSE = Pattern.compile("(?m)^\\|.*?(" + CMP_ID_FORM + ")");
    /** 状態セルが優先度を持たなくてよい「完了系」の語（前方一致）。 */
    private static final Pattern STATUS_PRIORITY_OR_DONE = Pattern.compile(
            "^(P[1-5] |完了|取り下げ|取下げ|取下|重複|不要|対応不要|検証済み|対応済み|合格)");

    private TaskListLedger() {
    }

    /** ファイルの読み取り口。不在は null、取得そのものの失敗は IOException（不在と混同しない）。 */
    interface Source {
        /** ディレクトリ直下の名前。ディレクトリが無ければ null。 */
        List<String> listDir(String dir) throws IOException;

        /** 本文。ファイルが無ければ null。 */
        String readFile(String path) throws IOException;
    }

    record LedgerFile(String path, String content) {
    }

    record Row(String id, String line, String file, int lineNumber) {
    }

    /**
     * 読んだ台帳。files は既定で申告（docs/task-list/changes/）を適用した実効の本文（raw 指定時は生の本文）、
     * rawFiles は常に生の本文、changes は申告の解析・適用結果。
     */
    record Ledger(String layout, List<LedgerFile> files, List<Row> rows, List<String> missing, List<LedgerFile> rawFiles, Changes changes) {
        boolean split() {
            return "split".equals(layout);
        }
    }

    enum Kind { MISSING, EMPTY, SCAN, DUPLICATE, COLUMNS, PRIORITY, PLACEMENT, CHANGE }

    record Violation(Kind kind, String message) {
    }

    /** 台帳の読み取りに失敗した（取得失敗・索引不在・0件）。 */
    static final class LedgerReadException extends IllegalStateException {
        LedgerReadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static boolean isLedgerFileName(String name) {
        return LEGACY_FILE_NAME.equals(name) || MONTH_FILE_NAME.matcher(name).matches();
    }

    /** 新配置で ID が置かれるべきファイル（リポジトリ相対）。CMP-ID の形でなければ null。 */
    static String expectedFile(String id) {
        Matcher m = NEW_FORM_ID.matcher(id);
        if (m.matches()) {
            return DIR + "/20" + m.group(1) + "-" + m.group(2) + ".md";
        }
        return OLD_FORM_ID.matcher(id).matches() ? DIR + "/" + LEGACY_FILE_NAME : null;
    }

    /** 台帳を読む（申告を適用した実効台帳）。requireRows が true なら索引の不在・行0件を例外にする。 */
    static Ledger read(Source source, boolean requireRows) {
        return read(source, requireRows, false);
    }

    /** 台帳を読む。raw が true なら files を申告適用前の生の本文で返す（Node: readLedger の { raw: true }）。 */
    static Ledger read(Source source, boolean requireRows, boolean raw) {
        List<String> entries;
        String index;
        try {
            entries = source.listDir(DIR);
        } catch (IOException e) {
            throw new LedgerReadException(DIR + "/ の一覧の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
        }
        List<String> listed = entries == null ? List.of() : entries.stream().sorted().toList();
        boolean hasChanges = listed.contains(CHANGES_DIR_NAME);
        List<String> names = listed.stream().filter(n -> !CHANGES_DIR_NAME.equals(n)).toList();
        String layout = names.stream().anyMatch(TaskListLedger::isLedgerFileName) ? "split" : "single";
        try {
            index = source.readFile(INDEX_PATH);
        } catch (IOException e) {
            throw new LedgerReadException(INDEX_PATH + " の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
        }
        List<LedgerFile> rawFiles = new ArrayList<>();
        rawFiles.add(new LedgerFile(INDEX_PATH, index));
        if ("split".equals(layout)) {
            for (String name : names) {
                String path = DIR + "/" + name;
                String content;
                try {
                    content = source.readFile(path);
                } catch (IOException e) {
                    throw new LedgerReadException(path + " の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
                }
                if (content == null) {
                    throw new LedgerReadException(path + " は一覧にあるのに本文を読めない", null);
                }
                rawFiles.add(new LedgerFile(path, content));
            }
        }
        ReadChanges readChanges = hasChanges ? readChanges(source) : new ReadChanges(List.of(), List.of());
        Applied effective = applyChanges(rawFiles, layout, readChanges.entries());
        List<LedgerFile> files = raw ? List.copyOf(rawFiles) : effective.files();
        List<Row> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (LedgerFile f : files) {
            if (f.content() != null) {
                rows.addAll(extractRows(f.content(), f.path()));
            }
        }
        for (LedgerFile f : rawFiles) {
            if (f.content() == null) {
                missing.add(f.path());
            }
        }
        if (requireRows) {
            if (!missing.isEmpty()) {
                throw new LedgerReadException(String.join(", ", missing) + " が存在しない（台帳が無い状態を「行0件」とは扱わない）", null);
            }
            if (rows.isEmpty()) {
                throw new LedgerReadException("台帳（" + layout + " 配置）から CMP 行を1件も読めなかった。0件は正常とみなさない", null);
            }
        }
        List<Problem> problems = new ArrayList<>(readChanges.problems());
        problems.addAll(effective.problems());
        Changes changes = new Changes(List.copyOf(readChanges.entries()), raw ? List.of() : effective.applied(), List.copyOf(problems));
        return new Ledger(layout, files, List.copyOf(rows), List.copyOf(missing), List.copyOf(rawFiles), changes);
    }

    /** 先頭セルが CMP-ID そのものである表の行（行本文は行末の CR を除く）。 */
    static List<Row> extractRows(String content, String file) {
        List<Row> rows = new ArrayList<>();
        String[] lines = content.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (!line.startsWith("|")) {
                continue;
            }
            List<String> cells = splitTableRow(line);
            if (cells.size() < 2) {
                continue;
            }
            String id = cells.get(1).trim();
            if (CMP_ID_EXACT.matcher(id).matches()) {
                rows.add(new Row(id, line, file, i + 1));
            }
        }
        return rows;
    }

    /** 全ファイルの本文を所定の順（索引→docs/task-list/ の名前順）に改行で連結する。行単位で読む番人の互換用。 */
    static String concatenatedContent(Ledger ledger) {
        StringBuilder sb = new StringBuilder();
        for (LedgerFile f : ledger.files()) {
            if (f.content() == null) {
                continue;
            }
            sb.append(f.content());
            if (!f.content().endsWith("\n")) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 構造検査（両配置・全ファイル横断）。重複（ファイル跨ぎ含む）・走査経路の自己検証（緩い計数との一致）・列数7・
     * 未完行の優先度 P1〜P5。新配置のときだけ配置違反（月違い・legacy 違い・不正なファイル名・索引の CMP 行）。
     * 違反の文言はファイル名を前置きする。
     */
    static List<Violation> check(Ledger ledger) {
        List<Violation> v = new ArrayList<>();
        LedgerFile index = ledger.files().stream().filter(f -> INDEX_PATH.equals(f.path())).findFirst().orElse(null);
        if (index == null || index.content() == null) {
            v.add(new Violation(Kind.MISSING, INDEX_PATH + " が存在しない（読めない）。台帳が無い状態を「問題なし」とは判定しない。"));
            return v;
        }
        if (index.content().trim().isEmpty()) {
            v.add(new Violation(Kind.EMPTY, INDEX_PATH + " が空である。台帳が空の状態を「問題なし」とは判定しない。"));
            return v;
        }
        List<LedgerFile> files = ledger.split() ? ledger.files() : List.of(index);
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<String>> where = new LinkedHashMap<>();
        int looseTotal = 0;
        int rowTotal = 0;
        for (LedgerFile file : files) {
            String text = file.content();
            int strict = 0;
            Matcher m = CMP_ID_ROW.matcher(text);
            while (m.find()) {
                strict++;
                counts.merge(m.group(1), 1, Integer::sum);
                List<String> fs = where.computeIfAbsent(m.group(1), k -> new ArrayList<>());
                if (!fs.contains(file.path())) {
                    fs.add(file.path());
                }
            }
            int loose = 0;
            Matcher l = CMP_ID_ROW_LOOSE.matcher(text);
            while (l.find()) {
                loose++;
            }
            looseTotal += loose;
            if (strict != loose) {
                v.add(new Violation(Kind.SCAN, file.path() + ": CMP-ID を含む表の行は " + loose + " 件あるが、先頭セルの CMP-ID として厳密に抽出できたのは "
                        + strict + " 件で件数が一致しない。先頭セルが壊れた行、または先頭セル以外にだけ CMP-ID を持つ表の行がある"
                        + "（取りこぼした行の重複を見逃すため fail とする）。"));
            }
            List<String> ids = new ArrayList<>();
            for (String line : text.split("\n", -1)) {
                if (!line.startsWith("|")) {
                    continue;
                }
                List<String> cells = splitTableRow(line);
                if (cells.size() < 2) {
                    continue;
                }
                String firstCell = cells.get(1).trim();
                if (!CMP_ID_PREFIX.matcher(firstCell).find()) {
                    continue;
                }
                rowTotal++;
                int columns = cells.size() - 2;
                if (columns != EXPECTED_COLUMN_COUNT) {
                    v.add(new Violation(Kind.COLUMNS, file.path() + ": 列数: " + firstCell + " の行は " + columns + " 列（期待 "
                            + EXPECTED_COLUMN_COUNT + " 列）。セル内の | は \\| にエスケープすること（コードスパン内でも）。"));
                }
                if (cells.size() < 4 || !CMP_ID_EXACT.matcher(firstCell).find()) {
                    continue;
                }
                ids.add(firstCell);
                String status = cells.get(3).replace("*", "").trim();
                if (!STATUS_PRIORITY_OR_DONE.matcher(status).find()) {
                    String head = status.length() > 30 ? status.substring(0, 30) + "…" : status;
                    v.add(new Violation(Kind.PRIORITY, file.path() + ": 優先度: " + firstCell + " の状態セル \"" + head
                            + "\" が P1〜P5（後ろに空白）または完了系の語で始まらない。未完の行は先頭に優先度を書くこと。"));
                }
            }
            if (!ledger.split()) {
                continue;
            }
            if (INDEX_PATH.equals(file.path())) {
                for (String id : ids) {
                    v.add(new Violation(Kind.PLACEMENT, file.path() + ": 配置: 新配置では索引に CMP 行を置かない（" + id + " は "
                            + expectedFile(id) + " へ）。"));
                }
                continue;
            }
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            if (!isLedgerFileName(name)) {
                v.add(new Violation(Kind.PLACEMENT, file.path() + ": 配置: docs/task-list/ のファイル名は YYYY-MM.md（月は 01〜12）か "
                        + LEGACY_FILE_NAME + " に限る。"));
            }
            for (String id : ids) {
                String expected = expectedFile(id);
                if (file.path().equals(expected)) {
                    continue;
                }
                if (LEGACY_FILE_NAME.equals(name)) {
                    v.add(new Violation(Kind.PLACEMENT, file.path() + ": 配置: " + LEGACY_FILE_NAME + " に新形式 ID " + id + " がある（"
                            + expected + " へ置くこと）。"));
                } else {
                    v.add(new Violation(Kind.PLACEMENT, file.path() + ": 配置: " + id + " は " + expected + " に置くこと（自分の月・旧形式は "
                            + LEGACY_FILE_NAME + "）。"));
                }
            }
        }
        if (looseTotal == 0) {
            v.add(new Violation(Kind.SCAN, "CMP-ID を含む表の行を1件も見つけられなかった（" + ledger.layout() + " 配置・" + files.size()
                    + " ファイル）。走査経路か台帳の表形式が壊れており、「重複なし」の判定自体が信用できない。"));
        }
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 1) {
                v.add(new Violation(Kind.DUPLICATE, "CMP-ID の重複: " + e.getKey() + " が " + e.getValue() + " 回出現する（"
                        + String.join(", ", where.get(e.getKey())) + "）。後から main へ入った側が採番し直すこと。"));
            }
        }
        if (rowTotal == 0 && looseTotal > 0) {
            v.add(new Violation(Kind.SCAN, "先頭セルに CMP-ID を持つ表の行を1件も見つけられなかった。走査経路（splitTableRow / CMP_ID_FORM）が壊れている可能性がある。"));
        }
        // 変更申告の形式・名前・サイズ・ID 一致・連鎖（fork・孤児・重複）・changes/ の不正なもの（CMP-261124-1222 ④）。
        // 上の検査は申告を適用した実効台帳に対して行っている。
        for (Problem p : ledger.changes().problems()) {
            v.add(new Violation(Kind.CHANGE, p.message()));
        }
        return v;
    }

    // ------------------------------------------------------------------
    // 変更申告（docs/task-list/changes/*.md。CMP-261124-1222 ④）
    // ------------------------------------------------------------------
    // Node 側 scripts/task-list-ledger.mjs の「変更申告」節と同じ規則（共有 fixture の「申告:」case で一致を固定）。
    // 申告: <CMP-ID>--<UTC yyyymmddThhmmssZ>--<6桁乱数>.md。LF・2行固定・16KiB 以下。
    //   1行目 <!-- ledger-change v1 op=add|update id=<CMP-ID> base=none|sha256:<64hex> -->、2行目 完全な7列行。
    // base = 実効台帳上の対象行（CR 除去・UTF-8）の sha256。実効台帳 = 月別ファイル＋申告を base 連鎖で適用したもの。
    // 同じ base の update 2件以上 = fork、どの版とも繋がらない update = 孤児、既に実効台帳にある ID への add = 重複（いずれも適用しない）。

    static final String CHANGES_DIR_NAME = "changes";
    static final String CHANGES_DIR = DIR + "/" + CHANGES_DIR_NAME;
    static final int MAX_CHANGE_BYTES = 16 * 1024;
    static final String TABLE_HEADER = "| ID | 戦役 | 状態 | 依存 | 完了条件 | 証拠(PR/テスト) | 台帳 |";
    static final String TABLE_SEPARATOR = "|----|------|------|------|---------|----------------|------|";
    private static final Pattern CHANGE_FILE_NAME = Pattern.compile("^(" + CMP_ID_FORM + ")--(\\d{8}T\\d{6}Z)--(\\d{6})\\.md$");
    private static final Pattern CHANGE_META = Pattern.compile(
            "^<!-- ledger-change v1 op=(add|update) id=(" + CMP_ID_FORM + ") base=(none|sha256:[0-9a-f]{64}) -->$");
    private static final String CHANGE_NAME_RULE = "<CMP-ID>--<UTC yyyymmddThhmmssZ>--<6桁乱数>.md";

    /** 申告の問題（kind: entry / name / size / format / id / fork / orphan / duplicate）。message はパスを前置きする。 */
    record Problem(String kind, String path, List<String> paths, String message) {
    }

    /** 解析した申告1件。valid が false なら errors に理由。 */
    record Change(String path, String name, String id, String time, String rand, String op, String base, String line,
                  String content, boolean valid, List<Problem> errors) {
    }

    record Changes(List<Change> entries, List<String> applied, List<Problem> problems) {
        static Changes none() {
            return new Changes(List.of(), List.of(), List.of());
        }
    }

    private static Problem problem(String kind, String path, String message) {
        return new Problem(kind, path, List.of(path), path + ": 申告: " + message);
    }

    /** 申告の base（対象行の CR を除いた UTF-8 の sha256）。 */
    static String changeRowHash(String line) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(line.replace("\r", "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 月別ファイルの新規作成時の本文（Node: newFileSkeleton）。 */
    static String newFileSkeleton(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String heading;
        if (LEGACY_FILE_NAME.equals(name)) {
            heading = "# 台帳 旧形式 ID（CMP-003 等）の行\n\n旧形式 ID の行はここに置く（月が決まらないため）。新しい行は ID の月のファイル docs/task-list/YYYY-MM.md の末尾に追加する。規約は [索引](../task-list.md) を参照。\n";
        } else {
            String month = name.substring(0, name.length() - 3);
            heading = "# 台帳 " + month + "（ID が CMP-" + month.substring(2, 4) + month.substring(5, 7)
                    + "DD-HHMM の行）\n\n新しい行は本ファイルの末尾に追加する。規約は [索引](../task-list.md) を参照。\n";
        }
        return heading + "\n" + TABLE_HEADER + "\n" + TABLE_SEPARATOR + "\n";
    }

    /** 申告1件を解析する（Node: parseChangeFile）。 */
    static Change parseChange(String path, String content) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        Matcher m = CHANGE_FILE_NAME.matcher(name);
        List<Problem> errors = new ArrayList<>();
        if (!m.matches()) {
            errors.add(problem("name", path, "ファイル名が不正（名前は " + CHANGE_NAME_RULE + " に限る）。"));
            return new Change(path, name, null, null, null, null, null, null, content, false, errors);
        }
        String fileId = m.group(1);
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CHANGE_BYTES) {
            errors.add(problem("size", path, "サイズが " + bytes + " バイトで上限 " + MAX_CHANGE_BYTES + " バイト（16KiB）を超える。"));
            return new Change(path, name, fileId, m.group(2), m.group(3), null, null, null, content, false, errors);
        }
        String text = content.replace("\r\n", "\n");
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        String[] lines = body.split("\n", -1);
        if (text.contains("\r") || lines.length != 2) {
            errors.add(problem("format", path, "形式: 2行（メタ行と表の行）でない（" + lines.length + " 行）。"));
            return new Change(path, name, fileId, m.group(2), m.group(3), null, null, null, content, false, errors);
        }
        Matcher meta = CHANGE_META.matcher(lines[0]);
        if (!meta.matches()) {
            errors.add(problem("format", path, "形式: 1行目が <!-- ledger-change v1 op=add|update id=<CMP-ID> base=none|sha256:<64hex> --> でない。"));
            return new Change(path, name, fileId, m.group(2), m.group(3), null, null, null, content, false, errors);
        }
        String op = meta.group(1);
        String id = meta.group(2);
        String base = meta.group(3);
        if ("add".equals(op) && !"none".equals(base)) {
            errors.add(problem("format", path, "形式: add の base は none に限る。"));
        }
        if ("update".equals(op) && "none".equals(base)) {
            errors.add(problem("format", path, "形式: update の base は sha256:<64hex>（対象行のハッシュ）が要る。"));
        }
        if (!id.equals(fileId)) {
            errors.add(problem("id", path, "ID 不一致: ファイル名の ID " + fileId + " とメタ行の ID " + id + " が違う。"));
        }
        String line = lines[1];
        List<String> cells = line.startsWith("|") ? splitTableRow(line) : List.of();
        if (cells.size() < 3) {
            errors.add(problem("format", path, "形式: 2行目が表の行（| で始まる7列行）でない。"));
        } else if (!cells.get(1).trim().equals(id)) {
            errors.add(problem("id", path, "ID 不一致: メタ行の ID " + id + " と行の先頭セル " + cells.get(1).trim() + " が違う。"));
        }
        return new Change(path, name, fileId, m.group(2), m.group(3), op, base, line, content, errors.isEmpty(), errors);
    }

    private record ReadChanges(List<Change> entries, List<Problem> problems) {
    }

    private static ReadChanges readChanges(Source source) {
        List<Change> entries = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        List<String> listed;
        try {
            listed = source.listDir(CHANGES_DIR);
        } catch (IOException e) {
            throw new LedgerReadException(CHANGES_DIR + "/ の一覧の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
        }
        if (listed == null) {
            problems.add(problem("entry", CHANGES_DIR, CHANGES_DIR + " がディレクトリでない。"));
            return new ReadChanges(entries, problems);
        }
        for (String name : listed.stream().sorted().toList()) {
            String path = CHANGES_DIR + "/" + name;
            if (!name.endsWith(".md")) {
                problems.add(problem("entry", path, CHANGES_DIR + "/ に .md 以外のもの（サブディレクトリを含む）を置かない。"));
                continue;
            }
            if (!CHANGE_FILE_NAME.matcher(name).matches()) {
                problems.add(problem("name", path, "ファイル名が不正（名前は " + CHANGE_NAME_RULE + " に限る）。"));
                continue;
            }
            String content;
            try {
                content = source.readFile(path);
            } catch (IOException e) {
                throw new LedgerReadException(path + " の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
            }
            if (content == null) {
                problems.add(problem("entry", path, "本文を読めない（サブディレクトリ＝ネストは置かない）。"));
                continue;
            }
            Change c = parseChange(path, content);
            entries.add(c);
            problems.addAll(c.errors());
        }
        return new ReadChanges(entries, problems);
    }

    private static final Comparator<Change> CHANGE_ORDER = Comparator.comparing(Change::time)
            .thenComparing(Change::rand).thenComparing(Change::name);

    /** 申告の適用先の1ファイル（既存の行＋末尾に足す行）。 */
    private static final class Doc {
        final String path;
        final String[] lines;
        final List<String> appended = new ArrayList<>();
        final boolean isNew;

        Doc(String path, String content, boolean isNew) {
            this.path = path;
            this.lines = content.split("\n", -1);
            this.isNew = isNew;
        }

        String render() {
            String text = String.join("\n", lines);
            if (appended.isEmpty()) {
                return text;
            }
            StringBuilder sb = new StringBuilder(text);
            if (!text.isEmpty() && !text.endsWith("\n")) {
                sb.append('\n');
            }
            appended.forEach(a -> sb.append(a).append('\n'));
            return sb.toString();
        }
    }

    private static final class Loc {
        final Doc doc;
        final boolean appended;
        final int idx;
        final boolean cr;
        final String source;

        Loc(Doc doc, boolean appended, int idx, boolean cr, String source) {
            this.doc = doc;
            this.appended = appended;
            this.idx = idx;
            this.cr = cr;
            this.source = source;
        }

        String get() {
            if (appended) {
                return doc.appended.get(idx);
            }
            String l = doc.lines[idx];
            return l.endsWith("\r") ? l.substring(0, l.length() - 1) : l;
        }

        void set(String v) {
            if (appended) {
                doc.appended.set(idx, v);
            } else {
                doc.lines[idx] = v + (cr ? "\r" : "");
            }
        }
    }

    record Applied(List<LedgerFile> files, List<String> applied, List<Problem> problems) {
    }

    /** 申告を生の台帳ファイルへ base 連鎖で適用する（Node: applyChanges）。 */
    static Applied applyChanges(List<LedgerFile> rawFiles, String layout, List<Change> entries) {
        List<Change> valid = entries.stream().filter(Change::valid).toList();
        Map<String, Doc> docs = new LinkedHashMap<>();
        Map<String, Loc> loc = new HashMap<>();
        for (LedgerFile f : rawFiles) {
            if (f.content() == null) {
                continue;
            }
            Doc doc = new Doc(f.path(), f.content(), false);
            docs.put(f.path(), doc);
            for (Row r : extractRows(f.content(), f.path())) {
                loc.putIfAbsent(r.id(), new Loc(doc, false, r.lineNumber() - 1, doc.lines[r.lineNumber() - 1].endsWith("\r"), f.path()));
            }
        }
        List<Problem> problems = new ArrayList<>();
        Set<String> applied = new TreeSet<>();
        for (Change e : valid.stream().filter(c -> "add".equals(c.op())).sorted(CHANGE_ORDER).toList()) {
            Loc existing = loc.get(e.id());
            if (existing != null) {
                problems.add(problem("duplicate", e.path(), "重複: 既に実効台帳にある ID " + e.id() + " への add（" + existing.source
                        + "）。新しい ID を採るか update にすること。"));
                continue;
            }
            String target = "split".equals(layout) ? expectedFile(e.id()) : INDEX_PATH;
            Doc doc = docs.get(target);
            if (doc == null) {
                doc = new Doc(target, "split".equals(layout) ? newFileSkeleton(target) : "", true);
                docs.put(target, doc);
            }
            doc.appended.add(e.line());
            loc.put(e.id(), new Loc(doc, true, doc.appended.size() - 1, false, e.path()));
            applied.add(e.path());
        }
        Map<String, List<Change>> updatesById = new TreeMap<>();
        for (Change e : valid) {
            if ("update".equals(e.op())) {
                updatesById.computeIfAbsent(e.id(), k -> new ArrayList<>()).add(e);
            }
        }
        for (Map.Entry<String, List<Change>> entry : updatesById.entrySet()) {
            String id = entry.getKey();
            List<Change> list = entry.getValue().stream().sorted(CHANGE_ORDER).toList();
            Map<String, List<Change>> byBase = new LinkedHashMap<>();
            for (Change u : list) {
                byBase.computeIfAbsent(u.base(), k -> new ArrayList<>()).add(u);
            }
            Set<String> forked = new HashSet<>();
            for (Map.Entry<String, List<Change>> g : byBase.entrySet()) {
                if (g.getValue().size() < 2) {
                    continue;
                }
                forked.add(g.getKey());
                List<String> paths = g.getValue().stream().map(Change::path).toList();
                problems.add(new Problem("fork", paths.get(0), paths, paths.get(0) + ": 申告: fork（分岐）: " + id + " に同じ base の update が "
                        + paths.size() + " 件ある（" + String.join(", ", paths) + "）。一本の連鎖（前の申告の行を base に）に直すこと。"));
            }
            Loc l = loc.get(id);
            Set<Change> used = new HashSet<>();
            if (l != null) {
                while (true) {
                    String h = changeRowHash(l.get());
                    List<Change> group = byBase.get(h);
                    if (group == null || forked.contains(h) || used.contains(group.get(0))) {
                        break;
                    }
                    l.set(group.get(0).line());
                    used.add(group.get(0));
                    applied.add(group.get(0).path());
                }
            }
            for (Change u : list) {
                if (used.contains(u) || forked.contains(u.base())) {
                    continue;
                }
                problems.add(problem("orphan", u.path(), "孤児: " + id + " の update の base が実効台帳のどの版とも繋がらない"
                        + (l == null ? "（対象の行が無い）" : "") + "。最新の実効行から base を計算し直すこと。"));
            }
        }
        List<LedgerFile> files = new ArrayList<>();
        for (LedgerFile f : rawFiles) {
            files.add(f.content() == null ? f : new LedgerFile(f.path(), docs.get(f.path()).render()));
        }
        for (Doc doc : docs.values()) {
            if (doc.isNew) {
                files.add(new LedgerFile(doc.path, doc.render()));
            }
        }
        files.sort((a, b) -> INDEX_PATH.equals(a.path()) ? -1 : INDEX_PATH.equals(b.path()) ? 1 : a.path().compareTo(b.path()));
        return new Applied(List.copyOf(files), List.copyOf(applied), List.copyOf(problems));
    }

    /** 実効台帳の ID→行 の対応表（同じ ID が複数あれば最初の行）。 */
    static Map<String, String> rowMap(Ledger ledger) {
        Map<String, String> map = new LinkedHashMap<>();
        ledger.rows().forEach(r -> map.putIfAbsent(r.id(), r.line()));
        return map;
    }

    /**
     * Markdown 表の1行を GFM のレンダリングに合わせてセル分割する。{@code \|} だけを区切りにしない特殊扱いとし、
     * それ以外の {@code |} はコードスパンの内外を問わずすべて区切りとして数える（2026-09-24 是正・Codex検分指摘）。
     */
    static List<String> splitTableRow(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int i = 0;
        int n = line.length();
        while (i < n) {
            char c = line.charAt(i);
            if (c == '\\' && i + 1 < n && line.charAt(i + 1) == '|') {
                buf.append('|');
                i += 2;
                continue;
            }
            if (c == '|') {
                result.add(buf.toString());
                buf.setLength(0);
                i++;
                continue;
            }
            buf.append(c);
            i++;
        }
        result.add(buf.toString());
        return result;
    }

    /** 作業木の読み取り口（root はリポジトリルート）。 */
    static Source fileSystem(Path root) {
        return new Source() {
            @Override
            public List<String> listDir(String dir) throws IOException {
                Path p = root.resolve(dir);
                if (!Files.isDirectory(p)) {
                    return null;
                }
                try (Stream<Path> s = Files.list(p)) {
                    return s.map(x -> x.getFileName().toString()).toList();
                }
            }

            @Override
            public String readFile(String path) throws IOException {
                Path p = root.resolve(path);
                return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : null;
            }
        };
    }

    /** パス→本文の Map を読み取り口にする（試験用）。 */
    static Source inMemory(Map<String, String> files) {
        return new Source() {
            @Override
            public List<String> listDir(String dir) {
                String prefix = dir + "/";
                // 直下の名前（ファイルとサブディレクトリ）。サブディレクトリは名前だけを返し、readFile では null（作業木と同じ）。
                List<String> names = files.keySet().stream()
                        .filter(p -> p.startsWith(prefix))
                        .map(p -> p.substring(prefix.length()).split("/", -1)[0])
                        .distinct()
                        .toList();
                return names.isEmpty() ? null : names;
            }

            @Override
            public String readFile(String path) {
                return files.get(path);
            }
        };
    }

    /** リポジトリルートを backend/ 実行・ルート実行の両方に対応して解決する（docs/task-list.md か docs/task-list/ がある場所）。 */
    static Path resolveRepoRoot() {
        for (Path candidate : new Path[]{Paths.get(""), Paths.get(".."), Paths.get("backend").resolve("..")}) {
            if (Files.isRegularFile(candidate.resolve(INDEX_PATH)) || Files.isDirectory(candidate.resolve(DIR))) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new UncheckedIOException(new IOException(INDEX_PATH + " が見つからない（CWD=" + Paths.get("").toAbsolutePath() + "）。"
                + "リポジトリ構成が変わった場合は resolveRepoRoot() の候補パスを見直すこと。"));
    }

    /** 実リポジトリの台帳（requireRows: 索引の不在・0件を例外にする）。 */
    static Ledger readRepository(boolean requireRows) {
        return read(fileSystem(resolveRepoRoot()), requireRows);
    }
}
