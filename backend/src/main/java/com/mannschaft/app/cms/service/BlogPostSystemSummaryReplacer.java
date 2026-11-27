package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p><b>同時実行</b>: 定期実行と手動再生成が同じ slug を同時に差し替えると、相手が確定した新記事を
 * こちらの検索が（未確定で）見られず、INSERT が一意制約違反（またはデッドロック）になる。
 * 差し替えは冪等なので、そのときはトランザクションごとやり直す。やり直しでは相手の確定済み記事が見えるため、
 * それを論理削除して差し替え、生存する記事は常に 1 件に保たれる。</p>
 *
 * <p>既存記事がある状態で同時に差し替えると、両者が同じ既存行（同じ版）を読み、先に確定した側が論理削除して
 * 版を上げるため、後の側の論理削除の UPDATE は更新 0 行（楽観ロック衝突）になる。これもやり直せば
 * 相手の確定済み記事を読み直して論理削除して差し替える（後勝ち）ので、同じくトランザクションごとやり直す。</p>
 *
 * <p>やり直す対象は slug の生存行一意制約違反（{@link BlogSlugAutoConflictException#isSlugUniqueViolation}）、
 * デッドロック・ロック待ちタイムアウト（{@link PessimisticLockingFailureException}）、楽観ロック衝突だけ。
 * 楽観ロック衝突は、flush を {@link EntityManager} で直接呼ぶため Spring に変換されない
 * {@link OptimisticLockException} のまま届く経路と、commit 時に {@link OptimisticLockingFailureException}
 * （{@code ObjectOptimisticLockingFailureException}）へ変換されて、または {@code TransactionSystemException} に
 * 包まれて届く経路があるため、原因連鎖のどこかにどちらかがあれば衝突とみなす。NOT NULL・外部キーなど
 * 他の整合性違反はやり直しても結果が変わらないため、そのまま伝播させる。</p>
 *
 * <p><b>呼び出し元トランザクション</b>: 呼び出し元が既にトランザクションを持つ場合は、
 * {@link BlogSlugConflictRetryAspect} と同じく<b>やり直さず 1 回だけ</b>実行する
 * （{@link TransactionTemplate} は REQUIRED なので呼び出し元のトランザクションに参加する）。違反した時点で
 * そのトランザクションはロールバック専用になり、同じトランザクション内のやり直しは必ず失敗するうえ、
 * 呼び出し元の他の更新との不可分性を壊さないためには呼び出し元ごとロールバックさせるしかないため。
 * 例外で拒否しないのは、呼び出し元の業務トランザクションの一部として差し替える正当な使い方（競合が無ければ成功する）を
 * 塞がないためであり、cms ドメインの既存の再試行（同アスペクト）と振る舞いを揃えるため。</p>
 */
@Slf4j
@Service
public class BlogPostSystemSummaryReplacer {

    /** 同時実行の競合に対する最大試行回数（初回を含む）。 */
    static final int MAX_ATTEMPTS = 3;

    private final BlogPostRepository blogPostRepository;
    private final TransactionTemplate transactionTemplate;

    public BlogPostSystemSummaryReplacer(BlogPostRepository blogPostRepository,
                                         PlatformTransactionManager transactionManager) {
        this.blogPostRepository = blogPostRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 個人ブログの system 生成記事（{@code PRIVATE} / {@code PUBLISHED} / タイムライン非連携）を差し替える。
     * 同 slug の生存記事があれば論理削除し、新規記事を作成する。全体で 1 トランザクション。
     * 呼び出し元にトランザクションが無ければ、同時実行の競合時に最大 {@value #MAX_ATTEMPTS} 回まで試行する。
     *
     * @return 作成した記事の ID（エンティティは API に露出しない）
     */
    public Long replacePrivateUserPost(Long userId, String slug, String title, String body,
                                       String excerpt, LocalDateTime publishedAt) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 呼び出し元のトランザクションに参加する。違反すればロールバック専用になるため、やり直さない。
            return transactionTemplate.execute(status ->
                    replaceInTransaction(userId, slug, title, body, excerpt, publishedAt));
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return transactionTemplate.execute(status ->
                        replaceInTransaction(userId, slug, title, body, excerpt, publishedAt));
            } catch (RuntimeException e) {
                String kind = retryableConflictKind(e);
                if (kind == null) {
                    throw e;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("[BlogPostSystemSummaryReplacer] 同時実行の競合が続いたため差し替えを諦める: "
                            + "userId={}, slug={}, cause={}, attempts={}", userId, slug, kind, attempt);
                    throw e;
                }
                log.info("[BlogPostSystemSummaryReplacer] {}のため差し替えをやり直す: userId={}, slug={}, attempt={}",
                        kind, userId, slug, attempt);
            }
        }
    }

    /**
     * やり直せば解消する同時実行の競合なら、その種別（ログ用）を返す。やり直し対象でなければ {@code null}。
     */
    private static String retryableConflictKind(RuntimeException e) {
        if (e instanceof PessimisticLockingFailureException) {
            return "ロック競合（デッドロック・ロック待ちタイムアウト）";
        }
        if (e instanceof DataIntegrityViolationException && BlogSlugAutoConflictException.isSlugUniqueViolation(e)) {
            return "slug 一意制約違反";
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OptimisticLockException || t instanceof OptimisticLockingFailureException) {
                return "楽観ロック衝突（既存記事が同時に差し替えられた）";
            }
        }
        return null;
    }

    private Long replaceInTransaction(Long userId, String slug, String title, String body,
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
