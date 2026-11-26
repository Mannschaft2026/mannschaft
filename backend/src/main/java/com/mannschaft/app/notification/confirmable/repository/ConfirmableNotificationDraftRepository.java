package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationDraftEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CMP-261126-1558: 確認通知の下書きリポジトリ。
 *
 * <p>読み書きは必ず (scope_type, scope_id, user_id) で絞る（他人・他スコープの下書きを、存在しない ID と
 * 同じに扱うため）。</p>
 */
public interface ConfirmableNotificationDraftRepository
        extends JpaRepository<ConfirmableNotificationDraftEntity, UUID> {

    List<ConfirmableNotificationDraftEntity> findByScopeTypeAndScopeIdAndUserIdOrderByUpdatedAtDesc(
            ScopeType scopeType, Long scopeId, Long userId);

    Optional<ConfirmableNotificationDraftEntity> findByIdAndScopeTypeAndScopeIdAndUserId(
            UUID id, ScopeType scopeType, Long scopeId, Long userId);

    long countByScopeTypeAndScopeIdAndUserId(ScopeType scopeType, Long scopeId, Long userId);

    /** 本人・同スコープの下書きだけを削除する（送信成功時・DELETE API）。 */
    long deleteByIdAndScopeTypeAndScopeIdAndUserId(UUID id, ScopeType scopeType, Long scopeId, Long userId);

    /** 退会30日後の強消去（Purge Listener）。 */
    long deleteByUserId(Long userId);

    /** 掃除バッチ: updated_at が閾値より前の下書きを物理削除する。 */
    long deleteByUpdatedAtBefore(Instant threshold);
}
