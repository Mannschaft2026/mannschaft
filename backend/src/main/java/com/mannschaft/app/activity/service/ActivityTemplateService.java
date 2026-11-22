package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityErrorCode;
import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.FieldType;
import com.mannschaft.app.activity.dto.ActivityTemplateResponse;
import com.mannschaft.app.activity.dto.CreateTemplateRequest;
import com.mannschaft.app.activity.dto.DuplicateTemplateRequest;
import com.mannschaft.app.activity.dto.ImportTemplateRequest;
import com.mannschaft.app.activity.dto.UpdateTemplateRequest;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.entity.SystemActivityTemplatePresetEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.activity.repository.SystemActivityTemplatePresetRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.EnumInputParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 活動テンプレートサービス。テンプレートのCRUD・複製・インポートを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityTemplateService {

    private static final int MAX_TEMPLATES_PER_SCOPE = 20;
    private static final int MAX_FIELDS_PER_TEMPLATE = 15;

    private final ActivityTemplateRepository templateRepository;
    private final ActivityTemplateFieldRepository fieldRepository;
    private final SystemActivityTemplatePresetRepository presetRepository;
    private final ActivityMapper activityMapper;
    private final ObjectMapper objectMapper;
    private final ActivityScopeAccessGuard scopeAccessGuard;

    /**
     * テンプレート一覧を取得する。
     *
     * <p>認可: 対象スコープの会員のみ閲覧可（非会員は 403 = COMMON_002）。
     * 他スコープ会員によるテンプレート列挙（IDOR）を封じる。</p>
     */
    public List<ActivityTemplateResponse> listTemplates(Long userId, ActivityScopeType scopeType, Long scopeId) {
        checkScopeMembership(userId, scopeType, scopeId);
        List<ActivityTemplateEntity> templates =
                templateRepository.findByScopeTypeAndScopeIdOrderBySortOrderAsc(scopeType, scopeId);
        if (templates.isEmpty()) {
            return List.of();
        }
        // 項目はテンプレート件数に関わらず 1 クエリで一括取得する（N+1 回避）
        Map<Long, List<ActivityTemplateFieldEntity>> fieldsByTemplate = fieldRepository
                .findByTemplateIdInOrderBySortOrderAsc(templates.stream().map(ActivityTemplateEntity::getId).toList())
                .stream().collect(Collectors.groupingBy(ActivityTemplateFieldEntity::getTemplateId));
        return templates.stream()
                .map(t -> toResponse(t, fieldsByTemplate.getOrDefault(t.getId(), List.of())))
                .collect(Collectors.toList());
    }

    /**
     * テンプレート詳細を取得する。
     *
     * <p>認可: テンプレートが属するスコープの会員のみ閲覧可（非会員は 403 = COMMON_002）。</p>
     */
    public ActivityTemplateResponse getTemplate(Long id, Long userId) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        checkScopeMembership(userId, entity.getScopeType(), entity.getScopeId());
        return toResponseWithFields(entity);
    }

    /**
     * テンプレートを作成する。
     */
    @Transactional
    public ActivityTemplateResponse createTemplate(Long userId, ActivityScopeType scopeType,
                                                    Long scopeId, CreateTemplateRequest request) {
        // 認可: 管理者（ADMIN/DEPUTY_ADMIN）のみ作成可。非管理者は403
        checkScopeAdmin(userId, scopeType, scopeId);
        // 上限チェック
        long count = templateRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (count >= MAX_TEMPLATES_PER_SCOPE) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        // フィールド数チェック
        if (request.getFields() != null) {
            checkFieldLimit(request.getFields());
            List<ErrorResponse.FieldError> errors = new ArrayList<>();
            validateFieldKeys(request.getFields(), Set.of(), errors);
            throwIfAny(errors);
        }

        ActivityVisibility visibility = request.getDefaultVisibility() != null
                ? EnumInputParser.parse(ActivityVisibility.class, request.getDefaultVisibility(), "defaultVisibility") : ActivityVisibility.MEMBERS_ONLY;

        ActivityTemplateEntity entity = ActivityTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(request.getName())
                .description(request.getDescription())
                .icon(request.getIcon())
                .color(request.getColor())
                .isParticipantRequired(request.getIsParticipantRequired() != null
                        ? request.getIsParticipantRequired() : true)
                .defaultVisibility(visibility)
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(entity);

        // フィールド作成
        if (request.getFields() != null) {
            int order = 0;
            for (CreateTemplateRequest.TemplateFieldInput field : request.getFields()) {
                ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                        .templateId(saved.getId())
                        .fieldKey(field.getFieldKey())
                        .fieldLabel(field.getFieldLabel())
                        .fieldType(FieldType.valueOf(field.getFieldType()))
                        .isRequired(field.getIsRequired() != null && field.getIsRequired())
                        .optionsJson(field.getOptionsJson())
                        .placeholder(field.getPlaceholder())
                        .unit(field.getUnit())
                        .isAggregatable(field.getIsAggregatable() != null && field.getIsAggregatable())
                        .sortOrder(field.getSortOrder() != null ? field.getSortOrder() : order++)
                        .build();
                fieldRepository.save(fieldEntity);
            }
        }

        log.info("テンプレート作成: templateId={}, name={}", saved.getId(), saved.getName());
        return toResponseWithFields(saved);
    }

    /**
     * テンプレートを更新する。
     */
    @Transactional
    public ActivityTemplateResponse updateTemplate(Long id, Long userId, UpdateTemplateRequest request) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        // 認可: 管理者（ADMIN/DEPUTY_ADMIN）のみ更新可。非管理者は403
        checkScopeAdmin(userId, entity.getScopeType(), entity.getScopeId());
        ActivityVisibility visibility = request.getDefaultVisibility() != null
                ? EnumInputParser.parse(ActivityVisibility.class, request.getDefaultVisibility(), "defaultVisibility") : entity.getDefaultVisibility();

        // 検証はすべて変更の前に行う（失敗時に途中まで変えた状態を残さない）
        List<ActivityTemplateFieldEntity> existingFields = fieldRepository.findByTemplateIdOrderBySortOrderAsc(id);
        validateFieldUpdate(request, existingFields);

        entity.update(request.getName(), request.getDescription(), request.getIcon(),
                request.getColor(),
                request.getIsParticipantRequired() != null ? request.getIsParticipantRequired() : entity.getIsParticipantRequired(),
                visibility, entity.getSortOrder());

        // フィールドの更新（field_key ベースの差分更新）。fields 省略時は項目に触れない
        if (request.getFields() != null) {
            applyFieldChanges(id, request, existingFields);
        }

        ActivityTemplateEntity saved = templateRepository.save(entity);
        log.info("テンプレート更新: templateId={}", id);
        return toResponseWithFields(saved);
    }

    // ── fieldKey 契約（F06.4.1 設計書 §7.1） ──

    /** 新規項目の fieldKey の書式。既存キー（旧書式を含む）は検査しない。 */
    private static final java.util.regex.Pattern NEW_FIELD_KEY_PATTERN =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]{0,49}");

    private void checkFieldLimit(List<CreateTemplateRequest.TemplateFieldInput> inputs) {
        if (inputs.size() > MAX_FIELDS_PER_TEMPLATE) {
            throw new BusinessException(ActivityErrorCode.FIELD_LIMIT_EXCEEDED,
                    List.of(new ErrorResponse.FieldError("fields",
                            "項目は" + MAX_FIELDS_PER_TEMPLATE + "個までです")));
        }
    }

    /** fieldKey の重複と、既存に無いキー（新規項目）の書式を検査する。 */
    private void validateFieldKeys(List<CreateTemplateRequest.TemplateFieldInput> inputs,
                                   Set<String> existingKeys, List<ErrorResponse.FieldError> errors) {
        Map<String, List<Integer>> indexesByKey = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            indexesByKey.computeIfAbsent(inputs.get(i).getFieldKey(), k -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < inputs.size(); i++) {
            String key = inputs.get(i).getFieldKey();
            String path = "fields[" + i + "].fieldKey";
            if (indexesByKey.get(key).size() > 1) {
                errors.add(new ErrorResponse.FieldError(path, "fieldKey が重複しています"));
            } else if (!existingKeys.contains(key) && !NEW_FIELD_KEY_PATTERN.matcher(key).matches()) {
                errors.add(new ErrorResponse.FieldError(path,
                        "新規項目の fieldKey は英小文字で始まる英小文字・数字・アンダースコアの50字以内です"));
            }
        }
    }

    private void throwIfAny(List<ErrorResponse.FieldError> errors) {
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, errors);
        }
    }

    /** 更新リクエストの項目まわりをすべて検証する（変更前に呼ぶ）。 */
    private void validateFieldUpdate(UpdateTemplateRequest request, List<ActivityTemplateFieldEntity> existingFields) {
        List<String> removed = request.getRemovedFieldKeys() != null ? request.getRemovedFieldKeys() : List.of();
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (request.getFields() == null) {
            for (int i = 0; i < removed.size(); i++) {
                errors.add(new ErrorResponse.FieldError("removedFieldKeys[" + i + "]",
                        "fields を省略したときは removedFieldKeys を指定できません"));
            }
            throwIfAny(errors);
            return;
        }
        List<CreateTemplateRequest.TemplateFieldInput> inputs = request.getFields();
        checkFieldLimit(inputs);

        Map<String, ActivityTemplateFieldEntity> existingByKey = existingFields.stream()
                .collect(Collectors.toMap(ActivityTemplateFieldEntity::getFieldKey, Function.identity(), (a, b) -> a));
        validateFieldKeys(inputs, existingByKey.keySet(), errors);

        Set<String> requestKeys = inputs.stream().map(CreateTemplateRequest.TemplateFieldInput::getFieldKey)
                .collect(Collectors.toSet());
        Set<String> seenRemoved = new HashSet<>();
        for (int i = 0; i < removed.size(); i++) {
            String key = removed.get(i);
            if (!existingByKey.containsKey(key)) {
                errors.add(new ErrorResponse.FieldError("removedFieldKeys[" + i + "]",
                        "removedFieldKeys に既存項目に無い fieldKey があります"));
            } else if (requestKeys.contains(key) || !seenRemoved.add(key)) {
                errors.add(new ErrorResponse.FieldError("removedFieldKeys[" + i + "]",
                        "removedFieldKeys の fieldKey が fields と重複しています"));
            }
        }
        // 照合: 既存キーは fields か removedFieldKeys のどちらかに必ず含まれる
        List<String> missing = existingFields.stream().map(ActivityTemplateFieldEntity::getFieldKey)
                .filter(k -> !requestKeys.contains(k) && !removed.contains(k)).toList();
        if (!missing.isEmpty()) {
            errors.add(new ErrorResponse.FieldError("fields",
                    "既存項目が fields にも removedFieldKeys にもありません: " + String.join(", ", missing)));
        }
        throwIfAny(errors);

        // 型変更は検証の最後（変更前）に弾く
        for (CreateTemplateRequest.TemplateFieldInput input : inputs) {
            ActivityTemplateFieldEntity existing = existingByKey.get(input.getFieldKey());
            if (existing != null && !existing.getFieldType().name().equals(input.getFieldType())) {
                throw new BusinessException(ActivityErrorCode.FIELD_TYPE_CHANGE_NOT_ALLOWED);
            }
        }
    }

    /** 検証済みの項目変更を適用する。removedFieldKeys の項目だけを削除し、既存は fieldKey で更新、無いキーは追加する。 */
    private void applyFieldChanges(Long id, UpdateTemplateRequest request,
                                   List<ActivityTemplateFieldEntity> existingFields) {
        List<String> removed = request.getRemovedFieldKeys() != null ? request.getRemovedFieldKeys() : List.of();
        Map<String, ActivityTemplateFieldEntity> existingByKey = existingFields.stream()
                .collect(Collectors.toMap(ActivityTemplateFieldEntity::getFieldKey, Function.identity(), (a, b) -> a));
        for (ActivityTemplateFieldEntity existing : existingFields) {
            if (removed.contains(existing.getFieldKey())) {
                fieldRepository.delete(existing);
            }
        }
        int order = 0;
        for (CreateTemplateRequest.TemplateFieldInput field : request.getFields()) {
            ActivityTemplateFieldEntity existing = existingByKey.get(field.getFieldKey());
            if (existing != null) {
                existing.update(
                        field.getFieldLabel(),
                        field.getIsRequired() != null ? field.getIsRequired() : existing.getIsRequired(),
                        field.getOptionsJson(),
                        field.getPlaceholder(),
                        field.getUnit(),
                        field.getIsAggregatable() != null ? field.getIsAggregatable() : existing.getIsAggregatable(),
                        field.getSortOrder() != null ? field.getSortOrder() : order++
                );
                fieldRepository.save(existing);
            } else {
                fieldRepository.save(ActivityTemplateFieldEntity.builder()
                        .templateId(id)
                        .fieldKey(field.getFieldKey())
                        .fieldLabel(field.getFieldLabel())
                        .fieldType(FieldType.valueOf(field.getFieldType()))
                        .isRequired(field.getIsRequired() != null && field.getIsRequired())
                        .optionsJson(field.getOptionsJson())
                        .placeholder(field.getPlaceholder())
                        .unit(field.getUnit())
                        .isAggregatable(field.getIsAggregatable() != null && field.getIsAggregatable())
                        .sortOrder(field.getSortOrder() != null ? field.getSortOrder() : order++)
                        .build());
            }
        }
    }

    /**
     * テンプレートを論理削除する。
     */
    @Transactional
    public void deleteTemplate(Long id, Long userId) {
        ActivityTemplateEntity entity = findTemplateOrThrow(id);
        // 認可: 管理者（ADMIN/DEPUTY_ADMIN）のみ削除可。非管理者は403
        checkScopeAdmin(userId, entity.getScopeType(), entity.getScopeId());
        entity.softDelete();
        templateRepository.save(entity);
        log.info("テンプレート削除: templateId={}", id);
    }

    /**
     * テンプレートを別スコープにコピーする。
     */
    @Transactional
    public ActivityTemplateResponse duplicateTemplate(Long id, Long userId, DuplicateTemplateRequest request) {
        ActivityTemplateEntity source = findTemplateOrThrow(id);
        // 認可: コピー元スコープの管理者（ADMIN/DEPUTY_ADMIN）のみ複製可。非管理者は403
        checkScopeAdmin(userId, source.getScopeType(), source.getScopeId());
        ActivityScopeType targetScopeType = EnumInputParser.parse(ActivityScopeType.class, request.getTargetScopeType(), "targetScopeType");
        Long targetScopeId = request.getTargetScopeId();
        // 認可: コピー先スコープの管理者のみ書き込み可。非管理者は403
        // （コピー元だけでなく書き込み先も検証し、任意スコープへの書き込みを封じる）
        checkScopeAdmin(userId, targetScopeType, targetScopeId);

        // コピー先の上限チェック
        long count = templateRepository.countByScopeTypeAndScopeId(targetScopeType, targetScopeId);
        if (count >= MAX_TEMPLATES_PER_SCOPE) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ActivityTemplateEntity newTemplate = ActivityTemplateEntity.builder()
                .scopeType(targetScopeType)
                .scopeId(targetScopeId)
                .name(source.getName() + "（コピー）")
                .description(source.getDescription())
                .icon(source.getIcon())
                .color(source.getColor())
                .isParticipantRequired(source.getIsParticipantRequired())
                .defaultVisibility(source.getDefaultVisibility())
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(newTemplate);

        // フィールドのコピー
        List<ActivityTemplateFieldEntity> sourceFields =
                fieldRepository.findByTemplateIdOrderBySortOrderAsc(source.getId());
        for (ActivityTemplateFieldEntity field : sourceFields) {
            ActivityTemplateFieldEntity copy = ActivityTemplateFieldEntity.builder()
                    .templateId(saved.getId())
                    .fieldKey(field.getFieldKey())
                    .fieldLabel(field.getFieldLabel())
                    .fieldType(field.getFieldType())
                    .isRequired(field.getIsRequired())
                    .optionsJson(field.getOptionsJson())
                    .placeholder(field.getPlaceholder())
                    .unit(field.getUnit())
                    .isAggregatable(field.getIsAggregatable())
                    .sortOrder(field.getSortOrder())
                    .build();
            fieldRepository.save(copy);
        }

        log.info("テンプレート複製: sourceId={}, newId={}, targetScope={}:{}", id, saved.getId(),
                targetScopeType, targetScopeId);
        return toResponseWithFields(saved);
    }

    /**
     * プリセットテンプレートをスコープにインポートする。
     */
    @Transactional
    public ActivityTemplateResponse importPreset(Long userId, ImportTemplateRequest request) {
        SystemActivityTemplatePresetEntity preset = presetRepository.findById(request.getPresetId())
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.PRESET_NOT_FOUND));

        ActivityScopeType scopeType = EnumInputParser.parse(ActivityScopeType.class, request.getScopeType(), "scopeType");
        Long scopeId = request.getScopeId();

        // 認可: インポート先スコープの管理者（ADMIN/DEPUTY_ADMIN）のみ実行可。非管理者は403
        checkScopeAdmin(userId, scopeType, scopeId);

        // 上限チェック
        long count = templateRepository.countByScopeTypeAndScopeId(scopeType, scopeId);
        if (count >= MAX_TEMPLATES_PER_SCOPE) {
            throw new BusinessException(ActivityErrorCode.TEMPLATE_LIMIT_EXCEEDED);
        }

        ActivityVisibility visibility = ActivityVisibility.valueOf(preset.getDefaultVisibility());

        ActivityTemplateEntity newTemplate = ActivityTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name(preset.getName())
                .description(preset.getDescription())
                .icon(preset.getIcon())
                .color(preset.getColor())
                .isParticipantRequired(preset.getIsParticipantRequired())
                .defaultVisibility(visibility)
                .createdBy(userId)
                .build();

        ActivityTemplateEntity saved = templateRepository.save(newTemplate);

        // fields_json を展開して activity_template_fields に INSERT
        try {
            List<Map<String, Object>> fields = objectMapper.readValue(
                    preset.getFieldsJson(), new TypeReference<>() {});
            int order = 0;
            for (Map<String, Object> field : fields) {
                ActivityTemplateFieldEntity fieldEntity = ActivityTemplateFieldEntity.builder()
                        .templateId(saved.getId())
                        .fieldKey((String) field.get("field_key"))
                        .fieldLabel((String) field.get("field_label"))
                        .fieldType(FieldType.valueOf((String) field.get("field_type")))
                        .isRequired(field.get("is_required") != null && (Boolean) field.get("is_required"))
                        .optionsJson(field.get("options_json") != null
                                ? objectMapper.writeValueAsString(field.get("options_json")) : null)
                        .placeholder((String) field.get("placeholder"))
                        .unit((String) field.get("unit"))
                        .isAggregatable(field.get("is_aggregatable") != null && (Boolean) field.get("is_aggregatable"))
                        .sortOrder(field.get("sort_order") != null
                                ? ((Number) field.get("sort_order")).intValue() : order++)
                        .build();
                fieldRepository.save(fieldEntity);
            }
        } catch (Exception e) {
            throw new RuntimeException("プリセットのfields_jsonのパースに失敗しました", e);
        }

        log.info("プリセットインポート: presetId={}, newTemplateId={}", preset.getId(), saved.getId());
        return toResponseWithFields(saved);
    }

    /**
     * テンプレートエンティティを取得する。存在しない場合は例外をスローする。
     */
    ActivityTemplateEntity findTemplateOrThrow(Long id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ActivityErrorCode.TEMPLATE_NOT_FOUND));
    }

    /**
     * スコープ会員であることを検証する（参照系・作成系）。
     *
     * <p>判定はスコープ種別を網羅的にディスパッチする {@link ActivityScopeAccessGuard} に委譲する。</p>
     */
    private void checkScopeMembership(Long userId, ActivityScopeType scopeType, Long scopeId) {
        scopeAccessGuard.checkMembership(userId, scopeType, scopeId);
    }

    /**
     * スコープ管理者（ADMIN/DEPUTY_ADMIN）であることを検証する（更新・削除系）。
     */
    private void checkScopeAdmin(Long userId, ActivityScopeType scopeType, Long scopeId) {
        scopeAccessGuard.checkAdminOrAbove(userId, scopeType, scopeId);
    }

    /**
     * テンプレートレスポンスにフィールド定義を付与して返す。
     */
    public List<ActivityTemplateResponse.TemplateFieldResponse> fieldsIfPresent(Long id, ActivityScopeType type, Long scopeId) {
        if (templateRepository.findById(id)
                .filter(t -> t.getScopeType() == type && java.util.Objects.equals(t.getScopeId(), scopeId)).isEmpty()) return List.of();
        return activityMapper.toTemplateFieldResponseList(fieldRepository.findByTemplateIdOrderBySortOrderAsc(id));
    }

    /**
     * 指定スコープに属する有効なテンプレートをフィールド定義付きで返す（他ドメイン向けの照会。F06.4.1 個人の頑張り記録）。
     *
     * <p>認可は行わない。呼び出し側が自ドメインの認可（所属・テンプレート管理権限）を済ませてから呼ぶこと。
     * 不在・論理削除済み・別スコープは区別せず empty を返す（存在を呼び出し側の応答で漏らさないため）。
     * 照会はテンプレート1件＋フィールド一括の2クエリで、フィールド数に比例しない。</p>
     */
    public java.util.Optional<ActivityTemplateResponse> findInScope(Long id, ActivityScopeType type, Long scopeId) {
        return templateRepository.findById(id)
                .filter(t -> t.getScopeType() == type && java.util.Objects.equals(t.getScopeId(), scopeId))
                .map(this::toResponseWithFields);
    }

    private ActivityTemplateResponse toResponseWithFields(ActivityTemplateEntity entity) {
        return toResponse(entity, fieldRepository.findByTemplateIdOrderBySortOrderAsc(entity.getId()));
    }

    private ActivityTemplateResponse toResponse(ActivityTemplateEntity entity, List<ActivityTemplateFieldEntity> fields) {
        List<ActivityTemplateResponse.TemplateFieldResponse> fieldResponses =
                activityMapper.toTemplateFieldResponseList(fields);
        return new ActivityTemplateResponse(
                entity.getId(), entity.getScopeType().name(),
                entity.getScopeId(),
                entity.getName(), entity.getDescription(),
                entity.getIcon(), entity.getColor(),
                entity.getIsParticipantRequired(),
                entity.getDefaultVisibility().name(),
                entity.getSortOrder(),
                fieldResponses, entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
