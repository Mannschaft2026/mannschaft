package com.mannschaft.app.common.testing;

import java.util.List;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * CMP-261111-0508（甲）: コンテキストのロード地点（ローダーの構築中・refresh の直前）で {@code [ci-ctx]} の観測を仕掛ける
 * ContextCustomizer。観測の中身と方式は {@link ContextKeyTestExecutionListener} を参照。
 *
 * <p>全インスタンスを等価（equals / hashCode が定数）にしてあるので、MCC（コンテキストキャッシュのキー）を割らない。
 * キー計算には customizer のクラス名が入るが、全 MCC 共通なので Orderer・Listener のキーの一致には影響しない。</p>
 */
public final class ContextLoadObservingCustomizer implements ContextCustomizer {

    @Override
    public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
        ContextKeyTestExecutionListener.observeLoad(context, mergedConfig);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ContextLoadObservingCustomizer;
    }

    @Override
    public int hashCode() {
        return ContextLoadObservingCustomizer.class.hashCode();
    }

    /** spring.factories（ContextCustomizerFactory）で登録する。 */
    public static final class Factory implements ContextCustomizerFactory {
        @Override
        public ContextCustomizer createContextCustomizer(Class<?> testClass,
                List<ContextConfigurationAttributes> configAttributes) {
            return new ContextLoadObservingCustomizer();
        }
    }
}
