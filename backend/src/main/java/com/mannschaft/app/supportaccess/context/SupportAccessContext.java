package com.mannschaft.app.supportaccess.context;

import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 代理閲覧中であることを表すリクエスト単位の文脈（旧リクエスト属性 originalAdminId の置き換え）。
 *
 * @param grantId        使用中の grant
 * @param operatorUserId 閲覧している運営者（監査の actor）
 * @param targetUserId   視点を借りている本人
 * @param expiresAt      許可の期限（許可+24h 固定）
 */
public record SupportAccessContext(UUID grantId, Long operatorUserId, Long targetUserId, Instant expiresAt) {
}
