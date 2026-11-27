package com.mannschaft.app.supportaccess;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.context.SupportAccessContextHolder;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 戦役Z-B 試練（IT）: S3 代理中は DB 接続を READ ONLY にし、JdbcTemplate・@Modifying・ネイティブ SQL の書込も 0 件。
 * S10/S11 閲覧記録・監査は REQUIRES_NEW の別接続で、業務 TX が巻き戻っても残る。
 *
 * <p>Hibernate のエンティティ事象だけでは @Modifying（296箇所）・JdbcTemplate を止められないため、
 * 接続そのものを読取専用にする（軍議検分 Z-B (1)）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("戦役Z-B 代理中の DB 読取専用と記録の独立 IT")
class SupportAccessReadOnlyConnectionIT extends AbstractMySqlIntegrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private SupportAccessGrantRepository grantRepository;
    @Autowired private SupportAccessLogRepository logRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ApplicationContext applicationContext;
    @Autowired @Qualifier("event-pool") private Executor eventPool;

    private Long targetId;
    private UUID grantId;
    private SupportAccessContext context;

    @BeforeEach
    void setUp() {
        targetId = userRepository.saveAndFlush(UserEntity.builder()
                .email(UUID.randomUUID() + "@support-access.invalid")
                .lastName("山田").firstName("花子").lastNameKana("ヤマダ").firstNameKana("ハナコ")
                .displayName("読取専用の本人").isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        Instant now = Instant.now();
        grantId = grantRepository.saveAndFlush(SupportAccessGrantEntity.builder()
                .operatorUserId(900_000_000L + (targetId % 1000)).targetUserId(targetId)
                .status(SupportAccessStatus.APPROVED).requestReason("読取専用の結合テストのため")
                .requestedAt(now.minus(java.time.Duration.ofHours(1))).requestExpiresAt(now.plus(java.time.Duration.ofHours(71)))
                .decidedAt(now.minus(java.time.Duration.ofMinutes(30))).expiresAt(now.plus(java.time.Duration.ofHours(23)))
                .activeSessionStartedAt(now.minus(java.time.Duration.ofMinutes(1))).guardianApprovalRequired(false).build()).getId();
        context = new SupportAccessContext(grantId, 900_000_000L + (targetId % 1000), targetId, now.plus(java.time.Duration.ofHours(23)));
    }

    @AfterEach
    void tearDown() {
        try {
            SupportAccessContextHolder.clear();
        } catch (UnsupportedOperationException ignored) {
            // 試練スタブの間は未実装
        }
    }

    private String displayName() {
        return jdbcTemplate.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, targetId);
    }

    @Test
    @DisplayName("S3 代理中の JdbcTemplate の UPDATE は READ ONLY 接続で拒否され、行は変わらない")
    void S3_JdbcTemplateの書込は拒否() {
        SupportAccessContextHolder.set(context);

        Throwable thrown = catchThrowable(() ->
                jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", "書き換え", targetId));

        SupportAccessContextHolder.clear();
        assertThat(thrown).as("READ ONLY 接続での書込は例外").isNotNull();
        assertThat(displayName()).isEqualTo("読取専用の本人");
    }

    @Test
    @DisplayName("S3 代理中に書込 TX（readOnly=false）内で呼んだ @Modifying ネイティブ UPDATE も拒否され、0 件")
    void S3_Modifyingの書込は拒否() {
        SupportAccessContextHolder.set(context);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(s -> userRepository.markPurgeStarted(targetId)));

        SupportAccessContextHolder.clear();
        assertThat(thrown).isNotNull();
        assertThat(userRepository.findPurgeStartedFlag(targetId)).contains(0);
    }

    @Test
    @DisplayName("S3 代理を終えた後の接続は書込可能に戻る（プールへ READ ONLY を持ち越さない）")
    void S3_終了後は接続が戻る() {
        SupportAccessContextHolder.set(context);
        catchThrowable(() -> jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", "x", targetId));
        SupportAccessContextHolder.clear();

        for (int i = 0; i < 20; i++) {
            jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", "戻った" + i, targetId);
        }
        assertThat(displayName()).isEqualTo("戻った19");
    }

    @Test
    @DisplayName("S3 event-pool（@Async の実行器）へ文脈が伝播し、非同期の受け手の書込も拒否される")
    void S3_非同期の受け手でも拒否() throws Exception {
        SupportAccessContextHolder.set(context);
        AtomicReference<Boolean> activeInWorker = new AtomicReference<>();
        AtomicReference<Throwable> writeError = new AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();

        eventPool.execute(() -> {
            try {
                activeInWorker.set(SupportAccessContextHolder.isActive());
                writeError.set(catchThrowable(() ->
                        jdbcTemplate.update("UPDATE users SET display_name = ? WHERE id = ?", "非同期", targetId)));
            } finally {
                done.complete(null);
            }
        });
        done.get(10, TimeUnit.SECONDS);
        SupportAccessContextHolder.clear();

        assertThat(activeInWorker.get()).isTrue();
        assertThat(writeError.get()).isNotNull();
        assertThat(displayName()).isEqualTo("読取専用の本人");
    }

    @Test
    @DisplayName("S10/S11 代理中でも閲覧記録（REQUIRES_NEW）は書け、外側の業務 TX が巻き戻っても残る")
    void S11_閲覧記録は業務TXの失敗でも残る() {
        Object recorder = applicationContext.getBean(com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder.class);
        var logRecorder = (com.mannschaft.app.supportaccess.service.SupportAccessLogRecorder) recorder;
        long before = logRepository.findByGrantIdOrderByOccurredAtAsc(grantId).size();
        SupportAccessContextHolder.set(context);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        catchThrowable(() -> tx.executeWithoutResult(s -> {
            logRecorder.recordView(context, "GET", "/api/v1/users/me");
            throw new IllegalStateException("業務の失敗");
        }));

        SupportAccessContextHolder.clear();
        assertThat(logRepository.findByGrantIdOrderByOccurredAtAsc(grantId)).hasSize((int) before + 1);
    }

    @Test
    @DisplayName("S11 遷移の監査（REQUIRES_NEW）は外側の業務 TX が巻き戻っても audit_logs に残る")
    void S11_監査は業務TXの失敗でも残る() {
        var auditRecorder = applicationContext.getBean(com.mannschaft.app.supportaccess.service.SupportAccessAuditRecorder.class);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        catchThrowable(() -> tx.executeWithoutResult(s -> {
            auditRecorder.record(SupportAccessAuditAction.REVOKED, context.operatorUserId(), targetId, grantId);
            throw new IllegalStateException("業務の失敗");
        }));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE event_type = 'SUPPORT_ACCESS_REVOKED' AND user_id = ? AND target_user_id = ?",
                Integer.class, context.operatorUserId(), targetId);
        assertThat(count).isEqualTo(1);
    }
}
