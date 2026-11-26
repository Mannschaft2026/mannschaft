package com.mannschaft.app.supportaccess.entity;

import com.mannschaft.app.supportaccess.SupportAccessEventKind;
import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/** F10.9 代理中の閲覧記録（support_access_logs）。本人も閲覧できる。 */
@Entity
@PersonalData(category = "support_access_logs")
@Table(name = "support_access_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SupportAccessLogEntity extends UuidV7Entity {

    @Column(name = "grant_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID grantId;

    @Column(name = "operator_user_id", nullable = false)
    private Long operatorUserId;

    @Column(name = "target_user_id", nullable = false)
    private Long targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_kind", nullable = false, length = 20)
    private SupportAccessEventKind eventKind;

    @Column(name = "http_method", length = 10)
    private String httpMethod;

    /** クエリ文字列は保存しない（PII 回避）。 */
    @Column(name = "request_path", length = 512)
    private String requestPath;

    @Column(name = "route_pattern", length = 255)
    private String routePattern;

    @Column(name = "scope_type", length = 20)
    private String scopeType;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(name = "http_status")
    private Short httpStatus;

    @Column(name = "deny_code", length = 30)
    private String denyCode;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /** chain の後に応答状態と最適一致パターンを書き足す。 */
    public void complete(int httpStatus, String routePattern) {
        this.httpStatus = (short) httpStatus;
        this.routePattern = routePattern != null && routePattern.length() > 255 ? routePattern.substring(0, 255) : routePattern;
    }
}
