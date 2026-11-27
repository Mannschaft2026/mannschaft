package com.mannschaft.app.supportaccess.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.Session;
import org.hibernate.jdbc.ReturningWork;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 第3回検分対応: ロック待ちの上限の区間が、本体・flush の失敗でも必ず既定に戻し、戻せない接続は壊すこと。
 */
@DisplayName("SupportAccessLockWaitScope（ロック待ち5秒の区間の確実な復帰）")
class SupportAccessLockWaitScopeTest {

    private EntityManager entityManager;
    private Connection connection;
    private Statement statement;
    private SupportAccessLockWaitScope scope;

    @BeforeEach
    void setUp() throws Exception {
        entityManager = mock(EntityManager.class);
        Session session = mock(Session.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        given(entityManager.unwrap(Session.class)).willReturn(session);
        given(session.doReturningWork(any())).willAnswer(inv ->
                inv.<ReturningWork<?>>getArgument(0).execute(connection));
        given(connection.createStatement()).willReturn(statement);
        scope = new SupportAccessLockWaitScope(entityManager);
    }

    @Test
    @DisplayName("正常時: 5秒にする → 本体 → flush → 既定に戻す の順で、戻しは JDBC 接続へ直接実行する")
    void 正常時の順序() throws Exception {
        Runnable body = mock(Runnable.class);

        String result = scope.call(() -> {
            body.run();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        InOrder order = inOrder(statement, body, entityManager);
        order.verify(statement).execute(SupportAccessLockWaitScope.SHORTEN_SQL);
        order.verify(body).run();
        order.verify(entityManager).flush();
        order.verify(statement).execute(SupportAccessLockWaitScope.RESTORE_SQL);
        verify(connection, never()).abort(any(Executor.class));
    }

    @Test
    @DisplayName("flush が例外（ロック待ち超過・制約違反）を投げても戻しは実行され、元の例外がそのまま返る")
    void flushの失敗でも戻す() throws Exception {
        PersistenceException flushFailure = new PersistenceException("Lock wait timeout exceeded");
        willThrow(flushFailure).given(entityManager).flush();

        assertThatThrownBy(() -> scope.call(() -> "x")).isSameAs(flushFailure);

        verify(statement).execute(SupportAccessLockWaitScope.RESTORE_SQL);
        verify(connection, never()).abort(any(Executor.class));
        assertThat(flushFailure.getSuppressed()).isEmpty();
    }

    @Test
    @DisplayName("本体の例外と戻しの失敗が重なっても元の例外を返し、戻しの失敗は suppressed、接続は壊す（再利用させない）")
    void 戻しの失敗は元の例外を隠さず接続を壊す() throws Exception {
        IllegalStateException bodyFailure = new IllegalStateException("本体の失敗");
        SQLException restoreFailure = new SQLException("connection lost", "08S01");
        given(statement.execute(SupportAccessLockWaitScope.RESTORE_SQL)).willThrow(restoreFailure);

        assertThatThrownBy(() -> scope.call(() -> {
            throw bodyFailure;
        })).isSameAs(bodyFailure);

        assertThat(bodyFailure.getSuppressed()).containsExactly(restoreFailure);
        verify(connection).abort(any(Executor.class));
    }

    @Test
    @DisplayName("本体は成功したが戻しに失敗したら、接続を壊して例外にする（5秒のままの接続をプールへ返さない・コミットさせない）")
    void 成功後の戻し失敗は接続を壊して例外() throws Exception {
        SQLException restoreFailure = new SQLException("connection lost", "08S01");
        given(statement.execute(SupportAccessLockWaitScope.RESTORE_SQL)).willThrow(restoreFailure);

        assertThatThrownBy(() -> scope.call(() -> "ok"))
                .isInstanceOf(IllegalStateException.class)
                .hasCause(restoreFailure);

        verify(connection).abort(any(Executor.class));
    }

    @Test
    @DisplayName("5秒にする SET 自体が失敗したら、本体を実行せず接続を壊す")
    void 短縮の失敗は本体を実行しない() throws Exception {
        SQLException shortenFailure = new SQLException("connection lost", "08S01");
        given(statement.execute(SupportAccessLockWaitScope.SHORTEN_SQL)).willThrow(shortenFailure);
        Runnable body = mock(Runnable.class);

        assertThatThrownBy(() -> scope.call(() -> {
            body.run();
            return null;
        })).isSameAs(shortenFailure);

        verify(body, never()).run();
        verify(connection).abort(any(Executor.class));
    }
}
