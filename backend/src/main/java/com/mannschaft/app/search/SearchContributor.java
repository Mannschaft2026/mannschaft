package com.mannschaft.app.search;

import java.util.List;

/**
 * グローバル検索に 1 種別分の結果と件数を提供する契約（設計書 §1「アーキテクチャ」）。
 *
 * <p>各ドメイン（timeline / chat / bulletin / cms / filesharing / activity / auth / team /
 * organization）が<b>自ドメイン内で</b>実装し Bean 登録する。search ドメインは本契約の実装を
 * 集約するだけで、他ドメインの Repository を直接 DI しない。</p>
 *
 * <p><b>可視性の不変条件</b>: {@link #count} と {@link #find} は同一の可視性述語から算出すること
 * （取得後フィルタだけで件数を出さない）。権限範囲外のデータは結果にも件数にも出してはならない。
 * 所属スコープ数に比例した SQL（ループ内クエリ）を発行しないこと。</p>
 */
public interface SearchContributor {

    /** 本実装が担当する種別。 */
    SearchType type();

    /**
     * 閲覧者に見える該当件数（上限で頭打ちしない真の件数）。
     *
     * @param criteria 検索条件
     * @param viewer   閲覧者
     * @return 可視件数
     */
    long count(SearchCriteria criteria, SearchViewer viewer);

    /**
     * 閲覧者に見える該当データを 1 ページ分取得する（関連度→新しい順）。
     *
     * @param criteria 検索条件
     * @param viewer   閲覧者
     * @param offset   0 始まりの取得開始位置
     * @param limit    最大件数
     * @return 結果（{@link SearchHit#type()} は {@link #type()} と一致）
     */
    List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit);
}
