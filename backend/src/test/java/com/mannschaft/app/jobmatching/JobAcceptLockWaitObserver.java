package com.mannschaft.app.jobmatching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.event.JobNotificationEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CMP-261021-0355 試練用: 求人受諾の並行実行を「全員がスナップショット確定後にロックで待っている」順序へ固定し、
 * その待ちを MySQL の performance_schema で実測する道具（{@code BirthProfileLockWaitObserver} /
 * {@code ConcurrentOnEvent} の型を踏襲）。
 *
 * <ul>
 *   <li>{@link PostingRowHold}: テスト側の別接続（root）で求人行を {@code SELECT ... FOR UPDATE} で保持する。受諾は
 *       {@code findById}（通常 SELECT＝RR の読取りビュー確定）の後に求人行ロック／{@code GET_LOCK} を取りに行くため、
 *       保持している間は全受諾スレッドがスナップショット確定後で止まる。</li>
 *   <li>{@link #awaitWaiters}: InnoDB の行ロック待ち（{@code data_lock_waits}）と {@code GET_LOCK} の待ち
 *       （{@code metadata_locks} の USER LEVEL LOCK / PENDING）の合計が期待数に達するまで上限付きでポーリングする。
 *       上限超過・worker の先行終了は原因付きで fail させる（黙って進めない）。上限は受諾の {@code GET_LOCK}
 *       タイムアウト（10 秒）より短く取る。</li>
 *   <li>{@link MatchedHook}: 受諾 TX がコミット前に同期発行する {@code JobNotificationEvent(MATCHED)} を合図に、
 *       受諾が求人ロックを持ったまま（コミット前）の瞬間に後続処理（withdraw/reject）を別スレッド・別 TX で開始し、
 *       後続がロック待ちに入るか先に終わるかを観測してから受諾をコミットさせる。</li>
 * </ul>
 *
 * <p>本道具は専用テストコンテナの root 資格情報だけを使い、DB 設定は変更しない。IT は同一 JVM 内で直列実行される
 * （junit-platform の並列実行は無効）ため、ロック待ちの総数はこのテストの待ちだけを数える。</p>
 */
final class JobAcceptLockWaitObserver {

    /** 待ち観測の上限。受諾の GET_LOCK タイムアウト（10 秒）より短くし、タイムアウトでの red を避ける。 */
    static final Duration OBSERVE_LIMIT = Duration.ofSeconds(5);
    private static final long POLL_INTERVAL_MILLIS = 25L;

    /** InnoDB 行ロック待ちのトランザクション数 + 指定ユーザーレベルロックの PENDING 数。 */
    private static final String WAITERS_SQL = """
            SELECT (SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID)
                      FROM performance_schema.data_lock_waits w)
                 + (SELECT COUNT(*)
                      FROM performance_schema.metadata_locks m
                     WHERE m.OBJECT_TYPE = 'USER LEVEL LOCK'
                       AND m.OBJECT_NAME = ?
                       AND m.LOCK_STATUS = 'PENDING')
            """;

    /** 指定接続（先行 TX）に塞がれた行ロック待ち数 + 指定ユーザーレベルロックの PENDING 数。 */
    private static final String WAITERS_BLOCKED_BY_LEADER_SQL = """
            SELECT (SELECT COUNT(DISTINCT w.REQUESTING_ENGINE_TRANSACTION_ID)
                      FROM performance_schema.data_lock_waits w
                      JOIN information_schema.INNODB_TRX b ON b.trx_id = w.BLOCKING_ENGINE_TRANSACTION_ID
                     WHERE b.trx_mysql_thread_id = ?)
                 + (SELECT COUNT(*)
                      FROM performance_schema.metadata_locks m
                     WHERE m.OBJECT_TYPE = 'USER LEVEL LOCK'
                       AND m.OBJECT_NAME = ?
                       AND m.LOCK_STATUS = 'PENDING')
            """;

    private JobAcceptLockWaitObserver() {
    }

    /** 受諾が取りに行く GET_LOCK のキー（{@code JobContractService} の {@code job_posting_} 接頭辞）。 */
    static String lockName(Long postingId) {
        return "job_posting_" + postingId;
    }

    static Connection openRoot(MySQLContainer<?> mysql) throws SQLException {
        String url = mysql.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "useSSL=false&allowPublicKeyRetrieval=true";
        return DriverManager.getConnection(url, "root", mysql.getPassword());
    }

    /** 前提: performance_schema とメタデータロック（USER LEVEL LOCK を含む）の計装が有効であること。 */
    static void assertInstrumentationEnabled(MySQLContainer<?> mysql) {
        try (Connection c = openRoot(mysql);
             PreparedStatement ps = c.prepareStatement("""
                     SELECT @@performance_schema,
                            (SELECT ENABLED FROM performance_schema.setup_instruments
                              WHERE NAME = 'wait/lock/metadata/sql/mdl'),
                            (SELECT ENABLED FROM performance_schema.setup_consumers
                              WHERE NAME = 'global_instrumentation')
                     """);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            if (rs.getInt(1) != 1) {
                throw new AssertionError("前提失敗: performance_schema が無効（@@performance_schema=" + rs.getInt(1) + "）");
            }
            if (!"YES".equals(rs.getString(2))) {
                throw new AssertionError("前提失敗: ユーザーレベルロックの計装 wait/lock/metadata/sql/mdl が無効: "
                        + rs.getString(2));
            }
            if (!"YES".equals(rs.getString(3))) {
                throw new AssertionError("前提失敗: consumer global_instrumentation が無効: " + rs.getString(3));
            }
        } catch (SQLException e) {
            throw new AssertionError("前提失敗: performance_schema の計装確認に失敗: " + e.getMessage(), e);
        }
    }

    /**
     * ロック待ち（行ロック待ちトランザクション + 当該求人の GET_LOCK PENDING）の合計が {@code expected} に達するまで待つ。
     * 期待数に達する前に {@code mustStillRun} のいずれかが終わった場合・上限を過ぎた場合は原因付きで fail。
     */
    static void awaitWaiters(MySQLContainer<?> mysql, Long postingId, int expected,
                             List<? extends Future<?>> mustStillRun, String situation) {
        long deadline = System.nanoTime() + OBSERVE_LIMIT.toNanos();
        long last = -1;
        try (Connection c = openRoot(mysql);
             PreparedStatement ps = c.prepareStatement(WAITERS_SQL)) {
            ps.setString(1, lockName(postingId));
            while (System.nanoTime() < deadline) {
                for (Future<?> f : mustStillRun) {
                    if (f.isDone()) {
                        throw new AssertionError("前提失敗（" + situation + "）: ロック待ちが " + expected
                                + " 件に揃う前に worker が終了した（待ち=" + last + "）: " + describe(f));
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    last = rs.getLong(1);
                }
                if (last >= expected) {
                    if (last > expected) {
                        throw new AssertionError("前提失敗（" + situation + "）: ロック待ちが期待 " + expected
                                + " 件を超えた（" + last + " 件。想定外の待ちが混入）");
                    }
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
            throw new AssertionError("前提失敗（" + situation + "）: " + OBSERVE_LIMIT.toSeconds()
                    + " 秒以内にロック待ちが " + expected + " 件に揃わなかった（最終観測=" + last + "）");
        } catch (SQLException e) {
            throw new AssertionError("前提失敗（" + situation + "）: ロック待ちの観測に失敗: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("前提失敗（" + situation + "）: ロック待ちの観測中に割り込まれた", e);
        }
    }

    /**
     * 後続（{@code followers}）がそれぞれ「終了した」か「ロック待ちに入った」状態になるまで待つ
     * （終了数 + (ロック待ち総数 - baseWaiters) ≥ 後続数）。上限超過は原因付きで fail。
     */
    static void awaitFollowersSettled(MySQLContainer<?> mysql, Long postingId, int baseWaiters,
                                      List<? extends Future<?>> followers, String situation) {
        long deadline = System.nanoTime() + OBSERVE_LIMIT.toNanos();
        long lastWaiters = -1;
        long lastDone = -1;
        try (Connection c = openRoot(mysql);
             PreparedStatement ps = c.prepareStatement(WAITERS_SQL)) {
            ps.setString(1, lockName(postingId));
            while (System.nanoTime() < deadline) {
                lastDone = followers.stream().filter(Future::isDone).count();
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    lastWaiters = rs.getLong(1);
                }
                if (lastDone + Math.max(0, lastWaiters - baseWaiters) >= followers.size()) {
                    return;
                }
                Thread.sleep(POLL_INTERVAL_MILLIS);
            }
            throw new AssertionError("前提失敗（" + situation + "）: " + OBSERVE_LIMIT.toSeconds()
                    + " 秒以内に後続 " + followers.size() + " 件が終了またはロック待ちにならなかった（終了="
                    + lastDone + " 待ち総数=" + lastWaiters + " 基準=" + baseWaiters + "）");
        } catch (SQLException e) {
            throw new AssertionError("前提失敗（" + situation + "）: ロック待ちの観測に失敗: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("前提失敗（" + situation + "）: 観測中に割り込まれた", e);
        }
    }

    private static String describe(Future<?> f) {
        try {
            return "結果=" + f.get(0, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String code = cause instanceof BusinessException b ? b.getErrorCode().getCode() : "NONE";
            return "例外 class=" + (cause == null ? "NONE" : cause.getClass().getName()) + " code=" + code;
        } catch (CancellationException e) {
            return "取消";
        } catch (TimeoutException e) {
            return "未終了";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "回収中に割り込み";
        }
    }

    /**
     * テスト側の別接続（root）で求人行を {@code SELECT ... FOR UPDATE} により保持する。{@link #close()} で
     * ロールバックして解放する（二重 close 可）。
     */
    static final class PostingRowHold implements AutoCloseable {
        private final Connection connection;
        private boolean released;

        private PostingRowHold(Connection connection) {
            this.connection = connection;
        }

        static PostingRowHold hold(MySQLContainer<?> mysql, Long postingId) {
            try {
                Connection c = openRoot(mysql);
                c.setAutoCommit(false);
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM " + mysql.getDatabaseName() + ".job_postings WHERE id = ? FOR UPDATE")) {
                    ps.setLong(1, postingId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            c.close();
                            throw new AssertionError("前提失敗: 保持対象の求人行が無い postingId=" + postingId);
                        }
                    }
                }
                return new PostingRowHold(c);
            } catch (SQLException e) {
                throw new AssertionError("前提失敗: 求人行の FOR UPDATE 保持に失敗: " + e.getMessage(), e);
            }
        }

        /** 保持を解放する。 */
        void release() {
            if (released) {
                return;
            }
            released = true;
            try {
                connection.rollback();
            } catch (SQLException e) {
                throw new AssertionError("求人行ロックの解放に失敗: " + e.getMessage(), e);
            } finally {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // 解放済み接続の close 失敗は後続の観測に影響しない（rollback の成否は上で検査済み）
                }
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    /** {@link MatchedHook} の観測結果。 */
    enum FollowerObservation {
        /** 合図のイベントが来なかった（受諾が MATCHED 発行まで到達していない）。 */
        NOT_FIRED,
        /** 後続が受諾（コミット前）に塞がれてロック待ちに入った。 */
        WAITED_FOR_LEADER,
        /** 後続が受諾のコミット前に終わった（受諾のロックを待っていない）。 */
        FINISHED_BEFORE_LEADER_COMMIT,
        /** 上限内に待ちも終了も観測できなかった。 */
        TIMED_OUT,
        /** 観測自体に失敗した。 */
        ERROR
    }

    /**
     * 受諾 TX が求人 {@code postingId} の {@code JobNotificationEvent(MATCHED)} をコミット前に同期発行した瞬間に、
     * {@code followerTask} を別スレッド（別 TX）で開始し、後続が受諾に塞がれて待つか先に終わるかを観測してから
     * 受諾スレッドへ戻す（＝受諾をコミットさせる）。購読は実行時にマルチキャスタへ足し {@link #close()} で外す
     * （Bean 定義・BeanOverride を増やさない）。
     */
    static final class MatchedHook<T> implements AutoCloseable {
        private final ApplicationEventMulticaster multicaster;
        private final DataSource appDataSource;
        private final MySQLContainer<?> mysql;
        private final Long postingId;
        private final Callable<T> followerTask;
        private final ApplicationListener<ApplicationEvent> listener;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final AtomicBoolean fired = new AtomicBoolean();
        private volatile Future<T> follower;
        private volatile FollowerObservation observation = FollowerObservation.NOT_FIRED;
        private volatile String detail = "未発火";

        MatchedHook(ApplicationContext context, MySQLContainer<?> mysql, Long postingId, Callable<T> followerTask) {
            this.multicaster = context.getBean(AbstractApplicationContext.APPLICATION_EVENT_MULTICASTER_BEAN_NAME,
                    ApplicationEventMulticaster.class);
            this.appDataSource = context.getBean(DataSource.class);
            this.mysql = mysql;
            this.postingId = postingId;
            this.followerTask = followerTask;
            this.listener = new ApplicationListener<>() {
                @Override
                public void onApplicationEvent(ApplicationEvent event) {
                    onEvent(event);
                }
            };
            multicaster.addApplicationListener(listener);
        }

        private void onEvent(ApplicationEvent event) {
            Object payload = event instanceof PayloadApplicationEvent<?> p ? p.getPayload() : event;
            if (!(payload instanceof JobNotificationEvent e)
                    || e.kind() != JobNotificationEvent.Kind.MATCHED
                    || !postingId.equals(e.postingId())
                    || !fired.compareAndSet(false, true)) {
                return;
            }
            long leaderConnectionId;
            try {
                leaderConnectionId = currentTransactionConnectionId();
            } catch (SQLException ex) {
                observation = FollowerObservation.ERROR;
                detail = "受諾 TX の接続 ID を取得できなかった: " + ex.getMessage();
                return;
            }
            Future<T> f = executor.submit(followerTask);
            follower = f;
            observe(leaderConnectionId, f);
        }

        private long currentTransactionConnectionId() throws SQLException {
            Connection con = DataSourceUtils.getConnection(appDataSource);
            try (PreparedStatement ps = con.prepareStatement("SELECT CONNECTION_ID()");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            } finally {
                DataSourceUtils.releaseConnection(con, appDataSource);
            }
        }

        private void observe(long leaderConnectionId, Future<T> f) {
            long deadline = System.nanoTime() + OBSERVE_LIMIT.toNanos();
            try (Connection admin = openRoot(mysql);
                 PreparedStatement ps = admin.prepareStatement(WAITERS_BLOCKED_BY_LEADER_SQL)) {
                ps.setLong(1, leaderConnectionId);
                ps.setString(2, lockName(postingId));
                while (System.nanoTime() < deadline) {
                    if (f.isDone()) {
                        observation = FollowerObservation.FINISHED_BEFORE_LEADER_COMMIT;
                        detail = "後続が受諾のコミット前に終わった（受諾のロックを待っていない）: " + describe(f);
                        return;
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            observation = FollowerObservation.WAITED_FOR_LEADER;
                            detail = "後続が受諾（thread=" + leaderConnectionId + "）のロックを待っている";
                            return;
                        }
                    }
                    Thread.sleep(POLL_INTERVAL_MILLIS);
                }
                observation = FollowerObservation.TIMED_OUT;
                detail = OBSERVE_LIMIT.toSeconds() + " 秒以内に後続の待ちも終了も観測できなかった";
            } catch (SQLException ex) {
                observation = FollowerObservation.ERROR;
                detail = "観測に失敗: " + ex.getMessage();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                observation = FollowerObservation.ERROR;
                detail = "観測中に割り込まれた";
            }
        }

        FollowerObservation observation() {
            return observation;
        }

        String detail() {
            return detail;
        }

        Future<T> follower() {
            if (follower == null) {
                throw new AssertionError("前提失敗: MATCHED イベントが発行されず後続は開始されていない: " + detail);
            }
            return follower;
        }

        /** 購読を外し、後続の終了を上限付きで必ず待つ（後始末との競合を防ぐ）。 */
        @Override
        public void close() {
            multicaster.removeApplicationListener(listener);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(90, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    throw new IllegalStateException("後続 TX が 90 秒以内に終わらない: " + detail);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                throw new IllegalStateException("後続の終了待ちの間に割り込まれた: " + detail, e);
            }
        }
    }
}
