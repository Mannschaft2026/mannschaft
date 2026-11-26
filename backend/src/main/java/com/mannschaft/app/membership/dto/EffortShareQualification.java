package com.mannschaft.app.membership.dto;

import com.mannschaft.app.membership.domain.EffortShareDisposition;

/**
 * チーム共有資格の一括照会の結果（F06.4.1 データ設計 §8。内部専用で Controller へ公開しない）。
 *
 * @param teamId            チーム ID
 * @param userId            本人のユーザー ID
 * @param grantMembershipId 照会した加入期間 ID
 * @param isCurrentEligible その加入期間が、同じ user/team の現在有効な直接所属であるか
 * @param disposition       加入期間が終わっていればその扱い（在籍中・移行前の終了行は null）
 */
public record EffortShareQualification(Long teamId, Long userId, Long grantMembershipId,
                                       boolean isCurrentEligible, EffortShareDisposition disposition) {
}
