package com.mannschaft.app.organization.repository;

import com.mannschaft.app.search.SearchLikeConditions;
import com.mannschaft.app.search.SearchScopeVisibilityPredicate;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 ORGANIZATION 種別）のキーワード検索クエリ（CMP-261116-0722）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）:
 * {@code (公開範囲の段を満たす) AND lifecycle_status = ACTIVE AND archived_at IS NULL AND deleted_at IS NULL}。
 * 公開範囲の段は {@link SearchScopeVisibilityPredicate}（組織詳細の正準 {@code ContentVisibilityChecker} と同じ規則。
 * PUBLIC は誰でも、PRIVATE は SCOPE_AFFILIATED＝その組織の所属者）。所属者にもアーカイブ除外を適用する。</p>
 *
 * <p>語数に応じて LIKE 条件の数が変わるため JPQL を動的に組み立てる（値はすべてバインド）。
 * 閲覧可能な組織 ID は段ごとに IN 句のパラメータ 1 個に収まり、所属数に比例した SQL は発行しない。</p>
 */
@Repository
public class OrganizationKeywordSearchRepository {

    private static final List<String> COLUMNS = List.of("o.name", "o.nameKana");

    @PersistenceContext
    private EntityManager em;

    /**
     * 可視組織の件数。
     *
     * @param terms         検索語（AND）
     * @param visibility   公開範囲の段の述語（{@link #find} と同一インスタンスを渡す）
     * @param onlyOrgId    スコープ絞り込み（null なら絞らない）
     */
    public long count(List<String> terms,
            SearchScopeVisibilityPredicate<OrganizationEntity.Visibility> visibility, Long onlyOrgId) {
        Map<String, Object> params = new HashMap<>();
        String where = where(terms, visibility, onlyOrgId, params);
        TypedQuery<Long> query = em.createQuery("SELECT COUNT(o) FROM OrganizationEntity o" + where, Long.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }

    /** 可視組織を新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句）。 */
    public List<OrganizationEntity> find(List<String> terms,
            SearchScopeVisibilityPredicate<OrganizationEntity.Visibility> visibility, Long onlyOrgId,
            int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(terms, visibility, onlyOrgId, params);
        TypedQuery<OrganizationEntity> query = em.createQuery(
                "SELECT o FROM OrganizationEntity o" + where + " ORDER BY o.createdAt DESC, o.id DESC",
                OrganizationEntity.class);
        params.forEach(query::setParameter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    private static String where(List<String> terms,
            SearchScopeVisibilityPredicate<OrganizationEntity.Visibility> visibility, Long onlyOrgId,
            Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE o.lifecycleStatus = :active"
                + " AND o.archivedAt IS NULL AND o.deletedAt IS NULL");
        params.put("active", OrganizationEntity.LifecycleStatus.ACTIVE);
        visibility.appendTo(sb, "o", params);
        if (onlyOrgId != null) {
            sb.append(" AND o.id = :onlyOrgId");
            params.put("onlyOrgId", onlyOrgId);
        }
        sb.append(SearchLikeConditions.allTermsContain(COLUMNS, terms, params));
        return sb.toString();
    }
}
