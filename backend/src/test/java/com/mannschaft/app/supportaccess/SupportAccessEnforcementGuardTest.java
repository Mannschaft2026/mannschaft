package com.mannschaft.app.supportaccess;

import com.mannschaft.app.supportaccess.context.SupportAccessWritable;
import com.mannschaft.app.supportaccess.filter.SupportAccessPathPolicy;
import com.mannschaft.app.supportaccess.web.SupportAccessDenied;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 *   <li>第1層: SecurityConfig の SYSTEM_ADMIN 予約パスは、すべて {@link SupportAccessPathPolicy} が拒否する</li>
 *   <li>第3層: {@link SupportAccessWritable}（書込可能な接続の例外）は閲覧記録の部品にだけ付ける</li>
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
        Matcher m = Pattern.compile("requestMatchers\\(\\s*\"([^\"]+)\"\\s*\\)\\s*\\.hasRole\\(\"SYSTEM_ADMIN\"\\)").matcher(securityConfig);
        TreeSet<String> reserved = new TreeSet<>();
        while (m.find()) {
            reserved.add(m.group(1));
        }
        assertThat(reserved).as("SecurityConfig の SYSTEM_ADMIN 予約パスを抽出できること")
                .contains("/api/v1/admin/stripe/**", "/api/v1/system-admin/**");
        List<String> allowed = new ArrayList<>();
        for (String pattern : reserved) {
            String concrete = pattern.replace("/**", "/x");
            if (!SupportAccessPathPolicy.isForbiddenDuringSupportAccess(concrete)) {
                allowed.add(pattern);
            }
        }
        assertThat(allowed)
                .as("SecurityConfig で SYSTEM_ADMIN 予約なのに代理中に通ってしまうパス（SupportAccessPathPolicy へ追加すること）")
                .isEmpty();
    }

    @Test
    @DisplayName("第3層 @SupportAccessWritable（書込可能な接続の例外）を付けてよいのは閲覧記録の部品だけ")
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
        assertThat(annotated).containsExactly("supportaccess/service/SupportAccessLogRecorder.java");
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
