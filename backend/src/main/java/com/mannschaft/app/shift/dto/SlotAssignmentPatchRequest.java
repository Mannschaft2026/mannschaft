package com.mannschaft.app.shift.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * スロット差分割当リクエストDTO。
 *
 * @param addUserIds    追加するユーザーIDリスト
 * @param removeUserIds 削除するユーザーIDリスト
 * @param slotVersion   楽観ロック用スロットバージョン
 */
public record SlotAssignmentPatchRequest(
        @Size(max = 200) List<Long> addUserIds,
        @Size(max = 200) List<Long> removeUserIds,
        @NotNull Integer slotVersion
) implements StrictShiftRequest {

    /** 追加・削除のどちらも空の差分は何も変えない要求なので入力エラーにする（CMP-260910-1607）。 */
    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "{shift.validation.assignment_diff_required}")
    public boolean isDiffSpecified() {
        return (addUserIds != null && !addUserIds.isEmpty())
                || (removeUserIds != null && !removeUserIds.isEmpty());
    }
}
