package com.mannschaft.app.support.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人テスト: テストコードが {@code INSERT INTO roles} の priority を数値で直書きすることを禁じる。
 *
 * <p>test プロファイルは Flyway 無効で roles が空のため、各 IT が roles をオンデマンド投入する。
 * その priority を固定値（例: 99）で書くと、commit されたまま同一 JVM に残り、後続クラスが正準値で
 * 投入しようとしても既存行が優先されて {@code hasRoleOrAbove} の優先度比較が崩れる
 * （例: SUPPORTER が MEMBER 以上と誤判定される）。priority は必ず正準表
 * {@link com.mannschaft.app.role.authorization.RolePriority}（V2.014 seed と一致）から取ること。</p>
 */
@DisplayName("番人: テストの roles INSERT で priority を数値直書きしない")
class RolesPriorityLiteralGuardTest {

    private static final Path TEST_ROOT = Paths.get("src/test/java");

    /**
     * 既知の違反（別戦役で根治予定）。ここへ新規追加してはならない。
     * BillingPayerHandoverStateMachineIT / WithdrawalPayerHandoverAuthzIT は ADMIN=100 を直書きしている
     * （台帳 CMP-261125-0612 の PR #4860 が両ファイルを正準 priority へ直す。確認済み。
     * #4860 のマージ後はこの許容登録を空にすること）。
     */
    private static final Set<String> KNOWN_VIOLATIONS = Set.of(
            "com/mannschaft/app/billing/BillingPayerHandoverStateMachineIT.java",
            "com/mannschaft/app/billing/WithdrawalPayerHandoverAuthzIT.java");

    private static final Pattern INSERT_ROLES = Pattern.compile(
            "INSERT\\s+(?:IGNORE\\s+)?INTO\\s+roles\\s*\\(([^)]*)\\)\\s*(VALUES\\s*\\(|SELECT\\s+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+");

    @Test
    @DisplayName("roles への INSERT の priority 値が数値リテラルでない")
    void rolesInsertDoesNotHardcodePriority() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                String rel = TEST_ROOT.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith("RolesPriorityLiteralGuardTest.java") || KNOWN_VIOLATIONS.contains(rel)) {
                    continue;
                }
                violations.addAll(findViolations(rel, Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        assertThat(violations)
                .as("roles の priority を数値で直書きしている。RolePriority.priority(name) を使うこと")
                .isEmpty();
    }

    @Test
    @DisplayName("検出器の自己検証: 直書きは検出し、パラメータは通す")
    void detectorSelfCheck() {
        assertThat(findViolations("x", "\"INSERT INTO roles (name, display_name, priority, is_system) \"\n"
                + "+ \"VALUES (:name, :name, 99, 0)\"")).hasSize(1);
        assertThat(findViolations("x", "\"INSERT IGNORE INTO roles (name, display_name, priority) \"\n"
                + "+ \"SELECT 'ADMIN', 'a', 2, 0 FROM DUAL\"")).hasSize(1);
        assertThat(findViolations("x", "\"INSERT INTO roles (name, display_name, priority, is_system) \"\n"
                + "+ \"VALUES (?, ?, ?, 0)\"")).isEmpty();
        assertThat(findViolations("x", "\"INSERT INTO roles (name, display_name, priority, is_system) \"\n"
                + "+ \"VALUES (:name, :dn, :priority, 0)\"")).isEmpty();
    }

    static List<String> findViolations(String rel, String source) {
        // Java の文字列連結（"..." + "..."）を取り除き、1 本の SQL として読む
        String flat = source.replaceAll("\"\\s*\\+\\s*\"", "").replaceAll("\\s+", " ");
        List<String> found = new ArrayList<>();
        Matcher m = INSERT_ROLES.matcher(flat);
        while (m.find()) {
            List<String> columns = splitTopLevel(m.group(1));
            int idx = indexOfPriority(columns);
            if (idx < 0) {
                continue;
            }
            List<String> values = splitTopLevel(valueList(flat, m.end(), m.group(2).trim().toUpperCase()));
            if (idx < values.size() && NUMERIC.matcher(values.get(idx).trim()).matches()) {
                found.add(rel + " : priority=" + values.get(idx).trim());
            }
        }
        return found;
    }

    private static int indexOfPriority(List<String> columns) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).trim().equalsIgnoreCase("priority")) {
                return i;
            }
        }
        return -1;
    }

    /** VALUES ( の直後なら対応する閉じ括弧まで、SELECT の直後なら FROM / 文字列終端まで。 */
    private static String valueList(String flat, int from, String kind) {
        if (kind.startsWith("VALUES")) {
            int depth = 1;
            boolean inStr = false;
            for (int i = from; i < flat.length(); i++) {
                char c = flat.charAt(i);
                if (c == '\'') {
                    inStr = !inStr;
                } else if (!inStr && c == '(') {
                    depth++;
                } else if (!inStr && c == ')' && --depth == 0) {
                    return flat.substring(from, i);
                }
            }
            return flat.substring(from);
        }
        int end = flat.toUpperCase().indexOf(" FROM ", from);
        return end < 0 ? flat.substring(from) : flat.substring(from, end);
    }

    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\'') {
                inStr = !inStr;
            }
            if (!inStr && c == '(') {
                depth++;
            } else if (!inStr && c == ')') {
                depth--;
            }
            if (!inStr && depth == 0 && c == ',') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }
}
