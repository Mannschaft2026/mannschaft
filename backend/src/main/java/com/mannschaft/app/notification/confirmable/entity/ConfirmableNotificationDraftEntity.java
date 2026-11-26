package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.membership.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * CMP-261126-1558: 確認通知の作成フォームの下書き。
 *
 * <p>スコープ（TEAM / ORGANIZATION）×作成者ごとに複数件（上限10件）保存する。
 * {@code user_id} はクロスドメイン参照のため FK を張らない。退会の30日後に
 * Purge Listener が {@code deleteByUserId} で物理削除する。</p>
 *
 * <p>試練（red）段階の骨格。列定義の正本は migration（出陣で追加）。</p>
 */
@Entity
@Table(name = "confirmable_notification_drafts", indexes = {
        @Index(name = "idx_cnd_scope_user_updated", columnList = "scope_type, scope_id, user_id, updated_at"),
        @Index(name = "idx_cnd_user", columnList = "user_id"),
        @Index(name = "idx_cnd_updated", columnList = "updated_at")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ConfirmableNotificationDraftEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Builder.Default
    @Column(name = "title", nullable = false, length = 200)
    private String title = "";

    @Column(name = "body", columnDefinition = "TEXT")
    private String body;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 10)
    private ConfirmableNotificationPriority priority = ConfirmableNotificationPriority.NORMAL;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "audience_mode", nullable = false, length = 10)
    private ConfirmableDraftAudienceMode audienceMode = ConfirmableDraftAudienceMode.DEFAULT;

    /** {@code [{"type":"TEAM","id":1}]} 形式の JSON 文字列（上限200件）。 */
    @Column(name = "selected_targets", columnDefinition = "JSON")
    private String selectedTargets;

    @Column(name = "selected_group_id", columnDefinition = "BINARY(16)")
    private UUID selectedGroupId;

    @Column(name = "selected_template_id")
    private Long selectedTemplateId;

    /** 楽観ロック。PUT の {@code baseVersion} と照合する。 */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
