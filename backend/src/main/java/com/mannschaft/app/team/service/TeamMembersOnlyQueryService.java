package com.mannschaft.app.team.service;

import com.mannschaft.app.common.MembersOnlyTeamQuery;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link MembersOnlyTeamQuery} の team ドメイン実装（CMP-261124-0159・F00 §20.2 規則 5）。
 *
 * <p>{@code SELECT id FROM teams WHERE id IN (...) AND visibility = 'MEMBERS_AND_ABOVE'} の 1 本だけを撃つ。
 * 結果をキャッシュしない（公開範囲の変更を次の呼び出しから即時に反映するため。AC-0159-06）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamMembersOnlyQueryService implements MembersOnlyTeamQuery {

    private final TeamRepository teamRepository;

    @Override
    public Set<Long> findMembersOnlyTeamIds(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = teamIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        // native の数値型（BIGINT / BIGINT UNSIGNED）の差で Long 以外が返っても集合判定を誤らないよう Long へ正規化する。
        return teamRepository.findMembersOnlyIdsByIdIn(ids).stream()
                .map(Number::longValue)
                .collect(Collectors.toUnmodifiableSet());
    }
}
