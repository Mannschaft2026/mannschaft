package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 番人: 「実装済みなのに画面から到達できないページ」の再発を機械的に止める（CMP-260909-1141 Phase 5）。
 *
 * <h2>背景 — この戦役の実作業で判明した5類型</h2>
 * <p>単純なリンク走査（「リンクが0本ならred」）だけでは足りないことが実測で分かっている。</p>
 * <table>
 *   <tr><th>型</th><th>実例</th><th>単純走査</th><th>本番人</th></tr>
 *   <tr><td>① リンクが無い</td><td>Phase 1〜4 で導線を足した18枚</td><td>○ 拾える</td><td>扱う（reachable）</td></tr>
 *   <tr><td>② リンクはあるが条件で永久に消える</td>
 *       <td>DEFAULT モジュール条件でサイドバー27項目が永久非表示（#3322で根治）</td>
 *       <td>✗ 定義はあるので素通り</td><td><b>扱わない — 別番人を後続で作る</b></td></tr>
 *   <tr><td>③ スコープ配下に同機能ページがある残骸</td>
 *       <td>{@code /admin/equipment} 等（CMP-260917-0041・CMP-260912-0911）</td>
 *       <td>✗ 削除すべきか導線を足すべきか区別できない</td><td>扱う（duplicate-remnant）</td></tr>
 *   <tr><td>④ BE が無く到達しても動かない</td><td>{@code /admin/campaigns} 等（CMP-260912-0909系）</td>
 *       <td>✗ 導線を足すと壊れた画面に案内する</td><td>扱う（be-pending）</td></tr>
 *   <tr><td>⑤ 走査後に他セッションが導線を足した</td>
 *       <td>{@code /my/shift-availability}（PR #3183 が先行していたのに「0件」と誤検出）</td>
 *       <td colspan="2">✗ 番人では原理的に防げない。<b>運用ルール</b>: 着手直前に最新 main で再確認すること</td></tr>
 * </table>
 *
 * <h2>設計方針 — 自動分類させない</h2>
 * <p>ページの同一性判定は脆く、この戦役でも家老が2度誤報した
 * （member-profiles を誤って「組織で壊れる」と報告／bulletin-categories を誤って「認可欠陥あり」と報告。
 * いずれも殿が実コードで確認して撤回）。<b>分類は人間が
 * {@code docs/inventory/page-reachability.yaml} に宣言し、本番人は「宣言が事実と食い違っていないか」
 * だけを検査する</b>。分類の4値（{@code reachable}/{@code duplicate-remnant}/{@code be-pending}/
 * {@code intentional-direct-only}）はすべて reason 必須。</p>
 *
 * <h2>検査範囲 — prefix ではなく exact-match の追跡リスト（段階導入）</h2>
 * <p>{@code frontend/app/pages/} は500ファイル超あり、実測できたのは30ルートのみ。
 * ディレクトリ prefix（{@code /admin/} 配下すべて等）で網羅性を取る案は、今回調査していない
 * 20枚超の別ページを巻き込み無審査 red の山になるため採用しなかった。台帳の
 * {@code tracked_paths}（exact-match の30パス）に対してのみ、双方向の陳腐化
 * （宣言されているが台帳に無い／台帳にあるが実在しない）を検査する。</p>
 *
 * <h2>リンク抽出の限界と逃げ道</h2>
 * <p>静的走査はすべての書き方を拾えない（動的テンプレートリテラルの先頭が変数のケース等）。
 * 拾えないことを理由に偽陽性（実際はリンクがあるのに red）が出る場合は、
 * {@code intentional-direct-only} へ落とすのではなく、分類はそのまま {@code reachable}/
 * {@code duplicate-remnant} を保ちつつ台帳の {@code link_detection_note} に理由を書いて
 * その1件だけ抑制する（偽陽性でうるさい番人は無効化されるため、実際的な逃げ道を用意する）。</p>
 *
 * <h2>本番人は ArchUnit ではない</h2>
 * <p>YAML・.vue・.ts のソース走査であり、凍結ストアは一切使わない
 * （{@code --tests} 絞り込み実行で免責が静かに消える既知事故を構造的に避けるため）。</p>
 */
