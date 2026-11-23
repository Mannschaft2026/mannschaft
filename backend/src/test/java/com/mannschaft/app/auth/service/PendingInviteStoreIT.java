package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingInviteSnapshot;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PendingInviteStore} の試験（CMP-261107-1833 設計 §1・§9-4、追加 AC7「古い attempt の更新は0行」）。
 *
 * <p>Store の主題は MySQL 上の条件付き UPDATE（UTC_TIMESTAMP(6) と 5 分・7 日の窓、attempt の世代照合）なので、
 * モックでなく実 DB で確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261107-1833 PendingInviteStore（条件付き遷移・attempt 世代）")
class PendingInviteStoreIT extends AbstractMySqlIntegrationTest {

    private static final String TABLE = "registration_pending_invites";
    private static final String HASH = "b".repeat(64);

    @Autowired private ApplicationContext context;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private Long userId;

    @BeforeEach
    void createUser() {
        userId = users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@example.com")
                .lastName("保留").firstName("試練").displayName("保留試練").isSearchable(true).locale("ja")
                .timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }

    @AfterEach
    void cleanup() {
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ?", Long.class, TABLE);
        if (exists != null && exists > 0) {
            jdbc.update("DELETE FROM " + TABLE + " WHERE user_id = ?", userId);
        }
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
    }

    private PendingInviteStore store() {
        return context.getBean(PendingInviteStore.class);
    }

    private PendingInviteStatus status() {
        return store().find(userId).map(PendingInviteSnapshot::status).orElseThrow();
    }

    private static LocalDateTime utcNow() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private void setColumn(String column, LocalDateTime value) {
        jdbc.update("UPDATE " + TABLE + " SET " + column + " = ? WHERE user_id = ?", value, userId);
    }

    @Test
    @DisplayName("AC1: createPending はハッシュ・招待先と PENDING を保存し、find で読める（UUIDv7 主キー）")
    void createAndFind() {
        store().createPending(userId, HASH, "TEAM", 70L);

        PendingInviteSnapshot row = store().find(userId).orElseThrow();
        assertThat(row.status()).isEqualTo(PendingInviteStatus.PENDING);
        assertThat(row.tokenHash()).isEqualTo(HASH);
        assertThat(row.scopeType()).isEqualTo("TEAM");
        assertThat(row.scopeId()).isEqualTo(70L);
        assertThat(row.id().version()).isEqualTo(7);
        assertThat(store().find(userId + 1_000_000L)).isEmpty();
    }

    @Test
    @DisplayName("AC7/§9-4: PENDING を取ると ACCEPTING・attempt・開始時刻が入り、5分以内の2回目は取れない")
    void acquireOnlyOnceWithinFiveMinutes() {
        store().createPending(userId, HASH, "TEAM", 70L);

        Optional<UUID> first = store().tryStartAccepting(userId);
        assertThat(first).isPresent();
        assertThat(status()).isEqualTo(PendingInviteStatus.ACCEPTING);
        assertThat(store().find(userId).orElseThrow().acceptingStartedAt()).isNotNull();
        assertThat(store().tryStartAccepting(userId)).as("処理中（5分以内）は取れない").isEmpty();
    }

    @Test
    @DisplayName("AC7/§9-4: 5分超の ACCEPTING は新しい attempt で取り直せ、古い attempt の完了・差し戻しは0行で何も書かない")
    void staleAttemptUpdatesNothing() {
        store().createPending(userId, HASH, "TEAM", 70L);
        UUID a = store().tryStartAccepting(userId).orElseThrow();
        setColumn("accepting_started_at", utcNow().minusMinutes(6));

        UUID b = store().tryStartAccepting(userId).orElseThrow();
        assertThat(b).isNotEqualTo(a);

        assertThat(store().completeAttempt(userId, a, PendingInviteStatus.EXPIRED, "ROLE_003")).as("古い A の完了").isFalse();
        assertThat(store().revertAttempt(userId, a)).as("古い A の差し戻し").isFalse();
        assertThat(status()).isEqualTo(PendingInviteStatus.ACCEPTING);
        assertThat(store().find(userId).orElseThrow().failureCode()).isNull();

        assertThat(store().completeAttempt(userId, b, PendingInviteStatus.JOINED, null)).isTrue();
        assertThat(status()).isEqualTo(PendingInviteStatus.JOINED);
        assertThat(jdbc.queryForObject("SELECT accept_attempt_id IS NULL FROM " + TABLE + " WHERE user_id = ?", Boolean.class,
                userId)).as("完了時 attempt は NULL").isTrue();
        assertThat(store().revertAttempt(userId, b)).as("完了後の差し戻しは0行").isFalse();
        assertThat(store().tryStartAccepting(userId)).as("JOINED は取れない").isEmpty();
    }

