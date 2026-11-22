package com.mannschaft.app.role;

import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.service.InviteAcceptanceWriter;
import com.mannschaft.app.role.service.MembershipGrantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * CMP-261107-1833 §9-3・§9-4（追加 AC7）: 保留承認の途中失敗からの復旧と、古い attempt との競合。
 *
 * <p>止め所は既存 Bean の spy（{@link MembershipGrantService}・{@link InviteAcceptanceWriter}）で作る
 * （{@link InviteAcceptanceFailureIT} と同流儀。spy を持つため専用 context）。保留行は試験が直接作る
 * （登録経路は {@link RegistrationPendingInviteIT}）。いずれも maxUses=1 で、復旧が使用回数を二重に進めれば
 * ROLE_003→EXPIRED になってしまう形にしてある。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261107-1833 保留承認の途中失敗・古い attempt（§9-3・§9-4）")
class RegistrationPendingInviteFailureIT extends InviteAcceptanceItSupport {

    static final String PENDING_TABLE = "registration_pending_invites";

    @MockitoSpyBean private MembershipGrantService grantSpy;
    @MockitoSpyBean private InviteAcceptanceWriter writerSpy;

    private final List<Long> pendingUsers = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void removePendingRows() {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ?", Long.class, PENDING_TABLE);
        for (Long userId : List.copyOf(pendingUsers)) {
            if (exists != null && exists > 0) {
                jdbc.update("DELETE FROM " + PENDING_TABLE + " WHERE user_id = ?", userId);
            }
            if (claimsTableExists()) {
                jdbc.update("DELETE FROM " + CLAIMS + " WHERE user_id = ?", userId);
            }
        }
        pendingUsers.clear();
    }

    private PendingInviteService pending() {
        return context.getBean(PendingInviteService.class);
    }

    private String acceptStatus(Long userId) {
        PendingInviteAcceptResponse response = pending().accept(userId);
        return response.status();
    }

    /** ACTIVE 利用者（メール確認・ログイン済み相当）に PENDING の保留行を作る。 */
    private Long userWithPendingInvite(Scope scope, InviteTokenEntity token) {
        Long userId = user();
        pendingUsers.add(userId);
        jdbc.update("INSERT INTO " + PENDING_TABLE + " (id, user_id, token_hash, scope_type, scope_id, status, version, "
                        + "created_at, updated_at) VALUES (UNHEX(REPLACE(?,'-','')), ?, ?, ?, ?, 'PENDING', 0, "
                        + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(), userId, new SecretTokenVault().hash(plaintext(token)), scope.type(), scope.id());
        return userId;
    }

    private String pendingStatus(Long userId) {
        return jdbc.queryForObject("SELECT status FROM " + PENDING_TABLE + " WHERE user_id = ?", String.class, userId);
    }

    private MembershipGrantService grant() {
        return AopTestUtils.getUltimateTargetObject(grantSpy);
    }

    private InviteAcceptanceWriter writerTarget() {
        return AopTestUtils.getUltimateTargetObject(writerSpy);
    }

    private void onGrantRole(org.mockito.stubbing.Answer<?> answer) {
        doAnswer(answer).when(grant()).grantRole(anyString(), anyLong(), anyLong(), anyLong(), any(), anyString());
    }

