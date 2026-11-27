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
import java.util.ArrayList;
import java.util.List;
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
    @Autowired private SupportAccessGrantService grantService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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
                () -> transitions.startSession(operator, grantId, "本人", List.of()));

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

        Throwable second = raceAfterHolding(v -> transitions.startSession(operator, grantId, "本人", List.of()),
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
                        "排他の結合テストのため", null, List.of()),
                () -> transitions.request(operator, new CreateSupportAccessGrantRequest(targetB, "排他の結合テストのため", null, null),
                        "排他の結合テストのため", null, List.of()));

        assertThat(codeOf(second)).isEqualTo("SUPPORT_ACCESS_011");
        assertThat(grantRepository.countByOperatorUserIdAndRequestedAtAfter(operator, Instant.now().minus(Duration.ofDays(1))))
                .isEqualTo(SupportAccessGrantService.DAILY_REQUEST_LIMIT);
    }

    // ─── 第2回検分対応: ロック順の統一（失効バッチ・退会・依頼と本人の操作が逆順にロックしない） ─────

    private UUID overduePending(long operatorId, long targetId) {
        Instant now = Instant.now();
        return grantRepository.saveAndFlush(base(operatorId, targetId).status(SupportAccessStatus.PENDING)
                .requestedAt(now.minus(Duration.ofHours(73))).requestExpiresAt(now.minus(Duration.ofMinutes(1))).build())
                .getId();
    }

    @Test
    @DisplayName("第2回検分 失効（先）と本人の許可（後）が重なっても、許可は 007 で EXPIRED が残る（デッドロックしない）")
    void 失効と許可() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID grantId = overduePending(operator, target);

        Throwable second = raceAfterHolding(v -> transitions.expireIfOverdue(grantId),
                () -> transitions.approve(target, grantId));

        assertThat(codeOf(second)).isEqualTo("SUPPORT_ACCESS_007");
        assertThat(grantRepository.findById(grantId).orElseThrow().getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
    }

    @Test
    @DisplayName("第2回検分 失効（先）と同じ組合せの依頼（後）が重なっても、依頼は失効の確定後に通り、新しい PENDING ができる")
    void 失効と依頼() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID stale = overduePending(operator, target);

        Throwable second = raceAfterHolding(v -> transitions.expireIfOverdue(stale),
                () -> transitions.request(operator, new CreateSupportAccessGrantRequest(target, "排他の結合テストのため", null, null),
                        "排他の結合テストのため", null, List.of()));

        assertThat(second).isNull();
        assertThat(grantRepository.findById(stale).orElseThrow().getStatus()).isEqualTo(SupportAccessStatus.EXPIRED);
        assertThat(grantRepository.findOpenIds(operator, target,
                java.util.EnumSet.of(SupportAccessStatus.PENDING, SupportAccessStatus.APPROVED))).hasSize(1);
    }

    @Test
    @DisplayName("第2回検分 失効バッチと本人の取消・許可を多数同時に走らせても、デッドロック・500 にならず各行は終端に収まる")
    void 失効バッチと本人の操作の並走() throws Exception {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Instant now = Instant.now();
            ids.add(grantRepository.saveAndFlush(base(IDS.incrementAndGet(), target).status(SupportAccessStatus.APPROVED)
                    .decidedAt(now.minus(Duration.ofHours(25))).expiresAt(now.minus(Duration.ofSeconds(1))).build()).getId());
        }
        List<Throwable> unexpected = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> batch = CompletableFuture.runAsync(() -> {
            await(start);
            for (int r = 0; r < 3; r++) {
                try {
                    grantService.expireOverdue();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }
        });
        CompletableFuture<Void> user = CompletableFuture.runAsync(() -> {
            await(start);
            for (UUID id : ids) {
                try {
                    grantService.revoke(target, id);
                } catch (BusinessException e) {
                    // 先に失効した行は 007（業務の拒否）。デッドロック・ロック待ちの 500 ではない
                    assertThat(e.getErrorCode().getCode()).isEqualTo("SUPPORT_ACCESS_007");
                } catch (Throwable t) {
                    unexpected.add(t);
                }
            }
        });
        start.countDown();
        CompletableFuture.allOf(batch, user).get(60, TimeUnit.SECONDS);

        assertThat(unexpected).as("デッドロック・ロック待ちの例外が利用者・バッチへ漏れない").isEmpty();
        for (UUID id : ids) {
            assertThat(grantRepository.findById(id).orElseThrow().getStatus())
                    .isIn(SupportAccessStatus.EXPIRED, SupportAccessStatus.REVOKED);
        }
    }

    @Test
    @DisplayName("第2回検分 退会の取消と本人の許可が重なっても、デッドロックせず取消（REVOKED・WITHDRAWAL）が残る")
    void 退会と許可() throws Exception {
        long target = IDS.incrementAndGet();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            ids.add(grantRepository.saveAndFlush(base(IDS.incrementAndGet(), target).status(SupportAccessStatus.PENDING)
                    .build()).getId());
        }

        Throwable second = raceAfterHolding(v -> transitions.approve(target, ids.get(2)),
                () -> grantService.revokeAllForWithdrawal(target));

        assertThat(second).isNull();
        for (UUID id : ids) {
            SupportAccessGrantEntity g = grantRepository.findById(id).orElseThrow();
            assertThat(g.getStatus()).isEqualTo(SupportAccessStatus.REVOKED);
            assertThat(g.getEndedReason()).isEqualTo(com.mannschaft.app.supportaccess.SupportAccessEndedReason.WITHDRAWAL);
        }
    }

    // ─── 第2回検分対応: 安全通知の outbox（遷移と同じ TX） ─────

    private com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer.PreparedEmail startEmail(long target) {
        return new com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer.PreparedEmail(target,
                SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET, "ja", "it-target@example.com",
                "【Mannschaft】サポート閲覧が開始されました", "<p>本文</p>");
    }

    private long mailRows(UUID grantId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE source_event_id LIKE ?",
                Long.class, "support-access:SESSION_STARTED_TO_TARGET:" + grantId + "%");
    }

    private long noticeRows(UUID grantId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM support_access_notification_outbox WHERE grant_id = UUID_TO_BIN(?)",
                Long.class, grantId.toString());
    }

    @Test
    @DisplayName("第2回検分 閲覧開始の TX が巻き戻れば、アプリ内通知の outbox もメール outbox も残らない")
    void 閲覧開始が巻き戻れば通知もメールも残らない() {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID grantId = approvedGrant(operator, target);

        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            transitions.startSession(operator, grantId, "本人", List.of(startEmail(target)));
            s.setRollbackOnly();
        });

        assertThat(mailRows(grantId)).isZero();
        assertThat(noticeRows(grantId)).isZero();
        assertThat(grantRepository.findById(grantId).orElseThrow().getActiveSessionStartedAt()).isNull();
    }

    @Test
    @DisplayName("第2回検分 閲覧開始が確定すれば、アプリ内通知の outbox とメール outbox の行が1つずつ残る（同じ遷移の再送は UNIQUE で重複しない）")
    void 閲覧開始が確定すれば通知とメールが残る() {
        long operator = IDS.incrementAndGet();
        long target = IDS.incrementAndGet();
        UUID grantId = approvedGrant(operator, target);

        transitions.startSession(operator, grantId, "本人", List.of(startEmail(target)));

        assertThat(mailRows(grantId)).isEqualTo(1);
        assertThat(noticeRows(grantId)).isEqualTo(1);
        String sourceEventId = jdbcTemplate.queryForObject(
                "SELECT source_event_id FROM email_outbox WHERE source_event_id LIKE ?", String.class,
                "support-access:SESSION_STARTED_TO_TARGET:" + grantId + "%");
        Instant startedAt = grantRepository.findById(grantId).orElseThrow().getActiveSessionStartedAt();
        assertThat(sourceEventId).endsWith(":" + target + ":" + startedAt.toEpochMilli());
        // 同じ遷移のメールをもう一度 enqueue しても、決定的な冪等キーの UNIQUE で2通目は入らない
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> emailOutboxService.enqueue(
                        startEmail(target).toRequest(grantId, startedAt)))
                .isInstanceOf(com.mannschaft.app.mail.outbox.EmailOutboxValidationException.class);
        assertThat(mailRows(grantId)).isEqualTo(1);
    }

    @Autowired private com.mannschaft.app.mail.outbox.EmailOutboxService emailOutboxService;

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
