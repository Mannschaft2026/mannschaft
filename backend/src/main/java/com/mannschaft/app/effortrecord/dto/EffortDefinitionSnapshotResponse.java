package com.mannschaft.app.effortrecord.dto;

import java.time.Instant;

/**
 * F06.4.1 チーム定義 snapshot 登録の応答。definitionToken は 10 分有効の署名 token
 * （organizationId / teamId / definitionUuid / revision / hash / 本人 ID を束縛）。
 */
public record EffortDefinitionSnapshotResponse(
        EffortDefinitionSnapshot definition,
        String definitionToken,
        Instant expiresAt) {
}
