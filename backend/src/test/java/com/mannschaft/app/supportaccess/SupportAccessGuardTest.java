package com.mannschaft.app.supportaccess;

import com.mannschaft.app.supportaccess.filter.SupportAccessPathPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役Z-B 試練: 番人（ソース・クラスパス走査）。
 *
 * <ul>
 *   <li>S4: download-url・export・csv・pdf を含む GET エンドポイントはすべて代理中に拒否される（新規追加も自動で捕捉）</li>
 *   <li>S13: AdminImpersonationFilter と旧ヘッダー・旧属性 originalAdminId が本番コードに残らない（SupportAccessContext へ移る）</li>
 *   <li>S14: GET の副作用（閲覧数・既読）を持つ Service は洗い出し表に載り、代理中のスキップ/拒否を実装している</li>
 *   <li>S15: AccountPurgeService・GdprPurgeRetryService に supportaccess を登録し、GDPR エクスポートが本人の閲覧記録を含む</li>
 * </ul>
 */
@DisplayName("戦役Z-B サポート閲覧の番人")
class SupportAccessGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/mannschaft/app");

    private static List<Path> mainJavaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rel(Path p) {
        return MAIN.relativize(p).toString().replace('\\', '/');
    }

    // ─── S4 ─────────────────────────────────────────────

    private static final Pattern EXPORT_LIKE = Pattern.compile("(?i)(download|export|csv|pdf)");

    @Test
    @DisplayName("S4 download-url・export・csv・pdf を含む GET エンドポイントはすべて代理中に 004 で拒否される")
    void S4_書出し系GETはすべて代理中に拒否() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> exportLikePaths = new TreeSet<>();
        for (var bd : scanner.findCandidateComponents("com.mannschaft.app")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            List<String> bases = classMapping == null || classMapping.path().length == 0
                    ? List.of("") : Arrays.asList(classMapping.path());
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null) {
                    continue;
                }
                boolean isGet = mm.method().length == 0 || Arrays.asList(mm.method()).contains(RequestMethod.GET)
                        || AnnotatedElementUtils.hasAnnotation(m, GetMapping.class);
                if (!isGet) {
                    continue;
                }
                List<String> subs = mm.path().length == 0 ? List.of("") : Arrays.asList(mm.path());
                for (String base : bases) {
                    for (String sub : subs) {
                        String full = (base + "/" + sub).replaceAll("/+", "/").replaceAll("/$", "");
                        if (EXPORT_LIKE.matcher(full).find()) {
                            exportLikePaths.add(full);
                        }
                    }
                }
            }
        }
        assertThat(exportLikePaths).as("走査が空なら番人が壊れている").hasSizeGreaterThan(10);

        List<String> allowed = new ArrayList<>();
        for (String pattern : exportLikePaths) {
            String concrete = pattern.replaceAll("\\{[^}]+}", "1");
            if (!SupportAccessPathPolicy.isForbiddenDuringSupportAccess(concrete)) {
                allowed.add(pattern + " (" + concrete + ")");
            }
        }
        assertThat(allowed).as("代理中に通ってしまう書出し系 GET（SupportAccessPathPolicy で拒否すること）").isEmpty();
    }

    // ─── S13 ────────────────────────────────────────────

    @Test
    @DisplayName("S13 旧 AdminImpersonationFilter は存在せず、SecurityConfig は SupportAccessFilter を登録する")
    void S13_旧フィルターは存在しない() throws Exception {
        assertThat(MAIN.resolve("admin/filter/AdminImpersonationFilter.java")).doesNotExist();
        List<String> referencing = mainJavaFiles().stream()
                .filter(p -> read(p).contains("AdminImpersonationFilter"))
                .map(SupportAccessGuardTest::rel)
                .toList();
        assertThat(referencing).as("AdminImpersonationFilter への参照").isEmpty();
        String securityConfig = read(MAIN.resolve("config/SecurityConfig.java"));
        assertThat(securityConfig).contains("SupportAccessFilter").doesNotContain("adminImpersonationFilter");
    }

    @Test
    @DisplayName("S13 旧ヘッダー名の文字列は SupportAccessHeaders（400 判定用）以外に無い")
    void S13_旧ヘッダーの文字列は残らない() throws Exception {
        List<String> referencing = mainJavaFiles().stream()
                .filter(p -> read(p).contains("X-Admin-Impersonate-User-Id"))
                .map(SupportAccessGuardTest::rel)
                .filter(r -> !r.equals("supportaccess/SupportAccessHeaders.java"))
                .toList();
        assertThat(referencing).isEmpty();
    }

    @Test
    @DisplayName("S13 旧リクエスト属性 originalAdminId の参照（BlogPostController・PersonalBlogController 等）は SupportAccessContext へ移る")
    void S13_旧属性originalAdminIdは残らない() throws Exception {
        Pattern attr = Pattern.compile("(get|set)Attribute\\(\\s*\"originalAdminId\"|ATTR_ORIGINAL_ADMIN_ID");
        List<String> referencing = mainJavaFiles().stream()
                .filter(p -> attr.matcher(read(p)).find())
                .map(SupportAccessGuardTest::rel)
                .toList();
        assertThat(referencing).as("request 属性 originalAdminId を読む/書く箇所").isEmpty();
    }

    @Test
    @DisplayName("S13 旧エラーコード ADMIN_007/ADMIN_008（変身用）は廃番")
    void S13_旧エラーコードは廃番() {
        String adminErrorCode = read(MAIN.resolve("admin/AdminErrorCode.java"));
        assertThat(adminErrorCode).doesNotContain("IMPERSONATION_SYSTEM_ADMIN_ONLY").doesNotContain("IMPERSONATION_TARGET_INVALID");
    }

    @Test
    @DisplayName("S13/S16 旧 FE ストア useAdminImpersonationStore は存在しない")
    void S13_旧FEストアは存在しない() {
        assertThat(Paths.get("../frontend/app/stores/useAdminImpersonationStore.ts")).doesNotExist();
        assertThat(Paths.get("../frontend/app/stores/useSupportAccessStore.ts")).exists();
    }

    // ─── S14 ────────────────────────────────────────────

    /** GET から呼ばれうる副作用の目印（閲覧数・既読・閲覧監査）。 */
    private static final Pattern GET_SIDE_EFFECT = Pattern.compile(
            "\\b(increment\\w*(View|Read)\\w*|markAs(Read|Viewed)\\w*|record\\w*Viewed\\w*|\\w*_VIEWED\\b)");

    private static final String INVENTORY = "/support_access_guard/get_side_effect_inventory.txt";

    @Test
    @DisplayName("S14 GET の副作用の目印を持つ Service はすべて洗い出し表（SKIP/DENY 分類）に載る")
    void S14_GET副作用は洗い出し表に載る() throws Exception {
        Set<String> inventory = loadInventory();
        List<String> missing = mainJavaFiles().stream()
                .filter(p -> p.getFileName().toString().endsWith("Service.java"))
                .filter(p -> GET_SIDE_EFFECT.matcher(read(p)).find())
                .map(SupportAccessGuardTest::rel)
                .filter(r -> !inventory.contains(r))
                .toList();
        assertThat(missing).as("洗い出し表に無い GET 副作用の候補（分類して載せること）").isEmpty();
    }

    @Test
    @DisplayName("S14 洗い出し表で SKIP/DENY に分類した Service は代理中の判定（SupportAccessContextHolder か @SupportAccessDenied）を持つ")
    void S14_洗い出し表の各行は代理中の扱いを実装() throws Exception {
        List<String> notHandled = new ArrayList<>();
        for (String line : loadInventoryLines()) {
            String[] cols = line.split("\\|");
            assertThat(cols).as("書式: <相対パス>|<SKIP|DENY|NONE>|<理由>: " + line).hasSize(3);
            assertThat(cols[1].trim()).isIn("SKIP", "DENY", "NONE");
            if (cols[1].trim().equals("NONE")) {
                assertThat(cols[2].trim()).as("NONE は GET から呼ばれない理由が必須: " + line).isNotBlank();
                continue;
            }
            String src = read(MAIN.resolve(cols[0].trim()));
            if (!src.contains("SupportAccessContextHolder") && !src.contains("SupportAccessDenied")) {
                notHandled.add(cols[0].trim());
            }
        }
        assertThat(notHandled).isEmpty();
    }

    @Test
    @DisplayName("S14 既知の GET 副作用（KbPageService の閲覧数・BillingInvoiceHistoryService の閲覧監査）は洗い出し表に載る")
    void S14_既知の副作用は載る() throws Exception {
        Set<String> inventory = loadInventory();
        Set<String> knownFiles = new LinkedHashSet<>();
        for (Path p : mainJavaFiles()) {
            String name = p.getFileName().toString();
            if (name.equals("KbPageService.java") || name.equals("BillingInvoiceHistoryService.java")) {
                knownFiles.add(rel(p));
            }
        }
        assertThat(knownFiles).isNotEmpty();
        assertThat(inventory).containsAll(knownFiles);
    }

    private static List<String> loadInventoryLines() throws IOException {
        try (InputStream in = SupportAccessGuardTest.class.getResourceAsStream(INVENTORY)) {
            assertThat(in).as("洗い出し表 " + INVENTORY + " が無い").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .toList();
        }
    }

    private static Set<String> loadInventory() throws IOException {
        return loadInventoryLines().stream().map(l -> l.split("\\|")[0].trim()).collect(Collectors.toSet());
    }

    // ─── S15 ────────────────────────────────────────────

    @Test
    @DisplayName("S15 AccountPurgeService と GdprPurgeRetryService の対象ドメインに supportaccess を登録する")
    void S15_パージ対象にsupportaccess() {
        for (String file : List.of("gdpr/service/AccountPurgeService.java", "gdpr/service/GdprPurgeRetryService.java")) {
            assertThat(read(MAIN.resolve(file))).as(file).contains("\"supportaccess\"");
        }
    }

    @Test
    @DisplayName("S15 GDPR エクスポート（PersonalDataCollector）は本人の閲覧記録を含める")
    void S15_GDPRエクスポートに閲覧記録() {
        String collector = read(MAIN.resolve("gdpr/service/PersonalDataCollector.java"));
        Matcher m = Pattern.compile("SupportAccessPersonalDataService").matcher(collector);
        assertThat(m.find()).as("PersonalDataCollector が SupportAccessPersonalDataService を使う").isTrue();
    }
}
