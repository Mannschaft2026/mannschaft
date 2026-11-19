package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.service.SharedFolderService;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TournamentFolderService} の認可配線テスト（F08.7.1 / 04 §3 / §5・CMP-261116-0726）。
 *
 * <p>大会／ディビジョン不在・帰属不一致・BULLETIN スペース欠落・閲覧権なしがすべて不在と同一の
 * 404 TOUR_029 に畳まれ（AC-8/AC-19）、閲覧可・書込不可だけが 403 TOUR_031 になること、
 * folderId 系では不在フォルダ（FILE_SHARING_001）に畳まれることを、
 * {@link TournamentContactSpaceAccessGate} への委譲として検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFolderService 認可配線テスト")
class TournamentFolderServiceTest {

    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private SharedFolderService folderService;
    @Mock
    private TournamentContactSpaceAccessGate accessGate;

    @InjectMocks
    private TournamentFolderService service;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long ORG_ID = 5L;
    private static final Long FOLDER_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final TournamentErrorCode TOUR_029 = TournamentErrorCode.CONTACT_SPACE_NOT_FOUND;

    private TournamentEntity tournament() {
        return TournamentEntity.builder().organizationId(ORG_ID).name("テスト大会").createdBy(1L).build();
    }

    private TournamentDivisionEntity division() {
        return TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).name("1部").build();
    }

    private SharedFolderEntity tournamentFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT).scopeRefId(TOURNAMENT_ID).name("大会要項").build();
    }

    private SharedFolderEntity divisionFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT_DIVISION).scopeRefId(DIVISION_ID).name("規約").build();
    }

    private static void assertCode(Throwable thrown, Object code) {
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(code);
    }

    @Nested
    @DisplayName("大会ルートフォルダ一覧・作成")
    class TournamentScope {

        @Test
        @DisplayName("一覧: 大会実在なら BULLETIN の閲覧要求を不在コード TOUR_029 でゲートへ委譲してから一覧する")
        void 一覧は閲覧ゲートを通す() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.listTournamentScopedRootFolders(FileScopeType.TOURNAMENT, TOURNAMENT_ID))
                    .willReturn(List.of());

            service.listTournamentRootFolders(TOURNAMENT_ID, null);

            verify(accessGate).requireView(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID,
                    ContactSpaceKind.BULLETIN, null, TOUR_029);
        }

        @Test
        @DisplayName("一覧: 大会不在は TOURNAMENT_NOT_FOUND ではなく閲覧権なしと同一の 404 TOUR_029")
        void 一覧の大会不在はTOUR_029() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.listTournamentRootFolders(TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, TOUR_029));
            verifyNoInteractions(accessGate, folderService);
        }

        @Test
        @DisplayName("一覧: 閲覧権なしはゲートの 404 TOUR_029 が伝播し、一覧を引かない")
        void 一覧の閲覧権なしは不在応答() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            willThrow(new BusinessException(TOUR_029)).given(accessGate).requireView(
                    ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN, USER_ID, TOUR_029);

            assertThatThrownBy(() -> service.listTournamentRootFolders(TOURNAMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, TOUR_029));
            verifyNoInteractions(folderService);
        }

        @Test
        @DisplayName("作成: BULLETIN の投稿要求を TOUR_029 でゲートへ委譲し、通れば主催組織にクォータ帰属して作成する")
        void 作成は投稿ゲートを通す() {
            CreateFolderRequest request = mock(CreateFolderRequest.class);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));

            service.createTournamentFolder(TOURNAMENT_ID, USER_ID, request);

            verify(accessGate).requirePost(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID,
                    ContactSpaceKind.BULLETIN, USER_ID, TOUR_029);
            verify(folderService).createTournamentScopedFolder(
                    FileScopeType.TOURNAMENT, ORG_ID, TOURNAMENT_ID, USER_ID, request);
        }

        @Test
        @DisplayName("作成: 閲覧可・書込不可はゲートの 403 TOUR_031 が伝播し、フォルダを作らない")
        void 作成の書込不可は403() {
            CreateFolderRequest request = mock(CreateFolderRequest.class);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN)).given(accessGate)
                    .requirePost(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID, ContactSpaceKind.BULLETIN,
                            USER_ID, TOUR_029);

            assertThatThrownBy(() -> service.createTournamentFolder(TOURNAMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN));
            verifyNoInteractions(folderService);
        }

        @Test
        @DisplayName("作成: 大会不在は 404 TOUR_029 でゲート・作成へ進まない")
        void 作成の大会不在はTOUR_029() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.createTournamentFolder(
                    TOURNAMENT_ID, USER_ID, mock(CreateFolderRequest.class)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, TOUR_029));
            verifyNoInteractions(accessGate, folderService);
        }
    }

    @Nested
    @DisplayName("ディビジョンルートフォルダ一覧・作成")
    class DivisionScope {

        @Test
        @DisplayName("一覧: divId→tId 帰属確認後、ディビジョンの BULLETIN 閲覧要求を TOUR_029 でゲートへ委譲する")
        void 一覧は閲覧ゲートを通す() {
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(folderService.listTournamentScopedRootFolders(FileScopeType.TOURNAMENT_DIVISION, DIVISION_ID))
                    .willReturn(List.of());

            service.listDivisionRootFolders(TOURNAMENT_ID, DIVISION_ID, USER_ID);

            verify(accessGate).requireView(ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID,
                    ContactSpaceKind.BULLETIN, USER_ID, TOUR_029);
        }

        @Test
        @DisplayName("一覧: ディビジョン不在・帰属不一致は DIVISION_NOT_FOUND ではなく 404 TOUR_029")
        void 一覧の帰属不一致はTOUR_029() {
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.listDivisionRootFolders(TOURNAMENT_ID, DIVISION_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, TOUR_029));
            verifyNoInteractions(accessGate, folderService);
        }

        @Test
        @DisplayName("作成: BULLETIN スペース欠落（AC-19）はゲートの 404 TOUR_029 が伝播し、フォルダを作らない")
        void 作成のスペース欠落はTOUR_029() {
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            willThrow(new BusinessException(TOUR_029)).given(accessGate).requirePost(
                    ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID, ContactSpaceKind.BULLETIN,
                    USER_ID, TOUR_029);

            assertThatThrownBy(() -> service.createDivisionFolder(
                    TOURNAMENT_ID, DIVISION_ID, USER_ID, mock(CreateFolderRequest.class)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, TOUR_029));
            verifyNoInteractions(folderService);
        }

        @Test
        @DisplayName("作成: 投稿ゲートを通れば主催組織にクォータ帰属してディビジョンスコープで作成する")
        void 作成は投稿ゲートを通す() {
            CreateFolderRequest request = mock(CreateFolderRequest.class);
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));

            service.createDivisionFolder(TOURNAMENT_ID, DIVISION_ID, USER_ID, request);

            verify(accessGate).requirePost(ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID,
                    ContactSpaceKind.BULLETIN, USER_ID, TOUR_029);
            verify(folderService).createTournamentScopedFolder(
                    FileScopeType.TOURNAMENT_DIVISION, ORG_ID, DIVISION_ID, USER_ID, request);
        }
    }

    @Nested
    @DisplayName("checkFolderViewAccess")
    class CheckFolderViewAccess {

        @Test
        @DisplayName("大会スコープ: フォルダ帰属確認後、不在コード FOLDER_NOT_FOUND で閲覧ゲートへ委譲する")
        void 大会スコープ閲覧委譲() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(tournamentFolder());

            service.checkFolderViewAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID);

            verify(accessGate).requireView(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID,
                    ContactSpaceKind.BULLETIN, USER_ID, FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        @Test
        @DisplayName("ディビジョンスコープ: divId→tId 帰属 + フォルダ帰属確認後に閲覧ゲートへ委譲する")
        void ディビジョンスコープ閲覧委譲() {
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(divisionFolder());

            service.checkFolderViewAccess(TOURNAMENT_ID, DIVISION_ID, FOLDER_ID, USER_ID);

            verify(accessGate).requireView(ContactSpaceScopeType.TOURNAMENT_DIVISION, DIVISION_ID,
                    ContactSpaceKind.BULLETIN, USER_ID, FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        @Test
        @DisplayName("IDOR: 他スコープ（ディビジョン）の folderId を大会スコープで渡すと 404 FOLDER_NOT_FOUND")
        void IDORは404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(divisionFolder());

            assertThatThrownBy(() -> service.checkFolderViewAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(accessGate, never()).requireView(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("存在しない大会も不在フォルダと同一の 404 FOLDER_NOT_FOUND でゲートへ進まない")
        void 大会不在は404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.checkFolderViewAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, FileSharingErrorCode.FOLDER_NOT_FOUND));
            verifyNoInteractions(accessGate);
        }
    }

    @Nested
    @DisplayName("checkFolderPostAccess")
    class CheckFolderPostAccess {

        @Test
        @DisplayName("大会スコープ: フォルダ帰属確認後、不在コード FOLDER_NOT_FOUND で投稿ゲートへ委譲する")
        void 大会スコープ投稿委譲() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(tournamentFolder());

            service.checkFolderPostAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID);

            verify(accessGate).requirePost(ContactSpaceScopeType.TOURNAMENT, TOURNAMENT_ID,
                    ContactSpaceKind.BULLETIN, USER_ID, FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        @Test
        @DisplayName("IDOR: 他スコープの folderId を渡すと 404 で投稿ゲートへ進まない")
        void IDORは404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
            given(folderService.findFolderOrThrow(FOLDER_ID)).willReturn(divisionFolder());

            assertThatThrownBy(() -> service.checkFolderPostAccess(TOURNAMENT_ID, null, FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertCode(e, FileSharingErrorCode.FOLDER_NOT_FOUND));
            verify(accessGate, never()).requirePost(any(), any(), any(), any(), any());
        }
    }
}
