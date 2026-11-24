package com.mannschaft.app.role.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 再招待依頼の一覧の 1 件（CMP-261107-1833 PR3 設計 i）。依頼者のメールアドレス・招待のハッシュは含めない。
 *
 * @param id                   保留（再招待依頼）の ID
 * @param requesterDisplayName 依頼者の表示名（利用者入力。FE は補間のみで表示する）
 * @param requesterAvatarUrl   依頼者のアバター表示 URL（未設定なら null）
 * @param requestedAt          依頼日時
 * @param roleName             再招待で実際に付与するロール（MEMBER/SUPPORTER）
 */
public record InviteReissueRequestResponse(UUID id, String requesterDisplayName, String requesterAvatarUrl,
                                           Instant requestedAt, String roleName) {
}
