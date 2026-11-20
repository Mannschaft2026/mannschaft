package com.mannschaft.app.search.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.search.SearchErrorCode;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.dto.GlobalSearchResponse;
import com.mannschaft.app.search.dto.SearchSuggestionResponse;
import com.mannschaft.app.search.service.GlobalSearchQueryService;
import com.mannschaft.app.search.service.GlobalSearchRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * グローバル検索コントローラー（F04.6 §4）。横断検索およびサジェスト API を提供する。
 *
 * <p>クエリパラメータは文字列で受け、{@link GlobalSearchRequest#parse} で検証する（型変換の失敗も
 * {@code SEARCH_*} の 400 に揃える）。検証を通った検索だけが履歴に残る。</p>
 */
@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "グローバル検索", description = "F04.6 横断検索・サジェスト")
@RequiredArgsConstructor
public class GlobalSearchController {

    /** サジェスト件数の既定値。 */
    private static final int DEFAULT_SUGGEST_LIMIT = 5;

    /** サジェスト件数の上限。 */
    private static final int MAX_SUGGEST_LIMIT = 10;

    private final GlobalSearchQueryService globalSearchQueryService;

    /**
     * 横断検索を実行する。可視範囲は種別ごとの Contributor が閲覧者のスコープ単位ロールで判定する。
     *
     * <p><b>認可</b>: 本 EP はスコープ ID（{@code scopeId}）を受け取るため自己スコープ注釈（SelfScopedEndpoint）の対象外
     * （同注釈は「スコープ ID を受け取らない」ことが前提。番人 {@code SelfScopedEndpointScopeInputGuardTest}）。
     * 所属・権限の判定は {@link GlobalSearchQueryService#search} が {@code SearchViewerResolver#resolve} 経由で
     * {@code AccessControlService#findAffiliatedScopeIds}（正準の所属列挙）とスコープ単位ロールから母集団を作り、
     * {@code scopeType}/{@code scopeId} がその母集団に無い（非所属・GUEST のみ・削除済み・存在しない ID）場合は
     * Contributor を呼ばずに全種別 0 件の同一応答を返す（存在オラクルを作らない）。</p>
     */
    @GetMapping
    @Operation(summary = "横断検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検索成功")
    public ResponseEntity<GlobalSearchResponse> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String scopeId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        GlobalSearchRequest request = GlobalSearchRequest.parse(
                q, type, scopeType, scopeId, dateFrom, dateTo, authorId, page, size,
                // 閲覧者 TZ は ThreadLocal なので、Contributor の別スレッドへ渡る前にここで解決して条件に載せる
                UserZoneLocalDateTimeParser.requestZone());
        return ResponseEntity.ok(globalSearchQueryService.search(SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * 検索サジェストを取得する。KEYWORD は本人の履歴のみ、USER / TEAM / ORGANIZATION は検索本体と同じ
     * 可視性述語を通したもののみを返す。
     */
    @SelfScopedEndpoint("GlobalSearchQueryService#suggest は SecurityUtils.getCurrentUserId() 本人の検索履歴と、"
            + "検索本体と同じ可視性述語を通した USER / TEAM / ORGANIZATION のみを候補にする")
    @GetMapping("/suggestions")
    @Operation(summary = "検索サジェスト")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SearchSuggestionResponse>> suggest(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String limit) {
        String prefix = SearchQueryText.normalize(q);
        if (prefix.isEmpty()) {
            throw new BusinessException(SearchErrorCode.EMPTY_QUERY);
        }
        if (prefix.codePointCount(0, prefix.length()) > GlobalSearchRequest.MAX_QUERY_LENGTH) {
            throw new BusinessException(SearchErrorCode.QUERY_TOO_LONG);
        }
        int parsedLimit = parseLimit(limit);
        SearchSuggestionResponse response =
                globalSearchQueryService.suggest(SecurityUtils.getCurrentUserId(), prefix, parsedLimit);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    private static int parseLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SUGGEST_LIMIT;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1 || value > MAX_SUGGEST_LIMIT) {
                throw new BusinessException(SearchErrorCode.INVALID_PAGE);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(SearchErrorCode.INVALID_PAGE);
        }
    }
}
