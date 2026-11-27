package com.mannschaft.app.supportaccess.context;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * F10.9 代理閲覧中でも、このクラス／メソッドの実行は書込可能な DB 接続で行うことを示す（閲覧記録・監査の記録用）。
 *
 * <p>代理中は DB 接続を READ ONLY にする（{@link SupportAccessContextHolder#isReadOnlyEnforced()}）。ところが
 * {@code @Transactional(REQUIRES_NEW)} の接続は<b>メソッド本体に入る前</b>（トランザクション開始時）に取得されるため、
 * 本体の中で {@link SupportAccessContextHolder#callWithWritableConnection} を呼んでも間に合わない。本注釈を付けると
 * {@link SupportAccessWritableAspect} がトランザクションの外側で書込可能な区間を開き、接続取得より先に READ ONLY の
 * 強制を外す。</p>
 *
 * <p><b>業務の書込には付けない。</b>付けてよいのは「代理中でも必ず書く記録」だけ（閲覧記録・監査）。
 * 番人 {@code SupportAccessWritableGuardTest} が付与先を固定する。</p>
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface SupportAccessWritable {
}
