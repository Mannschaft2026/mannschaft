package com.mannschaft.app.config;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>DATE 列が「接続作成時の JVM 既定 TZ」で整形されて 1 日ずれる事故のドライバ契約</b>（CMP-261123-1334 / AC-1）。
 *
 * <p>{@link TimeZoneConfig} は起動途中の {@code @PostConstruct} で JVM 既定 TZ を UTC から JST へ切り替える。
 * Hibernate の {@code DateJdbcType} は LocalDate を {@code java.sql.Date.valueOf(ld)}（その時点の JVM 既定 TZ の 0 時）にして
 * Calendar 無しの {@code setDate} で書く。Connector/J は既定（{@code cacheDefaultTimeZone=true}）だと、
 * 接続作成時の JVM 既定 TZ をキャッシュして整形するため、切替前（UTC）に作られた接続では前日になる。</p>
 *
 * <p>本テストは「UTC で接続を作成 → JVM 既定を JST に切替 → Hibernate と同じ書き方で書く」を実 MySQL で再現し、
 * 共有 {@code application.yml} の Hikari {@code data-source-properties} を接続プロパティとして適用したとき
 * 日付が保たれることを固定する。{@code cacheDefaultTimeZone: false} が yml から消えると赤になる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("DATE 列の TZ ずれ防波堤: ドライバ契約（CMP-261123-1334）")
class DateColumnTimeZoneShiftDriverContractTest {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("date_shift").withUsername("test").withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"));

    private static final List<LocalDate> DATES = List.of(
            LocalDate.of(2026, 3, 10), LocalDate.of(2026, 1, 31), LocalDate.of(2026, 12, 31),
            LocalDate.of(2028, 2, 29), LocalDate.of(2027, 1, 1));

    @BeforeAll
    static void start() throws Exception {
        MYSQL.start();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "test", "test");
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE d (id INT PRIMARY KEY, v DATE NOT NULL)");
        }
    }

    @AfterAll
    static void stop() {
        MYSQL.stop();
    }

    @Test
    @DisplayName("共有 application.yml の接続プロパティを適用すれば、UTC で作った接続を JST 切替後に使っても日付が保たれる")
    void appliedDataSourcePropertiesKeepDateAfterJvmTimeZoneSwitch() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", "test");
        props.setProperty("password", "test");
        props.putAll(sharedDataSourceProperties());
        assertThat(roundTrip(props)).isEmpty();
    }

    @Test
    @DisplayName("対照実験: 既定の接続（cacheDefaultTimeZone 既定）だと UTC 作成→JST 切替で日付が前日にずれる（再現条件の確認）")
    void defaultDriverShiftsDate() throws Exception {
        Properties props = new Properties();
        props.setProperty("user", "test");
        props.setProperty("password", "test");
        assertThat(roundTrip(props)).as("ずれが再現しない環境では本契約テストは無意味").isNotEmpty();
    }

    /** UTC で接続を作り、JVM 既定を JST に切り替えて Hibernate と同じ setDate で書く。ずれた日付の説明を返す。 */
    private static List<String> roundTrip(Properties props) throws Exception {
        TimeZone original = TimeZone.getDefault();
        List<String> mismatches = new ArrayList<>();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), props)) {
                TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
                try (Statement s = c.createStatement()) {
                    s.execute("DELETE FROM d");
                }
                int id = 0;
                for (LocalDate ld : DATES) {
                    id++;
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO d (id, v) VALUES (?, ?)")) {
                        ps.setInt(1, id);
                        ps.setDate(2, java.sql.Date.valueOf(ld));
                        ps.executeUpdate();
                    }
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT DATE_FORMAT(v, '%Y-%m-%d') FROM d WHERE id = " + id)) {
                        rs.next();
                        if (!ld.toString().equals(rs.getString(1))) {
                            mismatches.add(ld + " -> " + rs.getString(1));
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
        return mismatches;
    }

    private static Properties sharedDataSourceProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(
                Paths.get("src", "main", "resources", "application.yml").toAbsolutePath()));
        yaml.afterPropertiesSet();
        Properties all = yaml.getObject();
        String prefix = "spring.datasource.hikari.data-source-properties.";
        Properties out = new Properties();
        // 真偽値は String でなく Boolean のまま入るため stringPropertyNames() は使わない。
        for (Map.Entry<Object, Object> entry : all.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith(prefix)) {
                out.setProperty(key.substring(prefix.length()), String.valueOf(entry.getValue()));
            }
        }
        assertThat(out).as("application.yml に Hikari data-source-properties が読めること").isNotEmpty();
        return out;
    }
}
