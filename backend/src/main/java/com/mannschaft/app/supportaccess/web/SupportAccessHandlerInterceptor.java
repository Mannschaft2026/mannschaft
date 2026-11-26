package com.mannschaft.app.supportaccess.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** F10.9 閲覧のみの第2層: {@link SupportAccessDenied} の付いた handler を代理中は 004 で拒否する。試練スタブ。 */
public class SupportAccessHandlerInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        throw new UnsupportedOperationException("試練スタブ: 戦役Z-B 未実装");
    }
}
