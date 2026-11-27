package com.mannschaft.app.supportaccess.filter;

import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.i18n.EarlyErrorResponseWriter;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessHeaders;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantValidator;
import com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * F10.9 サポート閲覧フィルター（旧・管理者変身フィルターの置き換え）。
 *
 * <p>JwtAuthenticationFilter・ProxyInputContextFilter の後、ParentalConsentGateFilter・各レートリミット・
 * AuthorizationFilter より前に置く（SecurityConfig）。{@code X-Support-Access-Grant-Id} が付いたリクエストを、
 * 毎回 DB で検証（キャッシュしない。取消は次のリクエストから効く）したうえで、認証を
 * 「本人 ID ＋ {@link RoleClaimResolver#resolveRoles(Long)}（本人のロール）」で作り直す。
 * 運営者の ROLE_SYSTEM_ADMIN は引き継がない。</p>
 *
 * <h2>閲覧のみの強制（第1層）</h2>
 * <ol>
 *   <li>禁止パス（{@link SupportAccessPathPolicy}）は、メソッドを問わず SUPPORT_ACCESS_004</li>
 *   <li>GET/HEAD 以外は SUPPORT_ACCESS_003</li>
 * </ol>
 * <p>第2層は {@code SupportAccessHandlerInterceptor}（{@code @SupportAccessDenied}）、第3層は DB 接続の READ ONLY 化
 * （{@code SupportAccessReadOnlyDataSourceConfig}。{@link SupportAccessContextHolder} が有効な間だけ）。</p>
 *
 * <h2>記録</h2>
 * <p>閲覧は chain の<b>前</b>に VIEW を記録し（{@link SupportAccessLogRecorder#recordView}。失敗したら 503 で
 * fail-closed）、chain の後に応答状態を書き足す。記録は代理の文脈を設定する前後に行い、業務側の READ ONLY 接続とは
 * 別の書込可能な接続で書く。拒否は {@link SupportAccessLogRecorder#recordDenied} が記録する。</p>
 *
 * <h2>対象外</h2>
 * <p>/ws/** は代理にならない（CONNECT は常に運営者本人。購読も運営者のまま）。旧ヘッダー
 * {@link SupportAccessHeaders#LEGACY_IMPERSONATE} は経路を問わず 400（SUPPORT_ACCESS_012）。</p>
 */
@Slf4j
@Component
public class SupportAccessFilter extends OncePerRequestFilter {

    private static final String ROLE_SYSTEM_ADMIN = "ROLE_SYSTEM_ADMIN";

    /** 代理入力・後見切替のヘッダー（併用は 400 SUPPORT_ACCESS_009）。 */
    private static final List<String> PROXY_HEADERS = List.of("X-Proxy-For-User-Id", "X-Proxy-Consent-Id");

    private final Supplier<SupportAccessGrantValidator> validatorSupplier;
    private final Supplier<SupportAccessLogRecorder> logRecorderSupplier;
    private final Supplier<RoleClaimResolver> roleClaimResolverSupplier;

    private EarlyErrorResponseWriter earlyErrorResponseWriter;

    /**
     * Spring が使う構築子。検証・記録・ロール解決の部品は {@link ObjectProvider} で遅延解決する。
     *
     * <p>本フィルターは {@code Filter} 型の {@code @Component} のため、{@code @WebMvcTest} のスライス（約 140 本）にも
     * 読み込まれる。部品の Bean を必須にすると全スライスに {@code @MockitoBean} の追加を強いるため、ヘッダーが付いた
     * リクエストを処理するときに初めて解決する（{@code ProxyInputContextFilter} が後見切替の部品を遅延解決するのと同じ作法）。
     * 本番・フルコンテキストでは Bean が必ず存在する。解決に失敗したら 503 で fail-closed になる。</p>
     */
    @Autowired
    public SupportAccessFilter(ObjectProvider<SupportAccessGrantValidator> validator,
                               ObjectProvider<SupportAccessLogRecorder> logRecorder,
                               ObjectProvider<RoleClaimResolver> roleClaimResolver) {
        this.validatorSupplier = validator::getObject;
        this.logRecorderSupplier = logRecorder::getObject;
        this.roleClaimResolverSupplier = roleClaimResolver::getObject;
    }

    /** 単体テスト・手組み用。 */
    public SupportAccessFilter(SupportAccessGrantValidator validator,
                               SupportAccessLogRecorder logRecorder,
                               RoleClaimResolver roleClaimResolver) {
        this.validatorSupplier = () -> validator;
        this.logRecorderSupplier = () -> logRecorder;
        this.roleClaimResolverSupplier = () -> roleClaimResolver;
    }

    /** 早期拒否応答の部品（任意注入。無い縮小構成では ja 正本で同じ形を書く）。 */
    @Autowired(required = false)
    public void setEarlyErrorResponseWriter(EarlyErrorResponseWriter earlyErrorResponseWriter) {
        this.earlyErrorResponseWriter = earlyErrorResponseWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // WebSocket は代理にならない（旧ヘッダー・新ヘッダーとも見ない）。
        if (path != null && (path.equals("/ws") || path.startsWith("/ws/"))) {
            chain.doFilter(request, response);
            return;
        }

        // 旧ヘッダーは経路・認証状態を問わず 400（旧フィルターの /admin・/system-admin 素通しは引き継がない）。
        if (request.getHeader(SupportAccessHeaders.LEGACY_IMPERSONATE) != null) {
            writeError(request, response, HttpServletResponse.SC_BAD_REQUEST, SupportAccessErrorCode.LEGACY_HEADER);
            return;
        }

        String grantHeader = request.getHeader(SupportAccessHeaders.GRANT_ID);
        if (grantHeader == null) {
            chain.doFilter(request, response);
            return;
        }

        Authentication original = SecurityContextHolder.getContext().getAuthentication();
        if (original == null || !original.isAuthenticated() || original instanceof AnonymousAuthenticationToken) {
            // 未認証。ヘッダーは何の効力も持たない（保護された API は通常どおり認証で拒否される）。
            chain.doFilter(request, response);
            return;
        }

        Long operatorUserId = operatorUserId(original);
        if (operatorUserId == null || !hasSystemAdmin(original)) {
            log.warn("サポート閲覧ヘッダーを運営者以外が送信: principal={}, path={}", original.getName(), path);
            writeError(request, response, HttpServletResponse.SC_FORBIDDEN, SupportAccessErrorCode.GRANT_NOT_ACTIVE);
            return;
        }

        String method = request.getMethod();
        String grantHeaderValue = grantHeader.trim();

        UUID grantId = parseUuid(grantHeaderValue);
        if (grantId == null) {
            deny(request, response, operatorUserId, grantHeaderValue, method, path, SupportAccessErrorCode.GRANT_NOT_FOUND);
            return;
        }

        // 代理入力・後見切替との併用は不可（文脈の取り違えを防ぐ）。
        for (String header : PROXY_HEADERS) {
            if (request.getHeader(header) != null) {
                deny(request, response, operatorUserId, grantHeaderValue, method, path, SupportAccessErrorCode.PROXY_CONFLICT);
                return;
            }
        }

        // 毎リクエスト DB で検証する（キャッシュしない）。
        SupportAccessContext context;
        try {
            context = validatorSupplier.get().validate(operatorUserId, grantId);
        } catch (BusinessException e) {
            deny(request, response, operatorUserId, grantHeaderValue, method, path, e.getErrorCode());
            return;
        } catch (RuntimeException e) {
            log.error("サポート閲覧の許可検証に失敗（fail-closed）: grantId={}, operatorId={}", grantId, operatorUserId, e);
            writeError(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, SupportAccessErrorCode.UNAVAILABLE);
            return;
        }

        // 閲覧のみ（第1層）: 禁止パスはメソッドを問わず 004、GET/HEAD 以外は 003。
        if (SupportAccessPathPolicy.isForbiddenDuringSupportAccess(path)) {
            deny(request, response, operatorUserId, grantHeaderValue, method, path, SupportAccessErrorCode.OPERATION_FORBIDDEN);
            return;
        }
        if (!isReadMethod(method)) {
            deny(request, response, operatorUserId, grantHeaderValue, method, path, SupportAccessErrorCode.READ_ONLY_VIOLATION);
            return;
        }

        // 認証を本人のロールで作り直す（運営者の権限は引き継がない）。
        List<SimpleGrantedAuthority> authorities;
        try {
            authorities = targetAuthorities(context.targetUserId());
        } catch (RuntimeException e) {
            log.error("サポート閲覧の本人ロール解決に失敗（fail-closed）: grantId={}, targetId={}", grantId, context.targetUserId(), e);
            writeError(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, SupportAccessErrorCode.UNAVAILABLE);
            return;
        }
        if (authorities == null) {
            // 本人が運営者権限を持つ（運営アカウントは代理される側になれない）。多重防御。
            deny(request, response, operatorUserId, grantHeaderValue, method, path, SupportAccessErrorCode.GRANT_NOT_ACTIVE);
            return;
        }

        // 閲覧の記録を chain の前に書く。書けない閲覧は許さない（503）。
        UUID viewLogId;
        try {
            viewLogId = logRecorderSupplier.get().recordView(context, method, path);
        } catch (RuntimeException e) {
            log.error("サポート閲覧の閲覧記録に失敗（fail-closed）: grantId={}, operatorId={}", grantId, operatorUserId, e);
            writeError(request, response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, SupportAccessErrorCode.UNAVAILABLE);
            return;
        }

        UsernamePasswordAuthenticationToken targetAuth = new UsernamePasswordAuthenticationToken(
                String.valueOf(context.targetUserId()), null, authorities);
        targetAuth.setDetails(targetDetails(original));
        SecurityContextHolder.getContext().setAuthentication(targetAuth);
        SupportAccessContextHolder.set(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SupportAccessContextHolder.clear();
            SecurityContextHolder.getContext().setAuthentication(original);
            completeViewQuietly(viewLogId, request, response);
        }
    }

    // ─── 部品 ─────────────────────────────────────────────

    private static boolean isReadMethod(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private static Long operatorUserId(Authentication authentication) {
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean hasSystemAdmin(Authentication authentication) {
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (ROLE_SYSTEM_ADMIN.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 本人のロールから authorities を作る。本人が ROLE_SYSTEM_ADMIN と解決された場合は null（拒否）。
     * ロールは JWT 発行と同じ {@link RoleClaimResolver} で求める。
     */
    private List<SimpleGrantedAuthority> targetAuthorities(Long targetUserId) {
        List<String> roles = roleClaimResolverSupplier.get().resolveRoles(targetUserId);
        if (roles == null) {
            return List.of();
        }
        if (roles.contains("SYSTEM_ADMIN")) {
            return null;
        }
        return roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }

    /** details は運営者の jti（セッション特定用）を残し、ppc は立てない（本人の状態ゲートは検証側で判定済み）。 */
    private static Map<String, Object> targetDetails(Authentication original) {
        Map<String, Object> details = new HashMap<>();
        if (original.getDetails() instanceof Map<?, ?> map) {
            Object jti = map.get("jti");
            if (jti instanceof String s && !s.isBlank()) {
                details.put("jti", s);
            }
        }
        details.put("ppc", false);
        return details;
    }

    private void deny(HttpServletRequest request, HttpServletResponse response, Long operatorUserId,
                      String grantHeaderValue, String method, String path, ErrorCode code) throws IOException {
        try {
            logRecorderSupplier.get().recordDenied(operatorUserId, grantHeaderValue, method, path, code.getCode());
        } catch (RuntimeException e) {
            // 拒否の記録に失敗しても、拒否そのものは変えない（許可側には倒さない）。
            log.error("サポート閲覧の拒否記録に失敗: code={}, operatorId={}", code.getCode(), operatorUserId, e);
        }
        writeError(request, response, GlobalExceptionHandler.resolveStatus(code).value(), code);
    }

    private void completeViewQuietly(UUID viewLogId, HttpServletRequest request, HttpServletResponse response) {
        if (viewLogId == null) {
            return;
        }
        try {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            logRecorderSupplier.get().completeView(viewLogId, response.getStatus(), pattern instanceof String s ? s : null);
        } catch (RuntimeException e) {
            log.error("サポート閲覧の閲覧記録の完了更新に失敗: logId={}", viewLogId, e);
        }
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code)
            throws IOException {
        if (earlyErrorResponseWriter != null) {
            earlyErrorResponseWriter.write(request, response, status, code);
        } else {
            EarlyErrorResponseWriter.writeJapanese(response, status, code);
        }
    }
}
