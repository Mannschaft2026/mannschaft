package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

/** CMP-260910-1559: 実DB・実Securityによる記録時給別内訳と組織権限の契約。 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ShiftBudgetSummaryRateBreakdownIT extends AbstractMySqlIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private FeatureFlagRepository featureFlags;
    @Autowired private CacheManager caches;
    @PersistenceContext private EntityManager em;

    private Long organizationId;
    private Long allocationId;
    private Long userId;
    private long nextSlot;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlags, caches, "FEATURE_SHIFT_ENABLED");
        organizationId = insertOrganization();
        userId = 155900000L + organizationId;
        MembershipTestHelper.insertActiveUser(em, userId);
        ShiftBudgetAllocationEntity allocation = ShiftBudgetAllocationEntity.builder()
                .organizationId(organizationId).fiscalYearId(1L).budgetCategoryId(1L)
                .periodStart(LocalDate.of(2026, 6, 1)).periodEnd(LocalDate.of(2026, 6, 30))
                .allocatedAmount(new BigDecimal("50000")).consumedAmount(new BigDecimal("9600"))
                .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(userId).build();
        em.persist(allocation);
        em.flush();
        allocationId = allocation.getId();
        nextSlot = allocationId * 10000;
    }

    @Test
    void 記録された0円と正数時給の内訳を合計を変えず返す() throws Exception {
        grant(false, true);
        record("0", "8", ShiftBudgetConsumptionStatus.PLANNED, false);
        record("1200", "8", ShiftBudgetConsumptionStatus.CONFIRMED, false);
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user[0].amount").value(9600))
                .andExpect(jsonPath("$.data.by_user[0].hours").value(16))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown.length()").value(2))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[0].hourly_rate_snapshot").value(0))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[0].hours").value(8))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[1].hourly_rate_snapshot").value(1200))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[1].amount").value(9600));
    }

    @Test
    void 現在時給を改定しても履歴を再計算せず同額をまとめる() throws Exception {
        grant(false, true);
        record("1300", "2", ShiftBudgetConsumptionStatus.PLANNED, false);
        record("1200.00", "1", ShiftBudgetConsumptionStatus.PLANNED, false);
        record("1200.0", "2", ShiftBudgetConsumptionStatus.CONFIRMED, false);
        em.persist(ShiftHourlyRateEntity.builder().teamId(1L).userId(userId)
                .hourlyRate(new BigDecimal("2000")).effectiveFrom(LocalDate.of(2026, 1, 1)).build());
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user[0].amount").value(6200))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown.length()").value(2))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[0].hourly_rate_snapshot").value(1200))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[0].hours").value(3))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[1].hourly_rate_snapshot").value(1300));
    }

    @Test
    void 取消と削除済み消化を内訳から除外する() throws Exception {
        grant(false, true);
        record("1200", "1", ShiftBudgetConsumptionStatus.PLANNED, false);
        record("1300", "1", ShiftBudgetConsumptionStatus.CANCELLED, false);
        record("1400", "1", ShiftBudgetConsumptionStatus.CONFIRMED, true);
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user[0].hours").value(1))
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown.length()").value(1));
    }

    @Test
    void 消化0件なら空配列() throws Exception {
        grant(false, true);
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user").isEmpty());
    }

    @Test
    void SYS単独ではサマリを取得できない() throws Exception {
        MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
        summary(userId, organizationId).andExpect(status().isForbidden());
    }

    @Test
    void SYSでも組織所属だけではサマリを取得できない() throws Exception {
        MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        summary(userId, organizationId).andExpect(status().isForbidden());
    }

    @Test
    void SYSの実組織閲覧権限では個人内訳を返さない() throws Exception {
        grant(true, false);
        record("1200", "1", ShiftBudgetConsumptionStatus.PLANNED, false);
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flags[0]").value("BY_USER_HIDDEN"))
                .andExpect(jsonPath("$.data.by_user").doesNotExist());
    }

    @Test
    void SYSでも実組織の予算担当なら内訳を取得できる() throws Exception {
        grant(true, true);
        record("1200", "1", ShiftBudgetConsumptionStatus.PLANNED, false);
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user[0].rate_breakdown[0].hourly_rate_snapshot").value(1200));
    }

    @Test
    void 通常の閲覧者にも個人時給を返さない() throws Exception {
        grant(false, false);
        summary(userId, organizationId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.by_user").doesNotExist())
                .andExpect(jsonPath("$.data.flags[0]").value("BY_USER_HIDDEN"));
    }

    @Test
    void 別組織IDでは取得できない() throws Exception {
        grant(false, true);
        summary(userId, organizationId + 100000).andExpect(status().isForbidden());
    }

    @Test
    void 別組織の予算担当でも他組織の割当は取得できない() throws Exception {
        Long otherOrganizationId = insertOrganization();
        grant(otherOrganizationId, false, true);
        summary(userId, otherOrganizationId).andExpect(status().isNotFound());
    }

    @Test
    void 未認証では取得できない() throws Exception {
        em.flush();
        mvc.perform(get(path()).header("X-Organization-Id", organizationId))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 人数と記録数を増やしても取得SQLが増えない() throws Exception {
        grant(false, true);
        record("1200", "1", ShiftBudgetConsumptionStatus.PLANNED, false);
        summary(userId, organizationId).andExpect(status().isOk());
        int smallCount;
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            summary(userId, organizationId).andExpect(status().isOk());
            smallCount = capture.totalCount();
            assertThat(smallCount).isPositive();
        }
        for (int person = 0; person < 100; person++) {
            for (int day = 0; day < 31; day++) {
                recordForUser(userId + person + 1, day % 2 == 0 ? "1200" : "1300", "4",
                        ShiftBudgetConsumptionStatus.PLANNED, false);
            }
        }
        em.flush();
        em.clear();
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            summary(userId, organizationId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.by_user.length()").value(101))
                    .andExpect(jsonPath("$.data.by_user[1].rate_breakdown.length()").value(2));
            assertThat(capture.totalCount()).isEqualTo(smallCount);
        }
    }

    private ResultActions summary(Long actorId, Long orgId) throws Exception {
        em.flush();
        em.clear();
        return mvc.perform(get(path()).header("X-Organization-Id", orgId)
                .with(authentication(new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of()))));
    }

    private String path() {
        return "/api/v1/shift-budget/allocations/" + allocationId + "/consumption-summary";
    }

    private void record(String rate, String hours, ShiftBudgetConsumptionStatus status, boolean deleted) {
        recordForUser(userId, rate, hours, status, deleted);
    }

    private void recordForUser(Long workerId, String rate, String hours,
                               ShiftBudgetConsumptionStatus status, boolean deleted) {
        BigDecimal r = new BigDecimal(rate);
        BigDecimal h = new BigDecimal(hours);
        em.persist(ShiftBudgetConsumptionEntity.builder().allocationId(allocationId).shiftId(1L)
                .slotId(++nextSlot).userId(workerId).hourlyRateSnapshot(r).hours(h).amount(r.multiply(h))
                .currency("JPY").status(status).recordedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .deletedAt(deleted ? LocalDateTime.of(2026, 6, 2, 9, 0) : null).build());
    }

    private void grant(boolean systemAdmin, boolean budgetAdmin) {
        grant(organizationId, systemAdmin, budgetAdmin);
    }

    private void grant(Long scopeId, boolean systemAdmin, boolean budgetAdmin) {
        if (systemAdmin) MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, scopeId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, scopeId);
        PermissionGroupEntity group = PermissionGroupEntity.builder().organizationId(scopeId)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).name("1559担当").createdBy(userId).build();
        em.persist(group);
        em.flush();
        for (String permission : budgetAdmin ? List.of("BUDGET_VIEW", "BUDGET_ADMIN") : List.of("BUDGET_VIEW")) {
            em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                            + "SELECT :p, :p, 'ORGANIZATION', NOW(), NOW() FROM DUAL "
                            + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :p)")
                    .setParameter("p", permission).executeUpdate();
            Long permissionId = ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :p")
                    .setParameter("p", permission).getSingleResult()).longValue();
            em.persist(PermissionGroupPermissionEntity.builder().groupId(group.getId()).permissionId(permissionId).build());
        }
        em.persist(UserPermissionGroupEntity.builder().userId(userId).groupId(group.getId()).assignedBy(userId).build());
    }

    private Long insertOrganization() {
        // 既存OrgFanoutRecipientSourceRedITと同じEntity金型で必須値と既定値を保存する。
        OrganizationEntity organization = OrganizationEntity.builder()
                .name("1559契約")
                .slug("s-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .lifecycleStatus(OrganizationEntity.LifecycleStatus.ACTIVE)
                .build();
        em.persist(organization);
        em.flush();
        return organization.getId();
    }
}
