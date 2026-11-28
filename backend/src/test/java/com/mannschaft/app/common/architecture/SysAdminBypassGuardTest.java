package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>{@code src/main} の各 {@code .java} について、下記パターンの<b>ファイル別・パターン別・所属メソッド別件数</b>
 *       を数える（行番号に依存しないので、無関係な編集で壊れない）。件数キーは
 *       {@code パターン @ 所属メソッド名}（フィールド初期化・クラス直下は {@code -}）。
 *       所属メソッドを含めるのは、同一パターン内の入れ替え（拒否型を 1 本消して許可型を別メソッドへ 1 本足す等）で
 *       合計件数が変わらずに素通しが混入するのを検出するため。</li>
 *   <li>基準線（{@code sysadmin_bypass/baseline.txt}）より増えた、または基準線に無いキーに現れたら赤。</li>
 *   <li>基準線より減ったのに基準線を下げていなければ赤（縮める一方。下げ忘れも赤）。</li>
 *   <li>allowlist（{@code sysadmin_bypass/allowlist.txt}）は<b>クラス単位・理由必須</b>の固定除外
 *       （/system-admin/** 専用 Controller など SYSTEM_ADMIN が正当なもの）。{@code /admin/**} の一括指定は禁止。
 *       allowlist のファイルは件数を数えない。実在しなくなった行も赤（縮める一方）。</li>
 * </ul>
 *
 * <h2>検出パターン</h2>
 * <ul>
 *   <li>コード上（コメント・文字列の中身を除去して数える）:
 *     <ul>
 *       <li>名前に {@code SystemAdmin} を含むメソッドの呼び出し・宣言を<b>メソッド名ごと</b>に
 *           （{@code isSystemAdmin(} / {@code actorIsSystemAdmin(} / {@code isOrganizerAdminOrSystemAdmin(} /
 *           {@code requireHeadmanOrSystemAdmin(} / {@code systemAdmin(userId)} / {@code forSystemAdmin(} 等の
 *           許可型の包み関数・別表記と、{@code checkSystemAdmin(} / {@code requireSystemAdmin(} 等の拒否型の双方。
 *           引数の有無は問わない）。メソッド参照 {@code ::isSystemAdmin} も同様。</li>
 *       <li>{@code ViewerRole.SYSTEM_ADMIN} / {@code ViewerStatus.SYSTEM_ADMIN} / その他の
 *           {@code 型.SYSTEM_ADMIN} / {@code case SYSTEM_ADMIN} 等の裸の識別子 /
 *           {@code ROLE_SYSTEM_ADMIN} 定数の識別子参照。</li>
 *     </ul></li>
 *   <li>リテラル内（コメントだけ除去、文字列は残して数える。リテラルの中身が検出対象そのものであるため）:
 *       {@code hasRole('SYSTEM_ADMIN')} / {@code hasAnyRole(..SYSTEM_ADMIN..)} /
 *       {@code hasAuthority(..ROLE_SYSTEM_ADMIN..)} / {@code "ROLE_SYSTEM_ADMIN"} / {@code "SYSTEM_ADMIN"} /
 *       SQL・SpEL 内の {@code 'SYSTEM_ADMIN'}。</li>
 * </ul>
 *
 * <h2>基準線の再生成（縮めたとき・意図した変更のとき）</h2>
 * <pre>cd backend &amp;&amp; SYSADMIN_BASELINE_REGENERATE=1 sh gradlew test --tests '*SysAdminBypassGuardTest*'</pre>
 * <p>{@code src/test/resources/sysadmin_bypass/baseline.txt} が現状の実測で上書きされる
 * （形式: {@code パス<TAB>パターン @ メソッド<TAB>件数}、ソート済み）。環境変数が {@code "1"} のときだけ発動する。
 * <b>増える方向の再生成は PR レビューで拒否する</b>（正当な追加は allowlist への理由付き追記のみ）。
 * 基準線ファイルは CODEOWNERS の対象で、<b>件数が増える変更は PR 差分で目立つ</b>よう意図的に 1 件 1 行にしている。
 * 対象パターンを広げる変更に限り、増加が正当（既存の見逃しが可視化されるだけ）になる。</p>
 *
 * <h2>既知の限界（偽陰性・偽陽性の余地）</h2>
 * <ul>
 *   <li>局所変数の再利用: {@code boolean x = isSystemAdmin(..)} の結果を後で別の判定に流用する形
 *       （例: {@code MembershipBatchQueryService} 120-121 行付近）は、呼び出し箇所の件数しか見ない。
 *       変数の使われ方（短絡かどうか）までは追わない。</li>
 *   <li>同一メソッド内の {@code !} 反転: {@code !isSystemAdmin(..)} と {@code isSystemAdmin(..)} の入れ替え
 *       （拒否→許可の反転）は件数・所属メソッドが同じなので検出できない。</li>
 *   <li>同名オーバーロード: 引数の異なる同名メソッドは所属メソッド名が同じなので件数が合算される。
 *       オーバーロード間の移動は検出できない。</li>
 *   <li>メソッド名の変更（リネーム）は消滅＋新規として赤になる（基準線の再生成が必要）。</li>
 * </ul>
 *
 * <p>注: DefaultAccessControlService / RoleService / AdminImpersonationFilter / SecurityConfig は共通の素通し点または
 * 拒否型・パス規則を含むため allowlist に入れず基準線で固定する（クラス全体を除外すると新規混入が見えなくなる）。</p>
 */
