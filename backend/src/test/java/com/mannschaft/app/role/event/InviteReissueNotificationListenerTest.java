package com.mannschaft.app.role.event;

import com.mannschaft.app.auth.event.InviteReissueRequestedEvent;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.NotificationType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.service.InviteReissueRecipientResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    @Mock private NameResolverService nameResolverService;
    @Mock private MessageSource messageSource;

    @InjectMocks private InviteReissueNotificationListener listener;

    @Test
    @DisplayName("AC9: チームの招待権限者全員にだけ INVITE_REISSUE_REQUESTED を通知する（要請者は actor、actionUrl は対象チームの招待管理画面）")
    void notifiesInvitersOfTeam() {
        given(resolver.findInviterUserIds("TEAM", 70L)).willReturn(List.of(11L, 12L));
        given(resolver.findInviteManagementPath("TEAM", 70L)).willReturn(Optional.of("/teams/aoba/invites"));

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "TEAM", 70L));

        verify(notificationHelper).notifyAllLocalized(eq(List.of(11L, 12L)), eq(TYPE), eq("USER"), isNull(),
                eq(NotificationScopeType.TEAM), eq(70L), eq("/teams/aoba/invites"), eq(700L), any());
        verifyNoMoreInteractions(notificationHelper);
    }

    @Test
    @DisplayName("AC9: 組織の招待権限者へは ORGANIZATION スコープで通知する")
    void notifiesInvitersOfOrganization() {
        given(resolver.findInviterUserIds("ORGANIZATION", 80L)).willReturn(List.of(21L));
        given(resolver.findInviteManagementPath("ORGANIZATION", 80L)).willReturn(Optional.of("/organizations/x/invites"));

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "ORGANIZATION", 80L));

        verify(notificationHelper).notifyAllLocalized(eq(List.of(21L)), eq(TYPE), eq("USER"), isNull(),
                eq(NotificationScopeType.ORGANIZATION), eq(80L), eq("/organizations/x/invites"), eq(700L), any());
    }

    @Test
    @DisplayName("A-2: 本文に要請者の表示名と招待先の名前が入り、メールアドレスは入らない（受信者の言語で組み立てる）")
    void bodyCarriesRequesterNameAndScopeName() {
        given(resolver.findInviterUserIds("TEAM", 70L)).willReturn(List.of(11L));
        given(resolver.findInviteManagementPath("TEAM", 70L)).willReturn(Optional.of("/teams/aoba/invites"));
        given(nameResolverService.resolveUserDisplayName(700L)).willReturn("高橋");
        given(nameResolverService.resolveScopeName("TEAM", 70L)).willReturn("青葉野球部");
        given(messageSource.getMessage(eq("notification.inviteReissueRequested.title"), any(), eq(Locale.GERMAN)))
                .willReturn("T");
        given(messageSource.getMessage(eq("notification.inviteReissueRequested.body"), any(), eq(Locale.GERMAN)))
                .willReturn("B");

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "TEAM", 70L));

        ArgumentCaptor<NotificationHelper.LocalizedMessageBuilder> captor =
                ArgumentCaptor.forClass(NotificationHelper.LocalizedMessageBuilder.class);
        verify(notificationHelper).notifyAllLocalized(any(), eq(TYPE), any(), any(), any(), any(), any(), any(),
                captor.capture());
        NotificationHelper.LocalizedMessage message = captor.getValue().build(11L, Locale.GERMAN);
        assertThat(message.title()).isEqualTo("T");
        assertThat(message.body()).isEqualTo("B");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(messageSource).getMessage(eq("notification.inviteReissueRequested.body"), args.capture(),
                eq(Locale.GERMAN));
        assertThat(args.getValue()).containsExactly("高橋", "青葉野球部");
    }

    @Test
    @DisplayName("AC9: 招待権限者がいなければ誰にも通知しない（一般 MEMBER へ代替配送しない）")
    void noInviters_noNotification() {
        given(resolver.findInviterUserIds("TEAM", 70L)).willReturn(List.of());

        listener.onInviteReissueRequested(new InviteReissueRequestedEvent(700L, "TEAM", 70L));

        verifyNoInteractions(notificationHelper);
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
