package com.mannschaft.app.common.sysadminbypass;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役 Z-A PR3 番人（AC11）: <b>拒否型 4 箇所の既存テストを 1 行も変えない</b>ことを機械的に固定する。
 *
 * <p>PR3 では SYSTEM_ADMIN の短絡（許可型）を撤去し、それに依存した既存テストの期待値を反転する。
 * その作業で、SYSTEM_ADMIN を<b>拒否する</b>側（拒否型）のテストまで誤って書き換えると、
 * 拒否型が許可へ反転しても検出できなくなる。そこで拒否型を検証している既存テストのメソッド本体
 * （Java は {@code void 名前(...) { ... }}、FE は {@code it('題名', () => { ... })}）の SHA-256 を
 * {@code src/test/resources/z_a_pr3/deny_type_tests_frozen.tsv} に固定し、1 文字でも変われば赤にする。</p>
 *
 * <p>対象の拒否型（設計書 z-a-design.md §0）:</p>
 * <ul>
 *   <li>{@code DefaultAccessControlService.checkScopeAdminOnly}</li>
 *   <li>{@code DefaultAccessControlService.canActAsScopeAdminOnly}</li>
 *   <li>{@code RoleService.requireActorScopeAdminOnly}</li>
 *   <li>FE {@code useAppNavGroups} の {@code !authStore.isSystemAdmin}（代理入力導線）</li>
 * </ul>
 *
 * <p>正当な理由で拒否型テストを変える場合は、マスターの承認を得たうえで TSV のハッシュを更新すること
 * （PR 差分に TSV の変更が出るので検分で必ず目に入る）。本番側の拒否型コードが残っていることも併せて固定する。</p>
 */
@DisplayName("Z-A PR3 番人: 拒否型テストの無改変固定（AC11）")
class DenyTypeTestsFrozenGuardTest {

    /** テストの作業ディレクトリは backend/。リポジトリ直下からの相対パスを解決する基点。 */
    private static final Path REPO_ROOT = Path.of("..");
    private static final Path FROZEN = Path.of("src/test/resources/z_a_pr3/deny_type_tests_frozen.tsv");

    private static final Set<String> REQUIRED_SUBJECTS = Set.of(
            "checkScopeAdminOnly", "canActAsScopeAdminOnly", "requireActorScopeAdminOnly", "useAppNavGroups");

    /** 拒否型テストを含むファイルに @Disabled が 1 つでもあれば赤（メソッド・クラス・@Nested のいずれの無効化も塞ぐ）。 */
    private static final Pattern DISABLED = Pattern.compile("@Disabled\\b");

    private record Entry(String subject, String path, String selector, String sha256) {
    }

