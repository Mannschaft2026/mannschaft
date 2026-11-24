package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link RetryOnAutoSlugConflict} 付きメソッドで、自動採番 slug の同時作成競合を採番からやり直す。
 *
 * <p><b>トランザクション境界</b>: slug 一意制約に違反した JPA の永続化コンテキストとトランザクションは
 * ロールバック専用になり、同じトランザクション内では再試行できない。そこで {@code @Order(20)} により
 * {@code @Transactional}（既定 {@code LOWEST_PRECEDENCE}）より外側で動き、1 回の試行ごとに新しい
 * トランザクションでメソッドを呼び直す（違反した試行はロールバック済み。次の試行の事前確認は確定済みの
 * 競合相手を見て次の連番を選ぶ）。</p>
 *
 * <p>呼び出し元が既にトランザクションを持つ場合（他ドメインの業務トランザクションへ参加する呼び出し）は、
 * そのトランザクションがロールバック専用になっているため再試行せず、409（CMS_027）のまま伝播させて
 * 呼び出し元ごとロールバックさせる（原子性を壊さない）。</p>
 *
 * <p>どちらの場合も最終的な応答は {@code CMS_027}（409「記事の URL の自動割り当てが混み合いました」）。
 * 利用者は slug を指定していないため、明示指定の重複 {@code CMS_005} とは文言を分ける。</p>
 */
@Slf4j
@Aspect
@Component
@Order(20)
public class BlogSlugConflictRetryAspect {

    /** 試行の最大回数（初回を含む）。 */
    static final int MAX_ATTEMPTS = 3;

    @Around("@annotation(com.mannschaft.app.cms.service.RetryOnAutoSlugConflict)")
    public Object retryOnAutoSlugConflict(ProceedingJoinPoint pjp) throws Throwable {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return pjp.proceed();
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return pjp.proceed();
            } catch (BlogSlugAutoConflictException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    log.warn("ブログ記事 slug の同時作成競合が続いたため 409 を返す: method={}, attempts={}",
                            pjp.getSignature().toShortString(), attempt);
                    throw new BusinessException(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT, e);
                }
                log.info("ブログ記事 slug の同時作成競合のため採番からやり直す: method={}, attempt={}",
                        pjp.getSignature().toShortString(), attempt);
            }
        }
    }
}
