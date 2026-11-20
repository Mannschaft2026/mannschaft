package com.mannschaft.app.search.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.search.SearchErrorCode;
import com.mannschaft.app.search.SearchMapper;
import com.mannschaft.app.search.dto.SaveQueryRequest;
import com.mannschaft.app.search.dto.SavedQueryResponse;
import com.mannschaft.app.search.dto.SearchHistoryResponse;
import com.mannschaft.app.search.entity.SearchHistoryEntity;
import com.mannschaft.app.search.entity.SearchSavedQueryEntity;
import com.mannschaft.app.search.repository.SearchHistoryRepository;
import com.mannschaft.app.search.repository.SearchSavedQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SearchHistoryService} の単体テスト。
 * 検索履歴CRUD・保存済みクエリ管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchHistoryService 単体テスト")
class SearchHistoryServiceTest {

    @Mock
    private SearchHistoryRepository searchHistoryRepository;

    @Mock
    private SearchSavedQueryRepository searchSavedQueryRepository;

    @Mock
    private SearchMapper searchMapper;

    @InjectMocks
    private SearchHistoryService searchHistoryService;

    private static final Long USER_ID = 100L;
    private static final Long HISTORY_ID = 1L;

    // ========================================
    // recordHistory
    // ========================================
    @Nested
    @DisplayName("recordHistory")
    class RecordHistory {

        @Test
        @DisplayName("正常系: 新規クエリの場合は新規作成される")
        void 新規クエリの場合は新規作成される() {
            // given
            given(searchHistoryRepository.findByUserIdAndQuery(USER_ID, "テスト"))
                    .willReturn(Optional.empty());

            // when
            searchHistoryService.recordHistory(USER_ID, "テスト");

            // then
            verify(searchHistoryRepository).save(any(SearchHistoryEntity.class));
        }

        @Test
        @DisplayName("正常系: 既存クエリの場合は検索日時が更新される")
        void 既存クエリの場合は検索日時が更新される() {
            // given
            SearchHistoryEntity existing = SearchHistoryEntity.builder()
                    .userId(USER_ID).query("テスト").build();
            given(searchHistoryRepository.findByUserIdAndQuery(USER_ID, "テスト"))
                    .willReturn(Optional.of(existing));

            // when
            searchHistoryService.recordHistory(USER_ID, "テスト");

            // then
            verify(searchHistoryRepository, never()).save(any(SearchHistoryEntity.class));
        }

        @Test
        @DisplayName("正常系: 51件目を記録すると最古の1件だけが削除される")
        void 上限超過分は最古から削除される() {
            // given
            given(searchHistoryRepository.findByUserIdAndQuery(USER_ID, "新しい語")).willReturn(Optional.empty());
            List<SearchHistoryEntity> all = new java.util.ArrayList<>();
            for (int i = 0; i < 51; i++) {
                all.add(SearchHistoryEntity.builder().userId(USER_ID).query("語" + i).build());
            }
            given(searchHistoryRepository.findByUserIdOrderBySearchedAtDescIdDesc(USER_ID)).willReturn(all);

            // when
            searchHistoryService.recordHistory(USER_ID, "新しい語");

            // then
            verify(searchHistoryRepository).deleteAllInBatch(List.of(all.get(50)));
        }
    }

    @Nested
    @DisplayName("findDidYouMean")
    class FindDidYouMean {

        @Test
        @DisplayName("本人の履歴から距離2以内で最も近い語を返し、入力と同一の語は除く")
        void 本人の履歴から近い語を返す() {
            given(searchHistoryRepository.findByUserIdOrderBySearchedAtDescIdDesc(USER_ID)).willReturn(List.of(
                    SearchHistoryEntity.builder().userId(USER_ID).query("グランド").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("グラウンド").build(),
                    SearchHistoryEntity.builder().userId(USER_ID).query("まったく別の語").build()));

            assertThat(searchHistoryService.findDidYouMean(USER_ID, "グラウンド")).isEqualTo("グランド");
            assertThat(searchHistoryService.findDidYouMean(USER_ID, "zzzzzzzz")).isNull();
        }

        @Test
        @DisplayName("Levenshtein 距離は打ち切り付きで計算する")
        void levenshtein() {
            assertThat(SearchHistoryService.levenshtein("kitten", "sitting", 5)).isEqualTo(3);
            assertThat(SearchHistoryService.levenshtein("abc", "abc", 2)).isZero();
            assertThat(SearchHistoryService.levenshtein("a", "abcdef", 2)).isEqualTo(3);
        }
    }

    // ========================================
    // listHistory
    // ========================================
    @Nested
    @DisplayName("listHistory")
    class ListHistory {

