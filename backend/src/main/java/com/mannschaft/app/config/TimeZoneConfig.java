package com.mannschaft.app.config;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.TimeZone;

/**
 * JVM 既定タイムゾーンが {@link UserZoneLocalDateTimeParser#SERVER_ZONE} であることを
 * DataSource の生成時点で検証する番人（.claudecode.md §20 / CMP-261123-1335）。
 *
 * <p>既定 TZ の<b>設定</b>は {@link ServerTimeZoneEnvironmentPostProcessor} が Bean 生成より前に行う。
 * 本クラスは設定が行われなかった・後から書き換えられた場合に、食い違ったまま DB 接続プールが作られて
 * DATE 列が前日で保存される事故を、静かに進めず<b>起動失敗</b>として止める。</p>
 *
 * <p>番人は型 {@link DataSource} の全 Bean が対象。自動構成の単一 DataSource に加え、
 * {@link DataSourceConfig}（primary / replica / routing）と {@link ArchiveDataSourceConfig} を
 * 有効化した場合も同じ順序保証が効く。</p>
 *
 * <p><b>この番人の限界:</b> 検査するのは「DataSource Bean の生成時点の既定 TZ」だけである。
 * 「最初の接続より前」を保証しているのは {@link ServerTimeZoneEnvironmentPostProcessor} であり、
 * 番人は補助にすぎない。DataSource 生成後に誰かが {@code TimeZone.setDefault} を呼ぶ場合や、
 * 番人の登録より前に DataSource が早期生成される場合は素通りする。最終防衛は接続設定
 * {@code cacheDefaultTimeZone=false}（防波堤の PR。接続が既定 TZ を固定しない）である。</p>
 *
 * <p>基準ゾーンを環境変数で可変にしない理由は従来どおり（DB の既存 LocalDateTime 列の解釈が壊れる。
 * 受け皿は CMP-023）。</p>
 */
@Configuration
public class TimeZoneConfig {

    @Bean
    static BeanPostProcessor dataSourceTimeZoneGuard() {
        return new DataSourceTimeZoneGuard();
    }

    /** DataSource Bean の初期化前に JVM 既定 TZ を検査する。 */
    static class DataSourceTimeZoneGuard implements BeanPostProcessor {
        @Override
        public Object postProcessBeforeInitialization(Object bean, String beanName) {
            if (bean instanceof DataSource) {
                var actual = TimeZone.getDefault().toZoneId();
                if (!actual.equals(UserZoneLocalDateTimeParser.SERVER_ZONE)) {
                    throw new IllegalStateException(
                            "DataSource '" + beanName + "' の生成時点で JVM 既定タイムゾーン(" + actual
                                    + ")が SERVER_ZONE(" + UserZoneLocalDateTimeParser.SERVER_ZONE
                                    + ")と一致しない。接続が誤ったゾーンで固定され DATE 列が日ずれするため起動を中止する。"
                                    + "ServerTimeZoneEnvironmentPostProcessor（META-INF/spring.factories）が"
                                    + "実行されていること、DataSource 生成前に TimeZone.setDefault を呼ばないことを確認すること。");
                }
            }
            return bean;
        }
    }
}
