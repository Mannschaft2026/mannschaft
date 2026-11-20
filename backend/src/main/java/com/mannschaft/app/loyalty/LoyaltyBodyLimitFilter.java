package com.mannschaft.app.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.i18n.EarlyErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** チームポイントJSON本文の4KB境界。認証フィルターの後で検査する。 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
public class LoyaltyBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BODY_BYTES = 4096;
    private final ObjectMapper objectMapper;
    private EarlyErrorResponseWriter earlyErrorResponseWriter;

    public LoyaltyBodyLimitFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.matches("/api/v1/loyalty(?:/.*)?")
                && !path.matches("/api/v1/teams/[^/]+/loyalty(?:/.*)?");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            reject(request, response);
            return;
        }
        // Content-Lengthなし、または小さく偽装された本文も同じ有限バイト数で判定する。
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            reject(request, response);
            return;
        }
        chain.doFilter(new BoundedBodyRequest(request, body), response);
    }

    /**
     * 400 COMMON_001 を値なしで返す。CMP-261113-1507: 本フィルタは UserLocaleFilter より前で応答を書くため、
     * 利用者の言語（users.locale → Accept-Language）は {@link EarlyErrorResponseWriter} で解決する。
     */
    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setHeader("Cache-Control", "no-store");
        if (earlyErrorResponseWriter != null) {
            earlyErrorResponseWriter.write(request, response, HttpServletResponse.SC_BAD_REQUEST,
                    CommonErrorCode.COMMON_001);
            return;
        }
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(new ErrorResponse.ErrorDetail(
                CommonErrorCode.COMMON_001.getCode(), CommonErrorCode.COMMON_001.getMessage(), List.of())));
    }

    /** 早期拒否応答の部品（任意注入。既存の構築箇所のコンストラクタを変えないため setter で受ける）。 */
    @Autowired(required = false)
    public void setEarlyErrorResponseWriter(EarlyErrorResponseWriter earlyErrorResponseWriter) {
        this.earlyErrorResponseWriter = earlyErrorResponseWriter;
    }

    /** 検査済み本文のみをMVCへ再生し、機密値はログや例外文言へコピーしない。 */
    private static final class BoundedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private BoundedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public String getCharacterEncoding() { return StandardCharsets.UTF_8.name(); }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            return new ServletInputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(body);
                private boolean listenerRegistered;
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] target, int offset, int length) {
                    return input.read(target, offset, length);
                }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    if (listener == null || listenerRegistered) {
                        throw new IllegalStateException("ReadListenerは一度だけ指定できます");
                    }
                    listenerRegistered = true;
                    try {
                        if (!isFinished()) listener.onDataAvailable();
                        if (isFinished()) listener.onAllDataRead();
                    } catch (IOException failure) {
                        listener.onError(failure);
                    }
                }
            };
        }
    }
}
