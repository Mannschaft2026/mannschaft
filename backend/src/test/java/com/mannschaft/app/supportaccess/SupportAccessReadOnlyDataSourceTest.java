package com.mannschaft.app.supportaccess;

import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.context.SupportAccessReadOnlyDataSourcePostProcessor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 戦役Z-B 試練（単体）: 代理中の DB 接続の READ ONLY 化（第3層）。Docker 不要。
 *
 * <p>実 MySQL に対する書込拒否は {@code SupportAccessReadOnlyConnectionIT}。ここでは、本物の {@link HikariDataSource} を
 * プロキシで包んでも型が変わらないこと、代理中だけ接続に {@code setReadOnly(true)} が掛かること、
 * 書込可能な区間では掛からないこと、返却時に HikariCP が書込可へ戻すこと（プールへ持ち越さない）を確かめる。</p>
 */
@DisplayName("戦役Z-B 代理中の DB 接続 READ ONLY 化（単体）")
class SupportAccessReadOnlyDataSourceTest {

    private static final SupportAccessContext CONTEXT =
            new SupportAccessContext(UUID.randomUUID(), 9001L, 42L, Instant.parse("2026-11-27T10:00:00Z"));

    private Connection physical;
    private HikariDataSource hikari;
    private DataSource proxied;

    @BeforeEach
    void setUp() throws Exception {
        physical = mock(Connection.class);
        when(physical.isValid(anyInt())).thenReturn(true);
        DataSource fake = mock(DataSource.class);
        when(fake.getConnection()).thenReturn(physical);

        HikariConfig config = new HikariConfig();
        config.setDataSource(fake);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setPoolName("support-access-readonly-test");
        hikari = new HikariDataSource(config);

        BeanPostProcessor processor = SupportAccessReadOnlyDataSourcePostProcessor
                .supportAccessReadOnlyDataSourceBeanPostProcessor();
        proxied = (DataSource) processor.postProcessAfterInitialization(hikari, "dataSource");
    }

    @AfterEach
    void tearDown() {
        SupportAccessContextHolder.clear();
        hikari.close();
    }

    @Test
    @DisplayName("包んだ後も HikariDataSource のまま（型・プール設定を前提にした既存コードを壊さない）")
    void プロキシはHikariDataSourceのまま() {
        assertThat(proxied).isNotSameAs(hikari).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) proxied).getMaximumPoolSize()).isEqualTo(1);
        assertThat(((HikariDataSource) proxied).isClosed()).isFalse();
    }

    @Test
    @DisplayName("HikariDataSource 以外・アーカイブ用の DataSource は包まない")
    void 対象外のDataSourceは包まない() {
        BeanPostProcessor processor = SupportAccessReadOnlyDataSourcePostProcessor
                .supportAccessReadOnlyDataSourceBeanPostProcessor();
        DataSource other = mock(DataSource.class);
        assertThat(processor.postProcessAfterInitialization(other, "dataSource")).isSameAs(other);
        try (HikariDataSource archive = new HikariDataSource()) {
            assertThat(processor.postProcessAfterInitialization(archive, "archiveDataSource")).isSameAs(archive);
        }
    }

    @Test
    @DisplayName("代理でなければ接続に何もしない")
    void 代理でなければ読取専用にしない() throws Exception {
        try (Connection c = proxied.getConnection()) {
            assertThat(c).isNotNull();
        }

        verify(physical, never()).setReadOnly(true);
    }

    @Test
    @DisplayName("代理中は取得した接続を READ ONLY にし、返却時に HikariCP が書込可へ戻す")
    void 代理中は読取専用にして返却時に戻す() throws Exception {
        SupportAccessContextHolder.set(CONTEXT);

        try (Connection c = proxied.getConnection()) {
            assertThat(c).isNotNull();
        }

        var order = inOrder(physical);
        order.verify(physical).setReadOnly(true);
        // 接続はプールが保持したまま。返却（close）で既定（書込可）へ戻る。次の利用者へ READ ONLY を持ち越さない。
        order.verify(physical).setReadOnly(false);
    }

    @Test
    @DisplayName("書込可能な区間（閲覧記録・監査）で取得した接続は READ ONLY にしない")
    void 書込可能な区間は読取専用にしない() throws Exception {
        SupportAccessContextHolder.set(CONTEXT);

        SupportAccessContextHolder.callWithWritableConnection(() -> {
            try (Connection c = proxied.getConnection()) {
                return c;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        verify(physical, never()).setReadOnly(true);
    }

    @Test
    @DisplayName("READ ONLY にできなかった接続は閉じて例外にする（書込可能な接続を代理に使わせない）")
    void 設定失敗はfailClosed() throws Exception {
        SupportAccessContextHolder.set(CONTEXT);
        org.mockito.Mockito.doThrow(new java.sql.SQLException("setReadOnly 不可")).when(physical).setReadOnly(true);

        assertThatThrownBy(() -> proxied.getConnection())
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("setReadOnly");
    }
}
