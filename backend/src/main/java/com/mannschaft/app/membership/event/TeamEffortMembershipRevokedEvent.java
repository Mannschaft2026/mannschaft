package com.mannschaft.app.membership.event;

/**
 * TEAM の加入期間が明示的に終わった（disposition=REVOKED）ことを、終了 TX のコミット後に伝えるイベント
 * （F06.4.1 データ設計 §8。PR-3a 骨格。発火は終了 TX、購読は PR-3b の coordinator が AFTER_COMMIT で行う）。
 *
 * <p>endedMembershipId に束縛された共有だけを失効させる。同じ user/team の別の加入期間（再加入）には触れない。</p>
 *
 * @param teamId            チーム ID
 * @param userId            加入期間が終わったユーザー ID
 * @param endedMembershipId 終わった加入期間 ID（memberships.id）
 */
public record TeamEffortMembershipRevokedEvent(Long teamId, Long userId, Long endedMembershipId) {
}
