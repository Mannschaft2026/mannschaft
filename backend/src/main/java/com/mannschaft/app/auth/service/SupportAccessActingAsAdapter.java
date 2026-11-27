package com.mannschaft.app.auth.service;

import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.supportaccess.port.SupportAccessActingAsDetector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * F10.9 後見切替・代理入力の最中かの判定（supportaccess の port を auth ドメインが実装する）。
 *
 * <p>後見切替は JWT を再発行せず actor=保護者のまま {@code X-Proxy-For-User-Id=child} を
 * {@code ProxyInputContextFilter} が検証し、代理入力（F14.1）と同じく {@link ProxyInputContext#isProxy()} が真になる
 * （{@code AuthenticationCriticalOperationGuard} と同じ判定）。リクエストの外（バッチ等）では常に false。</p>
 */
@Component
@RequiredArgsConstructor
public class SupportAccessActingAsAdapter implements SupportAccessActingAsDetector {

    private final ProxyInputContext proxyInputContext;

    @Override
    public boolean isActingAsAnotherUser() {
        if (RequestContextHolder.getRequestAttributes() == null) {
            return false;
        }
        return proxyInputContext.isProxy();
    }
}
