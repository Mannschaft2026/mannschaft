package com.mannschaft.app.common.i18n;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/**
 * ユーザーの locale を各リクエストの LocaleContextHolder にセットするフィルター。
 *
 * 実行順序:
 * Spring Security の DelegatingFilterProxy（@Order = -100）より後に実行されるため、
 * JwtAuthenticationFilter が SecurityContextHolder にセット済みの状態で動作する。
 * @Order(LOWEST_PRECEDENCE - 10) = Integer.MAX_VALUE - 10 → Security より大きい数 → 後続実行
 *
 * locale 決定ロジック:
 * 1. ログイン済み（SecurityContext に Authentication あり）
 *    → UserLocaleCache.getLocale(userId) でキャッシュ参照（TTL 5分）
 *    → null の場合 "ja" にフォールバック
 * 2. 未ログイン
 *    → Accept-Language ヘッダーを Locale.LanguageRange.parse() で解析
 *    → 不正ヘッダーは IllegalArgumentException → DEFAULT_LOCALE にフォールバック
 * 3. いずれも SUPPORTED_LOCALES に含まれない場合 → DEFAULT_LOCALE
 *
 * <p><b>CMP-260923-1640 根治</b>: 本フィルターが {@link org.springframework.context.i18n.LocaleContextHolder}
 * にセットした値は、Spring MVC の {@code DispatcherServlet}（{@code FrameworkServlet#processRequest}）が
 * リクエストごとに {@code LocaleResolver} の解決結果で<b>上書き</b>してしまう。main には {@code LocaleResolver}
 * Bean が1つも登録されておらず、既定の {@code AcceptHeaderLocaleResolver} が使われていたため、
 * 「ログイン済みユーザーの DB locale」より「(なければサーバー既定 / あれば) Accept-Language」が
 * 常に最終的に勝ってしまっていた（ログイン済み・ヘッダー無しで英語になる等）。
 * 根治として {@link UserLocaleResolver}（Bean名 {@code localeResolver}）を追加し、
 * 本フィルターが解決した結果を {@link #RESOLVED_LOCALE_ATTRIBUTE} 経由で Resolver に渡して
 * DispatcherServlet 側の上書きでも同じ値が使われるようにした（判定ロジックの二重化を避けるため、
 * Resolver 自身は独自の解決を行わずこのフィルターの結果を尊重する）。</p>
 *
 * <p><b>あわせて根治した実害（未ログイン利用者の Accept-Language が無視される別欠陥）</b>:
 * Spring Security の {@code AnonymousAuthenticationFilter} は未ログインリクエストにも
 * principal="anonymousUser"（{@code String}）・{@code isAuthenticated()==true} の
 * {@link org.springframework.security.authentication.AnonymousAuthenticationToken} をセットする。
 * これを除外せずに「{@code isAuthenticated()} かつ {@code getPrincipal() instanceof String}」だけで
 * ログイン済み判定すると、未ログイン利用者も常にこの分岐に入り
 * {@code Long.parseLong("anonymousUser")} が失敗 → {@code DEFAULT_LOCALE(ja)} に固定され、
 * {@code Accept-Language} 分岐に一切進まなくなっていた。判定条件に
 * {@code !(auth instanceof AnonymousAuthenticationToken)} を追加して根治した。</p>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
@RequiredArgsConstructor
public class UserLocaleFilter extends OncePerRequestFilter {

    /**
     * 本フィルターが解決した {@link Locale} を保持するリクエスト属性名。
     * {@link UserLocaleResolver} が DispatcherServlet からの問い合わせ時にこれを読み、
     * フィルターと Resolver の判定が食い違わないようにする。
     */
    static final String RESOLVED_LOCALE_ATTRIBUTE = UserLocaleFilter.class.getName() + ".RESOLVED_LOCALE";

    private final UserLocaleCache userLocaleCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            Locale locale = resolveLocale(request);
            // DispatcherServlet の LocaleResolver 問い合わせ（UserLocaleResolver）へこの解決結果を渡す。
            request.setAttribute(RESOLVED_LOCALE_ATTRIBUTE, locale);
            // inheritable=false 固定: Virtual Threads 環境で InheritableThreadLocal への伝搬を防ぐ
            org.springframework.context.i18n.LocaleContextHolder
                    .setLocale(locale, false);
            filterChain.doFilter(request, response);
        } finally {
            // スレッドプール汚染防止: 必ず finally でクリアする
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    /**
     * 判定本体は {@link RequestLocaleResolution} に置く（CMP-261113-1507）。本フィルターより前で応答を書く
     * 早期拒否経路（{@link EarlyErrorResponseWriter}）も同じ順位で言語を決めるため、判定を二重化しない。
     */
    private Locale resolveLocale(HttpServletRequest request) {
        return RequestLocaleResolution.resolve(request, userLocaleCache);
    }
}
