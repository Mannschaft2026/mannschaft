package com.mannschaft.app.supportaccess.port;

/**
 * F10.9 判定に要る利用者の状態（auth ドメインの Entity を越境参照しないための写し）。
 *
 * @param status            UserEntity.UserStatus の名前（ACTIVE 以外は代理不可）
 * @param withdrawalPending 退会手続中（deleted_at あり）
 * @param systemAdmin       SYSTEM_ADMIN か
 */
public record SupportAccessUserSnapshot(Long userId, String displayName, String status,
                                        boolean withdrawalPending, boolean systemAdmin) {
}
