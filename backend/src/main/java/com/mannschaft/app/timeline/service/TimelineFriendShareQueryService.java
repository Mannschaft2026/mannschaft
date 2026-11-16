package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.dto.FriendShareableTeamPost;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * フレンドチームへ共有された TEAM 投稿を、他ドメインへ公開する読み取り専用の窓口（CMP-261103-0823）。
 *
 * <p>投稿側の可視性判定（実在・論理削除・{@code scope_type = TEAM}・{@code status = PUBLISHED}・
 * {@code share_with_friends = TRUE}）は timeline ドメインの責務としてここに閉じる。呼び出し側には
 * エンティティを渡さず {@link FriendShareableTeamPost} だけを返す。</p>
 *
 * <p>条件を満たさない投稿は、理由を問わず {@link Optional#empty()} を返す。呼び出し側は不在と
 * 区別できないため、投稿の実在・共有設定を外部へ判別させる応答（存在オラクル）を作れない。
 * フレンド関係・自チーム判定は呼び出し側（social）の責務。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimelineFriendShareQueryService {

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
}
