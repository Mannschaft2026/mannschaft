package com.mannschaft.app.membership.dto;

/**
 * チーム共有資格の一括照会の1件（F06.4.1 データ設計 §8）。
 *
 * @param teamId            チーム ID
 * @param userId            共有した本人（投稿者）のユーザー ID
 * @param grantMembershipId 共有に束縛された加入期間 ID（memberships.id）
 */
public record EffortShareQualificationQuery(Long teamId, Long userId, Long grantMembershipId) {
}
