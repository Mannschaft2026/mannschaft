package com.mannschaft.app.role.event;

import com.mannschaft.app.auth.event.InviteReissueRequestedEvent;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.InviteReissueRecipientResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link InviteReissueNotificationListener} 単体テスト（CMP-261107-1833 AC9・設計 §3・§9-2）。
 *
 * <p>通知先は {@link InviteReissueRecipientResolver}（招待権限者のみ）に任せ、その全員にだけ届ける。
 * 金型は {@code JoinRequestNotificationListener}（@Async("event-pool")・AFTER_COMMIT・@BackgroundFeaturePolicy）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteReissueNotificationListener 単体テスト（CMP-261107-1833 AC9）")
class InviteReissueNotificationListenerTest {

    private static final String TYPE = "INVITE_REISSUE_REQUESTED";

    @Mock private NotificationHelper notificationHelper;
    @Mock private InviteReissueRecipientResolver resolver;

    @InjectMocks private InviteReissueNotificationListener listener;

    @Test
    @DisplayName("AC9: チームの招待権限者全員にだけ INVITE_REISSUE_REQUESTED を通知する（要請者は actor、actionUrl は招待管理画面）")
    void notifiesInvitersOfTeam() {
        given(resolver.findInviterUserIds("TEAM", 70L)).willReturn(List.of(11L, 12L));

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "TEAM", 70L));

        for (Long inviter : List.of(11L, 12L)) {
            verify(notificationHelper).notify(eq(inviter), eq(TYPE), anyString(), anyString(), anyString(), any(),
                    eq(NotificationScopeType.TEAM), eq(70L), anyString(), eq(700L));
        }
        verifyNoMoreInteractions(notificationHelper);
    }

    @Test
    @DisplayName("AC9: 組織の招待権限者へは ORGANIZATION スコープで通知する")
    void notifiesInvitersOfOrganization() {
        given(resolver.findInviterUserIds("ORGANIZATION", 80L)).willReturn(List.of(21L));

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "ORGANIZATION", 80L));

        verify(notificationHelper).notify(eq(21L), eq(TYPE), anyString(), anyString(), anyString(), any(),
                eq(NotificationScopeType.ORGANIZATION), eq(80L), anyString(), eq(700L));
        verifyNoMoreInteractions(notificationHelper);
    }

    @Test
    @DisplayName("AC9: 招待権限者がいなければ誰にも通知しない（一般 MEMBER へ代替配送しない）")
    void noInviters_noNotification() {
        given(resolver.findInviterUserIds("TEAM", 70L)).willReturn(List.of());

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "TEAM", 70L));

        verify(notificationHelper, never()).notify(anyLong(), anyString(), anyString(), anyString(), anyString(), any(),
                any(NotificationScopeType.class), anyLong(), any(), any());
    }

    @Test
    @DisplayName("AC9: 配送失敗は呼び出し元へ伝播しない（AFTER_COMMIT の非同期リスナー）")
    void failureDoesNotPropagate() {
        given(resolver.findInviterUserIds("TEAM", 70L)).willThrow(new IllegalStateException("試練"));

        assertThatCode(() -> listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "TEAM", 70L)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC9/AC11: Bean で、@Async(event-pool)・AFTER_COMMIT・@BackgroundFeaturePolicy を持ち、@Transactional を持たない")
    void annotations() throws Exception {
        assertThat(InviteReissueNotificationListener.class.isAnnotationPresent(Component.class)).isTrue();
        assertThat(InviteReissueNotificationListener.class.isAnnotationPresent(Transactional.class)).isFalse();
        Method m = InviteReissueNotificationListener.class.getMethod("onInviteReissueRequested",
                InviteReissueRequestedEvent.class);
        assertThat(m.getAnnotation(Async.class)).isNotNull();
        assertThat(m.getAnnotation(Async.class).value()).isEqualTo("event-pool");
        TransactionalEventListener tel = m.getAnnotation(TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(m.isAnnotationPresent(BackgroundFeaturePolicy.class)).isTrue();
        assertThat(m.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("AC9: 通知種別 INVITE_REISSUE_REQUESTED が NotificationType に登録されている")
    void notificationTypeRegistered() {
        assertThat(NotificationType.valueOf(TYPE)).isNotNull();
    }
}
