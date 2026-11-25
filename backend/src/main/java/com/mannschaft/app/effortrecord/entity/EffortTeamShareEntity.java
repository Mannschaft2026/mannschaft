package com.mannschaft.app.effortrecord.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * F06.4.1 チーム側の共有行（PR-3a 骨格。共有の業務処理は PR-3b）。
 *
 * <p>経路キーは team_id だけで、組織の列は持たない（2026-11-18 マスター裁定）。grant_membership_id は
 * 共有を許可した加入期間（memberships.id）で、クロスドメイン FK は張らない（データ設計 §2・§8）。</p>
 */
@Entity
@Table(name = "effort_team_shares",
        uniqueConstraints = @UniqueConstraint(name = "uq_effort_team_source", columnNames = {"team_id", "source_record_id"}))
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EffortTeamShareEntity extends UuidV7Entity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    @Column(name = "source_record_id", nullable = false)
    private UUID sourceRecordId;

    @Column(name = "source_version", nullable = false)
    private Long sourceVersion;

    @Column(name = "grant_membership_id", nullable = false)
    private Long grantMembershipId;

    @Column(name = "share_version", nullable = false)
    private Long shareVersion;

    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;

    @Column(name = "recorded_on", nullable = false)
    private LocalDate recordedOn;

    @Column(name = "projection_json", columnDefinition = "JSON", nullable = false)
    private String projectionJson;

    @Column(name = "projection_hash", length = 64, columnDefinition = "CHAR(64)", nullable = false)
    private String projectionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 12, nullable = false)
    private EffortTeamShareState state;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
