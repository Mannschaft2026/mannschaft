package com.mannschaft.app.cms;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.cms.service.BlogSlugConflictRetryAspect;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * slug の同時作成競合（事前確認の後に別リクエストが同じ slug を確定させた）を、生存行の slug 一意制約
 * （V267: {@code uq_bp_slug_*_active}。{@link BlogPostEntity} のマッピングで {@code ddl-auto=create} のスキーマにも入る）
 * の実 DB で再現し、500 にならないことを固定する（台帳 CMP-261123-2341）。
 *
 * <p><b>競合の作り方</b>: 競合相手が同じ slug の行を INSERT して未確定のまま待つ。その間に作成を呼ぶと、事前確認
 * （一貫読み取り）は未確定の行を見ないので「未使用」と答え、INSERT は一意索引の重複検査で競合相手のロック待ちに入る。
 * {@code information_schema.processlist} で blog_posts への INSERT が終わらずに留まっている（ロック待ち）ことを確かめてから競合相手を
 * 確定させると、作成側の INSERT は一意制約違反になる。Repository の spy や認可の mock は使わず、チーム・組織の
 * 所属は本物のフィクスチャで通す。</p>
 *
 * <p>テスト全体のトランザクションは置かず、Controller からの呼び出しと同じく呼び出し元にトランザクションが無い状態で
 * {@link BlogPostService} を呼ぶ（行は確定するので、メール・slug・スコープはテストごとに一意にする）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ブログ記事 slug の同時作成競合（生存行の一意制約・実 DB）")
