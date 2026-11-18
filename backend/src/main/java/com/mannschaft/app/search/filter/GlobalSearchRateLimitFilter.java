package com.mannschaft.app.search.filter;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * グローバル検索のユーザー単位レートリミット（F04.6 §4 / §7、CMP-261116-0722）。
 *
 * <ul>
 *   <li>{@code GET /api/v1/search}: 30 回/分</li>
 *   <li>{@code GET /api/v1/search/suggestions}: 60 回/分</li>
 * </ul>
 *
 * <p>制限主体は認証済みなら {@code u:{userId}}（{@link AbstractRateLimitFilter} の共通規則）のため、
 * 別ユーザーは影響を受けない。超過は 429 + {@code Retry-After}。履歴・保存済み検索の API は対象外。</p>
 */
@Component
public class GlobalSearchRateLimitFilter extends AbstractRateLimitFilter {

    static final String SEARCH_PATH = "/api/v1/search";
    static final String SUGGEST_PATH = "/api/v1/search/suggestions";

    /** 検索のレート制限（回/分）。 */
    static final int SEARCH_RATE_PER_MINUTE = 30;

    /** サジェストのレート制限（回/分）。 */
    static final int SUGGEST_RATE_PER_MINUTE = 60;

    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String ZONE_PREFIX = "global-search:";

    public GlobalSearchRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = normalize(request.getServletPath());
        return !SEARCH_PATH.equals(path) && !SUGGEST_PATH.equals(path);
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        String path = normalize(request.getServletPath());
        if (SUGGEST_PATH.equals(path)) {
            return new RateLimitRule(ZONE_PREFIX + "SUGGEST", SUGGEST_RATE_PER_MINUTE, WINDOW);
        }
        if (SEARCH_PATH.equals(path)) {
            return new RateLimitRule(ZONE_PREFIX + "SEARCH", SEARCH_RATE_PER_MINUTE, WINDOW);
        }
        return null;
    }

    /** 末尾スラッシュを落とす（{@code /api/v1/search/} を別経路にして制限を回避させない）。 */
    private static String normalize(String path) {
        if (path != null && path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
