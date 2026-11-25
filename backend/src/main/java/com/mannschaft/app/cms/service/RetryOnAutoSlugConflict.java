package com.mannschaft.app.cms.service;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自動採番した slug の同時作成競合（{@link BlogSlugAutoConflictException}）を、
 * トランザクションの外側から採番し直して再試行する対象メソッドの印。
 *
 * <p>{@link BlogSlugConflictRetryAspect} が処理する。対象メソッドは {@code @Transactional}（REQUIRED）であること。
 * 再試行はメソッド全体（slug の採番・事前確認から INSERT まで）を新しいトランザクションで呼び直す。</p>
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RetryOnAutoSlugConflict {
}
