package com.mannschaft.app.role;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.event.UserRegisteredEvent;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.service.AuthCleanupBatchService;
import com.mannschaft.app.auth.service.AuthRegistrationService;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.common.timezone.TimezoneContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261107-1833: 招待付き新規登録の保留承認方式（設計 A′）の受け入れ試験（AC2〜AC10 の実 DB 部分）。
 *
 * <p>登録時は参加せず保留（registration_pending_invites）だけを作り、メール確認・（未成年は保護者同意）・ログインの後に
 * 本人が承認して参加する。承認は {@link PendingInviteService}（auth・TX なし）→ PendingInviteStore（auth 短TX）→
 * InviteAcceptanceFacade#joinByInviteTokenHash（role）。</p>
 *
 * <p>途中失敗・古い attempt の競合（§9-3・§9-4）は {@link RegistrationPendingInviteFailureIT}。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CMP-261107-1833 招待付き新規登録の保留承認")
class RegistrationPendingInviteIT extends InviteAcceptanceItSupport {

    static final String PENDING_TABLE = "registration_pending_invites";
    static final String ME = "/api/v1/me/pending-invite";

    @Autowired private AuthRegistrationService registration;
    @Autowired private AuthTokenService authTokens;
    @Autowired private ParentalConsentService parentalConsent;
    @Autowired private ParentalConsentLinkRepository consentLinks;
    @Autowired private AuthCleanupBatchService cleanupBatch;
    @Autowired private MockMvc mockMvc;

