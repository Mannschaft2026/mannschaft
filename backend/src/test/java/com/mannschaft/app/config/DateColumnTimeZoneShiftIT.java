package com.mannschaft.app.config;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.VillageMeetupCandidateDateEntity;
import com.mannschaft.app.village.repository.VillageMeetupCandidateDateRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>DATE 列の保存・更新で日付がずれないこと、および日時の格納基準の固定</b>（CMP-261123-1334 / AC-2・AC-3）。
 *
 * <p>旧原因（CMP-261123-1335 で解消済み。本テストは最終防衛の検証として残す）: JVM を UTC で起動した状態では、
 * 起動途中の {@code @PostConstruct} が JVM 既定 TZ を JST へ切り替える前に作られた Hikari 接続が存在し、その接続では DATE 列が 1 日前に書かれていた。
 * 現在は {@code ServerTimeZoneEnvironmentPostProcessor} が Bean 生成前に確定するため通常は起きないが、後から既定 TZ が変わっても守れることを確かめる。
 * 起動順序に依存すると再現が不安定になるため、本テストは「JVM 既定を UTC にして既存接続を破棄→新規接続を作成→
 * JVM 既定を JST へ戻して使う」を明示的に行い、決定的に再現する（実行時の JVM TZ に依らず同じ状況になる）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("DATE 列 TZ ずれ防波堤 IT（CMP-261123-1334）")
class DateColumnTimeZoneShiftIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private VillageMeetupCandidateDateRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;

    private static final List<LocalDate> DATES = List.of(
            LocalDate.of(2026, 3, 10), LocalDate.of(2026, 1, 31), LocalDate.of(2026, 12, 31),
            LocalDate.of(2028, 2, 29), LocalDate.of(2027, 1, 1));

    @Test
    @DisplayName("AC-2: UTC で作られた接続を JST 切替後に使っても、保存→読出→更新→読出で DATE 列の生値・読出値が元と一致する")
    void dateColumnSurvivesSaveAndUpdateOnConnectionsCreatedBeforeZoneSwitch() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            recreatePoolConnectionsUnderUtc();
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));

            for (LocalDate ld : DATES) {
                VillageMeetupCandidateDateEntity e = new VillageMeetupCandidateDateEntity();
                e.setMeetupId(UUID.randomUUID());
                e.setCandidateDate(ld);
                e.setSortOrder(1);
                e.setCreatedAt(LocalDateTime.of(2026, 3, 10, 10, 0));
                UUID id = repository.saveAndFlush(e).getId();

                assertThat(rawDate(id)).as("保存直後の生値 %s", ld).isEqualTo(ld.toString());
                assertThat(repository.findById(id).orElseThrow().getCandidateDate()).isEqualTo(ld);

                // 別項目だけ変更しても Hibernate は全列を書き直す（旧不具合では更新のたびにさらに 1 日ずれた）
                for (int i = 2; i <= 3; i++) {
                    VillageMeetupCandidateDateEntity loaded = repository.findById(id).orElseThrow();
                    loaded.setSortOrder(i);
                    repository.saveAndFlush(loaded);
                    assertThat(rawDate(id)).as("%d 回目の更新後の生値 %s", i - 1, ld).isEqualTo(ld.toString());
                    assertThat(repository.findById(id).orElseThrow().getCandidateDate()).isEqualTo(ld);
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    @DisplayName("AC-3: LocalDateTime 10:00 は生値 01:00（UTC 壁時計）で格納され、往復で 10:00 に戻る。LocalTime も同様")
    void dateTimeAndTimeStorageBasisIsUtc() {
        VillageMeetupCandidateDateEntity e = new VillageMeetupCandidateDateEntity();
        e.setMeetupId(UUID.randomUUID());
        e.setCandidateDate(LocalDate.of(2026, 3, 10));
        e.setCandidateTime(LocalTime.of(10, 0));
        e.setSortOrder(1);
        e.setCreatedAt(LocalDateTime.of(2026, 3, 10, 10, 0));
        UUID id = repository.saveAndFlush(e).getId();

        String idHex = id.toString().replace("-", "");
        assertThat(jdbc.queryForObject(
                "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') FROM village_meetup_candidate_dates WHERE id = UNHEX(?)",
                String.class, idHex)).isEqualTo("2026-03-10 01:00:00");
        assertThat(jdbc.queryForObject(
                "SELECT TIME_FORMAT(candidate_time, '%H:%i:%s') FROM village_meetup_candidate_dates WHERE id = UNHEX(?)",
                String.class, idHex)).isEqualTo("01:00:00");

        VillageMeetupCandidateDateEntity loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 3, 10, 10, 0));
        assertThat(loaded.getCandidateTime()).isEqualTo(LocalTime.of(10, 0));
    }

    private String rawDate(UUID id) {
        return jdbc.queryForObject(
                "SELECT DATE_FORMAT(candidate_date, '%Y-%m-%d') FROM village_meetup_candidate_dates WHERE id = UNHEX(?)",
                String.class, id.toString().replace("-", ""));
    }

    /** JVM 既定を UTC にして既存接続を破棄し、UTC で新規接続を作らせる（旧実装の切替前に作られた接続の再現）。 */
    private void recreatePoolConnectionsUnderUtc() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        pool.softEvictConnections();
        long deadline = System.currentTimeMillis() + 20_000;
        // 追い出し後の補充（最小アイドル数まで）が UTC のうちに完了するのを待つ。
        while (pool.getIdleConnections() < hikari.getMinimumIdle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(pool.getIdleConnections())
                .as("UTC で再作成された接続が最小アイドル数まで揃っていること（未達だと JST 作成の接続が混ざり得る）")
                .isGreaterThanOrEqualTo(hikari.getMinimumIdle());
    }
}
