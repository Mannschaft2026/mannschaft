package com.mannschaft.app.effortrecord.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 本人の原本レスポンス（API 契約 §2 OwnerRecord）。
 * nullable 項目（privateNote / share）も省略せず null で返す。
 */
public record EffortOwnerRecordResponse(
        UUID id,
        long version,
        long contentRevision,
        LocalDate recordedOn,
        String recordTimezone,
        EffortDefinitionSnapshot definition,
        List<EffortFieldValue> values,
        String privateNote,
        EffortOwnerShare share,
        Instant createdAt,
        Instant updatedAt) {
}
