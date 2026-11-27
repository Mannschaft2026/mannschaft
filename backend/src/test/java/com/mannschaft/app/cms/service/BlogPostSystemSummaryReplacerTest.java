package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link BlogPostSystemSummaryReplacer}: 同時実行の競合（slug の生存行一意制約違反・ロック競合）だけを
 * トランザクションごとやり直し、他の整合性違反はやり直さず、呼び出し元トランザクションがあるときは
 * 1 回だけ実行することを固定する（台帳 CMP-261124-2158）。
 *
 * <p>既存記事の論理削除が同時に走った相手と同じ版を更新しようとした楽観ロック衝突（flush を EntityManager で
 * 直接呼ぶため変換前の {@link OptimisticLockException} のまま届く経路と、commit 時に Spring の
 * {@link ObjectOptimisticLockingFailureException} へ変換されて届く経路の両方）もやり直し対象であることを固定する
 * （台帳 CMP-261127-1817）。</p>
 */
@DisplayName("BlogPostSystemSummaryReplacer（差し替えのやり直し範囲）")
class BlogPostSystemSummaryReplacerTest {

    private BlogPostRepository blogPostRepository;
    private PlatformTransactionManager transactionManager;
    private BlogPostSystemSummaryReplacer replacer;

    @BeforeEach
    void setUp() {
        blogPostRepository = mock(BlogPostRepository.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(blogPostRepository.findByUserIdAndSlug(any(), any())).thenReturn(Optional.empty());
        replacer = new BlogPostSystemSummaryReplacer(blogPostRepository, transactionManager);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static DataIntegrityViolationException violation(String constraintName, String sqlMessage) {
        SQLIntegrityConstraintViolationException sql =
                new SQLIntegrityConstraintViolationException(sqlMessage, "23000", 1062);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement [" + sqlMessage + "]", sql,
                        constraintName));
    }

    private static DataIntegrityViolationException slugViolation() {
        return violation("blog_posts.uq_bp_slug_user_active",
                "Duplicate entry '7-weekly-20411125-20411201-1' for key 'blog_posts.uq_bp_slug_user_active'");
    }

