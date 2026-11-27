package com.mannschaft.app.supportaccess;

import com.mannschaft.app.auth.event.WithdrawalRequestedEvent;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.context.SupportAccessContextTaskDecorator;
import com.mannschaft.app.supportaccess.dto.SupportAccessLogView;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.event.SupportAccessGrantChangedEvent;
import com.mannschaft.app.supportaccess.listener.SupportAccessNotificationListener;
import com.mannschaft.app.supportaccess.listener.SupportAccessPurgeEventListener;
import com.mannschaft.app.supportaccess.listener.SupportAccessWithdrawalListener;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import com.mannschaft.app.supportaccess.service.SupportAccessAuditRecorder;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder;
import com.mannschaft.app.supportaccess.service.SupportAccessPersonalDataService;
import com.mannschaft.app.supportaccess.service.SupportAccessRetentionService;
import com.mannschaft.app.supportaccess.service.SupportAccessNotificationOutboxStore;
import com.mannschaft.app.supportaccess.web.SupportAccessDenied;
import com.mannschaft.app.supportaccess.web.SupportAccessHandlerInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 戦役Z-B 試練: 周辺部品（文脈の保持と伝播・第2層の注釈・記録の REQUIRES_NEW・通知・退会/パージ・保存期間・
 * 本人向け閲覧記録・既存監査の actor 置換）。S3/S4/S10/S11/S12/S15 の一部。
 */
@DisplayName("戦役Z-B サポート閲覧の周辺部品 単体テスト")
class SupportAccessComponentsTest {

    private static final Long OPERATOR_ID = 9001L;
    private static final Long TARGET_ID = 42L;
    private static final Long GUARDIAN_ID = 55L;
    private static final UUID GRANT_ID = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final SupportAccessContext CONTEXT =
            new SupportAccessContext(GRANT_ID, OPERATOR_ID, TARGET_ID, NOW.plus(java.time.Duration.ofHours(20)));

    @AfterEach
    void tearDown() {
        try {
            SupportAccessContextHolder.clear();
        } catch (UnsupportedOperationException ignored) {
            // 試練スタブの間は未実装
        }
    }

    // ─── 文脈の保持と伝播（S3） ────────────────────────────────

    @Test
    @DisplayName("S3 文脈は set で有効・clear で無効になり、get で同じ値を返す")
    void S3_文脈の保持() {
        assertThat(SupportAccessContextHolder.isActive()).isFalse();
        SupportAccessContextHolder.set(CONTEXT);
        assertThat(SupportAccessContextHolder.isActive()).isTrue();
        assertThat(SupportAccessContextHolder.get()).contains(CONTEXT);
        SupportAccessContextHolder.clear();
        assertThat(SupportAccessContextHolder.get()).isEmpty();
    }

    @Test
    @DisplayName("S3 TaskDecorator は @Async の実行スレッドへ文脈を伝播し、実行後に元へ戻す（受け手でも READ ONLY・拒否が効く）")
    void S3_非同期へ文脈を伝播() throws Exception {
        SupportAccessContextHolder.set(CONTEXT);
        AtomicReference<Optional<SupportAccessContext>> seen = new AtomicReference<>();
        AtomicReference<Boolean> leftover = new AtomicReference<>();
        Runnable decorated = new SupportAccessContextTaskDecorator()
                .decorate(() -> seen.set(SupportAccessContextHolder.get()));
        SupportAccessContextHolder.clear();

        Thread worker = new Thread(() -> {
            decorated.run();
            leftover.set(SupportAccessContextHolder.isActive());
        });
        worker.start();
        worker.join();

        assertThat(seen.get()).contains(CONTEXT);
        assertThat(leftover.get()).isFalse();
    }

    // ─── 第2層の注釈（S4） ────────────────────────────────────

    static class DeniedController {
        @SupportAccessDenied
        public void download() {
        }

        public void view() {
        }
    }