class BlogPostSlugConflictRetryIT extends AbstractMySqlIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String RETRY_LOG = "採番からやり直す";

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BlogPostService blogPostService;

    @Autowired
    private BlogPostRepository blogPostRepository;

    private String key;
    private Logger aspectLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> aspectLog;

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        // 再試行の有無を確かめるため、アスペクトの INFO ログ（採番のやり直し）を捕捉する
        aspectLogger = (Logger) LoggerFactory.getLogger(BlogSlugConflictRetryAspect.class);
        previousLevel = aspectLogger.getLevel();
        aspectLogger.setLevel(Level.INFO);
        aspectLog = new ListAppender<>();
        aspectLog.start();
        aspectLogger.addAppender(aspectLog);
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(aspectLog);
        aspectLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("自動採番(個人): 事前確認の後に同 slug が確定しても、採番し直して -2 で作成できる")
    void 自動採番の競合は採番し直して作成できる() throws Exception {
        Long userId = persistUser("auto");

        BlogPostResponse second = raceAgainstCompetitor(
                post -> post.userId(userId).slug("weeklyreport"),
                () -> blogPostService.createPost(userId, personal("Weekly Report", null)));

        assertThat(second.getContent().slug()).isEqualTo("weeklyreport-2");
        assertThat(liveSlugs("user_id", userId)).containsExactly("weeklyreport", "weeklyreport-2");
        assertThat(retryLogCount()).as("一意制約違反を受けて採番からやり直した").isEqualTo(1);
    }

    @Test
    @DisplayName("明示指定: 事前確認の後に同 slug が確定していた一意制約違反は 409（CMS_005）で、行は増えない")
    void 明示指定の競合は409() {
        Long userId = persistUser("explicit");

        assertThatThrownBy(() -> raceAgainstCompetitor(
                post -> post.userId(userId).slug("race-slug"),
                () -> blogPostService.createPost(userId, personal("t2", "race-slug"))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CmsErrorCode.DUPLICATE_SLUG));
        assertThat(liveSlugs("user_id", userId)).containsExactly("race-slug");
        assertThat(retryLogCount()).as("明示指定は再試行しない").isZero();
    }

    @Test
    @DisplayName("自動採番(チーム): uq_bp_slug_team_active の競合でも採番し直して -2 で作成できる")
    void チームスコープの競合は採番し直して作成できる() throws Exception {
        Long userId = persistUser("team");
        Long teamId = transactionTemplate.execute(status -> {
            Long id = TeamOrgFixtureHelper.insertTeam(em, "slug競合チーム", "slug-race-team-" + key);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, id, RoleKind.MEMBER);
            return id;
        });

        BlogPostResponse second = raceAgainstCompetitor(
                post -> post.teamId(teamId).slug("teamreport"),
                () -> blogPostService.createPost(userId, scoped(teamId, null, "Team Report")));

        assertThat(second.getContent().slug()).isEqualTo("teamreport-2");
        assertThat(liveSlugs("team_id", teamId)).containsExactly("teamreport", "teamreport-2");
        assertThat(retryLogCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("自動採番(組織): uq_bp_slug_org_active の競合でも採番し直して -2 で作成できる")
    void 組織スコープの競合は採番し直して作成できる() throws Exception {
        Long userId = persistUser("org");
        Long orgId = transactionTemplate.execute(status -> {
            Long id = TeamOrgFixtureHelper.insertOrganization(em, "slug競合組織", "slug-race-org-" + key);
            MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, id, RoleKind.MEMBER);
            return id;
        });

        BlogPostResponse second = raceAgainstCompetitor(
                post -> post.organizationId(orgId).slug("orgreport"),
                () -> blogPostService.createPost(userId, scoped(null, orgId, "Org Report")));

        assertThat(second.getContent().slug()).isEqualTo("orgreport-2");
        assertThat(liveSlugs("organization_id", orgId)).containsExactly("orgreport", "orgreport-2");
        assertThat(retryLogCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("呼び出し元がトランザクション内なら再試行せず 1 回で 409（CMS_027）にし、呼び出し元ごとロールバックする")
    void 呼び出し元TX内では再試行しない() {
        Long userId = persistUser("outer-tx");

        assertThatThrownBy(() -> raceAgainstCompetitor(
                post -> post.userId(userId).slug("outerreport"),
                () -> transactionTemplate.execute(status ->
                        blogPostService.createPost(userId, personal("Outer Report", null)))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT));
        assertThat(liveSlugs("user_id", userId)).containsExactly("outerreport");
        assertThat(retryLogCount()).as("呼び出し元のトランザクション内では採番をやり直さない").isZero();
    }

    /**
     * 競合相手が同じ slug の行を INSERT して未確定のまま、{@code contender} を別スレッドで呼ぶ。
     * contender の blog_posts への INSERT がロック待ちに入ったのを確かめてから競合相手を確定させ、contender の結果を返す。
     */
    private <T> T raceAgainstCompetitor(Consumer<BlogPostEntity.BlogPostEntityBuilder<?, ?>> competitorScope,
                                        Callable<T> contender) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> competitor = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                BlogPostEntity.BlogPostEntityBuilder<?, ?> post = BlogPostEntity.builder()
                        .title("競合相手")
                        .body("本文")
                        .readingTimeMinutes((short) 1);
                competitorScope.accept(post);
                blogPostRepository.saveAndFlush(post.build());
                inserted.countDown();
                await(release);
            }));
            assertThat(inserted.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("競合相手の INSERT").isTrue();

            Future<T> result = executor.submit(contender);
            awaitInsertLockWait(result);
            release.countDown();
            competitor.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            try {
                return result.get(WAIT.toSeconds(), TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** contender の blog_posts への INSERT が一意索引の重複検査でロック待ちに入る（実行中のまま留まる）まで待つ。 */
    private void awaitInsertLockWait(Future<?> contender) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            // 実行中の INSERT は PROCESSLIST に現れる（基底クラスがアプリ接続ユーザーに PROCESS 権限を与えている）。
            // 競合相手が同じキーの行を未確定で持っている間、作成側の INSERT は終わらずここに留まる。
            Integer waiting = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM information_schema.processlist "
                    + "WHERE command = 'Query' AND LOWER(info) LIKE 'insert into blog_posts%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            if (contender.isDone()) {
                throw new AssertionError("ロック待ちに入る前に作成が終わった（競合を再現できていない）: " + processSnapshot());
            }
            Thread.sleep(20);
        }
        throw new AssertionError("作成の INSERT が競合相手のロック待ちに入らなかった: " + processSnapshot());
    }

    /** 失敗時の手掛かり: 実行中の文。 */
    private String processSnapshot() {
        return jdbcTemplate.queryForList("SELECT command, time, state, LEFT(info, 120) AS info "
                + "FROM information_schema.processlist WHERE command <> 'Sleep'").toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IllegalStateException("競合相手の確定待ちが時間切れ");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long retryLogCount() {
        return aspectLog.list.stream().filter(event -> event.getFormattedMessage().contains(RETRY_LOG)).count();
    }

    private Long persistUser(String label) {
        String email = "slug-race-" + label + "-" + key + "@example.com";
        return transactionTemplate.execute(status -> {
            em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                            + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                            + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                            + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                            + "VALUES (:email, '競合', 'slug', 'slug競合', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                            + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                    .setParameter("email", email).executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                    .setParameter("email", email).getSingleResult()).longValue();
        });
    }

    private static CreateBlogPostRequest personal(String title, String slug) {
        return new CreateBlogPostRequest(null, null, null, title, slug, "本文",
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static CreateBlogPostRequest scoped(Long teamId, Long organizationId, String title) {
        return new CreateBlogPostRequest(teamId == null ? null : teamId.toString(),
                organizationId == null ? null : organizationId.toString(), null, title, null, "本文",
                null, null, null, null, null, null, null, null, null, null, null);
    }

    /** scopeColumn は本クラスの固定値（user_id / team_id / organization_id）のみ。 */
    private List<String> liveSlugs(String scopeColumn, Long scopeId) {
        return jdbcTemplate.queryForList("SELECT slug FROM blog_posts WHERE " + scopeColumn
                + " = ? AND deleted_at IS NULL ORDER BY id", String.class, scopeId);
    }
}
