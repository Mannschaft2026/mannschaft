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
}
