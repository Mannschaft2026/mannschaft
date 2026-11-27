package com.mannschaft.app.supportaccess.web;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.filter.SupportAccessFilter;
import com.mannschaft.app.supportaccess.filter.SupportAccessPathPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * F10.9 閲覧のみの第2層: {@link SupportAccessDenied} の付いた handler（Controller クラス／メソッド）を、
 * 代理中は SUPPORT_ACCESS_004 で拒否する。
 *
 * <p>第1層（{@code SupportAccessFilter} のパス判定）が URL の見た目で拒否するのに対し、本層は handler 自体に付けた
 * 注釈で拒否する。download / export / csv / pdf 系の Controller・メソッドへ付与し、パス判定の取りこぼし
 * （URL に目印が無い書き出し等）を後から注釈で塞げるようにする。付与漏れは
 * {@code SupportAccessEnforcementGuardTest} が検出する。</p>
 *
 * <p>さらに、Spring が実際に選んだルートパターン（{@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}）と
 * デコード済みの lookup path（{@link SupportAccessFilter#decodedLookupPath}）にも {@link SupportAccessPathPolicy} を当てる
 * （第1層の URI 判定をエンコード・表記揺れで迂回されても、解決後の handler の経路で二重に塞ぐ）。</p>
 *
 * <p>代理でなければ何もしない。例外（{@link BusinessException}）は {@code GlobalExceptionHandler} が 403 に変換する。</p>
 */
public class SupportAccessHandlerInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!SupportAccessContextHolder.isActive()) {
            return true;
        }
        if (handler instanceof HandlerMethod handlerMethod && isDenied(handlerMethod)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATION_FORBIDDEN);
        }
        if (isForbiddenRoute(request)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATION_FORBIDDEN);
        }
        return true;
    }

    private static boolean isForbiddenRoute(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String routePattern && SupportAccessPathPolicy.isForbiddenRoutePattern(routePattern)) {
            return true;
        }
        return SupportAccessPathPolicy.isForbiddenDuringSupportAccess(SupportAccessFilter.decodedLookupPath(request));
    }

    private static boolean isDenied(HandlerMethod handlerMethod) {
        Class<?> userClass = ClassUtils.getUserClass(handlerMethod.getBeanType());
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), SupportAccessDenied.class)
                || AnnotatedElementUtils.hasAnnotation(userClass, SupportAccessDenied.class);
    }
}
