package com.mannschaft.app.notification;

import java.util.Set;

/**
 * 利用者の通知設定（スコープ・種別の OFF）に関わらず必ず届ける通知種別（安全上必須の通知）。
 *
 * <ul>
 *   <li>確認型通知（{@code CONFIRMABLE_NOTIFICATION*}・前方一致）: 受領確認を求めるため。</li>
 *   <li>F10.9 サポート閲覧の本人・保護者への通知（依頼・閲覧開始）: 運営が本人の画面を見る許可の依頼と、
 *       実際に見始めたことを本人が知らないまま進むことを防ぐため（許可・取消の判断材料であり、OFF にできてはならない）。</li>
 * </ul>
 *
 * <p>新しい種別を足すのは「OFF にされると利用者の安全・権利が損なわれる」通知に限る。</p>
 */
public final class MandatoryNotificationTypes {

    private static final String CONFIRMABLE_PREFIX = "CONFIRMABLE_NOTIFICATION";

    /** F10.9 サポート閲覧の本人・保護者宛（{@code SupportAccessNotificationKind} の本人側と対応）。 */
    public static final Set<String> SUPPORT_ACCESS_SAFETY_TYPES = Set.of(
            "SUPPORT_ACCESS_REQUESTED_TO_TARGET",
            "SUPPORT_ACCESS_REQUESTED_TO_GUARDIAN",
            "SUPPORT_ACCESS_SESSION_STARTED_TO_TARGET");

    private MandatoryNotificationTypes() {
    }

    /** opt-out を無視して必ず配信する種別か。 */
    public static boolean isMandatory(String notificationType) {
        if (notificationType == null) {
            return false;
        }
        return notificationType.startsWith(CONFIRMABLE_PREFIX) || SUPPORT_ACCESS_SAFETY_TYPES.contains(notificationType);
    }
}
