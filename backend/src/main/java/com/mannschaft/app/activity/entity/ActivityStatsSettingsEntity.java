package com.mannschaft.app.activity.entity;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * 活動統計のスコープ別設定（F06.4 §15.3・CMP-261124-0159）。上位参加者（名前つき）をメンバーに公開するか。
 *
 * <p>行が無いスコープは既定（OFF＝管理者のみ）として扱い、行は最初の保存で作る。テナントの
 * {@code organization_id} 列を持たないため {@code AbstractTenantAwareRepository} の対象外。
 * {@code updated_by} は退会者の物理削除時に NULL 化する（{@code ActivityStatsSettingsPurgeEventListener}）。</p>
 */
@Entity
@Table(name = "activity_stats_settings", uniqueConstraints =
        @UniqueConstraint(name = "uq_activity_stats_settings_scope", columnNames = {"scope_type", "scope_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ActivityStatsSettingsEntity extends UuidV7Entity {

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private ActivityScopeType scopeType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Builder.Default
    @Column(name = "top_participants_visible_to_members", nullable = false)
    private boolean topParticipantsVisibleToMembers = false;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 公開設定を変更する。 */
    public void change(boolean topParticipantsVisibleToMembers, Long updatedBy) {
        this.topParticipantsVisibleToMembers = topParticipantsVisibleToMembers;
        this.updatedBy = updatedBy;
    }
}