class SysAdminBypassGuardTest {

    static final Path MAIN_ROOT = Paths.get("src", "main", "java");
    static final Path BASELINE = Paths.get("src", "test", "resources", "sysadmin_bypass", "baseline.txt");
    static final Path ALLOWLIST = Paths.get("src", "test", "resources", "sysadmin_bypass", "allowlist.txt");

    /** コード上の参照（コメント・リテラル除去後に数える）。固定名のパターン。 */
    private static final Map<String, Pattern> CODE_PATTERNS = new LinkedHashMap<>();
    /** リテラル内の語（コメントだけ除去後に数える）。 */
    private static final Map<String, Pattern> LITERAL_PATTERNS = new LinkedHashMap<>();
    /** 名前に SystemAdmin を含むメソッドの呼び出し・宣言（メソッド名ごとにキー化）。 */
    private static final Pattern SYSTEM_ADMIN_METHOD = Pattern.compile("\\b(\\w*[Ss]ystemAdmin\\w*)\\s*\\(");
    /** メソッド参照（{@code ::isSystemAdmin}）。 */
    private static final Pattern SYSTEM_ADMIN_METHOD_REF = Pattern.compile("::\\s*(\\w*[Ss]ystemAdmin\\w*)\\b");

    static {
        CODE_PATTERNS.put("ViewerRole.SYSTEM_ADMIN", Pattern.compile("\\bViewerRole\\s*\\.\\s*SYSTEM_ADMIN\\b"));
        CODE_PATTERNS.put("ViewerStatus.SYSTEM_ADMIN", Pattern.compile("\\bViewerStatus\\s*\\.\\s*SYSTEM_ADMIN\\b"));
        CODE_PATTERNS.put("Type.SYSTEM_ADMIN",
                Pattern.compile("\\b(?!ViewerRole\\b|ViewerStatus\\b)[A-Z]\\w*\\s*\\.\\s*SYSTEM_ADMIN\\b"));
        CODE_PATTERNS.put("SYSTEM_ADMIN(bare)", Pattern.compile("(?<![\\w.])SYSTEM_ADMIN\\b"));
        CODE_PATTERNS.put("ROLE_SYSTEM_ADMIN(ident)", Pattern.compile("\\bROLE_SYSTEM_ADMIN\\b"));
        LITERAL_PATTERNS.put("hasRole('SYSTEM_ADMIN')", Pattern.compile("hasRole\\(\\s*'SYSTEM_ADMIN'\\s*\\)"));
        LITERAL_PATTERNS.put("hasAnyRole(..SYSTEM_ADMIN..)", Pattern.compile("hasAnyRole\\([^)]*SYSTEM_ADMIN[^)]*\\)"));
        LITERAL_PATTERNS.put("hasAuthority(..ROLE_SYSTEM_ADMIN..)",
                Pattern.compile("hasAuthority\\([^)]*ROLE_SYSTEM_ADMIN[^)]*\\)"));
        LITERAL_PATTERNS.put("\"ROLE_SYSTEM_ADMIN\"", Pattern.compile("\"ROLE_SYSTEM_ADMIN\""));
        LITERAL_PATTERNS.put("\"SYSTEM_ADMIN\"", Pattern.compile("\"SYSTEM_ADMIN\""));
        LITERAL_PATTERNS.put("'SYSTEM_ADMIN'(SQL/SpEL)", Pattern.compile("(?<!hasRole\\()'SYSTEM_ADMIN'"));
    }

