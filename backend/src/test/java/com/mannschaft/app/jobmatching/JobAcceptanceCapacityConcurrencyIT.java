package com.mannschaft.app.jobmatching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.JobAcceptLockWaitObserver.FollowerObservation;
import com.mannschaft.app.jobmatching.JobAcceptLockWaitObserver.MatchedHook;
import com.mannschaft.app.jobmatching.JobAcceptLockWaitObserver.PostingRowHold;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.repository.JobApplicationRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.jobmatching.service.JobApplicationService;
import com.mannschaft.app.jobmatching.service.JobContractService;
import com.mannschaft.app.jobmatching.service.command.ApplyCommand;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * CMP-261021-0355 試練: 求人受諾の定員集計が REPEATABLE READ のスナップショットを読む競合の実DB並行テスト。
 *
 * <h2>欠陥（修正前の挙動）</h2>
 * <p>{@code JobContractService#acceptApplication} は先頭の {@code applicationRepository.findById}（通常 SELECT）で
 * RR の読取りビューを確定させた後に {@code GET_LOCK} と求人行 {@code SELECT ... FOR UPDATE} を取る。ロック獲得後の
 * 応募ステータス確認・{@code countByJobPostingIdAndStatus(ACCEPTED)}・CLOSED 判定は古いスナップショットを読むため、
 * 先行受諾のコミット結果が見えず、定員超過・CLOSED 漏れ・同一応募の二重契約・取り下げ済み応募の受諾が起きる。
 * また {@code GET_LOCK} は finally でコミット前に解放されるため実質の排他にならない。
 * {@code JobApplicationService#withdraw/reject} はロックも {@code @Version} も無く通常 SELECT で検証して全列 UPDATE
 * するため、受諾のコミット待ちの間に読むと受諾後の ACCEPTED を WITHDRAWN/REJECTED で上書きする。</p>
 *
 * <h2>順序の固定</h2>
 * <p>テスト側の別接続で求人行を {@code FOR UPDATE} で保持し、全受諾スレッドが {@code findById} 後（スナップショット
 * 確定後）でロック待ちに入ったことを performance_schema で実測してから解放する（{@link JobAcceptLockWaitObserver}）。
 * 逆向き（受諾がロックを持ってコミット前）は、受諾 TX 内でコミット前に同期発行される MATCHED イベントを合図に
 * 後続を開始する。時間待ちでの推定はしない。</p>
 *
 * <h2>修正前の見込み</h2>
 * <p>(a)(b)(d)(e)(f) は red、(c)(g) は green（(c) は修正前後とも green の現契約固定）。</p>
 *
 * <p>クラスに {@code @Transactional} を付けない。各スレッドは Spring プロキシ経由で Service を呼び、それぞれ独立 TX。
 * fixture は明示コミットし、{@link #cleanUp()} で自分が作った行だけを消す。spy・BeanOverride は使わない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-0355 求人受諾の定員競合（RR スナップショット）の実DB並行テスト")
class JobAcceptanceCapacityConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final long WORKER_FINISH_SECONDS = 90L;

    @Autowired private JobContractService contractService;
    @Autowired private JobApplicationService applicationService;
    @Autowired private JobApplicationRepository applicationRepository;
    @Autowired private JobPostingRepository postingRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationContext applicationContext;

    @PersistenceContext
    private EntityManager em;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeamIds = new ArrayList<>();
    private final List<Long> createdPostingIds = new ArrayList<>();

    private record Fixture(Long teamId, Long requesterId, List<Long> workerIds, Long postingId, int capacity) {
        Long worker(int i) {
            return workerIds.get(i);
        }
    }

    /** worker の結果。成功なら {@code contractId}、業務例外なら {@code code}、それ以外は {@code unexpected}。 */
    private record Outcome(Long contractId, String code, String unexpected) {
        static Outcome ok(Long contractId) {
            return new Outcome(contractId, null, null);
        }

        boolean success() {
            return code == null && unexpected == null;
        }

        String label() {
            if (unexpected != null) {
                return "UNEXPECTED:" + unexpected;
            }
            return code == null ? "SUCCESS" : code;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 前提
    // ═════════════════════════════════════════════════════════════════════

    @BeforeEach
    void assertPreconditions() {
        String isolation = transactionTemplate.execute(tx ->
                String.valueOf(em.createNativeQuery("SELECT @@transaction_isolation").getSingleResult()));
        assertThat(isolation).as("前提: アプリ接続の分離レベルが REPEATABLE-READ であること（競合の再現条件）")
                .isEqualTo("REPEATABLE-READ");
        JobAcceptLockWaitObserver.assertInstrumentationEnabled(MYSQL);
    }

    // ═════════════════════════════════════════════════════════════════════
    // (a)(b)(c) 異なる応募の並行受諾
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(a) capacity=2 に 3 並行受諾 → ACCEPTED=2・契約2・求人 CLOSED・失敗1件は JOB_NOT_OPEN")
    void a_定員2に3並行受諾すると2件だけ成立し求人はCLOSEDになる() throws Exception {
        Fixture f = fixture("cap2x3", 2, 3);
        List<Long> appIds = List.of(insertApplication(f, f.worker(0)), insertApplication(f, f.worker(1)),
                insertApplication(f, f.worker(2)));

        List<Outcome> outcomes = acceptConcurrentlyWhileHeld(f, appIds);

        assertThat(labels(outcomes)).as("結果: %s", outcomes)
                .containsExactlyInAnyOrder("SUCCESS", "SUCCESS", "JOB_NOT_OPEN");
        assertInvariants(f, outcomes);
        assertThat(postingStatus(f.postingId())).as("定員充足で求人は CLOSED").isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("(b) capacity=2 に 2 並行受諾 → 両方成功・求人 CLOSED")
    void b_定員2に2並行受諾すると両方成立し求人はCLOSEDになる() throws Exception {
        Fixture f = fixture("cap2x2", 2, 2);
        List<Long> appIds = List.of(insertApplication(f, f.worker(0)), insertApplication(f, f.worker(1)));

        List<Outcome> outcomes = acceptConcurrentlyWhileHeld(f, appIds);

        assertThat(labels(outcomes)).as("結果: %s", outcomes).containsExactlyInAnyOrder("SUCCESS", "SUCCESS");
        assertInvariants(f, outcomes);
        assertThat(postingStatus(f.postingId())).as("定員充足で求人は CLOSED").isEqualTo("CLOSED");
    }

    /**
     * (c) capacity=1 は先行受諾が求人を CLOSED にし、後続は求人行の locking read で最新の CLOSED を見るため、
     * 修正前後とも green（現契約の固定）。定員集計のスナップショットに依存しない経路であることを固定する。
     */
    @Test
    @DisplayName("(c) capacity=1 に 2 並行受諾 → 成功1・JOB_NOT_OPEN 1（修正前後とも green の現契約固定）")
    void c_定員1に2並行受諾すると1件だけ成立する() throws Exception {
        Fixture f = fixture("cap1x2", 1, 2);
        List<Long> appIds = List.of(insertApplication(f, f.worker(0)), insertApplication(f, f.worker(1)));

        List<Outcome> outcomes = acceptConcurrentlyWhileHeld(f, appIds);

        assertThat(labels(outcomes)).as("結果: %s", outcomes).containsExactlyInAnyOrder("SUCCESS", "JOB_NOT_OPEN");
        assertInvariants(f, outcomes);
        assertThat(postingStatus(f.postingId())).isEqualTo("CLOSED");
    }

    // ═════════════════════════════════════════════════════════════════════
    // (d) 受諾のロック待ち中に同じ応募の withdraw がコミット
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(d) 受諾がロック待ちの間に同じ応募の withdraw がコミット → 受諾は JOB_APPLICATION_NOT_PENDING・WITHDRAWN のまま・契約0")
    void d_ロック待ち中に取り下げられた応募は受諾されない() throws Exception {
        Fixture f = fixture("wd-wait", 2, 1);
        Long appId = insertApplication(f, f.worker(0));

        ExecutorService pool = Executors.newFixedThreadPool(1);
        List<Outcome> outcomes;
        try {
            Future<Outcome> accept;
            try (PostingRowHold hold = PostingRowHold.hold(MYSQL, f.postingId())) {
                accept = pool.submit(acceptTask(appId, f.requesterId()));
                JobAcceptLockWaitObserver.awaitWaiters(MYSQL, f.postingId(), 1, List.of(accept), "(d) 受諾のロック待ち");

                // 受諾はスナップショット確定後で待っている。ここで取り下げをコミットする（求人行には触れない）。
                Outcome withdraw = run(() -> applicationService.withdraw(appId, f.worker(0)).getId());
                assertThat(withdraw.success()).as("前提: 受諾待ちの間の取り下げはコミットされる: %s", withdraw).isTrue();
                assertThat(accept.isDone()).as("前提: 取り下げのコミット時点で受諾はまだロック待ち").isFalse();
            }
            outcomes = List.of(accept.get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS));
        } finally {
            shutdown(pool);
        }

        assertThat(outcomes.get(0).label()).as("取り下げ済み応募の受諾は拒否される: %s", outcomes)
                .isEqualTo("JOB_APPLICATION_NOT_PENDING");
        assertThat(applicationStatus(appId)).as("応募は WITHDRAWN のまま（ACCEPTED で上書きされない）")
                .isEqualTo("WITHDRAWN");
        assertThat(contractCount(f.postingId())).as("契約は作られない").isZero();
        assertInvariants(f, outcomes);
    }

    // ═════════════════════════════════════════════════════════════════════
    // (e) 同一応募の N 並行受諾
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(e) 同一応募の 4 並行受諾 → 成功1・残り JOB_APPLICATION_NOT_PENDING・契約1・チャット1")
    void e_同一応募の並行受諾は1件だけ成立する() throws Exception {
        Fixture f = fixture("same-app", 5, 1);
        Long appId = insertApplication(f, f.worker(0));

        List<Outcome> outcomes = acceptConcurrentlyWhileHeld(f, List.of(appId, appId, appId, appId));

        assertThat(labels(outcomes)).as("結果: %s", outcomes).containsExactlyInAnyOrder("SUCCESS",
                "JOB_APPLICATION_NOT_PENDING", "JOB_APPLICATION_NOT_PENDING", "JOB_APPLICATION_NOT_PENDING");
        assertThat(contractCount(f.postingId())).as("同一応募の契約は 1 件").isEqualTo(1);
        assertThat(applicationStatus(appId)).isEqualTo("ACCEPTED");
        assertInvariants(f, outcomes);
        assertThat(postingStatus(f.postingId())).as("定員 5 に 1 件なので OPEN のまま").isEqualTo("OPEN");
    }

    // ═════════════════════════════════════════════════════════════════════
    // (f) 逆向き: 受諾がロックを持ってコミット前に withdraw / reject
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(f-1) 受諾のコミット前に同じ応募の withdraw → 受諾後に JOB_INVALID_STATE_TRANSITION・ACCEPTED のまま・契約1")
    void f1_受諾コミット前に始めた取り下げは受諾後の状態で拒否される() throws Exception {
        Fixture f = fixture("rev-wd", 2, 1);
        Long appId = insertApplication(f, f.worker(0));

        assertFollowerRejectedAfterAccept(f, appId,
                () -> run(() -> applicationService.withdraw(appId, f.worker(0)).getId()), "withdraw");
    }

    @Test
    @DisplayName("(f-2) 受諾のコミット前に同じ応募の reject → 受諾後に JOB_INVALID_STATE_TRANSITION・ACCEPTED のまま・契約1")
    void f2_受諾コミット前に始めた不採用は受諾後の状態で拒否される() throws Exception {
        Fixture f = fixture("rev-rj", 2, 1);
        Long appId = insertApplication(f, f.worker(0));

        assertFollowerRejectedAfterAccept(f, appId,
                () -> run(() -> applicationService.reject(appId, "IT", f.requesterId()).getId()), "reject");
    }

    /**
     * 受諾を先行させ、MATCHED 発行（コミット前・求人ロック保持中）の瞬間に後続を開始する。後続は受諾済みの応募に対する
     * 遷移なので、逐次実行時と同じ {@code JOB_INVALID_STATE_TRANSITION}（ACCEPTED は終着。
     * {@code JobApplicationStateMachine}）で拒否され、応募は ACCEPTED・契約 1 のまま残ること。
     */
    private void assertFollowerRejectedAfterAccept(Fixture f, Long appId, Callable<Outcome> followerTask,
                                                   String followerName) throws Exception {
        Outcome accept;
        Outcome follower;
        FollowerObservation observation;
        String detail;
        try (MatchedHook<Outcome> hook = new MatchedHook<>(applicationContext, MYSQL, f.postingId(), followerTask)) {
            accept = run(() -> contractService.acceptApplication(appId, f.requesterId()).getId());
            observation = hook.observation();
            detail = hook.detail();
            assertThat(observation).as("前提: 後続 %s が受諾のコミット前に開始され、待ちか終了が観測できること: %s",
                            followerName, detail)
                    .isIn(FollowerObservation.WAITED_FOR_LEADER, FollowerObservation.FINISHED_BEFORE_LEADER_COMMIT);
            follower = hook.follower().get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(accept.success()).as("先行の受諾は成功する: %s", accept).isTrue();
        assertThat(follower.label()).as("受諾済み応募への %s は定義済みエラーで拒否される（観測: %s）",
                followerName, detail).isEqualTo("JOB_INVALID_STATE_TRANSITION");
        assertThat(applicationStatus(appId)).as("応募は ACCEPTED のまま（%s で上書きされない）", followerName)
                .isEqualTo("ACCEPTED");
        assertThat(contractCount(f.postingId())).isEqualTo(1);
        assertInvariants(f, List.of(accept));
    }

    // ═════════════════════════════════════════════════════════════════════
    // (g) 受諾中に新規 apply・他応募の withdraw/reject を並行
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("(g) 受諾のロック待ち中に新規 apply・他応募の withdraw/reject を並行 → 全件が成功か定義済みエラー（デッドロック/500 なし）")
    void g_受諾と他の応募操作の並行はデッドロックせず定義済みの結果になる() throws Exception {
        Fixture f = fixture("mixed", 1, 4);
        Long acceptApp = insertApplication(f, f.worker(0));
        Long withdrawApp = insertApplication(f, f.worker(1));
        Long rejectApp = insertApplication(f, f.worker(2));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        Outcome accept;
        Outcome apply;
        Outcome withdraw;
        Outcome reject;
        try {
            Future<Outcome> acceptF;
            List<Future<Outcome>> others;
            try (PostingRowHold hold = PostingRowHold.hold(MYSQL, f.postingId())) {
                acceptF = pool.submit(acceptTask(acceptApp, f.requesterId()));
                JobAcceptLockWaitObserver.awaitWaiters(MYSQL, f.postingId(), 1, List.of(acceptF), "(g) 受諾のロック待ち");
                others = List.of(
                        pool.submit(() -> run(() -> applicationService.apply(
                                f.postingId(), new ApplyCommand("IT 並行応募"), f.worker(3)).getId())),
                        pool.submit(() -> run(() -> applicationService.withdraw(withdrawApp, f.worker(1)).getId())),
                        pool.submit(() -> run(() -> applicationService.reject(rejectApp, "IT", f.requesterId())
                                .getId())));
                JobAcceptLockWaitObserver.awaitFollowersSettled(MYSQL, f.postingId(), 1, others, "(g) 並行操作");
                assertThat(acceptF.isDone()).as("前提: 並行操作の間、受諾はロック待ちのまま").isFalse();
            }
            accept = acceptF.get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS);
            apply = others.get(0).get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS);
            withdraw = others.get(1).get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS);
            reject = others.get(2).get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS);
        } finally {
            shutdown(pool);
        }

        assertThat(accept.success()).as("受諾は成立する: %s", accept).isTrue();
        assertThat(List.of(apply, withdraw, reject))
                .as("全件が成功か定義済みの業務エラー（デッドロック・想定外例外なし）: apply=%s withdraw=%s reject=%s",
                        apply, withdraw, reject)
                .allSatisfy(o -> assertThat(o.unexpected()).isNull());
        assertThat(applicationStatus(acceptApp)).isEqualTo("ACCEPTED");
        assertThat(applicationStatus(withdrawApp)).isEqualTo(withdraw.success() ? "WITHDRAWN" : "APPLIED");
        assertThat(applicationStatus(rejectApp)).isEqualTo(reject.success() ? "REJECTED" : "APPLIED");
        assertThat(applicationCountOf(f.postingId(), f.worker(3))).isEqualTo(apply.success() ? 1 : 0);
        assertInvariants(f, List.of(accept));
        assertThat(postingStatus(f.postingId())).isEqualTo("CLOSED");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 並行実行・不変条件
    // ═════════════════════════════════════════════════════════════════════

    /** 求人行を保持したまま全受諾を開始し、全員のロック待ちを実測してから解放して結果を集める。 */
    private List<Outcome> acceptConcurrentlyWhileHeld(Fixture f, List<Long> appIds) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(appIds.size());
        try {
            List<Future<Outcome>> futures = new ArrayList<>();
            try (PostingRowHold hold = PostingRowHold.hold(MYSQL, f.postingId())) {
                for (Long appId : appIds) {
                    futures.add(pool.submit(acceptTask(appId, f.requesterId())));
                }
                JobAcceptLockWaitObserver.awaitWaiters(MYSQL, f.postingId(), appIds.size(), futures,
                        "全 " + appIds.size() + " 受諾のロック待ち");
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> fu : futures) {
                outcomes.add(fu.get(WORKER_FINISH_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            shutdown(pool);
        }
    }

    private Callable<Outcome> acceptTask(Long appId, Long requesterId) {
        return () -> run(() -> contractService.acceptApplication(appId, requesterId).getId());
    }

    private static Outcome run(Callable<Long> body) {
        try {
            return Outcome.ok(body.call());
        } catch (BusinessException e) {
            return new Outcome(null, e.getErrorCode().getCode(), null);
        } catch (Exception e) {
            return new Outcome(null, null, e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**
     * 全ケース共通の不変条件と副作用の件数（DB の実件数で確認）:
     * 契約（MATCHED）件数 ≤ capacity、ACCEPTED 件数 = 契約件数、契約件数 = 成功数、
     * JOB_CONTRACT チャット・メンバー（2名/契約）・初期システムメッセージ・JOB_MATCHED 通知が成功数と一致。
     */
    private void assertInvariants(Fixture f, List<Outcome> outcomes) {
        assertThat(outcomes).as("想定外例外（デッドロック・楽観ロック・一意制約違反等）が無いこと: %s", outcomes)
                .allSatisfy(o -> assertThat(o.unexpected()).isNull());
        long successes = outcomes.stream().filter(Outcome::success).count();
        List<Long> contractIds = jdbcTemplate.queryForList(
                "SELECT id FROM job_contracts WHERE job_posting_id = ?", Long.class, f.postingId());
        long matched = count("SELECT COUNT(*) FROM job_contracts WHERE job_posting_id = ? AND status = 'MATCHED'",
                f.postingId());
        long accepted = count("SELECT COUNT(*) FROM job_applications WHERE job_posting_id = ? AND status = 'ACCEPTED'",
                f.postingId());

        assertThat(matched).as("契約（MATCHED）件数 ≤ capacity(%s)", f.capacity()).isLessThanOrEqualTo(f.capacity());
        assertThat(accepted).as("ACCEPTED 応募件数 = 契約件数").isEqualTo(contractIds.size());
        assertThat((long) contractIds.size()).as("契約件数 = 成功数（結果: %s）", outcomes).isEqualTo(successes);

        long channels = contractIds.isEmpty() ? 0 : count(
                "SELECT COUNT(*) FROM chat_channels WHERE source_type = 'JOB_CONTRACT' AND source_id IN ("
                        + in(contractIds) + ")");
        assertThat(channels).as("JOB_CONTRACT チャット件数 = 成功数").isEqualTo(successes);
        if (!contractIds.isEmpty()) {
            String channelIds = "SELECT id FROM chat_channels WHERE source_type = 'JOB_CONTRACT' AND source_id IN ("
                    + in(contractIds) + ")";
            assertThat(count("SELECT COUNT(*) FROM chat_channel_members WHERE channel_id IN (" + channelIds + ")"))
                    .as("チャットメンバー = 2 × 成功数").isEqualTo(2 * successes);
            assertThat(count("SELECT COUNT(*) FROM chat_messages WHERE is_system = 1 AND channel_id IN ("
                    + channelIds + ")")).as("初期システムメッセージ = 成功数").isEqualTo(successes);
            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(count(
                    "SELECT COUNT(*) FROM notifications WHERE notification_type = 'JOB_MATCHED' AND source_id IN ("
                            + in(contractIds) + ")"))
                    .as("JOB_MATCHED 通知行 = 成功数（AFTER_COMMIT・非同期）").isEqualTo(successes));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // fixture（JobNotificationTransactionIT を踏襲）・後片付け
    // ═════════════════════════════════════════════════════════════════════

    /** チーム・Requester(ADMIN)・Worker(MEMBER)×workers・OPEN な求人（capacity 指定）をコミット済みで作る。 */
    private Fixture fixture(String tag, int capacity, int workers) {
        String nonce = tag + "-" + System.nanoTime();
        Fixture f = transactionTemplate.execute(tx -> {
            Long teamId = insertTeam("JOBCAP " + nonce);
            Long requesterId = insertUser("jobcap-req-" + nonce + "@example.com");
            MembershipTestHelper.insertMembership(em, requesterId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, requesterId, "ADMIN", teamId, null);
            List<Long> workerIds = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                Long workerId = insertUser("jobcap-wrk" + i + "-" + nonce + "@example.com");
                MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
                workerIds.add(workerId);
            }
            LocalDateTime now = LocalDateTime.now();
            JobPostingEntity posting = postingRepository.save(JobPostingEntity.builder()
                    .teamId(teamId)
                    .createdByUserId(requesterId)
                    .title("JOBCAP 求人 " + nonce)
                    .description("JOBCAP 説明")
                    .workLocationType(WorkLocationType.ONSITE)
                    .workAddress("JOBCAP 会場")
                    .workStartAt(now.plusDays(3))
                    .workEndAt(now.plusDays(3).plusHours(4))
                    .rewardType(RewardType.LUMP_SUM)
                    .baseRewardJpy(3000)
                    .capacity(capacity)
                    .applicationDeadlineAt(now.plusDays(2))
                    .visibilityScope(VisibilityScope.TEAM_MEMBERS)
                    .status(JobPostingStatus.OPEN)
                    .build());
            return new Fixture(teamId, requesterId, List.copyOf(workerIds), posting.getId(), capacity);
        });
        createdTeamIds.add(f.teamId());
        createdUserIds.add(f.requesterId());
        createdUserIds.addAll(f.workerIds());
        createdPostingIds.add(f.postingId());
        return f;
    }

    private Long insertApplication(Fixture f, Long workerId) {
        return transactionTemplate.execute(tx -> applicationRepository.save(JobApplicationEntity.builder()
                .jobPostingId(f.postingId())
                .applicantUserId(workerId)
                .selfPr("JOBCAP 自己PR")
                .status(JobApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now())
                .build()).getId());
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
                                + "VALUES (:email, 'JOBCAP', 'テスト', 'JOBCAP テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /** 自分が作った行だけを消す（非同期の JOB_MATCHED 通知が書き終わるのを上限付きで待ってから）。 */
    @AfterEach
    void cleanUp() {
        if (createdPostingIds.isEmpty()) {
            return;
        }
        String postings = in(createdPostingIds);
        String users = in(createdUserIds);
        List<Long> contractIds = jdbcTemplate.queryForList(
                "SELECT id FROM job_contracts WHERE job_posting_id IN (" + postings + ")", Long.class);
        if (!contractIds.isEmpty()) {
            try {
                await().atMost(5, TimeUnit.SECONDS).until(() -> count(
                        "SELECT COUNT(*) FROM notifications WHERE notification_type = 'JOB_MATCHED' AND source_id IN ("
                                + in(contractIds) + ")") >= contractIds.size());
            } catch (org.awaitility.core.ConditionTimeoutException ignored) {
                // 通知が来ないこと自体は各テストの不変条件で検査済み。ここは後片付けの待ち合わせだけ
            }
            String channelIds = "SELECT id FROM (SELECT id FROM chat_channels WHERE source_type = 'JOB_CONTRACT' "
                    + "AND source_id IN (" + in(contractIds) + ")) c";
            jdbcTemplate.update("DELETE FROM chat_messages WHERE channel_id IN (" + channelIds + ")");
            jdbcTemplate.update("DELETE FROM chat_channel_members WHERE channel_id IN (" + channelIds + ")");
            jdbcTemplate.update("DELETE FROM chat_channels WHERE source_type = 'JOB_CONTRACT' AND source_id IN ("
                    + in(contractIds) + ")");
        }
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id IN (" + users + ")");
        jdbcTemplate.update("DELETE FROM job_contracts WHERE job_posting_id IN (" + postings + ")");
        jdbcTemplate.update("DELETE FROM job_applications WHERE job_posting_id IN (" + postings + ")");
        jdbcTemplate.update("DELETE FROM job_postings WHERE id IN (" + postings + ")");
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id IN (" + users + ")");
        jdbcTemplate.update("DELETE FROM memberships WHERE user_id IN (" + users + ")");
        jdbcTemplate.update("DELETE FROM users WHERE id IN (" + users + ")");
        jdbcTemplate.update("DELETE FROM teams WHERE id IN (" + in(createdTeamIds) + ")");
        createdPostingIds.clear();
        createdUserIds.clear();
        createdTeamIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 読み取りヘルパー（別接続・自動コミットで最新のコミット済み状態を読む）
    // ═════════════════════════════════════════════════════════════════════

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private long contractCount(Long postingId) {
        return count("SELECT COUNT(*) FROM job_contracts WHERE job_posting_id = ?", postingId);
    }

    private long applicationCountOf(Long postingId, Long userId) {
        return count("SELECT COUNT(*) FROM job_applications WHERE job_posting_id = ? AND applicant_user_id = ?",
                postingId, userId);
    }

    private String applicationStatus(Long appId) {
        return jdbcTemplate.queryForObject("SELECT status FROM job_applications WHERE id = ?", String.class, appId);
    }

    private String postingStatus(Long postingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM job_postings WHERE id = ?", String.class, postingId);
    }

    private static List<String> labels(List<Outcome> outcomes) {
        return outcomes.stream().map(Outcome::label).toList();
    }

    /** ID は全てテスト自身が採番した Long なので、IN 句へ数値として直接埋め込む。 */
    private static String in(Collection<Long> ids) {
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static void shutdown(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        if (!pool.awaitTermination(WORKER_FINISH_SECONDS, TimeUnit.SECONDS)) {
            pool.shutdownNow();
            throw new AssertionError("worker が " + WORKER_FINISH_SECONDS + " 秒以内に終了しない");
        }
    }
}