    private static List<Entry> loadEntries() throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : Files.readAllLines(FROZEN, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] cols = line.split("\t");
            assertThat(cols).as("TSV の列数（subject, path, selector, sha256）: %s", line).hasSize(4);
            entries.add(new Entry(cols[0], cols[1], cols[2], cols[3]));
        }
        return entries;
    }

    @Test
    @DisplayName("固定対象の拒否型テスト本体がすべて存在し、SHA-256 が固定値と一致する（1 文字でも変われば赤）")
    void denyTypeTestBodiesAreUnchanged() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Entry e : loadEntries()) {
            Path file = REPO_ROOT.resolve(e.path());
            if (!Files.exists(file)) {
                problems.add("ファイルが無い: " + e.path());
                continue;
            }
            String src = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            boolean java = e.path().endsWith(".java");
            List<String> bodies = extract(src, e.selector(), java);
            if (bodies.size() != 1) {
                problems.add(String.format("%s の %s が %d 件見つかった（1 件であること）",
                        e.path(), e.selector(), bodies.size()));
                continue;
            }
            if (java && DISABLED.matcher(src).find()) {
                problems.add("@Disabled が含まれている（拒否型テストを無効化できない）: " + e.path());
                continue;
            }
            String actual = sha256(fingerprint(src, e.selector(), java));
            if (!actual.equals(e.sha256())) {
                problems.add(String.format("拒否型テストが変更された: %s %s（固定 %s / 実際 %s）",
                        e.path(), e.selector(), e.sha256(), actual));
            }
        }
        assertThat(problems).as("拒否型テストは無改変であること（z-a-design.md §8 規律）").isEmpty();
    }

    @Test
    @DisplayName("固定一覧は拒否型 4 箇所をすべて覆っている")
    void frozenListCoversAllDenyTypes() throws Exception {
        Set<String> covered = new LinkedHashSet<>();
        for (Entry e : loadEntries()) {
            covered.add(e.subject());
        }
        assertThat(covered).containsAll(REQUIRED_SUBJECTS);
    }

    @Test
    @DisplayName("本番側の拒否型 4 箇所は残っている（PR3 で SYSTEM_ADMIN の拒否を消さない）")
    void productionDenyTypesRemain() throws Exception {
        String acs = read("backend/src/main/java/com/mannschaft/app/role/service/DefaultAccessControlService.java");
        assertThat(acs).contains("public void checkScopeAdminOnly(")
                .contains("return !isSystemAdmin(userId) && isAdmin(userId, scopeId, scopeType);");
        String role = read("backend/src/main/java/com/mannschaft/app/role/service/RoleService.java");
        assertThat(role).contains("private void requireActorScopeAdminOnly(")
                .contains("boolean isAdmin = !systemAdmin && ");
        String nav = read("frontend/app/composables/useAppNavGroups.ts");
        assertThat(countOccurrences(nav, "!authStore.isSystemAdmin &&")).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("自己検証: 抽出器は Java のメソッド本体と FE の it ブロックを 1 件ずつ取り出し、改変でハッシュが変わる")
    void extractorSelfCheck() {
        String java = """
                class X {
                    @Test
                    void target() {
                        String s = "}{";
                        char c = '}';
                        // }
                        if (true) { s = s + "x"; }
                    }
                    void other() { }
                }
                """;
        List<String> bodies = extract(java, "target", true);
        assertThat(bodies).hasSize(1);
        assertThat(bodies.getFirst()).startsWith("void target()").endsWith("}");
        assertThat(bodies.getFirst()).contains("if (true)").doesNotContain("other");
        String changed = java.replace("s + \"x\"", "s + \"y\"");
        assertThat(sha256(extract(changed, "target", true).getFirst())).isNotEqualTo(sha256(bodies.getFirst()));

        String ts = """
                describe('d', () => {
                  it('題名 A', () => {
                    const o = { a: `}${1}` }
                    expect(o).toBeTruthy()
                  })
                  it('題名 B', () => {})
                })
                """;
        List<String> its = extract(ts, "it:題名 A", false);
        assertThat(its).hasSize(1);
        assertThat(its.getFirst()).startsWith("it('題名 A'").contains("toBeTruthy").doesNotContain("題名 B");
    }

    @Test
    @DisplayName("自己検証: 注釈の変更・補助メソッドの変更・@BeforeEach の変更で指紋が変わり、@Disabled は検出される")
    void fingerprintSelfCheck() {
        String base = """
                class X {
                    @BeforeEach
                    void setUp() { init(); }
                    @Test
                    @DisplayName("a")
                    void target() { helper(); }
                    private void helper() { check(1); }
                    private void unrelated() { }
                }
                """;
        String fp = fingerprint(base, "target", true);
        assertThat(fp).contains("@DisplayName(\"a\")").contains("@Test")
                .contains("void helper()").contains("void setUp()").doesNotContain("unrelated");
        // 本体は同じで注釈だけ変える（@Disabled の付与・@Test の削除・表示名変更）
        assertThat(sha256(fingerprint(base.replace("    @Test\n", "    @Test\n    @Disabled\n"), "target", true)))
                .isNotEqualTo(sha256(fp));
        assertThat(sha256(fingerprint(base.replace("    @Test\n", ""), "target", true))).isNotEqualTo(sha256(fp));
        assertThat(sha256(fingerprint(base.replace("DisplayName(\"a\")", "DisplayName(\"b\")"), "target", true)))
                .isNotEqualTo(sha256(fp));
        // 本体は同じで補助メソッド・@BeforeEach だけ変える
        assertThat(sha256(fingerprint(base.replace("check(1)", "check(2)"), "target", true))).isNotEqualTo(sha256(fp));
        assertThat(sha256(fingerprint(base.replace("init()", "skip()"), "target", true))).isNotEqualTo(sha256(fp));
        // 無関係なメソッドの変更では変わらない
        assertThat(sha256(fingerprint(base.replace("void unrelated() { }", "void unrelated() { x(); }"), "target", true)))
                .isEqualTo(sha256(fp));
        // @Disabled の検出
        assertThat(DISABLED.matcher(base.replace("    @Test\n", "    @Test\n    @Disabled(\"x\")\n")).find()).isTrue();
        assertThat(DISABLED.matcher(base).find()).isFalse();
    }

    // ───────────────────────────── 抽出器 ─────────────────────────────

    private static final String METHOD_DECL_FMT =
            "^[ \\t]*(?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w<>\\[\\],.? ]+?\\s+%s\\s*"
                    + "\\([^;{)]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{";

    private static final Set<String> NOT_METHODS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "new", "assertThat", "when", "verify");

    /**
     * 固定対象の指紋テキスト。メソッド本体だけでなく、直前の注釈（@Test・@Disabled・@DisplayName 等）、
     * 本体が呼ぶ同ファイルの補助メソッド（再帰的に）、@BeforeEach メソッドも含める。
     * FE は it ブロックに加え、同ファイルの beforeEach ブロックを含める。
     */
    static String fingerprint(String src, String selector, boolean java) {
        List<String> parts = new ArrayList<>();
        if (!java) {
            parts.addAll(extract(src, selector, false));
            Matcher m = Pattern.compile("\\bbeforeEach\\(").matcher(src);
            while (m.find()) {
                int arrow = src.indexOf("=>", m.end());
                int open = arrow < 0 ? -1 : src.indexOf('{', arrow);
                int close = open < 0 ? -1 : matchBrace(src, open);
                if (close > 0) {
                    parts.add(src.substring(m.start(), close + 1));
                }
            }
            return String.join("\n---\n", parts);
        }
        Set<String> included = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        queue.add(selector);
        Matcher be = Pattern.compile(
                "@BeforeEach\\b[^\\n]*\\n(?:\\s*@[^\\n]*\\n)*\\s*(?:(?:public|protected|private)\\s+)?void\\s+(\\w+)\\s*\\(")
                .matcher(src);
        while (be.find()) {
            queue.add(be.group(1));
        }
        while (!queue.isEmpty()) {
            String name = queue.remove(0);
            if (included.contains(name)) {
                continue;
            }
            String unit = methodUnit(src, name);
            if (unit == null) {
                continue;
            }
            included.add(name);
            parts.add(unit);
            Matcher call = Pattern.compile("\\b(\\w+)\\s*\\(").matcher(unit.substring(unit.indexOf('{')));
            while (call.find()) {
                String callee = call.group(1);
                if (!included.contains(callee) && !NOT_METHODS.contains(callee) && methodUnit(src, callee) != null) {
                    queue.add(callee);
                }
            }
        }
        // 先頭（対象）以外は辞書順にして順序の揺れを避ける
        String head = parts.get(0);
        List<String> rest = new ArrayList<>(parts.subList(1, parts.size()));
        Collections.sort(rest);
        StringBuilder sb = new StringBuilder(head);
        for (String r : rest) {
            sb.append("\n---\n").append(r);
        }
        return sb.toString();
    }

    /** 名前に一致するメソッドの「直前の注釈 + 宣言行 + 本体」。見つからなければ null。 */
    private static String methodUnit(String src, String name) {
        Matcher m = Pattern.compile(String.format(METHOD_DECL_FMT, Pattern.quote(name)), Pattern.MULTILINE).matcher(src);
        if (!m.find()) {
            return null;
        }
        int open = src.indexOf('{', m.end() - 1);
        int close = matchBrace(src, open);
        if (close < 0) {
            return null;
        }
        // 直前の注釈行（@ で始まる行）を遡って含める
        int from = m.start();
        while (from > 0) {
            int prevEnd = from - 1;
            int prevStart = src.lastIndexOf('\n', prevEnd - 1) + 1;
            if (src.substring(prevStart, prevEnd).trim().startsWith("@")) {
                from = prevStart;
            } else {
                break;
            }
        }
        return src.substring(from, close + 1);
    }

    /**
     * selector に一致するテスト本体を抽出する。Java は {@code void 名前(} から本体の閉じ括弧まで、
     * FE は {@code it:題名} を {@code it('題名'} から {@code it(...)} の本体の閉じ括弧まで。
     */
    static List<String> extract(String src, String selector, boolean java) {
        List<String> found = new ArrayList<>();
        Pattern start = java
                ? Pattern.compile("\\bvoid\\s+" + Pattern.quote(selector) + "\\s*\\(")
                : Pattern.compile("\\bit\\(\\s*'" + Pattern.quote(selector.substring("it:".length())) + "'");
        Matcher m = start.matcher(src);
        while (m.find()) {
            int from = m.start();
            int open = java ? src.indexOf('{', m.end()) : src.indexOf('{', src.indexOf("=>", m.end()));
            if (open < 0) {
                continue;
            }
            int close = matchBrace(src, open);
            if (close > 0) {
                found.add(src.substring(from, close + 1));
            }
        }
        return found;
    }

    /** open 位置の '{' に対応する '}' の位置を返す。文字列・文字・テンプレート・コメント内の括弧は数えない。 */
    private static int matchBrace(String s, int open) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                int nl = s.indexOf('\n', i);
                i = nl < 0 ? s.length() : nl;
                continue;
            }
            if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                i = end < 0 ? s.length() : end + 2;
                continue;
            }
            if (c == '"' && s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                i = end < 0 ? s.length() : end + 3;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                i = skipQuoted(s, i, c);
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
            i++;
        }
        return -1;
    }

    private static int skipQuoted(String s, int i, char quote) {
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) {
                return j + 1;
            }
            if (c == '\n' && quote != '`') {
                return j + 1;
            }
            j++;
        }
        return s.length();
    }

    static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String read(String repoRelative) throws IOException {
        return Files.readString(REPO_ROOT.resolve(repoRelative), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String s, String needle) {
        int count = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }
}
