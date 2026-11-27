package com.mannschaft.app.supportaccess.context;

import com.zaxxer.hikari.HikariDataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;

/**
 * F10.9 閲覧のみの第3層（最後の砦）: 代理閲覧の間は、アプリが取得する DB 接続をすべて READ ONLY にする。
 *
 * <h2>なぜ接続そのものを READ ONLY にするのか</h2>
 * <p>Hibernate の PreInsert/PreUpdate リスナーだけでは、{@code @Modifying} のクエリ（約 300 箇所）・JdbcTemplate・
 * ネイティブ SQL を止められない（エンティティ事象を通らない）。接続を READ ONLY にすれば、書込 SQL は
 * どの経路でも DB が拒否する（MySQL: {@code Cannot execute statement in a READ ONLY transaction}）。
 * MySQL Connector/J は {@code readOnlyPropagatesToServer}（既定 true）で {@code setReadOnly(true)} を
 * {@code SET SESSION TRANSACTION READ ONLY} としてサーバーへ伝える。</p>
 *
 * <h2>仕組み</h2>
 * <p>プライマリ・レプリカの {@link HikariDataSource} Bean を、{@code getConnection} の戻り値に
 * {@code setReadOnly(true)} を掛けるプロキシ（CGLIB。{@code HikariDataSource} のサブクラスのままなので、
 * Hikari 型を前提にした既存コード・メトリクスはそのまま動く）で包む。{@link SupportAccessContextHolder#isReadOnlyEnforced()}
 * が真のときだけ作用し、代理でなければ何もしない。</p>
 * <ul>
 *   <li>返却時: HikariCP が、{@code setReadOnly} を変えた接続を返却時に既定（書込可）へ戻す
 *       （ProxyConnection の dirty bit。プールへ READ ONLY を持ち越さない）。</li>
 *   <li>記録用の書込可能な接続: {@link SupportAccessContextHolder#callWithWritableConnection}（および
 *       {@link SupportAccessWritable}）の区間で取得した接続は READ ONLY にしない。閲覧記録・監査だけがこれを使う。</li>
 *   <li>OSIV は無効（open-in-view: false）。接続はリクエストの途中ではなく、フィルターが文脈を設定した後に
 *       取得される。{@code @Async} は TaskDecorator が文脈を伝播するため、受け手でも READ ONLY になる。</li>
 *   <li>設定に失敗した接続は閉じて例外にする（fail-closed。書込可能な接続を代理に使わせない）。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SupportAccessReadOnlyDataSourcePostProcessor {

    /** アーカイブ用の（元から読取専用の）DataSource は包まない。 */
    private static final String ARCHIVE_DATA_SOURCE = "archiveDataSource";

    /** BeanPostProcessor は他の Bean より早く作られるため static にする（依存を持たない）。 */
    @Bean
    public static BeanPostProcessor supportAccessReadOnlyDataSourceBeanPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource
                        && !ARCHIVE_DATA_SOURCE.equals(beanName)
                        && !AopUtils.isAopProxy(bean)) {
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.setProxyTargetClass(true);
                    factory.addAdvice(readOnlyWhileSupportAccess());
                    return factory.getProxy(bean.getClass().getClassLoader());
                }
                return bean;
            }
        };
    }

    /** {@code getConnection} の戻り値へ、代理中だけ READ ONLY を掛けるアドバイス。 */
    static MethodInterceptor readOnlyWhileSupportAccess() {
        return invocation -> {
            Object result = invocation.proceed();
            if (result instanceof Connection connection
                    && "getConnection".equals(invocation.getMethod().getName())
                    && SupportAccessContextHolder.isReadOnlyEnforced()) {
                try {
                    connection.setReadOnly(true);
                } catch (Exception e) {
                    try {
                        connection.close();
                    } catch (Exception closeFailure) {
                        e.addSuppressed(closeFailure);
                    }
                    throw e;
                }
            }
            return result;
        };
    }
}