    /** 1 件の検出（位置とパターン名）。 */
    private record Hit(int pos, String pattern) { }

    private static final Set<String> NOT_METHOD_KEYWORDS =
            Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "else");
    private static final Pattern THROWS_TAIL = Pattern.compile("\\s+throws\\s+[\\w.,\\s<>]+$");
    private static final Pattern TYPE_DECL = Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w+");

    /**
     * 波括弧の直前の見出しから「ブロックの名前」を決める。メソッド → メソッド名、型宣言 → {@code -}
     * （メンバ直下）、それ以外（if/for/lambda/配列初期化/static 初期化等）→ 空文字（外側を引き継ぐ）。
     * {@code code} はコメント・文字列を潰してある前提（括弧の対応が文字列に乱されない）。
     */
    static String blockName(String code, int braceIdx) {
        int j = braceIdx;
        while (j > 0) {
            char ch = code.charAt(j - 1);
            if (ch == ';' || ch == '{' || ch == '}') {
                break;
            }
            j--;
        }
        String hdr = THROWS_TAIL.matcher(code.substring(j, braceIdx).strip()).replaceFirst("");
        if (hdr.endsWith(")")) {
            int depth = 0;
            int k = hdr.length() - 1;
            for (; k >= 0; k--) {
                char c = hdr.charAt(k);
                if (c == ')') {
                    depth++;
                } else if (c == '(') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            if (k <= 0) {
                return "";
            }
            String before = hdr.substring(0, k).stripTrailing();
            int e = before.length();
            int s = e;
            while (s > 0 && Character.isJavaIdentifierPart(before.charAt(s - 1))) {
                s--;
            }
            if (s == e) {
                return "";
            }
            String ident = before.substring(s, e);
            if (NOT_METHOD_KEYWORDS.contains(ident)) {
                return "";
            }
            String prev = before.substring(0, s).stripTrailing();
            if (prev.endsWith("record")) {
                return "-";
            }
            if (prev.endsWith("new")) {
                return "";
            }
            return ident;
        }
        return TYPE_DECL.matcher(hdr).find() ? "-" : "";
    }

    /** 昇順の位置ごとの所属メソッド名（フィールド・クラス直下は {@code -}）。 */
    static String[] enclosingMethods(String code, int[] positions) {
        String[] result = new String[positions.length];
        Deque<String> stack = new ArrayDeque<>();
        int pi = 0;
        for (int i = 0; i < code.length() && pi < positions.length; i++) {
            while (pi < positions.length && positions[pi] <= i) {
                String ahead = methodOpenedAfter(code, positions[pi]);
                result[pi] = ahead != null ? ahead : effective(stack);
                pi++;
            }
            char c = code.charAt(i);
            if (c == '{') {
                stack.push(blockName(code, i));
            } else if (c == '}' && !stack.isEmpty()) {
                stack.pop();
            }
        }
        while (pi < positions.length) {
            String ahead = methodOpenedAfter(code, positions[pi]);
            result[pi] = ahead != null ? ahead : effective(stack);
            pi++;
        }
        return result;
    }

    /**
     * ヒットの後ろで {@code ;} {@code }} に当たる前に最初に開くブロックがメソッド本体なら、そのメソッド名を返す
     * （メソッドの {@code {} より前に付いたアノテーション {@code @PreAuthorize("hasAnyRole(..)")} を、
     * クラス直下ではなくそのメソッドへ帰属させるため）。括弧内（アノテーション引数の配列初期化等）の波括弧は無視する。
     * 該当しなければ {@code null}（呼び出し側が外側ブロックの名前を使う）。
     */
    private static String methodOpenedAfter(String code, int pos) {
        int depth = 0;
        for (int i = pos; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth <= 0 && (c == ';' || c == '}')) {
                return null;
            } else if (depth <= 0 && c == '{') {
                String name = blockName(code, i);
                return name.isEmpty() || "-".equals(name) ? null : name;
            }
        }
        return null;
    }

    private static String effective(Deque<String> stack) {
        for (String name : stack) {
            if (!name.isEmpty()) {
                return name;
            }
        }
        return "-";
    }

    /** 1 ソースの「パターン @ 所属メソッド」別件数（0 件は含めない）。 */
    static Map<String, Integer> countPatterns(String source) {
        String code = JavaSourceScanningUtils.maskCommentsAndLiterals(source);
        String lit = JavaSourceScanningUtils.maskCommentsOnly(source);
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Pattern> e : CODE_PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(code);
            while (m.find()) {
                hits.add(new Hit(m.start(), e.getKey()));
            }
        }
        Matcher call = SYSTEM_ADMIN_METHOD.matcher(code);
        while (call.find()) {
            String name = call.group(1);
            if (!Character.isUpperCase(name.charAt(0))) {
                hits.add(new Hit(call.start(), name + "("));
            }
        }
        Matcher ref = SYSTEM_ADMIN_METHOD_REF.matcher(code);
        while (ref.find()) {
            hits.add(new Hit(ref.start(), "::" + ref.group(1)));
        }
        for (Map.Entry<String, Pattern> e : LITERAL_PATTERNS.entrySet()) {
            Matcher m = e.getValue().matcher(lit);
            while (m.find()) {
                hits.add(new Hit(m.start(), e.getKey()));
            }
        }
        hits.sort(Comparator.comparingInt(Hit::pos));
        int[] positions = hits.stream().mapToInt(Hit::pos).toArray();
        String[] methods = enclosingMethods(code, positions);
        Map<String, Integer> result = new TreeMap<>();
        for (int i = 0; i < hits.size(); i++) {
            result.merge(hits.get(i).pattern() + " @ " + methods[i], 1, Integer::sum);
        }
        return result;
    }

    /** パス → ソース から「パス\tパターン @ メソッド」→件数 の平坦マップ（allowlist 除外後）を作る。 */
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
            assertEquals(3, r.length, "baseline は「パス<TAB>パターン @ メソッド<TAB>件数」: " + String.join("|", r));
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

        if ("1".equals(System.getenv("SYSADMIN_BASELINE_REGENERATE"))) {
            StringBuilder sb = new StringBuilder();
            sb.append("# SYSTEM_ADMIN 短絡の基準線（SysAdminBypassGuardTest）。パス<TAB>パターン @ メソッド<TAB>件数。\n")
                    .append("# 再生成: SYSADMIN_BASELINE_REGENERATE=1 sh gradlew test --tests '*SysAdminBypassGuardTest*'\n")
                    .append("# 増える方向の変更は禁止（縮める一方）。CODEOWNERS 対象。\n");
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
        assertEquals(1, baseline.get("a/A.java\tisSystemAdmin( @ f"));
        assertTrue(diff(baseline, scan(base, Map.of())).isEmpty(), "基準線どおりなのに赤");

        // 既存ファイルへ直書きを 1 件追加 → 増加で赤
        Map<String, String> added = Map.of("a/A.java",
                "class A { boolean f(Snap s) { return s.isSystemAdmin() || s.isSystemAdmin(); } }");
        assertEquals(1, diff(baseline, scan(added, Map.of())).size());

        // 新規ファイル出現 → 赤（本文ごとに、期待するキー「パターン @ 所属メソッド」が違反に含まれること）
        Map<String, String> bodies = new LinkedHashMap<>();
        bodies.put("boolean f(Q q) { return q.existsSystemAdminByUserId(1L); }", "existsSystemAdminByUserId( @ f");
        bodies.put("Object f() { return Snap.forSystemAdmin(); }", "forSystemAdmin( @ f");
        bodies.put("boolean f(Snap s) { return s.systemAdmin().x(); }", "systemAdmin( @ f");
        bodies.put("Object f(Long id) { return ViewerContext.systemAdmin(id); }", "systemAdmin( @ f");
        bodies.put("boolean f(V v) { return v == ViewerRole.SYSTEM_ADMIN; }", "ViewerRole.SYSTEM_ADMIN @ f");
        bodies.put("boolean f(V v) { return v == ViewerStatus.SYSTEM_ADMIN; }", "ViewerStatus.SYSTEM_ADMIN @ f");
        bodies.put("boolean f(V v) { return v == RoleName.SYSTEM_ADMIN; }", "Type.SYSTEM_ADMIN @ f");
        bodies.put("boolean f(V v) { switch (v) { case SYSTEM_ADMIN: return true; default: return false; } }",
                "SYSTEM_ADMIN(bare) @ f");
        bodies.put("static final String R = ROLE_SYSTEM_ADMIN;", "ROLE_SYSTEM_ADMIN(ident) @ -");
        bodies.put("boolean f(Long u) { return actorIsSystemAdmin(u); }", "actorIsSystemAdmin( @ f");
        bodies.put("boolean f(Long u) { return isOrganizerAdminOrSystemAdmin(u, 1L); }",
                "isOrganizerAdminOrSystemAdmin( @ f");
        bodies.put("void f(Long u) { requireHeadmanOrSystemAdmin(u, 1L); }", "requireHeadmanOrSystemAdmin( @ f");
        bodies.put("void f(Long u) { checkSystemAdmin(u); }", "checkSystemAdmin( @ f");
        bodies.put("boolean f(Stream<Long> s) { return s.anyMatch(this::isSystemAdmin); }", "::isSystemAdmin @ f");
        bodies.put("@PreAuthorize(\"hasRole('SYSTEM_ADMIN')\") void f() {}", "hasRole('SYSTEM_ADMIN') @ f");
        bodies.put("@PreAuthorize(\"hasAnyRole('ADMIN','SYSTEM_ADMIN')\") void f() {}",
                "hasAnyRole(..SYSTEM_ADMIN..) @ f");
        bodies.put("@PreAuthorize(\"hasAuthority('ROLE_SYSTEM_ADMIN')\") void f() {}",
                "hasAuthority(..ROLE_SYSTEM_ADMIN..) @ f");
        bodies.put("static final String Q = \"WHERE r.name = 'SYSTEM_ADMIN'\";", "'SYSTEM_ADMIN'(SQL/SpEL) @ -");
        bodies.put("boolean f(String r) { return \"ROLE_SYSTEM_ADMIN\".equals(r); }", "\"ROLE_SYSTEM_ADMIN\" @ f");
        bodies.put("boolean f(String r) { return \"SYSTEM_ADMIN\".equals(r); }", "\"SYSTEM_ADMIN\" @ f");
        for (Map.Entry<String, String> body : bodies.entrySet()) {
            Map<String, String> withNew = new LinkedHashMap<>(base);
            withNew.put("b/B.java", "class B { " + body.getKey() + " }");
            List<String> v = diff(baseline, scan(withNew, Map.of()));
            String expected = "新規混入（基準線に無い）: b/B.java  " + body.getValue() + " = 1";
            assertTrue(v.contains(expected),
                    "期待するキーで新規混入を検出できていない（偽陰性）: " + body.getKey() + " 期待 " + expected + " -> " + v);
        }

        // 減少したのに基準線を下げていない → 赤
        Map<String, String> removed = Map.of("a/A.java", "class A { }");
        assertEquals(1, diff(baseline, scan(removed, Map.of())).size());
    }

    @Test
    @DisplayName("自己検証: 同一パターン内の入れ替え（別メソッドへの移動・拒否型→許可型）は合計件数が同じでも赤")
    void 自己検証_入れ替え() {
        // 同一ファイル・同一パターンで、所属メソッドだけが f → g に変わる
        Map<String, String> before = Map.of("a/A.java",
                "class A { boolean f(S s) { return s.isSystemAdmin(); } boolean g(S s) { return false; } }");
        Map<String, String> moved = Map.of("a/A.java",
                "class A { boolean f(S s) { return false; } boolean g(S s) { return s.isSystemAdmin(); } }");
        Map<String, Integer> baseline = scan(before, Map.of());
        assertEquals(2, diff(baseline, scan(moved, Map.of())).size(), "メソッド間の移動を検出できていない");

        // 拒否型を 1 本削除して許可型を 1 本追加（ファイル内の SystemAdmin 呼び出し総数は 1 のまま）
        Map<String, String> deny = Map.of("a/B.java",
                "class B { void f(Long u) { checkSystemAdmin(u); } }");
        Map<String, String> allow = Map.of("a/B.java",
                "class B { boolean f(Long u) { return isSystemAdmin(u); } }");
        assertEquals(2, diff(scan(deny, Map.of()), scan(allow, Map.of())).size(), "拒否型→許可型の入れ替えを検出できていない");

        // 同じメソッド内の無関係な編集（リネーム・行追加）では赤にならない（脆さの確認）
        Map<String, String> edited = Map.of("a/A.java",
                "class A {\n int x;\n boolean f(S sn) {\n  int y = 1;\n  return sn.isSystemAdmin();\n }\n boolean g(S s) { return false; }\n}");
        assertTrue(diff(baseline, scan(edited, Map.of())).isEmpty(), "無関係な編集で赤になる（偽陽性）");
    }

    @Test
    @DisplayName("自己検証: 所属メソッドの判定（ラムダ・if・throws・内部クラス・フィールド初期化）")
    void 自己検証_所属メソッド() {
        String src = "class C {\n"
                + "  static final boolean X = isSystemAdmin(1L);\n"
                + "  void a(Long u) throws java.io.IOException {\n"
                + "    if (u != null) { run(() -> { isSystemAdmin(u); }); }\n"
                + "  }\n"
                + "  static class In { boolean b(Long u) { return isSystemAdmin(u); } int fld = isSystemAdmin(2L) ? 1 : 0; }\n"
                + "}";
        Map<String, Integer> c = countPatterns(src);
        // 外側クラスと内部クラスのフィールド初期化はどちらも - に合算される
        assertEquals(2, c.get("isSystemAdmin( @ -"), "フィールド初期化は - になるはずが " + c);
        assertEquals(1, c.get("isSystemAdmin( @ a"), "throws 付きメソッド内のラムダ・if は外側のメソッド名 " + c);
        assertEquals(1, c.get("isSystemAdmin( @ b"), "内部クラスのメソッド " + c);
    }

    @Test
    @DisplayName("自己検証: メソッドの { より前のアノテーションはそのメソッドへ帰属し、別メソッドへ移すと赤")
    void 自己検証_アノテーションの帰属() {
        String onF = "class C {\n"
                + "  @PreAuthorize(\"hasAnyRole('ADMIN','SYSTEM_ADMIN')\")\n"
                + "  public void f() { }\n"
                + "  public void g() { }\n"
                + "}";
        String onG = "class C {\n"
                + "  public void f() { }\n"
                + "  @PreAuthorize(\"hasAnyRole('ADMIN','SYSTEM_ADMIN')\")\n"
                + "  public void g() { }\n"
                + "}";
        assertEquals(1, countPatterns(onF).get("hasAnyRole(..SYSTEM_ADMIN..) @ f"), "" + countPatterns(onF));
        assertFalse(countPatterns(onF).containsKey("hasAnyRole(..SYSTEM_ADMIN..) @ -"), "クラス直下に帰属している");
        Map<String, Integer> baseline = scan(Map.of("a/C.java", onF), Map.of());
        assertFalse(diff(baseline, scan(Map.of("a/C.java", onG), Map.of())).isEmpty(),
                "アノテーションを別メソッドへ移しても緑（偽陰性）");
        // メソッド本体内の文（; で終わる）や if 条件は、後続のメソッドへ誤帰属しない
        String body = "class C {\n void f(Long u) { boolean b = isSystemAdmin(u);\n if (isSystemAdmin(u)) { run(); } }\n"
                + " void g() { }\n}";
        assertEquals(2, countPatterns(body).get("isSystemAdmin( @ f"), "" + countPatterns(body));
    }

    @Test
    @DisplayName("自己検証: コメントや文字列に現れるだけのコード系パターンは数えず、コメント内リテラル語も数えない")
    void 自己検証_マスク() {
        String src = "class C {\n"
                + "  // s.isSystemAdmin() hasRole('SYSTEM_ADMIN') \"SYSTEM_ADMIN\"\n"
                + "  /* ViewerRole.SYSTEM_ADMIN forSystemAdmin( ViewerStatus.SYSTEM_ADMIN */\n"
                + "  String msg = \"isSystemAdmin( を呼ばない\";\n"
                + "}";
        assertTrue(countPatterns(src).isEmpty(), "マスクが効いていない（偽陽性）: " + countPatterns(src));
        // 生成されるクラス名・コンストラクタ（大文字始まり）や監査イベント定数は数えない
        assertTrue(countPatterns("class D { Object f() { return new SystemAdminDashboardResponse(); }"
                + " AuditEventType t = AuditEventType.SYSTEM_ADMIN_EMAIL_OUTBOX_VIEWED; }").isEmpty());
        // テキストブロック内の SpEL はリテラルとして数える
        String tb = "class D { String x = \"\"\"\n hasRole('SYSTEM_ADMIN')\n \"\"\"; }";
        assertEquals(1, countPatterns(tb).get("hasRole('SYSTEM_ADMIN') @ -"));
    }

    @Test
    @DisplayName("自己検証: allowlist のクラスは数えない")
    void 自己検証_allowlist() {
        Map<String, String> src = Map.of("sys/SysCtl.java", "@PreAuthorize(\"hasRole('SYSTEM_ADMIN')\") class SysCtl {}");
        assertTrue(scan(src, Map.of("sys/SysCtl.java", "理由")).isEmpty());
        assertFalse(scan(src, Map.of()).isEmpty());
    }
}
