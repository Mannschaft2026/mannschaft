package com.mannschaft.app.role.dto;

/**
 * 新規登録時に招待トークンを読んだ結果（CMP-261107-1833 設計 §4）。Entity を外へ出さない射影。
 *
 * @param teamId         チーム招待ならチーム ID
 * @param organizationId 組織招待なら組織 ID
 * @param targeted       宛先付き（承諾型）招待か
 * @param valid          現時点で有効か（失効・期限切れ・上限到達なら false）
 */
public record RegistrationInviteInfo(Long teamId, Long organizationId, boolean targeted, boolean valid) {
}
