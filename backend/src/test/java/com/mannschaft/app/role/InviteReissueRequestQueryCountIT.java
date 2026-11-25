package com.mannschaft.app.role;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.PendingInviteAdminQueryService;
import com.mannschaft.app.common.PendingAggregate;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261107-1833 PR3: 再招待依頼の一覧と集約の SQL 本数（L4・L5）。
 *
 * <p>L4: 表示名はページごとにまとめて 1 本で引く（依頼者ごとに問い合わせない）。行数 2 と 20 で一覧の SQL 本数が同じであること。
 * L5: 集約の {@code preview_size=0} は件数の 1 本だけで、表示名を引く SQL を出さない。
 * SessionFactory 全体の Statistics を使うため排他（{@link Isolated}）にし、有効状態は必ず戻す
 * （金型: {@code AdminActionRequiredEmptyQueryCountIT}）。</p>
 */
@Isolated
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261107-1833 PR3 再招待依頼の一覧・集約の SQL 本数")
class InviteReissueRequestQueryCountIT extends InviteAcceptanceItSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService authTokens;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PendingInviteAdminQueryService adminQuery;

    private ReissueFixtures fx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fx = new ReissueFixtures(this, authTokens);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    private <T> Measured<T> measure(Supplier<T> work) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = stats.isStatisticsEnabled();
        try {
            stats.setStatisticsEnabled(true);
            stats.clear();
            T result = work.get();
            return new Measured<>(result, stats.getPrepareStatementCount());
        } finally {
            stats.setStatisticsEnabled(originallyEnabled);
        }
    }

    private record Measured<T>(T result, long statements) { }

    private long listStatements(ReissueFixtures.Case c, String auth) {
        return measure(() -> {
            try {
                return mockMvc.perform(get(ReissueFixtures.base(c) + "?page=0&size=20").header("Authorization", auth))
                        .andExpect(status().isOk()).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).statements();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L4: 一覧の SQL 本数は行数に依らない（2 行と 20 行で同じ。表示名はページごとに 1 本）")
    void listDoesNotIssuePerRowQueries(String type) {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(50));
        String auth = fx.bearer(c.admin());
        listStatements(c, auth); // 初回のキャッシュ・遅延初期化を計測から外す
        long two = listStatements(c, auth);

        for (int i = 0; i < 18; i++) {
            fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(40).plusSeconds(i));
        }
        long twenty = listStatements(c, auth);

        assertThat(twenty).as("20 行の一覧の SQL 本数（2 行: " + two + "）").isEqualTo(two);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L5: 集約の preview_size=0 は件数の 1 本だけ（表示名を引かない）。件数は依頼の数")
    void countOnlyAggregateIssuesOneStatement(String type) {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(50));

        Measured<PendingAggregate> measured =
                measure(() -> adminQuery.reissueRequestsFor(c.scope().type(), c.scope().id(), c.slug(), 0));

        assertThat(measured.result().pendingCount()).isEqualTo(2);
        assertThat(measured.result().items()).isEmpty();
        assertThat(measured.statements()).as("preview_size=0 は COUNT の 1 本だけ").isEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L4: 集約のプレビュー（preview_size=3）も行数に依らず、表示名は古い順の依頼者のもの")
    void previewNamesAreBatched(String type) {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(50));
        long withTwo = measure(() -> adminQuery.reissueRequestsFor(c.scope().type(), c.scope().id(), c.slug(), 3)).statements();
        fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(40));

        Measured<PendingAggregate> three =
                measure(() -> adminQuery.reissueRequestsFor(c.scope().type(), c.scope().id(), c.slug(), 3));

        assertThat(three.statements()).isEqualTo(withTwo);
        assertThat(three.result().items()).hasSize(3);
        assertThat(three.result().items().get(0).requestedBy()).isEqualTo(c.requesterName());
        assertThat(three.result().items().get(0).detailRoute()).isEqualTo(ReissueFixtures.invitesRoute(c));
    }
}
