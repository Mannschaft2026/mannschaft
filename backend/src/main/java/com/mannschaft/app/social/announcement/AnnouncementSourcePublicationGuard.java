package com.mannschaft.app.social.announcement;

import com.mannschaft.app.cms.service.BlogPostPublicationStatusService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.service.TimelinePostPublicationStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * お知らせの元コンテンツ（ブログ記事・タイムライン投稿）が公開済みかを判定する<b>唯一の窓口</b>
 * （F02.6 §5.3「元コンテンツの公開状態」・N9）。
 *
 * <h2>定義（一覧クエリと同じ）</h2>
 * <p>{@link AnnouncementSourceType#BLOG_POST} と {@link AnnouncementSourceType#TIMELINE_POST} は、
 * <b>公開済み（{@code status = PUBLISHED}）で論理削除されていない元が存在する</b>ときだけ「公開済み」とする。
 * 下書き・予約公開（{@code DRAFT}＋未来の公開日時）・承認待ち・差し戻し・アーカイブ・非表示・論理削除済み・
 * 元が存在しない（孤児）はすべて公開済みではない（fail-closed）。ソース種別が {@code null} も公開済みではない。
 * 他のソース種別は判定しない（常に公開済み扱い）。一覧の正準 WHERE 句
 * （{@code AnnouncementFeedQueryRepository#SOURCE_PUBLISHED_CONDITION}）と同じ定義である。</p>
 *
 * <h2>使う場所</h2>
 * <ul>
 *   <li>お知らせ化の入口（{@link #assertPublished}。Controller で Resolver のスコープ・個人ブログ判定より<b>前</b>に呼び、
 *       不在と同じ {@code ANNOUNCE_006} にする。他人の下書きの存在を応答差で悟らせない）</li>
 *   <li>お知らせ ID 指定の経路（ピン留め・削除の入口 Guard、単件既読・read-batch の判定部品、インボックスの可視判定、
 *       F00 Resolver の投影読込）。公開済みでなければ<b>お知らせが不在</b>と同じ扱いにする（タイトル・抜粋のキャッシュを返さない）</li>
 * </ul>
 *
 * <p>元コンテンツのドメインへは、リポジトリ・エンティティではなく各ドメインの Service 経由で問う。
 * 本部品は {@code @Transactional} を持たず、{@code @Transactional} の内側からは呼ばない（D-3T。問い合わせは各 Service の
 * 読み取り専用 tx で閉じる）。</p>
 */
@Component
@RequiredArgsConstructor
public class AnnouncementSourcePublicationGuard {

    private final BlogPostPublicationStatusService blogStatus;
    private final TimelinePostPublicationStatusService timelineStatus;

    /**
     * 元コンテンツが公開済みでなければ {@code ANNOUNCE_006}（不在と同じ）を投げる。
     */
    public void assertPublished(AnnouncementSourceType sourceType, Long sourceId) {
        if (!isPublished(sourceType, sourceId)) {
            throw new BusinessException(AnnouncementErrorCode.ANNOUNCE_006);
        }
    }

    /**
     * 元コンテンツが公開済みか（定義はクラス Javadoc）。
     */
    public boolean isPublished(AnnouncementSourceType sourceType, Long sourceId) {
        if (sourceType == null) {
            return false;
        }
        return switch (sourceType) {
            case BLOG_POST -> blogStatus.isPublished(sourceId);
            case TIMELINE_POST -> timelineStatus.isPublished(sourceId);
            default -> true;
        };
    }

    /**
     * 元コンテンツが公開済みの要素だけを、元の順序のまま返す（一括判定。種別ごとに 1 回ずつ問い合わせる）。
     *
     * @param items      判定対象（お知らせ等）
     * @param sourceType 要素からソース種別を取り出す関数
     * @param sourceId   要素からソース ID を取り出す関数
     */
    public <T> List<T> retainPublished(Collection<T> items, Function<T, AnnouncementSourceType> sourceType,
                                       Function<T, Long> sourceId) {
        List<Long> blogIds = idsOf(items, AnnouncementSourceType.BLOG_POST, sourceType, sourceId);
        List<Long> timelineIds = idsOf(items, AnnouncementSourceType.TIMELINE_POST, sourceType, sourceId);
        Set<Long> publishedBlogs = blogIds.isEmpty() ? Set.of() : blogStatus.findPublishedIds(blogIds);
        Set<Long> publishedTimelines = timelineIds.isEmpty() ? Set.of() : timelineStatus.findPublishedIds(timelineIds);
        List<T> result = new ArrayList<>(items.size());
        for (T item : items) {
            AnnouncementSourceType type = sourceType.apply(item);
            if (type == null) {
                continue;
            }
            boolean published = switch (type) {
                case BLOG_POST -> publishedBlogs.contains(sourceId.apply(item));
                case TIMELINE_POST -> publishedTimelines.contains(sourceId.apply(item));
                default -> true;
            };
            if (published) {
                result.add(item);
            }
        }
        return result;
    }

    private static <T> List<Long> idsOf(Collection<T> items, AnnouncementSourceType type,
                                        Function<T, AnnouncementSourceType> sourceType, Function<T, Long> sourceId) {
        return items.stream().filter(item -> sourceType.apply(item) == type).map(sourceId)
                .filter(Objects::nonNull).distinct().toList();
    }
}
