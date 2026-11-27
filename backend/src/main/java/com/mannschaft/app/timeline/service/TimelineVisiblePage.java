package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.dto.PostResponse;

import java.util.List;

/**
 * 閲覧可否で間引いた後の 1 ページ分（F06.4.1 努力由来の投稿の F00 判定）。
 *
 * <p>{@code nextCursor} は常に「このページで最後に見えた投稿の ID」で、閲覧者に見せない投稿の ID は含まない。
 * {@code hasNext} は「このページの後に見える投稿がまだある」ことを示す（末尾が非表示だけなら false・null）。</p>
 *
 * @param posts      返す投稿（要求件数まで。順序はカーソルの向きに従う）
 * @param nextCursor 次ページのカーソル。続きが無いとき null
 * @param hasNext    見える投稿の続きがあるか
 */
public record TimelineVisiblePage(List<PostResponse> posts, Long nextCursor, boolean hasNext) {
}
