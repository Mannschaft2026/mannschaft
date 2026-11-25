package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.CmsErrorCode;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BlogSlugConflictRetryAspect}: 自動採番 slug の同時作成競合をトランザクションの外側から
 * 採番し直し、上限到達・呼び出し元トランザクション参加中は 409（CMS_005）にすることを固定する。
 */
@DisplayName("BlogSlugConflictRetryAspect（自動採番 slug の競合再試行）")
class BlogSlugConflictRetryAspectTest {

    /** 試行ごとに採番する対象の代役。失敗させる回数を指定できる。 */
    static class SlugWriter {
        final List<Boolean> txActiveAtAttempt = new ArrayList<>();
        int failures;
        int calls;

        @RetryOnAutoSlugConflict
        public String write() {
            calls++;
            txActiveAtAttempt.add(TransactionSynchronizationManager.isActualTransactionActive());
            if (calls <= failures) {
                throw new BlogSlugAutoConflictException(new RuntimeException("Duplicate entry"));
            }
            return "slug-" + calls;
        }

        public String notAnnotated() {
            calls++;
            throw new BlogSlugAutoConflictException(new RuntimeException("Duplicate entry"));
        }
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static SlugWriter proxy(SlugWriter target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new BlogSlugConflictRetryAspect());
        return factory.getProxy();
    }

    @Test
    @DisplayName("競合したら呼び直し、2 回目の採番結果を返す")
    void 競合は呼び直して成功() {
        SlugWriter target = new SlugWriter();
        target.failures = 1;

        assertThat(proxy(target).write()).isEqualTo("slug-2");
        assertThat(target.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("競合が続いたら上限回数で 409（CMS_027 自動割り当ての混雑）")
    void 上限で409() {
        SlugWriter target = new SlugWriter();
        target.failures = Integer.MAX_VALUE;

        assertThatThrownBy(() -> proxy(target).write())
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT));
        assertThat(target.calls).isEqualTo(BlogSlugConflictRetryAspect.MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("呼び出し元のトランザクションに参加中は再試行せず 409（CMS_027）のまま伝播させる（原子性を壊さない）")
    void 参加中は再試行しない() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        SlugWriter target = new SlugWriter();
        target.failures = 1;

        assertThatThrownBy(() -> proxy(target).write())
                .isInstanceOf(BlogSlugAutoConflictException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT));
        assertThat(target.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("注釈の無いメソッドは再試行しない")
    void 注釈なしは対象外() {
        SlugWriter target = new SlugWriter();

        assertThatThrownBy(() -> proxy(target).notAnnotated()).isInstanceOf(BlogSlugAutoConflictException.class);
        assertThat(target.calls).isEqualTo(1);
    }
}