    private final List<Long> registeredUsers = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> notifiedUsers = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    @SuppressWarnings("unchecked")
    void redisFixture() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
    }

    @AfterEach
    void removeRegisteredUsers() {
        boolean pendingTable = tableExists(PENDING_TABLE);
        for (Long userId : List.copyOf(registeredUsers)) {
            if (pendingTable) {
                jdbc.update("DELETE FROM " + PENDING_TABLE + " WHERE user_id = ?", userId);
            }
            if (claimsTableExists()) {
                jdbc.update("DELETE FROM " + CLAIMS + " WHERE user_id = ?", userId);
            }
            jdbc.update("DELETE FROM parental_consent_links WHERE child_user_id = ?", userId);
            jdbc.update("DELETE FROM email_verification_tokens WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM audit_logs WHERE user_id = ? OR target_user_id = ?", userId, userId);
            jdbc.update("DELETE FROM notifications WHERE user_id = ? OR actor_id = ?", userId, userId);
            jdbc.update("DELETE FROM user_roles WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM memberships WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM my_scope_folder_items WHERE folder_id IN (SELECT id FROM my_scope_folders WHERE user_id = ?)", userId);
            jdbc.update("DELETE FROM my_scope_folders WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (Long userId : List.copyOf(notifiedUsers)) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
        }
        registeredUsers.clear();
        notifiedUsers.clear();
    }

    // ------------------------------------------------------------------
    // 登録・確認・承認の手順
    // ------------------------------------------------------------------

    record Registered(Long userId, String email, String verifyToken) { }

    private Registered register(String inviteToken, LocalDate birthDate) {
        String email = "pending-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "Passw0rd!Passw0rd", "招待", "登録", "保留試練", "100-0001",
                "ja", "Asia/Tokyo", inviteToken, birthDate.toString(), true, "2026-01-01");
        AtomicReference<String> verifyToken = new AtomicReference<>();
        ApplicationListener<PayloadApplicationEvent<?>> listener = event -> {
            if (event.getPayload() instanceof UserRegisteredEvent registered && email.equals(registered.getEmail())) {
                verifyToken.set(registered.getRawToken());
            }
        };
        ApplicationEventMulticaster multicaster = context.getBean("applicationEventMulticaster", ApplicationEventMulticaster.class);
        multicaster.addApplicationListener(listener);
        try {
            registration.register(req, "198.51.100." + (int) (Math.random() * 200));
        } finally {
            multicaster.removeApplicationListener(listener);
            jdbc.queryForList("SELECT id FROM users WHERE email = ?", Long.class, email).forEach(registeredUsers::add);
        }
        Long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(verifyToken.get()).as("UserRegisteredEvent のメール確認トークン").isNotNull();
        return new Registered(userId, email, verifyToken.get());
    }

    private Registered registerAdult(String inviteToken) {
        return register(inviteToken, today().minusYears(30));
    }

    private Registered registerAndVerifyAdult(InviteTokenEntity token) {
        Registered r = registerAdult(plaintext(token));
        registration.verifyEmail(r.verifyToken());
        return r;
    }

    private static LocalDate today() {
        // 本番コード（AuthRegistrationService#verifyEmail）は年齢判定の「今日」を TimezoneContextHolder.get() で取る。
        // メール認証は未認証リクエストで、ホルダー未セット時は UTC。JST 固定にすると JST 0〜9 時台に 18 歳境界がずれる。
        return LocalDate.now(TimezoneContextHolder.get());
    }

    private PendingInviteService pending() {
        return context.getBean(PendingInviteService.class);
    }

    private String acceptStatus(Long userId) {
        PendingInviteAcceptResponse response = pending().accept(userId);
        return response.status();
    }

    private Map<String, Object> pendingRow(Long userId) {
        return jdbc.queryForMap("SELECT HEX(id) AS id, token_hash, scope_type, scope_id, status, failure_code, "
                + "HEX(accept_attempt_id) AS accept_attempt_id, accepting_started_at, reissue_requested_at FROM " + PENDING_TABLE
                + " WHERE user_id = ?", userId);
    }

    private String pendingStatus(Long userId) {
        return jdbc.queryForObject("SELECT status FROM " + PENDING_TABLE + " WHERE user_id = ?", String.class, userId);
    }

    private long pendingCount(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + PENDING_TABLE + " WHERE user_id = ?", Long.class, userId);
    }

    private String userStatus(Long userId) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
    }

    private boolean tableExists(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() "
                + "AND TABLE_NAME = ?", Long.class, table);
        return count != null && count > 0;
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo(code);
    }

    private String bearer(Long userId, boolean pendingParentalConsent) {
        return "Bearer " + authTokens.issueAccessToken(userId, List.of("MEMBER"), pendingParentalConsent);
    }

    private void makePrivate(Scope scope) {
        jdbc.update("UPDATE " + scope.table() + " SET visibility = ? WHERE id = ?",
                "TEAM".equals(scope.type()) ? "MEMBERS_AND_ABOVE" : "PRIVATE", scope.id());
    }

    private String slug(Scope scope) {
        return jdbc.queryForObject("SELECT slug FROM " + scope.table() + " WHERE id = ?", String.class, scope.id());
    }

    private long notificationCount(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND notification_type = ?",
                Long.class, userId, "INVITE_REISSUE_REQUESTED");
    }

    private void awaitNotification(Long userId, long expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (notificationCount(userId) >= expected) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("再招待要請の通知が10秒以内に届かない: userId=" + userId);
    }

    // ------------------------------------------------------------------
    // AC2: 成人 確認→ログイン→承認で参加
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC2: 成人は 登録（保留 PENDING）→メール確認で ACTIVE→承認で JOINED。used_count 1・claim GRANTED・監査は通常承諾と同等・既定フォルダ配置")
    void adultAcceptsAfterVerification(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);

        Registered r = registerAdult(plaintext(token));
        assertThat(userStatus(r.userId())).isEqualTo("PENDING_VERIFICATION");
        assertThat(pendingStatus(r.userId())).isEqualTo("PENDING");

        registration.verifyEmail(r.verifyToken());
        assertThat(userStatus(r.userId())).isEqualTo("ACTIVE");
        assertThat(pendingStatus(r.userId())).as("確認だけでは参加しない（非同期自動参加なし）").isEqualTo("PENDING");
        assertThat(usedCount(token.getId())).isZero();

        List<String> events;
        try (AuditCapture capture = capture(r.userId())) {
            assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");
            events = capture.events();
        }
        assertThat(pendingStatus(r.userId())).isEqualTo("JOINED");
        assertThat(pendingRow(r.userId()).get("accept_attempt_id")).as("完了時 attempt は NULL").isNull();
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(claims(token.getId())).singleElement().satisfies(c -> {
            assertThat(c.status()).isEqualTo("GRANTED");
            assertThat(c.userId()).isEqualTo(r.userId());
        });
        assertThat(userRoleCount(r.userId(), scope)).isEqualTo(1);
        assertThat(membershipCount(r.userId(), scope)).isEqualTo(1);
        assertThat(folderPlacements(r.userId(), scope)).containsExactly("DEFAULT");
        assertThat(events).containsExactlyInAnyOrderElementsOf(successAudit(type));
    }

    @Test
    @DisplayName("AC2: 承認前の GET は PENDING・招待元名・ロール名を返し、承認後の GET は JOINED")
    void getShowsPendingThenJoined() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);

        PendingInviteResponse before = pending().findMine(r.userId());
        assertThat(before.status()).isEqualTo("PENDING");
        assertThat(before.scopeType()).isEqualTo("TEAM");
        assertThat(before.scopeName()).isEqualTo("世代試練チーム");
        assertThat(before.roleName()).isEqualTo("MEMBER");

        acceptStatus(r.userId());
        assertThat(pending().findMine(r.userId()).status()).isEqualTo("JOINED");
    }

    @Test
    @DisplayName("AC2: 招待なし登録は保留を作らない（GET は null）")
    void noInviteNoPending() {
        Registered r = registerAdult(null);
        registration.verifyEmail(r.verifyToken());

        assertThat(pendingCount(r.userId())).isZero();
        assertThat(pending().findMine(r.userId())).isNull();
        assertCode(catchThrowable(() -> pending().accept(r.userId())), "AUTH_084");
    }

    // ------------------------------------------------------------------
    // AC3: 18歳境界・保護者同意・全員拒否・未確認掃除
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC3: 18歳の誕生日当日は成人（確認で ACTIVE、承認で参加）")
    void eighteenthBirthdayIsAdult() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = register(plaintext(token), today().minusYears(18));

        registration.verifyEmail(r.verifyToken());

        assertThat(userStatus(r.userId())).isEqualTo("ACTIVE");
        assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");
    }

    @Test
    @DisplayName("AC3（§9-1）: 18歳の前日は保護者同意待ち。同意待ちトークンの GET は 403 AUTH_070 で保留は PENDING のまま、"
            + "承認＋トークン更新後の GET は 200 PENDING、承認で参加できる")
    void minorWaitsForParentalConsent() throws Exception {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = register(plaintext(token), today().minusYears(18).plusDays(1));

        registration.verifyEmail(r.verifyToken());
        assertThat(userStatus(r.userId())).isEqualTo("PENDING_PARENTAL_CONSENT");

        mockMvc.perform(get(ME).header("Authorization", bearer(r.userId(), true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_070"));
        assertThat(pendingStatus(r.userId())).isEqualTo("PENDING");

        Long parent = user();
        String consentToken = UUID.randomUUID().toString();
        consentLinks.saveAndFlush(ParentalConsentLinkEntity.builder().childUserId(r.userId())
                .parentEmail(UUID.randomUUID() + "@guardian.invalid").tokenHash(authTokens.hashToken(consentToken))
                .status(ParentalConsentLinkStatus.PENDING).expiresAt(LocalDateTime.now().plusDays(1)).build());
        parentalConsent.approveParentalConsent(consentToken, parent, "127.0.0.1");
        assertThat(userStatus(r.userId())).isEqualTo("ACTIVE");

        mockMvc.perform(get(ME).header("Authorization", bearer(r.userId(), false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");
        assertThat(userRoleCount(r.userId(), scope)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC3: 保護者全員が拒否すると保留は DISCARDED（参加なし）")
    void allGuardiansRejectDiscards() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = register(plaintext(token), today().minusYears(18).plusDays(1));
        registration.verifyEmail(r.verifyToken());
        String consentToken = UUID.randomUUID().toString();
        consentLinks.saveAndFlush(ParentalConsentLinkEntity.builder().childUserId(r.userId())
                .parentEmail(UUID.randomUUID() + "@guardian.invalid").tokenHash(authTokens.hashToken(consentToken))
                .status(ParentalConsentLinkStatus.PENDING).expiresAt(LocalDateTime.now().plusDays(1)).build());

        parentalConsent.rejectParentalConsent(consentToken, "127.0.0.1");

        assertThat(pendingStatus(r.userId())).isEqualTo("DISCARDED");
        assertThat(usedCount(token.getId())).isZero();
        assertThat(userRoleCount(r.userId(), scope)).isZero();
    }

    @Test
    @DisplayName("AC3/§5: 7日間メール未確認で掃除されたアカウントの保留は DISCARDED")
    void unverifiedCleanupDiscards() {
        Scope scope = scope("ORGANIZATION");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAdult(plaintext(token));
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", utcNow().minusDays(8), r.userId());

        cleanupBatch.cleanupExpiredUnverifiedAccounts();

        assertThat(pendingStatus(r.userId())).isEqualTo("DISCARDED");
    }

    // ------------------------------------------------------------------
    // AC4: 宛先付き招待で登録
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC4: 宛先付き招待での登録は AUTH_083 で拒否し、users 行も保留も残らない")
    void targetedInviteIsRejected(String type) {
        Scope scope = scope(type);
        Long invitee = user();
        InviteTokenEntity token = token(scope, 0, null, 1, 0, invitee, null);
        String email = "targeted-" + UUID.randomUUID() + "@example.com";
        RegisterRequest req = new RegisterRequest(email, "Passw0rd!Passw0rd", "招待", "登録", "宛先付き", "100-0001",
                "ja", "Asia/Tokyo", plaintext(token), "1990-01-01", true, "2026-01-01");

        Throwable thrown = catchThrowable(() -> registration.register(req, "198.51.100.7"));

        assertCode(thrown, "AUTH_083");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isZero();
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimCountOrZero(token.getId())).isZero();
    }

    // ------------------------------------------------------------------
    // AC5: ベータ無効・不在トークン
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC5: ベータ無効で存在しない招待は保留を作らず登録だけ成功する")
    void unknownInviteRegistersWithoutPending() {
        Registered r = registerAdult(UUID.randomUUID().toString());

        assertThat(userStatus(r.userId())).isEqualTo("PENDING_VERIFICATION");
        assertThat(pendingCount(r.userId())).isZero();
    }

    // ------------------------------------------------------------------
    // AC6: 失効・上限・ブロック
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC6: 承認時に失効していたら EXPIRED（参加なし）。公開なら joinable=true・publicPath=/teams|/organizations/{slug}")
    void expiredPublic(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        jdbc.update("UPDATE invite_tokens SET expires_at = ? WHERE id = ?", utcNow().minusMinutes(1), token.getId());

        assertThat(acceptStatus(r.userId())).isEqualTo("EXPIRED");

        assertThat(userRoleCount(r.userId(), scope)).isZero();
        assertThat(usedCount(token.getId())).isZero();
        PendingInviteResponse view = pending().findMine(r.userId());
        assertThat(view.status()).isEqualTo("EXPIRED");
        assertThat(view.joinable()).isTrue();
        assertThat(view.publicPath()).isEqualTo(("TEAM".equals(type) ? "/teams/" : "/organizations/") + slug(scope));
        assertCode(catchThrowable(() -> pending().requestReissue(r.userId())), "AUTH_085");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC6: 上限到達は EXPIRED。非公開なら joinable=false・publicPath なしで再招待要請できる")
    void exhaustedPrivate(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 1, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        makePrivate(scope);
        jdbc.update("UPDATE invite_tokens SET used_count = 1 WHERE id = ?", token.getId());

        assertThat(acceptStatus(r.userId())).isEqualTo("EXPIRED");
        assertThat(pendingRow(r.userId()).get("failure_code")).isEqualTo("ROLE_003");

        PendingInviteResponse view = pending().findMine(r.userId());
        assertThat(view.joinable()).isFalse();
        assertThat(view.publicPath()).isNull();
        PendingInviteReissueResponse reissue = pending().requestReissue(r.userId());
        assertThat(reissue.alreadyRequested()).isFalse();
        assertThat(pendingStatus(r.userId())).isEqualTo("REISSUE_REQUESTED");
        pending().dismiss(r.userId());
        assertThat(pendingStatus(r.userId())).isEqualTo("DISMISSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC6: ブロック中は UNAVAILABLE（参加なし・使用回数不変）")
    void blockedIsUnavailable(String type) {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        block(scope, r.userId(), admin(scope));

        assertThat(acceptStatus(r.userId())).isEqualTo("UNAVAILABLE");
        assertThat(userRoleCount(r.userId(), scope)).isZero();
        assertThat(usedCount(token.getId())).isZero();
    }

    // ------------------------------------------------------------------
    // AC7: 二重押し・並行・既在籍・ACCEPTING 5分超
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC7: 並行2スレッドの承認でも付与は1回。各結果は JOINED か AUTH_086、最終は JOINED・used_count 1・claim GRANTED 1件")
    void concurrentAcceptGrantsOnce(String type) throws Exception {
        Scope scope = scope(type);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        return acceptStatus(r.userId());
                    } catch (BusinessException e) {
                        return e.getErrorCode().getCode();
                    }
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isIn("JOINED", "AUTH_086");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(pendingStatus(r.userId())).isEqualTo("JOINED");
        assertThat(userRoleCount(r.userId(), scope)).isEqualTo(1);
        assertThat(membershipCount(r.userId(), scope)).isEqualTo(1);
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(claimStatuses(token.getId())).containsExactly("GRANTED");
    }

    @Test
    @DisplayName("AC7: 二重押し（JOINED 後の再承認）は 200 JOINED で付与・回数は不変")
    void secondPressReturnsJoined() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");

        assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
        assertThat(userRoleCount(r.userId(), scope)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC7: 承認前に別経路で既に在籍していれば、招待を使わず JOINED（used_count 0）")
    void alreadyMemberIsJoined() {
        Scope scope = scope("ORGANIZATION");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        insertUserRole(scope, r.userId());
        insertMembership(scope, r.userId(), "MEMBER");

        assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");
        assertThat(usedCount(token.getId())).isZero();
        assertThat(claimCountOrZero(token.getId())).isZero();
    }

    @Test
    @DisplayName("AC7: ACCEPTING のまま5分以内は AUTH_086、5分超は再実行できて JOINED")
    void staleAcceptingCanBeRetried() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        jdbc.update("UPDATE " + PENDING_TABLE + " SET status = 'ACCEPTING', accept_attempt_id = UNHEX(REPLACE(UUID(),'-','')), "
                + "accepting_started_at = ? WHERE user_id = ?", utcNow().minusMinutes(1), r.userId());

        assertCode(catchThrowable(() -> pending().accept(r.userId())), "AUTH_086");
        PendingInviteResponse view = pending().findMine(r.userId());
        assertThat(view.status()).isEqualTo("ACCEPTING");
        assertThat(view.retryAvailableAt()).as("GET の retryAvailableAt").isNotNull();

        jdbc.update("UPDATE " + PENDING_TABLE + " SET accepting_started_at = ? WHERE user_id = ?",
                utcNow().minusMinutes(6), r.userId());
        assertThat(acceptStatus(r.userId())).isEqualTo("JOINED");
        assertThat(usedCount(token.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AC8: 辞退
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC8: 辞退で DECLINED・参加なし・使用回数不変。DECLINED への承認は AUTH_085")
    void declineThenAcceptIsRejected() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);

        pending().decline(r.userId());

        assertThat(pendingStatus(r.userId())).isEqualTo("DECLINED");
        assertThat(userRoleCount(r.userId(), scope)).isZero();
        assertThat(usedCount(token.getId())).isZero();
        assertCode(catchThrowable(() -> pending().accept(r.userId())), "AUTH_085");
        assertThat(revokedAt(token.getId())).as("共有招待そのものは失効させない").isNull();
    }

    // ------------------------------------------------------------------
    // AC9: 再招待要請の通知先
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC9: 再招待要請は招待権限者（ADMIN）にだけ届き、一般 MEMBER・他テナントの ADMIN には届かない。7日内の再押下は通知なし")
    void reissueNotifiesInvitersOnly(String type) throws Exception {
        Scope scope = scope(type);
        Scope otherTenant = scope(type);
        Long admin = admin(scope);
        Long member = user();
        insertUserRole(scope, member);
        insertMembership(scope, member, "MEMBER");
        Long otherAdmin = admin(otherTenant);
        notifiedUsers.addAll(List.of(admin, member, otherAdmin));
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        makePrivate(scope);
        jdbc.update("UPDATE invite_tokens SET revoked_at = ? WHERE id = ?", utcNow(), token.getId());
        assertThat(acceptStatus(r.userId())).isEqualTo("EXPIRED");

        assertThat(pending().requestReissue(r.userId()).alreadyRequested()).isFalse();
        awaitNotification(admin, 1);

        assertThat(pending().requestReissue(r.userId()).alreadyRequested()).as("7日内の再押下").isTrue();
        TimeUnit.MILLISECONDS.sleep(1500);
        assertThat(notificationCount(admin)).as("7日内の再押下は通知しない").isEqualTo(1);
        assertThat(notificationCount(member)).as("一般 MEMBER").isZero();
        assertThat(notificationCount(otherAdmin)).as("他テナントの ADMIN").isZero();
        assertThat(notificationCount(r.userId())).as("要請者本人").isZero();
    }

    @Test
    @DisplayName("AC9: 7日を過ぎた再押下は再び通知する")
    void reissueAfterSevenDaysNotifiesAgain() throws Exception {
        Scope scope = scope("TEAM");
        Long admin = admin(scope);
        notifiedUsers.add(admin);
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        makePrivate(scope);
        jdbc.update("UPDATE invite_tokens SET revoked_at = ? WHERE id = ?", utcNow(), token.getId());
        acceptStatus(r.userId());
        pending().requestReissue(r.userId());
        awaitNotification(admin, 1);

        jdbc.update("UPDATE " + PENDING_TABLE + " SET reissue_requested_at = ? WHERE user_id = ?",
                utcNow().minusDays(8), r.userId());
        assertThat(pending().requestReissue(r.userId()).alreadyRequested()).isFalse();
        awaitNotification(admin, 2);
    }

    // ------------------------------------------------------------------
    // AC10: 未認証・他人の行・ハッシュの露出
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC10: 未認証の GET・POST は 401")
    void unauthenticatedIs401() throws Exception {
        mockMvc.perform(get(ME)).andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(ME + "/accept"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AC10: 他人の操作は自分の行に触れない（A の辞退・承認で B の保留は PENDING のまま）")
    void othersCannotTouchMyRow() {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered a = registerAndVerifyAdult(token);
        Registered b = registerAndVerifyAdult(token);

        pending().decline(a.userId());
        assertCode(catchThrowable(() -> pending().accept(a.userId())), "AUTH_085");

        assertThat(pendingStatus(b.userId())).isEqualTo("PENDING");
        assertThat(userRoleCount(b.userId(), scope)).isZero();
    }

    @Test
    @DisplayName("AC10: 招待のハッシュ・平文は GET 応答にもログにも出ない")
    void hashNeverLeaks(CapturedOutput output) throws Exception {
        Scope scope = scope("TEAM");
        InviteTokenEntity token = token(scope, 0, null, 3, 0, null, null);
        Registered r = registerAndVerifyAdult(token);
        String hash = new SecretTokenVault().hash(plaintext(token));
        int logStart = output.getAll().length();

        MvcResult result = mockMvc.perform(get(ME).header("Authorization", bearer(r.userId(), false)))
                .andExpect(status().isOk())
                .andReturn();
        acceptStatus(r.userId());
        pending().findMine(r.userId());

        assertThat(result.getResponse().getContentAsString()).doesNotContain(hash).doesNotContain(plaintext(token));
        String logs = output.getAll().substring(logStart);
        assertThat(logs).as("承認経路のログ").doesNotContain(hash).doesNotContain(plaintext(token));
    }
}
