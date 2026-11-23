package com.mannschaft.app.search.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 検索履歴エンティティ。ユーザーの検索キーワード履歴を管理する。
 *
 * <p>行の記録（UPSERT）は {@code SearchHistoryRepository#upsertSearchedNow} の生 SQL で行い、本エンティティを
 * save しない（並行検索で check-then-insert が一意キー違反になるのを避けるため）。一意キー・索引は
 * {@code V4.023__create_search_histories_table.sql} と同じものを宣言し、{@code ddl-auto=create} の
 * テスト環境でも本番と同じ制約で UPSERT が働くようにする。</p>
 */
@Entity
@Table(name = "search_histories",
        uniqueConstraints = @UniqueConstraint(name = "uk_search_histories_user_query", columnNames = {"user_id", "query"}),
        indexes = @Index(name = "idx_search_histories_user_searched", columnList = "user_id, searched_at DESC"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class SearchHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String query;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime searchedAt = LocalDateTime.now();
}
