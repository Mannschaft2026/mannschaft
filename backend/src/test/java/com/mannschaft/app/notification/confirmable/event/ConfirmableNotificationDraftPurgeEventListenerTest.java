package com.mannschaft.app.notification.confirmable.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * CMP-261126-1558 試練 AC13: {@link ConfirmableNotificationDraftPurgeEventListener} の単体テスト。
 *
 * <p>手本は {@code QuickMemoSettingsPurgeEventListener}。退会30日後の {@link AccountPurgedEvent} で、
 * その利用者の下書きだけを {@code deleteByUserId} で消し、所有TXのコミット後にだけ完了を記録する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConfirmableNotificationDraftPurgeEventListener 単体テスト（AC13）")
class ConfirmableNotificationDraftPurgeEventListenerTest {

    private static final Long USER_ID = 42L;

    @Mock
    private ConfirmableNotificationDraftRepository draftRepository;
    @Mock
    private AccountPurgeCompletionService completionService;

    @InjectMocks
    private ConfirmableNotificationDraftPurgeEventListener listener;

    @BeforeEach
    void initSynchronization() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearSynchronization() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    @DisplayName("AccountPurgedEvent で、その利用者の下書きだけを deleteByUserId で消す")
    void deletesOnlyThatUsersDrafts() {
        listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

        verify(draftRepository).deleteByUserId(USER_ID);
        verify(draftRepository, never()).deleteAll();
        verify(draftRepository, never()).deleteAllInBatch();
        verify(draftRepository, never()).deleteByUpdatedAtBefore(any());
        verifyNoMoreInteractions(draftRepository);
    }

    @Test
    @DisplayName("完了記録はコミット後（afterCommit）にだけ、ドメイン名 confirmabledraft で行う")
    void marksSuccessOnlyAfterCommit() {
        listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash"));

        verifyNoInteractions(completionService);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).as("コミット後の完了記録を登録する").isNotEmpty();
        syncs.forEach(TransactionSynchronization::afterCommit);

        verify(completionService).markDomainSuccess(USER_ID, ConfirmableNotificationDraftPurgeEventListener.DOMAIN);
        assertThat(ConfirmableNotificationDraftPurgeEventListener.DOMAIN).isEqualTo("confirmabledraft");
    }

    @Test
    @DisplayName("削除が失敗したら例外を伝播し、完了を記録しない（握りつぶさない）")
    void propagatesFailureWithoutMarkingSuccess() {
        given(draftRepository.deleteByUserId(anyLong())).willThrow(new IllegalStateException("DB 障害"));

        assertThatThrownBy(() -> listener.onAccountPurged(new AccountPurgedEvent(USER_ID, "hash")))
                .isInstanceOf(IllegalStateException.class);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("手動再試行 retryPurge も deleteByUserId で消して true を返す")
    void retryPurgeDeletes() {
        assertThat(listener.retryPurge(USER_ID)).isTrue();

        verify(draftRepository).deleteByUserId(USER_ID);
    }
}
