package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SYSTEM_ADMIN 短絡（テナントの業務情報への素通し）の新規混入を検出する基準線番人
 * （戦役 Z-A PR1、{@code docs/security/03_role_authority_model.md} §3.6 の実装の第一歩）。
 *
 * <h2>目的</h2>
 * <p>§3.6 は「SYSTEM_ADMIN にテナント業務情報の素通し権限を与えない」「新たな例外・バイパスを追加しない」と
 * 定める。既存の直書きは戦役 Z-A/Z-C で順次除去するため、本番人は<b>現状を基準線として固定</b>し、
 * 「増やさない・縮める一方」にする（挙動は変えない）。</p>
 *
 * <h2>判定</h2>
 * <ul>
 *   <li>{@code src/main} の各 {@code .java} について、下記パターンの<b>ファイル別・パターン別件数</b>を数える
 *       （行番号に依存しないので、無関係な編集で壊れない）。</li>
 *   <li>基準線（{@code sysadmin_bypass/baseline.txt}）より増えた、または基準線に無いファイルに現れたら赤。</li>
 *   <li>基準線より減ったのに基準線を下げていなければ赤（縮める一方。下げ忘れも赤）。</li>
 *   <li>allowlist（{@code sysadmin_bypass/allowlist.txt}）は<b>クラス単位・理由必須</b>の固定除外
 *       （/system-admin/** 専用 Controller など SYSTEM_ADMIN が正当なもの）。{@code /admin/**} の一括指定は禁止。
 *       allowlist のファイルは件数を数えない。実在しなくなった行も赤（縮める一方）。</li>
 * </ul>
 *
 * <h2>マスクの使い分け</h2>
 * <ul>
 *   <li>{@link JavaSourceScanningUtils#maskCommentsAndLiterals}（コメントと文字列の中身を除去）:
 *       コード上の呼び出し・参照である {@code isSystemAdmin(} / {@code existsSystemAdminByUserId(} /
 *       {@code .systemAdmin()} / {@code ViewerRole.SYSTEM_ADMIN} / {@code forSystemAdmin(}。
 *       文字列やコメントに現れるだけの語を数えないため。</li>
 *   <li>{@link JavaSourceScanningUtils#maskCommentsOnly}（コメントだけ除去、文字列は残す）:
 *       リテラルの中に現れる {@code hasRole('SYSTEM_ADMIN')}（SpEL）/ {@code "ROLE_SYSTEM_ADMIN"} /
 *       {@code "SYSTEM_ADMIN"}（文字列比較）。リテラルの中身が検出対象そのものであるため、
 *       リテラルを潰すと全件見逃す。コメント内の言及は数えない。</li>
 * </ul>
 *
 * <h2>基準線の再生成（縮めたとき・意図した変更のとき）</h2>
 * <pre>cd backend &amp;&amp; SYSADMIN_BASELINE_REGENERATE=1 sh gradlew test --tests '*SysAdminBypassGuardTest*'</pre>
 * <p>{@code src/test/resources/sysadmin_bypass/baseline.txt} が現状の実測で上書きされる
 * （形式: {@code パス<TAB>パターン名<TAB>件数}、ソート済み）。<b>増える方向の再生成は PR レビューで拒否する</b>
 * （正当な追加は allowlist への理由付き追記のみ）。</p>
 *
 * <p>注: DefaultAccessControlService / RoleService / AdminImpersonationFilter は共通の素通し点または
 * 拒否型を含むため allowlist に入れず基準線で固定する（クラス全体を除外すると新規混入が見えなくなる）。</p>
 */
class SysAdminBypassGuardTest {

    static final Path MAIN_ROOT = Paths.get("src", "main", "java");
    static final Path BASELINE = Paths.get("src", "test", "resources", "sysadmin_bypass", "baseline.txt");
    static final Path ALLOWLIST = Paths.get("src", "test", "resources", "sysadmin_bypass", "allowlist.txt");

