package com.mannschaft.app.team.repository;

import com.mannschaft.app.search.SearchLikeConditions;
import com.mannschaft.app.search.SearchScopeVisibilityPredicate;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 TEAM 種別）のキーワード検索クエリ（CMP-261116-0722）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）:
 * {@code (公開範囲の段を満たす) AND lifecycle_status = ACTIVE AND archived_at IS NULL AND deleted_at IS NULL}。
 * 公開範囲の段は {@link SearchScopeVisibilityPredicate}（チーム詳細の正準 {@code ContentVisibilityChecker} と同じ規則。
 * PUBLIC は誰でも、それ以外は閲覧者のそのチームでのロールが段の閾値以上）。所属者にもアーカイブ除外を適用する。</p>
 *
 * <p>語数に応じて LIKE 条件の数が変わるため JPQL を動的に組み立てる（値はすべてバインド）。
 * 閲覧可能なチーム ID は段ごとに IN 句のパラメータ 1 個に収まり、所属数に比例した SQL は発行しない。</p>
 */
@Repository
public class TeamKeywordSearchRepository {

    private static final List<String> COLUMNS = List.of("t.name", "t.nameKana");

    @PersistenceContext
    private EntityManager em;

    /**
     * 可視チームの件数。
     *
     * @param terms         検索語（AND）
     * @param visibility    公開範囲の段の述語（{@link #find} と同一インスタンスを渡す）
     * @param onlyTeamId    スコープ絞り込み（null なら絞らない）
     */
    public long count(List<String> terms,
            SearchScopeVisibilityPredicate<TeamEntity.Visibility> visibility, Long onlyTeamId) {
        Map<String, Object> params = new HashMap<>();
        String where = where(terms, visibility, onlyTeamId, params);
        TypedQuery<Long> query = em.createQuery("SELECT COUNT(t) FROM TeamEntity t" + where, Long.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }

    /** 可視チームを新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句）。 */
    public List<TeamEntity> find(List<String> terms,
            SearchScopeVisibilityPredicate<TeamEntity.Visibility> visibility, Long onlyTeamId,
            int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(terms, visibility, onlyTeamId, params);
        TypedQuery<TeamEntity> query = em.createQuery(
                "SELECT t FROM TeamEntity t" + where + " ORDER BY t.createdAt DESC, t.id DESC", TeamEntity.class);
        params.forEach(query::setParameter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    private static String where(List<String> terms,
            SearchScopeVisibilityPredicate<TeamEntity.Visibility> visibility, Long onlyTeamId,
            Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE t.lifecycleStatus = :active"
                + " AND t.archivedAt IS NULL AND t.deletedAt IS NULL");
        params.put("active", TeamEntity.LifecycleStatus.ACTIVE);
        visibility.appendTo(sb, "t", params);
        if (onlyTeamId != null) {
            sb.append(" AND t.id = :onlyTeamId");
            params.put("onlyTeamId", onlyTeamId);
        }
        sb.append(SearchLikeConditions.allTermsContain(COLUMNS, terms, params));
        return sb.toString();
    }
}
