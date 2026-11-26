package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 戦役 Z-A PR1 の番人 G3: スコープ種別 {@code "PLATFORM"} を、ロール判定系メソッドへ渡さない
 * （{@code docs/security/03_role_authority_model.md} §3.6）。
 *
 * <p>{@code hasRoleOrAbove} / {@code getRoleName} / {@code resolveEffectiveRoleName} /
 * {@code isAdminOrAbove} / {@code isMember} の<b>引数</b>に {@code "PLATFORM"} リテラルまたは
 * {@code PLATFORM_SCOPE_TYPE} が現れたら赤。PLATFORM スコープは運営領収書専用の疑似スコープで、
 * これらのメソッドへ渡すと {@code ScopeType.valueOf("PLATFORM")} の例外や、SYSTEM_ADMIN 短絡による
 * 意図しない権限成立を招く（運営領収書は {@code checkAdminOrAboveIncludingPlatform} 経由に限る）。
 * 現状は 0 件で固定する（基準線ファイルは持たない）。</p>
 *
 * <p>コメントだけ除去（{@link JavaSourceScanningUtils#maskCommentsOnly}）して走査する。
 * {@code "PLATFORM"} はリテラルそのものが検出対象のため、リテラルは潰さない。</p>
 */
class PlatformScopeRoleGuardTest {

    private static final Pattern GUARDED_CALL = Pattern.compile(
            "\\b(hasRoleOrAbove|getRoleName|resolveEffectiveRoleName|isAdminOrAbove|isMember)\\s*\\(");
    private static final Pattern PLATFORM_ARG = Pattern.compile("\"PLATFORM\"|\\bPLATFORM_SCOPE_TYPE\\b");

    /** 違反（呼び出し名＋引数テキスト）を返す。 */
    static List<String> findViolations(String source) {
        String s = JavaSourceScanningUtils.maskCommentsOnly(source);
        List<String> result = new ArrayList<>();
        Matcher m = GUARDED_CALL.matcher(s);
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
            if (PLATFORM_ARG.matcher(args).find()) {
                result.add(m.group(1) + "(" + args.strip());
            }
        }
        return result;
    }

    @Test
    @DisplayName("ロール判定系メソッドの引数に PLATFORM スコープを渡していない")
    void PLATFORMをロール判定へ渡していない() throws IOException {
        Map<String, String> sources = SysAdminBypassGuardTest.loadMainSources();
        assertTrue(sources.size() > 100, "本番ソースの走査対象が少なすぎる: " + sources.size());
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            for (String v : findViolations(e.getValue())) {
                violations.add(e.getKey() + " : " + v);
            }
        }
        if (!violations.isEmpty()) {
            fail("PLATFORM スコープをロール判定系メソッドへ渡しています（運営領収書は"
                    + " checkAdminOrAboveIncludingPlatform 経由に限る。docs/security/03 §3.6）:\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    @DisplayName("自己検証: 5 メソッド×PLATFORM（リテラル/定数）を検出し、無関係な呼び出し・コメントは誤検出しない")
    void 自己検証() {
        for (String call : List.of(
                "a.hasRoleOrAbove(1L, \"PLATFORM\", 0L, \"MEMBER\")",
                "a.getRoleName(1L, 0L, AccessControlService.PLATFORM_SCOPE_TYPE)",
                "a.resolveEffectiveRoleName(1L, 0L, \"PLATFORM\")",
                "a.isAdminOrAbove(1L, 0L, PLATFORM_SCOPE_TYPE)",
                "a.isMember(1L, 0L, \"PLATFORM\")",
                "a.isMember(1L, f(g(2)), \"PLATFORM\")")) {
            assertEquals(1, findViolations("class X { void f() { " + call + "; } }").size(),
                    "検出できていない（偽陰性）: " + call);
        }
        // 陰性: 別スコープ、拒否的な専用メソッド、コメント、メソッド呼び出し後ろの PLATFORM
        assertTrue(findViolations("class X { void f() { a.isMember(1L, 2L, \"TEAM\"); } }").isEmpty());
        assertTrue(findViolations(
                "class X { void f() { a.checkAdminOrAboveIncludingPlatform(1L, 0L, PLATFORM_SCOPE_TYPE); } }").isEmpty());
        assertTrue(findViolations("class X { /* a.isMember(1L, 0L, \"PLATFORM\") */ }").isEmpty());
        assertTrue(findViolations(
                "class X { void f() { a.isMember(1L, 2L, \"TEAM\"); y(\"PLATFORM\"); } }").isEmpty());
    }
}
