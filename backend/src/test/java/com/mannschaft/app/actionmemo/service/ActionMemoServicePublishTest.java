package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.AvailableTeamResponse;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyResponse;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishDailyToTeamResponse;
import com.mannschaft.app.actionmemo.dto.PublishToTeamRequest;
import com.mannschaft.app.actionmemo.dto.PublishToTeamResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;

/**
 * {@link ActionMemoPublishingService} 単体テスト — 投稿機能（publishDaily・publishToTeam・publishDailyToTeam）。
 *
 * <p>元ファイル ActionMemoServiceTest.java から分割。以下の @Nested クラスを含む:</p>
 * <ul>
 *   <li>PublishDailyTest</li>
 *   <li>PublishToTeamTest</li>
 *   <li>PublishDailyToTeamTest</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoService 単体テスト — 投稿機能")
class ActionMemoServicePublishTest {

    @Mock
    private ActionMemoRepository memoRepository;

    @Mock
    private ActionMemoTagRepository tagRepository;

    @Mock
    private ActionMemoTagLinkRepository tagLinkRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    /** 旧個人投稿の論理削除は timeline ドメインへ委譲する（所有者・スコープ判定は TimelinePostServiceTest が検証）。 */
    @Mock
    private TimelinePostService timelinePostService;

    @Mock
    private ActionMemoSettingsService settingsService;

    @Mock
    private ActionMemoMetrics metrics;

    @Mock
    private TodoService todoService;

    @Mock
    private TodoStatusService todoStatusService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private Clock wallClock;

    @InjectMocks
    private ActionMemoService actionMemoService;

    @InjectMocks
    private ActionMemoPublishingService actionMemoPublishingService;

    @InjectMocks
    private ActionMemoScopeService actionMemoScopeService;

    @InjectMocks
    private ActionMemoAdminService actionMemoAdminService;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long MEMO_ID = 1L;
    private static final Instant CURRENT_INSTANT = Instant.parse("2026-04-09T15:30:00Z");
    private static final LocalDate CURRENT_DATE = LocalDate.of(2026, 4, 10);

    @BeforeEach
    void setUpWallClock() {
        lenient().when(wallClock.instant()).thenReturn(CURRENT_INSTANT);
        lenient().when(wallClock.getZone()).thenReturn(UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    /**
     * id と createdAt を設定済みの ActionMemoEntity を生成する。
     * publishDaily 本文組み立てでは createdAt の HH:mm が使われるため必須。
     */
    private ActionMemoEntity memoWithCreatedAt(Long id, Long userId, LocalDate memoDate,
                                                String content, ActionMemoMood mood,
                                                LocalDateTime createdAt) {
        ActionMemoEntity memo = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .mood(mood)
                .build();
        ReflectionTestUtils.setField(memo, "id", id);
        ReflectionTestUtils.setField(memo, "createdAt", createdAt);
        return memo;
    }

    /**
     * Phase 3 テスト用: id / createdAt / Phase3 フィールドを設定済みの ActionMemoEntity を生成する。
     */
    private ActionMemoEntity phase3Memo(Long id, Long userId, LocalDate memoDate,
                                        String content, ActionMemoCategory category,
                                        Integer durationMinutes, BigDecimal progressRate,
                                        Long relatedTodoId, boolean completesTodo,
                                        LocalDateTime createdAt) {
        ActionMemoEntity memo = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .category(category)
                .durationMinutes(durationMinutes)
                .progressRate(progressRate)
                .relatedTodoId(relatedTodoId)
                .completesTodo(completesTodo)
                .build();
        ReflectionTestUtils.setField(memo, "id", id);
        if (createdAt != null) {
            ReflectionTestUtils.setField(memo, "createdAt", createdAt);
        }
        return memo;
    }

    /**
     * Phase 3 テスト用: 自分所有の PERSONAL TODO を生成する。
     */
    private TodoEntity ownPersonalTodo(Long id, Long userId, String title, TodoStatus status) {
        TodoEntity todo = TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(userId)
                .title(title)
                .status(status)
                .priority(TodoPriority.MEDIUM)
                .createdBy(userId)
                .sortOrder(0)
                .build();
        ReflectionTestUtils.setField(todo, "id", id);
        return todo;
    }

    /**
     * Phase 3 テスト用: ユーザー設定エンティティを生成する。
     */
    private UserActionMemoSettingsEntity settingsOf(Long userId,
                                                    ActionMemoCategory defaultCategory,
                                                    Long defaultPostTeamId) {
        return UserActionMemoSettingsEntity.builder()
                .userId(userId)
                .moodEnabled(false)
                .defaultCategory(defaultCategory)
                .defaultPostTeamId(defaultPostTeamId)
                .build();
    }

    // ==================================================================
    // タイムライン投稿の擬似 DB（チーム投稿と個人投稿の列分割の試練用）
    // ==================================================================

    /** timeline_posts を模した擬似 DB。save で採番・登録し、findById はここから引く。 */
    private final Map<Long, TimelinePostEntity> postsDb = new HashMap<>();

    private final AtomicLong postIdSeq = new AtomicLong(5000L);

    /**
     * {@link TimelinePostRepository} の save / findById を擬似 DB に結線する。
     *
     * <p>findById も結線するのは、旧実装（findById で旧投稿を引いて論理削除する）でも
     * 実 DB と同じく投稿が見つかる状態にし、誤削除を red として観測するため。</p>
     */
    private void useInMemoryPostsDb() {
        lenient().when(timelinePostRepository.save(any(TimelinePostEntity.class)))
                .thenAnswer(inv -> {
                    TimelinePostEntity p = inv.getArgument(0);
                    if (p.getId() == null) {
                        ReflectionTestUtils.setField(p, "id", postIdSeq.getAndIncrement());
                    }
                    postsDb.put(p.getId(), p);
                    return p;
                });
        lenient().when(timelinePostRepository.findById(any()))
                .thenAnswer(inv -> Optional.ofNullable(postsDb.get((Long) inv.getArgument(0))));
        lenient().when(memoRepository.save(any(ActionMemoEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    /** 擬似 DB に既存投稿を1件登録する。 */
    private TimelinePostEntity existingPost(Long id, PostScopeType scopeType, Long scopeId, Long postUserId) {
        TimelinePostEntity post = TimelinePostEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .userId(postUserId)
                .content("既存投稿")
                .build();
        ReflectionTestUtils.setField(post, "id", id);
        postsDb.put(id, post);
        return post;
    }

    /** WORK メモ（チーム投稿可能）を作る。 */
    private ActionMemoEntity workMemo(Long id, LocalDate memoDate, int hour) {
        return phase3Memo(id, USER_ID, memoDate, "仕事メモ" + id, ActionMemoCategory.WORK,
                null, null, null, false, LocalDateTime.of(2026, 4, 9, hour, 0));
    }

    private PublishDailyRequest publishDailyRequest(LocalDate memoDate) {
        PublishDailyRequest req = new PublishDailyRequest();
        req.setMemoDate(memoDate);
        return req;
    }

    // ==================================================================
    // publishDaily（Phase 2）
    // ==================================================================

    @Nested
    @DisplayName("publishDaily")
    class PublishDailyTest {

        private static final LocalDate TARGET_DATE = LocalDate.of(2026, 4, 9);

        @Test
        @DisplayName("memoDate 省略時は wallClock の業務日を使用する")
        void publishDaily_usesWallClockDateWhenMemoDateIsNull() {
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, CURRENT_DATE))
                    .willReturn(List.of());

            PublishDailyRequest req = new PublishDailyRequest();

            assertThatThrownBy(() -> actionMemoPublishingService.publishDaily(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NO_MEMOS_FOR_DATE);

            verify(memoRepository).findByUserIdAndMemoDate(USER_ID, CURRENT_DATE);
        }

        @Test
        @DisplayName("正常系: 3件のメモ → PERSONAL タイムラインに1件 INSERT、各メモの timelinePostId を更新")
        void publishDaily_success() {
            List<ActionMemoEntity> memos = new ArrayList<>();
            memos.add(memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "朝散歩", null,
                    LocalDateTime.of(2026, 4, 9, 9, 15)));
            memos.add(memoWithCreatedAt(2L, USER_ID, TARGET_DATE, "会議準備", null,
                    LocalDateTime.of(2026, 4, 9, 10, 42)));
            memos.add(memoWithCreatedAt(3L, USER_ID, TARGET_DATE, "コード書いた", null,
                    LocalDateTime.of(2026, 4, 9, 14, 30)));

            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(memos);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);

            AtomicLong postIdSeq = new AtomicLong(1000L);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity post = inv.getArgument(0);
                        ReflectionTestUtils.setField(post, "id", postIdSeq.getAndIncrement());
                        return post;
                    });

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);

            PublishDailyResponse response = actionMemoPublishingService.publishDaily(req, USER_ID);

            assertThat(response).isNotNull();
            assertThat(response.getMemoCount()).isEqualTo(3);
            assertThat(response.getMemoDate()).isEqualTo(TARGET_DATE);
            assertThat(response.getTimelinePostId()).isEqualTo(1000L);

            ArgumentCaptor<TimelinePostEntity> postCaptor =
                    ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(timelinePostRepository).save(postCaptor.capture());
            TimelinePostEntity saved = postCaptor.getValue();
            assertThat(saved.getScopeType()).isEqualTo(PostScopeType.PERSONAL);
            assertThat(saved.getScopeId()).isEqualTo(USER_ID);
            assertThat(saved.getUserId()).isEqualTo(USER_ID);
            assertThat(saved.getContent())
                    .contains("## 2026-04-09 の行動ログ")
                    .contains("朝散歩")
                    .contains("会議準備")
                    .contains("コード書いた");

            // 全メモに timelinePostId が設定される
            assertThat(memos).allSatisfy(m ->
                    assertThat(m.getTimelinePostId()).isEqualTo(1000L));
            verify(metrics).incrementPublishDailySuccess();
        }

        @Test
        @DisplayName("AC-13 冪等性: 既存 timeline_post_id（自分の PERSONAL 投稿）は timeline サービスへ論理削除を委譲し、新投稿に差し替える（save(旧) は呼ばない）")
        void publishDaily_idempotentOverwrite() {
            useInMemoryPostsDb();
            ActionMemoEntity memoWithOldPost = memoWithCreatedAt(
                    1L, USER_ID, TARGET_DATE, "既存メモ", null,
                    LocalDateTime.of(2026, 4, 9, 11, 0));
            memoWithOldPost.setTimelinePostId(500L);
            List<ActionMemoEntity> memos = List.of(memoWithOldPost);

            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(memos);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);

            existingPost(500L, PostScopeType.PERSONAL, USER_ID, USER_ID);

            PublishDailyResponse response = actionMemoPublishingService.publishDaily(
                    publishDailyRequest(TARGET_DATE), USER_ID);

            // 旧投稿の論理削除は timeline ドメインのサービスへ委譲（save(旧) はしない）
            verify(timelinePostService, times(1)).softDeleteOwnPersonalPosts(Set.of(500L), USER_ID);
            // 新投稿 ID で差し替え
            Long newId = response.getTimelinePostId();
            assertThat(newId).isNotNull().isNotEqualTo(500L);
            assertThat(memoWithOldPost.getTimelinePostId()).isEqualTo(newId);
            verify(timelinePostService, times(1)).softDeleteOwnPersonalPosts(any(), org.mockito.ArgumentMatchers.eq(USER_ID));
            verify(timelinePostRepository, never()).findById(any());
            verify(timelinePostRepository, times(1)).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("AC-14 対象日にメモ 0 件 → 400（ACTION_MEMO_NO_MEMOS_FOR_DATE）で投稿の作成・削除なし")
        void publishDaily_failsWhenNoMemos() {
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(List.of());

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);

            assertThatThrownBy(() -> actionMemoPublishingService.publishDaily(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NO_MEMOS_FOR_DATE);

            verify(metrics).incrementPublishDailyError();
            verify(timelinePostRepository, never()).save(any(TimelinePostEntity.class));
            verify(timelinePostService, never()).softDeleteOwnPersonalPosts(any(), any());
            verify(timelinePostRepository, never()).findById(any());
        }

        @Test
        @DisplayName("AC-1 publishToTeam 済み（チーム投稿T）のメモを含む日に publishDaily してもチーム投稿 T は論理削除されない")
        void publishDaily_afterPublishToTeam_doesNotDeleteTeamPost() {
            useInMemoryPostsDb();
            ActionMemoEntity work = workMemo(1L, TARGET_DATE, 9);
            given(memoRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(work));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(work));

            Long teamPostId = actionMemoPublishingService
                    .publishToTeam(1L, new PublishToTeamRequest(42L, null), USER_ID).getTimelinePostId();
            TimelinePostEntity teamPost = postsDb.get(teamPostId);

            actionMemoPublishingService.publishDaily(publishDailyRequest(TARGET_DATE), USER_ID);

            assertThat(teamPost.getScopeType()).isEqualTo(PostScopeType.TEAM);
            assertThat(teamPost.getDeletedAt()).as("チーム投稿は論理削除されない").isNull();
            // チーム投稿 ID は旧個人投稿 ID に含まれないため、削除委譲自体が起きない
            verify(timelinePostService, never()).softDeleteOwnPersonalPosts(any(), any());
            assertThat(teamPost.getStatus()).isNotEqualTo(PostStatus.DELETED);
        }

        @Test
        @DisplayName("AC-2 AC-1 の後、メモは postedTeamId=元チーム・teamTimelinePostId=T・timelinePostId=新個人投稿ID を持つ")
        void publishDaily_afterPublishToTeam_keepsBothReferences() {
            useInMemoryPostsDb();
            ActionMemoEntity work = workMemo(1L, TARGET_DATE, 9);
            given(memoRepository.findByIdAndUserId(1L, USER_ID)).willReturn(Optional.of(work));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(work));

            Long teamPostId = actionMemoPublishingService
                    .publishToTeam(1L, new PublishToTeamRequest(42L, null), USER_ID).getTimelinePostId();
            Long personalPostId = actionMemoPublishingService
                    .publishDaily(publishDailyRequest(TARGET_DATE), USER_ID).getTimelinePostId();

            assertThat(work.getPostedTeamId()).isEqualTo(42L);
            assertThat(work.getTeamTimelinePostId()).isEqualTo(teamPostId);
            assertThat(work.getTimelinePostId()).isEqualTo(personalPostId);
            assertThat(postsDb.get(personalPostId).getScopeType()).isEqualTo(PostScopeType.PERSONAL);
        }

        @Test
        @DisplayName("AC-5 当日メモの旧投稿IDが複数種（P1,P2）でも削除委譲は1回のみ（ID集合 P1,P2）・findById 不呼出・save(旧) なし")
        void publishDaily_multipleOldPosts_fetchedOnceAndAllDeleted() {
            useInMemoryPostsDb();
            ActionMemoEntity m1 = memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "A", null,
                    LocalDateTime.of(2026, 4, 9, 9, 0));
            ActionMemoEntity m2 = memoWithCreatedAt(2L, USER_ID, TARGET_DATE, "B", null,
                    LocalDateTime.of(2026, 4, 9, 10, 0));
            ActionMemoEntity m3 = memoWithCreatedAt(3L, USER_ID, TARGET_DATE, "C", null,
                    LocalDateTime.of(2026, 4, 9, 11, 0));
            m1.setTimelinePostId(501L);
            m2.setTimelinePostId(502L);
            m3.setTimelinePostId(501L);
            existingPost(501L, PostScopeType.PERSONAL, USER_ID, USER_ID);
            existingPost(502L, PostScopeType.PERSONAL, USER_ID, USER_ID);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(m1, m2, m3));

            Long newId = actionMemoPublishingService
                    .publishDaily(publishDailyRequest(TARGET_DATE), USER_ID).getTimelinePostId();

            verify(timelinePostService).softDeleteOwnPersonalPosts(Set.of(501L, 502L), USER_ID);
            assertThat(List.of(m1, m2, m3)).allSatisfy(m -> assertThat(m.getTimelinePostId()).isEqualTo(newId));
            verify(timelinePostService, times(1)).softDeleteOwnPersonalPosts(any(), org.mockito.ArgumentMatchers.eq(USER_ID));
            verify(timelinePostRepository, never()).findById(any());
            verify(timelinePostRepository, times(1)).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("AC-6 timelinePostId が非 PERSONAL 投稿・別ユーザーの個人投稿（scopeId≠操作者）を指していても論理削除しない")
        void publishDaily_doesNotDeleteNonPersonalOrOtherUsersPost() {
            useInMemoryPostsDb();
            ActionMemoEntity m1 = memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "A", null,
                    LocalDateTime.of(2026, 4, 9, 9, 0));
            ActionMemoEntity m2 = memoWithCreatedAt(2L, USER_ID, TARGET_DATE, "B", null,
                    LocalDateTime.of(2026, 4, 9, 10, 0));
            m1.setTimelinePostId(601L);
            m2.setTimelinePostId(602L);
            existingPost(601L, PostScopeType.TEAM, 42L, USER_ID);
            existingPost(602L, PostScopeType.PERSONAL, OTHER_USER_ID, OTHER_USER_ID);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(m1, m2));

            actionMemoPublishingService.publishDaily(publishDailyRequest(TARGET_DATE), USER_ID);

            // 非 PERSONAL・他人投稿の除外判定は timeline 側（TimelinePostServiceTest）が持つ。
            // ここでは操作者 ID を添えて境界の向こうへ委譲していることを検証する。
            verify(timelinePostService).softDeleteOwnPersonalPosts(Set.of(601L, 602L), USER_ID);
            verify(timelinePostService, never()).softDeleteOwnPersonalPosts(any(), org.mockito.ArgumentMatchers.eq(OTHER_USER_ID));
        }

        @Test
        @DisplayName("AC-16 PERSONAL かつ scopeId=操作者でも投稿の userId≠操作者なら論理削除しない")
        void publishDaily_doesNotDeletePersonalPostAuthoredByOtherUser() {
            useInMemoryPostsDb();
            ActionMemoEntity m1 = memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "A", null,
                    LocalDateTime.of(2026, 4, 9, 9, 0));
            m1.setTimelinePostId(701L);
            existingPost(701L, PostScopeType.PERSONAL, USER_ID, OTHER_USER_ID);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(m1));

            actionMemoPublishingService.publishDaily(publishDailyRequest(TARGET_DATE), USER_ID);

            // 投稿者 userId の照合は timeline 側が行う。操作者 ID を渡して委譲していることを検証する。
            verify(timelinePostService).softDeleteOwnPersonalPosts(Set.of(701L), USER_ID);
        }

        @Test
        @DisplayName("AC-7 当日メモの timelinePostId が全て null（初回）なら論理削除は起きず、新規投稿1件が全メモに紐づく")
        void publishDaily_firstTime_noDeletionAndAllLinked() {
            useInMemoryPostsDb();
            ActionMemoEntity m1 = memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "A", null,
                    LocalDateTime.of(2026, 4, 9, 9, 0));
            ActionMemoEntity m2 = memoWithCreatedAt(2L, USER_ID, TARGET_DATE, "B", null,
                    LocalDateTime.of(2026, 4, 9, 10, 0));
            TimelinePostEntity unrelated = existingPost(801L, PostScopeType.PERSONAL, USER_ID, USER_ID);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(m1, m2));

            Long newId = actionMemoPublishingService
                    .publishDaily(publishDailyRequest(TARGET_DATE), USER_ID).getTimelinePostId();

            assertThat(unrelated.getDeletedAt()).isNull();
            verify(timelinePostService, never()).softDeleteOwnPersonalPosts(any(), any());
            assertThat(List.of(m1, m2)).allSatisfy(m -> assertThat(m.getTimelinePostId()).isEqualTo(newId));
            verify(timelinePostRepository, never()).findById(any());
            verify(timelinePostRepository, times(1)).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("AC-8 旧投稿が存在しない／既に論理削除済みでも例外にならず新規投稿が作られる")
        void publishDaily_oldPostMissingOrAlreadyDeleted_stillPublishes() {
            useInMemoryPostsDb();
            ActionMemoEntity m1 = memoWithCreatedAt(1L, USER_ID, TARGET_DATE, "A", null,
                    LocalDateTime.of(2026, 4, 9, 9, 0));
            ActionMemoEntity m2 = memoWithCreatedAt(2L, USER_ID, TARGET_DATE, "B", null,
                    LocalDateTime.of(2026, 4, 9, 10, 0));
            m1.setTimelinePostId(901L); // 擬似 DB に存在しない
            m2.setTimelinePostId(902L); // 既に論理削除済み
            TimelinePostEntity alreadyDeleted = existingPost(902L, PostScopeType.PERSONAL, USER_ID, USER_ID);
            alreadyDeleted.softDelete();
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(List.of(m1, m2));

            PublishDailyResponse response = actionMemoPublishingService
                    .publishDaily(publishDailyRequest(TARGET_DATE), USER_ID);

            Long newId = response.getTimelinePostId();
            assertThat(newId).isNotNull();
            assertThat(postsDb.get(newId).getDeletedAt()).isNull();
            assertThat(List.of(m1, m2)).allSatisfy(m -> assertThat(m.getTimelinePostId()).isEqualTo(newId));
            verify(timelinePostService, times(1)).softDeleteOwnPersonalPosts(any(), org.mockito.ArgumentMatchers.eq(USER_ID));
            verify(timelinePostRepository, never()).findById(any());
        }

        @Test
        @DisplayName("AC-15 上限200件のメモが同一旧投稿IDを共有しても 削除委譲1回（ID集合は1件）で、200件すべて新投稿に紐づく")
        void publishDaily_200MemosSharingOldPost_singleFetchAndDelete() {
            useInMemoryPostsDb();
            List<ActionMemoEntity> memos = new ArrayList<>();
            for (long i = 1; i <= 200; i++) {
                ActionMemoEntity m = memoWithCreatedAt(i, USER_ID, TARGET_DATE, "メモ" + i, null,
                        LocalDateTime.of(2026, 4, 9, 9, 0).plusMinutes(i));
                m.setTimelinePostId(500L);
                memos.add(m);
            }
            existingPost(500L, PostScopeType.PERSONAL, USER_ID, USER_ID);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE)).willReturn(memos);

            Long newId = actionMemoPublishingService
                    .publishDaily(publishDailyRequest(TARGET_DATE), USER_ID).getTimelinePostId();

            verify(timelinePostService).softDeleteOwnPersonalPosts(Set.of(500L), USER_ID);
            assertThat(memos).hasSize(200)
                    .allSatisfy(m -> assertThat(m.getTimelinePostId()).isEqualTo(newId));
            verify(timelinePostService, times(1)).softDeleteOwnPersonalPosts(any(), org.mockito.ArgumentMatchers.eq(USER_ID));
            verify(timelinePostRepository, never()).findById(any());
            // 新投稿の save 1回のみ（旧投稿の save なし）
            verify(timelinePostRepository, times(1)).save(any(TimelinePostEntity.class));
        }

        @Test
        @DisplayName("extra_comment XSS 対策: <script> タグが本文からサニタイズされる")
        void publishDaily_sanitizesExtraCommentXss() {
            List<ActionMemoEntity> memos = List.of(memoWithCreatedAt(
                    1L, USER_ID, TARGET_DATE, "メモ", null,
                    LocalDateTime.of(2026, 4, 9, 12, 0)));

            given(memoRepository.findByUserIdAndMemoDate(USER_ID, TARGET_DATE))
                    .willReturn(memos);
            given(settingsService.getMoodEnabled(USER_ID)).willReturn(false);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 3000L);
                        return p;
                    });

            PublishDailyRequest req = new PublishDailyRequest();
            req.setMemoDate(TARGET_DATE);
            req.setExtraComment("今日はよく動けた<script>alert('xss')</script>");

            actionMemoPublishingService.publishDaily(req, USER_ID);

            ArgumentCaptor<TimelinePostEntity> captor =
                    ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(timelinePostRepository).save(captor.capture());
            String savedContent = captor.getValue().getContent();
            assertThat(savedContent)
                    .contains("今日はよく動けた")
                    .doesNotContain("<script>")
                    .doesNotContain("</script>");
        }
    }

    // ------------------------------------------------------------------
    // PublishToTeamTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: publishToTeam（メモ個別チーム投稿）")
    class PublishToTeamTest {

        @Test
        @DisplayName("AC-3 publishDaily（個人投稿P）の後に publishToTeam すると timelinePostId=P のまま、teamTimelinePostId=新チーム投稿ID")
        void publishToTeam_afterPublishDaily_keepsPersonalPostId() {
            useInMemoryPostsDb();
            LocalDate date = LocalDate.of(2026, 4, 9);
            ActionMemoEntity work = workMemo(MEMO_ID, date, 9);
            given(memoRepository.findByUserIdAndMemoDate(USER_ID, date)).willReturn(List.of(work));
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID)).willReturn(Optional.of(work));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);

            Long personalPostId = actionMemoPublishingService
                    .publishDaily(publishDailyRequest(date), USER_ID).getTimelinePostId();
            Long teamPostId = actionMemoPublishingService
                    .publishToTeam(MEMO_ID, new PublishToTeamRequest(42L, null), USER_ID).getTimelinePostId();

            assertThat(teamPostId).isNotEqualTo(personalPostId);
            assertThat(work.getTimelinePostId()).as("個人投稿IDは上書きされない").isEqualTo(personalPostId);
            assertThat(work.getTeamTimelinePostId()).isEqualTo(teamPostId);
            assertThat(work.getPostedTeamId()).isEqualTo(42L);
            assertThat(postsDb.get(teamPostId).getScopeType()).isEqualTo(PostScopeType.TEAM);
        }

        @Test
        @DisplayName("AC-13 正常系: WORK / 自チーム / 未投稿 → チーム投稿IDは teamTimelinePostId に入り timelinePostId は変えない")
        void publishToTeam_workMemoOwnTeam_returnsTimelinePostId() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業ログ", ActionMemoCategory.WORK, 30, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9000L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);
            PublishToTeamResponse response = actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID);

            assertThat(response.getTimelinePostId()).isEqualTo(9000L);
            assertThat(response.getTeamId()).isEqualTo(42L);
            assertThat(response.getMemoId()).isEqualTo(MEMO_ID);
            assertThat(workMemo.getPostedTeamId()).isEqualTo(42L);
            assertThat(workMemo.getTeamTimelinePostId()).isEqualTo(9000L);
            assertThat(workMemo.getTimelinePostId()).as("timelinePostId は PERSONAL 投稿専用").isNull();
        }

        @Test
        @DisplayName("PRIVATE メモは ONLY_WORK_CAN_BE_POSTED で拒否")
        void publishToTeam_privateMemo_throws_ONLY_WORK_CAN_BE_POSTED() {
            ActionMemoEntity privateMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "私事", ActionMemoCategory.PRIVATE, null, null, null, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(privateMemo));

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_ONLY_WORK_CAN_BE_POSTED);
        }

        @Test
        @DisplayName("既に投稿済みのメモは ALREADY_POSTED で拒否")
        void publishToTeam_alreadyPosted_throws_ALREADY_POSTED() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.now());
            workMemo.setPostedTeamId(42L); // 既投稿

            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_ALREADY_POSTED);
        }

        @Test
        @DisplayName("非メンバーチームは TEAM_NOT_FOUND（IDOR 対策で 404）")
        void publishToTeam_notTeamMember_throws_TEAM_NOT_FOUND() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(false);

            PublishToTeamRequest req = new PublishToTeamRequest(42L, null);

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_TEAM_NOT_FOUND);
        }

        @Test
        @DisplayName("team_id 省略 + settings.defaultPostTeamId も NULL → TEAM_ID_REQUIRED")
        void publishToTeam_teamIdNullAndNoDefault_throws_TEAM_ID_REQUIRED() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.now());
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(settingsService.findSettings(USER_ID)).willReturn(Optional.empty());

            PublishToTeamRequest req = new PublishToTeamRequest(null, null);

            assertThatThrownBy(() -> actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_TEAM_ID_REQUIRED);
        }

        @Test
        @DisplayName("team_id 省略 + settings.defaultPostTeamId あり → デフォルトチームに投稿成功")
        void publishToTeam_teamIdNullUsesDefault_succeeds() {
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.now(),
                    "作業", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(settingsService.findSettings(USER_ID))
                    .willReturn(Optional.of(settingsOf(USER_ID, ActionMemoCategory.WORK, 99L)));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 99L)).willReturn(true);
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9100L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishToTeamRequest req = new PublishToTeamRequest(null, null);
            PublishToTeamResponse response = actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID);

            assertThat(response.getTeamId()).isEqualTo(99L);
            assertThat(response.getTimelinePostId()).isEqualTo(9100L);
        }

        @Test
        @DisplayName("本文フォーマット: HH:MM / duration / 進捗率 / 関連TODO / extra_comment(sanitized) を含む")
        void publishToTeam_contentFormat_includesHHMM_duration_progress_todoTitle_extraComment_sanitized() {
            // 9:15 (JST) / WORK / duration=30 / progressRate=70.5 / relatedTodoId=42
            LocalDateTime created = LocalDateTime.of(2026, 4, 27, 9, 15);
            ActionMemoEntity workMemo = phase3Memo(MEMO_ID, USER_ID, LocalDate.of(2026, 4, 27),
                    "朝の作業", ActionMemoCategory.WORK, 30, new BigDecimal("70.50"), 42L, false,
                    created);

            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(workMemo));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(todoRepository.findByIdAndDeletedAtIsNull(42L))
                    .willReturn(Optional.of(ownPersonalTodo(42L, USER_ID, "重要タスク", TodoStatus.OPEN)));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9200L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishToTeamRequest req = new PublishToTeamRequest(42L,
                    "順調です<script>alert(1)</script>");
            actionMemoPublishingService.publishToTeam(MEMO_ID, req, USER_ID);

            ArgumentCaptor<TimelinePostEntity> postCaptor =
                    ArgumentCaptor.forClass(TimelinePostEntity.class);
            verify(timelinePostRepository).save(postCaptor.capture());
            String content = postCaptor.getValue().getContent();

            // [HH:MM] {content}
            assertThat(content).contains("[09:15]").contains("朝の作業");
            // 実績時間
            assertThat(content).contains("30分");
            // 進捗率（trailing zeros stripped: 70.5）
            assertThat(content).contains("70.5%");
            // 関連 TODO タイトル
            assertThat(content).contains("重要タスク");
            // 末尾コメント
            assertThat(content).contains("順調です");
            // XSS サニタイズ（<script> は除去される）
            assertThat(content)
                    .doesNotContain("<script>")
                    .doesNotContain("</script>");
        }
    }

    // ------------------------------------------------------------------
    // PublishDailyToTeamTest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Phase 3: publishDailyToTeam（日次まとめチーム投稿）")
    class PublishDailyToTeamTest {

        @Test
        @DisplayName("WORK かつ未投稿のメモのみが対象になる")
        void publishDailyToTeam_filtersWorkAndUnposted() {
            // findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull が
            // WORK & postedTeamId=null のメモを返すことを mock で表現
            ActionMemoEntity m1 = phase3Memo(11L, USER_ID, CURRENT_DATE,
                    "メモ1", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 9, 0));
            ActionMemoEntity m2 = phase3Memo(12L, USER_ID, CURRENT_DATE,
                    "メモ2", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), eq(CURRENT_DATE), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(m1, m2));
            // publishToTeam の中で findByIdAndUserId が呼ばれる
            given(memoRepository.findByIdAndUserId(11L, USER_ID)).willReturn(Optional.of(m1));
            given(memoRepository.findByIdAndUserId(12L, USER_ID)).willReturn(Optional.of(m2));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9300L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);
            PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(req, USER_ID);

            assertThat(response.getPostedCount()).isEqualTo(2);
            assertThat(response.getTeamId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("repository が WORK 未投稿フィルタで返却するメモのみ投稿対象になる（既投稿はそもそも返らない）")
        void publishDailyToTeam_skipsAlreadyPosted() {
            // 既投稿メモ (postedTeamId != null) は repository 側で除外される設計のため、
            // mock の返却値には未投稿メモのみを含める。
            ActionMemoEntity unpostedWork = phase3Memo(11L, USER_ID, CURRENT_DATE,
                    "未投稿WORK", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 9, 0));

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), eq(CURRENT_DATE), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(unpostedWork));
            given(memoRepository.findByIdAndUserId(11L, USER_ID))
                    .willReturn(Optional.of(unpostedWork));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9400L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);
            PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(req, USER_ID);

            // 既投稿は repository 段階で除外されるため、postedCount は未投稿分のみ
            assertThat(response.getPostedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-10 複数メモを日次チーム投稿すると各メモの teamTimelinePostId は各自のチーム投稿IDになり timelinePostId は変化しない")
        void publishDailyToTeam_setsTeamPostIdPerMemo_andKeepsPersonalPostId() {
            useInMemoryPostsDb();
            ActionMemoEntity m1 = workMemo(11L, CURRENT_DATE, 9);
            ActionMemoEntity m2 = workMemo(12L, CURRENT_DATE, 10);
            m1.setTimelinePostId(500L); // 既に個人投稿済み
            existingPost(500L, PostScopeType.PERSONAL, USER_ID, USER_ID);

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), eq(CURRENT_DATE), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(m1, m2));
            given(memoRepository.findByIdAndUserId(11L, USER_ID)).willReturn(Optional.of(m1));
            given(memoRepository.findByIdAndUserId(12L, USER_ID)).willReturn(Optional.of(m2));

            actionMemoPublishingService.publishDailyToTeam(new PublishDailyToTeamRequest(42L), USER_ID);

            assertThat(m1.getTeamTimelinePostId()).isNotNull();
            assertThat(m2.getTeamTimelinePostId()).isNotNull().isNotEqualTo(m1.getTeamTimelinePostId());
            assertThat(postsDb.get(m1.getTeamTimelinePostId()).getScopeType()).isEqualTo(PostScopeType.TEAM);
            assertThat(postsDb.get(m2.getTeamTimelinePostId()).getScopeType()).isEqualTo(PostScopeType.TEAM);
            assertThat(m1.getTimelinePostId()).as("個人投稿IDは変化しない").isEqualTo(500L);
            assertThat(m2.getTimelinePostId()).as("個人投稿IDは変化しない").isNull();
        }

        @Test
        @DisplayName("当日 WORK メモが0件 → NO_WORK_MEMO_TODAY")
        void publishDailyToTeam_zeroWorkMemos_throws_NO_WORK_MEMO_TODAY() {
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), eq(CURRENT_DATE), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of());

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);

            assertThatThrownBy(() -> actionMemoPublishingService.publishDailyToTeam(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            ActionMemoErrorCode.ACTION_MEMO_NO_WORK_MEMO_TODAY);
        }

        @Test
        @DisplayName("postedCount は実際に投稿したメモ数と一致する")
        void publishDailyToTeam_postedCountMatches() {
            ActionMemoEntity m1 = phase3Memo(11L, USER_ID, CURRENT_DATE,
                    "M1", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 9, 0));
            ActionMemoEntity m2 = phase3Memo(12L, USER_ID, CURRENT_DATE,
                    "M2", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 10, 0));
            ActionMemoEntity m3 = phase3Memo(13L, USER_ID, CURRENT_DATE,
                    "M3", ActionMemoCategory.WORK, null, null, null, false,
                    LocalDateTime.of(2026, 4, 27, 11, 0));

            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, 42L)).willReturn(true);
            given(memoRepository.findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                    eq(USER_ID), eq(CURRENT_DATE), eq(ActionMemoCategory.WORK)))
                    .willReturn(List.of(m1, m2, m3));
            given(memoRepository.findByIdAndUserId(11L, USER_ID)).willReturn(Optional.of(m1));
            given(memoRepository.findByIdAndUserId(12L, USER_ID)).willReturn(Optional.of(m2));
            given(memoRepository.findByIdAndUserId(13L, USER_ID)).willReturn(Optional.of(m3));
            given(timelinePostRepository.save(any(TimelinePostEntity.class)))
                    .willAnswer(inv -> {
                        TimelinePostEntity p = inv.getArgument(0);
                        ReflectionTestUtils.setField(p, "id", 9500L);
                        return p;
                    });
            given(memoRepository.save(any(ActionMemoEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            PublishDailyToTeamRequest req = new PublishDailyToTeamRequest(42L);
            PublishDailyToTeamResponse response = actionMemoPublishingService.publishDailyToTeam(req, USER_ID);

            assertThat(response.getPostedCount()).isEqualTo(3);
            // timelinePostRepository.save が 3 回呼ばれる
            verify(timelinePostRepository, atLeastOnce()).save(any(TimelinePostEntity.class));
        }
    }
}
