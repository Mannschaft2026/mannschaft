package com.mannschaft.app.supportaccess;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantTransitionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 検分差し戻し対応（致命）: 状態遷移の競合で取消・撤回・片側の許可が後勝ちの上書きで消えないこと（実 DB の行ロック）。
 *
 * <p>先に動く側（A）を外側のトランザクションの中で遷移させ、ロックを持ったままにする。その間に後の側（B）を
 * 別スレッドで開始し、A のコミット後に B が最新の状態で検証し直すことを確かめる。ロックなしの読み取りだと、
 * B は A のコミット前の古い状態を読んで書き戻し、A の遷移を消してしまう。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("戦役Z-B 状態遷移の排他 IT（取消・撤回・同時許可・1日の上限）")
class SupportAccessGrantConcurrencyIT extends AbstractMySqlIntegrationTest {

    private static final AtomicLong IDS = new AtomicLong(970_000_000L + (System.nanoTime() % 1_000_000L) * 10);
    private static final long HOLD_MILLIS = 400;

    @Autowired private SupportAccessGrantTransitionService transitions;
    @Autowired private SupportAccessGrantRepository grantRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private SupportAccessGrantEntity.SupportAccessGrantEntityBuilder<?, ?> base(long operatorId, long targetId) {
        Instant now = Instant.now();
        return SupportAccessGrantEntity.builder()
                .operatorUserId(operatorId).targetUserId(targetId)
                .requestReason("排他の結合テストのため").requestedAt(now.minus(Duration.ofMinutes(10)))
                .requestExpiresAt(now.plus(Duration.ofHours(71))).guardianApprovalRequired(false);
    }

    private UUID approvedGrant(long operatorId, long targetId) {
        Instant now = Instant.now();
        return grantRepository.saveAndFlush(base(operatorId, targetId).status(SupportAccessStatus.APPROVED)
                .decidedAt(now.minus(Duration.ofMinutes(5))).expiresAt(now.plus(Duration.ofHours(23))).build()).getId();
    }

    /** A を外側の TX でロックを持ったまま実行し、その間に B を別スレッドで始め、A のコミット後の B の結果を返す。 */
    private Throwable raceAfterHolding(Consumer<Void> first, Runnable second) throws Exception {
        CountDownLatch firstLocked = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CompletableFuture<Void> a = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            first.accept(null);
            firstLocked.countDown();
            sleep(HOLD_MILLIS);
        }));
        assertThat(firstLocked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Throwable> b = CompletableFuture.supplyAsync(() -> {
            try {
                second.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        });
        a.get(20, TimeUnit.SECONDS);
        return b.get(20, TimeUnit.SECONDS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String codeOf(Throwable t) {
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).getErrorCode().getCode();
    }

    @Test
    @DisplayName("本人の取消（先）と運営の閲覧開始（後）が重なっても、開始は 002 になり取消が残る")
    void 取消と閲覧開始() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID grantId = approvedGrant(operator, target);

        Throwable second = raceAfterHolding(v -> transitions.revoke(target, grantId),
                () -> transitions.startSession(operator, grantId, "本人"));

        assertThat(codeOf(second)).isEqualTo("SUPPORT_ACCESS_002");
        SupportAccessGrantEntity latest = grantRepository.findById(grantId).orElseThrow();
        assertThat(latest.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
        assertThat(latest.getActiveSessionStartedAt()).as("取消の後に閲覧開始が書き戻されない").isNull();
    }

    @Test
    @DisplayName("運営の閲覧開始（先）と本人の取消（後）が重なっても、取消が後から確定し REVOKED になる（開始で上書きされない）")
    void 閲覧開始と取消() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID grantId = approvedGrant(operator, target);

        Throwable second = raceAfterHolding(v -> transitions.startSession(operator, grantId, "本人"),
                () -> transitions.revoke(target, grantId));

        assertThat(second).isNull();
        assertThat(grantRepository.findById(grantId).orElseThrow().getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
    }

    @Test
    @DisplayName("運営の撤回（先）と本人の許可（後）が重なっても、許可は 007 になり CANCELLED が残る")
    void 撤回と許可() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID grantId = grantRepository.saveAndFlush(base(operator, target).status(SupportAccessStatus.PENDING).build()).getId();

        Throwable second = raceAfterHolding(v -> transitions.cancel(operator, grantId),
                () -> transitions.approve(target, grantId));

        assertThat(codeOf(second)).isEqualTo("SUPPORT_ACCESS_007");
        SupportAccessGrantEntity latest = grantRepository.findById(grantId).orElseThrow();
        assertThat(latest.getStatus()).isEqualTo(SupportAccessStatus.CANCELLED);
        assertThat(latest.getDecidedAt()).isNull();
    }

    @Test
    @DisplayName("本人と保護者が同時に許可しても、両方の許可が残って APPROVED になる（片方の許可が消えない）")
    void 本人と保護者の同時許可() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        long guardian = IDS.incrementAndGet();
        UUID grantId = grantRepository.saveAndFlush(base(operator, target).status(SupportAccessStatus.PENDING)
                .guardianApprovalRequired(true).guardianUserId(guardian).build()).getId();

        Throwable second = raceAfterHolding(v -> transitions.approve(target, grantId),
                () -> transitions.approve(guardian, grantId));

        assertThat(second).isNull();
        SupportAccessGrantEntity latest = grantRepository.findById(grantId).orElseThrow();
        assertThat(latest.getDecidedAt()).as("本人の許可").isNotNull();
        assertThat(latest.getGuardianDecidedAt()).as("保護者の許可").isNotNull();
        assertThat(latest.getStatus()).isEqualTo(SupportAccessStatus.APPROVED);
    }

    @Test
    @DisplayName("1日20件の上限: 19件ある運営者の同時2件の依頼は、依頼ロックで直列化され1件だけ通り、もう1件は 011")
    void 一日の上限の同時依頼() throws Exception {
        long operator = IDS.incrementAndGet();
        for (int i = 0; i < SupportAccessGrantService.DAILY_REQUEST_LIMIT - 1; i++) {
            grantRepository.saveAndFlush(base(operator, IDS.incrementAndGet()).status(SupportAccessStatus.DENIED)
                    .requestedAt(Instant.now().minus(Duration.ofMinutes(30))).build());
        }
        long targetA = IDS.incrementAndGet();
        long targetB = IDS.incrementAndGet();

        Throwable second = raceAfterHolding(
                v -> transitions.request(operator, new CreateSupportAccessGrantRequest(targetA, "排他の結合テストのため", null, null),
                        "排他の結合テストのため", null),
                () -> transitions.request(operator, new CreateSupportAccessGrantRequest(targetB, "排他の結合テストのため", null, null),
                        "排他の結合テストのため", null));

        assertThat(codeOf(second)).isEqualTo("SUPPORT_ACCESS_011");
        assertThat(grantRepository.countByOperatorUserIdAndRequestedAtAfter(operator, Instant.now().minus(Duration.ofDays(1))))
                .isEqualTo(SupportAccessGrantService.DAILY_REQUEST_LIMIT);
    }
}
