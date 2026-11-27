package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.MembersOnlyTeamQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link MembersOnlyTeamQuery} の実装（CMP-261124-0159・F00 §20.2 規則 4/5）。
 *
 * <p>親チーム上限は個別の業務ドメインではなく共通の可視性基盤（{@code AbstractContentVisibilityResolver}・
 * 共通の門）の責務であるため、その判定材料である「チームの公開範囲が非公開（{@code MEMBERS_AND_ABOVE}）か」の
 * 読み取りも共通基盤（{@code common}）に置く。読むのは {@code teams} の主キーと {@code visibility} の 1 列だけで、
 * 書き込みはしない（チームの公開範囲の変更は team ドメインが行う）。</p>
 *
 * <p>{@code SELECT id FROM teams WHERE id IN (...) AND visibility = 'MEMBERS_AND_ABOVE'} の 1 本だけを撃つ
 * （件数・チーム数に比例しない）。論理削除済みのチームも公開範囲どおりに判定する（中身を開かない側に倒す）。
 * 結果をキャッシュしない（公開範囲の変更を次の呼び出しから即時に反映する。AC-0159-06）。
 * Hibernate のセッション経由の native クエリにして、問い合わせ本数の番人（Hibernate statistics）に数えさせる。</p>
 */
@Service
@Transactional(readOnly = true)
public class MembersOnlyTeamQueryService implements MembersOnlyTeamQuery {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Set<Long> findMembersOnlyTeamIds(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Set.of();
        }
        List<Long> ids = teamIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Set.of();
        }
        List<?> rows = entityManager
                .createNativeQuery("SELECT id FROM teams WHERE id IN (:ids) AND visibility = 'MEMBERS_AND_ABOVE'")
                .setParameter("ids", ids)
                .getResultList();
        // native の数値型（BIGINT / BIGINT UNSIGNED）の差で Long 以外が返っても集合判定を誤らないよう Long へ正規化する。
        return rows.stream().map(row -> ((Number) row).longValue()).collect(Collectors.toUnmodifiableSet());
    }
}
