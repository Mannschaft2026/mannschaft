package com.mannschaft.app.effortrecord.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.effortrecord.service.EffortTeamShareVisibility;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F00 {@code ReferenceType.EFFORT_TEAM_SHARE} の可視性判定 Resolver（F06.4.1 親設計 §4・データ設計 §3）。
 *
 * <p>contentId は共有 UUID。判定は {@link EffortTeamShareVisibility} に集約し、チーム一覧・詳細・タイムライン・
 * 定義候補で同じ条件（閲覧者の現在所属・チームの有効性・team と owner の VISIBLE と版・投稿者の加入期間の資格）を使う。
 * 照会の障害は {@code EFFORT_004}（503）として呼出元へ伝える。UUID 経路専用で、Long 経路は fail-closed。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffortTeamShareVisibilityResolver implements ContentVisibilityResolver<Enum<?>> {

    private final EffortTeamShareVisibility visibility;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.EFFORT_TEAM_SHARE;
    }

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        log.warn("EFFORT_TEAM_SHARE resolver called via Long path (should be UUID)");
        return false;
    }

    @Override
    public Set<Long> filterAccessible(Collection<Long> contentIds, Long viewerUserId) {
        return Collections.emptySet();
    }

    @Override
    public boolean canViewUuid(UUID contentId, Long viewerUserId) {
        if (contentId == null || viewerUserId == null) {
            return false;
        }
        return visibility.filterByShareIds(List.of(contentId), viewerUserId).allowed().contains(contentId);
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }
        return visibility.filterByShareIds(contentIds, viewerUserId).allowed();
    }
}
