package com.mannschaft.app.auth.dto;

import com.mannschaft.app.auth.PendingInviteStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 保留承認行の読み取り射影（auth 内部用。応答 DTO には出さない）。CMP-261107-1833 試練スタブ。
 *
 * <p>{@code tokenHash} は招待照合用のハッシュで、応答・ログに出してはならない。</p>
 */
public record PendingInviteSnapshot(
        UUID id,
        Long userId,
        String tokenHash,
        String scopeType,
        Long scopeId,
        PendingInviteStatus status,
        String failureCode,
        LocalDateTime acceptingStartedAt,
        LocalDateTime reissueRequestedAt) {
}