    @Test
    @DisplayName("S4 @SupportAccessDenied の付いた handler は代理中なら GET でも拒否（004）、代理でなければ通す")
    void S4_注釈付きhandlerは代理中に拒否() throws Exception {
        SupportAccessHandlerInterceptor interceptor = new SupportAccessHandlerInterceptor();
        DeniedController controller = new DeniedController();
        HandlerMethod denied = new HandlerMethod(controller, DeniedController.class.getMethod("download"));
        HandlerMethod allowed = new HandlerMethod(controller, DeniedController.class.getMethod("view"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/x");

        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), denied)).isTrue();

        SupportAccessContextHolder.set(CONTEXT);
        assertThat(interceptor.preHandle(req, new MockHttpServletResponse(), allowed)).isTrue();
        MockHttpServletResponse res = new MockHttpServletResponse();
        boolean proceed;
        try {
            proceed = interceptor.preHandle(req, res, denied);
        } catch (com.mannschaft.app.common.BusinessException e) {
            assertThat(e.getErrorCode().getCode()).isEqualTo("SUPPORT_ACCESS_004");
            return;
        }
        assertThat(proceed).isFalse();
        assertThat(res.getStatus()).isEqualTo(403);
    }

    // ─── 記録の REQUIRES_NEW（S10/S11） ──────────────────────────

    @Test
    @DisplayName("S10/S11 閲覧記録の記録部品は公開メソッドすべてが REQUIRES_NEW（業務 TX が失敗しても消えない）")
    void S11_記録部品はREQUIRES_NEW() {
        for (Class<?> type : List.of(SupportAccessLogRecorder.class)) {
            List<Method> publicMethods = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                    .toList();
            assertThat(publicMethods).as(type.getSimpleName()).isNotEmpty();
            for (Method m : publicMethods) {
                Transactional tx = m.getAnnotation(Transactional.class);
                if (tx == null) {
                    tx = type.getAnnotation(Transactional.class);
                }
                assertThat(tx).as("%s#%s に @Transactional が無い", type.getSimpleName(), m.getName()).isNotNull();
                assertThat(tx.propagation()).as("%s#%s", type.getSimpleName(), m.getName())
                        .isEqualTo(Propagation.REQUIRES_NEW);
            }
        }
    }

    @Test
    @DisplayName("S11 遷移の監査の記録部品は @Transactional を持たない（auth の監査を supportaccess の TX の中から呼ばず、コミット後に独立して書く: D-3T）")
    void S11_監査部品は書込TXの外で動く() {
        assertThat(SupportAccessAuditRecorder.class.getAnnotation(Transactional.class)).isNull();
        for (Method m : SupportAccessAuditRecorder.class.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Transactional.class))
                    .as("SupportAccessAuditRecorder#%s", m.getName()).isNull();
        }
    }

    @Test
    @DisplayName("S11 監査 outbox の反映は actor=運営者・target=本人・metadata に grantId と auditOutboxId を入れ、反映後に確定する")
    void S11_遷移監査のactorは運営者() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore store =
                mock(com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore.class);
        UUID outboxId = UUID.randomUUID();
        given(store.leaseForGrant(GRANT_ID)).willReturn(List.of(
                new com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore.PendingAudit(
                        outboxId, GRANT_ID, "SUPPORT_ACCESS_SESSION_STARTED", OPERATOR_ID, TARGET_ID, NOW, 1)));

        int published = new SupportAccessAuditRecorder(auditLogService, store).relayForGrant(GRANT_ID);

        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).recordStrict(eq("SUPPORT_ACCESS_SESSION_STARTED"), eq(OPERATOR_ID), eq(TARGET_ID),
                metadata.capture());
        assertThat(metadata.getValue()).contains(GRANT_ID.toString()).contains(outboxId.toString());
        verify(store).markPublished(outboxId);
        assertThat(published).isEqualTo(1);
    }

    @Test
    @DisplayName("S11 audit_logs への反映に失敗したら握りつぶさず outbox に失敗を記録し、確定しない（再試行バッチが拾う）")
    void S11_反映失敗はoutboxに残る() {
        AuditLogService auditLogService = mock(AuditLogService.class);
        com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore store =
                mock(com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore.class);
        UUID outboxId = UUID.randomUUID();
        given(store.leaseDue(anyInt())).willReturn(List.of(
                new com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore.PendingAudit(
                        outboxId, GRANT_ID, "SUPPORT_ACCESS_REVOKED", OPERATOR_ID, TARGET_ID, NOW, 1)));
        org.mockito.Mockito.doThrow(new IllegalStateException("audit_logs down"))
                .when(auditLogService).recordStrict(any(), any(), any(), any());

        int published = new SupportAccessAuditRecorder(auditLogService, store).relayDue();

        assertThat(published).isZero();
        verify(store).markFailed(eq(outboxId), org.mockito.ArgumentMatchers.contains("audit_logs down"));
        verify(store, org.mockito.Mockito.never()).markPublished(any());
    }

    @Test
    @DisplayName("S11 監査の書込口（AuditLogStrictWriter）は REQUIRES_NEW・書込可能な接続で、失敗を例外で返す（握りつぶさない）")
    void S11_厳格な監査の書込口() throws Exception {
        Method write = com.mannschaft.app.auth.service.AuditLogStrictWriter.class
                .getMethod("write", com.mannschaft.app.auth.entity.AuditLogEntity.class);
        assertThat(write.getAnnotation(Transactional.class).propagation())
                .isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        assertThat(org.springframework.core.annotation.AnnotatedElementUtils.hasAnnotation(com.mannschaft.app.auth.service.AuditLogStrictWriter.class,
                com.mannschaft.app.supportaccess.context.SupportAccessWritable.class)).isTrue();

        com.mannschaft.app.auth.repository.AuditLogRepository repo =
                mock(com.mannschaft.app.auth.repository.AuditLogRepository.class);
        given(repo.saveAndFlush(any())).willThrow(new IllegalStateException("insert failed"));
        AuditLogService service = new AuditLogService(repo,
                mock(com.mannschaft.app.auth.service.AuditLogQueryService.class),
                new com.mannschaft.app.auth.service.AuditLogStrictWriter(repo));
        assertThatThrownBy(() -> service.recordStrict("SUPPORT_ACCESS_REVOKED", OPERATOR_ID, TARGET_ID, "{}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("S11 代理中に recordSync を業務 TX から呼んでも、READ ONLY の業務接続に参加せず独立した書込可能な TX で書く")
    void S11_代理中のrecordSyncは独立TX() {
        com.mannschaft.app.auth.repository.AuditLogRepository repo =
                mock(com.mannschaft.app.auth.repository.AuditLogRepository.class);
        com.mannschaft.app.auth.service.AuditLogStrictWriter writer = mock(com.mannschaft.app.auth.service.AuditLogStrictWriter.class);
        AuditLogService service = new AuditLogService(repo,
                mock(com.mannschaft.app.auth.service.AuditLogQueryService.class), writer);
        SupportAccessContextHolder.set(CONTEXT);
        try {
            service.recordSync("POINT_CARD_VIEWED", TARGET_ID, null, null, null, null, null, null, null);
        } finally {
            SupportAccessContextHolder.clear();
        }
        verify(writer).write(any());
        verify(repo, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("S10 巨大な数字のスコープ ID は 503 にせずスコープ不明として記録する")
    void S10_巨大なスコープIDはスコープ不明() {
        SupportAccessLogRepository repo = mock(SupportAccessLogRepository.class);
        given(repo.save(any(SupportAccessLogEntity.class))).willAnswer(inv -> inv.getArgument(0));

        new SupportAccessLogRecorder(repo, Clock.fixed(NOW, ZoneOffset.UTC))
                .recordView(CONTEXT, "GET", "/api/v1/teams/99999999999999999999999/schedules");

        ArgumentCaptor<SupportAccessLogEntity> captor = ArgumentCaptor.forClass(SupportAccessLogEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getScopeType()).isNull();
        assertThat(captor.getValue().getScopeId()).isNull();
        assertThat(captor.getValue().getRequestPath()).isEqualTo("/api/v1/teams/99999999999999999999999/schedules");
    }

    @Test
    @DisplayName("S10 閲覧記録はクエリ文字列を保存せず、運営者・本人・grant・パスを持つ VIEW 行を書く")
    void S10_閲覧記録の内容() {
        SupportAccessLogRepository repo = mock(SupportAccessLogRepository.class);
        given(repo.save(any(SupportAccessLogEntity.class))).willAnswer(inv -> inv.getArgument(0));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        new SupportAccessLogRecorder(repo, clock).recordView(CONTEXT, "GET", "/api/v1/teams/3/schedules?q=secret");

        ArgumentCaptor<SupportAccessLogEntity> captor = ArgumentCaptor.forClass(SupportAccessLogEntity.class);
        verify(repo).save(captor.capture());
        SupportAccessLogEntity row = captor.getValue();
        assertThat(row.getEventKind()).isEqualTo(SupportAccessEventKind.VIEW);
        assertThat(row.getGrantId()).isEqualTo(GRANT_ID);
        assertThat(row.getOperatorUserId()).isEqualTo(OPERATOR_ID);
        assertThat(row.getTargetUserId()).isEqualTo(TARGET_ID);
        assertThat(row.getHttpMethod()).isEqualTo("GET");
        assertThat(row.getRequestPath()).isEqualTo("/api/v1/teams/3/schedules").doesNotContain("secret");
        assertThat(row.getScopeType()).isEqualTo("TEAM");
        assertThat(row.getScopeId()).isEqualTo(3L);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
    }

    // ─── 既存監査の actor 置換（S11） ──────────────────────────

    @Test
    @DisplayName("S11 代理中に既存の監査（例 POINT_CARD_VIEWED）を書くと userId=運営者・targetUserId=本人・metadata に grantId")
    void S11_代理中の既存監査は運営者がactor() {
        com.mannschaft.app.auth.repository.AuditLogRepository auditLogRepository =
                mock(com.mannschaft.app.auth.repository.AuditLogRepository.class);
        AuditLogService auditLogService = newAuditLogService(auditLogRepository);
        SupportAccessContextHolder.set(CONTEXT);

        auditLogService.recordSync("POINT_CARD_VIEWED", TARGET_ID, null, null, null, null, null, null, "{\"cardId\":1}");

        ArgumentCaptor<com.mannschaft.app.auth.entity.AuditLogEntity> captor =
                ArgumentCaptor.forClass(com.mannschaft.app.auth.entity.AuditLogEntity.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(OPERATOR_ID);
        assertThat(captor.getValue().getTargetUserId()).isEqualTo(TARGET_ID);
        assertThat(captor.getValue().getMetadata()).contains(GRANT_ID.toString()).contains("cardId");
    }

    private static AuditLogService newAuditLogService(com.mannschaft.app.auth.repository.AuditLogRepository repo) {
        return new AuditLogService(repo, mock(com.mannschaft.app.auth.service.AuditLogQueryService.class),
                new com.mannschaft.app.auth.service.AuditLogStrictWriter(repo));
    }

    // ─── 通知（S12） ────────────────────────────────────────

    @Test
    @DisplayName("S12 通知の配送は AFTER_COMMIT のリスナーで行う（遷移直後と再試行の両方）")
    void S12_通知リスナーはAFTER_COMMIT() throws Exception {
        for (Method m : List.of(
                SupportAccessNotificationListener.class.getMethod("onGrantChanged", SupportAccessGrantChangedEvent.class),
                SupportAccessNotificationListener.class.getMethod("onRelayRequested",
                        com.mannschaft.app.supportaccess.event.SupportAccessNotificationRelayRequestedEvent.class))) {
            TransactionalEventListener ann = m.getAnnotation(TransactionalEventListener.class);
            assertThat(ann).as(m.getName()).isNotNull();
            assertThat(ann.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        }
    }

    @Test
    @DisplayName("S12 遷移直後はその grant の通知 outbox 行を配送し、配送済みにする（本人・保護者・閲覧開始）")
    void S12_本人への通知() {
        NotificationDeliveryRunner runner = mock(NotificationDeliveryRunner.class);
        SupportAccessNotificationOutboxStore store = mock(SupportAccessNotificationOutboxStore.class);
        var rows = List.of(row(SupportAccessNotificationKind.REQUESTED_TO_TARGET, TARGET_ID),
                row(SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN, GUARDIAN_ID),
                row(SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET, TARGET_ID));
        when(store.leaseForGrant(GRANT_ID)).thenReturn(rows);

        newNotificationListener(runner, store).onGrantChanged(
                new SupportAccessGrantChangedEvent(SupportAccessAuditAction.SESSION_STARTED, GRANT_ID, OPERATOR_ID, TARGET_ID, null));

        assertThat(sentTo(runner)).containsExactlyInAnyOrder(
                TARGET_ID + ":SUPPORT_ACCESS_" + SupportAccessNotificationKind.REQUESTED_TO_TARGET,
                GUARDIAN_ID + ":SUPPORT_ACCESS_" + SupportAccessNotificationKind.REQUESTED_TO_GUARDIAN,
                TARGET_ID + ":SUPPORT_ACCESS_" + SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET);
        rows.forEach(r -> verify(store).markPublished(r.id()));
    }

    @Test
    @DisplayName("S12 許可・拒否・取消は運営者へ通知する（再試行の依頼でも未配送の行を配送する）")
    void S12_運営者への通知() {
        NotificationDeliveryRunner runner = mock(NotificationDeliveryRunner.class);
        SupportAccessNotificationOutboxStore store = mock(SupportAccessNotificationOutboxStore.class);
        when(store.leaseDue(anyInt())).thenReturn(List.of(
                row(SupportAccessNotificationKind.APPROVED_TO_OPERATOR, OPERATOR_ID),
                row(SupportAccessNotificationKind.DENIED_TO_OPERATOR, OPERATOR_ID),
                row(SupportAccessNotificationKind.REVOKED_TO_OPERATOR, OPERATOR_ID)));

        newNotificationListener(runner, store).onRelayRequested(
                new com.mannschaft.app.supportaccess.event.SupportAccessNotificationRelayRequestedEvent(NOW));

        assertThat(sentTo(runner)).containsExactlyInAnyOrder(
                OPERATOR_ID + ":SUPPORT_ACCESS_" + SupportAccessNotificationKind.APPROVED_TO_OPERATOR,
                OPERATOR_ID + ":SUPPORT_ACCESS_" + SupportAccessNotificationKind.DENIED_TO_OPERATOR,
                OPERATOR_ID + ":SUPPORT_ACCESS_" + SupportAccessNotificationKind.REVOKED_TO_OPERATOR);
    }

    @Test
    @DisplayName("第2回検分 配送に失敗した行は配送済みにせず失敗を記録して残す（再試行バッチが拾う）。他の行は配送する")
    void S12_配送失敗は未配送で残る() {
        NotificationDeliveryRunner runner = mock(NotificationDeliveryRunner.class);
        SupportAccessNotificationOutboxStore store = mock(SupportAccessNotificationOutboxStore.class);
        var failing = row(SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET, TARGET_ID);
        var ok = row(SupportAccessNotificationKind.REVOKED_TO_OPERATOR, OPERATOR_ID);
        when(store.leaseForGrant(GRANT_ID)).thenReturn(List.of(failing, ok));
        when(runner.sendOne(org.mockito.ArgumentMatchers.argThat(r -> r != null && TARGET_ID.equals(r.recipientUserId()))))
                .thenThrow(new IllegalStateException("notification down"));

        newNotificationListener(runner, store).onGrantChanged(
                new SupportAccessGrantChangedEvent(SupportAccessAuditAction.SESSION_STARTED, GRANT_ID, OPERATOR_ID, TARGET_ID, null));

        verify(store).markFailed(eq(failing.id()), anyString());
        verify(store, never()).markPublished(failing.id());
        verify(store).markPublished(ok.id());
    }

    @Test
    @DisplayName("第2回検分 軽微8 拒否の監査はその outbox 行の ID だけを反映する（outbox 全体を FOR UPDATE で走査しない）")
    void 拒否の監査は1行だけ反映() {
        com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore store =
                mock(com.mannschaft.app.supportaccess.service.SupportAccessAuditOutboxStore.class);
        java.util.UUID outboxId = java.util.UUID.randomUUID();
        when(store.leaseById(outboxId)).thenReturn(List.of());
        SupportAccessAuditRecorder recorder = new SupportAccessAuditRecorder(mock(AuditLogService.class), store);

        new com.mannschaft.app.supportaccess.listener.SupportAccessAuditListener(recorder).onDeniedAuditAppended(
                new com.mannschaft.app.supportaccess.event.SupportAccessDeniedAuditAppendedEvent(outboxId));

        verify(store).leaseById(outboxId);
        verify(store, never()).leaseDue(anyInt());
    }

    private static SupportAccessNotificationOutboxStore.PendingNotification row(SupportAccessNotificationKind kind, Long recipient) {
        return new SupportAccessNotificationOutboxStore.PendingNotification(java.util.UUID.randomUUID(), GRANT_ID,
                kind.name(), recipient, 1);
    }

    /** 通知リスナーを、grant と利用者名を引ける最小の依存で組み立てる（配送は runner のモックで観測する）。 */
    private static SupportAccessNotificationListener newNotificationListener(NotificationDeliveryRunner runner,
                                                                             SupportAccessNotificationOutboxStore store) {
        SupportAccessGrantRepository grantRepository = mock(SupportAccessGrantRepository.class);
        SupportAccessGrantEntity grant = mock(SupportAccessGrantEntity.class);
        when(grant.getOperatorUserId()).thenReturn(OPERATOR_ID);
        when(grant.getTargetUserId()).thenReturn(TARGET_ID);
        when(grant.getRequestReason()).thenReturn("理由");
        when(grantRepository.findById(GRANT_ID)).thenReturn(java.util.Optional.of(grant));
        com.mannschaft.app.common.i18n.UserLocaleCache localeCache = mock(com.mannschaft.app.common.i18n.UserLocaleCache.class);
        when(localeCache.getLocale(any())).thenReturn("ja");
        org.springframework.context.MessageSource messageSource = mock(org.springframework.context.MessageSource.class);
        when(messageSource.getMessage(anyString(), any(), anyString(), any(java.util.Locale.class)))
                .thenAnswer(inv -> inv.getArgument(2));
        com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory directory =
                mock(com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory.class);
        when(directory.findUser(any())).thenReturn(java.util.Optional.empty());
        when(directory.findDeliverableEmail(any())).thenReturn(java.util.Optional.empty());
        var composer = new com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer(
                localeCache, messageSource, directory, "https://mannschaft.app");
        return new SupportAccessNotificationListener(runner, grantRepository, store, composer);
    }

    private static java.util.List<String> sentTo(NotificationDeliveryRunner runner) {
        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(runner, atLeastOnce()).sendOne(captor.capture());
        return captor.getAllValues().stream()
                .map(r -> r.recipientUserId() + ":" + r.notificationType()).toList();
    }

    // ─── 退会・パージ・保存期間・本人向け閲覧記録（S10/S15） ─────────

    @Test
    @DisplayName("S15 退会申請イベントで本人の grant を即時取消する")
    void S15_退会申請で即取消() {
        SupportAccessGrantService service = mock(SupportAccessGrantService.class);

        new SupportAccessWithdrawalListener(service).onWithdrawalRequested(new WithdrawalRequestedEvent(TARGET_ID, "a@example.invalid"));

        verify(service).revokeAllForWithdrawal(TARGET_ID);
    }

    @Test
    @DisplayName("S15 アカウントパージで本人の閲覧記録→許可の順に物理削除し、supportaccess ドメインを完了にする")
    void S15_パージで物理削除() {
        SupportAccessGrantRepository grants = mock(SupportAccessGrantRepository.class);
        SupportAccessLogRepository logs = mock(SupportAccessLogRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository outbox =
                mock(com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository.class);
        com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository notifications =
                mock(com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository.class);
        SupportAccessRetentionService retention = new SupportAccessRetentionService(grants, logs, outbox, notifications, clock);
        retention.purgeUser(TARGET_ID);
        var order = inOrder(logs, grants);
        order.verify(logs).deleteByTargetUserId(TARGET_ID);
        order.verify(grants).deleteByTargetUserId(TARGET_ID);
        // 保護者としてだけ残る行は保護者の userId を外し、本人宛の監査 outbox・運営者の依頼ロックも消す
        verify(grants).clearGuardianUserId(TARGET_ID);
        verify(grants).deleteRequestLock(TARGET_ID);
        // 第2回検分: 監査 outbox は反映済みだけ消し、未反映は本人の userId を外して残す（監査を失わない）
        verify(outbox).deletePublishedByTargetUserId(TARGET_ID);
        verify(outbox).anonymizeUnpublishedByTargetUserId(TARGET_ID);
        verify(notifications).deleteByUserId(TARGET_ID);

        SupportAccessRetentionService mockedRetention = mock(SupportAccessRetentionService.class);
        AccountPurgeCompletionService completion = mock(AccountPurgeCompletionService.class);
        new SupportAccessPurgeEventListener(mockedRetention, completion).onAccountPurged(new AccountPurgedEvent(TARGET_ID, "hash"));
        verify(mockedRetention).purgeUser(TARGET_ID);
        verify(completion).markDomainSuccess(TARGET_ID, "supportaccess");
    }

    @Test
    @DisplayName("S15 保存期間: 閲覧記録は1年、終了した依頼・許可は3年を過ぎたら物理削除する")
    void S15_保存期間() {
        SupportAccessGrantRepository grants = mock(SupportAccessGrantRepository.class);
        SupportAccessLogRepository logs = mock(SupportAccessLogRepository.class);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        new SupportAccessRetentionService(grants, logs,
                mock(com.mannschaft.app.supportaccess.repository.SupportAccessAuditOutboxRepository.class),
                mock(com.mannschaft.app.supportaccess.repository.SupportAccessNotificationOutboxRepository.class), clock)
                .purgeExpiredRecords();

        verify(logs).deleteByOccurredAtBefore(NOW.atZone(ZoneOffset.UTC).minusYears(1).toInstant());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<SupportAccessStatus>> statuses = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(grants).deleteByStatusInAndUpdatedAtBefore(statuses.capture(), eq(NOW.atZone(ZoneOffset.UTC).minusYears(3).toInstant()));
        assertThat(statuses.getValue())
                .containsExactlyInAnyOrder(SupportAccessStatus.DENIED, SupportAccessStatus.REVOKED,
                        SupportAccessStatus.EXPIRED, SupportAccessStatus.CANCELLED)
                .doesNotContain(SupportAccessStatus.PENDING, SupportAccessStatus.APPROVED);
    }

    @Test
    @DisplayName("S10/S15 本人向けの閲覧記録は日時・運営者の表示名・パス・スコープを返し、メールは出さない（GDPR エクスポートも同じ部品）")
    void S10_本人向け閲覧記録() {
        SupportAccessLogRepository logs = mock(SupportAccessLogRepository.class);
        SupportAccessUserDirectory directory = mock(SupportAccessUserDirectory.class);
        given(logs.findByTargetUserIdOrderByOccurredAtDesc(TARGET_ID)).willReturn(List.of(
                SupportAccessLogEntity.builder().grantId(GRANT_ID).operatorUserId(OPERATOR_ID).targetUserId(TARGET_ID)
                        .eventKind(SupportAccessEventKind.VIEW).httpMethod("GET").requestPath("/api/v1/teams/3/schedules")
                        .scopeType("TEAM").scopeId(3L).occurredAt(NOW).build()));
        given(directory.findUser(OPERATOR_ID))
                .willReturn(Optional.of(new SupportAccessUserSnapshot(OPERATOR_ID, "運営担当", "ACTIVE", false, true)));

        List<SupportAccessLogView> views = new SupportAccessPersonalDataService(logs,
                mock(SupportAccessGrantRepository.class), directory).listLogsForTarget(TARGET_ID);

        assertThat(views).singleElement().satisfies(v -> {
            assertThat(v.occurredAt()).isEqualTo(NOW);
            assertThat(v.operatorDisplayName()).isEqualTo("運営担当");
            assertThat(v.requestPath()).isEqualTo("/api/v1/teams/3/schedules");
            assertThat(v.scopeType()).isEqualTo("TEAM");
            assertThat(v.scopeId()).isEqualTo(3L);
            assertThat(v.toString()).doesNotContain("@");
        });
    }
}
