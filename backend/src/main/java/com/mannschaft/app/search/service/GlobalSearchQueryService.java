package com.mannschaft.app.search.service;

import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import com.mannschaft.app.search.dto.GlobalSearchResponse;
import com.mannschaft.app.search.dto.SearchSuggestionResponse;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * F04.6 グローバル検索の集約サービス（設計書 §6 検索実行フロー、CMP-261116-0722）。
 *
 * <p>search ドメインは<b>集約だけ</b>を担う。種別ごとの検索・可視性判定は各ドメインが実装する
 * {@link SearchContributor} に委ね、本クラスは他ドメインの Repository を一切参照しない。</p>
 *
 * <ul>
 *   <li>閲覧者（所属スコープとスコープ単位のロール）は {@link SearchViewerResolver} で 1 回だけ解決する。</li>
 *   <li>{@code scopeType} / {@code scopeId} が閲覧者の母集団に無い場合（非所属・GUEST のみ・存在しない ID）は
 *       Contributor を呼ばず、すべて 0 件の同一応答を返す（存在を明かさない）。</li>
 *   <li>Contributor は種別単位で並列に呼び、{@value #TIMEOUT_MILLIS} ms で打ち切る。例外・タイムアウトは
 *       その種別だけを {@code timedOutTypes} に入れて 0 件扱いにし、他種別の結果と件数は返す。</li>
 *   <li>{@code typeCounts} と結果は同じ Contributor の同一述語から出す（取得後フィルタで件数を作らない）。</li>
 * </ul>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 種別ごとに別スレッドで各ドメインの読み取りトランザクションを
 * 張るため、集約側でトランザクションを持たない（他ドメインへのトランザクション越境もしない。D-3 / D-3T）。</p>
 */
@Slf4j
@Service
public class GlobalSearchQueryService {

    /** 種別ごとのタイムアウト（設計書 §4 {@code timedOutTypes}）。 */
    static final long TIMEOUT_MILLIS = 3_000L;

    /** 概要ビューで種別ごとに返す最大件数。 */
    static final int OVERVIEW_LIMIT = 5;

    /** サジェストで USER / TEAM / ORGANIZATION ごとに返す最大件数。 */
    static final int SUGGEST_ENTITY_LIMIT = 2;

    /** サジェストの KEYWORD 最大件数。 */
    static final int SUGGEST_KEYWORD_LIMIT = 3;

    private static final List<SearchType> SUGGEST_ENTITY_TYPES =
            List.of(SearchType.USER, SearchType.TEAM, SearchType.ORGANIZATION);

    private final Map<SearchType, SearchContributor> contributors;
    private final SearchViewerResolver viewerResolver;
    private final SearchHistoryService searchHistoryService;
    private final ThreadPoolExecutor executor;

    public GlobalSearchQueryService(List<SearchContributor> contributorBeans,
                                    SearchViewerResolver viewerResolver,
                                    SearchHistoryService searchHistoryService) {
        Map<SearchType, SearchContributor> byType = new EnumMap<>(SearchType.class);
        for (SearchContributor contributor : contributorBeans) {
            SearchContributor previous = byType.put(contributor.type(), contributor);
            if (previous != null) {
                throw new IllegalStateException("SearchContributor が種別 " + contributor.type() + " に重複登録されています");
            }
        }
        this.contributors = byType;
        this.viewerResolver = viewerResolver;
        this.searchHistoryService = searchHistoryService;
        this.executor = new ThreadPoolExecutor(8, 64, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(512), new SearchThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 検索を実行する。入力検証済みの要求だけを受け取り、履歴を記録してから検索する。
     *
     * @param userId  閲覧者
     * @param request 検証済みの要求
     * @return 応答本体
     */
    public GlobalSearchResponse search(Long userId, GlobalSearchRequest request) {
        long started = System.nanoTime();
        SearchCriteria criteria = request.criteria();
        // F10.9 サポート閲覧中は、本人の検索履歴に残さない（閲覧のみ。本人の履歴・おすすめを汚さない）。
        if (!SupportAccessContextHolder.isActive()) {
            searchHistoryService.recordHistory(userId, criteria.query());
        }

        SearchViewer viewer = viewerResolver.resolve(userId);
        boolean scopeVisible = criteria.scopeType() == null
                || viewer.hasScope(criteria.scopeType(), criteria.scopeId());

        Map<SearchType, TypeResult> outcomes = scopeVisible
                ? runContributors(request, viewer)
                : Map.of();

        Map<SearchType, Long> typeCounts = new EnumMap<>(SearchType.class);
        for (SearchType type : SearchType.values()) {
            typeCounts.put(type, 0L);
        }
        List<SearchHit> results = new ArrayList<>();
        List<SearchType> timedOut = new ArrayList<>();
        Map<SearchType, Long> elapsedMillis = new LinkedHashMap<>();
        for (SearchType type : request.orderedTypes()) {
            TypeResult outcome = outcomes.get(type);
            if (outcome == null) {
                continue;
            }
            elapsedMillis.put(type, outcome.elapsedMillis());
            if (outcome.failed()) {
                timedOut.add(type);
                continue;
            }
            typeCounts.put(type, outcome.count());
            results.addAll(outcome.hits());
        }

        boolean allZero = typeCounts.values().stream().allMatch(c -> c == 0L);
        GlobalSearchResponse.ZeroResultsHelp help = allZero
                ? new GlobalSearchResponse.ZeroResultsHelp(
                        searchHistoryService.findDidYouMean(userId, criteria.query()),
                        broaderQuery(criteria.terms()))
                : null;

        GlobalSearchResponse.Meta meta;
        if (request.detail()) {
            SearchType type = request.orderedTypes().get(0);
            long total = typeCounts.get(type);
            int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + request.size() - 1) / request.size());
            meta = new GlobalSearchResponse.Meta(
                    GlobalSearchResponse.ViewMode.DETAIL, total, request.page(), request.size(), totalPages);
        } else {
            long total = typeCounts.values().stream().mapToLong(Long::longValue).sum();
            meta = new GlobalSearchResponse.Meta(GlobalSearchResponse.ViewMode.OVERVIEW, total, 0, OVERVIEW_LIMIT, 0);
        }

        log.info("グローバル検索: queryHash={}, viewMode={}, scopeVisible={}, timedOutTypes={}, elapsedMsByType={},"
                        + " totalMs={}", queryHash(criteria.query()), meta.viewMode(), scopeVisible, timedOut,
                elapsedMillis, (System.nanoTime() - started) / 1_000_000);

        return new GlobalSearchResponse(
                new GlobalSearchResponse.Data(List.copyOf(results), typeCounts, criteria.query(),
                        List.copyOf(timedOut), help),
                meta);
    }

    /**
     * サジェストを返す。KEYWORD は本人の履歴だけ、USER / TEAM / ORGANIZATION は検索本体と同じ
     * Contributor（同一の可視性述語）を通したものだけを候補にする。
     *
     * @param userId 閲覧者
     * @param prefix 入力中のキーワード（正規化済み・1 文字以上）
     * @param limit  最大件数
     * @return サジェスト
     */
    public SearchSuggestionResponse suggest(Long userId, String prefix, int limit) {
        List<SearchSuggestionResponse.Item> items = new ArrayList<>();
        for (String keyword : searchHistoryService.findOwnKeywordsByPrefix(
                userId, prefix, Math.min(limit, SUGGEST_KEYWORD_LIMIT))) {
            items.add(SearchSuggestionResponse.Item.keyword(keyword));
        }
        if (items.size() < limit) {
            SearchViewer viewer = viewerResolver.resolve(userId);
            SearchCriteria criteria = new SearchCriteria(prefix, List.of(prefix), null, null, null, null, null,
                    UserZoneLocalDateTimeParser.requestZone());
            Map<SearchType, Future<List<SearchHit>>> futures = new EnumMap<>(SearchType.class);
            for (SearchType type : SUGGEST_ENTITY_TYPES) {
                SearchContributor contributor = contributors.get(type);
                if (contributor == null) {
                    continue;
                }
                try {
                    futures.put(type, executor.submit(
                            () -> contributor.find(criteria, viewer, 0, SUGGEST_ENTITY_LIMIT)));
                } catch (RejectedExecutionException e) {
                    log.warn("検索サジェストの種別 {} を実行できませんでした（実行枠が満杯）", type);
                }
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
            for (Map.Entry<SearchType, Future<List<SearchHit>>> entry : futures.entrySet()) {
                for (SearchHit hit : await(entry.getKey(), entry.getValue(), deadline, List.<SearchHit>of())) {
                    items.add(toSuggestion(hit));
                }
            }
        }
        return new SearchSuggestionResponse(items.stream().limit(limit).toList());
    }

    private Map<SearchType, TypeResult> runContributors(GlobalSearchRequest request, SearchViewer viewer) {
        Map<SearchType, Future<TypeResult>> futures = new EnumMap<>(SearchType.class);
        Map<SearchType, TypeResult> outcomes = new EnumMap<>(SearchType.class);
        for (SearchType type : request.orderedTypes()) {
            SearchContributor contributor = contributors.get(type);
            if (contributor == null) {
                // 後続 PR で実装される種別。該当なしとして 0 件を返す（タイムアウト扱いにはしない）。
                outcomes.put(type, TypeResult.empty());
                continue;
            }
            try {
                futures.put(type, executor.submit(() -> runOne(contributor, request, viewer)));
            } catch (RejectedExecutionException e) {
                log.warn("グローバル検索の種別 {} を実行できませんでした（実行枠が満杯）", type);
                outcomes.put(type, TypeResult.failure(0L));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MILLIS);
        for (Map.Entry<SearchType, Future<TypeResult>> entry : futures.entrySet()) {
            outcomes.put(entry.getKey(),
                    await(entry.getKey(), entry.getValue(), deadline, TypeResult.failure(TIMEOUT_MILLIS)));
        }
        return outcomes;
    }

    /** 1 種別分の件数と結果を同一の述語で取得する。 */
    private static TypeResult runOne(SearchContributor contributor, GlobalSearchRequest request, SearchViewer viewer) {
        long started = System.nanoTime();
        SearchCriteria criteria = request.criteria();
        long count = contributor.count(criteria, viewer);
        List<SearchHit> hits;
        if (request.detail()) {
            long offset = request.offset();
            hits = offset >= count || offset > Integer.MAX_VALUE
                    ? List.of()
                    : contributor.find(criteria, viewer, (int) offset, request.size());
        } else {
            hits = contributor.find(criteria, viewer, 0, OVERVIEW_LIMIT);
        }
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        return new TypeResult(false, count, hits == null ? List.of() : List.copyOf(hits), elapsed);
    }

    /** 期限まで待ち、例外・タイムアウトは {@code fallback} に畳む（種別単位で切り離す）。 */
    private static <T> T await(SearchType type, Future<T> future, long deadlineNanos, T fallback) {
        try {
            long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("グローバル検索の種別 {} が {} ms を超えたため打ち切りました", type, TIMEOUT_MILLIS);
            return fallback;
        } catch (ExecutionException e) {
            log.warn("グローバル検索の種別 {} が失敗したためスキップしました", type, e.getCause());
            return fallback;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return fallback;
        }
    }

    private static SearchSuggestionResponse.Item toSuggestion(SearchHit hit) {
        return switch (hit.type()) {
            case USER -> new SearchSuggestionResponse.Item(hit.title(), "USER", hit.id(), null, null, hit.url());
            case TEAM -> new SearchSuggestionResponse.Item(hit.title(), "TEAM", null, hit.id(), null, hit.url());
            default -> new SearchSuggestionResponse.Item(
                    hit.title(), "ORGANIZATION", null, null, hit.id(), hit.url());
        };
    }

    /** 複数語のとき、末尾の語を 1 つ落とした機械的な候補（単一語は null）。 */
    static String broaderQuery(List<String> terms) {
        if (terms == null || terms.size() < 2) {
            return null;
        }
        return String.join(" ", terms.subList(0, terms.size() - 1));
    }

    /** ログに生のキーワードを出さないための SHA-256。 */
    static String queryHash(String query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(query.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 が利用できません", e);
        }
    }

    /** 1 種別分の実行結果。 */
    record TypeResult(boolean failed, long count, List<SearchHit> hits, long elapsedMillis) {

        static TypeResult empty() {
            return new TypeResult(false, 0L, List.of(), 0L);
        }

        static TypeResult failure(long elapsedMillis) {
            return new TypeResult(true, 0L, List.of(), elapsedMillis);
        }
    }

    /** 検索用のデーモンスレッド（名前で識別できるようにする）。 */
    private static final class SearchThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "global-search-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
