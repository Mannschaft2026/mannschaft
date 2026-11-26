package com.mannschaft.app.tournament.service;

import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse.TournamentHistoryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TeamTournamentHistoryFacade} の単体テスト（主催組織 slug の付与。ALIC-1）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamTournamentHistoryFacade 単体テスト")
class TeamTournamentHistoryFacadeTest {

    @Mock private OrganizationQueryService organizationQueryService;

    @InjectMocks
    private TeamTournamentHistoryFacade facade;

    private TournamentHistoryEntry entry(Long orgId) {
        return TournamentHistoryEntry.builder().organizationId(orgId).build();
    }

    @Test
    @DisplayName("各エントリに主催組織の slug が入り、組織は1回の一括取得で引く（ループ内呼び出ししない）")
    void 履歴に主催組織slugが入る() {
        TeamTournamentHistoryResponse in = TeamTournamentHistoryResponse.builder()
                .teamId(40L).history(List.of(entry(50L), entry(60L))).build();
        when(organizationQueryService.findSummariesByIds(any())).thenReturn(List.of(
                new OrganizationQueryService.OrganizationSummary(50L, "組織50", "org-fifty"),
                new OrganizationQueryService.OrganizationSummary(60L, "組織60", "org-sixty")));

        TeamTournamentHistoryResponse result = facade.attachOrganizationSlugs(in);

        assertThat(result.getTeamId()).isEqualTo(40L);
        assertThat(result.getHistory()).extracting(TournamentHistoryEntry::getOrganizationSlug)
                .containsExactly("org-fifty", "org-sixty");
        verify(organizationQueryService, times(1)).findSummariesByIds(any());
    }

    @Test
    @DisplayName("組織が削除済み等で slug が引けないエントリは organizationSlug=null")
    void 組織が引けなければslugはnull() {
        TeamTournamentHistoryResponse in = TeamTournamentHistoryResponse.builder()
                .teamId(41L).history(List.of(entry(50L))).build();
        when(organizationQueryService.findSummariesByIds(any())).thenReturn(List.of());

        TeamTournamentHistoryResponse result = facade.attachOrganizationSlugs(in);

        assertThat(result.getHistory()).hasSize(1);
        assertThat(result.getHistory().get(0).getOrganizationSlug()).isNull();
        assertThat(result.getHistory().get(0).getOrganizationId()).isEqualTo(50L);
    }
}
