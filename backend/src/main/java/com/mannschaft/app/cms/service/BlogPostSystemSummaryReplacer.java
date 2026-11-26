package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * システムが生成する個人ブログ記事（行動メモの週次まとめなど）を、同じ slug の既存記事と
 * <b>不可分に差し替える</b>窓口（ドメイン境界の Service 経由の参照）。
 *
 * <p>「既存記事の論理削除」と「新規記事の INSERT」を cms ドメイン内の 1 トランザクションで行う。
 * 呼び出し元（他ドメイン）はトランザクションを張らずにこのメソッドを呼ぶだけでよく、
 * 途中で失敗した場合は論理削除も含めて全てロールバックされるため、旧記事が消えて新記事が無い状態は生じない。</p>
 *
 * <p>生存行の slug 一意制約（{@code uq_bp_slug_user_active}）に掛からないよう、論理削除の UPDATE は
 * INSERT より前に flush して確定させる（ID が IDENTITY のため INSERT は即時、UPDATE は flush まで保留される）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogPostSystemSummaryReplacer {

    private final BlogPostRepository blogPostRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 個人ブログの system 生成記事（{@code PRIVATE} / {@code PUBLISHED} / タイムライン非連携）を差し替える。
     * 同 slug の生存記事があれば論理削除し、新規記事を作成する。全体で 1 トランザクション。
     *
     * @return 作成した記事の ID（エンティティは API に露出しない）
     */
    @Transactional
    public Long replacePrivateUserPost(Long userId, String slug, String title, String body,
                                       String excerpt, LocalDateTime publishedAt) {
        blogPostRepository.findByUserIdAndSlug(userId, slug).ifPresent(existing -> {
            existing.softDelete();
            blogPostRepository.save(existing);
            entityManager.flush();
            log.info("[BlogPostSystemSummaryReplacer] 既存記事を論理削除して差し替え: userId={}, blogPostId={}",
                    userId, existing.getId());
        });

        BlogPostEntity post = BlogPostEntity.builder()
                .userId(userId)
                .authorId(userId)
                .title(title)
                .slug(slug)
                .body(body)
                .excerpt(excerpt)
                .postType(PostType.BLOG)
                .visibility(Visibility.PRIVATE)
                .status(PostStatus.PUBLISHED)
                .publishedAt(publishedAt)
                .crossPostToTimeline(false)
                .targetType("ALL")
                .build();
        return blogPostRepository.save(post).getId();
    }
}
