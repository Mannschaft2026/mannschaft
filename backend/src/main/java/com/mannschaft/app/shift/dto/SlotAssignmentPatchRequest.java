package com.mannschaft.app.shift.dto;

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
) {}
