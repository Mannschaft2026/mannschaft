package com.mannschaft.app.auth.entity;

import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * 招待付き新規登録の保留承認（CMP-261107-1833 設計 §1）。
 *
 * <p>招待リンクから新規登録した利用者について、登録時点では参加させず保留を1行だけ残す。
 * 参加はメール確認（未成年は保護者同意）・ログインの後に本人が承認したときに行う。
 * 招待トークンの平文は持たず、{@code SecretTokenVault} のハッシュだけを持つ（応答・ログに出さない）。</p>
 *
 * <p>本 Entity は INSERT と読み取り専用で、状態遷移はすべて {@code PendingInviteStore} の
 * 条件付き native UPDATE で行う（並行する承認を1回に絞るため）。</p>
 */
@Entity
@Table(name = "registration_pending_invites")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class RegistrationPendingInviteEntity extends UuidV7Entity {

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private Long userId;

    @Column(name = "token_hash", nullable = false, length = 64, updatable = false)
    private String tokenHash;

    @Column(name = "scope_type", nullable = false, length = 20, updatable = false)
    private String scopeType;

    @Column(name = "scope_id", nullable = false, updatable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20, updatable = false)
    private PendingInviteStatus status;

    @Column(name = "failure_code", length = 20, updatable = false)
    private String failureCode;

    @Column(name = "accept_attempt_id", updatable = false)
    private UUID acceptAttemptId;

    @Column(name = "accepting_started_at", updatable = false)
    private Instant acceptingStartedAt;

    @Column(name = "reissue_requested_at", updatable = false)
    private Instant reissueRequestedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false, updatable = false)
    private Instant updatedAt;
}
