package com.mannschaft.app.survey.service;

import com.mannschaft.app.survey.repository.SurveyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;

/**
 * アンケートの公開状態を他ドメインへ答える窓口（ドメイン境界の Service 経由の参照。CMP-261122-1300）。
 *
 * <p>お知らせ（F02.6）が、元のアンケートがお知らせ化してよい状態（PUBLISHED / CLOSED）かを、リポジトリやエンティティへ直接触れずに
 * 確認するために使う。論理削除済み・存在しない ID は公開済みではない（fail-closed）。</p>
 *
 * <p><b>【試練スタブ】</b>CMP-261122-1300 の red テスト（試練）をコンパイルさせるための最小スタブ。
 * 判定は未実装で、出陣（実装）で本実装に置き換える。お知らせ側からはまだ呼ばれていない。</p>
 */
@Service
@RequiredArgsConstructor
public class SurveyPublicationStatusService {

    private final SurveyRepository surveyRepository;

    /** 未削除で PUBLISHED / CLOSED のアンケートなら true（ID が null なら照会せず false）。 */
    public boolean isPublished(Long id) {
        throw new UnsupportedOperationException("CMP-261122-1300: 未実装（試練スタブ）");
    }

    /**
     * 指定 ID のうち、未削除で PUBLISHED / CLOSED のものの ID 集合（一括判定。空入力なら照会せず空集合）。
     */
    public Set<Long> findPublishedIds(Collection<Long> ids) {
        throw new UnsupportedOperationException("CMP-261122-1300: 未実装（試練スタブ）");
    }
}
