package com.mannschaft.app.effortrecord.entity;

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
import java.util.UUID;

/**
 * F06.4.1 定義の不変 revision。保存後の {@code definition_json} は変更しない。
 * retired 後も物理削除しない（データ設計 §2）。
 */
@Entity
@Table(name = "effort_definition_revisions",
        uniqueConstraints = @UniqueConstraint(name = "uq_effort_revision", columnNames = {"definition_id", "revision"}))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EffortDefinitionRevisionEntity extends UuidV7Entity {

    /** 同一ドメイン内の FK（effort_definitions.id）。 */
    @Column(name = "definition_id", nullable = false)
    private UUID definitionId;

    @Column(name = "revision", nullable = false)
    private Integer revision;

    /** DefinitionSnapshot の canonical JSON。 */
    @Column(name = "definition_json", columnDefinition = "JSON", nullable = false)
    private String definitionJson;

    /** definition_json の SHA-256（16進 64 文字）。 */
    @Column(name = "definition_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String definitionHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
