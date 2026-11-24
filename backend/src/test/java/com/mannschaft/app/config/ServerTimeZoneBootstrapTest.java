package com.mannschaft.app.config;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.SpringFactoriesLoader;

import javax.sql.DataSource;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261123-1335 AC-4: JVM 既定 TZ は DataSource の最初の接続が作られるより前に
 * {@code SERVER_ZONE} へ確定していなければならない。
 *
 * <p>JVM を UTC で起動した状態（{@code -Ptest.timezone=UTC} 相当）を再現するため、
 * 各テストの前に既定 TZ を UTC へ戻す。アプリ本体の設定クラスは一切読み込まず、
 * {@code META-INF/spring.factories} 登録の EnvironmentPostProcessor だけで確定することを検査する
 * （{@code @SpringBootTest} も {@code main()} も同じ {@code SpringApplication} 経路を通る）。</p>
 */
@DisplayName("CMP-261123-1335 JVMタイムゾーンはDataSourceより前に確定する")
class ServerTimeZoneBootstrapTest {

    private static final AtomicReference<String> TZ_AT_DATASOURCE_CREATION = new AtomicReference<>();

    private TimeZone original;

    @BeforeEach
    void setUp() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        TZ_AT_DATASOURCE_CREATION.set(null);
    }

    @AfterEach
    void tearDown() {
        TimeZone.setDefault(original);
    }

    @Configuration(proxyBeanMethods = false)
    static class RecordingDataSourceConfig {
        /** DataSource Bean の生成時点（＝接続プールが作られうる最初の時点）の既定 TZ を記録する。 */
        @Bean
        static BeanPostProcessor recorder() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource) {
                        TZ_AT_DATASOURCE_CREATION.set(TimeZone.getDefault().getID());
                    }
                    return bean;
                }
            };
        }

        @Bean
        DataSource dataSource() {
            return new HikariDataSource();
        }
    }

    @Test
    @DisplayName("UTCで起動してもDataSource生成時点のJVM既定TZはSERVER_ZONEである")
    void DataSource生成時点の既定TZはSERVER_ZONE() {
        try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(RecordingDataSourceConfig.class)
                .web(WebApplicationType.NONE)
                .run()) {
            assertThat(TZ_AT_DATASOURCE_CREATION.get())
                    .as("DataSource Bean 生成時点の JVM 既定 TZ")
                    .isEqualTo(UserZoneLocalDateTimeParser.SERVER_ZONE.getId());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    @Import({TimeZoneConfig.class, DataSourceConfig.class, ArchiveDataSourceConfig.class})
    static class AllDataSourcesConfig {
    }

    private static final String[] ALL_DS_PROPS = {
            "--app.datasource.replica.enabled=true", "--app.archive.db.enabled=true"};

    @Test
    @DisplayName("replica・archive を有効化しても、全 DataSource の生成時点でJVM既定TZはSERVER_ZONEで、番人は通過する")
    void replicaとarchiveを有効化しても生成時点はSERVER_ZONE() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AllDataSourcesConfig.class)
                .web(WebApplicationType.NONE)
                
                .run(ALL_DS_PROPS)) {
            assertThat(ctx.getBeansOfType(DataSource.class)).containsKeys("primaryDataSource", "replicaDataSource",
                    "routingDataSource", "archiveDataSource");
        }
    }

    @Test
    @DisplayName("DataSource生成前に既定TZがSERVER_ZONEから食い違えば起動が失敗する（自動構成・replica・archive）")
    void 既定TZが食い違えば起動失敗() {
        // ApplicationContextInitializer は EnvironmentPostProcessor より後に動く＝確定後の書き換えを再現する
        assertThatThrownBy(() -> new SpringApplicationBuilder(AllDataSourcesConfig.class)
                .web(WebApplicationType.NONE)
                
                .initializers(ctx -> TimeZone.setDefault(TimeZone.getTimeZone("UTC")))
                .run(ALL_DS_PROPS))
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("SERVER_ZONE");
    }

    @Test
    @DisplayName("単一DataSource（自動構成と同じ型）でも食い違えば起動が失敗する")
    void 単一DataSourceでも食い違えば起動失敗() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(RecordingDataSourceConfig.class, TimeZoneConfig.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> TimeZone.setDefault(TimeZone.getTimeZone("UTC")))
                .run())
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("EnvironmentPostProcessor は spring.factories に登録されている")
    void EnvironmentPostProcessorが登録されている() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class,
                getClass().getClassLoader()))
                .contains(ServerTimeZoneEnvironmentPostProcessor.class.getName());
    }
}
