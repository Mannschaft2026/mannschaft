package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinReadStatusService} の単体テスト。
 * 既読マーク・既読者一覧を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinReadStatusService 単体テスト")
class BulletinReadStatusServiceTest {

    @Mock
    private BulletinReadStatusRepository readStatusRepository;

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinThreadService threadService;

    @Mock
    private BulletinMapper bulletinMapper;

    @Mock
    private BulletinAccessGuard accessGuard;

    @InjectMocks
    private BulletinReadStatusService bulletinReadStatusService;

    private static final Long THREAD_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;

    /** デフォルト（COUNT_ONLY モード）のスレッド。 */
    private BulletinThreadEntity createDefaultThread() {
        return BulletinThreadEntity.builder()
                .categoryId(5L).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("テスト").body("本文")
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY).build();
    }

    /** INDIVIDUAL モード（= 設計書 SHOW_READERS）のスレッド。 */
    private BulletinThreadEntity createIndividualThread() {
        return BulletinThreadEntity.builder()
                .categoryId(5L).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("テスト").body("本文")
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.INDIVIDUAL).build();
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("既読マーク_初回_保存とカウントインクリメント")
        void 既読マーク_初回_保存とカウントインクリメント() {
            // Given
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(readStatusRepository.existsByThreadIdAndUserId(THREAD_ID, USER_ID)).willReturn(false);

            // When
            bulletinReadStatusService.markAsRead(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            verify(readStatusRepository).save(any(BulletinReadStatusEntity.class));
            verify(threadRepository).save(thread);
            assertThat(thread.getReadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("既読マーク_既に既読_何もしない")
        void 既読マーク_既に既読_何もしない() {
            // Given
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(readStatusRepository.existsByThreadIdAndUserId(THREAD_ID, USER_ID)).willReturn(true);

            // When
            bulletinReadStatusService.markAsRead(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            verify(readStatusRepository, never()).save(any(BulletinReadStatusEntity.class));
        }
    }

    @Nested
    @DisplayName("listReadUsers")
    class ListReadUsers {

        @Test
        @DisplayName("既読者一覧取得_INDIVIDUALモード_readers返却")
        void 既読者一覧取得_INDIVIDUAL_readers返却() {
            // Given: INDIVIDUAL（SHOW_READERS）モードは readers をフル返却
            BulletinThreadEntity thread = createIndividualThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            BulletinReadStatusEntity reader = BulletinReadStatusEntity.builder()
                    .threadId(THREAD_ID).userId(11L).build();
            List<BulletinReadStatusEntity> entities = List.of(reader);
            given(readStatusRepository.findByThreadIdOrderByReadAtDesc(THREAD_ID)).willReturn(entities);
            given(bulletinMapper.toReadStatusResponseList(entities))
                    .willReturn(List.of(new ReadStatusResponse(1L, THREAD_ID, 11L, LocalDateTime.now())));

            // When
            List<ReadStatusResponse> result =
                    bulletinReadStatusService.listReadUsers(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, null);

            // Then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("既読者一覧取得_COUNT_ONLYモード_非ADMINはreaders非返却")
        void 既読者一覧取得_COUNT_ONLY_非ADMIN_空() {
            // Given: COUNT_ONLY モードかつ非 ADMIN は個人情報を返さない
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(accessGuard.isAdmin(USER_ID, SCOPE_TYPE, SCOPE_ID)).willReturn(false);

            // When
            List<ReadStatusResponse> result =
                    bulletinReadStatusService.listReadUsers(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, null);

            // Then: readers は返さない（件数は getReadCount で取得）
            assertThat(result).isEmpty();
            verify(readStatusRepository, never()).findByThreadIdOrderByReadAtDesc(any());
        }

        @Test
        @DisplayName("既読者一覧取得_filter=unread_非ADMIN_403")
        void 既読者一覧取得_unread_非ADMIN_403() {
            // Given
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(accessGuard.isAdmin(USER_ID, SCOPE_TYPE, SCOPE_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() ->
                    bulletinReadStatusService.listReadUsers(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, "unread"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("getReadCount")
    class GetReadCount {

        @Test
        @DisplayName("既読数取得_正常_カウント返却")
        void 既読数取得_正常_カウント返却() {
            // Given
            given(readStatusRepository.countByThreadId(THREAD_ID)).willReturn(5L);

            // When
            long result = bulletinReadStatusService.getReadCount(THREAD_ID);

            // Then
            assertThat(result).isEqualTo(5L);
        }
    }

    // ========================================================================
    // F08.7.1 大会スコープ（global 既読系）認可配線（B1/B3 取りこぼし）
    // ========================================================================
    @Nested
    @DisplayName("F08.7.1 大会連絡スコープ（global）")
    class TournamentScope {

        private static final Long T_SCOPE_ID = 500L;

        private BulletinThreadEntity tournamentThread() {
            return BulletinThreadEntity.builder()
                    .scopeType(ScopeType.TOURNAMENT).scopeId(T_SCOPE_ID).authorId(USER_ID)
                    .title("大会連絡").body("本文")
                    .readTrackingMode(ReadTrackingMode.INDIVIDUAL).build();
        }

        @Test
        @DisplayName("markAsReadGlobal: TOURNAMENT は tx 内で閲覧判定を撃たず（Controller で認可済み）checkMembership にも落ちない")
        void markAsRead大会はtx内で閲覧判定しない() {
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(tournamentThread()));
            given(readStatusRepository.existsByThreadIdAndUserId(any(), any())).willReturn(false);

            bulletinReadStatusService.markAsReadGlobal(THREAD_ID, USER_ID);

            verify(readStatusRepository).save(any());
            verify(accessGuard, never()).checkScopeContentAccess(any(), any(), any());
            verify(accessGuard, never()).checkMembershipForWrite(any(), any(), any());
        }

        @Test
        @DisplayName("listReadUsersGlobal: TOURNAMENT は呼び分けの誤りとして fail-closed で BULLETIN_002")
        void listReadUsersGlobal大会はfailClosed() {
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(tournamentThread()));

            assertThatThrownBy(() -> bulletinReadStatusService.listReadUsersGlobal(THREAD_ID, USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND));
            verify(readStatusRepository, never()).findByThreadIdOrderByReadAtDesc(any());
        }

        @Test
        @DisplayName("listTournamentReadUsers: 非モデレーターの INDIVIDUAL は既読者を返す")
        void 非モデレーターINDIVIDUALは既読者を返す() {
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(tournamentThread()));
            given(readStatusRepository.findByThreadIdOrderByReadAtDesc(THREAD_ID)).willReturn(List.of());
            given(bulletinMapper.toReadStatusResponseList(any())).willReturn(List.of());

            bulletinReadStatusService.listTournamentReadUsers(THREAD_ID, false, null);

            verify(readStatusRepository).findByThreadIdOrderByReadAtDesc(THREAD_ID);
            verify(accessGuard, never()).checkScopeContentAccess(any(), any(), any());
            verify(accessGuard, never()).checkMembershipForWrite(any(), any(), any());
        }

        @Test
        @DisplayName("listTournamentReadUsers: 非モデレーターの COUNT_ONLY は空配列（既読者を読まない）")
        void 非モデレーターCOUNT_ONLYは空() {
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.TOURNAMENT_DIVISION).scopeId(T_SCOPE_ID).authorId(USER_ID)
                    .title("大会連絡").body("本文")
                    .readTrackingMode(ReadTrackingMode.COUNT_ONLY).build();
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(thread));

            assertThat(bulletinReadStatusService.listTournamentReadUsers(THREAD_ID, false, null)).isEmpty();
            verify(readStatusRepository, never()).findByThreadIdOrderByReadAtDesc(any());
        }

        @Test
        @DisplayName("listTournamentReadUsers: モデレーター相当は COUNT_ONLY でも既読者を返す")
        void モデレーターはCOUNT_ONLYでも返す() {
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.TOURNAMENT).scopeId(T_SCOPE_ID).authorId(USER_ID)
                    .title("大会連絡").body("本文")
                    .readTrackingMode(ReadTrackingMode.COUNT_ONLY).build();
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(thread));
            given(readStatusRepository.findByThreadIdOrderByReadAtDesc(THREAD_ID)).willReturn(List.of());
            given(bulletinMapper.toReadStatusResponseList(any())).willReturn(List.of());

            bulletinReadStatusService.listTournamentReadUsers(THREAD_ID, true, "unread");

            verify(readStatusRepository).findByThreadIdOrderByReadAtDesc(THREAD_ID);
        }

        @Test
        @DisplayName("listTournamentReadUsers: filter=unread は非モデレーターなら 403 COMMON_002")
        void unreadは非モデレーター403() {
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(tournamentThread()));

            assertThatThrownBy(() -> bulletinReadStatusService.listTournamentReadUsers(THREAD_ID, false, "unread"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("listTournamentReadUsers: 大会以外のスレッドは fail-closed で BULLETIN_002")
        void 大会以外はfailClosed() {
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(T_SCOPE_ID).authorId(USER_ID)
                    .title("チーム").body("本文").build();
            given(threadRepository.findById(THREAD_ID)).willReturn(java.util.Optional.of(thread));

            assertThatThrownBy(() -> bulletinReadStatusService.listTournamentReadUsers(THREAD_ID, true, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND));
        }
    }
}
