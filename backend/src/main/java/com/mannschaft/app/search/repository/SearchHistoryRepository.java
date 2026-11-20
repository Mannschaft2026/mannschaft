package com.mannschaft.app.search.repository;

import com.mannschaft.app.search.entity.SearchHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
     * ユーザーIDと検索クエリで既存の履歴を取得する。
     */
    Optional<SearchHistoryEntity> findByUserIdAndQuery(Long userId, String query);

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
