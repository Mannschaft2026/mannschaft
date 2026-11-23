package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.timeline.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 タイムラインの読み取り経路で努力由来の投稿を F00 に通す共通 source guard（データ設計 §3・§7）。
 *
 * <p>timeline の read TX を終えてから（非 TX の呼出元で）、対応表で努力由来の投稿を 1 回で見分け、その share UUID を
 * {@link ContentVisibilityChecker#filterAccessibleUuid}（{@link ReferenceType#EFFORT_TEAM_SHARE}）で一括判定する。
 * 判定は effort ドメインの resolver に集約され、閲覧者の現在所属・チームの有効性・team と owner の VISIBLE と版・
 * 投稿者の加入期間の資格を毎回確かめる。通常の投稿はそのまま通す。F00 の照会障害は 503（EFFORT_004）として伝わり、
 * 「0 件」に置き換えない。</p>
 */
@Component
@RequiredArgsConstructor
public class TimelineEffortReadGuard {

    private final TimelineEffortSourceService sources;
    private final ContentVisibilityChecker visibilityChecker;

    /** 一覧から F00 を通らない努力由来の投稿を除く（通常の投稿・順序はそのまま）。 */
    public List<PostResponse> filterVisible(List<PostResponse> posts, Long viewerUserId) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }
        Map<Long, UUID> effort = sources.findShareUuidsByPostIds(posts.stream().map(PostResponse::getId)
                .filter(Objects::nonNull).toList());
        if (effort.isEmpty()) {
            return posts;
        }
        Set<UUID> allowed = visibilityChecker.filterAccessibleUuid(ReferenceType.EFFORT_TEAM_SHARE, effort.values(),
                viewerUserId);
        return posts.stream()
                .filter(p -> !effort.containsKey(p.getId()) || allowed.contains(effort.get(p.getId())))
                .toList();
    }

    /** 投稿が努力由来でなければ true、努力由来なら F00 を通るときだけ true。 */
    public boolean isVisible(Long postId, Long viewerUserId) {
        Map<Long, UUID> effort = sources.findShareUuidsByPostIds(List.of(postId));
        UUID shareId = effort.get(postId);
        return shareId == null
                || visibilityChecker.canViewUuid(ReferenceType.EFFORT_TEAM_SHARE, shareId, viewerUserId);
    }
}
