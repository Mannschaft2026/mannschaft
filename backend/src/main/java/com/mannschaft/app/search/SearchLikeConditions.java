package com.mannschaft.app.search;

import java.util.List;
import java.util.Map;

/**
 * LIKE 系 Contributor が使う「全語 AND・各語は対象列のいずれかに部分一致」の JPQL 断片を組み立てる。
 *
 * <p>語の値は必ずバインドパラメータで渡し（{@link SearchQueryText#containsPattern} でエスケープ済み）、
 * JPQL 文字列には列名とパラメータ名しか埋め込まない。エスケープ文字 {@code \} は {@code ESCAPE} 句に
 * バインドパラメータ（{@value #ESCAPE_PARAM}）で渡す（SQL 文字列リテラルのバックスラッシュ解釈や
 * 方言ごとの既定エスケープに依存しない）。大文字小文字の区別は列の照合順序（{@code *_ci}）に委ねる。</p>
 */
public final class SearchLikeConditions {

    /** ESCAPE 句に渡すパラメータ名。 */
    public static final String ESCAPE_PARAM = "likeEscape";

    /** {@link SearchQueryText#escapeLike} が使うエスケープ文字。 */
    private static final Character ESCAPE_CHAR = '\\';

    private SearchLikeConditions() {
    }

    /**
     * {@code AND (c1 LIKE :kw0 OR c2 LIKE :kw0) AND (c1 LIKE :kw1 OR ...)} を返し、
     * パラメータを {@code params} に積む。
     *
     * @param columns 対象列（JPQL のパス式。例 {@code t.name}）
     * @param terms   検索語（未エスケープ）
     * @param params  バインドパラメータの格納先
     * @return 先頭に {@code " AND "} を持つ条件断片（語が無ければ空文字）
     */
    public static String allTermsContain(List<String> columns, List<String> terms, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        if (!terms.isEmpty()) {
            params.put(ESCAPE_PARAM, ESCAPE_CHAR);
        }
        for (int i = 0; i < terms.size(); i++) {
            String name = "kw" + i;
            params.put(name, SearchQueryText.containsPattern(terms.get(i)));
            sb.append(" AND (");
            for (int c = 0; c < columns.size(); c++) {
                if (c > 0) {
                    sb.append(" OR ");
                }
                sb.append(columns.get(c)).append(" LIKE :").append(name).append(" ESCAPE :").append(ESCAPE_PARAM);
            }
            sb.append(")");
        }
        return sb.toString();
    }
}