    // ------------------------------------------------------------------
    // §9-3 (a): T7 実処理前に失敗
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC7/§9-3(a): T7 の実処理前に原因不明の失敗→保留は PENDING・使用回数1（CLAIMED 残置）、"
            + "再承認で既存 claim を再開して JOINED・使用回数1のまま・claim GRANTED")
    void failureBeforeGrantResumesClaim() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        AtomicInteger calls = new AtomicInteger();
        onGrantRole(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new DataAccessResourceFailureException("試練: T7 実処理前で停止");
            }
            return inv.callRealMethod();
        });

        Throwable first = catchThrowable(() -> pending().accept(userId));
        assertThat(first).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(pendingStatus(userId)).isEqualTo("PENDING");
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");

        assertThat(acceptStatus(userId)).isEqualTo("JOINED");
        assertThat(usedCount(token.getId())).as("再開は使用回数を進めない").isEqualTo(1);
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // §9-3 (b): T7 実処理後（コミット後）に失敗
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC7/§9-3(b): T7 のコミット後に失敗→保留は PENDING、再承認は在籍済みとして残 claim を GRANTED に確定し JOINED、招待監査は1回")
    void failureAfterGrantSettlesOrphanClaim() {
        Scope scope = scope("ORGANIZATION");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        AtomicInteger calls = new AtomicInteger();
        onGrantRole(inv -> {
            Object result = inv.callRealMethod();
            if (calls.getAndIncrement() == 0) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        throw new DataAccessResourceFailureException("試練: T7 コミット後に応答喪失");
                    }
                });
            }
            return result;
        });

        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            Throwable first = catchThrowable(() -> pending().accept(userId));
            assertThat(first).isInstanceOf(DataAccessResourceFailureException.class);
            assertThat(pendingStatus(userId)).isEqualTo("PENDING");
            assertThat(userRoleCount(userId, scope)).as("付与はコミット済み").isEqualTo(1);
            assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");

            assertThat(acceptStatus(userId)).isEqualTo("JOINED");
            events = capture.events();
        }
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(inviteAuditCount("ORGANIZATION", events)).as("招待監査は1回").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // §9-3 (c): T9（確定）失敗
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC7/§9-3(c): T9 の確定に失敗しても JOINED（EXPIRED にしない）。次の承認で claim は GRANTED に確定、監査1回")
    void recordJoinedFailureIsSettledNextTime() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new DataAccessResourceFailureException("試練: T9 で停止");
            }
            return inv.callRealMethod();
        }).when(writerTarget()).recordJoined(any(UUID.class));

        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            assertThat(acceptStatus(userId)).isEqualTo("JOINED");
            assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");

            assertThat(acceptStatus(userId)).isEqualTo("JOINED");
            events = capture.events();
        }
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(inviteAuditCount("TEAM", events)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // §9-4: ACCEPTING の世代（古い attempt は何も書かない）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC7/§9-4: A が T7 で停止中に accepting_started_at を6分前へ戻し B が取って JOINED。"
            + "その後 A が失敗しても JOINED のまま・claim GRANTED 1件・使用回数1")
    void staleAttemptCannotOverwrite() throws Exception {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        onGrantRole(inv -> {
            if (calls.getAndIncrement() == 0) {
                aEntered.countDown();
                assertThat(releaseA.await(30, TimeUnit.SECONDS)).isTrue();
                throw new DataAccessResourceFailureException("試練: A は B の確定後に失敗");
            }
            return inv.callRealMethod();
        });

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> a = pool.submit(() -> catchThrowable(() -> pending().accept(userId)));
            assertThat(aEntered.await(30, TimeUnit.SECONDS)).as("A が T7 に入った").isTrue();
            assertThat(pendingStatus(userId)).isEqualTo("ACCEPTING");

            jdbc.update("UPDATE " + PENDING_TABLE + " SET accepting_started_at = ? WHERE user_id = ?",
                    utcNow().minusMinutes(6), userId);
            assertThat(acceptStatus(userId)).as("B").isEqualTo("JOINED");

            releaseA.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).as("A は失敗する").isInstanceOf(DataAccessResourceFailureException.class);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
        assertThat(pendingStatus(userId)).as("古い A は PENDING に戻せない").isEqualTo("JOINED");
        assertThat(jdbc.queryForObject("SELECT accept_attempt_id IS NULL FROM " + PENDING_TABLE + " WHERE user_id = ?",
                Boolean.class, userId)).isTrue();
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
    }
    @Test
    @DisplayName("AC7/§9-4（Codex P1）: A が新規 claim を確保して T7 前で停止→5分超で B が同じ claim を再開し T7 をコミット→"
            + "B の T9 前に A が再開して ALREADY_JOINED。A は共有中の claim を解放せず、参加済み・使用回数1・claim GRANTED・招待監査1回")
    void staleAttemptAlreadyJoinedMustNotReleaseResumedClaim() throws Exception {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch resumeA = new CountDownLatch(1);
        CountDownLatch bAtT9 = new CountDownLatch(1);
        CountDownLatch resumeB = new CountDownLatch(1);
        AtomicInteger grantCalls = new AtomicInteger();
        AtomicInteger recordCalls = new AtomicInteger();
        onGrantRole(inv -> {
            if (grantCalls.getAndIncrement() == 0) {
                // A: 新規 claim を確保した後、T7 の実処理前で止まる。
                aEntered.countDown();
                assertThat(resumeA.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        });
        doAnswer(inv -> {
            if (recordCalls.getAndIncrement() == 0) {
                // B: T7 をコミットした直後、T9 の前で止まる（A はまだ T7 前で止まっているので最初の T9 は B）。
                bAtT9.countDown();
                assertThat(resumeB.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        }).when(writerTarget()).recordJoined(any(UUID.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            Future<Throwable> a = pool.submit(() -> catchThrowable(() -> pending().accept(userId)));
            assertThat(aEntered.await(30, TimeUnit.SECONDS)).as("A が新規 claim を確保して T7 に入った").isTrue();
            assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");

            jdbc.update("UPDATE " + PENDING_TABLE + " SET accepting_started_at = ? WHERE user_id = ?",
                    utcNow().minusMinutes(6), userId);
            Future<String> b = pool.submit(() -> acceptStatus(userId));
            assertThat(bAtT9.await(30, TimeUnit.SECONDS)).as("B が同じ claim を再開し T7 をコミットして T9 の前で停止").isTrue();
            assertThat(userRoleCount(userId, scope)).as("B の付与はコミット済み").isEqualTo(1);

            resumeA.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).as("A は ALREADY_JOINED を受けても失敗しない").isNull();

            resumeB.countDown();
            assertThat(b.get(30, TimeUnit.SECONDS)).as("B").isEqualTo("JOINED");
            events = capture.events();
        } finally {
            resumeA.countDown();
            resumeB.countDown();
            pool.shutdownNow();
        }
        assertThat(pendingStatus(userId)).isEqualTo("JOINED");
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
        assertThat(claimStatuses(token.getId())).as("共有中の claim は解放されない").containsExactly("GRANTED");
        assertThat(usedCount(token.getId())).as("参加済みなのに使用回数が0へ戻らない").isEqualTo(1);
        assertThat(inviteAuditCount("TEAM", events)).as("招待監査は1回").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Codex 検分 P1/P2: claim の付与印（付与と同じTXで立つ）で戻すか確定するかを決める
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Codex P1: A が新規 claim を確保して T7 前で停止→5分超で B が取り直し T6' 直前で停止→A が T7 をコミットして"
            + " T9 前で停止→招待が期限切れ→B は T6' で期限切れを見ても付与印のある claim を解放しない。"
            + "JOINED・使用回数1・claim GRANTED・招待監査1回")
    void resumedAttemptMustNotReleaseClaimGrantedByStaleAttempt() throws Exception {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        CountDownLatch aAtT7 = new CountDownLatch(1);
        CountDownLatch resumeA = new CountDownLatch(1);
        CountDownLatch bAtT6 = new CountDownLatch(1);
        CountDownLatch resumeB = new CountDownLatch(1);
        CountDownLatch aAtT9 = new CountDownLatch(1);
        CountDownLatch resumeAT9 = new CountDownLatch(1);
        AtomicInteger grantCalls = new AtomicInteger();
        AtomicInteger claimCalls = new AtomicInteger();
        AtomicInteger recordCalls = new AtomicInteger();
        onGrantRole(inv -> {
            if (grantCalls.getAndIncrement() == 0) {
                // A: 新規 claim を確保した後、T7 の実処理前で止まる。
                aAtT7.countDown();
                assertThat(resumeA.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        });
        doAnswer(inv -> {
            if (claimCalls.getAndIncrement() == 1) {
                // B: 在籍判定（未在籍）と T1 を通った後、T6' の直前で止まる。
                bAtT6.countDown();
                assertThat(resumeB.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        }).when(writerTarget()).claimOrResume(any(), anyLong(), anyBoolean(), any());
        doAnswer(inv -> {
            if (recordCalls.getAndIncrement() == 0) {
                // A: T7 をコミットした直後、T9 の前で止まる。
                aAtT9.countDown();
                assertThat(resumeAT9.await(30, TimeUnit.SECONDS)).isTrue();
            }
            return inv.callRealMethod();
        }).when(writerTarget()).recordJoined(any(UUID.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            Future<Throwable> a = pool.submit(() -> catchThrowable(() -> pending().accept(userId)));
            assertThat(aAtT7.await(30, TimeUnit.SECONDS)).as("A が新規 claim を確保して T7 に入った").isTrue();
            assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");

            jdbc.update("UPDATE " + PENDING_TABLE + " SET accepting_started_at = ? WHERE user_id = ?",
                    utcNow().minusMinutes(6), userId);
            Future<String> b = pool.submit(() -> acceptStatus(userId));
            assertThat(bAtT6.await(30, TimeUnit.SECONDS)).as("B が取り直して T6' の直前で停止").isTrue();

            resumeA.countDown();
            assertThat(aAtT9.await(30, TimeUnit.SECONDS)).as("A が T7 をコミットして T9 の前で停止").isTrue();
            assertThat(userRoleCount(userId, scope)).as("A の付与はコミット済み").isEqualTo(1);

            jdbc.update("UPDATE invite_tokens SET expires_at = ? WHERE id = ?", utcNow().minusDays(1), token.getId());
            resumeB.countDown();
            assertThat(b.get(30, TimeUnit.SECONDS)).as("B は期限切れでも付与済みの参加を確定する").isEqualTo("JOINED");

            resumeAT9.countDown();
            a.get(30, TimeUnit.SECONDS);
            events = capture.events();
        } finally {
            resumeA.countDown();
            resumeB.countDown();
            resumeAT9.countDown();
            pool.shutdownNow();
        }
        assertThat(pendingStatus(userId)).isEqualTo("JOINED");
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
        assertThat(membershipCount(userId, scope)).isEqualTo(1);
        assertThat(claimStatuses(token.getId())).as("付与印のある claim は解放されない").containsExactly("GRANTED");
        assertThat(usedCount(token.getId())).as("参加済みなのに使用回数が0へ戻らない").isEqualTo(1);
        assertThat(inviteAuditCount("TEAM", events)).as("招待監査は1回").isEqualTo(1);
    }

    @Test
    @DisplayName("Codex P2: 新規 claim の T7 直前に別経路で参加→T7 は ALREADY_JOINED。claim に付与印が無いので解放し、"
            + "JOINED・使用回数0・claim RELEASED・招待監査なし")
    void otherPathJoinBeforeGrantReleasesClaim() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        AtomicInteger calls = new AtomicInteger();
        onGrantRole(inv -> {
            if (calls.getAndIncrement() == 0) {
                // 別経路（参加申請の承認など）の参加が、この承認の T7 より先にコミットされる。
                inNewTx(() -> {
                    insertUserRole(scope, userId);
                    insertMembership(scope, userId, "MEMBER");
                    return null;
                });
            }
            return inv.callRealMethod();
        });

        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            assertThat(acceptStatus(userId)).isEqualTo("JOINED");
            events = capture.events();
        }
        assertThat(pendingStatus(userId)).isEqualTo("JOINED");
        assertThat(claimStatuses(token.getId())).as("使われていない claim は解放する").containsExactly("RELEASED");
        assertThat(usedCount(token.getId())).as("招待の使用回数は戻る").isZero();
        assertThat(inviteAuditCount("TEAM", events)).as("使われていない招待の監査は出さない").isZero();
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
    }

    @Test
    @DisplayName("Codex P2: T7 実処理前の失敗で claim が残った後、別経路で参加→再承認は在籍済みとして残 claim を解放する"
            + "（付与印なし）。JOINED・使用回数0・claim RELEASED")
    void otherPathJoinAfterFailureReleasesOrphanClaim() {
        Scope scope = scope("ORGANIZATION");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        AtomicInteger calls = new AtomicInteger();
        onGrantRole(inv -> {
            if (calls.getAndIncrement() == 0) {
                throw new DataAccessResourceFailureException("試練: T7 実処理前で停止");
            }
            return inv.callRealMethod();
        });

        assertThat(catchThrowable(() -> pending().accept(userId))).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(claimStatuses(token.getId())).containsExactly("CLAIMED");
        assertThat(usedCount(token.getId())).isEqualTo(1);

        insertUserRole(scope, userId);
        insertMembership(scope, userId, "MEMBER");

        List<String> events;
        try (AuditCapture capture = capture(userId)) {
            assertThat(acceptStatus(userId)).isEqualTo("JOINED");
            events = capture.events();
        }
        assertThat(claimStatuses(token.getId())).as("使われていない残 claim は解放する").containsExactly("RELEASED");
        assertThat(usedCount(token.getId())).isZero();
        assertThat(inviteAuditCount("ORGANIZATION", events)).isZero();
    }

    // ------------------------------------------------------------------
    // Codex 再検分 P2: 付与印の導入（V266）前からの CLAIMED は「付与されたか不明」。自動では戻さない
    // ------------------------------------------------------------------

    /** V266 前の版が残した CLAIMED 行（使用回数を消費済み・旧データの印あり）を置く。 */
    private UUID legacyClaim(Long tokenId, Long userId) {
        UUID claimId = insertClaim(tokenId, userId, "CLAIMED", utcNow().minusHours(1), true);
        jdbc.update("UPDATE " + CLAIMS + " SET legacy_grant_unknown_at = UTC_TIMESTAMP(6) WHERE id = UNHEX(?)",
                claimId.toString().replace("-", ""));
        return claimId;
    }

    @Test
    @DisplayName("Codex 再検分 P2: 旧版で T7 コミット後・T9 前に止まった旧 CLAIMED（在籍済み）を再承認→解放せず GRANTED・使用回数1")
    void legacyClaimOfJoinedUserIsConfirmedNotReleased() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        UUID claimId = legacyClaim(token.getId(), userId);
        insertUserRole(scope, userId);
        insertMembership(scope, userId, "MEMBER");

        assertThat(acceptStatus(userId)).isEqualTo("JOINED");

        assertThat(claimStatus(claimId)).as("付与されたか不明な旧データは戻さない").isEqualTo("GRANTED");
        assertThat(usedCount(token.getId())).as("使用回数を戻さない").isEqualTo(1);
    }

    @Test
    @DisplayName("Codex 再検分 P2: 旧 CLAIMED が残り、招待が期限切れ（未在籍）→再承認は EXPIRED だが claim は解放せず CLAIMED・使用回数1")
    void legacyClaimIsNotReleasedOnExpiry() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        UUID claimId = legacyClaim(token.getId(), userId);
        jdbc.update("UPDATE invite_tokens SET expires_at = ? WHERE id = ?", utcNow().minusDays(1), token.getId());

        assertThat(acceptStatus(userId)).isEqualTo("EXPIRED");

        assertThat(claimStatus(claimId)).as("付与されたか不明な旧データは自動では戻さない").isEqualTo("CLAIMED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("Codex 再検分 P2: 旧 CLAIMED が残り、未在籍で招待が有効→再承認はその claim を再開して付与し GRANTED・使用回数1")
    void legacyClaimOfNotJoinedUserIsResumed() {
        Scope scope = scope("ORGANIZATION");
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Long userId = userWithPendingInvite(scope, token);
        UUID claimId = legacyClaim(token.getId(), userId);

        assertThat(acceptStatus(userId)).isEqualTo("JOINED");

        assertThat(claimStatus(claimId)).isEqualTo("GRANTED");
        assertThat(usedCount(token.getId())).as("再開は使用回数を進めない").isEqualTo(1);
        assertThat(userRoleCount(userId, scope)).isEqualTo(1);
    }
}
