package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * タイムライン投稿の公開状態を他ドメインへ答える窓口（ドメイン境界の Service 経由の参照）。
 *
 * <p>お知らせ（F02.6）が、元投稿が公開中かをリポジトリやエンティティへ直接触れずに確認するために使う。
 * 下書き・予約投稿・非表示・削除済みは公開中ではない。</p>
 */
@Service
@RequiredArgsConstructor
public class TimelinePostPublicationStatusService {

    private final TimelinePostRepository timelinePostRepository;

    /** 未削除で {@code status = PUBLISHED} の投稿なら true。 */
    @Transactional(readOnly = true)
    public boolean isPublished(Long postId) {
        return postId != null && timelinePostRepository.existsByIdAndStatus(postId, PostStatus.PUBLISHED);
    }
}
