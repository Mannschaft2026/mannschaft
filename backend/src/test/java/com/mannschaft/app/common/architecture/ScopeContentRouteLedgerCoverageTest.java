package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.support.test.scopecontent.ScopeContentMatrixTable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-01: 経路台帳の網羅番人（F00 §20.5.7・AC-0159-107）。
 *
 * <h2>突き合わせる 4 つ</h2>
 * <ol>
 *   <li><b>コード側の一覧</b>（毎回コードから導出する）:
 *     <ul>
 *       <li>HTTP: 本番の {@code @RestController} / {@code @Controller} の GET ハンドラのうち、パスが TEAM スコープ
 *           （{@code /teams/{...}}・{@code {scopeType}}）のもの（補助 ID {@code R-<pkg>-<n>}）と、未ログインでも到達する公開経路
 *           （パスに {@code /public/}・{@code /signage/} を含むもの。{@code R-public-<n>}）。パスとメソッドの組み立ては
 *           {@code RequestMappingHandlerMapping} が読むのと同じ合成済みアノテーション
 *           （{@link AnnotatedElementUtils#findMergedAnnotation}）から求める（アプリケーションコンテキストを起動しない）。</li>
 *       <li>一斉配信の宛先作成（{@code R-aud-<n>}）: F00 §20.5.7 の grep 対象メソッド
 *           （{@code listActiveMemberIds} ほか）を呼ぶ本番メソッド。</li>
 *       <li>WebSocket 配信（{@code R-ws-<n>}）: {@code convertAndSend*} を呼ぶ本番メソッド。</li>
 *     </ul></li>
 *   <li><b>経路台帳</b> {@code scope_content_gate/routes.tsv}（補助 ID・分類・担当 PR・テスト名・状態）。</li>
 *   <li><b>Matrix 表</b> {@code scope_content_matrix/<package>.yaml}（各ドメイン PR が自分の分を追加）。</li>
 *   <li><b>検証事項表</b> {@code scope_content_gate/verification_items.tsv}（複数条項の AC を {@code V-0159-<AC>-<n>} に分解）。</li>
 * </ol>
 * <p>どれか 1 件の欠落・余剰で赤（双方向）。担当 PR が未マージの経路は台帳で「未移行」として許容し、
 * 「移行済み」にした行はテスト名（実在すること）と Matrix 表の行（門・Resolver・中身を返す書き込みの HTTP 経路）を必須とする。
 * 締め PR-Z で「未移行」0 件を確かめる。</p>
 *
 * <p>台帳の初期生成・コード変更時の追従: 環境変数 {@code SCOPE_ROUTES_REGENERATE=1} で {@link Regenerate} が
 * 既存行（補助 ID・分類・担当 PR・テスト名・状態）を保ったまま、コードにある経路を追加し、コードから消えた経路を落とす。
 * 手で行を足し引きしない（手書きしない。陣立て書 §7-13）。</p>
 */
@DisplayName("CMP-261124-0159 経路台帳の網羅番人（AC-0159-107）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class ScopeContentRouteLedgerCoverageTest {

    static final Path GATE_DIR = Paths.get("src", "test", "resources", "scope_content_gate");
    static final Path ROUTES = GATE_DIR.resolve("routes.tsv");
    static final Path VERIFICATION = GATE_DIR.resolve("verification_items.tsv");
    static final Path MATRIX_DIR = Paths.get("src", "test", "resources", "scope_content_matrix");
    static final Path TEST_SOURCES = Paths.get("src", "test", "java");

    static final String ROOT = "com.mannschaft.app.";
    static final Pattern TEAM_SCOPED_PATH = Pattern.compile("/teams/\\{[^}]+}|\\{scopeType}");
    static final Pattern PUBLIC_PATH = Pattern.compile("/public/|^/signage/");
    /** F00 §20.5.7 の宛先作成 grep と同じメソッド名。 */
    static final Pattern AUDIENCE_METHOD = Pattern.compile(
            "listActiveMemberIds|findUserIdsByScope|findActive[A-Za-z]*UserIdsByScope|getActiveUserIdsInScopes");
    static final Set<String> WS_SEND_METHODS = Set.of("convertAndSend", "convertAndSendToUser");
    static final Set<String> WS_SENDER_OWNERS = Set.of(
            "org.springframework.messaging.simp.SimpMessagingTemplate",
            "org.springframework.messaging.simp.SimpMessageSendingOperations",
            "org.springframework.messaging.core.AbstractMessageSendingTemplate",
            "org.springframework.messaging.core.MessageSendingOperations");

    static final Set<String> CLASSIFICATIONS =
            Set.of("門", "Resolver", "書き込み", "中身を返す書き込み", "対象外", "宛先", "WS", "未分類");
    static final Set<String> STATES = Set.of("未移行", "移行済み");
    static final Set<String> MATRIX_REQUIRED = Set.of("門", "Resolver", "中身を返す書き込み");
    static final Pattern ROUTE_ID = Pattern.compile("R-([a-z0-9]+)-(\\d+)");
    static final Pattern V_ID = Pattern.compile("V-0159-(\\d{2,3}[a-z]?)-(\\d+)");
    static final Pattern AC_ID = Pattern.compile("AC-0159-\\d{2,3}[a-z]?");

    /** 走査が壊れて 0 件で緑になる偽 green の防止。 */
    static final int MIN_TEAM_ROUTES = 100;

    // ───────── 本番 ─────────

    @Test
    @DisplayName("V-0159-107-1: コード側の経路一覧と経路台帳が双方向に一致する（台帳の 1 行の欠落・余剰で赤）")
    void コードと台帳が一致する() {
        List<CodeRoute> code = codeRoutes(ProductionClasses.get());
        assertThat(code.stream().filter(r -> r.category().equals("team")).count())
                .as("TEAM スコープの GET 経路の検出数（偽 green 防止）").isGreaterThanOrEqualTo(MIN_TEAM_ROUTES);
        List<String> errors = compareCodeAndLedger(code, readLedger(ROUTES));
        assertThat(errors).as("コード ⇔ 経路台帳の不一致（SCOPE_ROUTES_REGENERATE=1 で台帳を追従させる）").isEmpty();
    }

    @Test
    @DisplayName("V-0159-107-2: 経路台帳の各行の書式（補助 ID・分類・状態・移行済みのテスト名の実在）")
    void 台帳の書式() {
        assertThat(validateLedger(readLedger(ROUTES), TestSourceIndex.load())).isEmpty();
    }

    @Test
    @DisplayName("V-0159-107-3: Matrix 表の行は台帳に実在し、移行済みの読み取り経路は Matrix 表に載っている")
    void Matrix表と台帳が一致する() {
        assertThat(compareMatrixAndLedger(readMatrix(MATRIX_DIR), readLedger(ROUTES))).isEmpty();
    }

    @Test
    @DisplayName("V-0159-107-4: 検証事項表とテストの V 番号が双方向に一致する")
    void 検証事項表とテストが一致する() {
        assertThat(compareVerification(readVerification(VERIFICATION), TestSourceIndex.load())).isEmpty();
    }

    // ───────── 自己検査（1 件の削除・追加で赤になること） ─────────

    @Nested
    @DisplayName("自己検査")
    class SelfCheck {

        private final CodeRoute a = new CodeRoute("team", "bulletin", "HTTP", "GET /api/v1/teams/{teamId}/x",
                "com.mannschaft.app.bulletin.controller.XController#list");
        private final CodeRoute b = new CodeRoute("aud", "aud", "AUD", "listActiveMemberIds",
                "com.mannschaft.app.social.service.YService#send");

        private LedgerRow row(String id, CodeRoute r, String cls, String state, String test) {
            return new LedgerRow(id, r.kind(), r.target(), r.handler(), cls, "PR-D1", test, state);
        }

        @Test
        @DisplayName("V-0159-107-5: 台帳の行を 1 件削除すると赤、余計な行を 1 件足すと赤")
        void 台帳の削除と追加() {
            List<LedgerRow> ledger = List.of(row("R-bulletin-1", a, "門", "未移行", ""),
                    row("R-aud-1", b, "宛先", "未移行", ""));
            assertThat(compareCodeAndLedger(List.of(a, b), ledger)).isEmpty();
            assertThat(compareCodeAndLedger(List.of(a, b), ledger.subList(0, 1))).hasSize(1);
            List<LedgerRow> extra = new ArrayList<>(ledger);
            extra.add(new LedgerRow("R-bulletin-2", "HTTP", "GET /api/v1/teams/{teamId}/ghost",
                    "com.mannschaft.app.bulletin.controller.XController#ghost", "門", "PR-D1", "", "未移行"));
            assertThat(compareCodeAndLedger(List.of(a, b), extra)).hasSize(1);
        }

        @Test
        @DisplayName("V-0159-107-6: Matrix 表の行を 1 件削除すると赤（移行済み）、台帳に無い行を 1 件足すと赤")
        void Matrixの削除と追加() {
            List<LedgerRow> ledger = List.of(row("R-bulletin-1", a, "門", "移行済み", "X#y"));
            assertThat(compareMatrixAndLedger(Map.of("bulletin", Set.of("R-bulletin-1")), ledger)).isEmpty();
            assertThat(compareMatrixAndLedger(Map.of("bulletin", Set.of()), ledger)).hasSize(1);
            assertThat(compareMatrixAndLedger(Map.of("bulletin", Set.of("R-bulletin-1", "R-bulletin-9")), ledger))
                    .hasSize(1);
        }

        @Test
        @DisplayName("V-0159-107-7: 検証事項表の行を 1 件削除すると赤（テストにだけ残る）、余計な行を 1 件足すと赤")
        void 検証事項の削除と追加() {
            TestSourceIndex index = new TestSourceIndex(Map.of("FooTest", "@DisplayName(\"V-0159-04-1: ...\") void bar()"));
            VerificationRow v = new VerificationRow("V-0159-04-1", "AC-0159-04", "x", "PR-01", "FooTest#bar", "実装済み");
            assertThat(compareVerification(List.of(v), index)).isEmpty();
            assertThat(compareVerification(List.of(), index)).hasSize(1);
            VerificationRow ghost = new VerificationRow("V-0159-04-2", "AC-0159-04", "x", "PR-01", "FooTest#bar", "実装済み");
            assertThat(compareVerification(List.of(v, ghost), index)).hasSize(1);
        }
    }

    // ───────── 再生成（台帳を手書きしない） ─────────

    @Nested
    @DisplayName("再生成（SCOPE_ROUTES_REGENERATE=1 のときだけ）")
    class Regenerate {

        @Test
        void 台帳をコードに追従させる() throws IOException {
            Assumptions.assumeTrue("1".equals(System.getenv("SCOPE_ROUTES_REGENERATE")));
            List<LedgerRow> existing = Files.exists(ROUTES) ? readLedger(ROUTES) : List.of();
            Map<String, LedgerRow> byKey = new HashMap<>();
            Map<String, Integer> maxNo = new HashMap<>();
            for (LedgerRow r : existing) {
                byKey.put(r.key(), r);
                Matcher m = ROUTE_ID.matcher(r.id());
                if (m.matches()) {
                    maxNo.merge(m.group(1), Integer.parseInt(m.group(2)), Math::max);
                }
            }
            List<LedgerRow> rows = new ArrayList<>();
            List<CodeRoute> code = new ArrayList<>(codeRoutes(ProductionClasses.get()));
            code.sort(Comparator.comparing(CodeRoute::idPrefix).thenComparing(CodeRoute::handler)
                    .thenComparing(CodeRoute::target));
            for (CodeRoute r : code) {
                LedgerRow kept = byKey.get(r.key());
                if (kept != null) {
                    rows.add(kept);
                    continue;
                }
                int no = maxNo.merge(r.idPrefix(), 1, Integer::sum);
                rows.add(new LedgerRow("R-" + r.idPrefix() + "-" + no, r.kind(), r.target(), r.handler(),
                        LedgerDefaults.classification(r), LedgerDefaults.owner(r), "", "未移行"));
            }
            rows.sort(Comparator.comparing((LedgerRow r) -> idPrefixOf(r.id())).thenComparingInt(r -> idNoOf(r.id())));
            List<String> lines = new ArrayList<>(LedgerRow.HEADER);
            rows.forEach(r -> lines.add(r.toTsv()));
            Files.write(ROUTES, lines, StandardCharsets.UTF_8);
        }
    }

    // ───────── コード側の一覧 ─────────

    /**
     * コードから導出した経路。
     *
     * @param category {@code team}（TEAM スコープの GET）／{@code public}／{@code aud}／{@code ws}
     * @param pkg      ハンドラの最上位パッケージ（{@code com.mannschaft.app.} の直下）
     * @param kind     {@code HTTP}／{@code AUD}／{@code WS}
     * @param target   HTTP は「GET パス」、AUD・WS は呼び出し先メソッド名
     * @param handler  最上位クラスの FQCN#メソッド
     */
    record CodeRoute(String category, String pkg, String kind, String target, String handler) {
        String key() {
            return kind + " " + target + " " + handler;
        }

        String idPrefix() {
            return category.equals("team") ? pkg : category;
        }
    }

    static List<CodeRoute> codeRoutes(JavaClasses classes) {
        List<CodeRoute> routes = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.isAnnotatedWith(RestController.class) || javaClass.isAnnotatedWith(Controller.class)) {
                httpRoutes(javaClass, routes, seen);
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                String owner = call.getTargetOwner().getName();
                String name = call.getName();
                String handler = ScopeContentAccessGateCoverageTest.keyOf(call.getOrigin());
                String pkg = ScopeContentAccessGateCoverageTest.packageOf(handler);
                if (AUDIENCE_METHOD.matcher(name).matches() && owner.startsWith(ROOT)) {
                    add(routes, seen, new CodeRoute("aud", pkg, "AUD", name, handler));
                } else if (WS_SEND_METHODS.contains(name) && WS_SENDER_OWNERS.contains(owner)) {
                    add(routes, seen, new CodeRoute("ws", pkg, "WS", name, handler));
                }
            }
        }
        return routes;
    }

    private static void httpRoutes(JavaClass javaClass, List<CodeRoute> routes, Set<String> seen) {
        Class<?> type = javaClass.reflect();
        RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        String[] prefixes = classMapping == null || classMapping.path().length == 0
                ? new String[]{""} : classMapping.path();
        for (Method method : type.getDeclaredMethods()) {
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            List<RequestMethod> verbs = List.of(mapping.method());
            if (!verbs.isEmpty() && !verbs.contains(RequestMethod.GET)) {
                continue;
            }
            String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
            String handler = topLevel(type.getName()) + "#" + method.getName();
            String pkg = ScopeContentAccessGateCoverageTest.packageOf(handler);
            for (String prefix : prefixes) {
                for (String path : paths) {
                    String full = join(prefix, path);
                    String category = PUBLIC_PATH.matcher(full).find() ? "public"
                            : TEAM_SCOPED_PATH.matcher(full).find() ? "team" : null;
                    if (category != null) {
                        add(routes, seen, new CodeRoute(category, pkg, "HTTP", "GET " + full, handler));
                    }
                }
            }
        }
    }

    private static void add(List<CodeRoute> routes, Set<String> seen, CodeRoute route) {
        if (seen.add(route.key())) {
            routes.add(route);
        }
    }

    private static String join(String prefix, String path) {
        String a = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (path.isEmpty()) {
            return a.isEmpty() ? "/" : a;
        }
        return a + (path.startsWith("/") ? path : "/" + path);
    }

    private static String topLevel(String name) {
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }

    // ───────── 経路台帳 ─────────

    record LedgerRow(String id, String kind, String target, String handler, String classification,
                     String owner, String testName, String state) {

        static final List<String> HEADER = List.of(
                "# CMP-261124-0159 経路台帳（ScopeContentRouteLedgerCoverageTest がコードと双方向に突き合わせる）",
                "# 列: 補助ID / 種別(HTTP・AUD・WS) / 対象(GET パス・呼び出し先) / クラス#メソッド / 分類 / 担当PR / テスト名 / 状態",
                "# 分類: 門・Resolver・書き込み・中身を返す書き込み・対象外・宛先・WS・未分類（未分類は未移行の間だけ）",
                "# 行の追加・削除は SCOPE_ROUTES_REGENERATE=1 で再生成する（手書きしない）。各 PR は担当行の分類・テスト名・状態だけを更新する。",
                "補助ID\t種別\t対象\tクラス#メソッド\t分類\t担当PR\tテスト名\t状態");

        String key() {
            return kind + " " + target + " " + handler;
        }

        String toTsv() {
            return String.join("\t", id, kind, target, handler, classification, owner, testName, state);
        }
    }

    static List<LedgerRow> readLedger(Path file) {
        List<LedgerRow> rows = new ArrayList<>();
        for (String line : ScopeContentAccessGateCoverageTest.readLines(file)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("補助ID")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length != 8) {
                rows.add(new LedgerRow("列数不正:" + line, "", "", "", "", "", "", ""));
                continue;
            }
            rows.add(new LedgerRow(c[0].strip(), c[1].strip(), c[2].strip(), c[3].strip(), c[4].strip(),
                    c[5].strip(), c[6].strip(), c[7].strip()));
        }
        return rows;
    }

    static List<String> compareCodeAndLedger(List<CodeRoute> code, List<LedgerRow> ledger) {
        List<String> errors = new ArrayList<>();
        Set<String> ledgerKeys = new TreeSet<>();
        ledger.forEach(r -> ledgerKeys.add(r.key()));
        Set<String> codeKeys = new TreeSet<>();
        code.forEach(r -> codeKeys.add(r.key()));
        for (String k : codeKeys) {
            if (!ledgerKeys.contains(k)) {
                errors.add("台帳に無い経路（コードにはある）: " + k);
            }
        }
        for (String k : ledgerKeys) {
            if (!codeKeys.contains(k)) {
                errors.add("コードに無い経路（台帳にだけある）: " + k);
            }
        }
        return errors;
    }

    static List<String> validateLedger(List<LedgerRow> ledger, TestSourceIndex tests) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (LedgerRow r : ledger) {
            Matcher m = ROUTE_ID.matcher(r.id());
            if (!m.matches()) {
                errors.add("補助 ID の形が不正: " + r.id());
                continue;
            }
            if (!ids.add(r.id())) {
                errors.add("補助 ID の重複: " + r.id());
            }
            String prefix = m.group(1);
            boolean special = Set.of("public", "aud", "ws").contains(prefix);
            if (!special && !prefix.equals(ScopeContentAccessGateCoverageTest.packageOf(r.handler()))) {
                errors.add("補助 ID のパッケージとハンドラのパッケージが違う: " + r.id() + " " + r.handler());
            }
            if (!CLASSIFICATIONS.contains(r.classification())) {
                errors.add("分類が不正: " + r.id() + " " + r.classification());
            }
            if (!STATES.contains(r.state())) {
                errors.add("状態が不正: " + r.id() + " " + r.state());
            }
            if (!r.owner().startsWith("PR-")) {
                errors.add("担当 PR が不正: " + r.id() + " " + r.owner());
            }
            if ("移行済み".equals(r.state())) {
                if ("未分類".equals(r.classification())) {
                    errors.add("移行済みなのに未分類: " + r.id());
                }
                if (r.testName().isBlank() || !tests.exists(r.testName())) {
                    errors.add("移行済みのテスト名が無い・実在しない: " + r.id() + " " + r.testName());
                }
            }
        }
        return errors;
    }

    // ───────── Matrix 表 ─────────

    /** パッケージ → Matrix 表に載っている補助 ID。{@code _} で始まるファイルは自己検査用で対象外。 */
    static Map<String, Set<String>> readMatrix(Path dir) {
        Map<String, Set<String>> result = new TreeMap<>();
        if (!Files.isDirectory(dir)) {
            return result;
        }
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith("_")) {
                    continue;
                }
                result.put(name.substring(0, name.length() - ".yaml".length()), ScopeContentMatrixTable.load(file).ids());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    static List<String> compareMatrixAndLedger(Map<String, Set<String>> matrix, List<LedgerRow> ledger) {
        List<String> errors = new ArrayList<>();
        Map<String, LedgerRow> byId = new LinkedHashMap<>();
        ledger.forEach(r -> byId.put(r.id(), r));
        Set<String> inMatrix = new TreeSet<>();
        matrix.forEach((pkg, ids) -> {
            for (String id : ids) {
                inMatrix.add(id);
                LedgerRow row = byId.get(id);
                if (row == null) {
                    errors.add("Matrix 表（" + pkg + "）の行が台帳に無い: " + id);
                }
            }
        });
        for (LedgerRow r : ledger) {
            if ("移行済み".equals(r.state()) && "HTTP".equals(r.kind()) && MATRIX_REQUIRED.contains(r.classification())
                    && !inMatrix.contains(r.id())) {
                errors.add("移行済みの読み取り経路が Matrix 表に無い: " + r.id());
            }
        }
        return errors;
    }

    // ───────── 検証事項表（V 番号） ─────────

    record VerificationRow(String id, String ac, String content, String owner, String testName, String state) { }

    static List<VerificationRow> readVerification(Path file) {
        List<VerificationRow> rows = new ArrayList<>();
        for (String line : ScopeContentAccessGateCoverageTest.readLines(file)) {
            if (line.isBlank() || line.startsWith("#") || line.startsWith("検証ID")) {
                continue;
            }
            String[] c = line.split("\t", -1);
            if (c.length != 6) {
                rows.add(new VerificationRow("列数不正:" + line, "", "", "", "", ""));
                continue;
            }
            rows.add(new VerificationRow(c[0].strip(), c[1].strip(), c[2].strip(), c[3].strip(), c[4].strip(),
                    c[5].strip()));
        }
        return rows;
    }

    static List<String> compareVerification(List<VerificationRow> rows, TestSourceIndex tests) {
        List<String> errors = new ArrayList<>();
        Set<String> ids = new TreeSet<>();
        for (VerificationRow v : rows) {
            Matcher m = V_ID.matcher(v.id());
            if (!m.matches()) {
                errors.add("V 番号の形が不正: " + v.id());
                continue;
            }
            if (!ids.add(v.id())) {
                errors.add("V 番号の重複: " + v.id());
            }
            if (!AC_ID.matcher(v.ac()).matches() || !v.ac().equals("AC-0159-" + m.group(1))) {
                errors.add("V 番号と AC 番号が対応しない: " + v.id() + " " + v.ac());
            }
            if (!Set.of("未実装", "実装済み").contains(v.state())) {
                errors.add("状態が不正: " + v.id() + " " + v.state());
            }
            if ("実装済み".equals(v.state())) {
                if (!tests.exists(v.testName())) {
                    errors.add("実装済みのテストが実在しない: " + v.id() + " " + v.testName());
                } else if (!tests.sourceOf(v.testName()).contains(v.id())) {
                    errors.add("テストのソースに V 番号が書かれていない: " + v.id() + " " + v.testName());
                }
            }
        }
        for (String mentioned : tests.mentionedVerificationIds()) {
            if (!ids.contains(mentioned)) {
                errors.add("テストにだけある V 番号（検証事項表に無い）: " + mentioned);
            }
        }
        return errors;
    }

    // ───────── テストソースの索引 ─────────

    /** テストソース（単純クラス名 → ソース本文）。{@code Class#method} の実在確認と V 番号の抽出に使う。 */
    record TestSourceIndex(Map<String, String> sources) {

        static TestSourceIndex load() {
            Map<String, String> map = new HashMap<>();
            try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String name = file.getFileName().toString();
                    map.put(name.substring(0, name.length() - ".java".length()),
                            Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new TestSourceIndex(map);
        }

        boolean exists(String testName) {
            if (testName == null || testName.isBlank()) {
                return false;
            }
            int hash = testName.indexOf('#');
            String cls = hash < 0 ? testName : testName.substring(0, hash);
            String source = sources.get(cls);
            if (source == null) {
                return false;
            }
            // 日本語のメソッド名は \\b（ASCII の単語境界）では区切れないため、直前が識別子文字でないことで判定する
            return hash < 0 || Pattern.compile("(?<![\\p{L}\\p{N}_$])" + Pattern.quote(testName.substring(hash + 1))
                    + "\\s*\\(").matcher(source).find();
        }

        String sourceOf(String testName) {
            int hash = testName.indexOf('#');
            return sources.getOrDefault(hash < 0 ? testName : testName.substring(0, hash), "");
        }

        Set<String> mentionedVerificationIds() {
            Set<String> ids = new TreeSet<>();
            sources.forEach((cls, src) -> {
                if (cls.equals(ScopeContentRouteLedgerCoverageTest.class.getSimpleName())) {
                    // 番人自身の自己検査用ダミーの V 番号は数えない（本テストの V-0159-107-* は表に載せる）
                    Matcher own = Pattern.compile("@DisplayName\\(\"(V-0159-[0-9a-z]+-\\d+)").matcher(src);
                    while (own.find()) {
                        ids.add(own.group(1));
                    }
                    return;
                }
                Matcher m = V_ID.matcher(src);
                while (m.find()) {
                    ids.add(m.group());
                }
            });
            return ids;
        }
    }

    // ───────── 初期値（再生成で新規行に入れる分類・担当 PR。§20.5.2 と陣立て書 §2 の割当） ─────────

    static final class LedgerDefaults {

        private static final Map<String, String> OWNER = new HashMap<>();
        private static final Map<String, String> CLASSIFICATION = new HashMap<>();

        static {
            owner("PR-D1", "bulletin", "filesharing");
            owner("PR-D2", "timeline", "schedule", "cms", "gallery");
            owner("PR-D3", "survey", "circulation", "knowledgebase", "todo", "forms");
            owner("PR-D4", "chat", "dashboard", "corkboard", "match", "digest", "effortrecord");
            owner("PR-A1", "activity");
            owner("PR-S", "search");
            owner("PR-P", "signage");
            owner("PR-W1", "resident", "property", "repairplan", "parking");
            owner("PR-W2", "equipment", "facility", "reservation", "queue", "ticket", "incident", "safetycheck");
            owner("PR-W3", "family", "member", "memberinfo", "membership", "school");
            owner("PR-W4", "payment", "budget", "receipt");
            owner("PR-W5", "shift", "shiftbudget");
            owner("PR-W6", "performance", "service", "chart", "gamification");
            owner("PR-W7", "notification", "promotion", "directmail", "line");
            owner("PR-W8", "workflow", "proxyvote", "disclosure", "timetable");
            owner("PR-W9", "tournament", "recruitment", "matching", "jobmatching", "event");
            owner("PR-W10", "analytics", "template", "translation", "team", "social");
            for (String exempt : List.of("admin", "auth", "billing", "committee", "joinrequest", "organization",
                    "role", "village")) {
                CLASSIFICATION.put(exempt, "対象外");
                OWNER.putIfAbsent(exempt, "PR-Z");
            }
        }

        private static void owner(String pr, String... packages) {
            for (String p : packages) {
                OWNER.put(p, pr);
                CLASSIFICATION.putIfAbsent(p, "門");
            }
        }

        static String owner(CodeRoute r) {
            return switch (r.category()) {
                case "public" -> "PR-P";
                case "aud" -> "PR-N";
                case "ws" -> "PR-WS";
                default -> OWNER.getOrDefault(r.pkg(), "PR-Z");
            };
        }

        static String classification(CodeRoute r) {
            return switch (r.category()) {
                case "aud" -> "宛先";
                case "ws" -> "WS";
                case "public" -> "未分類";
                default -> CLASSIFICATION.getOrDefault(r.pkg(), "未分類");
            };
        }
    }

    private static String idPrefixOf(String id) {
        Matcher m = ROUTE_ID.matcher(id);
        return m.matches() ? m.group(1) : id;
    }

    private static int idNoOf(String id) {
        Matcher m = ROUTE_ID.matcher(id);
        return m.matches() ? Integer.parseInt(m.group(2)) : 0;
    }
}