    /** コード上の参照（コメント・リテラル除去後に数える）。 */
    private static final Map<String, Pattern> CODE_PATTERNS = new LinkedHashMap<>();
    /** リテラル内の語（コメントだけ除去後に数える）。 */
    private static final Map<String, Pattern> LITERAL_PATTERNS = new LinkedHashMap<>();

    static {
        CODE_PATTERNS.put("isSystemAdmin(", Pattern.compile("isSystemAdmin\\s*\\("));
        CODE_PATTERNS.put("existsSystemAdminByUserId(", Pattern.compile("existsSystemAdminByUserId\\s*\\("));
        CODE_PATTERNS.put(".systemAdmin()", Pattern.compile("\\.systemAdmin\\s*\\(\\s*\\)"));
        CODE_PATTERNS.put("ViewerRole.SYSTEM_ADMIN", Pattern.compile("ViewerRole\\s*\\.\\s*SYSTEM_ADMIN"));
        CODE_PATTERNS.put("forSystemAdmin(", Pattern.compile("forSystemAdmin\\s*\\("));
        LITERAL_PATTERNS.put("hasRole('SYSTEM_ADMIN')", Pattern.compile("hasRole\\('SYSTEM_ADMIN'\\)"));
        LITERAL_PATTERNS.put("\"ROLE_SYSTEM_ADMIN\"", Pattern.compile("\"ROLE_SYSTEM_ADMIN\""));
        LITERAL_PATTERNS.put("\"SYSTEM_ADMIN\"", Pattern.compile("\"SYSTEM_ADMIN\""));
    }

    /** 1 ソースのパターン別件数（0 件のパターンは含めない）。 */
    static Map<String, Integer> countPatterns(String source) {
        Map<String, Integer> result = new TreeMap<>();
        String code = JavaSourceScanningUtils.maskCommentsAndLiterals(source);
        for (Map.Entry<String, Pattern> e : CODE_PATTERNS.entrySet()) {
            addCount(result, e.getKey(), e.getValue().matcher(code));
        }
        String lit = JavaSourceScanningUtils.maskCommentsOnly(source);
        for (Map.Entry<String, Pattern> e : LITERAL_PATTERNS.entrySet()) {
            addCount(result, e.getKey(), e.getValue().matcher(lit));
        }
        return result;
    }

    private static void addCount(Map<String, Integer> result, String key, Matcher m) {
        int n = 0;
        while (m.find()) {
            n++;
        }
        if (n > 0) {
            result.put(key, n);
        }
    }

    /** パス → ソース から「パス\tパターン\t」→件数 の平坦マップ（allowlist 除外後）を作る。 */
    static Map<String, Integer> scan(Map<String, String> sources, Map<String, String> allowlist) {
        Map<String, Integer> flat = new TreeMap<>();
        for (Map.Entry<String, String> src : sources.entrySet()) {
            if (allowlist.containsKey(src.getKey())) {
                continue;
            }
            for (Map.Entry<String, Integer> c : countPatterns(src.getValue()).entrySet()) {
                flat.put(src.getKey() + "\t" + c.getKey(), c.getValue());
            }
        }
        return flat;
    }

