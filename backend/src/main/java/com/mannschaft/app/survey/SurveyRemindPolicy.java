package com.mannschaft.app.survey;

import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;

import java.time.Duration;
import java.time.Instant;
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
     * @param lastRemindedAt 前回送信の瞬間（未送信は null）
     * @param now            現在の瞬間
     * @return クールダウン中なら true
     */
    public static boolean isInCooldown(Instant lastRemindedAt, Instant now) {
        return lastRemindedAt != null
                && Duration.between(lastRemindedAt, now).toHours() < REMIND_COOLDOWN_HOURS;
    }

    /**
     * Entity が保持するサーバー壁時計（{@code last_reminded_at}）を瞬間へ戻す。
     *
     * @param serverWallClock JPA が返すサーバー既定ゾーンの壁時計（null 可）
     * @return 瞬間。null なら null
     */
    public static Instant toInstant(LocalDateTime serverWallClock) {
        return serverWallClock == null ? null
                : serverWallClock.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant();
    }

    /**
     * 次に督促を送れる時刻を返す。未送信・クールダウン経過済み・上限到達のいずれかなら null。
     *
     * @param lastRemindedAt    前回送信の瞬間
     * @param manualRemindCount 送信済み回数
     * @param now               現在の瞬間
     * @return 次回送信可能時刻、または null
     */
    public static Instant nextRemindableAt(Instant lastRemindedAt, Integer manualRemindCount,
                                           Instant now) {
        if (manualRemindCount != null && manualRemindCount >= MAX_MANUAL_REMIND_COUNT) {
            return null;
        }
        if (!isInCooldown(lastRemindedAt, now)) {
            return null;
        }
        return lastRemindedAt.plus(Duration.ofHours(REMIND_COOLDOWN_HOURS));
    }
}
