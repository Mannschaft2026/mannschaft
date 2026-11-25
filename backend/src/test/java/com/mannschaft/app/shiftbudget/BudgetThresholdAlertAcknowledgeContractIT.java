package com.mannschaft.app.shiftbudget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260910-1607 試練 ③: 予算閾値警告の確認（{@code POST /api/v1/shift-budget/alerts/{id}/acknowledge}）を
 * 実 JWT・実 Security フィルタ・実 MySQL（commit 観測）で固定する。
 *
 * <p>マスター裁定（確定）: 未確認なら 200 で確認記録。確認済みへの再確認（上書き指定なし）は 409（新エラーコード）で、
 * 応答に既存の確認者・確認時刻を含め、何も変えない。上書き指定（試練では {@code overwrite: true} を想定）付きの再送は
 * 200 で上書きし、監査ログ {@code BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED} を毎回 1 件記録する。
 * 並行二重 POST（上書き指定なし）は片方 200・片方 409、記録は先着の値。</p>
 *
 * <p>BUDGET_ADMIN は本番（V11.034）と同じく ADMIN ロールの既定権限として付与する（test は Flyway 無効のため
 * 不在なら本クラスが投入し、投入した行だけを後始末する）。監査ログは {@code @Async} で書かれるため、
 * 件数は待ってから数え、「増えない」ことは一定時間待ったうえで確かめる。</p>
 *
 * <p>攻め口: 性能＝該当なし（単一行の更新で、件数に比例する経路を持たない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260910-1607 予算閾値警告の確認（再確認 409・上書き・並行）")
class BudgetThresholdAlertAcknowledgeContractIT extends AbstractMySqlIntegrationTest {

    private static final String ACK = "/api/v1/shift-budget/alerts/{id}/acknowledge";
    private static final String EVENT = "BUDGET_THRESHOLD_ALERT_ACKNOWLEDGED";
    private static final long AUDIT_WAIT_MS = 10_000;
    private static final long AUDIT_SETTLE_MS = 1_500;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private BudgetThresholdAlertRepository alerts;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> organizationIds = new ArrayList<>();
    private final List<Long> allocationIds = new ArrayList<>();
    private boolean insertedPermission;
    private boolean insertedRolePermission;

    private Long org;
    private Long otherOrg;
    private Long budgetAdmin1;
    private Long budgetAdmin2;
    private Long plainMember;
    private Long outsideSystemAdmin;

