package com.mannschaft.app.role;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.auth.service.PendingInviteStore;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
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
    // PR3 検分 P2-2: 有効化の直前に保留が変わっていたら、有効にした招待を失効させる
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("P2-2: auth の確定の後、有効化までに保留が当該ハッシュの PENDING でなくなった（本人の辞退など）なら 409 ROLE_017。"
            + "招待は有効なまま残さず、INVITE_REISSUED の通知・監査も出さない")
    void pendingChangedBeforeActivationRevokesToken(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        doAnswer(inv -> {
            jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET status = 'DECLINED' WHERE user_id = ?",
                    c.requester());
            return inv.callRealMethod();
        }).when(issuer()).activateStaged(anyLong(), anyLong());

        reissue(c).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_017"));

        assertThat(fx.pendingStatus(c.requester())).isEqualTo("DECLINED");
        assertThat(fx.activeTokens(c)).isZero();
        assertThat(dmDuplicateCandidates(c)).isZero();
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isZero();
        assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUED"), c.requester())).isZero();
    }

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
