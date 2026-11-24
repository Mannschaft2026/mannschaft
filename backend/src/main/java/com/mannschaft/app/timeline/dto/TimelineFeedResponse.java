package com.mannschaft.app.timeline.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * タイムラインフィードレスポンスDTO。
 *
 * <p>FE が期待する形式:
 * <pre>
 * {
 *   "data": {
 *     "pinned": [...],
 *     "posts": [...]
 *   },
 *   "meta": {
 *     "nextCursor": null,
 *     "limit": 20,
 *     "hasNext": false
 *   }
 * }
 * </pre>
 * </p>
 *
 * <p>FE 型定義 {@code frontend/app/types/timeline.ts#TimelineFeedResponse} に対応する。</p>
 */
@Getter
@RequiredArgsConstructor
public class TimelineFeedResponse {

    private final FeedData data;
    private final FeedMeta meta;

    /**
     * フィードデータ部。ピン留め投稿リストと通常投稿リストを含む。
     */
    @Getter
    @RequiredArgsConstructor
    public static class FeedData {
        private final List<PostResponse> pinned;
        private final List<PostResponse> posts;
    }

    /**
     * フィードメタ情報。カーソルページネーション用。
     *
     * <p>次ページがある場合は最後に返した投稿 ID をカーソルとして返す。</p>
     */
    @Getter
    @RequiredArgsConstructor
    public static class FeedMeta {
        /** 次ページの起点カーソル。最終ページでは null。 */
        private final Long nextCursor;
        private final int limit;
        private final boolean hasNext;
    }

    /**
     * フィードデータとメタを組み立ててレスポンスを生成する。
     *
     * @param pinned    ピン留め投稿リスト
     * @param posts     通常投稿リスト
     * @param limit     リクエスト件数
     * @return タイムラインフィードレスポンス
     */
    public static TimelineFeedResponse of(
            List<PostResponse> pinned,
            List<PostResponse> posts,
            int limit) {
        int pageSize = limit > 0 ? limit : 20;
        boolean hasNext = posts.size() > pageSize;
        List<PostResponse> pagePosts = hasNext ? posts.subList(0, pageSize) : posts;
        Long nextCursor = hasNext && !pagePosts.isEmpty()
                ? pagePosts.get(pagePosts.size() - 1).getId()
                : null;
        FeedData feedData = new FeedData(pinned, pagePosts);
        FeedMeta feedMeta = new FeedMeta(nextCursor, pageSize, hasNext);
        return new TimelineFeedResponse(feedData, feedMeta);
    }

    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）用のレスポンスを組み立てる。
     *
     * <p>{@code GET /api/v1/timeline/my} 専用。id キーセットページネーションの
     * カーソルを埋める:</p>
     * <ul>
     *   <li>{@code pinned} は常に空（殿の確定仕様 c: /my では pinned を出さない）</li>
     *   <li>{@code hasNext = posts.size() > limit}</li>
     *   <li>{@code nextCursor = hasNext ? 最後の post.id : null}（id 降順なので末尾が最小 id）</li>
     * </ul>
     *
     * @param posts マイフィード投稿リスト（id 降順・最大 limit 件）
     * @param limit リクエスト件数
     * @return タイムラインフィードレスポンス（pinned 空・実カーソル付き）
     */
    public static TimelineFeedResponse ofMyFeed(List<PostResponse> posts, int limit) {
        int pageSize = limit > 0 ? limit : 20;
        boolean hasNext = posts.size() > pageSize;
        List<PostResponse> pagePosts = hasNext ? posts.subList(0, pageSize) : posts;
        Long nextCursor = (hasNext && !pagePosts.isEmpty())
                ? pagePosts.get(pagePosts.size() - 1).getId()
                : null;
        FeedData feedData = new FeedData(List.of(), pagePosts);
        FeedMeta feedMeta = new FeedMeta(nextCursor, pageSize, hasNext);
        return new TimelineFeedResponse(feedData, feedMeta);
    }

    /**
     * リプライ一覧（{@code GET /timeline/posts/{id}/replies}）用のレスポンスを組み立てる。
     *
     * <p>FE の {@code getReplies} は {@code TimelineFeedResponse}（{@code res.data.posts} /
     * {@code res.meta.nextCursor}）を期待するため、マイフィードと同形式で返す。
     * リプライに pinned の概念は無いため {@code data.pinned} は常に空。
     * ページネーション（{@code hasNext}/{@code nextCursor}）は {@link #ofMyFeed} と同じ
     * ID キーセット方式（ID 昇順の末尾 = 最大 ID を次カーソルとする）。</p>
     *
     * @param replies enrich 済みリプライ一覧（ID 昇順・最大 {@code limit + 1} 件）
     * @param limit   リクエスト件数
     * @return タイムラインフィードレスポンス（pinned 空・実カーソル付き）
     */
    public static TimelineFeedResponse ofReplies(List<PostResponse> replies, int limit) {
        return ofMyFeed(replies, limit);
    }

    /**
     * 取得後に閲覧可否で間引く一覧のページ応答（F06.4.1 努力由来の投稿の F00 判定）。
     *
     * <p>次ページの有無とカーソルは、間引く前の取得結果（{@code limit + 1} 件）から求める。間引いた後の件数で
     * 判定すると、末尾の判定用 1 件が間引かれたときに続きがあるのに {@code hasNext=false} になり、
     * ページングが途中で止まる。カーソルは間引く前のページ末尾の ID なので、間引いた投稿を再取得しない。</p>
     *
     * @param pinned  ピン留め（間引き済み）
     * @param fetched 間引く前の取得結果（ID 降順・昇順いずれもカーソルの向きに従う）
     * @param limit   ページサイズ
     * @param visible ページ内の投稿を閲覧可否で間引く関数（順序は保つ）
     * @return ページ応答
     */
    public static TimelineFeedResponse ofVisiblePage(List<PostResponse> pinned, List<PostResponse> fetched, int limit,
                                                     java.util.function.UnaryOperator<List<PostResponse>> visible) {
        int pageSize = limit > 0 ? limit : 20;
        boolean hasNext = fetched.size() > pageSize;
        List<PostResponse> pagePosts = hasNext ? fetched.subList(0, pageSize) : fetched;
        Long nextCursor = (hasNext && !pagePosts.isEmpty())
                ? pagePosts.get(pagePosts.size() - 1).getId()
                : null;
        FeedData feedData = new FeedData(pinned, visible.apply(pagePosts));
        FeedMeta feedMeta = new FeedMeta(nextCursor, pageSize, hasNext);
        return new TimelineFeedResponse(feedData, feedMeta);
    }
}