    @BeforeEach
    void 所有fixtureをcommitする() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        inTx(() -> {
            org = organization("CMP1607 組織");
            otherOrg = organization("CMP1607 他組織");
            budgetAdmin1 = user("予算管理者1");
            budgetAdmin2 = user("予算管理者2");
            plainMember = user("一般メンバー");
            // この組織のメンバーではない SYSTEM_ADMIN（isSystemAdmin で BUDGET_ADMIN 要件を通る）
            outsideSystemAdmin = user("組織外システム管理者");
            MembershipTestHelper.insertUserRole(em, outsideSystemAdmin, "SYSTEM_ADMIN", null, null);
            for (Long admin : List.of(budgetAdmin1, budgetAdmin2)) {
                MembershipTestHelper.insertMembership(em, admin, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);
                MembershipTestHelper.insertUserRole(em, admin, "ADMIN", null, org);
            }
            MembershipTestHelper.insertMembership(em, plainMember, ScopeType.ORGANIZATION, org, RoleKind.MEMBER);
            // roles の ADMIN 行は insertUserRole が冪等 seed するので、その後で権限を紐付ける
            grantBudgetAdminToAdminRole();
            return null;
        });
    }

    @AfterEach
    void 所有行だけをcleanupする() {
        inTx(() -> {
            if (!allocationIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM budget_threshold_alerts WHERE allocation_id IN (:ids)")
                        .setParameter("ids", allocationIds).executeUpdate();
                em.createNativeQuery("DELETE FROM shift_budget_allocations WHERE id IN (:ids)")
                        .setParameter("ids", allocationIds).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM audit_logs WHERE organization_id IN (:ids)")
                    .setParameter("ids", organizationIds).executeUpdate();
            for (String table : List.of("user_roles", "memberships")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE user_id IN (:ids)")
                        .setParameter("ids", userIds).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)").setParameter("ids", userIds).executeUpdate();
            organizations.deleteAllById(organizationIds);
            if (insertedRolePermission) {
                em.createNativeQuery("DELETE rp FROM role_permissions rp JOIN roles r ON r.id = rp.role_id "
                        + "JOIN permissions p ON p.id = rp.permission_id "
                        + "WHERE r.name = 'ADMIN' AND p.name = 'BUDGET_ADMIN'").executeUpdate();
            }
            if (insertedPermission) {
                em.createNativeQuery("DELETE FROM permissions WHERE name = 'BUDGET_ADMIN'").executeUpdate();
            }
            return null;
        });
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    @Test
    @DisplayName("AC-9: 未確認の警告を BUDGET_ADMIN が確認すると 200。acknowledgedBy/At を記録し監査ログ 1 件")
    void AC9_未確認の確認は200で記録と監査1件() throws Exception {
        Long alert = alert(org);

        MockHttpServletResponse response = acknowledge(budgetAdmin1, alert, null);

        expect(response, 200, null);
        Map<String, Object> stored = ackRow(alert);
        assertThat(((Number) stored.get("acknowledged_by")).longValue()).isEqualTo(budgetAdmin1);
        assertThat(stored.get("acknowledged_at")).isNotNull();
        assertThat(json(response).at("/data/acknowledged_by").asLong()).isEqualTo(budgetAdmin1);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9b: 確認済みへの同一 BUDGET_ADMIN の再確認（上書き指定なし）は 409。応答に既存の確認者・時刻、DB・監査不変")
    void AC9b_同一管理者の再確認は409で不変() throws Exception {
        Long alert = alert(org);
        expect(acknowledge(budgetAdmin1, alert, null), 200, null);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
        Map<String, Object> before = ackRow(alert);

        MockHttpServletResponse response = acknowledge(budgetAdmin1, alert, null);

        expectAlreadyAcknowledged(response, before);
        assertThat(ackRow(alert)).isEqualTo(before);
        assertThat(settledAuditCount(alert)).as("監査ログを足さない").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9b: 確認済みへの別 BUDGET_ADMIN の再確認（上書き指定なし）は 409。応答に既存の確認者・時刻、DB・監査不変")
    void AC9b_別管理者の再確認は409で不変() throws Exception {
        Long alert = alert(org);
        expect(acknowledge(budgetAdmin1, alert, null), 200, null);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
        Map<String, Object> before = ackRow(alert);

        MockHttpServletResponse response = acknowledge(budgetAdmin2, alert, Map.of("comment", "後から確認"));

        expectAlreadyAcknowledged(response, before);
        assertThat(ackRow(alert)).isEqualTo(before);
        assertThat(settledAuditCount(alert)).as("監査ログを足さない").isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9b: overwrite=false の明示も上書き指定なしと同じく 409 で不変")
    void AC9b_overwrite_falseは409で不変() throws Exception {
        Long alert = alert(org);
        expect(acknowledge(budgetAdmin1, alert, null), 200, null);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
        Map<String, Object> before = ackRow(alert);

        expectAlreadyAcknowledged(acknowledge(budgetAdmin2, alert, Map.of("overwrite", false)), before);
        assertThat(ackRow(alert)).isEqualTo(before);
        assertThat(settledAuditCount(alert)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-9c: 上書き指定（overwrite=true）付きの再確認は 200。確認者・時刻が新しい値になり監査ログ +1")
    void AC9c_上書き指定付き再確認は200で上書きし監査が増える() throws Exception {
        Long alert = alert(org);
        expect(acknowledge(budgetAdmin1, alert, null), 200, null);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
        // 時刻の更新を秒精度でも観測できるよう間を空ける
        TimeUnit.MILLISECONDS.sleep(1_100);
        Map<String, Object> before = ackRow(alert);

        MockHttpServletResponse response = acknowledge(budgetAdmin2, alert, Map.of("overwrite", true, "comment", "上書き"));

        expect(response, 200, null);
        Map<String, Object> after = ackRow(alert);
        assertThat(((Number) after.get("acknowledged_by")).longValue()).isEqualTo(budgetAdmin2);
        assertThat(toLocal(after.get("acknowledged_at"))).isAfter(toLocal(before.get("acknowledged_at")));
        assertThat(awaitAuditCount(alert, 2)).as("上書きのたびに監査ログを 1 件記録する").isEqualTo(2);
    }

    @Test
    @DisplayName("AC-9b: 確認者がこの組織のメンバーでない（組織外 SYSTEM_ADMIN）場合、409 の氏名は null（時刻・ID は返る）")
    void AC9b_組織外の確認者の氏名はnull() throws Exception {
        Long alert = alert(org);
        expect(acknowledge(outsideSystemAdmin, alert, null), 200, null);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
        Map<String, Object> before = ackRow(alert);

        MockHttpServletResponse response = acknowledge(budgetAdmin1, alert, null);

        expectAlreadyAcknowledged(response, before, null);
        assertThat(ackRow(alert)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-10: 他組織の alertId は 404 SHIFT_BUDGET_029 で不変（回帰）")
    void AC10_他組織の警告は404() throws Exception {
        Long foreign = alert(otherOrg);
        Map<String, Object> before = ackRow(foreign);

        expect(acknowledge(budgetAdmin1, foreign, null), 404, "SHIFT_BUDGET_029");

        assertThat(ackRow(foreign)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-10: BUDGET_ADMIN を持たない組織メンバーは 403 SHIFT_BUDGET_027 で不変（回帰）")
    void AC10_BUDGET_ADMINなしは403() throws Exception {
        Long alert = alert(org);
        Map<String, Object> before = ackRow(alert);

        expect(acknowledge(plainMember, alert, null), 403, "SHIFT_BUDGET_027");

        assertThat(ackRow(alert)).isEqualTo(before);
        assertThat(settledAuditCount(alert)).isZero();
    }

    @Test
    @DisplayName("AC-11: 並行二重 acknowledge（上書き指定なし）は 200 が 1 つ・409 が 1 つ。記録は 200 側の値で監査ログ 1 件")
    void AC11_並行二重確認は200と409が各1つ() throws Exception {
        Long alert = alert(org);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Map<Long, MockHttpServletResponse> responses = new LinkedHashMap<>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Map<Long, Future<MockHttpServletResponse>> futures = new LinkedHashMap<>();
            for (Long actor : List.of(budgetAdmin1, budgetAdmin2)) {
                Callable<MockHttpServletResponse> task = () -> {
                    ready.countDown();
                    assertThat(go.await(30, TimeUnit.SECONDS)).isTrue();
                    return acknowledge(actor, alert, null);
                };
                futures.put(actor, executor.submit(task));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var entry : futures.entrySet()) {
                responses.put(entry.getKey(), entry.getValue().get(60, TimeUnit.SECONDS));
            }
        }

        assertThat(responses.values().stream().map(MockHttpServletResponse::getStatus).toList())
                .as("同時送信の一方だけが確認を記録する").containsExactlyInAnyOrder(200, 409);
        Long winner = responses.entrySet().stream().filter(e -> e.getValue().getStatus() == 200)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        MockHttpServletResponse loser = responses.values().stream().filter(r -> r.getStatus() == 409)
                .findFirst().orElseThrow();
        Map<String, Object> stored = ackRow(alert);
        assertThat(((Number) stored.get("acknowledged_by")).longValue()).as("記録は 200 側の値").isEqualTo(winner);
        expectAlreadyAcknowledged(loser, stored);
        assertThat(awaitAuditCount(alert, 1)).isEqualTo(1);
        assertThat(settledAuditCount(alert)).as("409 側は監査ログを書かない").isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════

    /** 409・新エラーコード（SHIFT_BUDGET_032）・{@code error.details} に既存の確認者・氏名・確認時刻を含むこと。 */
    private void expectAlreadyAcknowledged(MockHttpServletResponse response, Map<String, Object> stored,
                                           String expectedByName) throws Exception {
        expect(response, 409, "SHIFT_BUDGET_032");
        JsonNode details = json(response).at("/error/details");
        assertThat(details.isObject()).as("error.details がオブジェクト: " + response.getContentAsString()).isTrue();
        assertThat(details.path("acknowledgedBy").asLong())
                .isEqualTo(((Number) stored.get("acknowledged_by")).longValue());
        assertThat(LocalDateTime.parse(details.path("acknowledgedAt").asText().substring(0, 19)))
                .isEqualTo(toLocal(stored.get("acknowledged_at")).truncatedTo(ChronoUnit.SECONDS));
        if (expectedByName == null) {
            assertThat(details.path("acknowledgedByName").isNull() || details.path("acknowledgedByName").isMissingNode())
                    .as("氏名は引かない（null）: " + response.getContentAsString()).isTrue();
        } else {
            assertThat(details.path("acknowledgedByName").asText()).isEqualTo(expectedByName);
        }
    }

    /** 組織メンバー（budgetAdmin）が確認者のときの 409。氏名は「姓 名」で返る。 */
    private void expectAlreadyAcknowledged(MockHttpServletResponse response, Map<String, Object> stored)
            throws Exception {
        String name = inTx(() -> {
            Long by = ((Number) stored.get("acknowledged_by")).longValue();
            UserEntity u = users.findById(by).orElseThrow();
            return u.getLastName() + " " + u.getFirstName();
        });
        expectAlreadyAcknowledged(response, stored, name);
    }

    private MockHttpServletResponse acknowledge(Long actor, Long alert, Map<String, Object> body) throws Exception {
        var request = post(ACK, alert)
                .header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")))
                .header("X-Organization-Id", org.toString())
                .header("Accept-Language", "ja");
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private void expect(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        if (code != null) {
            assertThat(json(response).at("/error/code").asText()).isEqualTo(code);
        }
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString());
    }

    private Map<String, Object> ackRow(Long alert) {
        return inTx(() -> {
            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT acknowledged_by, acknowledged_at, updated_at FROM budget_threshold_alerts "
                                    + "WHERE id = :id").setParameter("id", alert).getSingleResult();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("acknowledged_by", row[0]);
            map.put("acknowledged_at", row[1]);
            map.put("updated_at", row[2]);
            return map;
        });
    }

    private static LocalDateTime toLocal(Object value) {
        if (value instanceof LocalDateTime local) {
            return local;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        throw new IllegalStateException("想定外の時刻型: " + value);
    }

    private long auditCount(Long alert) {
        return inTx(() -> ((Number) em.createNativeQuery("SELECT COUNT(*) FROM audit_logs WHERE event_type = :event "
                        + "AND organization_id = :org AND JSON_EXTRACT(metadata, '$.alert_id') = :alert")
                .setParameter("event", EVENT).setParameter("org", org).setParameter("alert", alert)
                .getSingleResult()).longValue());
    }

    /** 非同期の監査ログが期待件数に達するまで待ち、達したら少し置いて余分が無いことも含めて返す。 */
    private long awaitAuditCount(Long alert, long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + AUDIT_WAIT_MS;
        while (auditCount(alert) < expected && System.currentTimeMillis() < deadline) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return settledAuditCount(alert);
    }

    /** 非同期の監査ログが書かれる余地を待ってから件数を返す（「増えない」ことの確認用）。 */
    private long settledAuditCount(Long alert) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(AUDIT_SETTLE_MS);
        return auditCount(alert);
    }

    private Long alert(Long organization) {
        return inTx(() -> {
            Long allocation = allocations.save(ShiftBudgetAllocationEntity.builder()
                    .organizationId(organization).teamId(null).fiscalYearId(9101L).budgetCategoryId(9102L)
                    .periodStart(LocalDate.of(2026, 7, 1)).periodEnd(LocalDate.of(2026, 7, 31))
                    .allocatedAmount(new BigDecimal("300000")).consumedAmount(new BigDecimal("250000"))
                    .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(budgetAdmin1).version(0L)
                    .build()).getId();
            allocationIds.add(allocation);
            return alerts.save(BudgetThresholdAlertEntity.builder().allocationId(allocation).thresholdPercent(80)
                    .triggeredAt(LocalDateTime.now().minusHours(1)).consumedAmountAtTrigger(new BigDecimal("240000"))
                    .notifiedUserIds("[]").build()).getId();
        });
    }

    /** 本番 V11.034 と同じく ADMIN ロールへ BUDGET_ADMIN を既定付与する。不在なら投入し、投入分だけ後始末する。 */
    private void grantBudgetAdminToAdminRole() {
        insertedPermission = em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT 'BUDGET_ADMIN', 'シフト予算管理者', 'ORGANIZATION', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'BUDGET_ADMIN')")
                .executeUpdate() > 0;
        insertedRolePermission = em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p "
                                + "WHERE r.name = 'ADMIN' AND p.name = 'BUDGET_ADMIN' "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "  WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .executeUpdate() > 0;
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp1607-ack-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long organization(String label) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("cmp1607-" + UUID.randomUUID().toString().substring(0, 18)).name(label)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        organizationIds.add(id);
        return id;
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }
}
