package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.scopegate.ScopeGateCompliantCaller;
import com.mannschaft.app.common.architecture.fixtures.scopegate.ScopeGateRogueReader;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-01: 共通の門の番人（ラチェット方式。F00 §20.5.1 / §20.6・AC-0159-25 の骨組み）。
 *
 * <h2>何を禁じるか</h2>
 * <p>本番コードから {@code AccessControlService#checkMembership} / {@code #isMember}（および同名ラッパ
 * {@code ActivityScopeAccessGuard}・{@code BulletinAccessGuard}・{@code MembershipQueryCache}）を直接呼ぶこと。
 * 読み取りは共通の門 {@code ScopeContentAccessGate} の {@code checkScopeContentAccess} 系、中身を返さない書き込みは同じく {@code checkMembershipForWrite} に
 * 書き分け、呼び出し箇所の意図（読み取りか書き込みか）を機械的に分類できるようにする。</p>
 *
 * <h2>台帳（凍結ストアは使わない）</h2>
 * <ul>
 *   <li>{@code scope_content_gate/pending_direct_calls.txt} — 未移行リスト。パッケージごとの {@code ## <package>} 見出し行の下に
 *       {@code FQCN#method} を並べる。各ドメイン PR は自パッケージの行だけを削除する（見出し行は締め PR-Z まで消さない）。
 *       見出し行で並行 PR のハンクが隔てられ、3-way merge が衝突しない。</li>
 *   <li>{@code scope_content_gate/exempt_direct_calls.txt} — 対象外（§20.5.2 の「対象外」行）。{@code FQCN<TAB>理由}。理由空欄は赤。</li>
 * </ul>
 * <p>ArchUnit の {@code FreezingArchRule}（{@code archunit_store}）は使わない（並行マージ・{@code --tests} 絞り込み・
 * CI シャード間汚染の既知の罠を避けるため）。本テストはファイルを読むだけで書き換えない。</p>
 *
 * <h2>判定（ラチェット）</h2>
 * <ol>
 *   <li>リスト外（未移行リストにも対象外にも無い）の直呼び → 赤（再増殖の防止）。</li>
 *   <li>未移行リストにあるのにもう直呼びしていない行（陳腐行） → 赤（削除漏れの防止）。</li>
 *   <li>対象外にあるのに直呼びしていないクラス・理由空欄 → 赤。</li>
 * </ol>
 *
 * <p>初期生成・再生成: 環境変数 {@code SCOPE_GATE_REGENERATE=1} で {@link Regenerate} が現在の直呼びから
 * 未移行リストを書き出す（PR-01 の初期生成専用。以後の PR は手で自パッケージの行を削除する）。</p>
 */
@DisplayName("CMP-261124-0159 共通の門の番人（直呼び禁止のラチェット）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ScopeContentAccessGateCoverageTest {

    static final String ROOT = "com.mannschaft.app.";

    /** 直呼びを禁じる呼び出し先（所有クラス）。 */
    static final Set<String> TARGET_OWNERS = Set.of(
            "com.mannschaft.app.common.AccessControlService",
            "com.mannschaft.app.role.service.DefaultAccessControlService",
            "com.mannschaft.app.activity.service.ActivityScopeAccessGuard",
            "com.mannschaft.app.bulletin.service.BulletinAccessGuard",
            "com.mannschaft.app.common.visibility.MembershipQueryCache");

    /** 直呼びを禁じるメソッド名。 */
    static final Set<String> TARGET_METHODS = Set.of("checkMembership", "isMember");

    static final Path DIR = Paths.get("src", "test", "resources", "scope_content_gate");
    static final Path PENDING = DIR.resolve("pending_direct_calls.txt");
    static final Path EXEMPT = DIR.resolve("exempt_direct_calls.txt");

    /** 走査が壊れて 0 件で緑になる偽 green の防止（origin/main 2026-11-25 実測: 直呼びは 400 箇所超）。 */
    static final int MIN_PRODUCTION_DIRECT_CALLS = 200;

    // ───────── 本番 ─────────

    @Test
    @DisplayName("走査そのものが機能している（本番の直呼びが下限以上見つかる）")
    void 走査が機能している() {
        Set<String> calls = directCalls(ProductionClasses.get());
        assertThat(calls.size()).as("本番の直呼び検出数").isGreaterThanOrEqualTo(MIN_PRODUCTION_DIRECT_CALLS);
    }

    @Test
    @DisplayName("リスト外の直呼びが無い（新規の checkMembership / isMember 直呼びは門か checkMembershipForWrite に書き分ける）")
    void リスト外の直呼びが無い() {
        Ledger ledger = Ledger.read(PENDING);
        Map<String, String> exempt = readExempt(EXEMPT);
        Set<String> violations = newViolations(directCalls(ProductionClasses.get()), ledger.entries(), exempt.keySet());
        assertThat(violations)
                .as("未移行リスト・対象外のどちらにも無い直呼び。読み取りは ScopeContentAccessGate#checkScopeContentAccess 系、"
                        + "中身を返さない書き込みは ScopeContentAccessGate#checkMembershipForWrite へ書き分けること（F00 §20.5.1）")
                .isEmpty();
    }

    @Test
    @DisplayName("未移行リストに陳腐行が無い（移行したら自パッケージの行を削除する）")
    void 陳腐行が無い() {
        Ledger ledger = Ledger.read(PENDING);
        Set<String> stale = staleEntries(directCalls(ProductionClasses.get()), ledger.entries());
        assertThat(stale).as("もう直呼びしていない未移行リストの行（削除すること）").isEmpty();
    }

    @Test
    @DisplayName("未移行リストの行は自パッケージの見出しの下にあり、重複が無い")
    void 台帳の書式() {
        Ledger ledger = Ledger.read(PENDING);
        assertThat(ledger.formatErrors()).isEmpty();
    }

    @Test
    @DisplayName("対象外は理由付きで、実際に直呼びしているクラスだけ（陳腐な対象外行が無い）")
    void 対象外の書式と陳腐行() {
        Map<String, String> exempt = readExempt(EXEMPT);
        assertThat(exempt).as("対象外").isNotEmpty();
        assertThat(exempt.entrySet().stream().filter(e -> e.getValue().isBlank()).map(Map.Entry::getKey).toList())
                .as("理由が空欄の対象外行").isEmpty();
        Set<String> callers = new TreeSet<>();
        for (String key : directCalls(ProductionClasses.get())) {
            callers.add(key.substring(0, key.indexOf('#')));
        }
        assertThat(exempt.keySet().stream().filter(c -> !callers.contains(c)).toList())
                .as("直呼びしていない対象外行（削除すること）").isEmpty();
        Ledger ledger = Ledger.read(PENDING);
        assertThat(ledger.entries().stream().filter(e -> exempt.containsKey(e.substring(0, e.indexOf('#')))).toList())
                .as("対象外と未移行リストの二重登録").isEmpty();
    }

    // ───────── 自己検査（番人が本当に赤くなること） ─────────

    @Nested
    @DisplayName("自己検査")
    class SelfCheck {

        private final JavaClasses fixtures = new ClassFileImporter()
                .importClasses(ScopeGateRogueReader.class, ScopeGateCompliantCaller.class);

        @Test
        @DisplayName("直呼びを足すと赤（ラムダ内の呼び出しも外側のメソッドとして検出し、門・書き込み用は検出しない）")
        void 直呼びを足すと赤() {
            Set<String> calls = directCalls(fixtures);
            String rogue = ScopeGateRogueReader.class.getName();
            assertThat(calls).containsExactlyInAnyOrder(rogue + "#list", rogue + "#peek");
            assertThat(newViolations(calls, Set.of(), Set.of())).hasSize(2);
            assertThat(newViolations(calls, Set.of(rogue + "#list"), Set.of())).containsExactly(rogue + "#peek");
            assertThat(newViolations(calls, Set.of(), Set.of(rogue))).isEmpty();
        }

        @Test
        @DisplayName("移行済みの行を未移行リストに残すと赤（陳腐行）")
        void 陳腐行を残すと赤() {
            Set<String> calls = directCalls(fixtures);
            String compliant = ScopeGateCompliantCaller.class.getName() + "#read";
            assertThat(staleEntries(calls, Set.of(compliant))).containsExactly(compliant);
        }

        @Test
        @DisplayName("台帳の行が他パッケージの見出しの下にあると書式違反")
        void 見出し違いは赤() {
            Ledger ledger = Ledger.parse(List.of("## bulletin", "com.mannschaft.app.activity.service.X#y"));
            assertThat(ledger.formatErrors()).hasSize(1);
        }
    }

    // ───────── 初期生成（PR-01 専用） ─────────

    @Nested
    @DisplayName("再生成（SCOPE_GATE_REGENERATE=1 のときだけ）")
    class Regenerate {

        @Test
        void 未移行リストを書き出す() throws IOException {
            Assumptions.assumeTrue("1".equals(System.getenv("SCOPE_GATE_REGENERATE")));
            Set<String> exempt = readExempt(EXEMPT).keySet();
            Map<String, List<String>> byPackage = new TreeMap<>();
            for (String key : directCalls(ProductionClasses.get())) {
                if (exempt.contains(key.substring(0, key.indexOf('#')))) {
                    continue;
                }
                byPackage.computeIfAbsent(packageOf(key), k -> new ArrayList<>()).add(key);
            }
            List<String> lines = new ArrayList<>(Ledger.HEADER);
            byPackage.forEach((pkg, keys) -> {
                lines.add("");
                lines.add("## " + pkg);
                keys.stream().sorted().forEach(lines::add);
            });
            Files.write(PENDING, lines, StandardCharsets.UTF_8);
        }
    }

    // ───────── 判定ロジック（自己検査と本番で共用） ─────────

    /** 直呼びの一覧（{@code 最上位クラスFQCN#メソッド}。ラムダはその外側のメソッド名に正規化する）。 */
    static Set<String> directCalls(JavaClasses classes) {
        Set<String> keys = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                if (!TARGET_OWNERS.contains(owner) || !TARGET_METHODS.contains(call.getName())) {
                    continue;
                }
                keys.add(keyOf(call.getOrigin()));
            }
        }
        return keys;
    }

    static String keyOf(JavaCodeUnit origin) {
        String ownerName = origin.getOwner().getName();
        int nested = ownerName.indexOf('$');
        String topLevel = nested < 0 ? ownerName : ownerName.substring(0, nested);
        String method = origin.getName();
        if (method.startsWith("lambda$")) {
            String[] parts = method.split("\\$");
            method = parts.length > 1 ? parts[1] : method;
        }
        String prefix = nested < 0 ? "" : ownerName.substring(nested + 1) + ".";
        return topLevel + "#" + prefix + method;
    }

    static String packageOf(String key) {
        String rest = key.startsWith(ROOT) ? key.substring(ROOT.length()) : key;
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    static Set<String> newViolations(Set<String> calls, Set<String> pending, Set<String> exemptClasses) {
        Set<String> result = new TreeSet<>();
        for (String key : calls) {
            if (pending.contains(key) || exemptClasses.contains(key.substring(0, key.indexOf('#')))) {
                continue;
            }
            result.add(key);
        }
        return result;
    }

    static Set<String> staleEntries(Set<String> calls, Set<String> pending) {
        Set<String> result = new TreeSet<>(pending);
        result.removeAll(calls);
        return result;
    }

    static Map<String, String> readExempt(Path file) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String raw : readLines(file)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int tab = line.indexOf('\t');
            String fqcn = tab < 0 ? line : line.substring(0, tab).strip();
            String reason = tab < 0 ? "" : line.substring(tab + 1).strip();
            result.put(fqcn, reason);
        }
        return result;
    }

    static List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 未移行リスト。 */
    record Ledger(Set<String> entries, List<String> formatErrors) {

        static final List<String> HEADER = List.of(
                "# CMP-261124-0159 未移行リスト（共通の門の番人 ScopeContentAccessGateCoverageTest のラチェット台帳）",
                "# 書式: パッケージごとの「## <package>」見出し行の下に「FQCN#method」。",
                "# 各ドメイン PR は自パッケージの行だけを削除する。見出し行は締め PR-Z まで消さない（並行 PR の衝突を防ぐ）。",
                "# 新しい行を足してはならない（新規の直呼びは共通の門 ScopeContentAccessGate の checkScopeContentAccess 系か checkMembershipForWrite に書き分ける）。");

        static Ledger read(Path file) {
            return parse(readLines(file));
        }

        static Ledger parse(List<String> lines) {
            Set<String> entries = new TreeSet<>();
            List<String> errors = new ArrayList<>();
            String heading = null;
            for (String raw : lines) {
                String line = raw.strip();
                if (line.isEmpty() || (line.startsWith("#") && !line.startsWith("## "))) {
                    continue;
                }
                if (line.startsWith("## ")) {
                    heading = line.substring(3).strip();
                    continue;
                }
                if (!line.contains("#")) {
                    errors.add("FQCN#method の形でない行: " + line);
                    continue;
                }
                if (heading == null || !heading.equals(packageOf(line))) {
                    errors.add("見出し「## " + heading + "」の下に他パッケージの行: " + line);
                }
                if (!entries.add(line)) {
                    errors.add("重複行: " + line);
                }
            }
            return new Ledger(entries, errors);
        }
    }
}
