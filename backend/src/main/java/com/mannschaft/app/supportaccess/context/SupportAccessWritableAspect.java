package com.mannschaft.app.supportaccess.context;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link SupportAccessWritable} を付けた記録用 Bean の実行を、書込可能な接続の区間で包む。
 *
 * <p>{@code @Order(HIGHEST_PRECEDENCE)} により {@code @Transactional} のアドバイスより外側で動き、
 * REQUIRES_NEW の接続取得より前に READ ONLY の強制を外す。区間はネストを数える
 * （{@link SupportAccessContextHolder#callWithWritableConnection}）ので、呼び出し元が代理でなければ何も変わらない。</p>
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SupportAccessWritableAspect {

    @Around("@within(com.mannschaft.app.supportaccess.context.SupportAccessWritable)"
            + " || @annotation(com.mannschaft.app.supportaccess.context.SupportAccessWritable)")
    public Object runWithWritableConnection(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!SupportAccessContextHolder.isActive()) {
            return joinPoint.proceed();
        }
        // callWithWritableConnection は Supplier しか取れないため、検査例外を包んで運ぶ。
        try {
            return SupportAccessContextHolder.callWithWritableConnection(() -> {
                try {
                    return joinPoint.proceed();
                } catch (RuntimeException | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new CheckedCarrier(t);
                }
            });
        } catch (CheckedCarrier carrier) {
            throw carrier.getCause();
        }
    }

    private static final class CheckedCarrier extends RuntimeException {
        CheckedCarrier(Throwable cause) {
            super(cause);
        }
    }
}