@DisplayName("番人: 孤立ページ（到達不能ページ）の再発防止")
class OrphanPageGuardTest {

    private static final Path INVENTORY = Paths.get("docs", "inventory", "page-reachability.yaml");
    private static final Path PAGES_DIR = Paths.get("frontend", "app", "pages");
    private static final Path SCAN_DIR = Paths.get("frontend", "app");

    private static final Set<String> VALID_CLASSIFICATIONS =
            Set.of("reachable", "duplicate-remnant", "be-pending", "intentional-direct-only");

    /** {@code to:}/{@code to=}/{@code absolutePath:} の値を抽出する（NuxtLink・カード配列・quickLinks 共通）。 */
    private static final Pattern TO_OR_ABSOLUTE_PATH =
            Pattern.compile("\\b(?:to|absolutePath)\\s*[:=]\\s*[\"'`]([^\"'`]*)[\"'`]");

    /** {@code navigateTo('...')} / {@code router.push('...')} の値を抽出する。 */
    private static final Pattern NAVIGATE =
            Pattern.compile("(?:navigateTo|router\\.push)\\(\\s*[\"'`]([^\"'`]*)[\"'`]");

    /** TeamSidebar/OrganizationSidebar の {@code path: '...'}（スコープ相対）を抽出する。 */
    private static final Pattern PATH_FIELD = Pattern.compile("\\bpath\\s*:\\s*['\"]([^'\"]*)['\"]");

    /** スコープ相対 {@code path:} を絶対ルートへ変換するための、ファイル名→prefix 対応。 */
    private static final Map<String, String> SIDEBAR_SCOPE_PREFIX = Map.of(
            "TeamSidebar.vue", "/teams/[slug]/",
            "OrganizationSidebar.vue", "/organizations/[slug]/"
    );

    private static final Pattern CMP_ID = Pattern.compile("^CMP-\\d{6}-\\d{4}$|^CMP-\\d{3,}$");

    @Test
    @DisplayName("追跡パスの網羅性・実在性・宣言妥当性を検査する")
    void 追跡パスの宣言と実態が一致していること() throws IOException {
        Path inventory = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(INVENTORY);
        Path pagesDir = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(PAGES_DIR);
        Path scanDir = FeatureGateRouteMapGuardTest.resolveFromRepoRoot(SCAN_DIR);

        assertThat(Files.isRegularFile(inventory)).as("台帳が見つからない: " + inventory).isTrue();
        assertThat(Files.isDirectory(pagesDir)).as("FE ページディレクトリが見つからない: " + pagesDir).isTrue();
        assertThat(Files.isDirectory(scanDir)).as("FE 走査ディレクトリが見つからない: " + scanDir).isTrue();

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(inventory)) {
            root = new Yaml().load(in);
        }

        @SuppressWarnings("unchecked")
        List<String> trackedPaths = (List<String>) root.get("tracked_paths");
        assertThat(trackedPaths)
                .as("tracked_paths が空 — 追跡対象が無いと本番人は何も検出できなくなる")
                .isNotEmpty();

        @SuppressWarnings("unchecked")
        List<Object> pageEntries = (List<Object>) root.get("pages");
        assertThat(pageEntries).as("pages が空").isNotEmpty();

        List<String> violations = new ArrayList<>();

