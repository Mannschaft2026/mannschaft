package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityStatsSettingsResponse;
import com.mannschaft.app.activity.dto.UpdateActivityStatsSettingsRequest;
import com.mannschaft.app.activity.entity.ActivityStatsSettingsEntity;
import com.mannschaft.app.activity.repository.ActivityStatsSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 活動統計のスコープ別設定（上位参加者をメンバーに公開するか。F06.4 §15.3）。認可は呼び出し側（Controller）で行う。
 *
 * <p>版の不一致は既存の楽観ロック競合（409 {@code COMMON_003}）。行が無いときの作成は {@code version=null} だけを受け付け、
 * 同時の初回作成が一意制約に当たった場合も同じ競合として返す（後から来た側は取り直して再送する）。</p>
 */
@Service
@RequiredArgsConstructor
public class ActivityStatsSettingsService {

    private final ActivityStatsSettingsRepository repository;

    /** 設定を返す。行が無ければ既定（OFF）で {@code version=null}。 */
    @Transactional(readOnly = true)
    public ActivityStatsSettingsResponse get(ActivityScopeType scopeType, Long scopeId) {
        return repository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(ActivityStatsSettingsService::toResponse)
                .orElseGet(() -> new ActivityStatsSettingsResponse(scopeType.name(), scopeId, false, null));
    }

    /** 上位参加者をメンバーに公開するか（行が無ければ false）。 */
    @Transactional(readOnly = true)
    public boolean topParticipantsVisibleToMembers(ActivityScopeType scopeType, Long scopeId) {
        return repository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(ActivityStatsSettingsEntity::isTopParticipantsVisibleToMembers)
                .orElse(false);
    }

    /** 設定を保存する（初回は作成）。 */
    @Transactional
    public ActivityStatsSettingsResponse update(ActivityScopeType scopeType, Long scopeId, Long userId,
                                                UpdateActivityStatsSettingsRequest request) {
        boolean visible = Boolean.TRUE.equals(request.getTopParticipantsVisibleToMembers());
        var existing = repository.findByScopeTypeAndScopeId(scopeType, scopeId);
        if (existing.isEmpty()) {
            if (request.getVersion() != null) {
                throw conflict();
            }
            ActivityStatsSettingsEntity created = ActivityStatsSettingsEntity.builder()
                    .scopeType(scopeType).scopeId(scopeId)
                    .topParticipantsVisibleToMembers(visible).updatedBy(userId).build();
            try {
                return toResponse(repository.saveAndFlush(created));
            } catch (DataIntegrityViolationException e) {
                // 同時の初回作成（一意制約 uq_activity_stats_settings_scope）。版の競合と同じ 409 にする
                throw new ObjectOptimisticLockingFailureException(ActivityStatsSettingsEntity.class, scopeId, e);
            }
        }
        ActivityStatsSettingsEntity entity = existing.get();
        if (!Objects.equals(entity.getVersion(), request.getVersion())) {
            throw conflict();
        }
        entity.change(visible, userId);
        return toResponse(repository.saveAndFlush(entity));
    }

    private static ObjectOptimisticLockingFailureException conflict() {
        return new ObjectOptimisticLockingFailureException(ActivityStatsSettingsEntity.class, "activity_stats_settings");
    }

    private static ActivityStatsSettingsResponse toResponse(ActivityStatsSettingsEntity entity) {
        return new ActivityStatsSettingsResponse(entity.getScopeType().name(), entity.getScopeId(),
                entity.isTopParticipantsVisibleToMembers(), entity.getVersion());
    }
}
