package com.mannschaft.app.survey;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * アンケート手動督促の規則（上限回数・クールダウン）の唯一の定義。
 *
 * <p>督促の送信判定（{@code SurveyRemindService}）と、詳細応答で FE へ返す
 * 残り回数・次回送信可能時刻（{@code SurveyMapper}）が同じ値を見るよう、ここに集約する。</p>
 */
public final class SurveyRemindPolicy {

    /** 督促回数の上限（手動リマインド）。 */
    public static final int MAX_MANUAL_REMIND_COUNT = 3;

    /** 督促のクールダウン時間（時間単位）。 */
    public static final long REMIND_COOLDOWN_HOURS = 24L;

    private SurveyRemindPolicy() {
    }

    /**
     * 前回送信から {@link #REMIND_COOLDOWN_HOURS} 時間が経過していないか判定する。
     *
     * @param lastRemindedAt 前回送信時刻（未送信は null）
     * @param now            現在時刻
     * @return クールダウン中なら true
     */
    public static boolean isInCooldown(LocalDateTime lastRemindedAt, LocalDateTime now) {
        return lastRemindedAt != null
                && Duration.between(lastRemindedAt, now).toHours() < REMIND_COOLDOWN_HOURS;
    }

    /**
     * 次に督促を送れる時刻を返す。未送信・クールダウン経過済み・上限到達のいずれかなら null。
     *
     * @param lastRemindedAt    前回送信時刻
     * @param manualRemindCount 送信済み回数
     * @param now               現在時刻
     * @return 次回送信可能時刻、または null
     */
    public static LocalDateTime nextRemindableAt(LocalDateTime lastRemindedAt, Integer manualRemindCount,
                                                  LocalDateTime now) {
        if (manualRemindCount != null && manualRemindCount >= MAX_MANUAL_REMIND_COUNT) {
            return null;
        }
        if (!isInCooldown(lastRemindedAt, now)) {
            return null;
        }
        return lastRemindedAt.plusHours(REMIND_COOLDOWN_HOURS);
    }
}
