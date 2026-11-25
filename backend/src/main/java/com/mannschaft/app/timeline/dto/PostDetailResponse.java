package com.mannschaft.app.timeline.dto;

import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.timeline.TimelinePostSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * タイムライン投稿詳細レスポンスDTO。添付ファイル・みたよ！状態・投票を含む。
 */
@Builder(toBuilder = true)
@Getter
public class PostDetailResponse {

    private final Long id;
    private final PostScopeDto scope;
    private final PostAuthorDto author;
    private final PostContentDto content;
    private final PostStatsDto stats;
    private final List<AttachmentResponse> attachments;
    private final PollResponse poll;
    private final PostAuditDto audit;

    /**
     * リプライのプレビュー（会話の古い順＝{@code createdAt} 昇順・先頭最大 5 件）。
     *
     * <p>リプライ一覧 API（{@code GET /timeline/posts/{id}/replies}）が
     * 「会話を古い順に読み、続きを下へロードする」ID 昇順キーセットページングであるのに合わせ、
     * 詳細ページのプレビューも同じ「古い順・先頭 N 件」で統一する（最新 N 件ではない）。
     * 著者表示名/アバター・投稿元スコープ名/slug・代理主体を enrich 済みで返す
     * （{@link PostResponse} と同じ enrich 経路を通す）。リプライが無ければ空リスト。
     * FE 型 {@code frontend/app/types/timeline.ts#TimelinePostDetailResponse.recentReplies} と対応する。</p>
     */
    private final List<PostResponse> recentReplies;

    /**
     * F06.4.1: 投稿の由来。対応表 {@code timeline_effort_sources} に行がある投稿（個人の頑張り記録のチーム共有）だけ
     * {@code PERSONAL_EFFORT_SHARE}、通常の投稿は {@code null}。本文の文字列では判定しない（API 契約 §4.2）。
     */
    @Schema(description = "投稿の由来。努力由来（頑張り記録のチーム共有）の投稿だけ PERSONAL_EFFORT_SHARE、通常の投稿は null",
            nullable = true)
    private final TimelinePostSourceType sourceType;

    /**
     * F06.4.1: 努力由来の投稿に載せる、閲覧者が F00 を通った共有（チーム詳細 API と同じ SharedRecord。allowlist の項目だけ）。
     * {@code sourceType} が非 null のとき必ず非 null。通常の投稿は {@code null}。timeline の read TX の外で
     * {@code TimelineEffortReadGuard} が一括で載せる（API 契約 §4.2）。
     */
    @Schema(description = "努力由来の投稿の共有内容（SharedRecord）。通常の投稿は null", nullable = true)
    private final EffortSharedRecord effortShare;

    public record PostScopeDto(String scopeType, Long scopeId) {}

    public record PostAuthorDto(Long userId, Long socialProfileId, String postedAsType, Long postedAsId) {}

    public record PostContentDto(String content, Long parentId, Long repostOfId, String status,
                                 LocalDateTime scheduledAt, Boolean isPinned) {}

    public record PostStatsDto(Integer repostCount, Integer reactionCount, Integer replyCount,
                               Short attachmentCount, Short editCount, int mitayoCount, boolean mitayo) {}

    public record PostAuditDto(LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
