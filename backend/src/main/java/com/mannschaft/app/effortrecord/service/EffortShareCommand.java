package com.mannschaft.app.effortrecord.service;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F06.4.1 owner 行に保存された共有の desired command（データ設計 §3。owner 行が配送の正本）。
 * coordinator はこの値だけを team・timeline・ack へ渡し、値・note を含むログを書かない。
 *
 * @param recordId          原本 ID
 * @param ownerUserId       所有者
 * @param shareId           共有 UUID
 * @param teamId            共有先チーム
 * @param shareVersion      共有許可の版
 * @param contentRevision   原本の内容版（team 行の source_version）
 * @param grantMembershipId 共有を許可した加入期間 ID
 * @param definitionUuid    共有先 TEAM 定義
 * @param recordedOn        記録日
 * @param projectionJson    desired projection（チームへ見せる部分だけ）
 * @param projectionHash    desired hash
 */
public record EffortShareCommand(UUID recordId, Long ownerUserId, UUID shareId, Long teamId, long shareVersion,
                                 long contentRevision, Long grantMembershipId, UUID definitionUuid, LocalDate recordedOn,
                                 String projectionJson, String projectionHash) {
}
