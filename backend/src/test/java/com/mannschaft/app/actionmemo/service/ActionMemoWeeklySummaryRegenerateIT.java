package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
    private BlogPostRepository blogPostRepository;

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

    /**
     * 定期実行と手動再生成が同時に走る状況を、実 DB のロック待ちで再現する。
     * 相手の実行が同 slug の新記事を INSERT して未確定の間に、こちらの再生成の INSERT を走らせる
     * （こちらの INSERT が相手のトランザクションの保持するロックを実際に待っていることを
     * {@code performance_schema.data_lock_waits} で確かめてから相手を確定させる）。
     * 相手の確定後、こちらは一意制約違反で失敗せず、やり直しで相手の記事を論理削除して差し替え、
     * 1 件だけ生存させなければならない（相手の記事が論理削除されていることが、やり直しが起きた証拠）。
     */
    @Test
    @DisplayName("同じ週の再生成が同時に走っても一意制約違反で失敗せず、生存する週次まとめは 1 件だけになる")
    void regenerateForAll_同時実行でも失敗しない() throws Exception {
        LocalDate from = LocalDate.of(2041, 11, 25);
        LocalDate to = LocalDate.of(2041, 12, 1);
        String slug = "weekly-20411125-20411201";
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-concurrent-" + UUID.randomUUID() + "@example.com")
                    .lastName("週次")
                    .firstName("同時")
                    .displayName("週次同時")
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

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong otherConnectionId = new AtomicLong();
        AtomicLong otherPostId = new AtomicLong();
        try {
            // 相手の実行: 同 slug の新記事を INSERT して未確定のまま保持する
            Future<?> other = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                BlogPostEntity otherPost = blogPostRepository.saveAndFlush(BlogPostEntity.builder()
                        .userId(userId).authorId(userId).title("相手の週次まとめ").slug(slug).body("相手の本文")
                        .readingTimeMinutes((short) 1).build());
                otherPostId.set(otherPost.getId());
                // 同じトランザクションの接続 ID（ロックを保持する側の識別に使う）
                otherConnectionId.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                inserted.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(inserted.await(30, TimeUnit.SECONDS)).as("相手の INSERT").isTrue();

            Future<ActionMemoWeeklySummaryService.RegenerationResult> mine =
                    executor.submit(() -> weeklySummaryService.regenerateForAll(from, to));
            awaitLockWait(mine, otherConnectionId.get(), "insert into blog_posts");
            release.countDown();
            other.get(30, TimeUnit.SECONDS);

            ActionMemoWeeklySummaryService.RegenerationResult result = mine.get(30, TimeUnit.SECONDS);
            assertThat(result.failedCount()).as("同時実行の競合は失敗件数に計上されない").isZero();
            assertThat(result.regeneratedCount()).isPositive();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted_at FROM blog_posts WHERE user_id = ? AND slug = ? ORDER BY id",
                userId, slug);
        assertThat(rows.stream().filter(r -> r.get("deleted_at") == null)).as("生存する週次まとめは 1 件だけ").hasSize(1);
        assertThat(rows.stream().filter(r -> ((Number) r.get("id")).longValue() == otherPostId.get()))
                .as("相手の記事はやり直しで論理削除されている")
                .singleElement()
                .satisfies(r -> assertThat(r.get("deleted_at")).isNotNull());
    }

    /**
     * 既存の週次まとめがある状態で同じユーザーの差し替えが同時に走る状況を、実 DB のロック待ちで再現する（台帳 CMP-261127-1817）。
     * 相手の差し替えが既存記事（version=1）を論理削除して新記事を INSERT し、未確定の間に、こちらの再生成を走らせる。
     * こちらは既存記事を（相手の未確定の変更が見えない一貫読み取りで）version=1 のまま読み、その論理削除の UPDATE が
     * 相手の保持する行ロックを実際に待っていることを {@code performance_schema.data_lock_waits} で確かめてから相手を確定させる。
     * 相手の確定後、こちらの UPDATE は版の一致しない行に当たって更新 0 行（楽観ロック衝突）になる。それで失敗せず、
     * やり直しで相手の新記事を論理削除して差し替え、1 件だけ生存させなければならない
     * （相手の新記事が論理削除されていることが、やり直しが起きた証拠）。
     */
    @Test
    @DisplayName("既存記事がある状態で同じ週の差し替えが同時に走っても楽観ロック衝突で失敗せず、生存する週次まとめは 1 件だけになる")
    void regenerateForUser_既存記事ありの同時実行でも楽観ロック衝突で失敗しない() throws Exception {
        LocalDate from = LocalDate.of(2041, 12, 2);
        LocalDate to = LocalDate.of(2041, 12, 8);
        String slug = "weekly-20411202-20411208";
        Long userId = transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email("weekly-concurrent-existing-" + UUID.randomUUID() + "@example.com")
                    .lastName("週次")
                    .firstName("既存")
                    .displayName("週次既存")
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
        // 既存の週次まとめ（version=1）を作っておく
        assertThat(weeklySummaryService.regenerateForUser(userId, from, to)).isTrue();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch replaced = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong otherConnectionId = new AtomicLong();
        AtomicLong otherPostId = new AtomicLong();
        try {
            // 相手の差し替え: 既存記事を論理削除（行ロックを保持）して新記事を INSERT し、未確定のまま保持する
            Future<?> other = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                BlogPostEntity existing = blogPostRepository.findByUserIdAndSlug(userId, slug).orElseThrow();
                existing.softDelete();
                blogPostRepository.saveAndFlush(existing);
                BlogPostEntity otherPost = blogPostRepository.saveAndFlush(BlogPostEntity.builder()
                        .userId(userId).authorId(userId).title("相手の週次まとめ").slug(slug).body("相手の本文")
                        .readingTimeMinutes((short) 1).build());
                otherPostId.set(otherPost.getId());
                otherConnectionId.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                replaced.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(replaced.await(30, TimeUnit.SECONDS)).as("相手の差し替え").isTrue();

            Future<Boolean> mine = executor.submit(() -> weeklySummaryService.regenerateForUser(userId, from, to));
            awaitLockWait(mine, otherConnectionId.get(), "update blog_posts");
            release.countDown();
            other.get(30, TimeUnit.SECONDS);

            assertThat(mine.get(30, TimeUnit.SECONDS)).as("楽観ロック衝突で失敗せず差し替える").isTrue();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, deleted_at FROM blog_posts WHERE user_id = ? AND slug = ? ORDER BY id",
                userId, slug);
        assertThat(rows).as("既存・相手・こちらの 3 件").hasSize(3);
        assertThat(rows.stream().filter(r -> r.get("deleted_at") == null)).as("生存する週次まとめは 1 件だけ").hasSize(1);
        assertThat(rows.stream().filter(r -> ((Number) r.get("id")).longValue() == otherPostId.get()))
                .as("相手の新記事はやり直しで論理削除されている")
                .singleElement()
                .satisfies(r -> assertThat(r.get("deleted_at")).isNotNull());
    }

    /**
     * 相手のトランザクション（接続 ID {@code blockingConnectionId}）が保持するロックを、
     * {@code blog_posts} への文（先頭が {@code statementPrefix} の INSERT・UPDATE）が実際に待っている状態になるまで待つ。
     * {@code performance_schema.data_lock_waits} / {@code data_locks} はアプリ用ユーザーに SELECT 権限が無いため、root で参照する。
     *
     * <p>{@code information_schema.innodb_trx} は InnoDB が内部キャッシュから返し、そのキャッシュは
     * <b>直前の読み取りから 0.1 秒以上空いたときにしか更新されない</b>。間隔を詰めて問い合わせ続けると、
     * 最初に読んだ時点（こちらの再生成がまだ読み取りだけで、更新するトランザクションになる前）の内容のまま
     * 固まり、実際にはロック待ちに入っていても検出できない（ロック待ち自体は {@code performance_schema} 側に
     * 出ているのに 30 秒待って失敗する、負荷の高いときだけの不安定さの原因だった）。そのため 0.2 秒間隔で問い合わせる。</p>
     */
    private void awaitLockWait(Future<?> contender, long blockingConnectionId, String statementPrefix)
            throws SQLException {
        // 挿入時の暗黙ロックは待つ側のスレッドが明示ロックへ変換するため、data_lock_waits の BLOCKING_THREAD_ID は
        // 待つ側を指すことがある。保持側はトランザクション ID から innodb_trx の接続 ID で識別する。
        String sql = "SELECT COUNT(*) FROM performance_schema.data_lock_waits w "
                + "JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "JOIN information_schema.innodb_trx blocking ON blocking.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID "
                + "JOIN information_schema.innodb_trx requesting "
                + "ON requesting.trx_id = w.REQUESTING_ENGINE_TRANSACTION_ID "
                + "WHERE l.OBJECT_SCHEMA = DATABASE() AND l.OBJECT_NAME = 'blog_posts' "
                + "AND blocking.trx_mysql_thread_id = ? "
                + "AND LOWER(requesting.trx_query) LIKE CONCAT(?, '%')";
        try (Connection root = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             PreparedStatement statement = root.prepareStatement(sql)) {
            statement.setLong(1, blockingConnectionId);
            statement.setString(2, statementPrefix);
            await("再生成の " + statementPrefix + " が相手のロック待ちに入る")
                    .atMost(Duration.ofSeconds(30))
                    .pollInterval(Duration.ofMillis(200))
                    .failFast("ロック待ちに入る前に再生成が終わった（競合を再現できていない）", contender::isDone)
                    .until(() -> {
                        try (ResultSet rs = statement.executeQuery()) {
                            return rs.next() && rs.getInt(1) > 0;
                        }
                    });
        }
    }
}
