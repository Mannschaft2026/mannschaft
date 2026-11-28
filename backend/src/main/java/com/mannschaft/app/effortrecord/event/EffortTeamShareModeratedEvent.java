package com.mannschaft.app.effortrecord.event;

import java.util.UUID;

/**
 * F06.4.1 チーム側の共有を MODERATED（終端）にした（管理者非表示・通報による非表示。データ設計 §3・§7.2）。
 *
 * <p>team TX の commit 後に owner 行へ MODERATED を反映し、timeline の対応投稿を非表示にする。通知が失われても
 * F00 は team 行の MODERATED を読むため表示は止まり、本人の retry・編集でも復活しない。値・note は載せない。</p>
 *
 * @param teamId      チーム
 * @param shareId     共有 UUID
 * @param recordId    原本 ID
 * @param actorUserId 非表示にした管理者（通報経由は null）
 * @param version     非表示後の team 行の version
 */
public record EffortTeamShareModeratedEvent(Long teamId, UUID shareId, UUID recordId, Long actorUserId, long version) {
}
