package com.mannschaft.app.common.architecture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    record Ledger(String layout, List<LedgerFile> files, List<Row> rows, List<String> missing) {
        boolean split() {
            return "split".equals(layout);
        }
    }

    enum Kind { MISSING, EMPTY, SCAN, DUPLICATE, COLUMNS, PRIORITY, PLACEMENT }

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

    /** 台帳を読む。requireRows が true なら索引の不在・行0件を例外にする。 */
    static Ledger read(Source source, boolean requireRows) {
        List<String> entries;
        String index;
        try {
            entries = source.listDir(DIR);
        } catch (IOException e) {
            throw new LedgerReadException(DIR + "/ の一覧の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
        }
        List<String> names = entries == null ? List.of() : entries.stream().sorted().toList();
        String layout = names.stream().anyMatch(TaskListLedger::isLedgerFileName) ? "split" : "single";
        try {
            index = source.readFile(INDEX_PATH);
        } catch (IOException e) {
            throw new LedgerReadException(INDEX_PATH + " の取得に失敗した（不在とは扱わない）: " + e.getMessage(), e);
        }
        List<LedgerFile> files = new ArrayList<>();
        files.add(new LedgerFile(INDEX_PATH, index));
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
                files.add(new LedgerFile(path, content));
            }
        }
        List<Row> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (LedgerFile f : files) {
            if (f.content() == null) {
                missing.add(f.path());
            } else {
                rows.addAll(extractRows(f.content(), f.path()));
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
        return new Ledger(layout, List.copyOf(files), List.copyOf(rows), List.copyOf(missing));
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
        return v;
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
                List<String> names = files.keySet().stream()
                        .filter(p -> p.startsWith(prefix))
                        .map(p -> p.substring(prefix.length()))
                        .filter(n -> !n.contains("/"))
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
