package com.mannschaft.app.search.repository;

import com.mannschaft.app.search.entity.SearchHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 検索履歴リポジトリ。
 */
public interface SearchHistoryRepository extends JpaRepository<SearchHistoryEntity, Long> {

    /**
     * ユーザーの検索履歴を検索日時の降順で取得する（同時刻は ID 降順）。上限 50 件の保持・didYouMean・
     * サジェストに使う。
     */
    List<SearchHistoryEntity> findByUserIdOrderBySearchedAtDescIdDesc(Long userId);

    /**
     * ユーザーの検索履歴を新しい順に最大 20 件取得する（{@code GET /search/recent}）。
     */
    List<SearchHistoryEntity> findTop20ByUserIdOrderBySearchedAtDescIdDesc(Long userId);

    /**
     * 検索履歴を 1 文で原子的に UPSERT する（無ければ挿入、{@code (user_id, query)} の一意キーに当たれば
     * {@code searched_at} だけを更新）。同一ユーザー・同一語の検索が並行しても一意キー違反にならない。
     *
     * <p>{@code searched_at} は Java の壁時計を束縛せず {@code UTC_TIMESTAMP()} で埋める（格納基準は UTC。
     * JPA 経路は {@code hibernate.jdbc.time_zone=UTC} で UTC に変換して格納しており、それと揃える。
     * {@code RawSqlTimeColumnGuardTest} 参照）。</p>
     *
     * @return 影響行数（挿入 1 / 更新 2 / 同値更新 0。MySQL の ON DUPLICATE KEY UPDATE の規約）
     */
    @Modifying
    @Query(value = """
            INSERT INTO search_histories (user_id, query, searched_at)
            VALUES (:userId, :query, UTC_TIMESTAMP())
            ON DUPLICATE KEY UPDATE searched_at = UTC_TIMESTAMP()
            """, nativeQuery = true)
    int upsertSearchedNow(@Param("userId") Long userId, @Param("query") String query);

    /**
     * 指定 ID の本人の履歴を削除する（上限超過分の掃除）。並行する別の記録が先に同じ行を消していても
     * 0 件削除になるだけで失敗しない。
     *
     * @return 削除件数
     */
    @Modifying
    @Query("DELETE FROM SearchHistoryEntity h WHERE h.userId = :userId AND h.id IN :ids")
    int deleteByUserIdAndIdIn(@Param("userId") Long userId, @Param("ids") List<Long> ids);

    /**
     * ユーザーの検索履歴を全削除する。
     */
    void deleteByUserId(Long userId);

    /**
     * ユーザーIDと履歴IDで検索履歴を取得する。
     */
    Optional<SearchHistoryEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの検索履歴件数を取得する。
     */
    long countByUserId(Long userId);
}
