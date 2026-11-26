package com.mannschaft.app.notification.confirmable.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSaveRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSummaryResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftUpdateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableDraftAudienceMode;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDraftEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CMP-261126-1558: 確認通知の下書きサービス。
 *
 * <p>スコープ×作成者ごとに上限10件（11件目は {@code DRAFT_LIMIT_EXCEEDED}・409）。
 * 更新は {@code baseVersion} による楽観ロック（不一致は {@code DRAFT_VERSION_CONFLICT}・409）。
 * 他人・他スコープの draftId は存在しない ID と同じく {@code DRAFT_NOT_FOUND}（404）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationDraftService {

    /** スコープ×作成者ごとの下書きの上限件数。 */
    public static final int MAX_DRAFTS_PER_SCOPE_AND_USER = 10;

    /** 一覧に出す本文の冒頭の文字数。 */
    public static final int BODY_EXCERPT_LENGTH = 100;

    /** selected_targets（JSON 文字列）との相互変換用。状態を持たないため共有してよい。 */
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ConfirmableTargetSpec>> TARGET_LIST = new TypeReference<>() {};

    private final ConfirmableNotificationDraftRepository draftRepository;

    /** 本人・同スコープの下書きを updated_at 降順で返す（本文は冒頭100文字＝コードポイント）。 */
    public List<ConfirmableNotificationDraftSummaryResponse> list(ScopeType scopeType, Long scopeId, Long userId) {
        return draftRepository.findByScopeTypeAndScopeIdAndUserIdOrderByUpdatedAtDesc(scopeType, scopeId, userId)
                .stream()
                .map(d -> ConfirmableNotificationDraftSummaryResponse.builder()
                        .id(d.getId())
                        .title(d.getTitle())
                        .bodyExcerpt(excerpt(d.getBody()))
                        .updatedAt(d.getUpdatedAt())
                        .build())
                .toList();
    }

    /**
     * 下書きを作成する。
     *
     * <p><b>同時作成対策（上限10件）</b>: 件数判定だけだと、同じスコープ×利用者の同時リクエストが
     * 双方とも「9件」と読んで11件目まで入ってしまう。そこで先に、そのスコープ×利用者の既存行を
     * {@code SELECT ... FOR UPDATE}（(scope_type, scope_id, user_id, updated_at) インデックス経由）で
     * 悲観ロックしてから件数判定・INSERT する。行が0件でもギャップロックが掛かるため直列化される。
     * 同時作成が互いのギャップロックで待ち合ってデッドロックになった場合は、MySQL が片方を中断し
     * その例外がそのまま呼び出し元へ伝わる（握りつぶさない。上限を超えて入るよりも失敗させる）。</p>
     */
    @Transactional
    public ConfirmableNotificationDraftDetailResponse create(
            ScopeType scopeType, Long scopeId, Long userId, ConfirmableNotificationDraftSaveRequest request) {
        draftRepository.lockByScopeTypeAndScopeIdAndUserId(scopeType, scopeId, userId);
        long count = draftRepository.countByScopeTypeAndScopeIdAndUserId(scopeType, scopeId, userId);
        if (count >= MAX_DRAFTS_PER_SCOPE_AND_USER) {
            throw new BusinessException(ConfirmableNotificationErrorCode.DRAFT_LIMIT_EXCEEDED);
        }
        ConfirmableNotificationDraftEntity entity = ConfirmableNotificationDraftEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .userId(userId)
                .title(titleOrEmpty(request.getTitle()))
                .body(request.getBody())
                .priority(priorityOrDefault(request.getPriority()))
                .deadlineAt(toStoredPrecision(request.getDeadlineAt()))
                .audienceMode(audienceOrDefault(request.getAudienceMode()))
                .selectedTargets(writeTargets(request.getSelectedTargets()))
                .selectedGroupId(request.getSelectedGroupId())
                .selectedTemplateId(request.getSelectedTemplateId())
                .build();
        return toDetail(draftRepository.save(entity));
    }

    public ConfirmableNotificationDraftDetailResponse get(
            ScopeType scopeType, Long scopeId, Long userId, UUID draftId) {
        return toDetail(findOwned(scopeType, scopeId, userId, draftId));
    }

    /** baseVersion が保存済みの version と一致するときだけ全項目を置き換える。 */
    @Transactional
    public ConfirmableNotificationDraftDetailResponse update(
            ScopeType scopeType, Long scopeId, Long userId, UUID draftId,
            ConfirmableNotificationDraftUpdateRequest request) {
        ConfirmableNotificationDraftEntity entity = findOwned(scopeType, scopeId, userId, draftId);
        if (!Objects.equals(request.getBaseVersion(), entity.getVersion())) {
            throw new BusinessException(ConfirmableNotificationErrorCode.DRAFT_VERSION_CONFLICT);
        }
        // 同じ内容の保存で version を進めないため、DB に保存される形へ揃えてから置き換える
        // （deadline_at は DATETIME(6) のマイクロ秒、selected_targets は MySQL が正規化した JSON 文字列）。
        entity.replaceContent(
                titleOrEmpty(request.getTitle()),
                request.getBody(),
                priorityOrDefault(request.getPriority()),
                toStoredPrecision(request.getDeadlineAt()),
                audienceOrDefault(request.getAudienceMode()),
                keepStoredJsonIfSame(entity.getSelectedTargets(), writeTargets(request.getSelectedTargets())),
                request.getSelectedGroupId(),
                request.getSelectedTemplateId());
        try {
            // flush して、読み取りから書き込みまでの間に別トランザクションが更新した場合の
            // 楽観ロック失敗をここで確定させる（version は DB の UPDATE ... WHERE version = ? で守られる）。
            return toDetail(draftRepository.saveAndFlush(entity));
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new BusinessException(ConfirmableNotificationErrorCode.DRAFT_VERSION_CONFLICT);
        }
    }

    @Transactional
    public void delete(ScopeType scopeType, Long scopeId, Long userId, UUID draftId) {
        long deleted = draftRepository.deleteByIdAndScopeTypeAndScopeIdAndUserId(draftId, scopeType, scopeId, userId);
        if (deleted == 0) {
            throw new BusinessException(ConfirmableNotificationErrorCode.DRAFT_NOT_FOUND);
        }
    }

    private ConfirmableNotificationDraftEntity findOwned(
            ScopeType scopeType, Long scopeId, Long userId, UUID draftId) {
        return draftRepository.findByIdAndScopeTypeAndScopeIdAndUserId(draftId, scopeType, scopeId, userId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.DRAFT_NOT_FOUND));
    }

    private static String titleOrEmpty(String title) {
        return title == null ? "" : title;
    }

    private static ConfirmableNotificationPriority priorityOrDefault(ConfirmableNotificationPriority priority) {
        return priority == null ? ConfirmableNotificationPriority.NORMAL : priority;
    }

    private static ConfirmableDraftAudienceMode audienceOrDefault(ConfirmableDraftAudienceMode mode) {
        return mode == null ? ConfirmableDraftAudienceMode.DEFAULT : mode;
    }

    /** deadline_at 列（DATETIME(6)）の精度へ切り詰める。保存後に読み直した値と比較が一致するようにする。 */
    private static Instant toStoredPrecision(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * MySQL の JSON 列は保存時にキー順・空白を正規化するため、読み直した文字列と新しく書き出した文字列は
     * 同じ内容でも一致しない。意味が同じ（配列の順序を含めて等しい）なら保存済みの文字列をそのまま使い、
     * Hibernate に差分と見なされて version が進むのを防ぐ。
     */
    private static String keepStoredJsonIfSame(String stored, String next) {
        if (stored == null || next == null) {
            return next;
        }
        try {
            return JSON.readTree(stored).equals(JSON.readTree(next)) ? stored : next;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("selected_targets の JSON を読み取れません", e);
        }
    }

    private static String excerpt(String body) {
        if (body == null) {
            return "";
        }
        if (body.codePointCount(0, body.length()) <= BODY_EXCERPT_LENGTH) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, BODY_EXCERPT_LENGTH));
    }

    private static String writeTargets(List<ConfirmableTargetSpec> targets) {
        if (targets == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(targets);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("selected_targets の JSON 化に失敗しました", e);
        }
    }

    private static List<ConfirmableTargetSpec> readTargets(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, TARGET_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("selected_targets の JSON を読み取れません", e);
        }
    }

    private static ConfirmableNotificationDraftDetailResponse toDetail(ConfirmableNotificationDraftEntity d) {
        return ConfirmableNotificationDraftDetailResponse.builder()
                .id(d.getId())
                .title(d.getTitle())
                .body(d.getBody())
                .priority(d.getPriority())
                .deadlineAt(d.getDeadlineAt())
                .audienceMode(d.getAudienceMode())
                .selectedTargets(readTargets(d.getSelectedTargets()))
                .selectedGroupId(d.getSelectedGroupId())
                .selectedTemplateId(d.getSelectedTemplateId())
                .version(d.getVersion())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
