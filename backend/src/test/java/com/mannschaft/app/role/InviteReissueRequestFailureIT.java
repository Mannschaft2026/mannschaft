package com.mannschaft.app.role;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.auth.service.PendingInviteStore;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.dto.InviteValidity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.service.InviteAcceptanceWriter;
import com.mannschaft.app.role.service.MembershipInviteTokenIssuer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261107-1833 PR3: 再招待の途中失敗（故障注入）の契約試験（F-0・F-DB・F-ACT・N5）。
 *
 * <p>止め所は既存 Bean の spy（{@link PendingInviteStore#confirmReissue}・{@link MembershipInviteTokenIssuer#activateStaged}・
 * {@link AuditLogService}）で作る。spy の集合は中間基底 {@link InviteAcceptanceSpyItSupport} で揃え、同じ context を共有する
 * （ContextOverrideCensus の distinct key を増やさない）。proxy の内側（{@link AopTestUtils#getUltimateTargetObject}）を
 * stub する流儀は {@link RegistrationPendingInviteFailureIT} と同じ。</p>
 *
 * <p>手順（設計 c・e）: 仮発行（{@code revoked_at = created_at}）→ recheckIssued → auth の確定（条件付き UPDATE）→
 * 有効化（{@code revoked_at = NULL}）→ 監査。補償による失効は行わない。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261107-1833 PR3 再招待の途中失敗（故障注入）")
class InviteReissueRequestFailureIT extends InviteAcceptanceSpyItSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService authTokens;
    @Autowired private InviteTokenRepository inviteTokens;

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

    private PendingInviteStore store() {
        return AopTestUtils.getUltimateTargetObject(pendingStoreSpy);
    }

    private MembershipInviteTokenIssuer issuer() {
        return AopTestUtils.getUltimateTargetObject(issuerSpy);
    }

    private AuditLogService audit() {
        return AopTestUtils.getUltimateTargetObject(auditSpy);
    }

    private PendingInviteService pending() {
        return context.getBean(PendingInviteService.class);
    }

    private ResultActions reissue(ReissueFixtures.Case c) throws Exception {
        return mockMvc.perform(post(ReissueFixtures.reissuePath(c)).header("Authorization", fx.bearer(c.admin())));
    }

    private ResultActions decline(ReissueFixtures.Case c) throws Exception {
        return mockMvc.perform(post(ReissueFixtures.declinePath(c)).header("Authorization", fx.bearer(c.admin())));
    }

    /** 宛先が依頼者で失効していない招待（DM 招待の重複判定 hasActivePendingInvite と同じ候補）。 */
    private long dmDuplicateCandidates(ReissueFixtures.Case c) {
        return ("TEAM".equals(c.scope().type())
                ? inviteTokens.findByTargetUserIdAndTeamIdAndRevokedAtIsNull(c.requester(), c.scope().id())
                : inviteTokens.findByTargetUserIdAndOrganizationIdAndRevokedAtIsNull(c.requester(), c.scope().id())).size();
    }

    // ------------------------------------------------------------------
    // F-0: auth の確定が 0 行（競合で見送りが先に確定）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("F-0: 仮発行の後、auth の確定が 0 行（見送りが先に確定）なら 409 ROLE_017。トークンは無効（仮発行の印のまま）で、"
            + "tokenId 指定の受諾は ROLE_002、DM 招待の重複判定にも入らない。INVITE_REISSUED の通知・監査は無い")
    void confirmLosesRace(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        doAnswer(inv -> {
            jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET status = 'REISSUE_DECLINED' WHERE user_id = ?",
                    c.requester());
            return inv.callRealMethod();
        }).when(store()).confirmReissue(any(), anyString(), anyLong(), anyString());

        reissue(c).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_017"));

        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_DECLINED");
        assertThat(fx.tokensFor(c)).as("仮発行したトークンは残る（補償の失効はしない）").singleElement().satisfies(t -> {
            assertThat(t.get("revoked_at")).as("無効のまま").isNotNull();
            assertThat(t.get("revoked_at")).as("仮発行の印 revoked_at=created_at").isEqualTo(t.get("created_at"));
        });
        assertThat(fx.activeTokens(c)).isZero();
        assertThat(dmDuplicateCandidates(c)).isZero();
        Long tokenId = ((Number) fx.tokensFor(c).get(0).get("id")).longValue();
        mockMvc.perform(post("/api/v1/invite/invitations/" + tokenId + "/accept")
                        .header("Authorization", fx.bearer(c.requester())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROLE_002"));
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isZero();
        assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUED"), c.requester())).isZero();
    }

    // ------------------------------------------------------------------
    // F-DB: auth の確定で DB 例外
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-DB: auth の確定で DB 例外なら 500。保留は REISSUE_REQUESTED のまま、トークンは無効。通知・監査なし")
    void confirmDbFailure() throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        Map<String, Object> before = fx.pendingRow(c.requester());
        doThrow(new DataAccessResourceFailureException("試練: auth の確定で停止"))
                .when(store()).confirmReissue(any(), anyString(), anyLong(), anyString());

        reissue(c).andExpect(status().isInternalServerError());

        assertThat(fx.pendingRow(c.requester())).isEqualTo(before);
        assertThat(fx.activeTokens(c)).isZero();
        assertThat(dmDuplicateCandidates(c)).isZero();
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isZero();
        assertThat(fx.audits("TEAM_INVITE_REISSUED", c.requester())).isZero();
    }

    // ------------------------------------------------------------------
    // F-ACT: 有効化の失敗
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"ZERO_ROWS", "DB_ERROR"})
    @DisplayName("F-ACT: 有効化が失敗（0 行・DB 例外）なら 500 ROLE_019。保留は PENDING で使えないトークンを指す。"
            + "窓（60 秒）を過ぎた次の GET で EXPIRED になり、7 日を待たず再依頼でき、招待権限者へ再び通知が届く")
    void activationFailureRecovers(String mode) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        if ("ZERO_ROWS".equals(mode)) {
            doReturn(false).when(issuer()).activateStaged(anyLong(), anyLong());
        } else {
            doThrow(new DataAccessResourceFailureException("試練: 有効化で停止")).when(issuer()).activateStaged(anyLong(), anyLong());
        }

        reissue(c).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("ROLE_019"));

        Map<String, Object> row = fx.pendingRow(c.requester());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("reissue_requested_at")).isNull();
        assertThat(fx.tokensFor(c)).singleElement().satisfies(t -> {
            assertThat(t.get("token_hash")).isEqualTo(row.get("token_hash"));
            assertThat(t.get("revoked_at")).as("使えないトークン").isNotNull();
        });
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isZero();
        assertThat(fx.audits("TEAM_INVITE_REISSUED", c.requester())).as("監査は有効化が成功したときだけ").isZero();

        LocalDateTime aged = utcNow().minusSeconds(90);
        jdbc.update("UPDATE invite_tokens SET created_at = ?, revoked_at = ? WHERE id = ?", aged, aged,
                fx.tokensFor(c).get(0).get("id"));
        long notifiedBefore = fx.notifications(c.admin(), "INVITE_REISSUE_REQUESTED");

        assertThat(pending().findMine(c.requester()).status()).isEqualTo("EXPIRED");
        assertThat(pending().requestReissue(c.requester()).alreadyRequested()).isFalse();
        ReissueFixtures.await(() -> fx.notifications(c.admin(), "INVITE_REISSUE_REQUESTED"), notifiedBefore + 1,
                "回復後の再依頼の通知");
    }

    // ------------------------------------------------------------------
    // N5: 監査が失敗しても確定は維持
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("N5: 監査の記録が失敗しても、再招待（200 REISSUED・有効なトークン 1 件・保留 PENDING）と"
            + "見送り（200 REISSUE_DECLINED）は確定したまま")
    void auditFailureDoesNotRollBack(String type) throws Exception {
        ReissueFixtures.Case reissued = fx.reissueRequested(type);
        ReissueFixtures.Case declined = fx.requestIn(reissued.scope(), reissued.admin(), "MEMBER", utcNow().minusMinutes(5));
        RuntimeException failure = new IllegalStateException("試練: 監査の記録で停止");
        doThrow(failure).when(audit()).record(anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        doThrow(failure).when(audit()).recordSync(anyString(), any(), any(), any(), any(), any(), any(), any(), any());

        reissue(reissued).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUED"));
        decline(declined).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUE_DECLINED"));

        assertThat(fx.activeTokens(reissued)).isEqualTo(1);
        assertThat(fx.pendingStatus(reissued.requester())).isEqualTo("PENDING");
        assertThat(fx.pendingStatus(declined.requester())).isEqualTo("REISSUE_DECLINED");
    }

    private NotificationHelper notifier() {
        return AopTestUtils.getUltimateTargetObject(notificationSpy);
    }

    // ------------------------------------------------------------------
    // PR3 検分 P2-3: 止まっていた GET が新しい PENDING を EXPIRED に上書きしない
    // ------------------------------------------------------------------

    @Test
    @DisplayName("P2-3: 古い招待を判定した GET が止まっている間に再招待が完了しても、再開した GET は新しいハッシュの PENDING を EXPIRED にしない")
    void staleGetDoesNotExpireReissuedPending() {
        Scope scope = scope("TEAM");
        Long requester = user();
        InviteTokenEntity expired = token(scope, 0, utcNow().minusMinutes(5), 3, 0, null, null);
        fx.insertPending(requester, scope, "PENDING", expired.getTokenHash(), null);
        InviteTokenEntity fresh = token(scope, 0, utcNow().plusDays(7), 1, 0, requester, null);
        doAnswer(inv -> {
            // GET が古い招待を「期限切れ」と判定した後、UPDATE の前に再招待の確定が割り込んだ
            jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET token_hash = ?, version = version + 1 "
                    + "WHERE user_id = ?", fresh.getTokenHash(), requester);
            return inv.callRealMethod();
        }).when(store()).expirePending(anyLong(), anyString(), anyString());

        pending().findMine(requester);

        Map<String, Object> row = fx.pendingRow(requester);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("token_hash")).isEqualTo(fresh.getTokenHash());
        assertThat(row.get("failure_code")).isNull();
    }

    // ------------------------------------------------------------------
    // PR3 3 回目の検分: 有効化と参照時の失効は、仮発行の行の上の CAS で先にコミットした方だけが勝つ（実 DB・別接続）
    // ------------------------------------------------------------------

    /**
     * 別スレッド（＝別接続・別 TX）で実行して待つ。spy の中は有効化の TX が現スレッドに束縛されているため、
     * そこで jdbc やサービスを呼ぶと同じ TX に合流してしまう。
     */
    private static <T> T onOtherThread(java.util.concurrent.Callable<T> work) throws Exception {
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            return other.submit(work).get(60, TimeUnit.SECONDS);
        } finally {
            other.shutdownNow();
        }
    }

    /** 仮発行の印（revoked_at = created_at）を、DB 時刻から指定ミリ秒前の作成として付け直す（別接続で自動コミット）。 */
    private void restage(Long tokenId, long millisAgo) throws Exception {
        onOtherThread(() -> jdbc.update("UPDATE invite_tokens SET created_at = UTC_TIMESTAMP(6) - INTERVAL ? MICROSECOND, "
                + "revoked_at = created_at WHERE id = ?", millisAgo * 1000, tokenId));
    }

    private InviteAcceptanceWriter writerTarget() {
        return AopTestUtils.getUltimateTargetObject(writerSpy);
    }

    /** InnoDB の行ロック待ちの件数（information_schema.innodb_trx の LOCK WAIT）。 */
    private long lockWaits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state = 'LOCK WAIT'",
                Long.class);
    }

    @Test
    @DisplayName("3・4回目: 有効化が窓の内で UPDATE し未コミットの間に、別接続の GET が窓の外と読んで失効 CAS に到達し、行ロックで待つ"
            + "（spy で到達、innodb_trx で LOCK WAIT を観測）。有効化がコミットされると CAS は 0 行で、確定読みは VALID、保留は PENDING")
    void activationCommittedFirstWinsOverGetRevoke() throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch casReached = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Object> casResult = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            restage(inv.getArgument(0), 59_000); // 窓の内（あと約 1 秒で窓の外）
            Object result = inv.callRealMethod(); // UPDATE は成功し、まだコミットしない（proxy の TX の中）
            updated.countDown();
            assertThat(commit.await(60, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(issuer()).activateStaged(anyLong(), anyLong());
        doAnswer(inv -> {
            casReached.countDown();
            Object result = inv.callRealMethod(); // 未コミットの有効化の行ロックで待つ
            casResult.set(result);
            return result;
        }).when(writerTarget()).revokeExpiredStaged(anyString(), anyLong());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> reissue = pool.submit(() -> reissue(c).andReturn().getResponse().getStatus());
            assertThat(updated.await(30, TimeUnit.SECONDS)).isTrue();
            TimeUnit.MILLISECONDS.sleep(1500); // 作成から 60 秒を過ぎる（コミット済みの旧値は revoked_at = created_at のまま）
            long waitsBefore = lockWaits();
            Future<String> get = pool.submit(() -> pending().findMine(c.requester()).status());

            assertThat(casReached.await(30, TimeUnit.SECONDS)).as("GET は失効の CAS に到達する").isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (lockWaits() <= waitsBefore && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(50);
            }
            assertThat(lockWaits()).as("失効の CAS が未コミットの有効化の行ロックで待っている").isGreaterThan(waitsBefore);
            assertThat(get.isDone()).isFalse();

            commit.countDown();
            assertThat(reissue.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(get.get(30, TimeUnit.SECONDS)).as("有効化が勝った").isEqualTo("PENDING");
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
        verify(writerTarget(), times(1)).revokeExpiredStaged(anyString(), anyLong());
        assertThat(casResult.get()).as("CAS は 0 行で、同じ TX の確定読みは VALID").isEqualTo(Optional.of(InviteValidity.VALID));
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("PENDING");
        assertThat(fx.activeTokens(c)).isEqualTo(1);
        assertThat(pending().accept(c.requester()).status()).isEqualTo("JOINED");
    }

    @Test
    @DisplayName("3回目: 参照時の失効 CAS が先にコミットすると、有効化は窓の内の時刻でも 0 行で 500 ROLE_019。"
            + "保留は EXPIRED（ROLE_002）で、すぐ再依頼できる")
    void getRevokeCommittedFirstWinsOverActivation() throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        doAnswer(inv -> {
            Long tokenId = inv.getArgument(0);
            restage(tokenId, 61_000); // 窓の外
            assertThat(onOtherThread(() -> pending().findMine(c.requester()).status())).as("GET の失効が先にコミット")
                    .isEqualTo("EXPIRED");
            // 失効の勝ちは時刻ではなく印で決まることを示すため、作成時刻だけを窓の内へ戻す（revoked_at は恒久失効の値のまま）
            onOtherThread(() -> jdbc.update("UPDATE invite_tokens SET created_at = UTC_TIMESTAMP(6) - INTERVAL 10 SECOND "
                    + "WHERE id = ?", tokenId));
            return inv.callRealMethod();
        }).when(issuer()).activateStaged(anyLong(), anyLong());

        reissue(c).andExpect(status().isInternalServerError()).andExpect(jsonPath("$.error.code").value("ROLE_019"));

        assertThat(fx.pendingStatus(c.requester())).isEqualTo("EXPIRED");
        assertThat(fx.pendingRow(c.requester()).get("failure_code")).isEqualTo("ROLE_002");
        assertThat(fx.activeTokens(c)).isZero();
        assertThat(pending().requestReissue(c.requester()).alreadyRequested()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("3回目（Codex P3）: 別スレッドで本人の承認が ACCEPTING を確定して止まっている間に有効化を完了させても、"
            + "再招待は 200 REISSUED で招待は有効なまま。承認を再開すると JOINED")
    void acceptingHeldWhileActivationCompletes(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        CountDownLatch accepting = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        String acceptThread = "reissue-accept-" + c.requester();
        doAnswer(inv -> {
            if (acceptThread.equals(Thread.currentThread().getName())) {
                // ACCEPTING は auth の短TXでコミット済み。招待の有効性を読む直前で止める
                accepting.countDown();
                assertThat(resume.await(60, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        }).when(writerTarget()).findValidity(anyString(), anyLong());

        ExecutorService pool = Executors.newSingleThreadExecutor(r -> new Thread(r, acceptThread));
        try {
            doAnswer(inv -> {
                Future<String> accept = pool.submit(() -> pending().accept(c.requester()).status());
                assertThat(accepting.await(30, TimeUnit.SECONDS)).isTrue();
                assertThat(onOtherThread(() -> fx.pendingStatus(c.requester()))).as("本人の承認は ACCEPTING を確定済み")
                        .isEqualTo("ACCEPTING");
                acceptFuture = accept;
                return inv.callRealMethod();
            }).when(issuer()).activateStaged(anyLong(), anyLong());

            reissue(c).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUED"));
            assertThat(fx.activeTokens(c)).as("正常な承認との競合で招待を失効させない").isEqualTo(1);

            resume.countDown();
            assertThat(acceptFuture.get(30, TimeUnit.SECONDS)).isEqualTo("JOINED");
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("JOINED");
    }

    private volatile Future<String> acceptFuture;

    // ------------------------------------------------------------------
    // PR3 検分 N5: 通知の配送が失敗しても確定は維持
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("N5: 依頼者への通知の配送が失敗しても、再招待（200 REISSUED・有効なトークン 1 件・保留 PENDING・監査 1 行）と"
            + "見送り（200 REISSUE_DECLINED・監査 1 行）は確定したまま")
    void notificationFailureDoesNotRollBack(String type) throws Exception {
        ReissueFixtures.Case reissued = fx.reissueRequested(type);
        ReissueFixtures.Case declined = fx.requestIn(reissued.scope(), reissued.admin(), "MEMBER", utcNow().minusMinutes(5));
        doThrow(new IllegalStateException("試練: 通知の配送で停止")).when(notifier())
                .notifyAllLocalized(any(), anyString(), any(), any(), any(), any(), any(), any(), any());

        reissue(reissued).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUED"));
        decline(declined).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUE_DECLINED"));

        ReissueFixtures.await(() -> fx.audits(ReissueFixtures.scopeEvent(reissued, "INVITE_REISSUED"), reissued.requester()),
                1, "再招待の監査");
        ReissueFixtures.await(() -> fx.audits(ReissueFixtures.scopeEvent(declined, "INVITE_REISSUE_DECLINED"),
                declined.requester()), 1, "見送りの監査");
        ReissueFixtures.settle();
        assertThat(fx.activeTokens(reissued)).isEqualTo(1);
        assertThat(fx.pendingStatus(reissued.requester())).isEqualTo("PENDING");
        assertThat(fx.pendingStatus(declined.requester())).isEqualTo("REISSUE_DECLINED");
        assertThat(fx.notifications(reissued.requester(), "INVITE_REISSUED")).isZero();
        assertThat(fx.notifications(declined.requester(), "INVITE_REISSUE_DECLINED")).isZero();
    }
}
