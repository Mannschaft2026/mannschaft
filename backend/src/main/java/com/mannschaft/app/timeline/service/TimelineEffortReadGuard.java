package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.service.EffortTimelineShareHydrator;
import com.mannschaft.app.timeline.TimelinePostSourceType;
import com.mannschaft.app.timeline.dto.PostDetailResponse;
import com.mannschaft.app.timeline.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

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
 * F00 の照会障害は 503（EFFORT_004）として伝わり、「0 件」に置き換えない。努力由来かを見分ける対応表の照会の障害も、
 * 通常の投稿として素通しせず同じ 503（EFFORT_004）にする（{@link #effortShareIds}）。値はキャッシュへ固定保存しない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimelineEffortReadGuard {

    private final TimelineEffortSourceService sources;
    private final ContentVisibilityChecker visibilityChecker;
    private final EffortTimelineShareHydrator hydrator;
    /** 走査上限で打ち切ったときの継続位置（暗号化した負の数のカーソル）の符号化。 */
    private final TimelineScanCursorCodec scanCursors;

    /** 一覧から F00 を通らない努力由来の投稿を除き、通った努力由来の投稿へ SharedRecord を載せる（順序はそのまま）。 */
    public List<PostResponse> filterVisible(List<PostResponse> posts, Long viewerUserId) {
        return filter(posts, viewerUserId, true);
    }

    /**
     * 特定ユーザーの投稿一覧（{@code GET /timeline/users/{userId}/posts}）用。{@link #filterVisible} と同じ判定に加え、
     * 著者を匿名にした共有（退会・purge 後）を載せる投稿を除く。利用者 ID で引く一覧に残すと、匿名の共有本文が
     * 過去の利用者 ID に紐付いてしまうため（退会フロー §13.13・API 契約 §4.2）。
     */
    public List<PostResponse> filterVisibleForAuthorPosts(List<PostResponse> posts, Long viewerUserId) {
        return filter(posts, viewerUserId, false);
    }

    /**
     * 1 回の要求で補充のために読む行の上限（API 契約 §6「1 回の走査上限は 1000 行」）。先読み行を含む。
     * 要求件数がこれを超える一覧（マイフィード・リプライは上限なし）は、要求件数 + 1 行までを上限にする。
     */
    public static final int MAX_SCAN_ROWS = 1000;
    /**
     * 補充 1 回あたりの取得件数の上限。スコープ別フィードの取得（{@code getFeedPage}）が 1 回 50 件で切るため、
     * それを超えて頼むと「件数 + 1 件」の先読みが効かず続きを見落とす。要求件数が大きい一覧は要求件数まで。
     */
    static final int MAX_BATCH = 50;

    /**
     * カーソル方式の一覧を、見える投稿だけで要求件数まで埋めて 1 ページにする（フィード・マイフィード・リプライ共通）。
     *
     * <p>非表示の投稿は飛ばし、足りない分は続きを取得し直して補う。取得件数は要求件数から倍々に増やし（1 回
     * {@link #MAX_BATCH} 件まで）、判定はバッチ単位の一括（1 件ずつ照会しない）。1 回の要求で読む行は
     * {@link #MAX_SCAN_ROWS} 行まで（先読み込み）で、取得・判定の回数も定数回に収まる（履歴の件数に比例しない）。</p>
     *
     * <ul>
     *   <li>上限までに要求件数 + 1 件（先読み）の見える投稿が揃えば、{@code nextCursor} は返したページで最後に
     *       見えた投稿の ID、{@code hasNext=true}。</li>
     *   <li>取得元の末尾に届けば {@code hasNext=false}・{@code nextCursor=null}（残りが非表示だけのときも同じ）。</li>
     *   <li>上限で打ち切ったときは、それまでに見えた投稿（0 件〜要求件数。空ページもあり得る）と {@code hasNext=true}、
     *       {@code nextCursor} に最後に読んだ行の位置を {@link TimelineScanCursorCodec} で暗号化した負の数を返す。
     *       非表示の投稿の ID はどの項目にも出ず、トークンから推定もできない。次の要求はその位置の続きから読むため、
     *       同じ行を読み直さず必ず前進し、辿り切れば見える投稿を欠けも重複もなく返す。</li>
     * </ul>
     *
     * @param cursor   起点カーソル（null なら先頭。正なら見えた投稿の ID、負なら継続位置トークン）
     * @param pageSize 要求件数（1 以上）
     * @param fetch    (取得位置, 件数) → 取得位置より後の最大 件数 + 1 件（先読み 1 件込み）の取得結果。TX は呼出先で完結させる
     * @param viewerUserId 閲覧者
     */
    public TimelineVisiblePage fillVisiblePage(Long cursor, int pageSize,
                                               java.util.function.BiFunction<Long, Integer, List<PostResponse>> fetch,
                                               Long viewerUserId) {
        int size = Math.max(pageSize, 1);
        int budget = Math.max(MAX_SCAN_ROWS, size + 1);
        int batchCap = Math.max(size, MAX_BATCH);
        List<PostResponse> collected = new ArrayList<>();
        Long position = scanCursors.toPosition(cursor);
        int read = 0;
        int batchSize = size;
        while (true) {
            int count = Math.min(batchSize, budget - read - 1);
            List<PostResponse> raw = fetch.apply(position, count);
            if (raw == null || raw.isEmpty()) {
                break;
            }
            read += raw.size();
            boolean sourceHasMore = raw.size() > count;
            List<PostResponse> batch = sourceHasMore ? raw.subList(0, count) : raw;
            List<PostResponse> visible = filter(batch, viewerUserId, true);
            if (visible != null) {
                collected.addAll(visible);
            }
            if (collected.size() > size || !sourceHasMore) {
                break;
            }
            position = batch.get(batch.size() - 1).getId();
            if (budget - read - 1 < 1) {
                // 走査上限: 見えた分（要求件数以下）だけ返し、続きは最後に読んだ行の位置を暗号化して渡す
                return new TimelineVisiblePage(collected, scanCursors.encode(position), true);
            }
            batchSize = Math.min(batchSize * 2, batchCap);
        }
        boolean hasNext = collected.size() > size;
        List<PostResponse> page = hasNext ? new ArrayList<>(collected.subList(0, size)) : collected;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new TimelineVisiblePage(page, nextCursor, hasNext);
    }

    /**
     * ユーザー別投稿一覧（作成日時降順・カーソル無し）を、見える投稿で要求件数まで埋める。応答にカーソルは無い。
     * 匿名の共有などを除いて足りないときは、取得件数を増やして先頭から 1 回だけ取り直す（取得は毎回 1 クエリ群で、
     * 1 件ずつ照会しない）。読む行は 2 回の合計で {@link #MAX_SCAN_ROWS} 行まで（要求件数がその半分以上なら
     * 取り直さず要求件数まで）で、届かなければ見えた分だけ返す（続きの目印を持たない一覧なので打ち切りを示す項目は無い）。
     *
     * @param pageSize 要求件数（1 以上）
     * @param fetch    取得件数 → 先頭からその件数までの取得結果（件数に満たなければ続きは無い）
     */
    public List<PostResponse> fillVisibleAuthorPosts(int pageSize,
                                                     java.util.function.IntFunction<List<PostResponse>> fetch,
                                                     Long viewerUserId) {
        int size = Math.max(pageSize, 1);
        int retryLimit = Math.max(MAX_SCAN_ROWS - size, size);
        int limit = size;
        while (true) {
            List<PostResponse> raw = fetch.apply(limit);
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            List<PostResponse> visible = filter(raw, viewerUserId, false);
            if (visible.size() >= size || raw.size() < limit || limit >= retryLimit) {
                return visible.size() > size ? new ArrayList<>(visible.subList(0, size)) : visible;
            }
            limit = retryLimit;
        }
    }

    private List<PostResponse> filter(List<PostResponse> posts, Long viewerUserId, boolean keepAnonymous) {
        if (posts == null || posts.isEmpty()) {
            return posts;
        }
        Map<Long, UUID> effort = effortShareIds(posts.stream().map(PostResponse::getId)
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
            if (record != null && (keepAnonymous || !isAnonymous(record))) {
                result.add(withShare(post, record));
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
        UUID shareId = effortShareIds(List.of(detail.getId())).get(detail.getId());
        PostDetailResponse hydrated = detail;
        if (shareId != null) {
            EffortSharedRecord record = visibleShares(List.of(shareId), viewerUserId).get(shareId);
            if (record == null) {
                return null;
            }
            hydrated = withShare(detail, record);
        }
        List<PostResponse> replies = hydrated.getRecentReplies();
        if (replies != null && !replies.isEmpty()) {
            hydrated = hydrated.toBuilder().recentReplies(filterVisible(replies, viewerUserId)).build();
        }
        return hydrated;
    }

    /** 投稿が努力由来でなければ true、努力由来なら F00 を通るときだけ true。 */
    public boolean isVisible(Long postId, Long viewerUserId) {
        Map<Long, UUID> effort = effortShareIds(List.of(postId));
        UUID shareId = effort.get(postId);
        return shareId == null
                || visibilityChecker.canViewUuid(ReferenceType.EFFORT_TEAM_SHARE, shareId, viewerUserId);
    }

    /**
     * 一覧の投稿へ SharedRecord を載せる。共有の著者が匿名（退会・purge 後）なら、外側の投稿者（{@code author.userId}・
     * {@code socialProfileId}・enrich 済みの {@code user}）も SharedRecord の著者と同じ匿名表現にそろえ、共有本文を
     * 過去の利用者 ID へ紐付けない（退会フロー §13.13: 本文は残し、書いた人の表示だけを匿名化する）。
     */
    private static PostResponse withShare(PostResponse post, EffortSharedRecord record) {
        PostResponse.PostResponseBuilder builder = post.toBuilder()
                .sourceType(TimelinePostSourceType.PERSONAL_EFFORT_SHARE).effortShare(record);
        if (isAnonymous(record)) {
            PostResponse.PostAuthorDto author = post.getAuthor();
            EffortSharedRecord.Author anonymous = record.author();
            builder.author(author == null ? null : new PostResponse.PostAuthorDto(null, null, author.postedAsType(), null))
                    .user(post.getUser() == null ? null
                            : new PostResponse.PostUserDto(anonymous.userId(), anonymous.displayName(), anonymous.avatarUrl()));
        }
        return builder.build();
    }

    /** 投稿詳細へ SharedRecord を載せる（匿名の共有なら外側の投稿者も匿名にする。{@link #withShare(PostResponse, EffortSharedRecord)} と同じ）。 */
    private static PostDetailResponse withShare(PostDetailResponse detail, EffortSharedRecord record) {
        PostDetailResponse.PostDetailResponseBuilder builder = detail.toBuilder()
                .sourceType(TimelinePostSourceType.PERSONAL_EFFORT_SHARE).effortShare(record);
        PostDetailResponse.PostAuthorDto author = detail.getAuthor();
        if (isAnonymous(record) && author != null) {
            builder.author(new PostDetailResponse.PostAuthorDto(null, null, author.postedAsType(), null));
        }
        return builder.build();
    }

    /** SharedRecord の著者が匿名（{@code userId=null}。退会・purge 後）か。 */
    private static boolean isAnonymous(EffortSharedRecord record) {
        return record.author() == null || record.author().userId() == null;
    }

    /**
     * 投稿 ID 集合のうち努力由来のものの post ID → share UUID（対応表の照会。PRIMARY の独立 TX）。
     *
     * <p>照会の障害（DB の照会失敗・TX/接続の開始失敗）は 503 {@code EFFORT_004} にする（API 契約 §6: source 照会障害）。
     * 「対応行なし＝通常の投稿」として素通しすると努力由来の投稿が F00 を通らずに出るため、空にも 500 にもしない。
     * ログには値・SQL を出さず例外種別だけを記録し、元の例外は cause として保つ。</p>
     */
    private Map<Long, UUID> effortShareIds(Collection<Long> postIds) {
        try {
            return sources.findShareUuidsByPostIds(postIds);
        } catch (DataAccessException | TransactionException e) {
            log.error("タイムライン投稿の努力由来判定（対応表の照会）に失敗: cause={}", e.getClass().getSimpleName());
            throw new BusinessException(EffortErrorCode.EFFORT_004, e);
        }
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
