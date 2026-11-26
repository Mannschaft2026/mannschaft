package com.mannschaft.app.supportaccess.filter;

import com.mannschaft.app.supportaccess.service.SupportAccessGrantValidator;
import com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * F10.9 サポート閲覧フィルター（AdminImpersonationFilter の置き換え）。試練スタブ。
 *
 * <p>JwtAuthenticationFilter の後・ProxyInputContextFilter の後、かつ ParentalConsentGateFilter・
 * 各レートリミット・AuthorizationFilter より前に置く。認証を「本人 ID ＋ RoleClaimResolver.resolveRoles(本人)」で
 * 作り直し（運営者の権限は引き継がない）、SupportAccessContextHolder を set/clear する。</p>
 */
public class SupportAccessFilter extends OncePerRequestFilter {

    private final SupportAccessGrantValidator validator;
    private final SupportAccessLogRecorder logRecorder;
    private final RoleClaimResolver roleClaimResolver;

    public SupportAccessFilter(SupportAccessGrantValidator validator,
                               SupportAccessLogRecorder logRecorder,
                               RoleClaimResolver roleClaimResolver) {
        this.validator = validator;
        this.logRecorder = logRecorder;
        this.roleClaimResolver = roleClaimResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