    /** 基準線と実測を突き合わせ、違反メッセージを返す（空なら緑）。 */
    static List<String> diff(Map<String, Integer> baseline, Map<String, Integer> actual) {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Integer> a : actual.entrySet()) {
            Integer b = baseline.get(a.getKey());
            String shown = a.getKey().replace("\t", "  ");
            if (b == null) {
                violations.add("新規混入（基準線に無い）: " + shown + " = " + a.getValue());
            } else if (a.getValue() > b) {
                violations.add("増加: " + shown + " 基準線 " + b + " → 実測 " + a.getValue());
            } else if (a.getValue() < b) {
                violations.add("減少したが基準線が未更新（縮める一方）: " + shown
                        + " 基準線 " + b + " → 実測 " + a.getValue());
            }
        }
        for (Map.Entry<String, Integer> b : baseline.entrySet()) {
            if (!actual.containsKey(b.getKey())) {
                violations.add("消滅したが基準線が未更新（縮める一方）: "
                        + b.getKey().replace("\t", "  ") + " 基準線 " + b.getValue() + " → 0");
            }
        }
        return violations;
    }

    static Map<String, String> loadMainSources() throws IOException {
        assertTrue(Files.isDirectory(MAIN_ROOT),
                "本番ソースルートが見つからない: " + MAIN_ROOT.toAbsolutePath());
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(MAIN_ROOT)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                sources.put(MAIN_ROOT.relativize(p).toString().replace('\\', '/'), Files.readString(p));
            }
        }
        return sources;
    }

    /** {@code 列1<TAB>列2[<TAB>列3]} 形式。{@code #} 始まり・空行は無視。 */
    static List<String[]> readTable(Path file) throws IOException {
        List<String[]> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            rows.add(line.split("\t"));
        }
        return rows;
    }

    static Map<String, String> loadAllowlist() throws IOException {
        Map<String, String> allow = new TreeMap<>();
        for (String[] r : readTable(ALLOWLIST)) {
            assertTrue(r.length == 2 && !r[1].isBlank(),
                    "allowlist は「パス<TAB>理由」の形式で、理由が必須: " + String.join("|", r));
            assertTrue(r[0].endsWith(".java") && !r[0].contains("*"),
                    "allowlist はクラス単位で列挙する（ワイルドカード・ディレクトリ一括は禁止）: " + r[0]);
            allow.put(r[0], r[1]);
        }
        return allow;
    }

    static Map<String, Integer> loadBaseline() throws IOException {
        Map<String, Integer> base = new TreeMap<>();
        for (String[] r : readTable(BASELINE)) {
            assertEquals(3, r.length, "baseline は「パス<TAB>パターン<TAB>件数」: " + String.join("|", r));
            base.put(r[0] + "\t" + r[1], Integer.parseInt(r[2]));
        }
        return base;
    }

    @Test
    @DisplayName("SYSTEM_ADMIN 短絡の直書きは基準線から増えず、縮めたら基準線も下げている")
    void 基準線どおりである() throws IOException {
        Map<String, String> sources = loadMainSources();
        Map<String, String> allowlist = loadAllowlist();
        Map<String, Integer> actual = scan(sources, allowlist);

        if (System.getenv("SYSADMIN_BASELINE_REGENERATE") != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("# SYSTEM_ADMIN 短絡の基準線（SysAdminBypassGuardTest）。パス<TAB>パターン<TAB>件数。\n")
                    .append("# 再生成: SYSADMIN_BASELINE_REGENERATE=1 sh gradlew test --tests '*SysAdminBypassGuardTest*'\n")
                    .append("# 増える方向の変更は禁止（縮める一方）。\n");
            for (Map.Entry<String, Integer> e : actual.entrySet()) {
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            Files.writeString(BASELINE, sb.toString());
            return;
        }

        // allowlist の実在確認（縮める一方）
        List<String> stale = new ArrayList<>();
        for (String path : allowlist.keySet()) {
            if (!sources.containsKey(path)) {
                stale.add(path);
            }
        }
        assertTrue(stale.isEmpty(), "allowlist に実在しないクラスがある（削除すること）: " + stale);

        List<String> violations = diff(loadBaseline(), actual);
        if (!violations.isEmpty()) {
            fail("SYSTEM_ADMIN 短絡の直書きが基準線と一致しません（docs/security/03 §3.6: 新たな素通しを追加しない）。\n"
                    + "増えた場合は直書きをやめ、正当な運営専用機能なら /system-admin 配下へ置くか理由付きで allowlist に追記。\n"
                    + "減った場合は SYSADMIN_BASELINE_REGENERATE=1 で基準線を下げること。\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    @DisplayName("allowlist は理由必須・クラス単位で、重複せず基準線と排他")
    void allowlistは基準線と排他である() throws IOException {
        Map<String, String> allowlist = loadAllowlist();
        Map<String, Integer> baseline = loadBaseline();
        for (String key : baseline.keySet()) {
            String path = key.substring(0, key.indexOf('\t'));
            assertFalse(allowlist.containsKey(path), "allowlist と基準線に同じクラスがある: " + path);
        }
    }

    // ---- 自己検証（試験用ソースを使い、検出器自身の偽陰性・偽陽性を晒す） ----

    @Test
    @DisplayName("自己検証: 新規直書きを混ぜると赤、基準線どおりなら緑、減少の下げ忘れも赤")
    void 自己検証_diff判定() {
        Map<String, String> base = Map.of("a/A.java",
                "class A { boolean f(Snap s) { return s.isSystemAdmin(); } }");
        Map<String, Integer> baseline = scan(base, Map.of());
        assertEquals(1, baseline.get("a/A.java\tisSystemAdmin("));
        assertTrue(diff(baseline, scan(base, Map.of())).isEmpty(), "基準線どおりなのに赤");

        // 既存ファイルへ直書きを 1 件追加 → 増加で赤
        Map<String, String> added = Map.of("a/A.java",
                "class A { boolean f(Snap s) { return s.isSystemAdmin() || s.isSystemAdmin(); } }");
        assertEquals(1, diff(baseline, scan(added, Map.of())).size());

        // 新規ファイル出現 → 赤（コード参照・リテラル・SpEL のそれぞれ）
        for (String body : List.of(
                "boolean f(Q q) { return q.existsSystemAdminByUserId(1L); }",
                "Object f() { return Snap.forSystemAdmin(); }",
                "boolean f(Snap s) { return s.systemAdmin().x(); }",
                "boolean f(V v) { return v == ViewerRole.SYSTEM_ADMIN; }",
                "@PreAuthorize(\"hasRole('SYSTEM_ADMIN')\") void f() {}",
                "boolean f(String r) { return \"ROLE_SYSTEM_ADMIN\".equals(r); }",
                "boolean f(String r) { return \"SYSTEM_ADMIN\".equals(r); }")) {
            Map<String, String> withNew = new LinkedHashMap<>(base);
            withNew.put("b/B.java", "class B { " + body + " }");
            List<String> v = diff(baseline, scan(withNew, Map.of()));
            assertEquals(1, v.size(), "新規混入を検出できていない（偽陰性）: " + body + " -> " + v);
        }

        // 減少したのに基準線を下げていない → 赤
        Map<String, String> removed = Map.of("a/A.java", "class A { }");
        assertEquals(1, diff(baseline, scan(removed, Map.of())).size());
    }

    @Test
    @DisplayName("自己検証: コメントや文字列に現れるだけのコード系パターンは数えず、コメント内リテラル語も数えない")
    void 自己検証_マスク() {
        String src = "class C {\n"
                + "  // s.isSystemAdmin() hasRole('SYSTEM_ADMIN') \"SYSTEM_ADMIN\"\n"
                + "  /* ViewerRole.SYSTEM_ADMIN forSystemAdmin( */\n"
                + "  String msg = \"isSystemAdmin( を呼ばない\";\n"
                + "}";
        assertTrue(countPatterns(src).isEmpty(), "マスクが効いていない（偽陽性）: " + countPatterns(src));
        // テキストブロック内の SpEL はリテラルとして数える
        String tb = "class D { String x = \"\"\"\n hasRole('SYSTEM_ADMIN')\n \"\"\"; }";
        assertEquals(1, countPatterns(tb).get("hasRole('SYSTEM_ADMIN')"));
    }

    @Test
    @DisplayName("自己検証: allowlist のクラスは数えない")
    void 自己検証_allowlist() {
        Map<String, String> src = Map.of("sys/SysCtl.java", "@PreAuthorize(\"hasRole('SYSTEM_ADMIN')\") class SysCtl {}");
        assertTrue(scan(src, Map.of("sys/SysCtl.java", "理由")).isEmpty());
        assertFalse(scan(src, Map.of()).isEmpty());
    }
}
