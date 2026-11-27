package com.mannschaft.app.tournament.service;

import com.mannschaft.app.organization.service.OrganizationQueryService;
import com.mannschaft.app.tournament.dto.TeamTournamentHistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * チーム大会参加履歴に主催組織の slug を付与するファサード。
 *
 * <p>組織ページ URL は slug が正準のため、履歴の各エントリへ {@code organizationSlug} を付ける。
 * 組織ドメインの Service 呼び出しを tournament の {@code @Transactional} 内に置くと
 * ArchUnit D-3T（他ドメイン Repository への TX 越境）に抵触するため、本クラスは
 * <b>トランザクションの外</b>に置き、履歴の組み立て（TX 内）と slug 解決（TX 外）を分ける。
 * 組織は履歴に出る organizationId をまとめて1回で引く（ループ内呼び出しなし）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamTournamentHistoryFacade {

    private final OrganizationQueryService organizationQueryService;

    /**
     * 履歴（可視性フィルタ済み）の各エントリへ主催組織 slug を付ける。削除済み組織など引けないエントリの slug は null。
     *
     * @param history {@code StandingsQueryService#getTeamHistory} の結果
     */
    public TeamTournamentHistoryResponse attachOrganizationSlugs(TeamTournamentHistoryResponse history) {

        Set<Long> orgIds = new HashSet<>();
        for (var e : history.getHistory()) {
            if (e.getOrganizationId() != null) orgIds.add(e.getOrganizationId());
        }
        Map<Long, String> slugByOrgId = new HashMap<>();
        for (var summary : organizationQueryService.findSummariesByIds(orgIds)) {
            slugByOrgId.put(summary.id(), summary.slug());
        }
        List<TeamTournamentHistoryResponse.TournamentHistoryEntry> withSlug = history.getHistory().stream()
                .map(e -> e.toBuilder().organizationSlug(slugByOrgId.get(e.getOrganizationId())).build())
                .toList();
        return history.toBuilder().history(withSlug).build();
    }
}
