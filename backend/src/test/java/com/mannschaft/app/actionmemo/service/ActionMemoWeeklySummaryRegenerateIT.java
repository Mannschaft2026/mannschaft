package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行動メモ週次まとめの再生成（同一週を 2 回生成）が、生存行の slug 一意制約
 * （V267: {@code uq_bp_slug_user_active}）下でも成功することを実 DB で固定する（台帳 CMP-261123-2341）。
 *
 * <p>一意制約は {@code BlogPostEntity} の生成列 {@code active_unique_key} のマッピングで {@code ddl-auto=create} の
 * スキーマにも入るので、共有の統合テスト構成（{@link AbstractMySqlIntegrationTest}）のまま本番と同じ制約で検証できる。
 * 再生成は同一トランザクション内で「既存記事の論理削除 → 同 slug の新規 INSERT」を行う。
 * ID が IDENTITY のため INSERT は即時に走る一方、論理削除の UPDATE は flush まで保留されるので、
 * 論理削除を INSERT より先に確定させないと旧行が生存扱いのまま一意制約違反になる。</p>
 *
 * <p>テスト全体のトランザクションは置かず、本番の手動再生成と同じく 1 回の再生成を 1 トランザクションとする
 * （行は確定するので、メールはテストごとに一意にし、週は他の試験と重ならない遠い日付を使う）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("行動メモ週次まとめの再生成と生存行 slug 一意制約（実 DB）")
class ActionMemoWeeklySummaryRegenerateIT extends AbstractMySqlIntegrationTest {

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
        LocalDate from = LocalDate.of(2041, 11, 4);
        LocalDate to = LocalDate.of(2041, 11, 10);
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-regenerate-" + UUID.randomUUID() + "@example.com")
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
                userId, "weekly-20411104-20411110");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("deleted_at")).as("1 回目の記事は論理削除される").isNotNull();
        assertThat(rows.get(1).get("deleted_at")).as("2 回目の記事が生存する").isNull();
    }

    @Test
    @DisplayName("トランザクション外の経路（全ユーザー再生成）でも同一週の 2 回生成は失敗にならない")
    void regenerateForAll_同一週の2回生成で失敗しない() {
        LocalDate from = LocalDate.of(2041, 11, 11);
        LocalDate to = LocalDate.of(2041, 11, 17);
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-regenerate-all-" + UUID.randomUUID() + "@example.com")
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
            assertThat(result.regeneratedCount()).as("対象ユーザーを含めて再生成した").isPositive();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted_at FROM blog_posts WHERE user_id = ? AND slug = ? ORDER BY id",
                userId, "weekly-20411111-20411117");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("deleted_at")).isNotNull();
        assertThat(rows.get(1).get("deleted_at")).isNull();
    }

    @Test
    @DisplayName("再生成の新規 INSERT が失敗しても旧記事は論理削除されず生存し続ける（論理削除と新規作成は不可分）")
    void regenerateForAll_新規作成が失敗すると旧記事は生存したまま() {
        LocalDate from = LocalDate.of(2041, 11, 18);
        LocalDate to = LocalDate.of(2041, 11, 24);
        String slug = "weekly-20411118-20411124";
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-atomic-" + UUID.randomUUID() + "@example.com")
                    .lastName("週次")
                    .firstName("原子性")
                    .displayName("週次原子性")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .isSearchable(true)
                    .build();
            em.persist(user);
            em.persist(ActionMemoEntity.builder()
                    .userId(user.getId()).memoDate(from.plusDays(1)).content("朝散歩").build());
            return user.getId();
        });

        // 1 回目: 正常に生成（旧記事になる）
        assertThat(weeklySummaryService.regenerateForAll(from, to).failedCount()).isZero();

        // 2 回目: 対象ユーザー・slug の新規 INSERT だけを実 DB のトリガで失敗させる（論理削除の UPDATE は通る）
        String trigger = "trg_weekly_atomic_" + userId;
        jdbcTemplate.execute("CREATE TRIGGER " + trigger + " BEFORE INSERT ON blog_posts FOR EACH ROW "
                + "BEGIN IF NEW.user_id = " + userId + " AND NEW.slug = '" + slug + "' THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced insert failure'; END IF; END");
        ActionMemoWeeklySummaryService.RegenerationResult result;
        try {
            result = weeklySummaryService.regenerateForAll(from, to);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + trigger);
        }

        assertThat(result.failedCount()).as("新規作成の失敗は失敗件数に計上される").isEqualTo(1);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted_at FROM blog_posts WHERE user_id = ? AND slug = ? ORDER BY id",
                userId, slug);
        assertThat(rows).as("新規行は作られていない").hasSize(1);
        assertThat(rows.get(0).get("deleted_at")).as("旧記事は論理削除されず生存する").isNull();
    }
}
