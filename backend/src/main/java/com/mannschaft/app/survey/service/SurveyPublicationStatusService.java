package com.mannschaft.app.survey.service;

import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.SurveyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * アンケートの公開状態を他ドメインへ答える窓口（ドメイン境界の Service 経由の参照。CMP-261122-1300）。
 *
 * <p>お知らせ（F02.6）が、元のアンケートがお知らせ化してよい状態（PUBLISHED / CLOSED）かを、リポジトリやエンティティへ直接触れずに
 * 確認するために使う。論理削除済み・存在しない ID は公開済みではない（fail-closed）。</p>
 */
@Service
@RequiredArgsConstructor
public class SurveyPublicationStatusService {

    /** お知らせ化を許す状態（PUBLISHED / CLOSED。他の状態・論理削除・不在はお知らせ化しない）。 */
    private static final Set<SurveyStatus> PUBLISHED_STATUSES = EnumSet.of(SurveyStatus.PUBLISHED, SurveyStatus.CLOSED);

    private final SurveyRepository surveyRepository;

    /** 未削除で PUBLISHED / CLOSED のアンケートなら true（ID が null なら照会せず false）。 */
    @Transactional(readOnly = true)
    public boolean isPublished(Long id) {
        return id != null && surveyRepository.existsByIdAndStatusIn(id, PUBLISHED_STATUSES);
    }

    /**
     * 指定 ID のうち、未削除で PUBLISHED / CLOSED のものの ID 集合（一括判定。空入力なら照会せず空集合）。
     */
    @Transactional(readOnly = true)
    public Set<Long> findPublishedIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(surveyRepository.findIdsByIdInAndStatusIn(ids, PUBLISHED_STATUSES));
    }
}
