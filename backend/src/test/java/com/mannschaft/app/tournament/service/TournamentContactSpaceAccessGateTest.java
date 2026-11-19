package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TournamentContactSpaceAccessGate} の単体テスト（CMP-261116-0726）。
 *
 * <p>閲覧権なし・スペース不在・大会／ディビジョン不在は呼び出し側指定の不在コードに畳まれ、
 * 閲覧可・権限なしだけが 403（投稿 TOUR_031／公開設定 TOUR_032）になること、
 * 投稿者は閲覧判定を走らせないこと（AC-20）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentContactSpaceAccessGate 単体テスト")
class TournamentContactSpaceAccessGateTest {

    @Mock
    private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock
    private TournamentParticipantRepository participantRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TournamentContactSpaceAccessGate gate;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long ORG_ID = 5L;
    private static final Long USER_ID = 10L;
    private static final ErrorCode NOT_FOUND = TournamentErrorCode.CONTACT_SPACE_NOT_FOUND;
    private static final ContactSpaceScopeType T = ContactSpaceScopeType.TOURNAMENT;
    private static final ContactSpaceScopeType D = ContactSpaceScopeType.TOURNAMENT_DIVISION;
    private static final ContactSpaceKind BULLETIN = ContactSpaceKind.BULLETIN;
    private static final ContactSpaceKind CHAT = ContactSpaceKind.CHAT;

    private TournamentEntity tournament() {
        return TournamentEntity.builder().organizationId(ORG_ID).name("テスト大会").createdBy(1L).build();
    }

    private TournamentDivisionEntity division() {
        return TournamentDivisionEntity.builder().tournamentId(TOURNAMENT_ID).name("1部").build();
    }

