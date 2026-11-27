package com.mannschaft.app.supportaccess;

import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import com.mannschaft.app.supportaccess.filter.SupportAccessPathPolicy;
import com.mannschaft.app.supportaccess.web.SupportAccessDenied;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役Z-B 番人: 閲覧のみの強制（第1〜第3層）の取りこぼしを、ソース・クラスパス走査で検出する。
 *
 * <ul>
 *   <li>第2層: download / export / csv / pdf を含むパスの handler は、メソッドを問わず {@link SupportAccessDenied} を持つ</li>
 *   <li>第2層: 応答 DTO に資格情報（token・secret・password・apiKey・signedUrl 系）の文字列フィールドを持つ GET は
 *       {@link SupportAccessDenied} を持つ（基準線なし。例外は理由付きの許可表 {@code credential_get_allowlist.txt}）</li>
 *   <li>第1層: SecurityConfig の SYSTEM_ADMIN 予約パスは、すべて {@link SupportAccessPathPolicy} が拒否する</li>
 *   <li>第3層: {@link SupportAccessWritable}（書込可能な接続の例外）は閲覧記録の部品にだけ付ける</li>
 *   <li>非同期: {@code CompletableFuture.supplyAsync/runAsync} は Executor を明示する（共有 ForkJoin プールには
 *       代理の文脈が伝播しない。基準線なし）</li>
 *   <li>フィルター順: SupportAccessFilter は ProxyInputContextFilter の後、保護者同意ゲート・レートリミットの前</li>
 * </ul>
 */
