package com.mannschaft.app.timeline.dto;

/**
 * フレンドチームへ共有された TEAM 投稿の、他ドメインへ渡してよい最小限の写し。
 *
 * <p>CMP-261103-0823。social ドメイン（フレンド転送）が timeline のエンティティへ直接依存しないよう、
 * 転送に必要な値だけを持つ。</p>
 *
 * @param postId  投稿 ID
 * @param teamId  投稿元チーム ID（{@code scope_type = TEAM} の {@code scope_id}）
 * @param content 本文（null のことがある）
 */
public record FriendShareableTeamPost(Long postId, Long teamId, String content) {
}