    private TournamentContactSpaceEntity space(ContactSpaceScopeType scopeType, Long scopeId,
                                               ContactSpaceKind kind, boolean isPublic) {
        return TournamentContactSpaceEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).spaceKind(kind).refId(999L).isPublic(isPublic).build();
    }

    private void givenSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind, boolean isPublic) {
        given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(scopeType, scopeId, kind))
                .willReturn(Optional.of(space(scopeType, scopeId, kind, isPublic)));
    }

    private void givenNoSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind) {
        given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(scopeType, scopeId, kind))
                .willReturn(Optional.empty());
    }

    private void givenTournamentExists() {
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament()));
    }

    private static void assertThrowsCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(expected));
    }

    // ====================================================================
    // requireView
    // ====================================================================

    @Nested
    @DisplayName("requireView")
    class RequireView {

        @Test
        @DisplayName("公開スペースは未ログインでも許可し、所属判定を走らせない")
        void 公開スペースは未ログインでも許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND))
                    .doesNotThrowAnyException();
            verifyNoInteractions(participantRepository, accessControlService);
        }

        @Test
        @DisplayName("非公開スペースに未ログインは不在コード（旧 403 TOUR_030 ではない）")
        void 非公開に未ログインは不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);

            assertThrowsCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND), NOT_FOUND);
        }

        @Test
        @DisplayName("スペース不在は呼び出し側が渡した不在コード")
        void スペース不在は指定コード() {
            givenNoSpace(T, TOURNAMENT_ID, BULLETIN);

            assertThrowsCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID,
                    FileSharingErrorCode.FOLDER_NOT_FOUND), FileSharingErrorCode.FOLDER_NOT_FOUND);
        }

        @Test
        @DisplayName("参加チームの active member は許可（組織解決に進まない）")
        void activeMemberは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verifyNoInteractions(tournamentRepository, accessControlService);
        }

        @Test
        @DisplayName("主催組織 ADMIN は許可")
        void 主催ADMINは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は許可")
        void SYSTEM_ADMINは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("閲覧権なし（部外者・主催組織一般・他テナントADMIN）は不在コード")
        void 閲覧権なしは不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND), NOT_FOUND);
        }

        @Test
        @DisplayName("ディビジョン: 組織はディビジョン→大会を辿って解決し、主催 ADMIN を許可する")
        void ディビジョンの主催ADMINは許可() {
            givenSpace(D, DIVISION_ID, CHAT, false);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division()));
            givenTournamentExists();
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> gate.requireView(D, DIVISION_ID, CHAT, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(participantRepository).countActiveMemberOfDivisionParticipantTeam(DIVISION_ID, USER_ID);
        }

        @Test
        @DisplayName("ディビジョン: スペースは残るが大会が論理削除済みなら不在コード")
        void ディビジョンの大会不在は不在() {
            givenSpace(D, DIVISION_ID, BULLETIN, false);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division()));
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThrowsCode(() -> gate.requireView(D, DIVISION_ID, BULLETIN, USER_ID, NOT_FOUND), NOT_FOUND);
            verifyNoInteractions(accessControlService);
        }
    }

    // ====================================================================
    // requirePost
    // ====================================================================

    @Nested
    @DisplayName("requirePost")
    class RequirePost {

        @Test
        @DisplayName("参加チームの代表は許可し、閲覧判定（active member・公開設定）を走らせない（AC-20）")
        void チーム代表は許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(participantRepository, never()).countActiveMemberOfAnyParticipantTeam(anyLong(), anyLong());
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("ディビジョン参加チームの代表は許可")
        void ディビジョンのチーム代表は許可() {
            givenSpace(D, DIVISION_ID, BULLETIN, false);
            given(participantRepository.countTeamAdminOfDivisionParticipantTeam(DIVISION_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requirePost(D, DIVISION_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("主催組織 ADMIN・SYSTEM_ADMIN は許可")
        void 主催ADMINとSYSTEM_ADMINは許可() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(accessControlService).isAdmin(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("スペース（kind）欠落は投稿者でも不在コード（AC-19）")
        void スペース欠落は投稿者でも不在() {
            givenNoSpace(T, TOURNAMENT_ID, BULLETIN);

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND), NOT_FOUND);
            verifyNoInteractions(participantRepository, accessControlService);
        }

        @Test
        @DisplayName("参加チームの一般 active member（閲覧可・投稿不可）は 403 TOUR_031")
        void 一般メンバーは403() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("公開スペース（kind 側）の部外者は 403 TOUR_031（AC-17）")
        void 公開スペースの部外者は403() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("公開スペースに未ログインは 403 TOUR_031、非公開スペースに未ログインは不在コード")
        void 未ログインは公開設定次第() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);
            givenSpace(T, TOURNAMENT_ID, CHAT, false);

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, CHAT, null, NOT_FOUND), NOT_FOUND);
            verifyNoInteractions(participantRepository, accessControlService);
        }

        @Test
        @DisplayName("閲覧権なし（非公開・非メンバー・非ADMIN）は不在コード")
        void 閲覧権なしは不在() {
            givenSpace(T, TOURNAMENT_ID, CHAT, false);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requirePost(T, TOURNAMENT_ID, CHAT, USER_ID,
                    FileSharingErrorCode.FILE_NOT_FOUND), FileSharingErrorCode.FILE_NOT_FOUND);
        }
    }

    // ====================================================================
    // requireAuthorOrPost
    // ====================================================================

    @Nested
    @DisplayName("requireAuthorOrPost")
    class RequireAuthorOrPost {

        @Test
        @DisplayName("本人は閲覧だけを要求する（一般 active member でも許可）")
        void 本人は閲覧のみ要求() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThatCode(() -> gate.requireAuthorOrPost(T, TOURNAMENT_ID, BULLETIN, USER_ID, true, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(participantRepository, never()).countTeamAdminOfAnyParticipantTeam(anyLong(), anyLong());
        }

        @Test
        @DisplayName("資格を失った本人（WITHDRAWN/DISQUALIFIED で閲覧不可）は不在コード（AC-16）")
        void 資格喪失の本人は不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requireAuthorOrPost(T, TOURNAMENT_ID, BULLETIN, USER_ID, true, NOT_FOUND),
                    NOT_FOUND);
        }

        @Test
        @DisplayName("本人でなければ投稿権を要求する（一般 active member は 403 TOUR_031）")
        void 本人以外は投稿権要求() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThrowsCode(() -> gate.requireAuthorOrPost(T, TOURNAMENT_ID, BULLETIN, USER_ID, false, NOT_FOUND),
                    TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }
    }

    // ====================================================================
    // requireViewResolvingLevel
    // ====================================================================

    @Nested
    @DisplayName("requireViewResolvingLevel")
    class RequireViewResolvingLevel {

        @Test
        @DisplayName("投稿者は POST を返し、閲覧判定を走らせない")
        void 投稿者はPOST() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            given(participantRepository.countTeamAdminOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThat(gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .isEqualTo(ContactAccessLevel.POST);
            verify(participantRepository, never()).countActiveMemberOfAnyParticipantTeam(anyLong(), anyLong());
        }

        @Test
        @DisplayName("一般 active member は VIEW")
        void 一般メンバーはVIEW() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenTournamentExists();
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThat(gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND))
                    .isEqualTo(ContactAccessLevel.VIEW);
        }

        @Test
        @DisplayName("公開スペースの未ログインは VIEW")
        void 公開の未ログインはVIEW() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, true);

            assertThat(gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, null, NOT_FOUND))
                    .isEqualTo(ContactAccessLevel.VIEW);
        }

        @Test
        @DisplayName("閲覧権なし・スペース不在は不在コード")
        void 閲覧権なしは不在() {
            givenSpace(T, TOURNAMENT_ID, BULLETIN, false);
            givenNoSpace(T, TOURNAMENT_ID, CHAT);
            givenTournamentExists();

            assertThrowsCode(() -> gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, USER_ID, NOT_FOUND),
                    NOT_FOUND);
            assertThrowsCode(() -> gate.requireViewResolvingLevel(T, TOURNAMENT_ID, CHAT, USER_ID, NOT_FOUND),
                    NOT_FOUND);
        }
    }

    // ====================================================================
    // requireVisibilityManage
    // ====================================================================

    @Nested
    @DisplayName("requireVisibilityManage")
    class RequireVisibilityManage {

        @Test
        @DisplayName("主催組織 ADMIN は許可し、スペース一覧を引かない")
        void 主催ADMINは許可() {
            givenTournamentExists();
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID))
                    .doesNotThrowAnyException();
            verifyNoInteractions(contactSpaceRepository);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は正しい大会配下のディビジョンで許可")
        void SYSTEM_ADMINはディビジョンで許可() {
            givenTournamentExists();
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, DIVISION_ID, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("大会不在は管理者でも 404 TOUR_029")
        void 大会不在は404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.empty());

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID), NOT_FOUND);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("パスの大会 ID とディビジョンの帰属が食い違えば SYSTEM_ADMIN でも 404 TOUR_029（AC-18）")
        void 帰属不一致は404() {
            givenTournamentExists();
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.empty());

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, DIVISION_ID, USER_ID), NOT_FOUND);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("参加チームの active member（知り得るが管理権なし）は 403 TOUR_032")
        void activeMemberは403() {
            givenTournamentExists();
            given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID))
                    .willReturn(List.of(space(T, TOURNAMENT_ID, BULLETIN, false), space(T, TOURNAMENT_ID, CHAT, false)));
            given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, USER_ID)).willReturn(1);

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID),
                    TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
        }

        @Test
        @DisplayName("いずれかのスペースが公開なら部外者も知り得るため 403 TOUR_032（AC-17）")
        void 公開スペースありの部外者は403() {
            givenTournamentExists();
            given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID))
                    .willReturn(List.of(space(T, TOURNAMENT_ID, BULLETIN, false), space(T, TOURNAMENT_ID, CHAT, true)));

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID),
                    TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
            verifyNoInteractions(participantRepository);
        }

        @Test
        @DisplayName("ディビジョン: 全スペース非公開で非メンバーは不在と同一の 404 TOUR_029")
        void 知り得ない者は404() {
            givenTournamentExists();
            given(divisionRepository.findByIdAndTournamentId(DIVISION_ID, TOURNAMENT_ID))
                    .willReturn(Optional.of(division()));
            given(contactSpaceRepository.findByScopeTypeAndScopeId(D, DIVISION_ID))
                    .willReturn(List.of(space(D, DIVISION_ID, BULLETIN, false)));

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, DIVISION_ID, USER_ID), NOT_FOUND);
            verify(participantRepository).countActiveMemberOfDivisionParticipantTeam(DIVISION_ID, USER_ID);
        }

        @Test
        @DisplayName("スペースが 1 件も無ければ active member でも閲覧対象が無く 404 TOUR_029")
        void スペース皆無は404() {
            givenTournamentExists();
            given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID)).willReturn(List.of());

            assertThrowsCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, USER_ID), NOT_FOUND);
            verify(participantRepository, never()).countActiveMemberOfAnyParticipantTeam(any(), any());
        }
    }
}
