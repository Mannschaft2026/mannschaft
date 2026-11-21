package com.mannschaft.app.effortrecord.dto;

import com.mannschaft.app.effortrecord.enums.EffortSourceType;

import java.util.List;
import java.util.UUID;

/**
 * F06.4.1 定義 snapshot（API 契約 §2 DefinitionSnapshot）。原本作成時に複写され、保存後は不変。
 * teamTemplateId は既存 BIGINT のまま（UUID へ置換しない）。
 */
public record EffortDefinitionSnapshot(
        UUID definitionUuid,
        int revision,
        EffortSourceType source,
        String presetCode,
        Long teamTemplateId,
        String templateName,
        String durationFieldKey,
        String subjectFieldKey,
        List<EffortFieldDefinition> fields) {
}
