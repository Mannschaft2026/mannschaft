package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.entity.NotificationEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CMP-261002-1559: 確認通知の受信者行・アプリ内通知を「500人ずつ多値 INSERT」で書き込む共通部品。
 *
 * <p><b>試練（テスト先行）用の骨格</b>。現時点ではすべて {@link UnsupportedOperationException} を投げ、
 * 受け入れテストを red に保つ。出陣（実装）で、{@link ConfirmableFanoutChunkSink} の内部処理
 * （{@code findExistingRecipientUserIds} / {@code insertRecipients} / {@code insertAppNotifications}）を
 * ここへ移し、同期 {@link ConfirmableNotificationService#send} と非同期 fan-out の両方から使う。</p>
 *
 * <ul>
 *   <li>1 回の呼び出しに渡す受信者は最大 {@link #CHUNK_SIZE} 人（呼び出し側で刻む）。</li>
 *   <li>受信者数に上限は設けない（500 は1チャンクの単位であり全体の上限ではない。F04.9 §4）。</li>
 *   <li>トランザクションは呼び出し側のものに参加する（本部品は独自に境界を張らない）。</li>
 * </ul>
 */
@Component
public class ConfirmableRecipientBulkWriter {

    /** 1 回の多値 INSERT で扱う受信者数の上限（全体の上限ではない）。 */
    public static final int CHUNK_SIZE = 500;

    /**
     * 指定の確認通知に既に受信者行がある user_id を、1 文の SELECT で求める（再開・再発火時の二重防止）。
     *
     * @param notificationId 確認通知ID
     * @param userIds        候補受信者（最大 {@link #CHUNK_SIZE} 人）
     * @return 既に受信者行がある user_id
     */
    public Set<Long> findExistingRecipientUserIds(Long notificationId, List<Long> userIds) {
        throw new UnsupportedOperationException("CMP-261002-1559: 試練の骨格。出陣で実装する");
    }

    /**
     * 受信者行を確認トークン付きで 1 文の多値 INSERT で作成する。
     *
     * @param notificationId                確認通知ID
     * @param userIds                       新規受信者（重複なし・最大 {@link #CHUNK_SIZE} 人）
     * @param resolvedFirstReminderMinutes  解決済みの1回目リマインド分数（非正規化して保存する）
     * @param resolvedSecondReminderMinutes 解決済みの2回目リマインド分数（非正規化して保存する）
     * @return user_id → 確認トークン
     */
    public Map<Long, String> insertRecipients(Long notificationId, List<Long> userIds,
            int resolvedFirstReminderMinutes, int resolvedSecondReminderMinutes) {
        throw new UnsupportedOperationException("CMP-261002-1559: 試練の骨格。出陣で実装する");
    }

    /**
     * アプリ内通知（{@code notifications}）を 1 文の多値 INSERT で作成する。
     *
     * <p>戻り値は採番 id 付きの通知で、呼び出し側がコミット後の WebSocket / Push 配信
     * （{@code NotificationDispatchService#dispatchBatch}）に渡すためのもの。</p>
     *
     * @param notification        確認通知（source_id・scope・title 等の素材）
     * @param userIds             新規受信者（重複なし・最大 {@link #CHUNK_SIZE} 人）
     * @param appNotificationType 通知種別（例: {@code CONFIRMABLE_NOTIFICATION} / {@code RECRUITMENT_PENALTY_APPLIED}）
     * @return 作成した通知（採番 id 付き）
     */
    public List<NotificationEntity> insertAppNotifications(ConfirmableNotificationEntity notification,
            List<Long> userIds, String appNotificationType) {
        throw new UnsupportedOperationException("CMP-261002-1559: 試練の骨格。出陣で実装する");
    }
}
