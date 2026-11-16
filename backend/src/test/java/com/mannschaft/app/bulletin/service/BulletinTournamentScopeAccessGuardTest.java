package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.service.TournamentContactAccessService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 大会スコープ一覧の閲覧認可が、取引外の Guard 側へ移ったこと（D-3T 根治）を固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinTournamentScopeAccessGuard 大会スコープ一覧の閲覧認可")
class BulletinTournamentScopeAccessGuardTest {

    private static final Long SCOPE_ID = 500L;
    private static final Long USER_ID = 10L;

    @Mock private TournamentContactAccessService tournamentContactAccessService;
    @InjectMocks private BulletinTournamentScopeAccessGuard guard;

    @Test
    @DisplayName("TOURNAMENT は大会の連絡閲覧認可（BULLETIN）に委譲する")
    void 大会は連絡閲覧認可へ委譲() {
        guard.checkListView(ScopeType.TOURNAMENT, SCOPE_ID, USER_ID);

        verify(tournamentContactAccessService).checkView(
                eq(ContactSpaceScopeType.TOURNAMENT), eq(SCOPE_ID), eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
    }

    @Test
    @DisplayName("TOURNAMENT_DIVISION はディビジョンの連絡閲覧認可に委譲する")
    void ディビジョンは連絡閲覧認可へ委譲() {
        guard.checkListView(ScopeType.TOURNAMENT_DIVISION, SCOPE_ID, USER_ID);

        verify(tournamentContactAccessService).checkView(
                eq(ContactSpaceScopeType.TOURNAMENT_DIVISION), eq(SCOPE_ID), eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
    }

    @Test
    @DisplayName("閲覧不可（canView 例外）はそのまま伝播する")
    void 閲覧不可は伝播() {
        doThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                .when(tournamentContactAccessService).checkView(any(), any(), any(), any());

        assertThatThrownBy(() -> guard.checkListView(ScopeType.TOURNAMENT, SCOPE_ID, USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("大会以外のスコープでは何もしない（所属認可はサービス側）")
    void 大会以外は何もしない() {
        guard.checkListView(ScopeType.TEAM, SCOPE_ID, USER_ID);

        verifyNoInteractions(tournamentContactAccessService);
    }
}
