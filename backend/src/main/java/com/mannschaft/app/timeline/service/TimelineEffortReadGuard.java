package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.service.EffortTimelineShareHydrator;
import com.mannschaft.app.timeline.TimelinePostSourceType;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 タイムラインの読み取り経路で努力由来の投稿を F00 に通し、通った投稿へ SharedRecord を hydrate する共通 source guard
 * （データ設計 §3・§7）。
 *
 * <p>timeline の read TX を終えてから（非 TX の呼出元で）、対応表で努力由来の投稿を 1 回で見分け、その share UUID を
 * {@link ContentVisibilityChecker#filterAccessibleUuid}（{@link ReferenceType#EFFORT_TEAM_SHARE}）で一括判定する。
 * 判定は effort ドメインの resolver（{@code EffortTeamShareVisibility}）に集約され、閲覧者の現在所属・チームの有効性・
 * team と owner の VISIBLE と版・投稿者の加入期間の資格を毎回確かめる。通った投稿には、effort の read hydrator
 * （{@link EffortTimelineShareHydrator}。同じ判定を読み込んだ行へ掛けてから組み立てる）で SharedRecord を
 * {@code effortShare} として載せ、{@code sourceType=PERSONAL_EFFORT_SHARE} を付ける。どちらかで落ちた努力由来の投稿は
 * 一覧から除く（fail closed）。通常の投稿はそのまま通し、本文が固定識別子と同じでも目印を付けない（対応表の有無だけで判定する）。
 * F00 の照会障害は 503（EFFORT_004）として伝わり、「0 件」に置き換えない。値はキャッシュへ固定保存しない。</p>
 */
@Component
@RequiredArgsConstructor
public class TimelineEffortReadGuard {

    private final TimelineEffortSourceService sources;
    private final ContentVisibilityChecker visibilityChecker;
    private final EffortTimelineShareHydrator hydrator;

    /** 一覧から F00 を通らない努力由来の投稿を除き、通った努力由来の投稿へ SharedRecord を載せる（順序はそのまま）。 */
    public List<PostResponse> filterVisible(List<PostResponse> posts, Long viewerUserId) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }
        Map<Long, UUID> effort = sources.findShareUuidsByPostIds(posts.stream().map(PostResponse::getId)
                .filter(Objects::nonNull).toList());
        if (effort.isEmpty()) {
            return posts;
        }
        Map<UUID, EffortSharedRecord> shared = visibleShares(effort.values(), viewerUserId);
        List<PostResponse> result = new ArrayList<>(posts.size());
        for (PostResponse post : posts) {
            UUID shareId = effort.get(post.getId());
            if (shareId == null) {
                result.add(post);
                continue;
            }
            EffortSharedRecord record = shared.get(shareId);
            if (record != null) {
                result.add(post.toBuilder().sourceType(TimelinePostSourceType.PERSONAL_EFFORT_SHARE)
                        .effortShare(record).build());
            }
        }
        return result;
    }

    /**
     * 投稿詳細を判定・hydrate する。努力由来でなければそのまま、努力由来なら F00 を通るときだけ SharedRecord を載せて返す。
     *
     * @return 見せてよい詳細。努力由来で F00 を通らなければ null（呼出元は存在しない投稿と同じ 404 にする）
     */
    public PostDetailResponse visibleDetail(PostDetailResponse detail, Long viewerUserId) {
        if (detail == null) {
            return null;
        }
        UUID shareId = sources.findShareUuidsByPostIds(List.of(detail.getId())).get(detail.getId());
        PostDetailResponse hydrated = detail;
        if (shareId != null) {
            EffortSharedRecord record = visibleShares(List.of(shareId), viewerUserId).get(shareId);
            if (record == null) {
                return null;
            }
            hydrated = detail.toBuilder().sourceType(TimelinePostSourceType.PERSONAL_EFFORT_SHARE)
                    .effortShare(record).build();
        }
        List<PostResponse> replies = hydrated.getRecentReplies();
        if (replies != null && !replies.isEmpty()) {
            hydrated = hydrated.toBuilder().recentReplies(filterVisible(replies, viewerUserId)).build();
        }
        return hydrated;
    }

    /** 投稿が努力由来でなければ true、努力由来なら F00 を通るときだけ true。 */
    public boolean isVisible(Long postId, Long viewerUserId) {
        Map<Long, UUID> effort = sources.findShareUuidsByPostIds(List.of(postId));
        UUID shareId = effort.get(postId);
        return shareId == null
                || visibilityChecker.canViewUuid(ReferenceType.EFFORT_TEAM_SHARE, shareId, viewerUserId);
    }

    /** F00 を通った共有だけを SharedRecord にする（F00 → hydrator の両方を通ったものだけ。1 ページにつき各 1 回）。 */
    private Map<UUID, EffortSharedRecord> visibleShares(Collection<UUID> shareIds, Long viewerUserId) {
        Set<UUID> allowed = visibilityChecker.filterAccessibleUuid(ReferenceType.EFFORT_TEAM_SHARE, shareIds,
                viewerUserId);
        if (allowed.isEmpty()) {
            return Map.of();
        }
        return hydrator.hydrate(allowed, viewerUserId);
    }
}
