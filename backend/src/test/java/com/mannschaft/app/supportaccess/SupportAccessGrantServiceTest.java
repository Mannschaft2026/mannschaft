package com.mannschaft.app.supportaccess;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.service.SupportAccessAuditRecorder;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantTransitionService;
import com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役Z-B 試練: 依頼〜失効の状態遷移 {@link SupportAccessGrantService}。
 *
 * <p>S7（所属する運営者は依頼・開始とも 010、対象が SYSTEM_ADMIN・自分・退会手続中は 005）、
 * S8（理由 10〜1000字、同じ組合せは同時1件、再依頼前にその場で失効）、S9（再認証必須・24h 固定・延長不可・
 * 本人はいつでも取消・保護者承認）、S2（閲覧開始）、S11（遷移ごとの監査）、S12（通知用イベント）、
 * S15（退会で即取消）を固定する。状態遷移は findById で返した Entity をその場で書き換える前提。</p>
 */
@DisplayName("戦役Z-B SupportAccessGrantService 単体テスト")
class SupportAccessGrantServiceTest {

    private static final Long OPERATOR_ID = 9001L;
    private static final Long TARGET_ID = 42L;
    private static final Long GUARDIAN_ID = 55L;
    private static final UUID GRANT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 26, 12, 0);
    private static final String REASON = "問い合わせ #123 の表示崩れを本人の画面で確認するため";
    private static final SupportAccessApproveRequest REAUTH = new SupportAccessApproveRequest("correct-password", null);

    private SupportAccessGrantRepository grantRepository;
    private SupportAccessUserDirectory userDirectory;
    private SupportAccessOperatorAffiliationPort affiliationPort;
    private SupportAccessReauthenticationVerifier reauth;
    private SupportAccessAuditRecorder auditRecorder;
    private SupportAccessLogRecorder logRecorder;
    private ApplicationEventPublisher eventPublisher;
    private SupportAccessGrantService service;

    @BeforeEach
    void setUp() {
        grantRepository = mock(SupportAccessGrantRepository.class);
        userDirectory = mock(SupportAccessUserDirectory.class);
        affiliationPort = mock(SupportAccessOperatorAffiliationPort.class);
        reauth = mock(SupportAccessReauthenticationVerifier.class);
        auditRecorder = mock(SupportAccessAuditRecorder.class);
        logRecorder = mock(SupportAccessLogRecorder.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        // 書込トランザクション（Repository のみ）と、TX の外で動く入口（port・監査）に分けて組む
        service = new SupportAccessGrantService(
                new SupportAccessGrantTransitionService(grantRepository, logRecorder, eventPublisher, clock),
                userDirectory, affiliationPort, reauth, auditRecorder);

        lenient().when(userDirectory.findUser(TARGET_ID))
                .thenReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", false, false)));
        lenient().when(userDirectory.findUser(OPERATOR_ID))
                .thenReturn(Optional.of(new SupportAccessUserSnapshot(OPERATOR_ID, "運営担当", "ACTIVE", false, true)));
        lenient().when(userDirectory.findRequiredGuardianUserId(anyLong())).thenReturn(Optional.empty());
        lenient().when(affiliationPort.isAffiliatedWithAnyTenant(anyLong())).thenReturn(false);
        lenient().when(grantRepository.findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(anyLong(), anyLong(), anyCollection()))
                .thenReturn(Optional.empty());
        lenient().when(grantRepository.save(any(SupportAccessGrantEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reauth.verify(eq(TARGET_ID), eq(REAUTH))).thenReturn(true);
        lenient().when(reauth.verify(eq(GUARDIAN_ID), eq(REAUTH))).thenReturn(true);
    }

    private static String codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).getErrorCode().getCode();
    }

    private static SupportAccessGrantEntity.SupportAccessGrantEntityBuilder<?, ?> pending() {
        return SupportAccessGrantEntity.builder()
                .id(GRANT_ID)
                .operatorUserId(OPERATOR_ID)
                .targetUserId(TARGET_ID)
                .status(SupportAccessStatus.PENDING)
                .requestReason(REASON)
                .requestedAt(NOW.minusHours(1))
                .requestExpiresAt(NOW.plusHours(71))
                .guardianApprovalRequired(false);
    }

    private static SupportAccessGrantEntity.SupportAccessGrantEntityBuilder<?, ?> approved() {
        return pending().status(SupportAccessStatus.APPROVED).decidedAt(NOW.minusMinutes(30))
                .expiresAt(NOW.minusMinutes(30).plusHours(24));
    }

    private SupportAccessGrantEntity givenGrant(SupportAccessGrantEntity grant) {
        given(grantRepository.findById(GRANT_ID)).willReturn(Optional.of(grant));
        return grant;
    }

    private List<SupportAccessGrantChangedEvent> publishedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .filter(SupportAccessGrantChangedEvent.class::isInstance)
                .map(SupportAccessGrantChangedEvent.class::cast)
                .toList();
    }

    @Nested
    @DisplayName("運営の依頼（request）")
    class RequestGrant {

        @Test
        @DisplayName("S8/S11/S12 依頼は PENDING・依頼から72時間で失効として保存し、監査 REQUESTED と通知イベントを出す")
        void S8_依頼はPENDINGで保存() {
            SupportAccessGrantSnapshot saved = service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, "FEEDBACK", 77L));

            assertThat(saved.status()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(saved.operatorUserId()).isEqualTo(OPERATOR_ID);
            assertThat(saved.targetUserId()).isEqualTo(TARGET_ID);
            assertThat(saved.requestReason()).isEqualTo(REASON);
            assertThat(saved.referenceType()).isEqualTo("FEEDBACK");
            assertThat(saved.referenceId()).isEqualTo(77L);
            assertThat(saved.requestedAt()).isEqualTo(NOW);
            assertThat(saved.requestExpiresAt()).isEqualTo(NOW.plusHours(72));
            assertThat(saved.expiresAt()).isNull();
            verify(grantRepository).save(any(SupportAccessGrantEntity.class));
            verify(auditRecorder).record(eq(SupportAccessAuditAction.REQUESTED), eq(OPERATOR_ID), eq(TARGET_ID), any());
            assertThat(publishedEvents()).extracting(SupportAccessGrantChangedEvent::action)
                    .contains(SupportAccessAuditAction.REQUESTED);
        }

        @ParameterizedTest(name = "S8 理由「{0}」は 008")
        @NullSource
        @ValueSource(strings = {"", "          ", "123456789"})
        @DisplayName("S8 理由が無い・空白のみ・10字未満は 400 SUPPORT_ACCESS_008")
        void S8_理由が短いと008(String reason) {
            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, reason, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_008"));
            verify(grantRepository, never()).save(any());
        }

        @Test
        @DisplayName("S8 理由は10字ちょうど・1000字ちょうどなら通り、1001字は 008")
        void S8_理由の境界値() {
            service.request(OPERATOR_ID, new CreateSupportAccessGrantRequest(TARGET_ID, "あ".repeat(10), null, null));
            service.request(OPERATOR_ID, new CreateSupportAccessGrantRequest(TARGET_ID, "あ".repeat(1000), null, null));
            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, "あ".repeat(1001), null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_008"));
        }

        @Test
        @DisplayName("S7 対象が SYSTEM_ADMIN なら 005")
        void S7_対象がSYSTEM_ADMINなら005() {
            given(userDirectory.findUser(TARGET_ID))
                    .willReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "別の運営", "ACTIVE", false, true)));

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
        }

        @Test
        @DisplayName("S7 対象が自分なら 005")
        void S7_対象が自分なら005() {
            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(OPERATOR_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
        }

        @Test
        @DisplayName("S7 対象が退会手続中なら 005")
        void S7_対象が退会手続中なら005() {
            given(userDirectory.findUser(TARGET_ID))
                    .willReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", true, false)));

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
        }

        @Test
        @DisplayName("S7 テナントに所属する運営アカウントは依頼できない（010）")
        void S7_所属する運営者は依頼不可() {
            given(affiliationPort.isAffiliatedWithAnyTenant(OPERATOR_ID)).willReturn(true);

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_010"));
            verify(grantRepository, never()).save(any());
        }

        @Test
        @DisplayName("S8 同じ運営者×本人で有効な依頼・許可が開いていれば 409 SUPPORT_ACCESS_006")
        void S8_同じ組合せは同時1件() {
            given(grantRepository.findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(eq(OPERATOR_ID), eq(TARGET_ID), anyCollection()))
                    .willReturn(Optional.of(pending().build()));

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_006"));
        }

        @Test
        @DisplayName("S8 開いている grant が期限切れ（バッチ前）なら、その場で EXPIRED にしてから新しい依頼を作る")
        void S8_再依頼前にその場で失効() {
            SupportAccessGrantEntity stale = approved().expiresAt(NOW.minusMinutes(1)).build();
            given(grantRepository.findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(eq(OPERATOR_ID), eq(TARGET_ID), anyCollection()))
                    .willReturn(Optional.of(stale));

            SupportAccessGrantSnapshot created = service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null));

            assertThat(stale.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(stale.getEndedReason()).isEqualTo(SupportAccessEndedReason.EXPIRED);
            assertThat(created.status()).isEqualTo(SupportAccessStatus.PENDING);
            verify(auditRecorder).record(eq(SupportAccessAuditAction.EXPIRED), eq(OPERATOR_ID), eq(TARGET_ID), any());
        }

        @Test
        @DisplayName("S8 開いている PENDING が依頼期限（72h）切れなら、その場で EXPIRED にして新しい依頼を作る")
        void S8_依頼期限切れのPENDINGもその場で失効() {
            SupportAccessGrantEntity stale = pending().requestExpiresAt(NOW.minusSeconds(1)).build();
            given(grantRepository.findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(eq(OPERATOR_ID), eq(TARGET_ID), anyCollection()))
                    .willReturn(Optional.of(stale));

            SupportAccessGrantSnapshot created = service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null));

            assertThat(stale.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(created.status()).isEqualTo(SupportAccessStatus.PENDING);
        }

        @Test
        @DisplayName("運営者ごとに1日20件を超える依頼は 429 SUPPORT_ACCESS_011")
        void 一日の依頼上限は011() {
            given(grantRepository.countByOperatorUserIdAndRequestedAtAfter(eq(OPERATOR_ID), any()))
                    .willReturn((long) SupportAccessGrantService.DAILY_REQUEST_LIMIT);

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_011"));
        }

        @Test
        @DisplayName("S9/S12 未成年・後見下の本人への依頼は保護者の承認も要る印を付け、保護者へも通知イベントを出す")
        void S9_未成年は保護者承認が必要() {
            given(userDirectory.findRequiredGuardianUserId(TARGET_ID)).willReturn(Optional.of(GUARDIAN_ID));

            SupportAccessGrantSnapshot saved = service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null));

            assertThat(saved.guardianApprovalRequired()).isTrue();
            assertThat(saved.guardianUserId()).isEqualTo(GUARDIAN_ID);
            assertThat(publishedEvents()).anySatisfy(e -> {
                assertThat(e.action()).isEqualTo(SupportAccessAuditAction.REQUESTED);
                assertThat(e.guardianUserId()).isEqualTo(GUARDIAN_ID);
            });
        }
    }

    @Nested
    @DisplayName("本人・保護者の許可（approve）")
    class Approve {

        @Test
        @DisplayName("S9/S11/S12 本人の再認証つき許可で APPROVED、期限は許可時刻＋24時間に固定し、監査と通知イベントを出す")
        void S9_許可は24時間固定() {
            SupportAccessGrantEntity grant = givenGrant(pending().build());

            service.approve(TARGET_ID, GRANT_ID, REAUTH);

            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.APPROVED);
            assertThat(grant.getDecidedAt()).isEqualTo(NOW);
            assertThat(grant.getExpiresAt()).isEqualTo(NOW.plusHours(24));
            verify(reauth).verify(TARGET_ID, REAUTH);
            verify(auditRecorder).record(eq(SupportAccessAuditAction.APPROVED), eq(OPERATOR_ID), eq(TARGET_ID), eq(GRANT_ID));
            assertThat(publishedEvents()).extracting(SupportAccessGrantChangedEvent::action)
                    .contains(SupportAccessAuditAction.APPROVED);
        }

        @Test
        @DisplayName("S9 再認証に失敗したら許可しない（状態は PENDING のまま・監査 APPROVED なし）")
        void S9_再認証失敗は許可しない() {
            SupportAccessGrantEntity grant = givenGrant(pending().build());
            SupportAccessApproveRequest wrong = new SupportAccessApproveRequest("wrong", null);
            given(reauth.verify(TARGET_ID, wrong)).willReturn(false);

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, wrong)).isInstanceOf(BusinessException.class);
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(grant.getExpiresAt()).isNull();
            verify(auditRecorder, never()).record(eq(SupportAccessAuditAction.APPROVED), any(), any(), any());
        }

        @Test
        @DisplayName("S9 再認証の材料が無い（パスワードもパスキーも無い）許可は拒否する")
        void S9_再認証なしは拒否() {
            SupportAccessGrantEntity grant = givenGrant(pending().build());

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, new SupportAccessApproveRequest(null, null)))
                    .isInstanceOf(BusinessException.class);
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.PENDING);
        }

        @Test
        @DisplayName("S9 既に APPROVED の grant を再び許可しても期限は延びない（007）")
        void S9_延長不可() {
            SupportAccessGrantEntity grant = givenGrant(approved().build());
            LocalDateTime before = grant.getExpiresAt();

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_007"));
            assertThat(grant.getExpiresAt()).isEqualTo(before);
        }

        @Test
        @DisplayName("S9 依頼期限（72h）を過ぎた PENDING は許可できない（007）")
        void S9_依頼期限切れは許可できない() {
            givenGrant(pending().requestExpiresAt(NOW.minusSeconds(1)).build());

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_007"));
        }

        @Test
        @DisplayName("S6/S9 本人でも保護者でもない利用者の許可は 001（存在を明かさない）")
        void S9_他人は許可できない() {
            givenGrant(pending().build());

            assertThatThrownBy(() -> service.approve(77L, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_001"));
        }

        @Test
        @DisplayName("S9 未成年・後見下: 本人の許可だけでは PENDING のまま、保護者の許可も揃って APPROVED（期限は最後の許可＋24h）")
        void S9_本人と保護者の両方で初めてAPPROVED() {
            SupportAccessGrantEntity grant = givenGrant(pending().guardianApprovalRequired(true).guardianUserId(GUARDIAN_ID).build());

            service.approve(TARGET_ID, GRANT_ID, REAUTH);
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(grant.getDecidedAt()).isEqualTo(NOW);
            assertThat(grant.getExpiresAt()).isNull();

            service.approve(GUARDIAN_ID, GRANT_ID, REAUTH);
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.APPROVED);
            assertThat(grant.getGuardianDecidedAt()).isEqualTo(NOW);
            assertThat(grant.getExpiresAt()).isEqualTo(NOW.plusHours(24));
            verify(reauth).verify(GUARDIAN_ID, REAUTH);
        }

        @Test
        @DisplayName("S9 未成年・後見下: 保護者が先に許可しても本人の許可が無ければ PENDING のまま")
        void S9_保護者だけではAPPROVEDにならない() {
            SupportAccessGrantEntity grant = givenGrant(pending().guardianApprovalRequired(true).guardianUserId(GUARDIAN_ID).build());

            service.approve(GUARDIAN_ID, GRANT_ID, REAUTH);

            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(grant.getGuardianDecidedAt()).isEqualTo(NOW);
            assertThat(grant.getExpiresAt()).isNull();
        }

        @Test
        @DisplayName("S9/S12 本人の拒否で DENIED、コメントを保存し、運営者へ通知イベントを出す")
        void S9_拒否はDENIED() {
            SupportAccessGrantEntity grant = givenGrant(pending().build());

            service.deny(TARGET_ID, GRANT_ID, "今は困ります");

            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.DENIED);
            assertThat(grant.getDenyComment()).isEqualTo("今は困ります");
            assertThat(grant.getDecidedAt()).isEqualTo(NOW);
            verify(auditRecorder).record(eq(SupportAccessAuditAction.DENIED), eq(OPERATOR_ID), eq(TARGET_ID), eq(GRANT_ID));
            assertThat(publishedEvents()).extracting(SupportAccessGrantChangedEvent::action)
                    .contains(SupportAccessAuditAction.DENIED);
        }
    }

    @Nested
    @DisplayName("取消・撤回・閲覧開始/終了・失効・退会")
    class Lifecycle {

        @Test
        @DisplayName("S9/S11/S12 本人はいつでも取消でき REVOKED(USER_REVOKED)、監査と運営者への通知イベント")
        void S9_本人はいつでも取消() {
            SupportAccessGrantEntity grant = givenGrant(approved().activeSessionStartedAt(NOW.minusMinutes(1)).build());

            service.revoke(TARGET_ID, GRANT_ID);

            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            assertThat(grant.getRevokedAt()).isEqualTo(NOW);
            assertThat(grant.getEndedReason()).isEqualTo(SupportAccessEndedReason.USER_REVOKED);
            verify(auditRecorder).record(eq(SupportAccessAuditAction.REVOKED), eq(OPERATOR_ID), eq(TARGET_ID), eq(GRANT_ID));
            assertThat(publishedEvents()).extracting(SupportAccessGrantChangedEvent::action)
                    .contains(SupportAccessAuditAction.REVOKED);
        }

        @Test
        @DisplayName("S6 他人の grant は取消できず 001")
        void S6_他人のgrantは取消不可() {
            givenGrant(approved().build());

            assertThatThrownBy(() -> service.revoke(77L, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_001"));
        }

        @Test
        @DisplayName("S11 運営の撤回で CANCELLED(OPERATOR_CANCELLED)、他の運営者の grant は 001")
        void S11_運営の撤回() {
            SupportAccessGrantEntity grant = givenGrant(pending().build());

            assertThatThrownBy(() -> service.cancel(8888L, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_001"));
            service.cancel(OPERATOR_ID, GRANT_ID);

            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.CANCELLED);
            assertThat(grant.getEndedReason()).isEqualTo(SupportAccessEndedReason.OPERATOR_CANCELLED);
            verify(auditRecorder).record(eq(SupportAccessAuditAction.CANCELLED), eq(OPERATOR_ID), eq(TARGET_ID), eq(GRANT_ID));
        }

        @Test
        @DisplayName("S2/S10/S11/S12 閲覧開始で開始時刻を記録し、閲覧記録 SESSION_START・監査・本人への通知イベントを出す")
        void S2_閲覧開始() {
            SupportAccessGrantEntity grant = givenGrant(approved().build());

            SupportAccessSessionResponse res = service.startSession(OPERATOR_ID, GRANT_ID);

            assertThat(res.grantId()).isEqualTo(GRANT_ID);
            assertThat(res.targetUserId()).isEqualTo(TARGET_ID);
            assertThat(res.targetDisplayName()).isEqualTo("本人");
            assertThat(res.expiresAt()).isEqualTo(grant.getExpiresAt());
            assertThat(grant.getActiveSessionStartedAt()).isEqualTo(NOW);
            assertThat(grant.getActiveSessionEndedAt()).isNull();
            assertThat(grant.getLastSessionStartedAt()).isEqualTo(NOW);
            verify(logRecorder).recordSessionStart(any());
            verify(auditRecorder).record(eq(SupportAccessAuditAction.SESSION_STARTED), eq(OPERATOR_ID), eq(TARGET_ID), eq(GRANT_ID));
            assertThat(publishedEvents()).extracting(SupportAccessGrantChangedEvent::action)
                    .contains(SupportAccessAuditAction.SESSION_STARTED);
        }

        @Test
        @DisplayName("S2 承認されていない・期限切れの grant は閲覧開始できない（002）")
        void S2_未承認は開始できない() {
            givenGrant(pending().build());
            assertThatThrownBy(() -> service.startSession(OPERATOR_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));

            givenGrant(approved().expiresAt(NOW.minusSeconds(1)).build());
            assertThatThrownBy(() -> service.startSession(OPERATOR_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
        }

        @Test
        @DisplayName("S7 テナントに所属する運営アカウントは閲覧開始できない（010）")
        void S7_所属する運営者は開始不可() {
            givenGrant(approved().build());
            given(affiliationPort.isAffiliatedWithAnyTenant(OPERATOR_ID)).willReturn(true);

            assertThatThrownBy(() -> service.startSession(OPERATOR_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_010"));
        }

        @Test
        @DisplayName("S6 他の運営者の grant は閲覧開始できず 001")
        void S6_他の運営者は開始不可() {
            givenGrant(approved().build());

            assertThatThrownBy(() -> service.startSession(8888L, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_001"));
        }

        @Test
        @DisplayName("S2/S11 閲覧終了で終了時刻を記録し、閲覧記録 SESSION_END と監査 SESSION_ENDED を残す")
        void S2_閲覧終了() {
            SupportAccessGrantEntity grant = givenGrant(approved().activeSessionStartedAt(NOW.minusMinutes(10)).build());

            service.endSession(OPERATOR_ID, GRANT_ID);

            assertThat(grant.getActiveSessionEndedAt()).isEqualTo(NOW);
            verify(logRecorder).recordSessionEnd(any());
            verify(auditRecorder).record(eq(SupportAccessAuditAction.SESSION_ENDED), eq(OPERATOR_ID), eq(TARGET_ID), eq(GRANT_ID));
        }

        @Test
        @DisplayName("S11 失効バッチ: 依頼72h切れの PENDING と許可24h切れの APPROVED を EXPIRED にし、それぞれ監査する")
        void S11_失効バッチ() {
            SupportAccessGrantEntity stalePending = pending().id(UUID.randomUUID()).requestExpiresAt(NOW.minusMinutes(1)).build();
            SupportAccessGrantEntity staleApproved = approved().id(UUID.randomUUID()).expiresAt(NOW.minusMinutes(1)).build();
            given(grantRepository.findByStatusAndRequestExpiresAtBefore(eq(SupportAccessStatus.PENDING), any()))
                    .willReturn(List.of(stalePending));
            given(grantRepository.findByStatusAndExpiresAtBefore(eq(SupportAccessStatus.APPROVED), any()))
                    .willReturn(List.of(staleApproved));

            int count = service.expireOverdue();

            assertThat(count).isEqualTo(2);
            assertThat(stalePending.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(staleApproved.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(staleApproved.getEndedReason()).isEqualTo(SupportAccessEndedReason.EXPIRED);
            verify(auditRecorder, org.mockito.Mockito.times(2))
                    .record(eq(SupportAccessAuditAction.EXPIRED), eq(OPERATOR_ID), eq(TARGET_ID), any());
        }

        @Test
        @DisplayName("S15 退会申請で本人の開いている grant（PENDING/APPROVED）を即座に REVOKED(WITHDRAWAL) にする")
        void S15_退会で即取消() {
            SupportAccessGrantEntity open1 = pending().id(UUID.randomUUID()).build();
            SupportAccessGrantEntity open2 = approved().id(UUID.randomUUID()).operatorUserId(8888L).build();
            given(grantRepository.findByTargetUserIdAndStatusIn(eq(TARGET_ID), anyCollection()))
                    .willReturn(List.of(open1, open2));

            int count = service.revokeAllForWithdrawal(TARGET_ID);

            assertThat(count).isEqualTo(2);
            assertThat(List.of(open1, open2)).allSatisfy(g -> {
                assertThat(g.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
                assertThat(g.getEndedReason()).isEqualTo(SupportAccessEndedReason.WITHDRAWAL);
                assertThat(g.getRevokedAt()).isEqualTo(NOW);
            });
        }
    }
}
