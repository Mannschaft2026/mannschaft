package com.mannschaft.app.effortrecord.visibility;

import com.mannschaft.app.common.visibility.ContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * F00 {@code ReferenceType.EFFORT_RECORD} の可視性判定 Resolver（F06.4.1 親設計 §4）。
 *
 * <p>私的原本は所有者本人だけが読める（管理者・SYSTEM_ADMIN・同じチームの人も不可）。削除済み・purge 済みは
 * {@code @SQLRestriction} で読まれず不在として deny する。UUID 経路専用で、Long 経路は fail-closed。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffortRecordVisibilityResolver implements ContentVisibilityResolver<Enum<?>> {

    private final EffortRecordRepository records;

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.EFFORT_RECORD;
    }

    @Override
    public boolean canView(Long contentId, Long viewerUserId) {
        log.warn("EFFORT_RECORD resolver called via Long path (should be UUID)");
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
        return records.findByIdAndOwnerUserId(contentId, viewerUserId).isPresent();
    }

    @Override
    public Set<UUID> filterAccessibleUuid(Collection<UUID> contentIds, Long viewerUserId) {
        if (contentIds == null || contentIds.isEmpty() || viewerUserId == null) {
            return Collections.emptySet();
        }
        Set<UUID> accessible = new HashSet<>();
        for (EffortRecordEntity record : records.findAllById(contentIds)) {
            if (viewerUserId.equals(record.getOwnerUserId())) {
                accessible.add(record.getId());
            }
        }
        return accessible;
    }
}
