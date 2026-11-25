package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.committee.dto.CommitteeDistributeRequest;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.entity.DistributionScope;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.committee.service.CommitteeDistributionService;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.support.ConfirmableFanoutFixture;
import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.repository.OrganizationNotificationBalanceRepository;
import com.mannschaft.app.support.perf.SqlStatementCounter;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * CMP-261002-1559 試練（実 MySQL）: 同期 {@link ConfirmableNotificationService#send} の受信者上限
 * （{@code MAX_RECIPIENT_COUNT=500}）撤廃と「500人ずつ多値 INSERT」の受け入れテスト。
 *
 * <p>マスター裁可（2026-11-25）: 受信者上限を撤廃し、同期 send も {@link ConfirmableFanoutChunkSink} と同じ
 * 多値 INSERT（受信者行・アプリ内通知・既存受信者除外）を共通化して使う。通知（親）は1件のまま。
 * 設計書 F04.9 §4「1 チャンクは最大 500 人であり、500 人は全体の上限ではない」。</p>
 *
 * <h2>SQL 文数の数え方（F2）</h2>
 * <p>{@link ConfirmableFanoutChunkSinkInsertCountTest} と<b>同じ構成</b>（同じ {@code @Import}）で
 * {@code CountingDataSource} を使う（構成キーを増やさないため。docs/conventions/backend/31-ci-shard-weighting.md）。
 * 計数器は共有の静的インスタンスなので、本クラスは「テストスレッドで発行された文」だけを数える述語を
 * 毎回登録する。AFTER_COMMIT + {@code @Async} のメールリスナ・配信プールの文は数えない
 * （同期 send の呼び出し内で受信者数に比例する文が出ないことを固定する）。</p>
 *
 * <p>クラスに {@code @Transactional} は付けない（send 自身の TX をコミットさせて実 DB を観測する。§9.7）。
 * 所有行は実行ごとの一意な接頭辞・タイトルで識別し {@link #cleanUp()} で消す。</p>
 */
@DisplayName("CMP-261002-1559 同期 send の受信者上限撤廃と一括INSERT（実DB）")
@Import(ConfirmableFanoutChunkSinkInsertCountTest.CountingDsConfig.class)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ConfirmableNotificationSyncSendBulkIT extends AbstractMySqlIntegrationTest {

    /** F2: 1000人送信で同期 send がテストスレッドで発行してよい SQL 文数の上限（受信者数に比例しない定数）。 */
    private static final long MAX_SYNC_STATEMENTS_FOR_1000 = 40L;
    /** F2: 1000人 = 500人×2チャンク。各表への INSERT は高々チャンク数。 */
    private static final long MAX_INSERTS_PER_TABLE_FOR_1000 = 2L;

    private static final String SYNC_ALL = "cmp1559_sync_all";
    private static final String SYNC_RECIPIENT_INSERT = "cmp1559_sync_recipient_insert";
    private static final String SYNC_NOTIFICATION_INSERT = "cmp1559_sync_notification_insert";

    @Autowired
    private ConfirmableNotificationService service;
    @Autowired
    private CommitteeDistributionService committeeDistributionService;
    @Autowired
    private CommitteeRepository committeeRepository;
    @Autowired
    private OrganizationNotificationBalanceRepository balanceRepository;
    @Autowired
    private SqlStatementCounter statementCounter;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager em;

    private String tag;
    private String titlePrefix;
    private final List<String> emailPrefixes = new ArrayList<>();
    private final List<Long> scopeIds = new ArrayList<>();
    private Long organizationId;
    private Long committeeId;
    private Long balanceOrganizationId;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        titlePrefix = "CMP-261002-1559 " + tag;
        Thread testThread = Thread.currentThread();
        statementCounter.register(SYNC_ALL, sql -> Thread.currentThread() == testThread);
        statementCounter.register(SYNC_RECIPIENT_INSERT, sql -> Thread.currentThread() == testThread
                && sql.contains("insert") && sql.contains("confirmable_notification_recipients"));
        statementCounter.register(SYNC_NOTIFICATION_INSERT, sql -> Thread.currentThread() == testThread
                && sql.contains("insert") && sql.contains("into notifications"));
    }

    @AfterEach
    void cleanUp() {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM confirmable_notifications WHERE title LIKE ?", Long.class, titlePrefix + "%");
        for (Long id : ids) {
            jdbc.update("DELETE FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?", id);
            jdbc.update("DELETE FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?", id);
            jdbc.update("DELETE FROM email_outbox WHERE source_event_id LIKE ?", "notif-confirm:" + id + ":%");
            jdbc.update("DELETE FROM committee_distribution_logs WHERE confirmable_notification_id = ?", id);
            jdbc.update("DELETE FROM confirmable_notifications WHERE id = ?", id);
        }
        for (Long scopeId : scopeIds) {
            jdbc.update("DELETE FROM confirmable_notification_settings WHERE scope_id = ?", scopeId);
        }
        if (committeeId != null) {
            jdbc.update("DELETE FROM committee_distribution_logs WHERE committee_id = ?", committeeId);
            jdbc.update("DELETE FROM committee_members WHERE committee_id = ?", committeeId);
            jdbc.update("DELETE FROM confirmable_notification_settings WHERE scope_type = 'COMMITTEE' AND scope_id = ?",
                    committeeId);
            jdbc.update("DELETE FROM committees WHERE id = ?", committeeId);
        }
        if (organizationId != null) {
            jdbc.update("DELETE FROM organizations WHERE id = ?", organizationId);
        }
        if (balanceOrganizationId != null) {
            jdbc.update("DELETE FROM notification_monthly_usage WHERE organization_id = ?", balanceOrganizationId);
            jdbc.update("DELETE FROM organization_notification_balances WHERE organization_id = ?", balanceOrganizationId);
        }
        for (String prefix : emailPrefixes) {
            ConfirmableFanoutFixture.deleteUsers(transactionManager, em, prefix);
        }
    }

    // =====================================================================
    // F1 境界値
    // =====================================================================

    @ParameterizedTest(name = "F1: 受信者{0}人")
    @ValueSource(ints = {500, 501, 1000})
    @DisplayName("F1: 受信者 500/501/1000 人（境界）で、全員に受信者行とアプリ内通知が1件ずつでき、通知（親）は1件")
    void F1_受信者500_501_1000人_全員に受信者行とアプリ内通知ができる(int count) {
        List<Long> users = users(count);
        Long scopeId = newScopeId();

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = sendTeam(scopeId, "F1-" + count, users));

        assertThat(thrown)
                .as("F1(%d人): 受信者上限は撤廃される（現状は 500 超過で SEND_FAILED）", count)
                .isNull();
        Long id = created[0].getId();
        assertThat(recipientUserIds(id)).as("F1(%d人): 全員に受信者行がある", count)
                .hasSize(count).containsExactlyInAnyOrderElementsOf(users);
        assertThat(appNotificationUserIds(id)).as("F1(%d人): 全員にアプリ内通知が1件ずつある", count)
                .hasSize(count).containsExactlyInAnyOrderElementsOf(users);
        assertThat(countParentsByTitle(titlePrefix + " F1-" + count))
                .as("F1(%d人): 確認通知（親）は1件のまま", count).isEqualTo(1L);
    }

    // =====================================================================
    // F2 SQL 文数
    // =====================================================================

    @Test
    @DisplayName("F2: 1000人送信で同期 send が発行する SQL 文数は定数上限内（受信者行・通知の INSERT は高々チャンク数）")
    void F2_1000人送信_SQL文数が受信者数に比例しない() {
        List<Long> users = users(1000);
        Long scopeId = newScopeId();

        statementCounter.reset();
        Throwable thrown = catchThrowable(() -> sendTeam(scopeId, "F2", users));
        long all = statementCounter.count(SYNC_ALL);
        long recipientInserts = statementCounter.count(SYNC_RECIPIENT_INSERT);
        long notificationInserts = statementCounter.count(SYNC_NOTIFICATION_INSERT);

        assertThat(thrown).as("F2: 1000人送信が成功する").isNull();
        assertThat(recipientInserts)
                .as("F2: confirmable_notification_recipients への INSERT 文は 500人ずつの多値 INSERT（1000人で高々%d文）",
                        MAX_INSERTS_PER_TABLE_FOR_1000)
                .isPositive().isLessThanOrEqualTo(MAX_INSERTS_PER_TABLE_FOR_1000);
        assertThat(notificationInserts)
                .as("F2: notifications への INSERT 文は 500人ずつの多値 INSERT（1000人で高々%d文）",
                        MAX_INSERTS_PER_TABLE_FOR_1000)
                .isPositive().isLessThanOrEqualTo(MAX_INSERTS_PER_TABLE_FOR_1000);
        assertThat(all)
                .as("F2: 同期 send がテストスレッドで発行した SQL 文の総数は定数上限 %d 以内"
                        + "（受信者ごとの INSERT/SELECT（N+1）が無い）", MAX_SYNC_STATEMENTS_FOR_1000)
                .isLessThanOrEqualTo(MAX_SYNC_STATEMENTS_FOR_1000);
    }

    // =====================================================================
    // F3 冪等
    // =====================================================================

    @Test
    @DisplayName("F3: 同じ発生元（RECRUITMENT_AUTO_CANCEL）で1000人に再発火しても、uq_cn_once_per_source で親1件・受信者行1000・通知1000のまま")
    void F3_同じsourceで再発火しても受信者行と通知が重複しない() {
        List<Long> users = users(1000);
        Long scopeId = newScopeId();
        Long sourceId = 990_000_000L + Math.abs(System.nanoTime() % 1_000_000L);

        Throwable first = catchThrowable(() -> sendAutoCancel(scopeId, sourceId, users));
        assertThat(first).as("F3: 初回の1000人送信は成功する").isNull();

        Throwable second = catchThrowable(() -> sendAutoCancel(scopeId, sourceId, users));

        assertThat(second).as("F3: 再発火は uq_cn_once_per_source で拒否される").isNotNull();
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM confirmable_notifications WHERE source_type = 'RECRUITMENT_AUTO_CANCEL' AND source_id = ?",
                Long.class, sourceId);
        assertThat(ids).as("F3: 同じ発生元の確認通知（親）は1件").hasSize(1);
        assertThat(recipientUserIds(ids.get(0))).as("F3: 受信者行は1000のまま（重複なし）").hasSize(1000);
        assertThat(appNotificationUserIds(ids.get(0))).as("F3: アプリ内通知は1000のまま（重複なし）").hasSize(1000);
        assertThat(countAppNotificationsForUsers(users))
                .as("F3: 再発火で受信者側に確認通知のアプリ内通知が増えない").isEqualTo(1000L);
    }

    @Test
    @DisplayName("F3: 入力にチャンク境界をまたぐ重複（1000人+先頭200人の再掲）があっても、受信者行・通知は1人1件で総数は1000")
    void F3_チャンク境界をまたぐ重複入力でも1人1件() {
        List<Long> users = users(1000);
        List<Long> withDuplicates = new ArrayList<>(users);
        withDuplicates.addAll(users.subList(0, 200));
        Long scopeId = newScopeId();

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = sendTeam(scopeId, "F3dup", withDuplicates));

        assertThat(thrown).as("F3: 重複入力（1200件・一意1000人）でも送信できる").isNull();
        Long id = created[0].getId();
        assertThat(recipientUserIds(id)).as("F3: 受信者行は一意の1000").hasSize(1000).doesNotHaveDuplicates();
        assertThat(appNotificationUserIds(id)).as("F3: 通知は一意の1000").hasSize(1000).doesNotHaveDuplicates();
        assertThat(parentCounters(id).get("total_recipient_count")).as("F3: 総受信者数は1000")
                .isEqualTo(1000);
    }

    // =====================================================================
    // F4 呼出元の同期経路
    // =====================================================================

    @Test
    @DisplayName("F4: 委員会配布（CommitteeDistributionService#distribute）で現役メンバー501人全員に受信者行と通知が届く")
    void F4_委員会配布で501人全員に届く() throws Exception {
        List<Long> users = users(501);
        organizationId = createOrg();
        CommitteeEntity committee = committeeRepository.save(CommitteeEntity.builder()
                .organizationId(organizationId)
                .name("CMP-261002-1559 委員会")
                .status(CommitteeStatus.ACTIVE)
                .build());
        committeeId = committee.getId();
        insertCommitteeMembers(committeeId, users);
        Long chairUserId = users.get(0);

        CommitteeDistributeRequest request = new CommitteeDistributeRequest();
        setField(request, "contentType", "CUSTOM_MESSAGE");
        setField(request, "customTitle", titlePrefix + " F4-committee");
        setField(request, "customBody", "本文");
        setField(request, "targetScope", DistributionScope.COMMITTEE_ONLY);
        setField(request, "announcementEnabled", Boolean.FALSE);
        setField(request, "confirmationMode", ConfirmationMode.REQUIRED);
        setField(request, "confirmationDeadlineAt", LocalDateTime.now().plusDays(7));

        CommitteeDistributionLogEntity[] logEntity = new CommitteeDistributionLogEntity[1];
        Throwable thrown = catchThrowable(() ->
                logEntity[0] = committeeDistributionService.distribute(committeeId, request, chairUserId));

        assertThat(thrown).as("F4: 委員会メンバー501人への配布が SEND_FAILED にならない").isNull();
        Long id = logEntity[0].getConfirmableNotificationId();
        assertThat(id).as("F4: 確認通知が作られる").isNotNull();
        assertThat(recipientUserIds(id)).as("F4: 委員会メンバー501人全員に受信者行").containsExactlyInAnyOrderElementsOf(users);
        assertThat(appNotificationUserIds(id)).as("F4: 委員会メンバー501人全員にアプリ内通知").containsExactlyInAnyOrderElementsOf(users);
    }

    @Test
    @DisplayName("F4: 支払い依頼（PaymentRequestService と同じ呼出形: TEAM・HIGH・期限・actionUrl）で501人全員に届く")
    void F4_支払い依頼の呼出形で501人全員に届く() {
        List<Long> users = users(501);
        Long teamId = newScopeId();
        String actionUrl = "/teams/" + teamId + "/payment-requests/1";

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = service.send(
                ScopeType.TEAM, teamId, titlePrefix + " F4-payment", "請求本文",
                ConfirmableNotificationPriority.HIGH, LocalDateTime.now().plusDays(14).withNano(0),
                null, null, actionUrl, null, users.get(0), users));

        assertThat(thrown).as("F4: 支払い依頼の呼出形で501人送信が SEND_FAILED にならない").isNull();
        Long id = created[0].getId();
        assertThat(recipientUserIds(id)).as("F4: 501人全員に受信者行").containsExactlyInAnyOrderElementsOf(users);
        assertThat(appNotificationUserIds(id)).as("F4: 501人全員にアプリ内通知").containsExactlyInAnyOrderElementsOf(users);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ? "
                        + "AND action_url = ? AND priority = 'HIGH'", Long.class, id, actionUrl))
                .as("F4: アプリ内通知は actionUrl・優先度 HIGH を保ったまま全員分").isEqualTo(501L);
    }

    // =====================================================================
    // F5 集計
    // =====================================================================

    @Test
    @DisplayName("F5: 1000人送信の通知1件で総受信者数・未確認数が1000、1人確認すると未確認数が999になる")
    void F5_1000人送信の集計が1通知で全受信者分正しい() {
        List<Long> users = users(1000);
        Long scopeId = newScopeId();

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = sendTeam(scopeId, "F5", users));
        assertThat(thrown).as("F5: 1000人送信が成功する").isNull();
        Long id = created[0].getId();

        Map<String, Object> counters = parentCounters(id);
        assertThat(counters.get("total_recipient_count")).as("F5: 総受信者数=1000").isEqualTo(1000);
        assertThat(counters.get("unconfirmed_count")).as("F5: 未確認数=1000").isEqualTo(1000);
        assertThat(counters.get("delivery_status")).as("F5: 同期 send は配信済み").isEqualTo("DELIVERED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients "
                        + "WHERE confirmable_notification_id = ? AND is_confirmed = 0", Long.class, id))
                .as("F5: 未確認の受信者行=1000").isEqualTo(1000L);

        Long lastChunkUser = users.get(999); // 2チャンク目の利用者
        service.confirm(id, lastChunkUser);

        assertThat(parentCounters(id).get("unconfirmed_count"))
                .as("F5: 2チャンク目の利用者が確認すると未確認数=999").isEqualTo(999);
    }

    // =====================================================================
    // F6 課金
    // =====================================================================

    @Test
    @DisplayName("F6: 同期 send は F09.13 の課金対象外。残高0・猶予超過の組織へ1000人送っても成功し、残高行・月次使用量は不変")
    void F6_同期sendは1000人でも通知クレジットを消費しない() {
        List<Long> users = users(1000);
        balanceOrganizationId = 88_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
        scopeIds.add(balanceOrganizationId);
        balanceRepository.save(OrganizationNotificationBalanceEntity.builder()
                .organizationId(balanceOrganizationId)
                .freeUsedThisMonth(10_000L)
                .freeQuotaMonth(LocalDate.now().withDayOfMonth(1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodStartAt(LocalDateTime.now().minusHours(73))
                .gracePeriodDebt(1L)
                .build());
        List<Object> before = balanceSnapshot();
        long usageBefore = monthlyUsageRows();

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = service.send(
                ScopeType.ORGANIZATION, balanceOrganizationId, titlePrefix + " F6", "本文",
                ConfirmableNotificationPriority.NORMAL, null,
                null, null, null, null, null, users.get(0), users));

        assertThat(thrown).as("F6: 課金対象外のため残高にかかわらず1000人送信が成功する").isNull();
        assertThat(recipientUserIds(created[0].getId())).as("F6: 受信者行は1000").hasSize(1000);
        assertThat(balanceSnapshot()).as("F6: 残高行（無料枠使用量・残高・猶予）は不変").isEqualTo(before);
        assertThat(monthlyUsageRows()).as("F6: 月次使用量の行は増えない").isEqualTo(usageBefore);
    }

    // =====================================================================
    // F7 可視性・他テナント
    // =====================================================================

    @Test
    @DisplayName("F7: 受信者行と通知の対象は入力（呼出元が確定した受信者）と完全一致し、入力外の他テナント利用者は一切含まれない")
    void F7_入力外の他テナント利用者は受信者行にも通知にも入らない() {
        List<Long> users = users(1000);
        List<Long> outsiders = users(5); // 別テナント相当（入力に含めない）
        Long scopeId = newScopeId();

        ConfirmableNotificationEntity[] created = new ConfirmableNotificationEntity[1];
        Throwable thrown = catchThrowable(() -> created[0] = sendTeam(scopeId, "F7", users));

        assertThat(thrown).as("F7: 1000人送信が成功する").isNull();
        Long id = created[0].getId();
        List<Long> recipientRows = recipientUserIds(id);
        List<Long> notified = appNotificationUserIds(id);
        assertThat(new HashSet<>(recipientRows)).as("F7: 受信者行の集合と通知の集合は一致する")
                .isEqualTo(new HashSet<>(notified));
        assertThat(recipientRows).as("F7: 受信者行は入力と完全一致").containsExactlyInAnyOrderElementsOf(users)
                .doesNotContainAnyElementsOf(outsiders);
        assertThat(notified).as("F7: 通知は入力と完全一致").doesNotContainAnyElementsOf(outsiders);
        assertThat(countAppNotificationsForUsers(outsiders)).as("F7: 入力外の利用者には確認通知が1件も作られない")
                .isZero();
    }

    // =====================================================================
    // F8 失敗時の原子性
    // =====================================================================

    @Test
    @DisplayName("F8: 2チャンク目の受信者行 INSERT が失敗したら、親・1チャンク目の受信者行・通知を含め全体がロールバックされる")
    void F8_2チャンク目の受信者行INSERT失敗で全体がロールバックされる() throws SQLException {
        assertWholeSendRollsBackWhenSecondChunkFails("confirmable_notification_recipients", "F8r");
    }

    @Test
    @DisplayName("F8: 2チャンク目のアプリ内通知 INSERT が失敗したら、親・受信者行・1チャンク目の通知を含め全体がロールバックされる")
    void F8_2チャンク目の通知INSERT失敗で全体がロールバックされる() throws SQLException {
        assertWholeSendRollsBackWhenSecondChunkFails("notifications", "F8n");
    }

    private void assertWholeSendRollsBackWhenSecondChunkFails(String table, String label) throws SQLException {
        List<Long> users = users(1000);
        Long poisoned = users.get(700); // 2チャンク目（501〜1000人目）の利用者
        Long scopeId = newScopeId();
        String trigger = "cmp1559_fail_" + UUID.randomUUID().toString().replace("-", "");
        ownTriggerDdl(trigger, table, poisoned, true);
        Throwable thrown;
        try {
            thrown = catchThrowable(() -> sendTeam(scopeId, label, users));
        } finally {
            ownTriggerDdl(trigger, table, poisoned, false);
        }

        assertThat(thrown).as("F8(%s): 2チャンク目の失敗は呼び出し元へ伝播する", table).isNotNull();
        assertThat(countParentsByTitle(titlePrefix + " " + label))
                .as("F8(%s): 確認通知（親）は残らない", table).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM confirmable_notification_recipients WHERE user_id IN ("
                        + placeholders(users) + ")", Long.class, users.toArray()))
                .as("F8(%s): 1チャンク目の受信者行も残らない（部分送信なし）", table).isZero();
        assertThat(countAppNotificationsForUsers(users))
                .as("F8(%s): 1チャンク目のアプリ内通知も残らない（部分送信なし）", table).isZero();
    }

    @Test
    @DisplayName("アプリ内通知の organization_id: 組織スコープは scope_id、チームスコープは NULL（非同期経路と同じ規則）")
    void 組織スコープのアプリ内通知はorganization_idを持ちチームはNULL() {
        List<Long> orgUsers = users(3);
        Long orgScopeId = newScopeId();
        ConfirmableNotificationEntity org = service.send(ScopeType.ORGANIZATION, orgScopeId,
                titlePrefix + " ORG", "本文", ConfirmableNotificationPriority.NORMAL, null,
                null, null, null, null, null, orgUsers.get(0), orgUsers);
        List<Long> teamUsers = users(3);
        ConfirmableNotificationEntity team = sendTeam(newScopeId(), "TEAMORG", teamUsers);

        assertThat(jdbc.queryForList(
                "SELECT organization_id FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Long.class, org.getId()))
                .as("組織スコープ: 全通知の organization_id = scope_id").hasSize(3).containsOnly(orgScopeId);
        assertThat(jdbc.queryForList(
                "SELECT organization_id FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Long.class, team.getId()))
                .as("チームスコープ: organization_id は NULL").hasSize(3).containsOnlyNulls();
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private List<Long> users(int count) {
        String prefix = "cmp1559-" + tag + "-" + emailPrefixes.size();
        emailPrefixes.add(prefix);
        return ConfirmableFanoutFixture.insertUsers(transactionManager, em, count, prefix);
    }

    private Long newScopeId() {
        Long id = 87_000_000L + Math.abs(System.nanoTime() % 1_000_000L);
        scopeIds.add(id);
        return id;
    }

    private ConfirmableNotificationEntity sendTeam(Long scopeId, String label, List<Long> recipients) {
        return service.send(ScopeType.TEAM, scopeId, titlePrefix + " " + label, "本文",
                ConfirmableNotificationPriority.NORMAL, null,
                null, null, null, null, null, recipients.get(0), recipients);
    }

    private ConfirmableNotificationEntity sendAutoCancel(Long scopeId, Long sourceId, List<Long> recipients) {
        return service.sendFromSource("RECRUITMENT_AUTO_CANCEL", sourceId, ScopeType.TEAM, scopeId,
                titlePrefix + " F3", "本文", ConfirmableNotificationPriority.HIGH, null, null,
                recipients.get(0), recipients);
    }

    private List<Long> recipientUserIds(Long notificationId) {
        return jdbc.queryForList(
                "SELECT user_id FROM confirmable_notification_recipients WHERE confirmable_notification_id = ?",
                Long.class, notificationId);
    }

    private List<Long> appNotificationUserIds(Long notificationId) {
        return jdbc.queryForList(
                "SELECT user_id FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND source_id = ?",
                Long.class, notificationId);
    }

    private long countAppNotificationsForUsers(List<Long> userIds) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE source_type = 'CONFIRMABLE_NOTIFICATION' AND user_id IN ("
                        + placeholders(userIds) + ")", Long.class, userIds.toArray());
        return c == null ? 0 : c;
    }

    private long countParentsByTitle(String title) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM confirmable_notifications WHERE title = ?", Long.class, title);
        return c == null ? 0 : c;
    }

    private Map<String, Object> parentCounters(Long notificationId) {
        return jdbc.queryForMap(
                "SELECT total_recipient_count, unconfirmed_count, delivery_status FROM confirmable_notifications WHERE id = ?",
                notificationId);
    }

    private List<Object> balanceSnapshot() {
        return new TransactionTemplate(transactionManager).execute(status -> balanceRepository
                .findByOrganizationId(balanceOrganizationId)
                .map(b -> List.<Object>of(b.getFreeUsedThisMonth(), b.getCreditBalance(),
                        String.valueOf(b.getGracePeriodStartAt()), b.getGracePeriodDebt()))
                .orElse(List.of()));
    }

    private long monthlyUsageRows() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM notification_monthly_usage WHERE organization_id = ?",
                Long.class, balanceOrganizationId);
        return c == null ? 0 : c;
    }

    private static String placeholders(List<Long> ids) {
        return String.join(",", ids.stream().map(i -> "?").toList());
    }

    private long createOrg() {
        jdbc.update("INSERT INTO organizations "
                        + "(slug, name, org_type, visibility, hierarchy_visibility, supporter_enabled, "
                        + "version, created_at, updated_at) "
                        + "VALUES (?, 'CMP-261002-1559組織', 'COMMUNITY', 'PUBLIC', 'FULL', 1, 0, NOW(), NOW())",
                "cmp1559-" + (System.nanoTime() % 1_000_000_000L));
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    private void insertCommitteeMembers(Long committeeId, List<Long> userIds) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO committee_members (committee_id, user_id, role, joined_at) VALUES ");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < userIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,NOW() - INTERVAL 5 DAY)");
            args.add(committeeId);
            args.add(userIds.get(i));
            args.add(i == 0 ? "CHAIR" : "MEMBER");
        }
        jdbc.update(sql.toString(), args.toArray());
    }

    private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = CommitteeDistributeRequest.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 自己検体（実行ごとに一意な trigger 名・自分が作った利用者）だけに作用する失敗注入 trigger の DDL。 */
    private void ownTriggerDdl(String trigger, String table, Long poisonedUserId, boolean create) throws SQLException {
        if (!trigger.matches("cmp1559_fail_[0-9a-f]{32}")
                || !Set.of("confirmable_notification_recipients", "notifications").contains(table)
                || poisonedUserId == null || poisonedUserId <= 0) {
            throw new IllegalArgumentException("自己triggerの識別子・対象表の前提を満たさない");
        }
        String url = MYSQL.getJdbcUrl();
        url += (url.contains("?") ? "&" : "?") + "connectTimeout=1000&socketTimeout=5000&useSSL=false"
                + "&allowPublicKeyRetrieval=true";
        try (Connection connection = DriverManager.getConnection(url, "root", MYSQL.getPassword());
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(create
                    ? "CREATE TRIGGER " + trigger + " BEFORE INSERT ON " + table + " FOR EACH ROW BEGIN "
                            + "IF NEW.user_id = " + poisonedUserId
                            + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'cmp1559 injected failure'; END IF; END"
                    : "DROP TRIGGER " + trigger);
        }
    }
}
