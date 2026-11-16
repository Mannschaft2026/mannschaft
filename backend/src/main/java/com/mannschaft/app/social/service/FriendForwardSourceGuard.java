package com.mannschaft.app.social.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * フレンド転送の転送元投稿を検査する門。CMP-261103-0823 存在オラクル封じ。
 *
 * <p>次のいずれかに該当する転送元は、不在と同一の
 * {@link SocialErrorCode#FRIEND_FORWARD_SOURCE_POST_NOT_FOUND}（404 SOCIAL_122）を返し、
 * 投稿の実在・共有設定・フレンド関係を外部から判別できないようにする。</p>
 * <ul>
 *   <li>投稿が存在しない（論理削除済みを含む）</li>
 *   <li>{@code scope_type} が {@code TEAM} ではない（スコープ越境）</li>
 *   <li>{@code status} が {@code PUBLISHED} ではない</li>
 *   <li>{@code share_with_friends} が TRUE ではない</li>
 *   <li>転送実行チームと投稿元チームがフレンド関係にない（自チームを含む）</li>
 * </ul>
 *
 * <p>{@code @Transactional} は持たず、呼び出し元 Service の取引に参加する（読み取りのみ）。</p>
 */
@Component
@RequiredArgsConstructor
public class FriendForwardSourceGuard {

    private final TimelinePostRepository timelinePostRepository;
    private final TeamFriendRepository teamFriendRepository;

    /**
     * 転送元投稿が転送可能であることを確かめ、投稿を返す。
     *
     * @param forwardingTeamId 転送を実行する自チーム ID
     * @param sourcePostId     転送元投稿 ID
     * @return 転送可能な転送元投稿
     * @throws BusinessException 不許可の場合（不在と同一の 404 SOCIAL_122）
     */
    public TimelinePostEntity requireForwardable(Long forwardingTeamId, Long sourcePostId) {
        TimelinePostEntity post = timelinePostRepository.findById(sourcePostId)
                .filter(p -> p.getScopeType() == PostScopeType.TEAM)
                .filter(p -> p.getStatus() == PostStatus.PUBLISHED)
                .filter(p -> Boolean.TRUE.equals(p.getShareWithFriends()))
                .filter(p -> p.getScopeId() != null)
                .orElseThrow(FriendForwardSourceGuard::notFound);

        long aId = Math.min(forwardingTeamId, post.getScopeId());
        long bId = Math.max(forwardingTeamId, post.getScopeId());
        if (aId == bId || teamFriendRepository.findByTeamAIdAndTeamBId(aId, bId).isEmpty()) {
            throw notFound();
        }
        return post;
    }

    private static BusinessException notFound() {
        return new BusinessException(SocialErrorCode.FRIEND_FORWARD_SOURCE_POST_NOT_FOUND);
    }
}
