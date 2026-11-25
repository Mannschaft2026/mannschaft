package com.mannschaft.app.timeline.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * F06.4.1 努力由来の TEAM タイムライン投稿と共有の対応表（PR-3a 骨格。upsert / revoke は PR-3b）。
 *
 * <p>値は timeline_posts.content へ複写しない。FK は同一ドメインの timeline_posts だけ（データ設計 §7）。</p>
 */
@Entity
@Table(name = "timeline_effort_sources", uniqueConstraints = {
        @UniqueConstraint(name = "uq_timeline_effort_share", columnNames = {"share_uuid"}),
        @UniqueConstraint(name = "uq_timeline_effort_post", columnNames = {"timeline_post_id"})})
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class TimelineEffortSourceEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "timeline_post_id", nullable = false)
    private Long timelinePostId;

    @Column(name = "share_uuid", nullable = false)
    private UUID shareUuid;

    @Column(name = "grant_membership_id", nullable = false)
    private Long grantMembershipId;

    @Column(name = "share_version", nullable = false)
    private Long shareVersion;

    @Column(name = "content_revision", nullable = false)
    private Long contentRevision;

    @Column(name = "is_revoked", nullable = false)
    private Boolean isRevoked;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (isRevoked == null) {
            isRevoked = Boolean.FALSE;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
