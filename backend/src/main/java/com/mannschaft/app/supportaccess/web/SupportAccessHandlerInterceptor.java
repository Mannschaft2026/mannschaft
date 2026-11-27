package com.mannschaft.app.supportaccess.web;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * F10.9 閲覧のみの第2層: {@link SupportAccessDenied} の付いた handler（Controller クラス／メソッド）を、
 * 代理中は SUPPORT_ACCESS_004 で拒否する。
 *
 * <p>第1層（{@code SupportAccessFilter} のパス判定）が URL の見た目で拒否するのに対し、本層は handler 自体に付けた
 * 注釈で拒否する。download / export / csv / pdf 系の Controller・メソッドへ付与し、パス判定の取りこぼし
 * （URL に目印が無い書き出し等）を後から注釈で塞げるようにする。付与漏れは
 * {@code SupportAccessDeniedAnnotationGuardTest} が検出する。</p>
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
        return true;
    }

    private static boolean isDenied(HandlerMethod handlerMethod) {
        Class<?> userClass = ClassUtils.getUserClass(handlerMethod.getBeanType());
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), SupportAccessDenied.class)
                || AnnotatedElementUtils.hasAnnotation(userClass, SupportAccessDenied.class);
    }
}
