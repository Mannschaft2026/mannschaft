package com.mannschaft.app.common.i18n;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * DispatcherServlet（{@code GlobalExceptionHandler}）より前で応答を書く早期拒否経路の共通部品（CMP-261113-1507）。
 *
 * <p>Security の AccessDeniedHandler・保護者同意ゲート・代理入力・管理者変身・loyalty 本文上限の各フィルタは
 * {@link UserLocaleFilter} より前で応答を確定させるため、{@code LocaleContextHolder} を使えない。
 * 本クラスが {@link RequestLocaleResolution}（users.locale → Accept-Language → ja）で言語を決め、
 * {@link ErrorMessageResolver} で訳した {@link ErrorResponse} 形式（{@code {"error":{"code","message","fieldErrors"}}}）を書く。</p>
 *
 * <p>本 Bean を組み立てない縮小構成（{@code @WebMvcTest} スライス等）のために、Bean 不在時の
 * {@link #writeJapanese} も用意する（ja 正本で同じ形の応答を書く）。</p>
 */
@Component
public class EarlyErrorResponseWriter {

    private static final ObjectMapper FALLBACK_MAPPER = new ObjectMapper();

    private final ErrorMessageResolver errorMessageResolver;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<UserLocaleCache> userLocaleCacheProvider;

    public EarlyErrorResponseWriter(MessageSource messageSource,
                                    ObjectMapper objectMapper,
                                    ObjectProvider<UserLocaleCache> userLocaleCacheProvider) {
        this.errorMessageResolver = new ErrorMessageResolver(messageSource);
        this.objectMapper = objectMapper;
        this.userLocaleCacheProvider = userLocaleCacheProvider;
    }

    /** リクエストの言語（{@link RequestLocaleResolution} と同じ順位）。 */
    public Locale resolveLocale(HttpServletRequest request) {
        return RequestLocaleResolution.resolve(request, userLocaleCacheProvider.getIfAvailable());
    }

    /**
     * 利用者の言語で訳したエラー応答を書く。
     *
     * @param request  リクエスト（言語判定に使う）
     * @param response 書き込み先
     * @param status   HTTP ステータス
     * @param code     エラーコード
     */
    public void write(HttpServletRequest request, HttpServletResponse response, int status, ErrorCode code)
            throws IOException {
        String message = errorMessageResolver.resolve(code, resolveLocale(request));
        writeBody(response, status, code, message, objectMapper);
    }

    /** Bean 不在時のフォールバック。ja 正本の文言で同じ形の応答を書く。 */
    public static void writeJapanese(HttpServletResponse response, int status, ErrorCode code) throws IOException {
        writeBody(response, status, code, code.getMessage(), FALLBACK_MAPPER);
    }

    private static void writeBody(HttpServletResponse response, int status, ErrorCode code, String message,
                                  ObjectMapper mapper) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = new ErrorResponse(new ErrorResponse.ErrorDetail(code.getCode(), message, List.of()));
        response.getWriter().write(mapper.writeValueAsString(body));
    }
}
