package com.mannschaft.app.effortrecord.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * F06.4.1 GDPR 個人データエクスポート用の原本 1 件。本人が入力した値と定義の識別情報だけを持つ。
 * 内部 hash・共有の内部状態・receipt・他人のデータは含めない。
 */
public record EffortPersonalDataEntry(
        UUID id,
        UUID definitionUuid,
        int definitionRevision,
        String definitionName,
        LocalDate recordedOn,
        String recordTimezone,
        Map<String, String> values,
        String note,
        Instant createdAt,
        Instant updatedAt) {
}
