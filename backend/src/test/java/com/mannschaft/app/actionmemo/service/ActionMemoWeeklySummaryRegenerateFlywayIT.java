package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行動メモ週次まとめの再生成（同一週を 2 回生成）が、生存行の slug 一意制約
 * （V267: {@code uq_bp_slug_user_active}）下でも成功することを実 Flyway スキーマで固定する。
 *
 * <p>通常の統合テストは {@code ddl-auto=create} で生成列を含む一意制約が入らないため、本番と同じ
 * Flyway スキーマを使う。再生成は同一トランザクション内で「既存記事の論理削除 → 同 slug の新規 INSERT」を行う。
 * ID が IDENTITY のため INSERT は即時に走る一方、論理削除の UPDATE は flush まで保留されるので、
 * 論理削除を INSERT より先に確定させないと旧行が生存扱いのまま一意制約違反になる。</p>
 *
 * <p>テスト全体のトランザクションは置かず、本番の手動再生成と同じく 1 回の再生成を 1 トランザクションとする。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.actionmemo.service.ActionMemoWeeklySummaryRegenerateFlywayIT#isDockerAvailable")
@DisplayName("行動メモ週次まとめの再生成と生存行 slug 一意制約（Flyway 実スキーマ）")
class ActionMemoWeeklySummaryRegenerateFlywayIT {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_actionmemo_weekly_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ActionMemoWeeklySummaryService weeklySummaryService;

    @Test
    @DisplayName("同一週を 2 回生成すると旧記事は論理削除され、生存する週次まとめは新しい 1 件だけになる")
    void regenerateForUser_同一週の2回生成で一意制約違反にならない() {
        LocalDate from = LocalDate.of(2026, 11, 2);
        LocalDate to = LocalDate.of(2026, 11, 8);
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-regenerate@example.com")
                    .lastName("週次")
                    .firstName("再生成")
                    .displayName("週次再生成")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .isSearchable(true)
                    .build();
            em.persist(user);
            em.persist(ActionMemoEntity.builder()
                    .userId(user.getId()).memoDate(from.plusDays(1)).content("朝散歩").build());
            em.persist(ActionMemoEntity.builder()
                    .userId(user.getId()).memoDate(from.plusDays(3)).content("読書").build());
            return user.getId();
        });

        assertThat(weeklySummaryService.regenerateForUser(userId, from, to)).isTrue();
        assertThat(weeklySummaryService.regenerateForUser(userId, from, to)).isTrue();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted_at FROM blog_posts WHERE user_id = ? AND slug = ? ORDER BY id",
                userId, "weekly-20261102-20261108");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("deleted_at")).as("1 回目の記事は論理削除される").isNotNull();
        assertThat(rows.get(1).get("deleted_at")).as("2 回目の記事が生存する").isNull();
    }

    @Test
    @DisplayName("トランザクション外の経路（全ユーザー再生成）でも同一週の 2 回生成は失敗にならない")
    void regenerateForAll_同一週の2回生成で失敗しない() {
        LocalDate from = LocalDate.of(2026, 11, 9);
        LocalDate to = LocalDate.of(2026, 11, 15);
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-regenerate-all@example.com")
                    .lastName("週次")
                    .firstName("全員")
                    .displayName("週次全員")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .isSearchable(true)
                    .build();
            em.persist(user);
            em.persist(ActionMemoEntity.builder()
                    .userId(user.getId()).memoDate(from.plusDays(2)).content("ジョギング").build());
            return user.getId();
        });

        for (int i = 0; i < 2; i++) {
            ActionMemoWeeklySummaryService.RegenerationResult result = weeklySummaryService.regenerateForAll(from, to);
            assertThat(result.failedCount()).as("%d 回目の生成で失敗が出ない", i + 1).isZero();
            assertThat(result.regeneratedCount()).isEqualTo(1);
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted_at FROM blog_posts WHERE user_id = ? AND slug = ? ORDER BY id",
                userId, "weekly-20261109-20261115");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("deleted_at")).isNotNull();
        assertThat(rows.get(1).get("deleted_at")).isNull();
    }
}
