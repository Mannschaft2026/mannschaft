package com.mannschaft.app.signage;

import com.mannschaft.app.signage.websocket.SignageSocketAccessReader;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AC6/8: 実MySQLの旧REPEATABLE READと独立資格照合、実broker配送のquery数を検査する。
 * 共通fixtureだけを共有し、15ネットワーク先行試練を継承して二重実行しない。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SignageSocketDatabaseIT extends SignageSocketIntegrationFixture {
    @Autowired SignageSocketAccessReader accessReader;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Test
    void AC6_publisherの旧RRsnapshotでも失効commit後の独立資格判定は拒否する() throws Exception {
        Inbox revoked = subscribe(connect("/ws", token.getToken(), null, null), emergency());
        Inbox control = subscribe(connect("/ws/signage", otherToken.getToken(), null, null), emergency());
        TransactionTemplate outer = new TransactionTemplate(transactions);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertThat(accessReader.authenticate(token.getToken(), "127.0.0.1")).isPresent();
            assertThat(((Number) em.createNativeQuery("SELECT CASE WHEN is_active THEN 1 ELSE 0 END FROM signage_access_tokens WHERE id=:id")
                    .setParameter("id", token.getId()).getSingleResult()).intValue()).isEqualTo(1);
            CompletableFuture.runAsync(() -> mutateToken("isActive", false)).join();
            assertThat(((Number) em.createNativeQuery("SELECT CASE WHEN is_active THEN 1 ELSE 0 END FROM signage_access_tokens WHERE id=:id")
                    .setParameter("id", token.getId()).getSingleResult()).intValue())
                    .as("publisher側のRRは失効前のsnapshotを保持").isEqualTo(1);
            assertThat(accessReader.permits(token.getId(), screen.getId(), "127.0.0.1"))
                    .as("同じthread内の資格照合も旧RRへ参加しない").isFalse();
            try {
                assertDelivery(control, revoked);
            } catch (Exception e) {
                throw new AssertionError("旧RR内publisherからの配送を実STOMPで検査", e);
            }
        });
    }

    @Test
    void AC8_正常配送はsubscriptionごとに1projectionで制御と非Signageは0query() throws Exception {
        var statistics = entityManagerFactory.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        boolean previous = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            var session = connect("/ws", token.getToken(), null, null);
            Inbox device = subscribe(session, emergency());
            long before = projectionQueries(statistics);
            String marker = UUID.randomUUID().toString();
            publisher.publishEmergency(screen.getId(), marker);
            assertThat(device.messages.poll(10, TimeUnit.SECONDS)).containsEntry("message", marker);
            assertThat(projectionQueries(statistics) - before).isEqualTo(1);
            before = projectionQueries(statistics);
            device.subscription.unsubscribe();
            await().atMost(Duration.ofSeconds(10)).until(() -> !registered(emergency(), device.id));
            assertNonSignageDelivery();
            assertThat(projectionQueries(statistics) - before).isZero();
        } finally {
            statistics.setStatisticsEnabled(previous);
        }
    }

    private long projectionQueries(org.hibernate.stat.Statistics statistics) {
        return Arrays.stream(statistics.getQueries()).filter(query -> query.contains("SignageSocketAccess("))
                .mapToLong(query -> statistics.getQueryStatistics(query).getExecutionCount()).sum();
    }

}
