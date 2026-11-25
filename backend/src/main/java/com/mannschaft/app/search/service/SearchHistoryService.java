package com.mannschaft.app.search.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.search.SearchErrorCode;
import com.mannschaft.app.search.SearchMapper;
import com.mannschaft.app.search.dto.SaveQueryRequest;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import com.mannschaft.app.search.repository.SearchSavedQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 検索履歴・保存済みクエリサービス（F04.6 §4〜§5）。
 *
 * <p>履歴・保存済み検索はすべて本人（{@code userId}）の行だけを読み書きし、他人の ID 指定は
 * 「見つからない」（404）に畳んで存在を明かさない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchHistoryService {

    /** 1 ユーザーが保持する検索履歴の上限。超過分は最古から物理削除する。 */
    static final int MAX_HISTORIES = 50;

    /** {@code GET /search/recent} が返す件数。 */
    static final int RECENT_LIMIT = 20;

    /** 1 ユーザーの保存済み検索の上限（超過は 409）。 */
    static final int MAX_SAVED_QUERIES = 10;

    /** didYouMean の候補にする Levenshtein 距離の上限。 */
    static final int DID_YOU_MEAN_MAX_DISTANCE = 2;

    /** 保存済み検索条件の JSON 検証用（Bean 依存を増やさない読み取り専用の用途）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchHistoryRepository searchHistoryRepository;
    private final SearchSavedQueryRepository searchSavedQueryRepository;
    private final SearchMapper searchMapper;

    /**
     * 検索履歴を記録する（入力検証・正規化を通った検索だけを呼ぶこと）。同一キーワードは検索日時を更新し、
     * 1 ユーザー {@value #MAX_HISTORIES} 件を超えた分は最古から削除する。
     *
     * <p>記録は {@code INSERT ... ON DUPLICATE KEY UPDATE} の 1 文で原子的に行う。FE は詳細ビューで検索語が
     * 変わると同じ q の検索を 2 本同時に送るため、check-then-insert では一意キー違反（500）になっていた。
     * 上限超過分の削除は ID 指定の 1 文で行い、並行する記録と同じ行を消し合っても 0 件削除で済む
     * （ロックするのは削除対象の古い行だけで、並行する記録の新しい行には触れない）。</p>
     *
     * @param userId          ユーザーID
     * @param normalizedQuery 正規化済みキーワード
     */
    @Transactional
    public void recordHistory(Long userId, String normalizedQuery) {
        searchHistoryRepository.upsertSearchedNow(userId, normalizedQuery);
        List<SearchHistoryEntity> all = searchHistoryRepository.findByUserIdOrderBySearchedAtDescIdDesc(userId);
        if (all.size() > MAX_HISTORIES) {
            List<Long> overflowIds = all.subList(MAX_HISTORIES, all.size()).stream()
                    .map(SearchHistoryEntity::getId)
                    .toList();
            searchHistoryRepository.deleteByUserIdAndIdIn(userId, overflowIds);
        }
    }

    /**
     * ユーザーの検索履歴を新しい順に最大 {@value #RECENT_LIMIT} 件取得する。
     *
     * @param userId ユーザーID
     * @return 検索履歴レスポンスリスト
     */
    public List<SearchHistoryResponse> listHistory(Long userId) {
        List<SearchHistoryEntity> entities =
                searchHistoryRepository.findTop20ByUserIdOrderBySearchedAtDescIdDesc(userId);
        return searchMapper.toHistoryResponseList(entities);
    }

    /**
     * 本人の検索履歴の前方一致候補を新しい順に返す（サジェストの KEYWORD。他人の履歴は使わない）。
     *
     * @param userId ユーザーID
     * @param prefix 入力中のキーワード
     * @param limit  最大件数
     * @return キーワード候補
     */
    public List<String> findOwnKeywordsByPrefix(Long userId, String prefix, int limit) {
        String lowerPrefix = prefix.toLowerCase(Locale.ROOT);
        return searchHistoryRepository.findByUserIdOrderBySearchedAtDescIdDesc(userId).stream()
                .map(SearchHistoryEntity::getQuery)
                .filter(q -> q.toLowerCase(Locale.ROOT).startsWith(lowerPrefix))
                .distinct()
                .limit(limit)
                .toList();
    }

    /**
     * 本人の検索履歴だけから「もしかして」の候補を返す（Levenshtein 距離 {@value #DID_YOU_MEAN_MAX_DISTANCE}
     * 以内で最も近い語。同距離なら新しい方。入力と同一の語は除く）。他人の検索語は使わない。
     *
     * @param userId          ユーザーID
     * @param normalizedQuery 正規化済みキーワード
     * @return 候補（無ければ null）
     */
    public String findDidYouMean(Long userId, String normalizedQuery) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (SearchHistoryEntity history : searchHistoryRepository.findByUserIdOrderBySearchedAtDescIdDesc(userId)) {
            String candidate = history.getQuery();
            if (candidate.equals(normalizedQuery)) {
                continue;
            }
            int distance = levenshtein(candidate, normalizedQuery, DID_YOU_MEAN_MAX_DISTANCE);
            if (distance <= DID_YOU_MEAN_MAX_DISTANCE && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * ユーザーの検索履歴を全削除する。
     *
     * @param userId ユーザーID
     */
    @Transactional
    public void deleteAllHistory(Long userId) {
        searchHistoryRepository.deleteByUserId(userId);
        log.info("検索履歴全削除: userId={}", userId);
    }

    /**
     * 検索履歴を個別削除する。他人の履歴 ID は見つからない扱い（404）。
     *
     * @param userId    ユーザーID
     * @param historyId 検索履歴ID
     */
    @Transactional
    public void deleteHistory(Long userId, Long historyId) {
        SearchHistoryEntity entity = searchHistoryRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(SearchErrorCode.HISTORY_NOT_FOUND));
        searchHistoryRepository.delete(entity);
        log.info("検索履歴削除: userId={}, historyId={}", userId, historyId);
    }

    /**
     * 保存済みクエリ一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 保存済みクエリレスポンスリスト
     */
    public List<SavedQueryResponse> listSavedQueries(Long userId) {
        List<SearchSavedQueryEntity> entities = searchSavedQueryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return searchMapper.toSavedQueryResponseList(entities);
    }

    /**
     * 検索クエリを保存する。{@code queryParams} は JSON オブジェクトとして検証し（不正は 400）、
     * 上限 {@value #MAX_SAVED_QUERIES} 件を超える保存は 409 にする。
     *
     * @param userId  ユーザーID
     * @param request 保存リクエスト
     * @return 保存済みクエリレスポンス
     */
    @Transactional
    public SavedQueryResponse saveQuery(Long userId, SaveQueryRequest request) {
        validateQueryParams(request.getQueryParams());
        long count = searchSavedQueryRepository.countByUserId(userId);
        if (count >= MAX_SAVED_QUERIES) {
            throw new BusinessException(SearchErrorCode.MAX_SAVED_QUERIES_EXCEEDED);
        }

        SearchSavedQueryEntity entity = SearchSavedQueryEntity.builder()
                .userId(userId)
                .name(request.getName())
                .queryParams(request.getQueryParams())
                .build();

        SearchSavedQueryEntity saved = searchSavedQueryRepository.save(entity);
        log.info("検索クエリ保存: userId={}, savedQueryId={}", userId, saved.getId());
        return searchMapper.toSavedQueryResponse(saved);
    }

    /**
     * 保存済み検索を削除する。他人の ID・存在しない ID はどちらも見つからない扱い（404）。
     *
     * @param userId       ユーザーID
     * @param savedQueryId 保存済み検索ID
     */
    @Transactional
    public void deleteSavedQuery(Long userId, Long savedQueryId) {
        SearchSavedQueryEntity entity = searchSavedQueryRepository.findByIdAndUserId(savedQueryId, userId)
                .orElseThrow(() -> new BusinessException(SearchErrorCode.SAVED_QUERY_NOT_FOUND));
        searchSavedQueryRepository.delete(entity);
        log.info("保存済み検索削除: userId={}, savedQueryId={}", userId, savedQueryId);
    }

    private static void validateQueryParams(String queryParams) {
        try {
            JsonNode node = JSON.readTree(queryParams);
            if (node == null || !node.isObject()) {
                throw new BusinessException(SearchErrorCode.INVALID_SAVED_QUERY_PARAMS);
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(SearchErrorCode.INVALID_SAVED_QUERY_PARAMS);
        }
    }

    /**
     * 打ち切り付き Levenshtein 距離（{@code max} を超えることが確定したら {@code max + 1} を返す）。
     */
    static int levenshtein(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] curr = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            curr[0] = i;
            int rowMin = curr[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, curr[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] tmp = prev;
            prev = curr;
            curr = tmp;
        }
        return prev[b.length()];
    }
}
