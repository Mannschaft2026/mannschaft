package com.mannschaft.app.search.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchErrorCode;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 入力検証を通った {@code GET /api/v1/search} の要求（設計書 §4「エラーレスポンス」）。
 *
 * <p>生の文字列パラメータを {@link #parse} で検証し、不正はすべて {@link SearchErrorCode} の 400 にする
 * （型変換エラーで別系統のエラーにしない）。検証を通らない検索は履歴に残さない（呼び出し側が
 * 本メソッドの成功後にだけ履歴を記録する）。</p>
 *
 * @param criteria 検索条件（正規化済み）
 * @param types    対象種別（宣言順）
 * @param detail   詳細ビューか（{@code type} 単一指定）
 * @param page     0 始まりのページ（詳細ビューのみ有効）
 * @param size     ページサイズ（詳細ビューのみ有効）
 */
public record GlobalSearchRequest(SearchCriteria criteria, Set<SearchType> types, boolean detail, int page,
                                  int size) {

    /** キーワードの最小文字数（正規化後）。 */
    public static final int MIN_QUERY_LENGTH = 2;

    /** キーワードの最大文字数（正規化後）。 */
    public static final int MAX_QUERY_LENGTH = 100;

    /** ページサイズの既定値。 */
    public static final int DEFAULT_SIZE = 20;

    /** ページサイズの上限。 */
    public static final int MAX_SIZE = 50;

    /**
     * 生のクエリパラメータを検証する。
     *
     * @throws BusinessException 不正な入力（400）
     */
    public static GlobalSearchRequest parse(String q, String type, String scopeType, String scopeId,
                                            String dateFrom, String dateTo, String authorId,
                                            String page, String size) {
        String query = parseQuery(q);
        Set<SearchType> types = parseTypes(type);
        boolean detail = type != null && !type.isBlank() && types.size() == 1;

        String normalizedScopeType = blankToNull(scopeType);
        String rawScopeId = blankToNull(scopeId);
        Long parsedScopeId = null;
        if (normalizedScopeType != null) {
            if (!SearchViewer.TEAM.equals(normalizedScopeType)
                    && !SearchViewer.ORGANIZATION.equals(normalizedScopeType)) {
                throw new BusinessException(SearchErrorCode.INVALID_SCOPE);
            }
            if (rawScopeId == null) {
                throw new BusinessException(SearchErrorCode.INVALID_SCOPE);
            }
            parsedScopeId = parsePositiveLong(rawScopeId, SearchErrorCode.INVALID_SCOPE);
        } else if (rawScopeId != null) {
            throw new BusinessException(SearchErrorCode.INVALID_SCOPE);
        }

        LocalDate from = parseDate(dateFrom);
        LocalDate to = parseDate(dateTo);
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(SearchErrorCode.INVALID_FILTER);
        }
        Long author = blankToNull(authorId) == null ? null
                : parsePositiveLong(authorId.trim(), SearchErrorCode.INVALID_FILTER);

        int parsedPage = parseInt(page, 0);
        int parsedSize = parseInt(size, DEFAULT_SIZE);
        if (parsedPage < 0 || parsedSize < 1 || parsedSize > MAX_SIZE) {
            throw new BusinessException(SearchErrorCode.INVALID_PAGE);
        }

        SearchCriteria criteria = new SearchCriteria(query, SearchQueryText.terms(query), normalizedScopeType,
                parsedScopeId, from, to, author);
        return new GlobalSearchRequest(criteria, types, detail, parsedPage, parsedSize);
    }

    /** {@code page * size}（int を超える場合は {@link Long} のまま返す）。 */
    public long offset() {
        return (long) page * size;
    }

    private static String parseQuery(String q) {
        String normalized = SearchQueryText.normalize(q);
        if (normalized.isEmpty()) {
            throw new BusinessException(SearchErrorCode.EMPTY_QUERY);
        }
        int length = normalized.codePointCount(0, normalized.length());
        if (length < MIN_QUERY_LENGTH) {
            throw new BusinessException(SearchErrorCode.QUERY_TOO_SHORT);
        }
        if (length > MAX_QUERY_LENGTH) {
            throw new BusinessException(SearchErrorCode.QUERY_TOO_LONG);
        }
        return normalized;
    }

    /** 大文字の列挙値名だけを受理する（小文字・未定義値・空要素は 400）。未指定は全種別。 */
    private static Set<SearchType> parseTypes(String type) {
        if (type == null || type.isBlank()) {
            return EnumSet.allOf(SearchType.class);
        }
        Set<SearchType> result = EnumSet.noneOf(SearchType.class);
        for (String token : type.split(",", -1)) {
            String name = token.trim();
            SearchType matched = null;
            for (SearchType candidate : SearchType.values()) {
                if (candidate.name().equals(name)) {
                    matched = candidate;
                }
            }
            if (matched == null) {
                throw new BusinessException(SearchErrorCode.INVALID_TYPE);
            }
            result.add(matched);
        }
        return result;
    }

    private static LocalDate parseDate(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(SearchErrorCode.INVALID_FILTER);
        }
    }

    private static Long parsePositiveLong(String raw, SearchErrorCode error) {
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new BusinessException(error);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new BusinessException(error);
        }
    }

    private static int parseInt(String raw, int defaultValue) {
        String value = blankToNull(raw);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new BusinessException(SearchErrorCode.INVALID_PAGE);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 対象種別を宣言順の一覧で返す。 */
    public List<SearchType> orderedTypes() {
        return List.copyOf(types);
    }
}
