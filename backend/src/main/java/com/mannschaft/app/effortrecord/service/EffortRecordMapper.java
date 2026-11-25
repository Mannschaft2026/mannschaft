package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortFieldValue;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortOwnerShare;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F06.4.1 原本 Entity → 本人向けレスポンス（API 契約 §2 OwnerRecord）。
 *
 * <p>values は<b>保存済み snapshot の field 順</b>で返す（MySQL の JSON 列は読み出し時にキー順を変えるため、
 * values_json の順序には依存しない）。nullable 項目も省略せず null で返す。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortRecordMapper {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() { };

    private final EffortSnapshotCodec codec;
    private final ObjectMapper objectMapper;

    public EffortOwnerRecordResponse toResponse(EffortRecordEntity entity) {
        EffortDefinitionSnapshot definition = codec.parseSnapshot(entity.getDefinitionSnapshot());
        Map<String, String> stored = codec.parseValues(entity.getValuesJson());
        List<EffortFieldValue> values = new ArrayList<>(definition.fields().size());
        definition.fields().forEach(field -> values.add(new EffortFieldValue(field.fieldKey(), stored.get(field.fieldKey()))));
        return new EffortOwnerRecordResponse(entity.getId(), entity.getVersion(), entity.getContentRevision(),
                entity.getRecordedOn(), entity.getRecordTimezone(), definition, values, entity.getPrivateNote(),
                toShare(entity), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private EffortOwnerShare toShare(EffortRecordEntity entity) {
        if (entity.getShareId() == null) {
            return null;
        }
        List<String> selected = List.of();
        if (entity.getSelectedFieldKeys() != null) {
            try {
                selected = objectMapper.readValue(entity.getSelectedFieldKeys(), STRINGS);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("保存済みの共有項目を読み込めません", e);
            }
        }
        return new EffortOwnerShare(entity.getShareId(), entity.getShareTeamId(), entity.getShareState(),
                entity.getShareVersion(), entity.getSyncedSourceVersion() == null ? 0L : entity.getSyncedSourceVersion(),
                entity.getGrantMembershipId(), selected, entity.getLastErrorCode(), entity.getShareUpdatedAt());
    }
}
