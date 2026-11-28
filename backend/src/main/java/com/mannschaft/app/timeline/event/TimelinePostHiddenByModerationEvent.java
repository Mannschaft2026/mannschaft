package com.mannschaft.app.timeline.event;

/**
 * 通報・モデレーションでタイムライン投稿が非表示にされた（F06.4.1 データ設計 §7.2）。
 *
 * <p>モデレーションの TX の中で発行し、commit 後（AFTER_COMMIT）に購読側が扱う。努力由来の投稿なら effort の共有を
 * MODERATED（終端）へ寄せる。投稿 ID だけを持ち、本文は載せない。</p>
 *
 * @param postId 非表示にされた投稿 ID
 */
public record TimelinePostHiddenByModerationEvent(Long postId) {
}
