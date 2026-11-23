package com.mannschaft.app.effortrecord.event;

import java.util.UUID;

/**
 * F06.4.1 本人取消・原本削除で共有許可を owner TX で失効させた（データ設計 §3。commit 後に team・timeline を非表示にする）。
 *
 * <p>ID と版だけを持つ。値・note・token は載せない（AC-61）。読み取りの deny は owner 行の REVOKED で既に確定しているため、
 * 本イベントの後処理が遅れても止まっても共有は読めない。</p>
 *
 * @param recordId     原本 ID
 * @param ownerUserId  所有者
 * @param shareId      共有 UUID
 * @param teamId       共有先チーム
 * @param shareVersion 取消後の shareVersion
 * @param explicit     本人の明示取消なら true（監査対象）。原本削除に伴う取消は false
 */
public record EffortShareRevokedEvent(UUID recordId, Long ownerUserId, UUID shareId, Long teamId, long shareVersion,
                                      boolean explicit) {
}