@DisplayName("戦役Z-B 閲覧のみの強制の番人")
class SupportAccessEnforcementGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/mannschaft/app");

    private static final Pattern EXPORT_LIKE = Pattern.compile("(?i)(download|export|csv|pdf)");

    @Test
    @DisplayName("第2層 書出し系パスの handler はすべて @SupportAccessDenied を持つ（Controller クラスまたはメソッド）")
    void 書出し系handlerに注釈が付いている() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (var bd : scanner.findCandidateComponents("com.mannschaft.app")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            List<String> bases = classMapping == null || classMapping.path().length == 0
                    ? List.of("") : Arrays.asList(classMapping.path());
            boolean classDenied = AnnotatedElementUtils.hasAnnotation(type, SupportAccessDenied.class);
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null) {
                    continue;
                }
                List<String> subs = mm.path().length == 0 ? List.of("") : Arrays.asList(mm.path());
                for (String base : bases) {
                    for (String sub : subs) {
                        String full = (base + "/" + sub).replaceAll("/+", "/");
                        if (!EXPORT_LIKE.matcher(full).find()) {
                            continue;
                        }
                        checked++;
                        boolean methodDenied = AnnotatedElementUtils.hasAnnotation(m, SupportAccessDenied.class);
                        if (!classDenied && !methodDenied) {
                            missing.add(type.getSimpleName() + "#" + m.getName() + " " + full);
                        }
                    }
                }
            }
        }
        assertThat(checked).as("走査が空なら番人が壊れている").isGreaterThan(40);
        assertThat(missing).as("@SupportAccessDenied の付与漏れ（書出し系の handler）").isEmpty();
    }

    @Test
    @DisplayName("第1層 SecurityConfig で SYSTEM_ADMIN 予約のパスは、すべて代理中の禁止パスに含まれる")
    void SYSTEM_ADMIN予約パスは禁止パスに含まれる() throws IOException {
        String securityConfig = Files.readString(MAIN.resolve("config/SecurityConfig.java"), StandardCharsets.UTF_8);
        TreeSet<String> reserved = systemAdminReservedPaths(securityConfig);
        assertThat(reserved).as("SecurityConfig の SYSTEM_ADMIN 予約パスを抽出できること")
                .contains("/api/v1/admin/stripe/**", "/api/v1/system-admin/**", "/api/v1/admin/age-group-settings/**");
        List<String> allowed = new ArrayList<>();
        for (String pattern : reserved) {
            String concrete = pattern.replace("/**", "/x").replaceAll("\\{[^}]+}", "1");
            if (!SupportAccessPathPolicy.isForbiddenDuringSupportAccess(concrete)) {
                allowed.add(pattern);
            }
        }
        assertThat(allowed)
                .as("SecurityConfig で SYSTEM_ADMIN 予約なのに代理中に通ってしまうパス（SupportAccessPathPolicy へ追加すること）")
                .isEmpty();
    }

    @Test
    @DisplayName("第1層 SYSTEM_ADMIN 予約の抽出は複数引数・HttpMethod 付き・hasAnyRole・hasAuthority・access() に対応する")
    void SYSTEM_ADMIN予約の抽出器の自己検査() {
        String sample = """
                .requestMatchers("/a/**", "/b/**").hasRole("SYSTEM_ADMIN")
                .requestMatchers(HttpMethod.GET, "/c/**").hasRole("SYSTEM_ADMIN")
                .requestMatchers(
                    "/d/**",
                    "/e/**"
                ).hasAnyRole("SYSTEM_ADMIN")
                .requestMatchers("/f/**").hasAuthority("ROLE_SYSTEM_ADMIN")
                .requestMatchers("/g/**").access(AuthorizationManagers.anyOf(hasRole("SYSTEM_ADMIN")))
                // .requestMatchers("/commented/**").hasRole("SYSTEM_ADMIN")
                .requestMatchers("/h/**").hasAnyRole("ADMIN", "SYSTEM_ADMIN")
                .requestMatchers("/i/**").hasRole("ADMIN")
                """;
        assertThat(systemAdminReservedPaths(sample))
                .containsExactly("/a/**", "/b/**", "/c/**", "/d/**", "/e/**", "/f/**", "/g/**");
    }

    /** {@code requestMatchers(...)} に続く認可が SYSTEM_ADMIN だけを許すパス（文字列リテラル）を抽出する。 */
    static TreeSet<String> systemAdminReservedPaths(String source) {
        String noComments = Arrays.stream(source.split("\n"))
                .map(l -> l.replaceFirst("^\\s*//.*$", ""))
                .collect(Collectors.joining("\n"));
        Matcher m = Pattern.compile(
                        "requestMatchers\\(([^()]*)\\)\\s*\\.(hasRole|hasAnyRole|hasAuthority|hasAnyAuthority|access)\\("
                                + "((?:[^()]|\\((?:[^()]|\\([^()]*\\))*\\))*)\\)")
                .matcher(noComments);
        Pattern literal = Pattern.compile("\"([^\"]*)\"");
        TreeSet<String> reserved = new TreeSet<>();
        while (m.find()) {
            List<String> roles = new ArrayList<>();
            Matcher r = literal.matcher(m.group(3));
            while (r.find()) {
                roles.add(r.group(1).replaceFirst("^ROLE_", ""));
            }
            if (roles.isEmpty() || !roles.stream().allMatch("SYSTEM_ADMIN"::equals)) {
                continue;
            }
            Matcher p = literal.matcher(m.group(1));
            while (p.find()) {
                if (p.group(1).startsWith("/")) {
                    reserved.add(p.group(1));
                }
            }
        }
        return reserved;
    }

    // ─── 第2層: 資格情報を返す GET ──────────────────────────────

    /** 資格情報とみなす応答フィールド名（大文字小文字を問わず部分一致）。 */
    private static final Pattern CREDENTIAL_FIELD = Pattern.compile(
            "(?i).*(token|secret|password|apikey|api_key|signedurl|presigned).*");

    private static final String CREDENTIAL_ALLOWLIST = "/support_access_guard/credential_get_allowlist.txt";

    @Test
    @DisplayName("第2層 応答 DTO（戻り値の型を再帰的に）に token・secret・password・apiKey・signedUrl 系の文字列フィールドを持つ GET は @SupportAccessDenied 必須")
    void 資格情報を返すGETには注釈が付いている() throws Exception {
        Map<String, String> allowlist = loadReasonedList(CREDENTIAL_ALLOWLIST);
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> missing = new ArrayList<>();
        Set<String> seenHandlers = new TreeSet<>();
        int credentialHandlers = 0;
        for (var bd : scanner.findCandidateComponents("com.mannschaft.app")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            if (!isMainSource(type)) {
                continue; // テスト用の fixture（src/test）は対象外
            }
            boolean classDenied = AnnotatedElementUtils.hasAnnotation(type, SupportAccessDenied.class);
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null || !isGetHandler(m, mm)) {
                    continue;
                }
                List<String> hits = new ArrayList<>();
                collectCredentialFields(ResolvableType.forMethodReturnType(m), "", new HashSet<>(), hits, 0);
                if (hits.isEmpty()) {
                    continue;
                }
                credentialHandlers++;
                String key = type.getSimpleName() + "#" + m.getName();
                seenHandlers.add(key);
                if (classDenied || AnnotatedElementUtils.hasAnnotation(m, SupportAccessDenied.class)
                        || allowlist.containsKey(key)) {
                    continue;
                }
                missing.add(key + " " + hits);
            }
        }
        assertThat(credentialHandlers).as("走査が空なら番人が壊れている").isGreaterThan(5);
        assertThat(missing).as("資格情報を返す GET の @SupportAccessDenied 付与漏れ（例外は理由付きで "
                + CREDENTIAL_ALLOWLIST + " へ）").isEmpty();
        List<String> stale = allowlist.keySet().stream().filter(k -> !seenHandlers.contains(k)).toList();
        assertThat(stale).as("例外表に残った、もう該当しない handler（表から消すこと）").isEmpty();
    }

    private static boolean isMainSource(Class<?> type) {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) {
            top = top.getEnclosingClass();
        }
        String rel = top.getName().substring("com.mannschaft.app.".length()).replace('.', '/') + ".java";
        return Files.exists(MAIN.resolve(rel));
    }

    private static boolean isGetHandler(Method m, RequestMapping mm) {
        return AnnotatedElementUtils.hasAnnotation(m, GetMapping.class) || mm.method().length == 0
                || Arrays.asList(mm.method()).contains(RequestMethod.GET);
    }

    /** 戻り値の型を再帰的にたどり、資格情報らしい文字列フィールドを集める（com.mannschaft 配下の型だけを展開）。 */
    private static void collectCredentialFields(ResolvableType type, String path, Set<String> visiting,
                                                List<String> hits, int depth) {
        if (depth > 8 || type == ResolvableType.NONE) {
            return;
        }
        if (type.isArray()) {
            collectCredentialFields(type.getComponentType(), path + "[]", visiting, hits, depth + 1);
            return;
        }
        Class<?> raw = type.resolve();
        if (raw == null) {
            return;
        }
        if (!raw.getName().startsWith("com.mannschaft.")) {
            // ResponseEntity・List・Optional・Page 等の外部型は型引数だけをたどる
            for (ResolvableType generic : type.getGenerics()) {
                collectCredentialFields(generic, path, visiting, hits, depth + 1);
            }
            return;
        }
        if (raw.isEnum()) {
            return;
        }
        String key = type.toString();
        if (!visiting.add(key)) {
            return;
        }
        for (Class<?> c = raw; c != null && c.getName().startsWith("com.mannschaft."); c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                    continue;
                }
                ResolvableType fieldType = ResolvableType.forField(f, type);
                Class<?> fieldRaw = fieldType.resolve();
                if (fieldRaw != null && isCredentialCarrier(fieldRaw) && CREDENTIAL_FIELD.matcher(f.getName()).matches()) {
                    hits.add(path + "." + f.getName());
                }
                collectCredentialFields(fieldType, path + "." + f.getName(), visiting, hits, depth + 1);
            }
        }
        visiting.remove(key);
    }

    /** 資格情報を運びうる型（文字列・URI・バイト列）。真偽値・件数・日時のフィールド（hasPassword 等）は対象外。 */
    private static boolean isCredentialCarrier(Class<?> fieldType) {
        return CharSequence.class.isAssignableFrom(fieldType) || fieldType == java.net.URI.class
                || fieldType == java.net.URL.class || fieldType == byte[].class || fieldType == char[].class;
    }

    // ─── 非同期: 代理文脈の伝播 ─────────────────────────────

    @Test
    @DisplayName("非同期 CompletableFuture.supplyAsync/runAsync は Executor を明示する（ForkJoin 共有プールには代理の文脈が伝播しない）")
    void Executor未指定のsupplyAsyncとrunAsyncは禁止() throws IOException {
        Pattern call = Pattern.compile("\\b(supplyAsync|runAsync)\\s*\\(");
        List<String> violations = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> s = Files.walk(MAIN)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = readUnchecked(p);
                Matcher m = call.matcher(src);
                while (m.find()) {
                    checked++;
                    List<String> args = topLevelArguments(src, m.end());
                    if (args.size() < 2) {
                        int line = (int) src.substring(0, m.start()).chars().filter(ch -> ch == '\n').count() + 1;
                        violations.add(MAIN.relativize(p).toString().replace('\\', '/') + ":" + line);
                    }
                }
            }
        }
        assertThat(checked).as("走査が空なら番人が壊れている（ダッシュボードの並列集計は supplyAsync を使う）").isGreaterThan(10);
        assertThat(violations).as("Executor 未指定の supplyAsync/runAsync（文脈を伝播する Executor を第2引数で渡すこと）").isEmpty();
    }

    /** {@code (} の直後から、対応する {@code )} までの最上位の引数を返す（文字列・括弧の入れ子を考慮）。 */
    static List<String> topLevelArguments(String src, int start) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        for (int i = start; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    current.append(src.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '(' || c == '{' || c == '[') {
                depth++;
            } else if (c == ')' || c == '}' || c == ']') {
                if (depth == 0) {
                    if (!current.toString().isBlank()) {
                        args.add(current.toString().trim());
                    }
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        return args;
    }

    @Test
    @DisplayName("非同期 引数解析の自己検査（ラムダ内のカンマ・文字列内の括弧に惑わされない）")
    void 引数解析の自己検査() {
        String src = "supplyAsync(() -> f(a, \"x)\"), executor); runAsync(() -> { g(1, 2); });";
        assertThat(topLevelArguments(src, src.indexOf('(') + 1)).hasSize(2);
        assertThat(topLevelArguments(src, src.indexOf("runAsync(") + 9)).hasSize(1);
    }

    private static Map<String, String> loadReasonedList(String resource) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (InputStream in = SupportAccessEnforcementGuardTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource + " が無い").isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                String l = line.trim();
                if (l.isEmpty() || l.startsWith("#")) {
                    continue;
                }
                String[] cols = l.split("\\|", 2);
                assertThat(cols).as("書式: <Controller#method>|<理由>: " + l).hasSize(2);
                assertThat(cols[1].trim()).as("理由が必須: " + l).isNotBlank();
                entries.put(cols[0].trim(), cols[1].trim());
            }
        }
        return entries;
    }

    @Test
    @DisplayName("第3層 @SupportAccessWritable（書込可能な接続の例外）を付けてよいのは閲覧記録・監査（audit_logs と監査 outbox）・障害記録（error_reports）の書込口だけ")
    void writable注釈の付与先を固定する() throws IOException {
        List<String> annotated;
        try (Stream<Path> s = Files.walk(MAIN)) {
            annotated = s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> readUnchecked(p).contains("@SupportAccessWritable\n")
                            || readUnchecked(p).contains("@SupportAccessWritable\r\n"))
                    .map(p -> MAIN.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .collect(Collectors.toList());
        }
        // 監査の書込口（検分差し戻し対応）: auth の AuditLogStrictWriter（REQUIRES_NEW で audit_logs へ）と、
        // supportaccess の監査 outbox のリース・確定（SupportAccessAuditOutboxStore）。どちらも記録専用で業務の書込はしない。
        assertThat(annotated).containsExactly(
                "auth/service/AuditLogStrictWriter.java",
                "errorreport/service/ErrorReportIngestService.java",
                "supportaccess/service/SupportAccessAuditOutboxStore.java",
                "supportaccess/service/SupportAccessLogRecorder.java");
        assertThat(SupportAccessLogRecorderMarker.isMarked()).isTrue();
    }

    /** 実クラスにも付いている（ソース走査だけでなくリフレクションでも確認する）。 */
    private static final class SupportAccessLogRecorderMarker {
        static boolean isMarked() {
            return AnnotatedElementUtils.hasAnnotation(
                    com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder.class, SupportAccessWritable.class);
        }
    }

    @Test
    @DisplayName("フィルター順 SupportAccessFilter は ProxyInputContextFilter の後、保護者同意ゲート・レートリミットはその後ろに登録する")
    void フィルター順() throws IOException {
        String src = Files.readString(MAIN.resolve("config/SecurityConfig.java"), StandardCharsets.UTF_8);
        assertThat(src).contains(".addFilterAfter(proxyInputContextFilter, JwtAuthenticationFilter.class)")
                .contains(".addFilterAfter(supportAccessFilter, ProxyInputContextFilter.class)")
                .contains("http.addFilterAfter(gateFilter, SupportAccessFilter.class)");
        // 認証確定後に動くべきフィルターは、JwtAuthenticationFilter 直後ではなく SupportAccessFilter の後ろに置く。
        List<String> stillAfterJwt = Arrays.stream(src.split("\n"))
                .filter(l -> l.contains("addFilterAfter(") && l.contains("JwtAuthenticationFilter.class"))
                .map(String::trim)
                .toList();
        assertThat(stillAfterJwt).as("JwtAuthenticationFilter の直後に登録してよいのは ProxyInputContextFilter だけ")
                .containsExactly(".addFilterAfter(proxyInputContextFilter, JwtAuthenticationFilter.class)");
    }

    @Test
    @DisplayName("SupportAccessFilter は @Component の自動登録を無効化し、Spring Security のチェーンからだけ動く（二重実行しない）")
    void フィルターは自動登録しない() throws IOException {
        String src = Files.readString(MAIN.resolve("config/SecurityConfig.java"), StandardCharsets.UTF_8);
        assertThat(src).contains("FilterRegistrationBean<SupportAccessFilter> supportAccessFilterRegistration()");
        assertThat(src).contains("registration.setEnabled(false)");
    }

    private static String readUnchecked(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