    @Test
    @DisplayName("AC7: 原因不明の失敗は自分の attempt で PENDING に戻り、再び取れる")
    void revertToPending() {
        store().createPending(userId, HASH, "ORGANIZATION", 80L);
        UUID a = store().tryStartAccepting(userId).orElseThrow();

        assertThat(store().revertAttempt(userId, a)).isTrue();
        assertThat(status()).isEqualTo(PendingInviteStatus.PENDING);
        assertThat(store().tryStartAccepting(userId)).isPresent();
    }

    @Test
    @DisplayName("AC6: 失敗コード付きで EXPIRED に完了できる")
    void completeExpiredWithFailureCode() {
        store().createPending(userId, HASH, "TEAM", 70L);
        UUID a = store().tryStartAccepting(userId).orElseThrow();

        assertThat(store().completeAttempt(userId, a, PendingInviteStatus.EXPIRED, "ROLE_002")).isTrue();
        PendingInviteSnapshot row = store().find(userId).orElseThrow();
        assertThat(row.status()).isEqualTo(PendingInviteStatus.EXPIRED);
        assertThat(row.failureCode()).isEqualTo("ROLE_002");
    }

    @Test
    @DisplayName("AC8: transition は現状態が from に含まれるときだけ遷移する（PENDING→DECLINED、DECLINED→DECLINED は0行）")
    void conditionalTransition() {
        store().createPending(userId, HASH, "TEAM", 70L);

        assertThat(store().transition(userId, Set.of(PendingInviteStatus.PENDING), PendingInviteStatus.DECLINED)).isTrue();
        assertThat(store().transition(userId, Set.of(PendingInviteStatus.PENDING), PendingInviteStatus.DECLINED)).isFalse();
        assertThat(status()).isEqualTo(PendingInviteStatus.DECLINED);
    }

    @Test
    @DisplayName("AC9: 再招待要請は EXPIRED から REISSUE_REQUESTED へ。7日以内の再要請は false、7日超は再び true")
    void reissueWindow() {
        store().createPending(userId, HASH, "TEAM", 70L);
        UUID a = store().tryStartAccepting(userId).orElseThrow();
        store().completeAttempt(userId, a, PendingInviteStatus.EXPIRED, "ROLE_002");

        assertThat(store().markReissueRequested(userId)).isTrue();
        assertThat(status()).isEqualTo(PendingInviteStatus.REISSUE_REQUESTED);
        assertThat(store().find(userId).orElseThrow().reissueRequestedAt()).isNotNull();
        assertThat(store().markReissueRequested(userId)).as("7日以内").isFalse();

        setColumn("reissue_requested_at", utcNow().minusDays(8));
        assertThat(store().markReissueRequested(userId)).as("7日超").isTrue();
    }

    @Test
    @DisplayName("AC9: PENDING からは再招待要請できない")
    void reissueRequiresExpired() {
        store().createPending(userId, HASH, "TEAM", 70L);

        assertThat(store().markReissueRequested(userId)).isFalse();
        assertThat(status()).isEqualTo(PendingInviteStatus.PENDING);
    }

    @Test
    @DisplayName("AC3/§5: discardOpen は未終了の行を DISCARDED にし、JOINED は変えない")
    void discardOpenOnly() {
        store().createPending(userId, HASH, "TEAM", 70L);
        assertThat(store().discardOpen(userId)).isEqualTo(1);
        assertThat(status()).isEqualTo(PendingInviteStatus.DISCARDED);

        jdbc.update("UPDATE " + TABLE + " SET status = 'JOINED' WHERE user_id = ?", userId);
        assertThat(store().discardOpen(userId)).isZero();
        assertThat(status()).isEqualTo(PendingInviteStatus.JOINED);
    }
}