    private static BlogPostEntity saved(long id) {
        BlogPostEntity entity = BlogPostEntity.builder().userId(7L).slug("weekly").build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private Long replace() {
        return replacer.replacePrivateUserPost(7L, "weekly-20411125-20411201", "題", "本文", "抜粋",
                LocalDateTime.of(2041, 12, 1, 21, 0));
    }

    @Test
    @DisplayName("slug の生存行一意制約違反はトランザクションごとやり直し、2 回目の結果を返す")
    void slug一意制約違反はやり直す() {
        when(blogPostRepository.save(any())).thenThrow(slugViolation()).thenReturn(saved(42L));

        assertThat(replace()).isEqualTo(42L);
        verify(blogPostRepository, times(2)).save(any());
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    @DisplayName("NOT NULL 違反はやり直さず、そのまま伝播させる")
    void notNull違反はやり直さない() {
        DataIntegrityViolationException notNull = violation(null, "Column 'title' cannot be null");
        when(blogPostRepository.save(any())).thenThrow(notNull);

        assertThatThrownBy(this::replace).isSameAs(notNull);
        verify(blogPostRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("外部キー違反はやり直さず、そのまま伝播させる")
    void 外部キー違反はやり直さない() {
        DataIntegrityViolationException fk = violation("blog_posts.fk_bp_user",
                "Cannot add or update a child row: a foreign key constraint fails (CONSTRAINT `fk_bp_user`)");
        when(blogPostRepository.save(any())).thenThrow(fk);

        assertThatThrownBy(this::replace).isSameAs(fk);
        verify(blogPostRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("ロック競合（デッドロック等）はやり直し、上限回数で諦めて伝播させる")
    void ロック競合は上限までやり直す() {
        CannotAcquireLockException deadlock = new CannotAcquireLockException("Deadlock found");
        when(blogPostRepository.save(any())).thenThrow(deadlock);

        assertThatThrownBy(this::replace).isSameAs(deadlock);
        verify(blogPostRepository, times(BlogPostSystemSummaryReplacer.MAX_ATTEMPTS)).save(any());
    }

    @Test
    @DisplayName("呼び出し元にトランザクションがあれば、slug 一意制約違反でもやり直さず 1 回だけ実行する")
    void 呼び出し元トランザクション中はやり直さない() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        DataIntegrityViolationException slug = slugViolation();
        when(blogPostRepository.save(any())).thenThrow(slug).thenReturn(saved(42L));

        assertThatThrownBy(this::replace).isSameAs(slug);
        verify(blogPostRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("呼び出し元にトランザクションがあっても、競合が無ければそのまま差し替える")
    void 呼び出し元トランザクション中でも成功する() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        when(blogPostRepository.save(any())).thenReturn(saved(42L));

        assertThat(replace()).isEqualTo(42L);
        verify(blogPostRepository, times(1)).save(any());
    }

    private static OptimisticLockException staleUpdate() {
        return new OptimisticLockException(
                "Batch update returned unexpected row count from update [0]; actual row count: 0; expected: 1");
    }

    @Test
    @DisplayName("楽観ロック衝突（変換前の jakarta.persistence.OptimisticLockException）はやり直し、2 回目の結果を返す")
    void 楽観ロック衝突_jakarta版はやり直す() {
        when(blogPostRepository.save(any())).thenThrow(staleUpdate()).thenReturn(saved(42L));

        assertThat(replace()).isEqualTo(42L);
        verify(blogPostRepository, times(2)).save(any());
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    @DisplayName("楽観ロック衝突（Spring の ObjectOptimisticLockingFailureException）はやり直し、2 回目の結果を返す")
    void 楽観ロック衝突_Spring版はやり直す() {
        when(blogPostRepository.save(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(BlogPostEntity.class, 10L))
                .thenReturn(saved(42L));

        assertThat(replace()).isEqualTo(42L);
        verify(blogPostRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("commit 時に Spring の楽観ロック例外へ変換されて届いた衝突もやり直す")
    void 楽観ロック衝突_commit時に変換されて届いてもやり直す() {
        when(blogPostRepository.save(any())).thenReturn(saved(41L)).thenReturn(saved(42L));
        doThrow(new ObjectOptimisticLockingFailureException("commit で更新 0 行", staleUpdate()))
                .doNothing()
                .when(transactionManager).commit(any());

        assertThat(replace()).isEqualTo(42L);
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    @DisplayName("commit 時に変換されず TransactionSystemException に包まれて届いた楽観ロック衝突もやり直す")
    void 楽観ロック衝突_commit時に包まれて届いてもやり直す() {
        when(blogPostRepository.save(any())).thenReturn(saved(41L)).thenReturn(saved(42L));
        doThrow(new TransactionSystemException("Could not commit JPA transaction",
                new RollbackException("Error while committing the transaction", staleUpdate())))
                .doNothing()
                .when(transactionManager).commit(any());

        assertThat(replace()).isEqualTo(42L);
        verify(transactionManager, times(2)).commit(any());
    }

    @Test
    @DisplayName("楽観ロック衝突が続けば上限回数で諦めて伝播させる")
    void 楽観ロック衝突は上限までやり直す() {
        OptimisticLockException stale = staleUpdate();
        when(blogPostRepository.save(any())).thenThrow(stale);

        assertThatThrownBy(this::replace).isSameAs(stale);
        verify(blogPostRepository, times(BlogPostSystemSummaryReplacer.MAX_ATTEMPTS)).save(any());
    }

    @Test
    @DisplayName("呼び出し元にトランザクションがあれば、楽観ロック衝突でもやり直さず 1 回だけ実行する")
    void 呼び出し元トランザクション中は楽観ロック衝突でもやり直さない() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        OptimisticLockException stale = staleUpdate();
        when(blogPostRepository.save(any())).thenThrow(stale).thenReturn(saved(42L));

        assertThatThrownBy(this::replace).isSameAs(stale);
        verify(blogPostRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("楽観ロックと無関係な TransactionSystemException はやり直さない")
    void 楽観ロック以外のcommit失敗はやり直さない() {
        when(blogPostRepository.save(any())).thenReturn(saved(41L));
        TransactionSystemException other = new TransactionSystemException("Could not commit JPA transaction",
                new RollbackException("Transaction marked as rollbackOnly"));
        doThrow(other).when(transactionManager).commit(any());

        assertThatThrownBy(this::replace).isSameAs(other);
        verify(transactionManager, times(1)).commit(any());
    }
}
