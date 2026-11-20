package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.dto.FriendShareableTeamPost;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * フレンド転送（social ドメイン）が timeline 投稿を扱うための公開窓口（CMP-261103-0823）。
 *
 * <p>social が timeline のエンティティ・Repository へ直接依存しないよう、投稿の読み取り・
 * 転送先投稿の作成・論理削除をここに閉じ、呼び出し側にはエンティティを渡さない。</p>
 *
 * <h2>転送元の可視性判定</h2>
 * <p>投稿側の判定（実在・論理削除・{@code scope_type = TEAM}・{@code status = PUBLISHED}・
 * {@code share_with_friends = TRUE}）は timeline ドメインの責務としてここで行う。条件を満たさない
 * 投稿は理由を問わず {@link Optional#empty()} を返すため、呼び出し側は不在と区別できず、
 * 投稿の実在・共有設定を外部へ判別させる応答（存在オラクル）を作れない。
 * フレンド関係・自チーム判定は呼び出し側（social）の責務。</p>
 *
 * <h2>トランザクション</h2>
 * <p>書き込みメソッドは既定の伝播（REQUIRED）で呼び出し側のトランザクションに参加する。
 * 転送先投稿の作成と転送履歴の保存は同一トランザクションで確定・巻き戻しされる
 * （転送履歴の UNIQUE 違反時に転送先投稿も巻き戻る）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineFriendForwardPostService {

    private final TimelinePostRepository timelinePostRepository;

    /**
     * フレンド共有済みの公開 TEAM 投稿を取得する。
     *
     * @param postId 投稿 ID
     * @return 条件を満たす投稿の写し。不在・論理削除済み・TEAM 以外・PUBLISHED 以外・非共有は空
     */
    public Optional<FriendShareableTeamPost> findFriendShareableTeamPost(Long postId) {
        if (postId == null) {
            return Optional.empty();
        }
        return timelinePostRepository.findById(postId)
                .filter(p -> p.getScopeType() == PostScopeType.TEAM)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .filter(p -> Boolean.TRUE.equals(p.getShareWithFriends()))
                .filter(p -> p.getScopeId() != null)
                .map(p -> new FriendShareableTeamPost(p.getId(), p.getScopeId(), p.getContent()));
    }

    /**
     * 転送先チームのタイムラインに {@code FRIEND_FORWARD} スコープの投稿を作成する。
     *
     * @param forwardingTeamId   転送先（自）チーム ID
     * @param userId             操作実行者のユーザー ID
     * @param content            投稿本文
     * @param sourcePostId       転送元投稿 ID
     * @param forwardTargetRange 転送範囲（{@code ForwardTarget} の名前）
     * @return 作成した投稿の ID
     */
    @Transactional
    public Long createFriendForwardPost(Long forwardingTeamId, Long userId, String content,
                                        Long sourcePostId, String forwardTargetRange) {
        TimelinePostEntity saved = timelinePostRepository.save(
                TimelinePostEntity.builder()
                        .scopeType(PostScopeType.FRIEND_FORWARD)
                        .scopeId(forwardingTeamId)
                        .userId(userId)
                        .postedAsType(PostedAsType.TEAM)
                        .postedAsId(forwardingTeamId)
                        .content(content)
                        .status(PostStatus.PUBLISHED)
                        .forwardSourcePostId(sourcePostId)
                        .forwardTargetRange(forwardTargetRange)
                        .shareWithFriends(false)
                        .build());
        return saved.getId();
    }

    /**
     * 転送先投稿を論理削除する。既に存在しない（論理削除済みを含む）場合は何もしない。
     *
     * @param postId 転送先投稿 ID
     */
    @Transactional
    public void softDeleteFriendForwardPost(Long postId) {
        if (postId == null) {
            return;
        }
        timelinePostRepository.findById(postId)
                .ifPresent(post -> {
                    post.softDelete();
                    timelinePostRepository.save(post);
                });
    }
}
