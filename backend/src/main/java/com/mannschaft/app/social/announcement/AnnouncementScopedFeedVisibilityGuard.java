package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * お知らせの削除・ピン留めの前に通す「帰属と閲覧資格の門」。CMP-261103-0822 存在オラクル封じ。
 *
 * <h2>保証する内容</h2>
 * <p>次のいずれかを満たさない要求には、不在と同一の {@link AnnouncementErrorCode#ANNOUNCE_001}（404）を返し、
 * お知らせ ID の実在・帰属を秘匿する。</p>
 * <ol>
 *   <li>お知らせが実在する</li>
 *   <li>パスのスコープ（チーム / 組織）に帰属している（他スコープのお知らせを自スコープの URL 経由で操作させない）</li>
 *   <li>要求者がそのお知らせを閲覧できる（既読と同じ基準。課金 HIDDEN・未知の visibility は不可視。
 *       {@link AnnouncementReadCommandService#isReadableInScope}）</li>
 * </ol>
 * <p>閲覧資格がある者の著者・ADMIN 判定と、権限不足の 403 {@link AnnouncementErrorCode#ANNOUNCE_002} は
 * {@link AnnouncementFeedService} に委ねる（閲覧できる者には存在情報が新たに漏れない）。</p>
 *
 * <h2>なぜ {@code @Transactional} を付けず Controller から先に呼ぶのか</h2>
 * <p>閲覧資格の判定はキャッシュなしの役割解決・課金判定を含み、他ドメインの Repository へ到達する。
 * {@link AnnouncementFeedService} は {@code @Transactional} のため、その内側で判定すると取引が越境する
 * （D-3T 番人、CLAUDE.md DB 設計の原則 #5）。本部品は取引を持たず、他ドメインの判定はそれぞれの取引で行う。</p>
 */
@Component
@RequiredArgsConstructor
public class AnnouncementScopedFeedVisibilityGuard {

    private final AnnouncementReadCommandService readCommandService;

    /**
     * お知らせがパスのスコープに属し、呼出者が閲覧できることを確かめる。
     *
     * @param scopeType      パスのスコープ種別
     * @param scopeId        パスのスコープ ID
     * @param announcementId お知らせ ID
     * @param userId         操作ユーザー ID
     * @throws BusinessException 不在・越境・不可視（{@link AnnouncementErrorCode#ANNOUNCE_001} / 404）
     */
    public void requireVisible(AnnouncementScopeType scopeType, Long scopeId, Long announcementId, Long userId) {
        if (!readCommandService.isReadableInScope(scopeType, scopeId, announcementId, userId)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_001);
        }
    }
}
