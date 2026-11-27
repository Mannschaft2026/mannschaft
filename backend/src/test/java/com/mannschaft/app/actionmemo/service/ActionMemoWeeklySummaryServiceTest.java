package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.service.BlogPostSystemSummaryReplacer;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoWeeklySummaryService} 単体テスト。
 *
 * <p>設計書 §7.1 の Phase 3 スコープ5項目を本ファイルで検証する:</p>
 * <ul>
 *   <li>正常系 — 当週にメモがあるユーザーのみ blog_posts に PRIVATE で INSERT</li>
 *   <li>0件ユーザースキップ — メモがないユーザーは blog_posts レコードを作成しない</li>
 *   <li>mood セクション分岐 — その週に {@code mood IS NOT NULL} が1件以上あれば
 *       テンプレートに気分セクションが含まれる</li>
 *   <li>OFF→ON→OFF パターン — 現在 {@code mood_enabled = false} でも過去のメモに mood が
 *       入っていれば気分セクションが表示される（本サービスは {@code mood_enabled} を参照しない）</li>
 *   <li>個別失敗の隔離 — 1ユーザーで例外が出ても次のユーザーに進む（次ユーザーの save が呼ばれる）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoWeeklySummaryService 単体テスト")
class ActionMemoWeeklySummaryServiceTest {

    @Mock
    private ActionMemoRepository memoRepository;

    @Mock
    private ActionMemoTagLinkRepository tagLinkRepository;

    @Mock
    private ActionMemoTagRepository tagRepository;

    @Mock
    private BlogPostSystemSummaryReplacer blogPostSystemSummaryReplacer;

    @Mock
    private ActionMemoMetrics actionMemoMetrics;

    @Mock
    private Clock wallClock;

    @InjectMocks
    private ActionMemoWeeklySummaryService weeklySummaryService;

