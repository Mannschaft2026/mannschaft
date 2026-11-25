package com.mannschaft.app.shift.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * シフト枠更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor(onConstructor_ = @JsonCreator)
public class UpdateShiftSlotRequest implements StrictShiftRequest {

    private final LocalDate slotDate;

    private final LocalTime startTime;

    private final LocalTime endTime;

    private final Long positionId;

    private final Integer requiredCount;

    @Size(max = 200)
    private final List<Long> assignedUserIds;

    @Size(max = 200)
    private final String note;

    /**
     * 翌日終了（日跨ぎ）か。設計 F03.5 §11.2.5 規則5。
     *
     * <p>部分更新のため {@code null} は「現値維持」を意味する。</p>
     */
    private final Boolean endsNextDay;

    /** 枠の日付変更に伴い、紐付く希望も移動することを確認したか。省略は未確認。 */
    @Schema(description = "枠の日付変更に伴う希望の日付移動を確認したか。省略・null・false は未確認")
    private final Boolean moveRequestsConfirmed;

    /**
     * 希望移動の確認を省略する既存呼び出し用。
     *
     * @param slotDate 対象日
     * @param startTime 開始時刻
     * @param endTime 終了時刻
     * @param positionId ポジションID
     * @param requiredCount 必要人数
     * @param assignedUserIds 割当ユーザーID
     * @param note メモ
     * @param endsNextDay 翌日終了か（null は現値維持）
     */
    public UpdateShiftSlotRequest(LocalDate slotDate, LocalTime startTime, LocalTime endTime,
                                  Long positionId, Integer requiredCount,
                                  List<Long> assignedUserIds, String note, Boolean endsNextDay) {
        this(slotDate, startTime, endTime, positionId, requiredCount,
                assignedUserIds, note, endsNextDay, null);
    }

    /**
     * 日跨ぎ指定なしの互換コンストラクタ（{@code endsNextDay} 据え置き）。
     *
     * @param slotDate        対象日
     * @param startTime       開始時刻
     * @param endTime         終了時刻
     * @param positionId      ポジションID
     * @param requiredCount   必要人数
     * @param assignedUserIds 割当ユーザーID
     * @param note            メモ
     */
    public UpdateShiftSlotRequest(LocalDate slotDate, LocalTime startTime, LocalTime endTime,
                                  Long positionId, Integer requiredCount,
                                  List<Long> assignedUserIds, String note) {
        this(slotDate, startTime, endTime, positionId, requiredCount, assignedUserIds, note, null);
    }
}
