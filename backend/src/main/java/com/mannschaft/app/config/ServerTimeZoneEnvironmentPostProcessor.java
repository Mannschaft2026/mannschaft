package com.mannschaft.app.config;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.TimeZone;

/**
 * JVM のデフォルトタイムゾーンを業務ローカル時刻の基準ゾーンへ、<b>ApplicationContext の生成前</b>に確定させる
 * （.claudecode.md §20 / CMP-261123-1335）。
 *
 * <p>基準ゾーンは {@link UserZoneLocalDateTimeParser#SERVER_ZONE}（アプリ層の壁時計ゾーン）を
 * <b>唯一の正</b>として参照する。Dockerfile・Terraform・{@code TZ} 環境変数には併記しない。</p>
 *
 * <p><b>なぜ {@code @PostConstruct} ではないのか:</b> 旧 {@code TimeZoneConfig} の {@code @PostConstruct} は
 * Bean 生成の途中で動くため、DataSource（Hikari）の最初の接続が先に作られうる。MySQL Connector/J は接続作成時の
 * JVM 既定 TZ を接続に固定（{@code cacheDefaultTimeZone}）するので、切替前に作られた接続は DATE 列の
 * バインドが前日にずれる。EnvironmentPostProcessor は Bean 生成より前に動く。{@code main()} 先頭での設定は
 * {@code @SpringBootTest} が {@code main} を通らず経路が割れるため採らない。{@code SpringApplication} 経由なら
 * 本番・テストとも同じ経路になる。</p>
 *
 * <p>食い違いの最終防衛線は {@link TimeZoneConfig} の DataSource 番人。</p>
 */
public class ServerTimeZoneEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        TimeZone.setDefault(TimeZone.getTimeZone(UserZoneLocalDateTimeParser.SERVER_ZONE));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
