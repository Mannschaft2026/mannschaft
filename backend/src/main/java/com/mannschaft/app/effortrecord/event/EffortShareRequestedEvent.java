package com.mannschaft.app.effortrecord.event;

import java.util.UUID;

/**
 * F06.4.1 共有の同期要求イベント（データ設計 §3。PR-3b 試練が置いた骨格。発行と購読は出陣で実装する）。
 *
 * <p>原本 ID・owner ID・share ID・sourceVersion（contentRevision）・shareVersion・grantMembershipId と経路の teamId だけを持つ。
 * 値・note・snapshot・token は載せない。イベントは配送の唯一の正本ではなく、owner 行の desired 状態が正本で、
 * 購読側（非 TX coordinator）は現在の desired と版が一致しないイベントを捨てる。</p>
 *
 * @param recordId          原本 ID
 * @param ownerUserId       原本の所有者
 * @param shareId           共有 UUID
 * @param teamId            共有先チーム（経路キー）
 * @param sourceVersion     要求時の contentRevision
 * @param shareVersion      要求時の shareVersion
 * @param grantMembershipId 共有を許可した加入期間 ID
 */
public record EffortShareRequestedEvent(UUID recordId, Long ownerUserId, UUID shareId, Long teamId, long sourceVersion,
                                        long shareVersion, Long grantMembershipId) {
}
