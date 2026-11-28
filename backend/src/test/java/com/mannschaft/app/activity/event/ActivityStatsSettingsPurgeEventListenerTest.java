package com.mannschaft.app.activity.event;

import com.mannschaft.app.activity.repository.ActivityStatsSettingsRepository;
import com.mannschaft.app.gdpr.service.AccountPurgeCompletionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261124-0159 PR-A1b: 退会者の物理削除で活動統計の設定の最終更新者を NULL 化する（F06.4 §15.3）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityStatsSettingsPurgeEventListener 単体テスト")
class ActivityStatsSettingsPurgeEventListenerTest {

    @Mock private ActivityStatsSettingsRepository settingsRepository;
    @Mock private AccountPurgeCompletionService completionService;
    @InjectMocks private ActivityStatsSettingsPurgeEventListener listener;

    @Test
    @DisplayName("再試行は updated_by の NULL 化を実行して成功を返す（設定の行は消さない）")
    void 再試行はNULL化() {
        assertThat(listener.retryPurge(42L)).isTrue();
        verify(settingsRepository).clearUpdatedBy(42L);
        verifyNoInteractions(completionService);
    }

    @Test
    @DisplayName("完了トラッキングのドメイン名は activity")
    void ドメイン名() {
        assertThat(ActivityStatsSettingsPurgeEventListener.DOMAIN).isEqualTo("activity");
    }
}
