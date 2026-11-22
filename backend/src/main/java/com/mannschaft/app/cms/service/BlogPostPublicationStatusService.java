package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ブログ記事の公開状態を他ドメインへ答える窓口（ドメイン境界の Service 経由の参照）。
 *
 * <p>お知らせ（F02.6）が、元記事が公開済みかをリポジトリやエンティティへ直接触れずに確認するために使う。
 * 予約公開中（{@code DRAFT} ＋未来の公開日時）は公開済みではない。削除済みの記事は存在しない扱い。</p>
 */
@Service
@RequiredArgsConstructor
public class BlogPostPublicationStatusService {

    private final BlogPostRepository blogPostRepository;

    /** 未削除で {@code status = PUBLISHED} の記事なら true。 */
    @Transactional(readOnly = true)
    public boolean isPublished(Long postId) {
        return postId != null && blogPostRepository.existsByIdAndStatus(postId, PostStatus.PUBLISHED);
    }
}
