package com.mannschaft.app.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * リクエストの言語を決める唯一の判定（{@link UserLocaleFilter} と早期拒否経路の共通部品）。
 *
 * <p>優先順位:</p>
 * <ol>
 *   <li>ログイン済み（{@link AnonymousAuthenticationToken} 以外で principal が userId 文字列）
 *       → {@link UserLocaleCache} の users.locale。未対応値・取得不能は ja</li>
 *   <li>未ログイン → Accept-Language を {@link Locale.LanguageRange#parse} で解析し、最初の対応言語</li>
 *   <li>いずれも該当しない・不正ヘッダー → ja</li>
 * </ol>
 *
 * <p>CMP-261113-1507: Security のフィルタ鎖（AccessDeniedHandler・保護者同意ゲート・代理入力・管理者変身）や
 * loyalty 本文上限フィルタは {@link UserLocaleFilter} より前で応答を書くため、{@code LocaleContextHolder} は
 * まだ利用者の言語になっていない。これらの経路も本クラスで同じ順位の判定を行う。</p>
 */
@Slf4j
public final class RequestLocaleResolution {

    private static final Set<String> SUPPORTED_LOCALES = Set.of("ja", "en", "zh", "ko", "es", "de");
    private static final Locale DEFAULT_LOCALE = Locale.JAPANESE;

    private RequestLocaleResolution() {
    }

    /**
     * リクエストの言語を解決する。
     *
     * @param request         HTTP リクエスト
     * @param userLocaleCache users.locale のキャッシュ（null なら Accept-Language のみで判定する）
     * @return 対応言語の {@link Locale}（既定 ja）
     */
    public static Locale resolve(HttpServletRequest request, UserLocaleCache userLocaleCache) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // CMP-260923-1640 是正: Spring Security の AnonymousAuthenticationFilter は未ログインリクエストにも
        // 「principal="anonymousUser"（String）・isAuthenticated()=true」の AnonymousAuthenticationToken を
        // セットする。これを除外しないと isAuthenticated()==true かつ getPrincipal() instanceof String に
        // 一致してしまい、Long.parseLong("anonymousUser") が失敗 → catch → DEFAULT_LOCALE(ja) 固定で
        // Accept-Language 分岐に一切進まなくなる（未ログイン利用者の言語切替が常に無視される実害があった）。
        // この除外を外すと UserLocaleResolutionIT の「未認証・Accept-Language: en」が red になることを
        // 実証済み（tests=5 failures=1、失敗するのはこの1件のみ）。
        boolean isRealUser = auth != null && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken);

        // ログイン済みの場合は DB（キャッシュ経由）から locale を取得
        if (isRealUser && auth.getPrincipal() instanceof String principal && userLocaleCache != null) {
            try {
                Long userId = Long.parseLong(principal);
                String localeStr = userLocaleCache.getLocale(userId);
                // null ガード + サブタグ除去 (例: "en-US" → "en")
                if (localeStr != null) {
                    String normalized = Locale.forLanguageTag(localeStr).getLanguage();
                    if (SUPPORTED_LOCALES.contains(normalized)) {
                        return Locale.forLanguageTag(normalized);
                    }
                }
            } catch (NumberFormatException e) {
                log.debug("userId のパース失敗: {}", auth.getPrincipal());
            }
            return DEFAULT_LOCALE;
        }

        // 未ログイン: Accept-Language ヘッダーから決定
        return resolveFromAcceptLanguage(request);
    }

    private static Locale resolveFromAcceptLanguage(HttpServletRequest request) {
        String header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return DEFAULT_LOCALE;
        }
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            for (Locale.LanguageRange range : ranges) {
                // ベース言語コードのみ抽出（例: "en-US" → "en"）
                String lang = Locale.forLanguageTag(range.getRange()).getLanguage();
                if (SUPPORTED_LOCALES.contains(lang)) {
                    return Locale.forLanguageTag(lang);
                }
            }
        } catch (IllegalArgumentException e) {
            // 不正な Accept-Language ヘッダー → デフォルトにフォールバック
            log.debug("不正な Accept-Language ヘッダー: {}", header);
        }
        return DEFAULT_LOCALE;
    }
}
