package com.mannschaft.app.role;

import com.mannschaft.app.auth.service.AuthTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261107-1833 PR3: 再招待と見送りの並行競合（実 DB・並行スレッド）。C1〜C3。
 *
 * <p>勝敗は auth の条件付き UPDATE（REISSUE_REQUESTED のときだけ効く）で決まる。再招待が勝てば有効なトークン 1 件・
 * INVITE_REISSUED だけ、見送りが勝てば有効なトークン 0 件・INVITE_REISSUE_DECLINED だけ。負けた側は 409 ROLE_017。
 * 順序が決まった場合は {@link InviteReissueRequestContractIT}、止め所を差し込んだ競合（F-0）は {@link InviteReissueRequestFailureIT}。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261107-1833 PR3 再招待と見送りの並行競合")
class InviteReissueRequestRaceIT extends InviteAcceptanceItSupport {

    private static final int ROUNDS = 3;

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService authTokens;

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

    private List<Integer> race(ReissueFixtures.Case c, List<String> paths) throws Exception {
        String auth = fx.bearer(c.admin());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(paths.size());
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (String path : paths) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post(path).header("Authorization", auth)).andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C1/C2: 再招待と見送りを同時に押すと、200 は 1 件・もう一方は 409。勝者に応じて有効なトークン（1/0 件）と"
            + "監査・通知（REISSUED だけ／DECLINED だけ）がそろう")
    void reissueVersusDecline(String type) throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            ReissueFixtures.Case c = fx.reissueRequested(type);

            List<Integer> statuses = race(c, List.of(ReissueFixtures.reissuePath(c), ReissueFixtures.declinePath(c)));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            boolean reissueWon = statuses.get(0) == 200;
            ReissueFixtures.settle();
            if (reissueWon) {
                assertThat(fx.pendingStatus(c.requester())).isEqualTo("PENDING");
                assertThat(fx.activeTokens(c)).isEqualTo(1);
                assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUED"), c.requester())).isEqualTo(1);
                assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUE_DECLINED"), c.requester())).isZero();
                assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isEqualTo(1);
                assertThat(fx.notifications(c.requester(), "INVITE_REISSUE_DECLINED")).isZero();
            } else {
                assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_DECLINED");
                assertThat(fx.activeTokens(c)).isZero();
                assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUE_DECLINED"), c.requester())).isEqualTo(1);
                assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUED"), c.requester())).isZero();
                assertThat(fx.notifications(c.requester(), "INVITE_REISSUE_DECLINED")).isEqualTo(1);
                assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isZero();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C3: 同時の再招待（二重押し）でも 200 は 1 件・他は 409、有効なトークンは 1 件、INVITE_REISSUED は 1 件")
    void doubleReissue(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        String path = ReissueFixtures.reissuePath(c);

        List<Integer> statuses = race(c, List.of(path, path, path));

        assertThat(statuses).containsExactlyInAnyOrder(200, 409, 409);
        assertThat(fx.activeTokens(c)).isEqualTo(1);
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("PENDING");
        ReissueFixtures.await(() -> fx.notifications(c.requester(), "INVITE_REISSUED"), 1, "INVITE_REISSUED");
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isEqualTo(1);
        assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUED"), c.requester())).isEqualTo(1);
    }
}
