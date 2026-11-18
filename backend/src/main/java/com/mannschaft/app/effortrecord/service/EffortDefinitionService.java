package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.dto.CreateDefinitionSnapshotRequest;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionTokenResponse;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionRevisionEntity;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRepository;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRevisionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F06.4.1 定義 Service。標準プリセットの取得と、原本作成時の定義 snapshot 解決を担う。
 *
 * <p>チーム定義の revision 登録・署名 token 発行（registerTeamSnapshot）は PR-2 で実装する。
 * PR-1 の原本作成は標準プリセットだけを受け付ける（TEAM 定義は token を検証できないため 400 で拒否する）。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortDefinitionService {

    private final EffortDefinitionRepository definitions;
    private final EffortDefinitionRevisionRepository revisions;
    private final EffortSnapshotCodec codec;

    /** 原本作成時に解決した定義。snapshotJson は revision の保存 JSON（原本へ複写する正本）。 */
    public record ResolvedDefinition(EffortDefinitionEntity definition, int revision,
                                     EffortDefinitionSnapshot snapshot, String snapshotJson) {
    }

    /** 標準プリセット 3 種（STUDY_V1 / EXERCISE_V1 / WEIGHT_V1）を返す。所属不要。固定 UUID 昇順。 */
    @Transactional(readOnly = true)
    public List<EffortDefinitionSnapshot> listPresets() {
        List<EffortDefinitionEntity> presets = definitions.findBySourceTypeOrderBySourceKeyAsc(EffortSourceType.PRESET);
        Map<String, EffortDefinitionRevisionEntity> byKey = revisions
                .findByDefinitionIdIn(presets.stream().map(EffortDefinitionEntity::getId).toList()).stream()
                .collect(Collectors.toMap(r -> r.getDefinitionId() + "#" + r.getRevision(), Function.identity()));
        return presets.stream()
                .sorted(Comparator.comparing(EffortDefinitionEntity::getId))
                .map(def -> {
                    EffortDefinitionRevisionEntity revision = byKey.get(def.getId() + "#" + def.getCurrentRevision());
                    if (revision == null) {
                        throw new IllegalStateException("標準プリセットの revision がありません: " + def.getSourceKey());
                    }
                    return codec.parseSnapshot(revision.getDefinitionJson());
                })
                .toList();
    }

    /**
     * チーム定義の不変 revision を登録し、10 分有効の definitionToken を返す（API 契約 §3 / §7、AC-19）。
     *
     * <p>試練骨格（PR-2）。契約: 現在所属＋TEAM template の scopeId 一致（非所属 403 COMMON_002、
     * 別チーム・不在 template 404 EFFORT_001、廃止 409 EFFORT_002）。activity の現定義と指標設定の canonical hash が
     * 現行 revision と同じなら revision を増やさず、異なれば同一 effort TX で新 revision を発行する。
     * token の有効期限は utcClock 基準で 10 分。原本は作らない。</p>
     */
    public EffortDefinitionTokenResponse registerTeamSnapshot(Long userId, CreateDefinitionSnapshotRequest request) {
        throw new UnsupportedOperationException("F06.4.1 PR-2 試練骨格: definition-snapshots は未実装");
    }

    /**
     * 原本作成用に定義 revision を解決する。標準プリセットのみ。
     * 不明な定義・revision、および TEAM 定義（PR-2 まで未対応）は 400（fieldErrors 付き）。
     */
    @Transactional(readOnly = true)
    public ResolvedDefinition resolveForCreate(UUID definitionUuid, int revision) {
        EffortDefinitionEntity definition = definitions.findById(definitionUuid)
                .orElseThrow(() -> invalid("definitionUuid", "定義が見つかりません"));
        if (definition.getSourceType() != EffortSourceType.PRESET) {
            throw invalid("definitionToken", "チーム定義の記録は現在対応していません");
        }
        EffortDefinitionRevisionEntity found = revisions.findByDefinitionIdAndRevision(definitionUuid, revision)
                .orElseThrow(() -> invalid("definitionRevision", "定義の版が見つかりません"));
        return new ResolvedDefinition(definition, revision, codec.parseSnapshot(found.getDefinitionJson()),
                found.getDefinitionJson());
    }

    /** 集計用: 定義と現行 revision の snapshot。不在は empty。 */
    @Transactional(readOnly = true)
    public java.util.Optional<ResolvedDefinition> findCurrent(UUID definitionUuid) {
        return definitions.findById(definitionUuid).flatMap(definition ->
                revisions.findByDefinitionIdAndRevision(definitionUuid, definition.getCurrentRevision())
                        .map(r -> new ResolvedDefinition(definition, r.getRevision(),
                                codec.parseSnapshot(r.getDefinitionJson()), r.getDefinitionJson())));
    }

    /** 集計用: 指定 revision の snapshot（原本が凍結した版）。 */
    @Transactional(readOnly = true)
    public java.util.Optional<EffortDefinitionSnapshot> findRevision(UUID definitionUuid, int revision) {
        return revisions.findByDefinitionIdAndRevision(definitionUuid, revision)
                .map(r -> codec.parseSnapshot(r.getDefinitionJson()));
    }

    private static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
    }
}
