package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.effortrecord.service.EffortTimelineShareHydrator;
import com.mannschaft.app.timeline.dto.PostResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * F06.4.1 タイムラインの補充の走査上限（API 契約 §6: 1 回 1000 行）の単体試験（CMP-261115-2145）。
 *
 * <p>非表示（F00 を通らない努力由来）の投稿が大量に連続しても、1 回の要求で読む行・取得回数が履歴の件数に比例せず、
 * 非表示の投稿の ID を続きの目印に出さず、辿り切れば見える投稿を欠けも重複もなく返すことを確かめる。</p>
 */
@DisplayName("TimelineEffortReadGuard 走査上限")
class TimelineEffortReadGuardScanTest {

    private static final int TOTAL = 6000;
    private static final Set<Long> VISIBLE = Set.of(5990L, 5989L, 3000L, 1500L, 1499L, 10L);

    private TimelineEffortSourceService sources;
    private TimelineEffortReadGuard guard;
    private final Map<Long, UUID> shareOf = new HashMap<>();

    @BeforeEach
    void setUp() {
        sources = Mockito.mock(TimelineEffortSourceService.class);
        ContentVisibilityChecker checker = Mockito.mock(ContentVisibilityChecker.class);
        EffortTimelineShareHydrator hydrator = Mockito.mock(EffortTimelineShareHydrator.class);
        for (long id = 1; id <= TOTAL; id++) {
            if (!VISIBLE.contains(id)) {
                shareOf.put(id, UUID.randomUUID());
            }
        }
        when(sources.findShareUuidsByPostIds(anyCollection())).thenAnswer(inv -> {
            Map<Long, UUID> result = new HashMap<>();
            for (Object id : (Collection<?>) inv.getArgument(0)) {
                UUID share = shareOf.get((Long) id);
                if (share != null) {
                    result.put((Long) id, share);
                }
            }
            return result;
        });
        when(checker.filterAccessibleUuid(any(), anyCollection(), anyLong())).thenReturn(Set.of());
        guard = new TimelineEffortReadGuard(sources, checker, hydrator,
                new TimelineScanCursorCodec("unit-test-secret-key-for-timeline-cursor-0123456789"));
    }

    /** ID 降順のフィード（取得位置より小さい ID を 件数 + 1 件まで）。読んだ行数と取得回数を数える。 */
    private record Feed(AtomicInteger rows, AtomicInteger calls) {
        List<PostResponse> fetch(Long position, int count) {
            calls.incrementAndGet();
            List<PostResponse> out = new ArrayList<>();
            long from = position == null ? TOTAL : position - 1;
            for (long id = from; id >= 1 && out.size() < count + 1; id--) {
                out.add(PostResponse.builder().id(id).build());
            }
            rows.addAndGet(out.size());
            return out;
        }
    }

    @ParameterizedTest(name = "size={0}")
    @ValueSource(ints = {1, 2, 3, 20, 50})
    @DisplayName("非表示が数千行連続しても 1 回の読取は 1000 行・取得は定数回まで。目印に非表示 ID を出さず、辿り切ると見える投稿が欠けも重複もなく得られる")
    void 走査上限と進行保証(int size) {
        List<Long> got = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Long cursor = null;
        int requests = 0;
        while (true) {
            Feed feed = new Feed(new AtomicInteger(), new AtomicInteger());
            TimelineVisiblePage page = guard.fillVisiblePage(cursor, size, feed::fetch, 1L);
            requests++;
            assertThat(feed.rows().get()).as("1 回の要求で読む行（先読み込み）").isLessThanOrEqualTo(1000);
            assertThat(feed.calls().get()).as("取得・判定の回数は定数回").isLessThanOrEqualTo(30);
            assertThat(page.posts()).hasSizeLessThanOrEqualTo(size);
            page.posts().forEach(p -> got.add(p.getId()));
            Long next = page.nextCursor();
            if (next == null) {
                assertThat(page.hasNext()).isFalse();
                break;
            }
            assertThat(page.hasNext()).isTrue();
            assertThat(shareOf).as("目印は非表示の投稿 ID ではない").doesNotContainKey(next).doesNotContainKey(-next);
            if (next > 0) {
                assertThat(VISIBLE).contains(next);
            } else {
                assertThat(-next).as("継続位置は投稿 ID の範囲から推定できない値").isGreaterThan(TOTAL * 100L);
            }
            assertThat(seen.add(next)).as("同じ目印を二度返さない").isTrue();
            assertThat(requests).as("有限回で終わる").isLessThan(100);
            cursor = next;
        }
        assertThat(got).containsExactly(5990L, 5989L, 3000L, 1500L, 1499L, 10L);
    }

    @Test
    @DisplayName("非表示が少なければ従来どおり見えた投稿の ID を目印に、要求件数で埋めたページを返す")
    void 上限に届かなければ従来どおり() {
        Feed feed = new Feed(new AtomicInteger(), new AtomicInteger());
        // 新しい順は 6000〜5991 の 10 件が非表示、その後ろに見える 5990, 5989 が続く。要求 1 件なら 5990 が 1 ページ目で、
        // 5989 が「次がある」の根拠（先読み）になる。取得は要求件数 1 から倍々（1, 2, 4, 8 件）で、各回は「件数 + 先読み 1 行」を読む。
        //   1 回目 count=1: 6000, 5999 の 2 行（判定対象は 6000 のみ・非表示）
        //   2 回目 count=2: 5999, 5998, 5997 の 3 行（判定対象 5999, 5998・非表示）
        //   3 回目 count=4: 5997〜5993 の 5 行（判定対象 5997〜5994・非表示）
        //   4 回目 count=8: 5993〜5985 の 9 行（判定対象 5993〜5986 のうち 5990, 5989 が見え、件数 + 1 = 2 が揃って終わる）
        // 合計 4 回・19 行で、1000 行の上限には届かず暗号化トークンへ切り替わらない（目印は最後に見えた投稿の ID 5990）。
        TimelineVisiblePage page = guard.fillVisiblePage(null, 1, feed::fetch, 1L);
        assertThat(page.posts()).extracting(PostResponse::getId).containsExactly(5990L);
        assertThat(page.nextCursor()).isEqualTo(5990L);
        assertThat(page.hasNext()).isTrue();
        assertThat(feed.calls().get()).isEqualTo(4);
        assertThat(feed.rows().get()).isEqualTo(19);
    }

    @Test
    @DisplayName("ユーザー別一覧の取り直しも合計 1000 行・2 回まで")
    void ユーザー別一覧の上限() {
        AtomicInteger rows = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        List<PostResponse> posts = guard.fillVisibleAuthorPosts(5, limit -> {
            calls.incrementAndGet();
            List<PostResponse> out = new ArrayList<>();
            for (long id = TOTAL; id >= 1 && out.size() < limit; id--) {
                out.add(PostResponse.builder().id(id).build());
            }
            rows.addAndGet(out.size());
            return out;
        }, 1L);
        assertThat(rows.get()).isLessThanOrEqualTo(1000);
        assertThat(calls.get()).isLessThanOrEqualTo(2);
        assertThat(posts).extracting(PostResponse::getId).containsExactly(5990L, 5989L);
    }
}
