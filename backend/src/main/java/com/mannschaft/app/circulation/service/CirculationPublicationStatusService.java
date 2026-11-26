package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.CirculationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * 回覧板の公開状態を他ドメインへ答える窓口（ドメイン境界の Service 経由の参照。CMP-261122-1300）。
 *
 * <p>お知らせ（F02.6）が、元の回覧板がお知らせ化してよい状態（ACTIVE / COMPLETED）かを、リポジトリやエンティティへ直接触れずに
 * 確認するために使う。論理削除済み・存在しない ID は公開済みではない（fail-closed）。</p>
 */
@Service
@RequiredArgsConstructor
public class CirculationPublicationStatusService {

    /** お知らせ化を許す状態（ACTIVE / COMPLETED。他の状態・論理削除・不在はお知らせ化しない）。 */
    private static final Set<CirculationStatus> PUBLISHED_STATUSES = EnumSet.of(CirculationStatus.ACTIVE, CirculationStatus.COMPLETED);

    private final CirculationDocumentRepository circulationDocumentRepository;

    /** 未削除で ACTIVE / COMPLETED の回覧板なら true（ID が null なら照会せず false）。 */
    @Transactional(readOnly = true)
    public boolean isPublished(Long id) {
        return id != null && circulationDocumentRepository.existsByIdAndStatusIn(id, PUBLISHED_STATUSES);
    }

    /**
     * 指定 ID のうち、未削除で ACTIVE / COMPLETED のものの ID 集合（一括判定。空入力なら照会せず空集合）。
     */
    @Transactional(readOnly = true)
    public Set<Long> findPublishedIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(circulationDocumentRepository.findIdsByIdInAndStatusIn(ids, PUBLISHED_STATUSES));
    }
}
