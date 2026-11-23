package com.mannschaft.app.actionmemo;

import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.event.ActionMemoAnonymizationEventListener;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.service.ActionMemoPublishingService;
import com.mannschaft.app.actionmemo.service.ActionMemoService;
import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261123-1201 行動メモの日ごとの公開記録（{@code action_memo_daily_posts}）の実DB試練。
 *
 * <p>メモの日付を動かすと {@code timeline_post_id} が外れるため、「日Aの全メモを別日へ移して戻し、再公開」すると
 * 旧個人投稿を見つけられず新旧2件が残っていた。日ごとの公開記録を (user_id, memo_date) で持ち、
 * publishDaily が「記録の投稿 ID」と「当日メモの投稿 ID」の和集合から旧投稿を探して論理削除する。
 * 同じ利用者・同じ日の公開は、旧 ID とメモを読む前から公開完了まで直列化する（メモ行の悲観ロック）。</p>
 *
 * <p>クラスに {@code @Transactional} を付けない（コミット後の状態を別接続で検証する。{@link ActionMemoTeamPostSplitIT} と同じ）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("行動メモ 日ごとの公開記録（実DB）")
class ActionMemoDailyPostIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate MEMO_DATE = LocalDate.of(2026, 3, 15);

    @Autowired private ActionMemoPublishingService publishingService;
    @Autowired private ActionMemoService actionMemoService;
    @Autowired private ActionMemoRepository memoRepository;
    @Autowired private ActionMemoAnonymizationEventListener anonymizationListener;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> ownerId = insertUser("am-daily-" + suffix + "@example.com"));
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.update("DELETE FROM action_memo_daily_posts WHERE user_id = ?", ownerId);
        jdbcTemplate.update("DELETE FROM timeline_posts WHERE user_id = ?", ownerId);
        jdbcTemplate.update("DELETE FROM action_memos WHERE user_id = ?", ownerId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", ownerId);
    }

    @Test
    @DisplayName("全メモを別の日へ移して戻し、再公開しても旧個人投稿が論理削除され、生存投稿は1件になる")
    void 全メモ移動して戻し再公開しても旧投稿が消える() {
        Long memoId = newMemo(MEMO_DATE, "動かすメモ");
        Long post1 = publishDaily(MEMO_DATE);

        setAuthentication(ownerId);
        changeDate(memoId, MEMO_DATE.minusDays(1));
        changeDate(memoId, MEMO_DATE);
        assertThat(memoTimelinePostId(memoId)).as("日付変更でメモの投稿 ID は外れている").isNull();

        Long post2 = publishDaily(MEMO_DATE);

        assertThat(post2).isNotEqualTo(post1);
        assertThat(deletedAtOf(post1)).as("旧個人投稿は論理削除される").isNotNull();
        assertThat(alivePersonalPosts()).as("生存 PERSONAL 投稿は新しい1件のみ").containsExactly(post2);
        assertThat(dailyPostId(MEMO_DATE)).isEqualTo(post2);
    }

    @Test
    @DisplayName("初回公開で日ごとの記録ができ、再公開で投稿 ID が更新される（行は1つのまま）")
    void 初回公開で記録ができ再公開で更新される() {
        newMemo(MEMO_DATE, "初回");
        assertThat(dailyPostRows(MEMO_DATE)).isZero();

        Long post1 = publishDaily(MEMO_DATE);
        assertThat(dailyPostRows(MEMO_DATE)).isEqualTo(1);
        assertThat(dailyPostId(MEMO_DATE)).isEqualTo(post1);

        Long post2 = publishDaily(MEMO_DATE);
        assertThat(dailyPostRows(MEMO_DATE)).isEqualTo(1);
        assertThat(dailyPostId(MEMO_DATE)).isEqualTo(post2);
    }

    @Test
    @DisplayName("メモを論理削除しても日ごとの記録は残り、再公開で旧投稿を見失わない")
    void メモ削除で記録は消えない() {
        Long memoId = newMemo(MEMO_DATE, "消すメモ");
        newMemo(MEMO_DATE, "残すメモ");
        Long post1 = publishDaily(MEMO_DATE);

        setAuthentication(ownerId);
        actionMemoService.deleteMemo(memoId, ownerId);

        assertThat(dailyPostId(MEMO_DATE)).isEqualTo(post1);
        Long post2 = publishDaily(MEMO_DATE);
        assertThat(deletedAtOf(post1)).isNotNull();
        assertThat(alivePersonalPosts()).containsExactly(post2);
    }

    @Test
    @DisplayName("同じ日の公開を同時に2回呼んでも、生存投稿は1件・記録はその1件を指す（UNIQUE 競合で失敗しない）")
    void 同時公開でも生存投稿は1件() throws Exception {
        for (int round = 0; round < 3; round++) {
            LocalDate date = MEMO_DATE.plusDays(round);
            newMemo(date, "並行公開 " + round);
            newMemo(date, "並行公開 追加 " + round);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                List<Future<Long>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    Callable<Long> task = () -> {
                        ready.countDown();
                        go.await();
                        return publishDaily(date);
                    };
                    futures.add(pool.submit(task));
                }
                ready.await(10, TimeUnit.SECONDS);
                go.countDown();
                for (Future<Long> f : futures) {
                    f.get(60, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            List<Long> alive = jdbcTemplate.queryForList(
                    "SELECT id FROM timeline_posts WHERE user_id = ? AND scope_type = 'PERSONAL' "
                            + "AND deleted_at IS NULL AND content LIKE ?",
                    Long.class, ownerId, "## " + date + "%");
            assertThat(alive).as("round " + round + ": その日の生存投稿は1件").hasSize(1);
            assertThat(dailyPostRows(date)).isEqualTo(1);
            assertThat(dailyPostId(date)).isEqualTo(alive.get(0));
        }
    }

    @Test
    @DisplayName("退会の即時匿名化で日ごとの記録が削除される")
    void 退会の即時匿名化で記録が消える() throws Exception {
        newMemo(MEMO_DATE, "退会前");
        publishDaily(MEMO_DATE);
        assertThat(dailyPostRows(MEMO_DATE)).isEqualTo(1);

        anonymizationListener.onUserAnonymized(new UserAnonymizedEvent(ownerId, "am-daily@example.com"));

        long deadline = System.currentTimeMillis() + 15_000;
        while (dailyPostRows(MEMO_DATE) > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(dailyPostRows(MEMO_DATE)).as("日ごとの記録は退会受付直後に削除される").isZero();
    }

    @Test
    @DisplayName("30日後の消去・手動再試行の経路でも、残っていた日ごとの記録が回収される")
    void 消去と再試行の経路で記録が回収される() {
        newMemo(MEMO_DATE, "退会前");
        publishDaily(MEMO_DATE);
        assertThat(dailyPostRows(MEMO_DATE)).isEqualTo(1);

        assertThat(anonymizationListener.retryPurge(ownerId)).isTrue();

        assertThat(dailyPostRows(MEMO_DATE)).as("retryPurge で残りが回収される").isZero();
    }

    // ── helpers ────────────────────────────────────────────────

    private Long newMemo(LocalDate date, String content) {
        return transactionTemplate.execute(tx -> memoRepository.save(ActionMemoEntity.builder()
                .userId(ownerId)
                .memoDate(date)
                .content(content)
                .category(ActionMemoCategory.WORK)
                .build()).getId());
    }

    private Long publishDaily(LocalDate date) {
        PublishDailyRequest req = new PublishDailyRequest();
        req.setMemoDate(date);
        return publishingService.publishDaily(req, ownerId).getTimelinePostId();
    }

    private void changeDate(Long memoId, LocalDate newDate) {
        UpdateActionMemoRequest req = new UpdateActionMemoRequest();
        req.setMemoDate(newDate);
        actionMemoService.updateMemo(memoId, req, ownerId);
    }

    private Long memoTimelinePostId(Long memoId) {
        return jdbcTemplate.queryForObject("SELECT timeline_post_id FROM action_memos WHERE id = ?", Long.class, memoId);
    }

    private Object deletedAtOf(Long postId) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM timeline_posts WHERE id = ?", Object.class, postId);
    }

    private List<Long> alivePersonalPosts() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM timeline_posts WHERE user_id = ? AND scope_type = 'PERSONAL' AND deleted_at IS NULL",
                Long.class, ownerId);
    }

    private long dailyPostRows(LocalDate date) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM action_memo_daily_posts WHERE user_id = ? AND memo_date = ?",
                Long.class, ownerId, date);
        return n == null ? 0 : n;
    }

    private Long dailyPostId(LocalDate date) {
        return jdbcTemplate.queryForObject(
                "SELECT timeline_post_id FROM action_memo_daily_posts WHERE user_id = ? AND memo_date = ?",
                Long.class, ownerId, date);
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'AM日次', 'テスト', 'AM日次テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
