package com.mannschaft.app.supportaccess.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 閲覧開始の応答。
 *
 * @param remainingSeconds 応答時点での許可の残り秒数（BE の時刻基準）。FE は端末の時計ではなくこの値を基準に
 *                         残り時間を数える（端末の時計のずれで期限の表示が狂わないようにする）
 */
public record SupportAccessSessionResponse(
        UUID grantId,
        Long targetUserId,
        String targetDisplayName,
        Instant expiresAt,
        @Schema(description = "応答時点での許可の残り秒数（BE の時刻基準）。FE はこの値を基準に残り時間を表示する")
        long remainingSeconds) {
}
