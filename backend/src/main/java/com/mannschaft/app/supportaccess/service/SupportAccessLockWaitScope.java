package com.mannschaft.app.supportaccess.service;

import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

/**
 * F10.9 遷移トランザクションの「ロック待ちの上限を5秒にする区間」（第3回検分対応）。
 *
 * <p>{@code jakarta.persistence.lock.timeout} のミリ秒指定は Hibernate の MySQL 方言が黙って無視するため、
 * 接続のセッション変数 {@code innodb_lock_wait_timeout} を区間の先頭で5秒にし、終わりに既定へ戻す。
 * 接続はプールで再利用されるので、<b>戻せなかった接続をプールへ返してはならない</b>（後の無関係な処理が
 * 5秒でロック待ちを打ち切られる）。</p>
 *
 * <h2>以前の実装の欠陥と、この区間の守り</h2>
 * <ol>
 *   <li><b>本体の書込は区間の中で確定させる</b>: 以前は戻しの SET を {@code @Modifying} のネイティブクエリで
 *       実行しており、その {@code executeUpdate} が Hibernate の自動 flush を誘発していた。メールを伴わない遷移では
 *       grant の UPDATE・outbox の INSERT がこの flush で初めて走り、そこでのロック待ち超過・デッドロック・制約違反で
 *       戻しの SET 自体が実行されなかった。いまは本体の直後（区間の中）で明示的に {@link EntityManager#flush()} する。</li>
 *   <li><b>戻しは Hibernate を通さない</b>: 先頭の SET を {@link Session#doReturningWork} で実行するときに
 *       このトランザクションの JDBC 接続を受け取り（Spring の JPA トランザクションは接続を TX の終わりまで保持する）、
 *       戻しはその接続へ直接 {@link Statement} で実行する。flush も、壊れたセッションの状態にも左右されない。</li>
 *   <li><b>戻せなかった接続は壊す</b>: 戻しが失敗したら {@link Connection#abort} で物理接続を閉じる。HikariCP は
 *       閉じた接続への操作（TX の巻き戻し・返却時のリセット）で SQLState 08 系の例外を受けて接続を破棄し、
 *       プールへは戻さない（借り出し時の生存確認も閉じた接続を弾く）。</li>
 *   <li><b>元の例外を隠さない</b>: 本体（flush を含む）の例外を投げ直し、戻しの失敗はその suppressed に付ける。</li>
 * </ol>
 */
@Slf4j
@Component
public class SupportAccessLockWaitScope {

    static final String SHORTEN_SQL = "SET SESSION innodb_lock_wait_timeout = 5";
    static final String RESTORE_SQL = "SET SESSION innodb_lock_wait_timeout = DEFAULT";

    private final EntityManager entityManager;

    public SupportAccessLockWaitScope(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * ロック待ちの上限を5秒にして本体を実行し、書込を flush してから既定に戻す。トランザクションの中で呼ぶこと。
     * 5秒を超える待ち・デッドロックは例外のまま返す（呼び出し側が再試行を判断する）。
     */
    public <T> T call(Supplier<T> body) {
        Connection connection = entityManager.unwrap(Session.class).doReturningWork(c -> {
            try {
                execute(c, SHORTEN_SQL);
            } catch (SQLException e) {
                // 5秒にできたかどうか分からない接続はプールへ返さない
                abortQuietly(c, e);
                throw e;
            }
            return c;
        });
        T result;
        try {
            result = body.get();
            // 本体の UPDATE/INSERT をここで確定させる（戻しの後の自動 flush で初めて失敗させない）
            entityManager.flush();
        } catch (RuntimeException | Error primary) {
            try {
                restore(connection);
            } catch (SQLException | RuntimeException restoreFailure) {
                primary.addSuppressed(restoreFailure);
            }
            throw primary;
        }
        try {
            restore(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("サポート閲覧: ロック待ちの上限を既定に戻せない（接続は破棄した）", e);
        }
        return result;
    }

    /** 既定に戻す。失敗したら接続を壊して（プールへ再利用させず）例外を返す。 */
    private static void restore(Connection connection) throws SQLException {
        try {
            execute(connection, RESTORE_SQL);
        } catch (SQLException | RuntimeException e) {
            log.error("サポート閲覧: ロック待ちの上限を既定に戻せないため接続を破棄する error={}", e.toString());
            abortQuietly(connection, e);
            throw e;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** 物理接続を閉じる（HikariCP は閉じた接続を返却時に破棄する）。abort の失敗は元の例外の suppressed に付ける。 */
    private static void abortQuietly(Connection connection, Exception cause) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException | RuntimeException abortFailure) {
            cause.addSuppressed(abortFailure);
        }
    }
}
