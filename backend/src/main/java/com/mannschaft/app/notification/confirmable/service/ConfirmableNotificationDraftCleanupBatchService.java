package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * CMP-261126-1558: 確認通知の下書きの掃除バッチ。
 *
 * <p>updated_at から {@value #RETENTION_DAYS} 日を超えた下書きを日次で物理削除する。
 * 作りは {@link ConfirmableNotificationExpiryBatchService} に揃える（{@code @Scheduled}・ShedLock・
 * {@code @BatchEndpoint}・{@code @BackgroundFeaturePolicy} は出陣で付ける）。</p>
 *
 * <p>試練（red）段階の骨格。</p>
 */
@Service
@RequiredArgsConstructor
public class ConfirmableNotificationDraftCleanupBatchService {

    /** 下書きの保持日数（updated_at 起点）。 */
    public static final int RETENTION_DAYS = 90;

    private final ConfirmableNotificationDraftRepository draftRepository;

    /** 日次の入口。現在時刻から {@value #RETENTION_DAYS} 日より前に更新された下書きを消す。 */
    public void runBatch() {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }

    /**
     * 指定時刻から {@value #RETENTION_DAYS} 日を超えた下書きを消す。
     *
     * @param now 基準時刻
     * @return 削除件数
     */
    public long purgeExpired(Instant now) {
        throw new UnsupportedOperationException("CMP-261126-1558: 未実装（試練）");
    }
}
