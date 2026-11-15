package com.mannschaft.app.moduleevent.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;

/**
 * 実機E2E検体の定義登録だけを検査する。DB・full App・外部serviceは起動しない。
 * 検体は通常のcomponent走査（他のSpringBootTest含む）へ混入せず、起動入口の明示sourceでのみ採用される。
 */
class ModuleEventRealE2eBootstrapBoundaryTest {
    private static final Set<String> FIXTURE_BEANS = Set.of("l1mE2eWorld", "l1mE2eDomain", "l1mE2eResolver", "l1mE2eListeners");

    @Test
    void 通常component走査では検体を採用しない() {
        var scanner = new ClassPathScanningCandidateComponentProvider(true);
        long count = scanner.findCandidateComponents(ModuleEventRealE2eFixtures.class.getPackageName()).stream()
                .filter(definition -> ModuleEventRealE2eFixtures.class.getName().equals(definition.getBeanClassName()))
                .count();

        assertEquals(0, count, "明示source以外のcomponent走査へfixtureを混ぜない");
    }

    @Test
    void 明示sourceでは既存四beanの定義だけを登録できる() {
        var registry = new DefaultListableBeanFactory();
        new AnnotatedBeanDefinitionReader(registry).register(ModuleEventRealE2eFixtures.class);
        new ConfigurationClassPostProcessor().postProcessBeanDefinitionRegistry(registry);
        Set<String> actual = Arrays.stream(registry.getBeanDefinitionNames())
                .filter(name -> name.startsWith("l1mE2e")).collect(java.util.stream.Collectors.toSet());

        assertEquals(FIXTURE_BEANS, actual);
        for (String name : FIXTURE_BEANS) {
            assertEquals(name, registry.getBeanDefinition(name).getFactoryMethodName());
        }
        // refresh/getBeanを呼ばず、DB依存を実体化しない。
        assertFalse(registry.containsSingleton("l1mE2eDomain"));
    }
}