    private static final Long USER_A = 100L;
    private static final Long USER_B = 200L;
    private static final Long USER_C = 300L;
    private static final LocalDate TODAY = LocalDate.of(2026, 4, 12);
    private static final LocalDateTime CURRENT_TIME = TODAY.atTime(21, 0);
    private static final Instant CURRENT_INSTANT = CURRENT_TIME
            .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE)
            .toInstant();

    @BeforeEach
    void setUpWallClock() {
        lenient().when(wallClock.instant()).thenReturn(CURRENT_INSTANT);
        lenient().when(wallClock.getZone()).thenReturn(UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    /**
     * 週次バッチ対象メモのヘルパー。
     */
    private ActionMemoEntity memo(Long userId, LocalDate memoDate, String content, ActionMemoMood mood) {
        return ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .mood(mood)
                .build();
    }

    // ==================================================================
    // 正常系
    // ==================================================================

    @Nested
    @DisplayName("generateWeeklySummaries 正常系")
    class NormalCaseTest {

        @Test
        @DisplayName("当週にメモがあるユーザーのみ blog_posts に PRIVATE で INSERT される")
        void generateWeeklySummaries_savesPrivateBlogPostForActiveUsers() {
            // USER_A: 3件のメモあり（mood なし）
            List<ActionMemoEntity> memosA = List.of(
                    memo(USER_A, TODAY.minusDays(2), "朝散歩した", null),
                    memo(USER_A, TODAY.minusDays(1), "数学ドリル", null),
                    memo(USER_A, TODAY.minusDays(1), "読書会 発表", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memosA);

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<String> slug = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<LocalDateTime> publishedAt = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(blogPostSystemSummaryReplacer, times(1)).replacePrivateUserPost(
                    eq(USER_A), slug.capture(), title.capture(), body.capture(), anyString(), publishedAt.capture());

            assertThat(title.getValue()).startsWith("週次ふりかえり:");
            assertThat(slug.getValue()).startsWith("weekly-");
            assertThat(body.getValue()).contains("# 週次ふりかえり:");
            assertThat(body.getValue()).contains("## 📊 今週のサマリー");
            assertThat(body.getValue()).contains("メモ件数: 3件");
            assertThat(publishedAt.getValue()).isEqualTo(CURRENT_TIME);

            verify(memoRepository).findDistinctUserIdsByMemoDateBetween(
                    TODAY.minusDays(7), TODAY.minusDays(1));
            verify(actionMemoMetrics, times(1)).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, never()).recordWeeklySummaryFailed();
        }
    }

    // ==================================================================
    // 再生成（同一週の既存記事の差し替え）
    // ==================================================================

    @Nested
    @DisplayName("再生成の冪等性")
    class RegenerationTest {

        @Test
        @DisplayName("再生成の論理削除と新規作成は cms 側の差し替え窓口 1 回に委譲され、actionmemo 側はトランザクションを張らない")
        void regenerateForUser_delegatesReplaceToCms() {
            LocalDate from = TODAY.minusDays(7);
            LocalDate to = TODAY.minusDays(1);
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(List.of(memo(USER_A, TODAY.minusDays(2), "朝散歩した", null)));

            assertThat(weeklySummaryService.regenerateForUser(USER_A, from, to)).isTrue();

            verify(blogPostSystemSummaryReplacer).replacePrivateUserPost(
                    eq(USER_A), eq("weekly-20260405-20260411"), anyString(), anyString(), anyString(), any());
            assertThat(ActionMemoWeeklySummaryService.class
                    .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        }
    }

    // ==================================================================
    // 0件ユーザースキップ
    // ==================================================================

    @Nested
    @DisplayName("0件ユーザースキップ")
    class ZeroMemoSkipTest {

        @Test
        @DisplayName("メモが0件のユーザーは blog_posts レコードを作成しない")
        void generateWeeklySummaries_skipsUserWithZeroMemos() {
            // findDistinctUserIdsByMemoDateBetween は通常 0件ユーザーを返さないが、
            // レースコンディション等で空リストが返る可能性を想定する
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(List.of());

            weeklySummaryService.generateWeeklySummaries();

            verifyNoInteractions(blogPostSystemSummaryReplacer);
            verify(actionMemoMetrics, never()).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, atLeastOnce()).recordWeeklySummarySkipped();
        }

        @Test
        @DisplayName("対象ユーザーが空リストの場合は何もしない")
        void generateWeeklySummaries_noopWhenUserListEmpty() {
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of());

            weeklySummaryService.generateWeeklySummaries();

            verifyNoInteractions(blogPostSystemSummaryReplacer);
            verify(actionMemoMetrics, never()).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, never()).recordWeeklySummarySkipped();
            verify(actionMemoMetrics, never()).recordWeeklySummaryFailed();
        }
    }

    // ==================================================================
    // mood セクション分岐
    // ==================================================================

    @Nested
    @DisplayName("mood セクション分岐")
    class MoodSectionTest {

        @Test
        @DisplayName("その週に mood IS NOT NULL が1件以上あれば気分セクションを含む")
        void generateWeeklySummaries_includesMoodSectionWhenAnyMoodPresent() {
            List<ActionMemoEntity> memos = List.of(
                    memo(USER_A, TODAY.minusDays(3), "散歩", null),
                    memo(USER_A, TODAY.minusDays(2), "数学", ActionMemoMood.GOOD),
                    memo(USER_A, TODAY.minusDays(1), "読書", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memos);

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(blogPostSystemSummaryReplacer).replacePrivateUserPost(
                    eq(USER_A), anyString(), anyString(), bodyCaptor.capture(), anyString(), any());
            String body = bodyCaptor.getValue();

            assertThat(body).contains("## 📈 気分の推移");
            assertThat(body).contains("平均気分:");
            // mood 絵文字が少なくとも1つ含まれる
            assertThat(body).contains("🙂");
        }

        @Test
        @DisplayName("その週の mood がすべて NULL なら気分セクションを含まない")
        void generateWeeklySummaries_omitsMoodSectionWhenAllNull() {
            List<ActionMemoEntity> memos = List.of(
                    memo(USER_A, TODAY.minusDays(3), "散歩", null),
                    memo(USER_A, TODAY.minusDays(1), "読書", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memos);

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(blogPostSystemSummaryReplacer).replacePrivateUserPost(
                    eq(USER_A), anyString(), anyString(), bodyCaptor.capture(), anyString(), any());
            String body = bodyCaptor.getValue();

            assertThat(body).doesNotContain("## 📈 気分の推移");
            assertThat(body).doesNotContain("平均気分:");
        }
    }

    // ==================================================================
    // OFF→ON→OFF パターン
    // ==================================================================

    @Nested
    @DisplayName("OFF→ON→OFF パターン")
    class MoodToggleTest {

        @Test
        @DisplayName("現在 mood_enabled=false でも過去の mood 入力データは気分セクションに反映される")
        void generateWeeklySummaries_respectsHistoricalMoodRegardlessOfCurrentSetting() {
            // 設計書 §5.5: mood セクション分岐は user_action_memo_settings.mood_enabled の
            // 現在値ではなく、その週のメモに mood IS NOT NULL が1件以上存在するかで判定する。
            // 本サービスは UserActionMemoSettingsRepository を参照しないため、
            // 「現在 OFF」の状態はモックでは表現しないが、mood 入力済みメモだけで
            // 気分セクションが生成されることを検証する。
            List<ActionMemoEntity> memosWithHistoricalMood = List.of(
                    memo(USER_A, TODAY.minusDays(5), "月曜の記録", ActionMemoMood.GREAT),
                    memo(USER_A, TODAY.minusDays(4), "火曜の記録", ActionMemoMood.GOOD),
                    memo(USER_A, TODAY.minusDays(3), "水曜の記録", ActionMemoMood.OK),
                    memo(USER_A, TODAY.minusDays(2), "木曜の記録", null),
                    memo(USER_A, TODAY.minusDays(1), "金曜の記録", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memosWithHistoricalMood);

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(blogPostSystemSummaryReplacer).replacePrivateUserPost(
                    eq(USER_A), anyString(), anyString(), bodyCaptor.capture(), anyString(), any());
            String body = bodyCaptor.getValue();

            // 気分セクションが含まれる（本サービスは UserActionMemoSettingsRepository を参照しない）
            assertThat(body).contains("## 📈 気分の推移");
            assertThat(body).contains("平均気分:");
            // 過去に入力された絵文字が反映される
            assertThat(body).contains("😄"); // GREAT
        }
    }

    // ==================================================================
    // 個別失敗の隔離
    // ==================================================================

    @Nested
    @DisplayName("個別失敗の隔離")
    class IsolationTest {

        @Test
        @DisplayName("1ユーザーで例外が出ても次のユーザーに進む（次ユーザーの save が呼ばれる）")
        void generateWeeklySummaries_continuesOnIndividualFailure() {
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            // USER_A: 正常（メモ1件）
            List<ActionMemoEntity> memosA = List.of(
                    memo(USER_A, TODAY.minusDays(1), "Aのメモ", null)
            );
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memosA);

            // USER_B: メモ取得で例外
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_B), any(), any()))
                    .willThrow(new RuntimeException("DB 接続エラー（模擬）"));

            // USER_C: 正常（メモ1件）
            List<ActionMemoEntity> memosC = List.of(
                    memo(USER_C, TODAY.minusDays(1), "Cのメモ", null)
            );
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_C), any(), any()))
                    .willReturn(memosC);


            weeklySummaryService.generateWeeklySummaries();

            // USER_A と USER_C の差し替えが呼ばれる（計2回）
            ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
            verify(blogPostSystemSummaryReplacer, times(2)).replacePrivateUserPost(
                    userIdCaptor.capture(), anyString(), anyString(), anyString(), anyString(), any());
            assertThat(userIdCaptor.getAllValues()).containsExactlyInAnyOrder(USER_A, USER_C);

            verify(actionMemoMetrics, times(2)).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, times(1)).recordWeeklySummaryFailed();
        }
    }
}
