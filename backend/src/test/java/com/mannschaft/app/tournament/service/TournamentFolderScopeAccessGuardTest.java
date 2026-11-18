package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentFolderScopeAccessGuard} の単体テスト（F08.7.1 / 04 §3 / §5、CMP-261116-0726）。
 *
 * <p>{@code /api/v1/files**} の読み取り経路が大会フォルダに対して {@link TournamentContactSpaceAccessGate} の
 * 閲覧判定を必ず通すこと（閲覧権なしはフォルダ ID 経路で {@code FILE_SHARING_001}、ファイル ID 経路で
 * {@code FILE_SHARING_002} の不在応答）、Controller 前段の存在秘匿つき投稿認可がゲートの投稿判定へ委譲されること、
 * tx 内の多層防御（{@code checkFolderPostBy*}）が従来どおり {@link TournamentContactAccessService#checkPost} を
 * 呼ぶこと、大会以外（TEAM/ORG/PERSONAL）スコープでは no-op であることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFolderScopeAccessGuard 単体テスト")
class TournamentFolderScopeAccessGuardTest {

    @Mock
    private SharedFolderRepository folderRepository;
    @Mock
    private SharedFileRepository fileRepository;
    @Mock
    private TournamentContactAccessService accessService;
    @Mock
    private TournamentContactSpaceAccessGate accessGate;

    @InjectMocks
    private TournamentFolderScopeAccessGuard guard;

    private static final Long FOLDER_ID = 1L;
    private static final Long FILE_ID = 2L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long USER_ID = 10L;

    private SharedFolderEntity tournamentFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT)
                .organizationId(5L)
                .scopeRefId(TOURNAMENT_ID)
                .name("大会要項")
                .build();
    }

    private SharedFolderEntity divisionFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT_DIVISION)
                .organizationId(5L)
                .scopeRefId(DIVISION_ID)
                .name("規約")
                .build();
    }

    private SharedFolderEntity teamFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(7L)
                .name("チームフォルダ")
                .build();
    }

    private SharedFileEntity file() {
        return SharedFileEntity.builder()
                .folderId(FOLDER_ID).name("a.pdf").fileKey("k").fileSize(1L).contentType("application/pdf").build();
    }

    // ========================================================================
    // 大会フォルダ — 閲覧認可委譲
    // ========================================================================

    @Nested
    @DisplayName("大会フォルダの閲覧認可")
    class TournamentFolderView {

        @Test
        @DisplayName("閲覧権なしはゲートが不在コード（FOLDER_NOT_FOUND）で拒否し、そのまま伝播する")
        void 閲覧権なしは不在コード() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(accessGate).requireView(
                            eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                            eq(ContactSpaceKind.BULLETIN), eq(USER_ID), eq(FileSharingErrorCode.FOLDER_NOT_FOUND));

            assertThatThrownBy(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        @Test
        @DisplayName("未ログイン（userId=null）でも閲覧判定へ委譲する（公開判定はゲート側）")
        void 未ログインはゲートへ委譲() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

            guard.checkFolderViewByFolderId(FOLDER_ID, null);

            verify(accessGate).requireView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                    eq(ContactSpaceKind.BULLETIN), isNull(), eq(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("閲覧できる者は通過する（例外なし）")
        void 閲覧可は通過() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

            assertThatCode(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(accessGate).requireView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID), eq(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("ファイル ID 経路はファイル→フォルダ→ディビジョンスコープと辿り、不在コードは FILE_NOT_FOUND")
        void ファイルID経路は不在コードFILE() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file()));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(divisionFolder()));

            guard.checkFolderViewByFileId(FILE_ID, USER_ID);

            verify(accessGate).requireView(
                    eq(ContactSpaceScopeType.TOURNAMENT_DIVISION), eq(DIVISION_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID), eq(FileSharingErrorCode.FILE_NOT_FOUND));
        }
    }

    // ========================================================================
    // 大会フォルダ — tx 内の多層防御（従来どおり TournamentContactAccessService.checkPost）
    // ========================================================================

    @Nested
    @DisplayName("大会フォルダの投稿認可（tx 内の多層防御）")
    class TournamentFolderPost {

        @Test
        @DisplayName("一般メンバーが投稿しようとすると投稿認可で 403")
        void 一般メンバー投稿で403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(accessService).checkPost(
                            eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID), eq(USER_ID));

            assertThatThrownBy(() -> guard.checkFolderPostByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("ファイル ID 経由の投稿認可も委譲する")
        void ファイルID経由で投稿認可委譲() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file()));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

            guard.checkFolderPostByFileId(FILE_ID, USER_ID);

            verify(accessService).checkPost(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID), eq(USER_ID));
        }
    }

    // ========================================================================
    // Controller 前段の存在秘匿つき投稿認可
    // ========================================================================

    @Nested
    @DisplayName("存在秘匿つき投稿認可（requirePostConcealedBy*）")
    class PostConcealed {

        @Test
        @DisplayName("フォルダ ID 経路: ゲートの requirePost を FOLDER_NOT_FOUND で呼ぶ")
        void フォルダID経路でゲート委譲() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

            guard.requirePostConcealedByFolderId(FOLDER_ID, USER_ID);

            verify(accessGate).requirePost(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID), eq(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("ファイル ID 経路: ゲートの requirePost を FILE_NOT_FOUND で呼ぶ（ディビジョン）")
        void ファイルID経路でゲート委譲() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file()));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(divisionFolder()));

            guard.requirePostConcealedByFileId(FILE_ID, USER_ID);

            verify(accessGate).requirePost(
                    eq(ContactSpaceScopeType.TOURNAMENT_DIVISION), eq(DIVISION_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID), eq(FileSharingErrorCode.FILE_NOT_FOUND));
        }

        @Test
        @DisplayName("閲覧可・投稿権なしはゲートが 403 TOUR_031 を投げ、そのまま伝播する")
        void 閲覧可投稿権なしは403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(accessGate).requirePost(any(), any(), any(), any(), any());

            assertThatThrownBy(() -> guard.requirePostConcealedByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("フォルダ不在は FOLDER_NOT_FOUND、ファイル不在は FILE_NOT_FOUND（ゲートは呼ばない）")
        void 不在コード() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requirePostConcealedByFolderId(FOLDER_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND);
            assertThatThrownBy(() -> guard.requirePostConcealedByFileId(FILE_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND);
            verify(accessGate, never()).requirePost(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ファイルは実在するが所属フォルダが不在でも FILE_NOT_FOUND（FOLDER_NOT_FOUND を漏らさない）")
        void 所属フォルダ不在はファイル不在コード() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file()));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requirePostConcealedByFileId(FILE_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND);
            assertThatThrownBy(() -> guard.checkFolderViewByFileId(FILE_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND);
        }
    }

    // ========================================================================
    // 大会以外スコープ — no-op
    // ========================================================================

    @Nested
    @DisplayName("大会以外スコープは no-op")
    class NonTournamentScope {

        @Test
        @DisplayName("TEAM フォルダの閲覧では連絡スペース判定を一切呼ばない（既存挙動維持）")
        void TEAM閲覧はnoop() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));

            assertThatCode(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(accessGate, never()).requireView(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("TEAM フォルダの投稿（tx 内・前段とも）では連絡スペース判定を一切呼ばない")
        void TEAM投稿はnoop() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));

            assertThatCode(() -> guard.checkFolderPostByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            assertThatCode(() -> guard.requirePostConcealedByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(accessService, never()).checkPost(any(), any(), any());
            verify(accessGate, never()).requirePost(any(), any(), any(), any(), any());
        }
    }

    // ========================================================================
    // 不在 — 404
    // ========================================================================

    @Nested
    @DisplayName("不在は 404")
    class NotFound {

        @Test
        @DisplayName("存在しないフォルダ ID は FOLDER_NOT_FOUND（ゲートは呼ばない）")
        void フォルダ不在404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND);
            verify(accessGate, never()).requireView(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("存在しないファイル ID は FILE_NOT_FOUND（ゲートは呼ばない）")
        void ファイル不在404() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkFolderViewByFileId(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND);
            verify(accessGate, never()).requireView(any(), any(), any(), any(), any());
        }
    }
}
