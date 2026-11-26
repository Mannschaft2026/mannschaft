package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.config.DataSourceContextHolder;
import com.mannschaft.app.config.DataSourceType;
import com.mannschaft.app.config.ReplicaRoutingAspect;
import com.mannschaft.app.config.RoutingDataSource;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareRepository;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F06.4.1 PR-3b 共有の書込判断に使う読込が PRIMARY へ向くこと（CMP-261115-2145、Codex 4 巡目 P2）。
 *
 * <p>リードレプリカ有効時、メソッドの {@code @Transactional(readOnly = true)} は {@link ReplicaRoutingAspect} が REPLICA へ回す。
 * 共有 PUT・訂正の commit 直後に動く非同期の同期処理が、イベントを捨てる根拠（現在の desired command）をレプリカから読むと、
 * 複製の遅れだけで正常なイベントを古い要求として捨て、共有が PENDING に取り残される。判断の読込は PRIMARY の独立 TX で行う。</p>
 *
 * <p>実 ReplicaRoutingAspect・RoutingDataSource・TX proxy で物理接続の取得先を観測する小 Spring 試練
 * （{@code ModuleEventNoticePrimaryRoutingTest} と同じ形）。Repository は mock とし、DB は起動しない。</p>
 */
@DisplayName("F06.4.1 PR-3b 共有の書込判断の読込は PRIMARY の独立 TX で行う")
class EffortShareDecisionReadRoutingTest {

    @AfterEach
    void routing指定を残さない() {
        DataSourceContextHolder.clear();
    }

    @Test
    @DisplayName("P2: 同期処理がイベントを捨てる根拠の pendingCommand は PRIMARY の接続で読む（非同期の入口＝指定なし）")
    void pendingCommandはPRIMARYで読む() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Fixtures.class)) {
            assertThat(context.getBean(EffortShareTxService.class).pendingCommand(UUID.randomUUID())).isEmpty();
            assertThat(context.getBean(Trace.class).targets).containsExactly(DataSourceType.PRIMARY);
            assertThat(DataSourceContextHolder.getDataSourceType()).isNull();
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        }
    }

    @Test
    @DisplayName("P2: 外側が REPLICA 指定でも pendingCommand は独立した PRIMARY の接続で読み、外側の指定を復元する")
    void 外側REPLICAでもpendingCommandはPRIMARYで読む() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Fixtures.class)) {
            DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);
            assertThat(context.getBean(EffortShareTxService.class).pendingCommand(UUID.randomUUID())).isEmpty();
            assertThat(context.getBean(Trace.class).targets).containsExactly(DataSourceType.PRIMARY);
            assertThat(DataSourceContextHolder.getDataSourceType()).isEqualTo(DataSourceType.REPLICA);
        }
    }

    @Test
    @DisplayName("P2: 訂正の再同期・preview・retry の判断に使う owned は PRIMARY の接続で読む")
    void ownedはPRIMARYで読む() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Fixtures.class)) {
            assertThat(context.getBean(EffortShareTxService.class).owned(1L, UUID.randomUUID())).isNotNull();
            assertThat(context.getBean(Trace.class).targets).containsExactly(DataSourceType.PRIMARY);
        }
    }

    @Test
    @DisplayName("P2: 非表示の後処理が timeline の失効版を決める team 行の find は PRIMARY の接続で読む")
    void teamShareのfindはPRIMARYで読む() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(Fixtures.class)) {
            assertThat(context.getBean(EffortTeamShareTxService.class).find(UUID.randomUUID())).isEmpty();
            assertThat(context.getBean(Trace.class).targets).containsExactly(DataSourceType.PRIMARY);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Fixtures {
        @Bean Trace trace() { return new Trace(); }
        @Bean ReplicaRoutingAspect routingAspect() { return new ReplicaRoutingAspect(); }
        @Bean RoutingDataSource dataSource(Trace trace) {
            RoutingDataSource routing = new RoutingDataSource();
            routing.setTargetDataSources(Map.of(DataSourceType.PRIMARY, new RecordingDataSource(DataSourceType.PRIMARY, trace),
                    DataSourceType.REPLICA, new RecordingDataSource(DataSourceType.REPLICA, trace)));
            routing.setDefaultTargetDataSource(new RecordingDataSource(DataSourceType.PRIMARY, trace));
            return routing;
        }
        @Bean DataSourceTransactionManager transactionManager(RoutingDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        /** {@code @PersistenceContext} の注入先（この試練では EntityManager を使わない）。 */
        @Bean EntityManagerFactory entityManagerFactory() { return mock(EntityManagerFactory.class); }
        @Bean EffortShareTxService ownerTx() {
            EffortRecordRepository records = mock(EffortRecordRepository.class);
            when(records.findById(any())).thenReturn(Optional.empty());
            when(records.findByIdAndOwnerUserId(any(), any())).thenReturn(Optional.of(EffortRecordEntity.builder().build()));
            return new EffortShareTxService(records, mock(EffortTeamShareRepository.class), null, null, null, null, null,
                    null, null);
        }
        @Bean EffortTeamShareTxService teamTx() {
            EffortTeamShareRepository teamShares = mock(EffortTeamShareRepository.class);
            when(teamShares.findById(any())).thenReturn(Optional.empty());
            return new EffortTeamShareTxService(teamShares, null, null, null, null, null);
        }
    }

    static class Trace {
        final List<DataSourceType> targets = new ArrayList<>();
    }

    static class RecordingDataSource extends AbstractDataSource {
        private final DataSourceType target;
        private final Trace trace;
        RecordingDataSource(DataSourceType target, Trace trace) { this.target = target; this.trace = trace; }
        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true);
            trace.targets.add(target);
            return connection;
        }
        @Override
        public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }
    }
}
