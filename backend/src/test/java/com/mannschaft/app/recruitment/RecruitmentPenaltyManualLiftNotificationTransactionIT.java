package com.mannschaft.app.recruitment;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltyRecomputeBatch;
import com.mannschaft.app.recruitment.service.RecruitmentPenaltyService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 実MySQLで手動解除の通知・コミット境界・競合する解除処理を検証する。 */
class RecruitmentPenaltyManualLiftNotificationTransactionIT extends AbstractMySqlIntegrationTest {

    private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

    @Autowired
    private RecruitmentPenaltyService penaltyService;

    @Autowired
    private RecruitmentPenaltyRecomputeBatch recomputeBatch;

    @Autowired
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @Autowired
    private RecruitmentPenaltySettingRepository settingRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ownerId;
    private Long adminId;
    private Long otherAdminId;
    private Long teamId;
    private Long settingId;
    private Long penaltyId;

    @AfterEach
    void 作成した試験データを削除する() {
        if (penaltyId != null) {
            jdbcTemplate.update("DELETE FROM notifications WHERE source_type = 'RECRUITMENT_PENALTY' AND source_id = ?",
                    penaltyId);
            penaltyRepository.deleteById(penaltyId);
        }
        if (settingId != null) {
            settingRepository.deleteById(settingId);
        }
        if (teamId != null) {
            jdbcTemplate.update("DELETE FROM teams WHERE id = ?", teamId);
        }
        for (Long id : new Long[] { ownerId, adminId, otherAdminId }) {
            if (id != null) {
                jdbcTemplate.update("DELETE FROM users WHERE id = ?", id);
            }
        }
    }

    @Test
    void 手動解除をコミットするとグローバルの発動元スコープ付きで本人だけに通知する() {
        prepareGlobalPenalty();

        transactionTemplate.executeWithoutResult(status -> {
            penaltyService.liftPenalty(penaltyId, adminId);
            assertThat(notifications()).isEmpty();
        });

        assertManualLiftAndNotification();
    }

    @Test
    void 手動解除をロールバックすると更新も通知も残らない() {
        prepareGlobalPenalty();

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(penaltyService.liftPenalty(penaltyId, adminId).getLiftedAt()).isNotNull();
            status.setRollbackOnly();
        });

        assertThat(penaltyRepository.findById(penaltyId).orElseThrow().getLiftedAt()).isNull();
        await().during(Duration.ofMillis(400)).atMost(WAIT_LIMIT)
                .untilAsserted(() -> assertThat(notifications()).isEmpty());
    }

    @Test
    void 同時手動解除は先に確定した解除者と一件の通知を保つ() throws Exception {
        prepareGlobalPenalty();
        otherAdminId = createUser();

        afterConcurrentSnapshot(status -> {
            assertThatThrownBy(() -> penaltyService.liftPenalty(penaltyId, otherAdminId))
                    .isInstanceOfSatisfying(BusinessException.class, e ->
                            assertThat(e.getErrorCode()).isEqualTo(RecruitmentErrorCode.INVALID_STATE_TRANSITION));
            status.setRollbackOnly();
        });

        assertManualLiftAndNotification();
    }

    @Test
    void 再計算が候補を先に読んでも確定済みの手動解除を上書きしない() throws Exception {
        prepareGlobalPenalty();
        RecruitmentPenaltyRecomputeBatch target = AopTestUtils.getUltimateTargetObject(recomputeBatch);

        afterConcurrentSnapshot(status -> target.recomputePenalties());

        assertManualLiftAndNotification();
    }

    private void afterConcurrentSnapshot(Consumer<TransactionStatus> contender) throws Exception {
        CountDownLatch snapshotReady = new CountDownLatch(1);
        CountDownLatch winnerCommitted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // 対象を実際の再計算候補クエリで読み、MySQLのスナップショットと管理中Entityを作る。
                assertThat(penaltyRepository.findActivePenaltiesAfterId(LocalDateTime.now(), penaltyId - 1,
                        PageRequest.of(0, 1))).extracting(RecruitmentUserPenaltyEntity::getId).containsExactly(penaltyId);
                snapshotReady.countDown();
                awaitLatch(winnerCommitted);
                contender.accept(status);
            }));
            try {
                awaitLatch(snapshotReady);
                penaltyService.liftPenalty(penaltyId, adminId);
            } finally {
                winnerCommitted.countDown();
            }
            future.get(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("解除競合の同期中に割り込まれました", e);
        }
    }

    private void assertManualLiftAndNotification() {
        RecruitmentUserPenaltyEntity penalty = penaltyRepository.findById(penaltyId).orElseThrow();
        assertThat(penalty.getLiftedAt()).isNotNull();
        assertThat(penalty.getLiftedBy()).isEqualTo(adminId);
        assertThat(penalty.getLiftReason()).isEqualTo(PenaltyLiftReason.ADMIN_MANUAL);

        await().during(Duration.ofMillis(400)).atMost(WAIT_LIMIT).untilAsserted(() ->
                assertThat(notifications()).singleElement().satisfies(notification -> {
                    assertThat(((Number) notification.get("user_id")).longValue()).isEqualTo(ownerId);
                    assertThat(((Number) notification.get("scope_id")).longValue()).isEqualTo(teamId);
                    assertThat(notification).containsEntry("notification_type", "RECRUITMENT_PENALTY_LIFTED")
                            .containsEntry("priority", "NORMAL")
                            .containsEntry("scope_type", "TEAM");
                    assertThat((String) notification.get("body")).contains("管理者", "手動解除");
                }));
    }

    private List<Map<String, Object>> notifications() {
        return jdbcTemplate.queryForList("""
                SELECT user_id, notification_type, priority, scope_type, scope_id, body
                FROM notifications WHERE source_type = 'RECRUITMENT_PENALTY' AND source_id = ?
                """, penaltyId);
    }

    private void prepareGlobalPenalty() {
        ownerId = createUser();
        adminId = createUser();
        String slug = "cmp019-wave17-" + Long.toUnsignedString(System.nanoTime(), 36);
        jdbcTemplate.update("""
                INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, created_at, updated_at)
                VALUES (?, 'PUBLIC', 1, 0, 0, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, slug, slug);
        teamId = jdbcTemplate.queryForObject("SELECT id FROM teams WHERE slug = ?", Long.class, slug);

        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM).scopeId(teamId).build();
        setting.update(false, 3, 180, 30, PenaltyApplyScope.ALL_SCOPES, false, 30);
        settingId = settingRepository.save(setting).getId();
        LocalDateTime now = LocalDateTime.now();
        penaltyId = penaltyRepository.save(RecruitmentUserPenaltyEntity.builder()
                .userId(ownerId).scopeType(RecruitmentScopeType.GLOBAL).triggeredBySettingId(settingId)
                .triggeredNoShowCount(3).startedAt(now.minusDays(1)).expiresAt(now.plusDays(1)).build()).getId();
    }

    private Long createUser() {
        String email = "cmp019-wave17-" + System.nanoTime() + "@example.test";
        jdbcTemplate.update("""
                INSERT INTO users (email, last_name, first_name, display_name, status,
                    is_searchable, handle_searchable, contact_approval_required, online_visibility,
                    dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted,
                    follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at)
                VALUES (?, 'CMP019', 'Wave17', 'CMP019 Wave17', 'ACTIVE',
                    1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, email);
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
