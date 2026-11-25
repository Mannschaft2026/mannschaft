package com.mannschaft.app.support.test;

import java.util.List;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * テスト用: アプリの {@link PasswordEncoder#matches} の呼び出し回数を<b>呼び出しスレッド単位</b>で数える（CMP-261123-1434）。
 *
 * <p>用途: バックアップコード照合が保存件数を超えるハッシュ比較をしないこと（AC-54。{@code MfaBackupCodeLatencyIT}）を、
 * 壁時計ではなく照合回数で判定する。</p>
 *
 * <h2>なぜ BeanOverride（{@code @MockitoSpyBean}）を使わないか</h2>
 * <p>テストクラスに {@code @MockitoSpyBean(PasswordEncoder)} を足すと、そのクラスだけ MergedContextConfiguration が分岐し
 * 構成キー（起動する context）が 1 つ増える（規約 31「構成キーの棚卸し」のラチェットが red にする）。本クラスは
 * spring.factories の {@link ContextCustomizerFactory} で<b>全テストコンテキストに一様に</b>載る customizer であり、
 * 全インスタンスを等価（equals / hashCode が定数）にしてあるので、構成キーの種類を増やさない
 * （{@code ContextLoadObservingCustomizer} と同じ方式）。</p>
 *
 * <h2>挙動</h2>
 * <ul>
 *   <li>コンテキストの refresh 前に BeanPostProcessor を登録し、本番の {@link DelegatingPasswordEncoder}（{@code AuthConfig}）を
 *       委譲するだけの計数ラッパーで包む。encode / matches / upgradeEncoding の結果は委譲先そのままで、挙動は変えない。</li>
 *   <li>Mockito の mock / spy は包まない（{@code @MockitoBean} などで差し替えた試験の stub・verify を壊さない）。</li>
 *   <li>計数は {@link ThreadLocal}。共有コンテキストで別スレッド（非同期リスナー等）が照合しても混ざらない。
 *       計測側は {@link #reset()} の後に対象を同じスレッドで呼び、{@link #currentThreadCount()} を読む。</li>
 * </ul>
 */
public final class PasswordMatchCounter {

    private static final ThreadLocal<int[]> COUNT = ThreadLocal.withInitial(() -> new int[1]);

    private PasswordMatchCounter() {
    }

    /** 呼び出しスレッドの照合回数を 0 に戻す。 */
    public static void reset() {
        COUNT.get()[0] = 0;
    }

    /** 直近の {@link #reset()} 以降に、呼び出しスレッドで行われた {@code matches} の回数。 */
    public static int currentThreadCount() {
        return COUNT.get()[0];
    }

    /** 注入された PasswordEncoder が計数ラッパーか（計数が効いていない空虚な判定を防ぐ前提確認用）。 */
    public static boolean isCounting(PasswordEncoder encoder) {
        return encoder instanceof CountingPasswordEncoder;
    }

    /** 委譲するだけの計数ラッパー。 */
    static class CountingPasswordEncoder implements PasswordEncoder {
        private final PasswordEncoder delegate;

        CountingPasswordEncoder(PasswordEncoder delegate) {
            this.delegate = delegate;
        }

        @Override
        public String encode(CharSequence rawPassword) {
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            COUNT.get()[0]++;
            return delegate.matches(rawPassword, encodedPassword);
        }

        @Override
        public boolean upgradeEncoding(String encodedPassword) {
            return delegate.upgradeEncoding(encodedPassword);
        }
    }

    /** 本番の DelegatingPasswordEncoder だけを包む。mock / spy は包まない。 */
    static final class WrappingPostProcessor implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DelegatingPasswordEncoder encoder && !Mockito.mockingDetails(bean).isMock()) {
                return new CountingPasswordEncoder(encoder);
            }
            return bean;
        }
    }

    /** refresh の直前に BeanPostProcessor を登録する customizer。全インスタンス等価で MCC を割らない。 */
    public static final class Customizer implements ContextCustomizer {
        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
            context.getBeanFactory().addBeanPostProcessor(new WrappingPostProcessor());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Customizer;
        }

        @Override
        public int hashCode() {
            return Customizer.class.hashCode();
        }
    }

    /** spring.factories（ContextCustomizerFactory）で登録する。 */
    public static final class Factory implements ContextCustomizerFactory {
        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            return new Customizer();
        }
    }
}
