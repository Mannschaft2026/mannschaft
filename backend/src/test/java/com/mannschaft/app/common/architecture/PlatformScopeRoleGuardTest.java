package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 戦役 Z-A PR1 の番人 G3: スコープ種別 {@code "PLATFORM"} を {@code AccessControlService} のスコープ引数付きメソッドへ
 * 渡してよいのは {@code checkAdminOrAboveIncludingPlatform} だけ（{@code docs/security/03_role_authority_model.md} §3.6）。
 *
 * <h2>判定（許可リストではなく<b>禁止対象の反転</b>）</h2>
 * <p>{@code AccessControlService} インターフェースで {@code String scopeType} 引数を取る全メソッド
 * （{@code checkAdminOrAbove} / {@code checkMembership} / {@code isAdmin} / {@code isRegularMemberOrAbove} /
 * {@code hasPermission} / {@code checkPermission} / {@code checkAdminOrHasPermission} /
 * {@code checkScopeAdminOnly} / {@code isMemberOrDescendant} / {@code hasRoleOrAbove} 等。
 * ソースのインターフェース宣言から自動で収集するため、メソッドが増えても漏れない）の<b>引数</b>に
 * PLATFORM が現れたら赤。{@code checkAdminOrAboveIncludingPlatform} だけが例外。</p>
 *
 * <p>PLATFORM は次のいずれか: リテラル {@code "PLATFORM"} / {@code PLATFORM_SCOPE_TYPE} /
 * <b>値が {@code "PLATFORM"} の別名定数</b>（例: {@code BetaTesterBadgeAwardService.BETA_BADGE_SCOPE_TYPE}）。
 * 別名は本番ソース全体から {@code = "PLATFORM";} の定数宣言を走査して収集する（定数名で追跡）。</p>
 *
 * <p>PLATFORM スコープは運営領収書専用の疑似スコープで、これらのメソッドへ渡すと
 * {@code ScopeType.valueOf("PLATFORM")} の例外や SYSTEM_ADMIN 短絡による意図しない権限成立を招く。
 * 現状は 0 件で固定する（基準線ファイルは持たない）。</p>
 *
 * <p>注: 設計書 §6 G3 の後半（SYSTEM_ADMIN の {@code hasRoleOrAbove(..., PLATFORM, ...)} が false になる等の
 * 単体テスト）は<b>挙動が変わる</b>ため PR3 で行う（本 PR は挙動を変えない番人のみ）。</p>
 *
 * <p>コメントだけ除去（{@link JavaSourceScanningUtils#maskCommentsOnly}）して走査する。
 * {@code "PLATFORM"} はリテラルそのものが検出対象のため、リテラルは潰さない。</p>
 */
class PlatformScopeRoleGuardTest {

    static final String ALLOWED_METHOD = "checkAdminOrAboveIncludingPlatform";
    static final String INTERFACE_PATH = "com/mannschaft/app/common/AccessControlService.java";

    private static final Pattern INTERFACE_METHOD = Pattern.compile("\\b(\\w+)\\s*\\(([^;{)]*)\\)\\s*;");
    private static final Pattern PLATFORM_ALIAS_DECL = Pattern.compile("\\b(\\w+)\\s*=\\s*\"PLATFORM\"\\s*;");

    /** インターフェース宣言から、{@code String scopeType} 引数を取るメソッド名を集める（許可メソッドを除く）。 */
    static Set<String> guardedMethodNames(String accessControlServiceSource) {
        String s = JavaSourceScanningUtils.maskCommentsAndLiterals(accessControlServiceSource);
        Set<String> names = new LinkedHashSet<>();
        Matcher m = INTERFACE_METHOD.matcher(s);
        while (m.find()) {
            if (m.group(2).contains("String scopeType") && !ALLOWED_METHOD.equals(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** 本番ソース全体から、値が {@code "PLATFORM"} の定数名（別名）を集める。 */
    static Set<String> platformAliases(Map<String, String> sources) {
        Set<String> aliases = new LinkedHashSet<>();
        for (String src : sources.values()) {
            Matcher m = PLATFORM_ALIAS_DECL.matcher(JavaSourceScanningUtils.maskCommentsOnly(src));
            while (m.find()) {
                aliases.add(m.group(1));
            }
        }
        return aliases;
    }

    /** 違反（呼び出し名＋引数テキスト）を返す。 */
    static List<String> findViolations(String source, Set<String> guardedMethods, Set<String> aliases) {
        String s = JavaSourceScanningUtils.maskCommentsOnly(source);
        String names = String.join("|", guardedMethods);
        Pattern call = Pattern.compile("\\b(" + names + ")\\s*\\(");
        StringBuilder platform = new StringBuilder("\"PLATFORM\"");
        for (String a : aliases) {
            platform.append("|\\b").append(Pattern.quote(a)).append("\\b");
        }
        Pattern platformArg = Pattern.compile(platform.toString());
        List<String> result = new ArrayList<>();
        Matcher m = call.matcher(s);
        while (m.find()) {
            int start = m.end();
            int depth = 1;
            int i = start;
            while (i < s.length() && depth > 0) {
                char c = s.charAt(i);
                if (c == '"') {
                    // 文字列リテラルは丸ごと読み飛ばして閉じ括弧の数え間違いを避ける
                    i++;
                    while (i < s.length() && s.charAt(i) != '"') {
                        if (s.charAt(i) == '\\') {
                            i++;
                        }
                        i++;
                    }
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                }
                i++;
            }
            String args = s.substring(start, Math.min(i, s.length()));
            if (platformArg.matcher(args).find()) {
                result.add(m.group(1) + "(" + args.strip());
            }
        }
        return result;
    }

    @Test
    @DisplayName("PLATFORM スコープを checkAdminOrAboveIncludingPlatform 以外の AccessControlService メソッドへ渡していない")
    void PLATFORMを専用メソッド以外へ渡していない() throws IOException {
        Map<String, String> sources = SysAdminBypassGuardTest.loadMainSources();
        assertTrue(sources.size() > 100, "本番ソースの走査対象が少なすぎる: " + sources.size());
        String acs = sources.get(INTERFACE_PATH);
        assertTrue(acs != null, "AccessControlService のソースが見つからない: " + INTERFACE_PATH);
        Set<String> guarded = guardedMethodNames(acs);
        for (String expected : List.of("checkAdminOrAbove", "checkMembership", "isAdmin", "isRegularMemberOrAbove",
                "hasPermission", "checkPermission", "checkAdminOrHasPermission", "checkScopeAdminOnly",
                "isMemberOrDescendant", "hasRoleOrAbove", "getRoleName", "isMember", "isAdminOrAbove")) {
            assertTrue(guarded.contains(expected), "ガード対象メソッドの収集漏れ: " + expected + " in " + guarded);
        }
        Set<String> aliases = platformAliases(sources);
        assertTrue(aliases.contains("PLATFORM_SCOPE_TYPE"), "PLATFORM_SCOPE_TYPE の別名収集漏れ: " + aliases);
        assertTrue(aliases.contains("BETA_BADGE_SCOPE_TYPE"), "別名定数の追跡漏れ: " + aliases);

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            for (String v : findViolations(e.getValue(), guarded, aliases)) {
                violations.add(e.getKey() + " : " + v);
            }
        }
        if (!violations.isEmpty()) {
            fail("PLATFORM スコープを AccessControlService のスコープ付きメソッドへ渡しています（渡してよいのは"
                    + " checkAdminOrAboveIncludingPlatform だけ。docs/security/03 §3.6）:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    @DisplayName("自己検証: 全スコープ付きメソッド×PLATFORM（リテラル/定数/別名）を検出し、許可メソッド・無関係な呼び出し・コメントは誤検出しない")
    void 自己検証() {
        String acs = "interface AccessControlService {\n"
                + "  String PLATFORM_SCOPE_TYPE = \"PLATFORM\";\n"
                + "  void checkAdminOrAbove(Long userId, Long scopeId, String scopeType);\n"
                + "  boolean isMember(Long userId, Long scopeId, String scopeType);\n"
                + "  boolean hasPermission(Long userId, Long scopeId, String scopeType, String permissionName);\n"
                + "  void checkAdminOrAboveIncludingPlatform(Long userId, Long scopeId, String scopeType);\n"
                + "  boolean isSystemAdmin(Long userId);\n"
                + "  Map<Long, String> resolveEffectiveRoleNames(\n"
                + "      java.util.Collection<Long> userIds, Long scopeId, String scopeType);\n"
                + "}";
        Set<String> guarded = guardedMethodNames(acs);
        assertEquals(Set.of("checkAdminOrAbove", "isMember", "hasPermission", "resolveEffectiveRoleNames"), guarded);
        Set<String> aliases = platformAliases(Map.of(
                "x/AccessControlService.java", acs,
                "y/Badge.java", "class Badge { private static final String BETA = \"PLATFORM\"; }"));
        assertEquals(Set.of("PLATFORM_SCOPE_TYPE", "BETA"), aliases);

        for (String call : List.of(
                "a.checkAdminOrAbove(1L, 0L, \"PLATFORM\")",
                "a.isMember(1L, 0L, AccessControlService.PLATFORM_SCOPE_TYPE)",
                "a.hasPermission(1L, 0L, PLATFORM_SCOPE_TYPE, \"X\")",
                "a.isMember(1L, 0L, BETA)",
                "a.resolveEffectiveRoleNames(ids, 0L, BETA)",
                "a.isMember(1L, f(g(2)), \"PLATFORM\")")) {
            assertEquals(1, findViolations("class X { void f() { " + call + "; } }", guarded, aliases).size(),
                    "検出できていない（偽陰性）: " + call);
        }
        // 陰性: 別スコープ、許可された専用メソッド、無関係なメソッドへの PLATFORM、コメント、呼び出し後ろの PLATFORM
        assertTrue(findViolations("class X { void f() { a.isMember(1L, 2L, \"TEAM\"); } }", guarded, aliases).isEmpty());
        assertTrue(findViolations(
                "class X { void f() { a.checkAdminOrAboveIncludingPlatform(1L, 0L, PLATFORM_SCOPE_TYPE); } }",
                guarded, aliases).isEmpty());
        assertTrue(findViolations("class X { void f() { repo.findByScopeType(BETA, 1L); } }", guarded, aliases).isEmpty());
        assertTrue(findViolations("class X { /* a.isMember(1L, 0L, \"PLATFORM\") */ }", guarded, aliases).isEmpty());
        assertTrue(findViolations(
                "class X { void f() { a.isMember(1L, 2L, \"TEAM\"); y(\"PLATFORM\"); } }", guarded, aliases).isEmpty());
    }
}