        // pages: を path -> エントリ に変換しつつ、重複・理由・分類妥当性を検査する。
        Map<String, Map<String, Object>> pageByPath = new LinkedHashMap<>();
        for (Object o : pageEntries) {
            if (!(o instanceof Map)) {
                violations.add("pages の要素がマップでない: " + o);
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> e = (Map<String, Object>) o;
            String path = str(e.get("path"));
            if (path.isBlank()) {
                violations.add("pages に path の無いエントリがある");
                continue;
            }
            if (pageByPath.containsKey(path)) {
                violations.add("pages に同一 path が重複している: " + path);
            }
            pageByPath.put(path, e);

            String reason = str(e.get("reason")).trim();
            if (reason.isEmpty()) {
                violations.add("分類の理由が無い: " + path + " — reason は全分類で必須");
            }

            String classification = str(e.get("classification"));
            if (!VALID_CLASSIFICATIONS.contains(classification)) {
                violations.add("未知の分類: " + path + " -> " + classification
                        + "（許容値: " + VALID_CLASSIFICATIONS + "）");
            }
        }

        // tracked_paths <-> pages: の双方向一致（宣言漏れ・宣言過多の両方を陳腐化として検出）。
        Set<String> trackedSet = new LinkedHashSet<>(trackedPaths);
        for (String t : trackedSet) {
            if (!pageByPath.containsKey(t)) {
                violations.add("追跡対象だが台帳(pages:)に分類が無い: " + t
                        + " — page-reachability.yaml の pages: に分類を追加すること");
            }
        }
        for (String p : pageByPath.keySet()) {
            if (!trackedSet.contains(p)) {
                violations.add("台帳(pages:)にあるが tracked_paths に載っていない: " + p
                        + " — tracked_paths に追記すること（両者は1対1で保つ設計）");
            }
        }

        // FE ページ実体からルートを復元する。
        List<Path> vueFiles;
        try (Stream<Path> walk = Files.walk(pagesDir)) {
            vueFiles = walk.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".vue"))
                    .sorted()
                    .toList();
        }
        Set<String> existingRoutes = new LinkedHashSet<>();
        for (Path f : vueFiles) {
            existingRoutes.add(FeatureGatePageCoverageGuardTest.toRoutePath(pagesDir.relativize(f)));
        }
        assertThat(existingRoutes)
                .as("自己検証: FE ページ実体からルートが1件も復元できなかった — 走査が壊れている")
                .isNotEmpty();

        // リンク（導線）をアプリ全体から抽出する。
        List<Path> scanFiles;
        try (Stream<Path> walk = Files.walk(scanDir)) {
            scanFiles = walk.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.endsWith(".vue") || n.endsWith(".ts");
                    })
                    .toList();
        }
        Set<String> reachableLinks = new LinkedHashSet<>();
        for (Path f : scanFiles) {
            String content = Files.readString(f, StandardCharsets.UTF_8);
            reachableLinks.addAll(extractLinks(f.getFileName().toString(), content));
        }
        assertThat(reachableLinks)
                .as("自己検証: アプリ全体からリンクが1件も抽出できなかった — 走査が壊れている")
                .isNotEmpty();
        // 動的ルート（[slug] 等）とテンプレートリテラルの動的セグメント（${id}）を同じ [param] に揃えて照合する。
        Set<String> comparableLinks = new LinkedHashSet<>();
        for (String link : reachableLinks) comparableLinks.add(toComparable(link));

        // 分類ごとの検査。
        for (Map.Entry<String, Map<String, Object>> entry : pageByPath.entrySet()) {
            String path = entry.getKey();
            Map<String, Object> e = entry.getValue();
            String classification = str(e.get("classification"));

            if (!existingRoutes.contains(path)) {
                violations.add("台帳(" + classification + ")にあるが該当ページが実在しない: " + path
                        + " — ページが削除されたなら台帳からも削除すること（陳腐化）");
                continue; // 実体が無ければ以降の個別検査は無意味
            }

            switch (classification) {
                case "reachable" -> {
                    boolean suppressed = !str(e.get("link_detection_note")).isBlank();
                    if (!comparableLinks.contains(toComparable(path)) && !suppressed) {
                        violations.add("reachable と宣言されているが、当該パスへのリンクが見つからない: " + path
                                + " — 導線が消えた（退行）か、走査で拾えない書き方に変わった可能性がある。"
                                + "後者なら link_detection_note に理由を書いて抑制すること");
                    }
                }
                case "duplicate-remnant" -> {
                    String replacement = str(e.get("replacement")).trim();
                    boolean suppressed = !str(e.get("link_detection_note")).isBlank();
                    if (replacement.isEmpty()) {
                        violations.add("duplicate-remnant だが replacement が無い: " + path);
                    } else {
                        if (!existingRoutes.contains(replacement)) {
                            violations.add("duplicate-remnant の replacement が実在しない: " + path
                                    + " -> " + replacement + "（代替ページが削除された可能性がある）");
                        }
                        if (!comparableLinks.contains(toComparable(replacement)) && !suppressed) {
                            violations.add("duplicate-remnant の replacement へのリンクが見つからない: " + path
                                    + " -> " + replacement
                                    + "（走査で拾えない書き方なら link_detection_note に理由を書いて抑制すること）");
                        }
                    }
                }
                case "be-pending" -> {
                    String cmp = str(e.get("cmp")).trim();
                    if (cmp.isEmpty() || !CMP_ID.matcher(cmp).matches()) {
                        violations.add("be-pending だが cmp（対応する CMP 番号）が無いか形式が不正: " + path
                                + " -> '" + cmp + "'");
                    }
                }
                case "intentional-direct-only" -> {
                    // reason 必須の検査は上で全分類共通に実施済み。追加検査は無い。
                }
                default -> {
                    // 未知分類は上で既に violations 済み。
                }
            }
        }

        if (violations.isEmpty()) return;

        StringBuilder sb = new StringBuilder();
        sb.append("孤立ページ台帳の宣言と実態が食い違っています。\n")
                .append("本番人の検出を緩めて通すことは禁止。page-reachability.yaml か FE 実装を直すこと。\n")
                .append("違反一覧:\n");
        for (String v : violations) sb.append("  x ").append(v).append("\n");
        assertThat(violations).as(sb.toString()).isEmpty();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * 1ファイルの中身から抽出できるリンク候補（正規化済み）の集合を返す。
     *
     * <p>{@code to:}/{@code to=}/{@code absolutePath:}・{@code navigateTo}/{@code router.push} は
     * ファイル種別を問わず抽出する。TeamSidebar.vue / OrganizationSidebar.vue に限り、
     * スコープ相対の {@code path: '...'} をスコープ prefix 付きの絶対パスへ変換して追加する
     * （{@code path: 'equipment'} は {@code /teams/[slug]/equipment} 相当の nuxt ページに対応する）。</p>
     */
    static Set<String> extractLinks(String fileName, String content) {
        Set<String> links = new LinkedHashSet<>();
        addMatches(links, TO_OR_ABSOLUTE_PATH, content);
        addMatches(links, NAVIGATE, content);

        String prefix = SIDEBAR_SCOPE_PREFIX.get(fileName);
        if (prefix != null) {
            Matcher m = PATH_FIELD.matcher(content);
            while (m.find()) {
                String value = m.group(1);
                if (value == null || value.isBlank()) continue;
                links.add(normalizeLink(prefix + value));
            }
        }
        return links;
    }

    private static void addMatches(Set<String> links, Pattern pattern, String content) {
        Matcher m = pattern.matcher(content);
        while (m.find()) {
            String raw = m.group(1);
            if (raw == null || raw.isBlank() || !raw.startsWith("/")) continue;
            links.add(normalizeLink(raw));
        }
    }

    /** パスセグメント全体が {@code ${...}} の動的部分（{@code /scopes/${type}/${id}/no-shows} の {@code ${type}} 等）。 */
    private static final Pattern DYNAMIC_SEGMENT = Pattern.compile("(?<=/)\\$\\{[^}]*\\}(?=/|$)");

    /** Nuxt の動的ルートのセグメント（{@code [slug]}・{@code [scopeId]} 等。{@code [...all]} も含む）。 */
    private static final Pattern ROUTE_PARAM_SEGMENT = Pattern.compile("(?<=/)\\[[^/\\]]+\\](?=/|$)");

    /** 動的セグメントを区別せず照合するための共通トークン。 */
    private static final String PARAM_TOKEN = "[param]";

    /**
     * リンク文字列を比較可能な形へ正規化する。
     *
     * <ul>
     *   <li>クエリ文字列（{@code ?scope=...}）は切り落とす。</li>
     *   <li>パスセグメント全体がテンプレートリテラルの動的部分（{@code /${id}/}）のものは
     *       動的セグメント {@code [param]} に置き換え、以降のセグメントも残す
     *       （以前は {@code ${} 以降をすべて切り落としていたため、
     *       {@code /scopes/${type}/${id}/no-shows} が {@code /scopes} としか認識されず、
     *       動的ルートへの導線を検出できなかった）。</li>
     *   <li>セグメントの一部だけが動的（{@code /foo-${id}}・{@code /a/b${x}}）な場合は
     *       どのルートか確定できないため、その位置で切り落とす（従来どおり）。</li>
     *   <li>末尾スラッシュも除く。</li>
     * </ul>
     *
     * <p>Nuxt の動的ルート（{@code [slug]} 等）も同じ {@code [param]} に正規化するので、
     * リンク側とルート側を {@link #toComparable(String)} で揃えて照合できる。</p>
     */
    static String normalizeLink(String raw) {
        int q = raw.indexOf('?');
        String s = q >= 0 ? raw.substring(0, q) : raw;
        s = DYNAMIC_SEGMENT.matcher(s).replaceAll(PARAM_TOKEN);
        int d = s.indexOf("${");
        if (d >= 0) s = s.substring(0, d); // セグメント途中の動的部分は従来どおり切り落とし
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** ルート（{@code /scopes/[scopeType]/[scopeId]/no-shows}）を、正規化済みリンクと比較できる形へ揃える。 */
    static String toComparable(String route) {
        return ROUTE_PARAM_SEGMENT.matcher(route).replaceAll(PARAM_TOKEN);
    }

    // ------------------------------------------------------------------
    // 自己検証: 本番人が実際に欠陥を検出できることを合成入力で固定する。
    // ------------------------------------------------------------------

    @Test
    @DisplayName("自己検証: クエリ・テンプレートリテラルの動的部分を正しく切り落とすこと")
    void リンク正規化() {
        assertThat(normalizeLink("/admin/vendors?scope=teams&scopeId=1")).isEqualTo("/admin/vendors");
        assertThat(normalizeLink("/admin/vendors?scope=teams&scopeId=${props.teamId}")).isEqualTo("/admin/vendors");
        assertThat(normalizeLink("/admin/line-settings")).isEqualTo("/admin/line-settings");
        assertThat(normalizeLink("/settings/profile-visibility/")).isEqualTo("/settings/profile-visibility");
        assertThat(normalizeLink("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("自己検証: セグメント全体が動的な ${...} は [param] にして後続セグメントを残すこと")
    void リンク正規化_動的セグメント() {
        // 以前は ${ 以降を切り落とし、/scopes/ としか認識されず動的ルートへの導線を検出できなかった
        assertThat(normalizeLink("/scopes/${type}/${id}/no-shows")).isEqualTo("/scopes/[param]/[param]/no-shows");
        assertThat(normalizeLink("/teams/${props.slug}/settings/x?tab=${tab}")).isEqualTo("/teams/[param]/settings/x");
        assertThat(normalizeLink("/teams/${slug}")).isEqualTo("/teams/[param]");
        // セグメントの一部だけが動的なものはルートを確定できないため、従来どおりその位置で切り落とす
        assertThat(normalizeLink("/foo-${id}/bar")).isEqualTo("/foo-");
        assertThat(normalizeLink("/a/b${x}")).isEqualTo("/a/b");
        // 先頭が変数のリンクは addMatches が拾わない（従来どおり）
        assertThat(extractLinks("Hub.vue", "{ to: `${base}/settings/x` }")).isEmpty();
    }

    @Test
    @DisplayName("自己検証: Nuxt の動的ルートとテンプレートリテラルのリンクが同じ [param] 形で一致すること")
    void ルート照合_動的セグメント() {
        String route = "/scopes/[scopeType]/[scopeId]/no-shows";
        Set<String> links = extractLinks("AdminSettingsHub.vue",
                "{ absolutePath: `/scopes/${recruitmentScopeType.value}/${scopeNumericId.value}/no-shows`, title: 'x' }");
        Set<String> comparable = new LinkedHashSet<>();
        for (String l : links) comparable.add(toComparable(l));
        assertThat(comparable).contains(toComparable(route));
        // 別ルートへは一致しない（セグメント数・静的部分が違えば拾わない）
        assertThat(comparable).doesNotContain(toComparable("/scopes/[scopeType]/[scopeId]/penalty-settings"));
        assertThat(comparable).doesNotContain(toComparable("/scopes/[scopeType]/no-shows"));
        // 単一の [param] は [param] 化され、既存ルートの照合は変えない
        assertThat(toComparable("/teams/[slug]/equipment")).isEqualTo("/teams/[param]/equipment");
        // [...all] のような catch-all も同じ [param] 形になる
        assertThat(toComparable("/teams/[...all]")).isEqualTo("/teams/[param]");
    }

    @Test
    @DisplayName("自己検証: to: / absolutePath: / navigateTo / router.push を抽出できること")
    void リンク抽出_一般() {
        String content = """
                { label: 'x', to: '/me/payments/receipts' },
                { labelKey: 'y', absolutePath: `/admin/vendors?scope=teams&scopeId=${props.teamId}` },
                navigateTo('/my/shift-availability')
                router.push("/settings/profile-visibility")
                """;
        Set<String> links = extractLinks("SomeComponent.vue", content);
        assertThat(links).contains(
                "/me/payments/receipts",
                "/admin/vendors",
                "/my/shift-availability",
                "/settings/profile-visibility");
    }

    @Test
    @DisplayName("自己検証: TeamSidebar/OrganizationSidebar のスコープ相対 path: を絶対パスへ変換できること")
    void リンク抽出_サイドバー相対パス() {
        String teamSidebar = "{ labelKey: 'x', path: 'equipment', moduleSlug: 'equipment' }";
        assertThat(extractLinks("TeamSidebar.vue", teamSidebar)).contains("/teams/[slug]/equipment");

        String orgSidebar = "{ labelKey: 'y', path: 'member-profiles', moduleSlug: 'member_intro' }";
        assertThat(extractLinks("OrganizationSidebar.vue", orgSidebar)).contains("/organizations/[slug]/member-profiles");

        // 他のファイルでは同じ path: が拾われて誤った絶対パスを作らないこと
        // （スコープ prefix 対応表に無いファイル名では変換規則を適用しない）。
        assertThat(extractLinks("UnrelatedComponent.vue", teamSidebar)).doesNotContain("/teams/[slug]/equipment");
    }

    @Test
    @DisplayName("自己検証: 空文字列・相対パス・非パス文字列を誤ってリンクとして拾わないこと")
    void リンク抽出_境界() {
        String content = """
                { path: '', moduleSlug: null }
                { to: 'ちがう文字列' }
                """;
        Set<String> links = extractLinks("TeamSidebar.vue", content);
        // path: '' は空なので /teams/[slug]/ 単体を作らない。
        assertThat(links).doesNotContain("/teams/[slug]/");
        // '/' で始まらない to: は拾わない。
        assertThat(links).doesNotContain("ちがう文字列");
    }
}
