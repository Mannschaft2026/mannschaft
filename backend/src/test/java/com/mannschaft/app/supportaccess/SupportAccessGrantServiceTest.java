package com.mannschaft.app.supportaccess;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.entity.SupportAccessAuditOutboxEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.port.SupportAccessActingAsDetector;
import com.mannschaft.app.supportaccess.port.SupportAccessGuardianRequirement;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessReauthenticationVerifier;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository;
import com.mannschaft.app.supportaccess.entity.SupportAccessNotificationOutboxEntity;
import com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer;
import com.mannschaft.app.supportaccess.service.SupportAccessNotificationOutboxStore;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.supportaccess.service.SupportAccessAuditRecorder;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantTransitionService;
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
import java.time.Instant;
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
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final String REASON = "問い合わせ #123 の表示崩れを本人の画面で確認するため";
    private static final SupportAccessApproveRequest REAUTH = new SupportAccessApproveRequest("correct-password", null);

    private SupportAccessGrantRepository grantRepository;
    private SupportAccessUserDirectory userDirectory;
    private SupportAccessOperatorAffiliationPort affiliationPort;
    private SupportAccessReauthenticationVerifier reauth;
    private SupportAccessAuditRecorder auditRecorder;
    private SupportAccessLogRepository logRepository;
    private SupportAccessAuditOutboxRepository outboxRepository;
    private SupportAccessActingAsDetector actingAs;
    private ApplicationEventPublisher eventPublisher;
    private SupportAccessNotificationOutboxRepository notificationOutboxRepository;
    private EmailOutboxService emailOutboxService;
    private SupportAccessNoticeComposer composer;
    private SupportAccessNotificationOutboxStore notificationOutboxStore;
    private SupportAccessGrantService service;
    private com.mannschaft.app.supportaccess.service.SupportAccessLockWaitScope lockWaitScope;
    /** 許可の時点の保護者の要否の再評価が返す値（既定は不要）。 */
    private SupportAccessGuardianRequirement currentGuardianRequirement = SupportAccessGuardianRequirement.notRequired();

    @BeforeEach
    void setUp() {
        grantRepository = mock(SupportAccessGrantRepository.class);
        userDirectory = mock(SupportAccessUserDirectory.class);
        affiliationPort = mock(SupportAccessOperatorAffiliationPort.class);
        reauth = mock(SupportAccessReauthenticationVerifier.class);
        auditRecorder = mock(SupportAccessAuditRecorder.class);
        logRepository = mock(SupportAccessLogRepository.class);
        outboxRepository = mock(SupportAccessAuditOutboxRepository.class);
        actingAs = mock(SupportAccessActingAsDetector.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        notificationOutboxRepository = mock(SupportAccessNotificationOutboxRepository.class);
        emailOutboxService = mock(EmailOutboxService.class);
        composer = mock(SupportAccessNoticeComposer.class);
        notificationOutboxStore = mock(SupportAccessNotificationOutboxStore.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // ロック待ちの区間（JDBC 接続へ直接 SET する）は単体では本体をそのまま実行する。区間自体は SupportAccessLockWaitScopeTest
        lockWaitScope = mock(com.mannschaft.app.supportaccess.service.SupportAccessLockWaitScope.class);
        lenient().when(lockWaitScope.call(any())).thenAnswer(inv ->
                inv.<java.util.function.Supplier<?>>getArgument(0).get());
        // 書込トランザクション（Repository のみ）と、TX の外で動く入口（port・監査）に分けて組む
        service = new SupportAccessGrantService(
                new SupportAccessGrantTransitionService(grantRepository, logRepository, outboxRepository,
                        notificationOutboxRepository, emailOutboxService, eventPublisher, clock, lockWaitScope),
                userDirectory, affiliationPort, reauth, actingAs, auditRecorder, composer, notificationOutboxStore);
        lenient().when(notificationOutboxRepository.save(any(SupportAccessNotificationOutboxEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        lenient().when(userDirectory.findUser(TARGET_ID))
                .thenReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", false, false)));
        lenient().when(userDirectory.findUser(OPERATOR_ID))
                .thenReturn(Optional.of(new SupportAccessUserSnapshot(OPERATOR_ID, "運営担当", "ACTIVE", false, true)));
        lenient().when(userDirectory.resolveGuardianRequirement(anyLong()))
                .thenAnswer(inv -> currentGuardianRequirement);
        lenient().when(userDirectory.findUser(GUARDIAN_ID))
                .thenReturn(Optional.of(new SupportAccessUserSnapshot(GUARDIAN_ID, "保護者", "ACTIVE", false, false)));
        lenient().when(affiliationPort.isAffiliatedWithAnyTenant(anyLong())).thenReturn(false);
        lenient().when(grantRepository.findOpenIds(anyLong(), anyLong(), anyCollection()))
                .thenReturn(List.of());
        lenient().when(outboxRepository.save(any(SupportAccessAuditOutboxEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(logRepository.save(any(SupportAccessLogEntity.class))).thenAnswer(inv -> inv.getArgument(0));
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
                .requestedAt(NOW.minus(java.time.Duration.ofHours(1)))
                .requestExpiresAt(NOW.plus(java.time.Duration.ofHours(71)))
                .guardianApprovalRequired(false);
    }

    private static SupportAccessGrantEntity.SupportAccessGrantEntityBuilder<?, ?> approved() {
        return pending().status(SupportAccessStatus.APPROVED).decidedAt(NOW.minus(java.time.Duration.ofMinutes(30)))
                .expiresAt(NOW.minus(java.time.Duration.ofMinutes(30)).plus(java.time.Duration.ofHours(24)));
    }

    private SupportAccessGrantEntity givenGrant(SupportAccessGrantEntity grant) {
        lenient().when(grantRepository.findById(grant.getId())).thenReturn(Optional.of(grant));
        lenient().when(grantRepository.findByIdForUpdate(grant.getId())).thenReturn(Optional.of(grant));
        return grant;
    }

    /** 依頼の時点で同じ運営者×本人に開いている grant（id はロックなしで返し、主キーでロックして読む）。 */
    private void givenOpen(SupportAccessGrantEntity... grants) {
        given(grantRepository.findOpenIds(eq(OPERATOR_ID), eq(TARGET_ID), anyCollection()))
                .willReturn(java.util.Arrays.stream(grants).map(SupportAccessGrantEntity::getId).toList());
        for (SupportAccessGrantEntity g : grants) {
            givenGrant(g);
        }
    }

    /** 遷移と同じ TX で通知 outbox に書かれた種別。 */
    private List<String> notifiedKinds() {
        ArgumentCaptor<SupportAccessNotificationOutboxEntity> captor =
                ArgumentCaptor.forClass(SupportAccessNotificationOutboxEntity.class);
        verify(notificationOutboxRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(SupportAccessNotificationOutboxEntity::getNotificationKind).toList();
    }

    /** 遷移と同じ TX で監査 outbox に書かれた種別（audit_logs への反映はコミット後の auditRecorder）。 */
    private List<String> auditedEvents() {
        ArgumentCaptor<SupportAccessAuditOutboxEntity> captor = ArgumentCaptor.forClass(SupportAccessAuditOutboxEntity.class);
        verify(outboxRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(SupportAccessAuditOutboxEntity::getEventType).toList();
    }

    private List<String> auditedEventsOrEmpty() {
        ArgumentCaptor<SupportAccessAuditOutboxEntity> captor = ArgumentCaptor.forClass(SupportAccessAuditOutboxEntity.class);
        verify(outboxRepository, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues().stream().map(SupportAccessAuditOutboxEntity::getEventType).toList();
    }

    private List<SupportAccessEventKind> sessionLogKinds() {
        ArgumentCaptor<SupportAccessLogEntity> captor = ArgumentCaptor.forClass(SupportAccessLogEntity.class);
        verify(logRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(SupportAccessLogEntity::getEventKind).toList();
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
            assertThat(saved.requestExpiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofHours(72)));
            assertThat(saved.expiresAt()).isNull();
            verify(grantRepository).save(any(SupportAccessGrantEntity.class));
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_REQUESTED");
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
            givenOpen(pending().build());

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_006"));
        }

        @Test
        @DisplayName("S8 開いている grant が期限切れ（バッチ前）なら、その場で EXPIRED にしてから新しい依頼を作る")
        void S8_再依頼前にその場で失効() {
            SupportAccessGrantEntity stale = approved().expiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            givenOpen(stale);

            SupportAccessGrantSnapshot created = service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null));

            assertThat(stale.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(stale.getEndedReason()).isEqualTo(SupportAccessEndedReason.EXPIRED);
            assertThat(created.status()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_EXPIRED");
        }

        @Test
        @DisplayName("S8 開いている PENDING が依頼期限（72h）切れなら、その場で EXPIRED にして新しい依頼を作る")
        void S8_依頼期限切れのPENDINGもその場で失効() {
            SupportAccessGrantEntity stale = pending().requestExpiresAt(NOW.minus(java.time.Duration.ofSeconds(1))).build();
            givenOpen(stale);

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
            given(userDirectory.resolveGuardianRequirement(TARGET_ID))
                    .willReturn(SupportAccessGuardianRequirement.requiredBy(List.of(GUARDIAN_ID)));

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
            assertThat(grant.getExpiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofHours(24)));
            verify(reauth).verify(TARGET_ID, REAUTH);
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_APPROVED");
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
            assertThat(auditedEventsOrEmpty()).doesNotContain("SUPPORT_ACCESS_APPROVED");
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
            Instant before = grant.getExpiresAt();

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_007"));
            assertThat(grant.getExpiresAt()).isEqualTo(before);
        }

        @Test
        @DisplayName("S9 依頼期限（72h）を過ぎた PENDING は許可できない（007）")
        void S9_依頼期限切れは許可できない() {
            givenGrant(pending().requestExpiresAt(NOW.minus(java.time.Duration.ofSeconds(1))).build());

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
            currentGuardianRequirement = SupportAccessGuardianRequirement.requiredBy(List.of(GUARDIAN_ID));
            SupportAccessGrantEntity grant = givenGrant(pending().guardianApprovalRequired(true).guardianUserId(GUARDIAN_ID).build());

            service.approve(TARGET_ID, GRANT_ID, REAUTH);
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(grant.getDecidedAt()).isEqualTo(NOW);
            assertThat(grant.getExpiresAt()).isNull();

            service.approve(GUARDIAN_ID, GRANT_ID, REAUTH);
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.APPROVED);
            assertThat(grant.getGuardianDecidedAt()).isEqualTo(NOW);
            assertThat(grant.getExpiresAt()).isEqualTo(NOW.plus(java.time.Duration.ofHours(24)));
            verify(reauth).verify(GUARDIAN_ID, REAUTH);
        }

        @Test
        @DisplayName("S9 未成年・後見下: 保護者が先に許可しても本人の許可が無ければ PENDING のまま")
        void S9_保護者だけではAPPROVEDにならない() {
            currentGuardianRequirement = SupportAccessGuardianRequirement.requiredBy(List.of(GUARDIAN_ID));
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
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_DENIED");
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
            SupportAccessGrantEntity grant = givenGrant(approved().activeSessionStartedAt(NOW.minus(java.time.Duration.ofMinutes(1))).build());

            service.revoke(TARGET_ID, GRANT_ID);

            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            assertThat(grant.getRevokedAt()).isEqualTo(NOW);
            assertThat(grant.getEndedReason()).isEqualTo(SupportAccessEndedReason.USER_REVOKED);
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_REVOKED");
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
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_CANCELLED");
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
            assertThat(sessionLogKinds()).contains(SupportAccessEventKind.SESSION_START);
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_SESSION_STARTED");
            assertThat(publishedEvents()).extracting(SupportAccessGrantChangedEvent::action)
                    .contains(SupportAccessAuditAction.SESSION_STARTED);
        }

        @Test
        @DisplayName("S2 承認されていない・期限切れの grant は閲覧開始できない（002）")
        void S2_未承認は開始できない() {
            givenGrant(pending().build());
            assertThatThrownBy(() -> service.startSession(OPERATOR_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));

            givenGrant(approved().expiresAt(NOW.minus(java.time.Duration.ofSeconds(1))).build());
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
            SupportAccessGrantEntity grant = givenGrant(approved().activeSessionStartedAt(NOW.minus(java.time.Duration.ofMinutes(10))).build());

            service.endSession(OPERATOR_ID, GRANT_ID);

            assertThat(grant.getActiveSessionEndedAt()).isEqualTo(NOW);
            assertThat(sessionLogKinds()).contains(SupportAccessEventKind.SESSION_END);
            assertThat(auditedEvents()).contains("SUPPORT_ACCESS_SESSION_ENDED");
        }

        @Test
        @DisplayName("S11 失効バッチ: 依頼72h切れの PENDING と許可24h切れの APPROVED を EXPIRED にし、それぞれ監査する")
        void S11_失効バッチ() {
            SupportAccessGrantEntity stalePending = pending().id(UUID.randomUUID()).requestExpiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            SupportAccessGrantEntity staleApproved = approved().id(UUID.randomUUID()).expiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            given(grantRepository.findOverdueIds(NOW)).willReturn(List.of(stalePending.getId(), staleApproved.getId()));
            givenGrant(stalePending);
            givenGrant(staleApproved);

            int count = service.expireOverdue();

            assertThat(count).isEqualTo(2);
            assertThat(stalePending.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(staleApproved.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(staleApproved.getEndedReason()).isEqualTo(SupportAccessEndedReason.EXPIRED);
            assertThat(auditedEvents()).filteredOn("SUPPORT_ACCESS_EXPIRED"::equals).hasSize(2);
            verify(auditRecorder).relayForGrant(stalePending.getId());
            verify(auditRecorder).relayForGrant(staleApproved.getId());
        }

        @Test
        @DisplayName("S15 退会申請で本人の開いている grant（PENDING/APPROVED）を即座に REVOKED(WITHDRAWAL) にする")
        void S15_退会で即取消() {
            SupportAccessGrantEntity open1 = pending().id(UUID.randomUUID()).build();
            SupportAccessGrantEntity open2 = approved().id(UUID.randomUUID()).operatorUserId(8888L).build();
            given(grantRepository.findOpenIdsByTargetUserId(eq(TARGET_ID), anyCollection()))
                    .willReturn(List.of(open1.getId(), open2.getId()));
            givenGrant(open1);
            givenGrant(open2);

            int count = service.revokeAllForWithdrawal(TARGET_ID);

            assertThat(count).isEqualTo(2);
            assertThat(List.of(open1, open2)).allSatisfy(g -> {
                assertThat(g.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
                assertThat(g.getEndedReason()).isEqualTo(SupportAccessEndedReason.WITHDRAWAL);
                assertThat(g.getRevokedAt()).isEqualTo(NOW);
            });
        }

        @Test
        @DisplayName("S15 退会で取り消した grant は退会を撤回しても復活しない（REVOKED は終端で、許可・再開はできない）")
        void S15_退会撤回でPENDINGは復活しない() {
            SupportAccessGrantEntity open = givenGrant(pending().build());
            given(grantRepository.findOpenIdsByTargetUserId(eq(TARGET_ID), anyCollection()))
                    .willReturn(List.of(open.getId()));
            service.revokeAllForWithdrawal(TARGET_ID);

            assertThat(open.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_007"));
            assertThat(open.isOpen()).isFalse();
        }

        @Test
        @DisplayName("S15 退会リスナーの取りこぼしは失効バッチの照合で取り消す（退会手続中・見つからない本人の開いている grant）")
        void S15_退会の取りこぼしを照合で取消() {
            SupportAccessGrantEntity open = pending().id(UUID.randomUUID()).build();
            given(grantRepository.findByStatusIn(anyCollection())).willReturn(List.of(open));
            given(userDirectory.findUser(TARGET_ID))
                    .willReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", true, false)));
            given(grantRepository.findOpenIdsByTargetUserId(eq(TARGET_ID), anyCollection()))
                    .willReturn(List.of(open.getId()));
            givenGrant(open);

            int revoked = service.reconcileWithdrawnTargets();

            assertThat(revoked).isEqualTo(1);
            assertThat(open.getEndedReason()).isEqualTo(SupportAccessEndedReason.WITHDRAWAL);
        }
    }

    @Nested
    @DisplayName("検分差し戻し対応: 排他・監査 outbox・後見切替・未成年の fail-closed")
    class ReviewFixes {

        @Test
        @DisplayName("遷移は排他ロック付きの読み取り（findByIdForUpdate）で行い、ロックなしの findById で書かない")
        void 遷移はロック付きで読む() {
            givenGrant(approved().build());

            service.revoke(TARGET_ID, GRANT_ID);

            verify(grantRepository).findByIdForUpdate(GRANT_ID);
            verify(grantRepository, never()).findById(GRANT_ID);
        }

        @Test
        @DisplayName("依頼は運営者の依頼ロックを最初に取り、その後に開いている grant を排他ロックで読む（ロック順の固定）")
        void 依頼は運営者ロックが先() {
            service.request(OPERATOR_ID, new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null));

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(grantRepository);
            order.verify(grantRepository).lockOperatorForRequest(OPERATOR_ID);
            order.verify(grantRepository).findOpenIds(eq(OPERATOR_ID), eq(TARGET_ID), anyCollection());
            order.verify(grantRepository).countByOperatorUserIdAndRequestedAtAfter(eq(OPERATOR_ID), any());
        }

        @Test
        @DisplayName("閲覧開始の直前に取消が確定していたら（ロック後の再検証で）002 にし、開始で取消を上書きしない")
        void 開始と取消の競合は取消が勝つ() {
            SupportAccessGrantEntity unlocked = approved().build();
            SupportAccessGrantEntity lockedLatest = approved().build();
            lockedLatest.revoke(NOW, SupportAccessEndedReason.USER_REVOKED);
            given(grantRepository.findById(GRANT_ID)).willReturn(Optional.of(unlocked));
            given(grantRepository.findByIdForUpdate(GRANT_ID)).willReturn(Optional.of(lockedLatest));

            assertThatThrownBy(() -> service.startSession(OPERATOR_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
            assertThat(lockedLatest.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            assertThat(lockedLatest.getActiveSessionStartedAt()).isNull();
        }

        @Test
        @DisplayName("監査は遷移と同じ TX で outbox に書き、コミット後に auditRecorder が反映する（遷移の確定と監査の欠落が起きない）")
        void 監査はoutboxとコミット後の反映() {
            givenGrant(approved().build());

            service.cancel(OPERATOR_ID, GRANT_ID);

            ArgumentCaptor<SupportAccessAuditOutboxEntity> captor = ArgumentCaptor.forClass(SupportAccessAuditOutboxEntity.class);
            verify(outboxRepository).save(captor.capture());
            SupportAccessAuditOutboxEntity row = captor.getValue();
            assertThat(row.getEventType()).isEqualTo("SUPPORT_ACCESS_CANCELLED");
            assertThat(row.getGrantId()).isEqualTo(GRANT_ID);
            assertThat(row.getOperatorUserId()).isEqualTo(OPERATOR_ID);
            assertThat(row.getTargetUserId()).isEqualTo(TARGET_ID);
            assertThat(row.getPublishedAt()).isNull();
            assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
            verify(auditRecorder).relayForGrant(GRANT_ID);
        }

        @Test
        @DisplayName("閲覧開始の応答は BE の時刻基準の残り秒数を返す")
        void 閲覧開始は残り秒数を返す() {
            givenGrant(approved().build());

            SupportAccessSessionResponse res = service.startSession(OPERATOR_ID, GRANT_ID);

            assertThat(res.remainingSeconds())
                    .isEqualTo(java.time.Duration.between(NOW, res.expiresAt()).getSeconds());
        }

        @Test
        @DisplayName("後見切替・代理入力の最中は、保護者が子として許可しても 403 SUPPORT_ACCESS_015（状態は変わらない）")
        void 後見切替中の許可は015() {
            SupportAccessGrantEntity grant = givenGrant(pending().guardianApprovalRequired(true).guardianUserId(GUARDIAN_ID).build());
            given(actingAs.isActingAsAnotherUser()).willReturn(true);

            assertThatThrownBy(() -> service.approve(GUARDIAN_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_015"));
            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_015"));
            assertThatThrownBy(() -> service.deny(TARGET_ID, GRANT_ID, null))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_015"));
            assertThatThrownBy(() -> service.revoke(TARGET_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_015"));
            assertThat(grant.getStatus()).isEqualTo(SupportAccessStatus.PENDING);
            assertThat(grant.getGuardianDecidedAt()).isNull();
            verify(reauth, never()).verify(any(), any());
        }

        @Test
        @DisplayName("未成年判定の fail-closed: 保護者が要るのに承認できる保護者がいない（リンク無し・停止中）なら依頼は 005")
        void 保護者が解決できなければ005() {
            given(userDirectory.resolveGuardianRequirement(TARGET_ID))
                    .willReturn(SupportAccessGuardianRequirement.requiredBy(List.of()));

            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
            verify(grantRepository, never()).save(any());
        }

        @Test
        @DisplayName("保護者が SYSTEM_ADMIN、または依頼した運営者本人なら依頼は 005（利益相反）")
        void 保護者が運営なら005() {
            given(userDirectory.resolveGuardianRequirement(TARGET_ID))
                    .willReturn(SupportAccessGuardianRequirement.requiredBy(List.of(OPERATOR_ID)));
            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));

            given(userDirectory.resolveGuardianRequirement(TARGET_ID))
                    .willReturn(SupportAccessGuardianRequirement.requiredBy(List.of(GUARDIAN_ID)));
            given(userDirectory.findUser(GUARDIAN_ID))
                    .willReturn(Optional.of(new SupportAccessUserSnapshot(GUARDIAN_ID, "別の運営", "ACTIVE", false, true)));
            assertThatThrownBy(() -> service.request(OPERATOR_ID,
                    new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null)))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
            verify(grantRepository, never()).save(any());
        }

        @Test
        @DisplayName("保護者は許可の時点で再評価する: 依頼後に保護者が停止・リンク解除されたら許可は 005")
        void 許可時に保護者を再評価() {
            SupportAccessGrantEntity grant = givenGrant(pending().guardianApprovalRequired(true).guardianUserId(GUARDIAN_ID).build());
            given(userDirectory.resolveGuardianRequirement(TARGET_ID))
                    .willReturn(SupportAccessGuardianRequirement.requiredBy(List.of()));

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
            assertThat(grant.getDecidedAt()).isNull();
        }

        @Test
        @DisplayName("依頼の時は保護者が不要でも、許可の時点で要るようになっていたら 005（依頼のやり直し）")
        void 許可時に保護者が要るようになったら005() {
            givenGrant(pending().build());
            given(userDirectory.resolveGuardianRequirement(TARGET_ID))
                    .willReturn(SupportAccessGuardianRequirement.requiredBy(List.of(GUARDIAN_ID)));

            assertThatThrownBy(() -> service.approve(TARGET_ID, GRANT_ID, REAUTH))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_005"));
        }
    }

    @Nested
    @DisplayName("第2回検分対応: ロック順の統一・ロック競合の再試行・安全通知の outbox")
    class SecondReviewFixes {

        private SupportAccessNoticeComposer.PreparedEmail email(Long recipient,
                                                                 com.mannschaft.app.supportaccess.SupportAccessNotificationKind kind) {
            return new SupportAccessNoticeComposer.PreparedEmail(recipient, kind, "ja", "target@example.com",
                    "【Mannschaft】件名", "<p>本文</p>");
        }

        @Test
        @DisplayName("失効バッチは id をロックなしで集め、主キーでロックしてから期限を再検証する（読んだ後に取り消された行は失効させない）")
        void 失効バッチは主キーでロックして再検証() {
            SupportAccessGrantEntity revokedMeanwhile = approved().id(UUID.randomUUID())
                    .expiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            revokedMeanwhile.revoke(NOW, SupportAccessEndedReason.USER_REVOKED);
            given(grantRepository.findOverdueIds(NOW)).willReturn(List.of(revokedMeanwhile.getId()));
            givenGrant(revokedMeanwhile);

            int count = service.expireOverdue();

            assertThat(count).isZero();
            assertThat(revokedMeanwhile.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            verify(grantRepository).findByIdForUpdate(revokedMeanwhile.getId());
            verify(lockWaitScope, org.mockito.Mockito.atLeastOnce()).call(any());
        }

        @Test
        @DisplayName("失効バッチ: ロックが取れない行は1回だけ再試行し、それでも取れなければ次の回へ回して他の行は失効させる")
        void 失効バッチのロック競合は次の回へ() {
            SupportAccessGrantEntity locked = pending().id(UUID.randomUUID())
                    .requestExpiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            SupportAccessGrantEntity free = pending().id(UUID.randomUUID())
                    .requestExpiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            given(grantRepository.findOverdueIds(NOW)).willReturn(List.of(locked.getId(), free.getId()));
            given(grantRepository.findByIdForUpdate(locked.getId()))
                    .willThrow(new org.springframework.dao.CannotAcquireLockException("Lock wait timeout exceeded"));
            givenGrant(free);

            int count = service.expireOverdue();

            assertThat(count).isEqualTo(1);
            assertThat(free.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            verify(grantRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(locked.getId());
        }

        @Test
        @DisplayName("本人の取消でデッドロックに選ばれても1回だけ再試行して成功する")
        void デッドロックは1回再試行() {
            SupportAccessGrantEntity grant = approved().build();
            given(grantRepository.findByIdForUpdate(GRANT_ID))
                    .willThrow(new org.springframework.dao.CannotAcquireLockException("Deadlock found",
                            new java.sql.SQLException("Deadlock found when trying to get lock", "40001", 1213)))
                    .willReturn(Optional.of(grant));

            SupportAccessGrantSnapshot result = service.revoke(TARGET_ID, GRANT_ID);

            assertThat(result.status()).isEqualTo(SupportAccessStatus.REVOKED);
        }

        @Test
        @DisplayName("再試行してもロックが取れなければ利用者には 409 SUPPORT_ACCESS_007（500 にしない）")
        void ロック競合が解けなければ409() {
            given(grantRepository.findByIdForUpdate(GRANT_ID))
                    .willThrow(new org.springframework.dao.PessimisticLockingFailureException("Lock wait timeout exceeded"));

            assertThatThrownBy(() -> service.revoke(TARGET_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_007"));
            verify(grantRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(GRANT_ID);
        }

        @Test
        @DisplayName("退会の取消は1件ずつ主キーでロックし、ロックが取れない行が残れば例外にして再試行・照合へ回す（他の行は取り消す）")
        void 退会の取消はロックが取れない行を残して例外() {
            SupportAccessGrantEntity locked = pending().id(UUID.randomUUID()).build();
            SupportAccessGrantEntity free = approved().id(UUID.randomUUID()).build();
            given(grantRepository.findOpenIdsByTargetUserId(eq(TARGET_ID), anyCollection()))
                    .willReturn(List.of(locked.getId(), free.getId()));
            given(grantRepository.findByIdForUpdate(locked.getId()))
                    .willThrow(new org.springframework.dao.CannotAcquireLockException("Lock wait timeout exceeded"));
            givenGrant(free);

            assertThatThrownBy(() -> service.revokeAllForWithdrawal(TARGET_ID))
                    .isInstanceOf(org.springframework.dao.CannotAcquireLockException.class);
            assertThat(free.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            assertThat(free.getEndedReason()).isEqualTo(SupportAccessEndedReason.WITHDRAWAL);
        }

        @Test
        @DisplayName("ロック競合の判定は Spring・JPA・Hibernate・JDBC（1205/1213/40001）の原因の連鎖で行い、業務例外は含めない")
        void ロック競合の判定() {
            assertThat(SupportAccessGrantService.isLockConflict(
                    new RuntimeException(new java.sql.SQLException("timeout", "HY000", 1205)))).isTrue();
            assertThat(SupportAccessGrantService.isLockConflict(
                    new jakarta.persistence.PessimisticLockException("x"))).isTrue();
            assertThat(SupportAccessGrantService.isLockConflict(
                    new BusinessException(SupportAccessErrorCode.INVALID_TRANSITION))).isFalse();
            assertThat(SupportAccessGrantService.isLockConflict(new IllegalStateException("x"))).isFalse();
        }

        @Test
        @DisplayName("閲覧開始: 本人へのアプリ内通知の outbox 行とメール outbox の行を遷移と同じ TX で書く（冪等キーは grantId＋種別＋宛先＋開始時刻）")
        void 閲覧開始の通知は遷移と同じTXでoutboxへ() {
            givenGrant(approved().build());
            given(composer.prepareEmail(eq(TARGET_ID),
                    eq(com.mannschaft.app.supportaccess.SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET),
                    eq(OPERATOR_ID), eq(TARGET_ID), eq(REASON)))
                    .willReturn(Optional.of(email(TARGET_ID,
                            com.mannschaft.app.supportaccess.SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET)));

            service.startSession(OPERATOR_ID, GRANT_ID);

            assertThat(notifiedKinds()).containsExactly("SESSION_STARTED_TO_TARGET");
            ArgumentCaptor<EmailOutboxRequest> mail = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(mail.capture());
            assertThat(mail.getValue().sourceEventId())
                    .isEqualTo("support-access:SESSION_STARTED_TO_TARGET:" + GRANT_ID + ":" + TARGET_ID + ":" + NOW.toEpochMilli());
            assertThat(mail.getValue().idempotencyKey()).as("メール outbox が source_event_id から決定的に作る").isNull();
            assertThat(mail.getValue().toAddress()).isEqualTo("target@example.com");
        }

        @Test
        @DisplayName("閲覧開始が巻き戻る（ロック後の再検証で 002）なら、通知 outbox もメールも書かない")
        void 遷移が巻き戻れば通知もメールも出ない() {
            SupportAccessGrantEntity unlocked = approved().build();
            SupportAccessGrantEntity lockedLatest = approved().build();
            lockedLatest.revoke(NOW, SupportAccessEndedReason.USER_REVOKED);
            given(grantRepository.findById(GRANT_ID)).willReturn(Optional.of(unlocked));
            given(grantRepository.findByIdForUpdate(GRANT_ID)).willReturn(Optional.of(lockedLatest));
            given(composer.prepareEmail(any(), any(), any(), any(), any()))
                    .willReturn(Optional.of(email(TARGET_ID,
                            com.mannschaft.app.supportaccess.SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET)));

            assertThatThrownBy(() -> service.startSession(OPERATOR_ID, GRANT_ID))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo("SUPPORT_ACCESS_002"));
            verify(notificationOutboxRepository, never()).save(any());
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("同じ遷移の通知 outbox 行が既にあれば二重に書かない（冪等キーで確かめ、UNIQUE 違反で遷移を巻き戻さない）")
        void 通知outboxは冪等() {
            givenGrant(approved().build());
            given(notificationOutboxRepository.existsByIdempotencyKey(any())).willReturn(true);

            service.revoke(TARGET_ID, GRANT_ID);

            verify(notificationOutboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("同じ遷移のメールは同じ source_event_id（＝同じ冪等キー）になり、メール outbox の UNIQUE で再送しても1通")
        void メールの冪等キーは決定的() {
            var prepared = email(TARGET_ID,
                    com.mannschaft.app.supportaccess.SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET);
            EmailOutboxRequest first = prepared.toRequest(GRANT_ID, NOW);
            EmailOutboxRequest again = prepared.toRequest(GRANT_ID, NOW);
            EmailOutboxRequest nextSession = prepared.toRequest(GRANT_ID, NOW.plusSeconds(60));

            assertThat(again.sourceEventId()).isEqualTo(first.sourceEventId());
            assertThat(nextSession.sourceEventId()).as("次の閲覧開始は別のメール").isNotEqualTo(first.sourceEventId());
            com.mannschaft.app.mail.outbox.IdempotencyKeyGenerator gen = new com.mannschaft.app.mail.outbox.IdempotencyKeyGenerator();
            assertThat(gen.generate(TARGET_ID, first.templateKind(), first.sourceEventId()))
                    .isEqualTo(gen.generate(TARGET_ID, again.templateKind(), again.sourceEventId()));
        }

        @Test
        @DisplayName("依頼: 本人・保護者のメールを TX の前に組み、通知 outbox には本人・保護者の2行を書く")
        void 依頼の通知は本人と保護者() {
            currentGuardianRequirement = SupportAccessGuardianRequirement.requiredBy(List.of(GUARDIAN_ID));

            service.request(OPERATOR_ID, new CreateSupportAccessGrantRequest(TARGET_ID, REASON, null, null));

            assertThat(notifiedKinds()).containsExactly("REQUESTED_TO_TARGET", "REQUESTED_TO_GUARDIAN");
            verify(composer).prepareEmail(TARGET_ID,
                    com.mannschaft.app.supportaccess.SupportAccessNotificationKind.REQUESTED_TO_TARGET,
                    OPERATOR_ID, TARGET_ID, REASON);
            verify(composer).prepareEmail(GUARDIAN_ID,
                    com.mannschaft.app.supportaccess.SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN,
                    OPERATOR_ID, TARGET_ID, REASON);
        }
    }

    @Nested
    @DisplayName("第3回検分対応: 失効バッチ・退会の照合は1件の失敗で残りを打ち切らない")
    class ThirdReviewFixes {

        @Test
        @DisplayName("失効バッチ: ロック競合以外の失敗（DB の一時障害）の行があっても他の行は失効させ、失敗した行は開いたまま次の回に回る")
        void 失効バッチは1件の失敗で打ち切らない() {
            SupportAccessGrantEntity broken = pending().id(UUID.randomUUID())
                    .requestExpiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            SupportAccessGrantEntity free = pending().id(UUID.randomUUID())
                    .requestExpiresAt(NOW.minus(java.time.Duration.ofMinutes(1))).build();
            given(grantRepository.findOverdueIds(NOW)).willReturn(List.of(broken.getId(), free.getId()));
            given(grantRepository.findByIdForUpdate(broken.getId()))
                    .willThrow(new org.springframework.dao.DataAccessResourceFailureException("Communications link failure"))
                    .willReturn(Optional.of(broken));
            givenGrant(free);

            int count = service.expireOverdue();

            assertThat(count).isEqualTo(1);
            assertThat(free.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
            assertThat(broken.getStatus()).as("失敗した行は開いたまま（次の回が拾う）").isEqualTo(SupportAccessStatus.PENDING);

            // 次の回: 同じ行がまた期限切れとして拾われ、今度は失効する（握りつぶしではなく再処理される）
            given(grantRepository.findOverdueIds(NOW)).willReturn(List.of(broken.getId()));
            assertThat(service.expireOverdue()).isEqualTo(1);
            assertThat(broken.getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
        }

        @Test
        @DisplayName("退会の照合: 1人の照会・取消の失敗で残りの本人の照合を打ち切らない（失敗した本人は次の回で再照合）")
        void 退会の照合は1人の失敗で打ち切らない() {
            Long otherTarget = 43L;
            SupportAccessGrantEntity brokenTargetGrant = pending().id(UUID.randomUUID()).build();
            SupportAccessGrantEntity otherGrant = pending().id(UUID.randomUUID()).targetUserId(otherTarget).build();
            given(grantRepository.findByStatusIn(anyCollection())).willReturn(List.of(brokenTargetGrant, otherGrant));
            given(userDirectory.findUser(TARGET_ID))
                    .willThrow(new org.springframework.dao.DataAccessResourceFailureException("Communications link failure"))
                    .willReturn(Optional.of(new SupportAccessUserSnapshot(TARGET_ID, "本人", "ACTIVE", true, false)));
            given(userDirectory.findUser(otherTarget))
                    .willReturn(Optional.of(new SupportAccessUserSnapshot(otherTarget, "別の本人", "ACTIVE", true, false)));
            given(grantRepository.findOpenIdsByTargetUserId(eq(TARGET_ID), anyCollection()))
                    .willReturn(List.of(brokenTargetGrant.getId()));
            given(grantRepository.findOpenIdsByTargetUserId(eq(otherTarget), anyCollection()))
                    .willReturn(List.of(otherGrant.getId()));
            givenGrant(brokenTargetGrant);
            givenGrant(otherGrant);

            int revoked = service.reconcileWithdrawnTargets();

            assertThat(revoked).isEqualTo(1);
            assertThat(otherGrant.getEndedReason()).isEqualTo(SupportAccessEndedReason.WITHDRAWAL);
            assertThat(brokenTargetGrant.isOpen()).as("失敗した本人の grant は開いたまま").isTrue();

            // 次の回: 開いたままの grant の本人が再び照合され、取り消される
            given(grantRepository.findByStatusIn(anyCollection())).willReturn(List.of(brokenTargetGrant));
            assertThat(service.reconcileWithdrawnTargets()).isEqualTo(1);
            assertThat(brokenTargetGrant.getEndedReason()).isEqualTo(SupportAccessEndedReason.WITHDRAWAL);
        }
    }
}
