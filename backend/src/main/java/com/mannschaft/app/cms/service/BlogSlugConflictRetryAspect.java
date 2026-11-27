package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.SQLException;

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
 * <p><b>デッドロック（CMP-261124-2159）</b>: 同時作成で MySQL がデッドロックを検知すると、被害者のトランザクションは
 * 全体がロールバックされる（何も確定していない）ので、slug 競合と同じく新しいトランザクションで呼び直せる。
 * 再試行するのは {@link DeadlockLoserDataAccessException}、および MySQL のデッドロック（エラー 1213 / SQLState 40001）が
 * 原因の {@link CannotAcquireLockException} のみ。ロック待ちタイムアウト（1205）は待ち時間を増幅するので再試行しない。
 * slug の明示指定でも、デッドロックはロールバック済みの全体を再実行するだけなので安全（slug 競合の判定とは独立）。
 * 上限到達・呼び出し元トランザクション内では {@code CMS_028}（503）にする。</p>
 *
 * <p>slug 競合の場合の最終的な応答は {@code CMS_027}（409「記事の URL の自動割り当てが混み合いました」）。
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
            try {
                return pjp.proceed();
            } catch (RuntimeException e) {
                if (isDeadlock(e)) {
                    // 呼び出し元ごとロールバックさせる。ここで再試行すると呼び出し元の他の書き込みが失われる
                    throw new BusinessException(CmsErrorCode.POST_WRITE_DEADLOCK, e);
                }
                throw e;
            }
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return pjp.proceed();
            } catch (RuntimeException e) {
                if (isDeadlock(e)) {
                    if (attempt >= MAX_ATTEMPTS) {
                        log.warn("ブログ記事の保存がデッドロックし続けたため 503 を返す: method={}, attempts={}",
                                pjp.getSignature().toShortString(), attempt);
                        throw new BusinessException(CmsErrorCode.POST_WRITE_DEADLOCK, e);
                    }
                    log.info("ブログ記事の保存がデッドロックしたためトランザクションをやり直す: method={}, attempt={}",
                            pjp.getSignature().toShortString(), attempt);
                    continue;
                }
                if (!(e instanceof BlogSlugAutoConflictException)) {
                    throw e;
                }
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

    /** MySQL のデッドロック（トランザクション全体がロールバックされる）か。ロック待ちタイムアウト(1205)は含めない。 */
    static boolean isDeadlock(Throwable failure) {
        boolean lockFailure = false;
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof DeadlockLoserDataAccessException) {
                return true;
            }
            if (cause instanceof CannotAcquireLockException) {
                lockFailure = true;
            }
            if (lockFailure && cause instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || "40001".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return false;
    }
}