        @Test
        @DisplayName("正常系: 検索履歴一覧を取得できる")
        void 検索履歴一覧を取得できる() {
            // given
            List<SearchHistoryEntity> entities = List.of(
                    SearchHistoryEntity.builder().userId(USER_ID).query("テスト").build());
            List<SearchHistoryResponse> expected = List.of(
                    new SearchHistoryResponse(1L, "テスト", LocalDateTime.now()));

            given(searchHistoryRepository.findTop20ByUserIdOrderBySearchedAtDescIdDesc(USER_ID)).willReturn(entities);
            given(searchMapper.toHistoryResponseList(entities)).willReturn(expected);

            // when
            List<SearchHistoryResponse> result = searchHistoryService.listHistory(USER_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // deleteAllHistory
    // ========================================
    @Nested
    @DisplayName("deleteAllHistory")
    class DeleteAllHistory {

        @Test
        @DisplayName("正常系: 検索履歴を全削除できる")
        void 検索履歴を全削除できる() {
            // when
            searchHistoryService.deleteAllHistory(USER_ID);

            // then
            verify(searchHistoryRepository).deleteByUserId(USER_ID);
        }
    }

    // ========================================
    // deleteHistory
    // ========================================
    @Nested
    @DisplayName("deleteHistory")
    class DeleteHistory {

        @Test
        @DisplayName("正常系: 検索履歴を個別削除できる")
        void 検索履歴を個別削除できる() {
            // given
            SearchHistoryEntity entity = SearchHistoryEntity.builder()
                    .userId(USER_ID).query("テスト").build();
            given(searchHistoryRepository.findByIdAndUserId(HISTORY_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // when
            searchHistoryService.deleteHistory(USER_ID, HISTORY_ID);

            // then
            verify(searchHistoryRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: 検索履歴が見つからない場合はエラー")
        void 検索履歴が見つからない場合はエラー() {
            // given
            given(searchHistoryRepository.findByIdAndUserId(HISTORY_ID, USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> searchHistoryService.deleteHistory(USER_ID, HISTORY_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SearchErrorCode.HISTORY_NOT_FOUND));
        }
    }

    // ========================================
    // saveQuery
    // ========================================
    @Nested
    @DisplayName("saveQuery")
    class SaveQuery {

        @Test
        @DisplayName("正常系: 検索クエリを保存できる")
        void 検索クエリを保存できる() {
            // given
            SaveQueryRequest req = new SaveQueryRequest("マイ検索", "{\"q\":\"テスト\"}");
            SearchSavedQueryEntity saved = SearchSavedQueryEntity.builder()
                    .userId(USER_ID).name("マイ検索").queryParams("{\"q\":\"テスト\"}").build();
            SavedQueryResponse expected = new SavedQueryResponse(1L, "マイ検索", "{\"q\":\"テスト\"}", LocalDateTime.now());

            given(searchSavedQueryRepository.countByUserId(USER_ID)).willReturn(5L);
            given(searchSavedQueryRepository.save(any(SearchSavedQueryEntity.class))).willReturn(saved);
            given(searchMapper.toSavedQueryResponse(any(SearchSavedQueryEntity.class))).willReturn(expected);

            // when
            SavedQueryResponse result = searchHistoryService.saveQuery(USER_ID, req);

            // then
            assertThat(result.getName()).isEqualTo("マイ検索");
        }

        @Test
        @DisplayName("異常系: 保存済みクエリが上限の場合はエラー")
        void 保存済みクエリが上限の場合はエラー() {
            // given
            SaveQueryRequest req = new SaveQueryRequest("マイ検索", "{}");
            given(searchSavedQueryRepository.countByUserId(USER_ID)).willReturn(10L);

            // when & then
            assertThatThrownBy(() -> searchHistoryService.saveQuery(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SearchErrorCode.MAX_SAVED_QUERIES_EXCEEDED));
        }

        @Test
        @DisplayName("異常系: queryParams が JSON オブジェクトでなければ 400 系エラーで保存しない")
        void 不正なJSONはエラー() {
            for (String invalid : List.of("{not json", "[1,2]", "\"q\"")) {
                SaveQueryRequest req = new SaveQueryRequest("壊れ", invalid);
                assertThatThrownBy(() -> searchHistoryService.saveQuery(USER_ID, req))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(SearchErrorCode.INVALID_SAVED_QUERY_PARAMS));
            }
            verify(searchSavedQueryRepository, never()).save(any(SearchSavedQueryEntity.class));
        }
    }

    @Nested
    @DisplayName("deleteSavedQuery")
    class DeleteSavedQuery {

        @Test
        @DisplayName("本人のものは削除し、他人・不在の ID は SAVED_QUERY_NOT_FOUND")
        void 本人のものだけ削除できる() {
            SearchSavedQueryEntity own = SearchSavedQueryEntity.builder()
                    .userId(USER_ID).name("n").queryParams("{}").build();
            given(searchSavedQueryRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(own));
            given(searchSavedQueryRepository.findByIdAndUserId(2L, USER_ID)).willReturn(Optional.empty());

            searchHistoryService.deleteSavedQuery(USER_ID, 1L);
            verify(searchSavedQueryRepository).delete(own);

            assertThatThrownBy(() -> searchHistoryService.deleteSavedQuery(USER_ID, 2L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(SearchErrorCode.SAVED_QUERY_NOT_FOUND));
        }
    }

    // ========================================
    // listSavedQueries
    // ========================================
    @Nested
    @DisplayName("listSavedQueries")
    class ListSavedQueries {

        @Test
        @DisplayName("正常系: 保存済みクエリ一覧を取得できる")
        void 保存済みクエリ一覧を取得できる() {
            // given
            List<SearchSavedQueryEntity> entities = List.of(
                    SearchSavedQueryEntity.builder().userId(USER_ID).name("テスト").queryParams("{}").build());
            List<SavedQueryResponse> expected = List.of(
                    new SavedQueryResponse(1L, "テスト", "{}", LocalDateTime.now()));

            given(searchSavedQueryRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(entities);
            given(searchMapper.toSavedQueryResponseList(entities)).willReturn(expected);

            // when
            List<SavedQueryResponse> result = searchHistoryService.listSavedQueries(USER_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }
}
