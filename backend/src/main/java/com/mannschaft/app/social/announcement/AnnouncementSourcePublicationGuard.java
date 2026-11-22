package com.mannschaft.app.social.announcement;

import com.mannschaft.app.cms.service.BlogPostPublicationStatusService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.service.TimelinePostPublicationStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * お知らせ化する元コンテンツが公開状態かを確認する（F02.6 §5.3「元コンテンツの公開状態」・N9）。
 *
 * <p>ブログ記事は {@code PUBLISHED}、タイムライン投稿は {@code PUBLISHED} だけを許す。下書き・予約公開
 * （{@code DRAFT} ＋未来の公開日時）・承認待ち・差し戻し・アーカイブ・非表示・削除済みは、
 * 不在と同じ {@code ANNOUNCE_006} で拒否する。{@link AnnouncementSourceResolver} の
 * スコープ・個人ブログ判定より<b>前</b>に呼ぶこと（他人の下書きの存在を応答差で悟らせない。存在オラクル方針）。</p>
 *
 * <p>元コンテンツのドメインへは、リポジトリ・エンティティではなく各ドメインの Service 経由で問う。</p>
 */
@Component
@RequiredArgsConstructor
public class AnnouncementSourcePublicationGuard {

    private final BlogPostPublicationStatusService blogStatus;
    private final TimelinePostPublicationStatusService timelineStatus;

    /**
     * 元コンテンツが公開状態でなければ {@code ANNOUNCE_006} を投げる。他のソース種別は何もしない。
     */
    public void assertPublished(AnnouncementSourceType sourceType, Long sourceId) {
        boolean published = switch (sourceType) {
            case BLOG_POST -> blogStatus.isPublished(sourceId);
            case TIMELINE_POST -> timelineStatus.isPublished(sourceId);
            default -> true;
        };
        if (!published) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_006);
        }
    }
}
