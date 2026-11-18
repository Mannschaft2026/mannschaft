package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.search.SearchLikeConditions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 USER 種別）のキーワード検索クエリ（CMP-261116-0722）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）:
 * {@code id IN 閲覧者と同一スコープに在籍する利用者 AND is_searchable = TRUE AND status = ACTIVE
 * AND deleted_at IS NULL}。検索対象は平文の {@code display_name} / {@code nickname2} のみで、
 * 暗号化された氏名（{@code last_name} / {@code first_name}）は対象にしない。</p>
 *
 * <p>候補の利用者 ID 集合は IN 句のパラメータ 1 個で渡し、所属数に比例した SQL は発行しない。</p>
 */
@Repository
public class UserKeywordSearchRepository {

    private static final List<String> COLUMNS = List.of("u.displayName", "u.nickname2");

    @PersistenceContext
    private EntityManager em;

    /**
     * 可視ユーザーの件数。
     *
     * @param terms          検索語（AND）
     * @param candidateIds   閲覧者と同一スコープに在籍する利用者 ID（非空）
     */
    public long count(List<String> terms, Collection<Long> candidateIds) {
        Map<String, Object> params = new HashMap<>();
        String where = where(terms, candidateIds, params);
        TypedQuery<Long> query = em.createQuery("SELECT COUNT(u) FROM UserEntity u" + where, Long.class);
        params.forEach(query::setParameter);
        return query.getSingleResult();
    }

    /** 可視ユーザーを 1 ページ分取得する（{@link #count} と同一の WHERE 句）。 */
    public List<UserEntity> find(List<String> terms, Collection<Long> candidateIds, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(terms, candidateIds, params);
        TypedQuery<UserEntity> query = em.createQuery(
                "SELECT u FROM UserEntity u" + where + " ORDER BY u.createdAt DESC, u.id DESC", UserEntity.class);
        params.forEach(query::setParameter);
        query.setFirstResult(offset);
        query.setMaxResults(limit);
        return query.getResultList();
    }

    private static String where(List<String> terms, Collection<Long> candidateIds, Map<String, Object> params) {
        params.put("candidateIds", candidateIds);
        params.put("active", UserEntity.UserStatus.ACTIVE);
        return " WHERE u.id IN :candidateIds AND u.isSearchable = true AND u.status = :active"
                + " AND u.deletedAt IS NULL"
                + SearchLikeConditions.allTermsContain(COLUMNS, terms, params);
    }
}
