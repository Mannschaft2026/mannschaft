package com.mannschaft.app.shift;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261115-1821 G01: 勤務日に有効な時給のない割当者を公開前に検出する初期RED。
 *
 * <p>既存の公開POSTを実JWT・Securityフィルタ・MySQLで呼ぶ。是正前の200が400期待に失敗する。
 * 本試験は公開前の拒否だけが対象なのでテストTXをrollbackし、是正前の公開イベントもcommitしない。
 * AFTER_COMMITの実計上・配送保証はこのテストの合格根拠に含めない。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261115-1821 公開前の勤務日時給契約（初期RED）")
class ShiftPublicationHourlyRateContractIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate WORK_DATE = LocalDate.of(2026, 7, 15);

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftHourlyRateRepository rates;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftBudgetFeatureService budgetFeature;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    @AfterEach
    void フラグのキャッシュを残さない() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    @Test
    void G01_予算連動の割当者に勤務日時給がなければ400と対象者日付を返す() throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Long orgId = TeamOrgFixtureHelper.insertOrganization(em, "1821時給組織", "o-" + nonce);
        Long teamId = TeamOrgFixtureHelper.insertTeam(em, "1821時給チーム", "t-" + nonce);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamId, orgId, "ACTIVE",
                LocalDateTime.of(2026, 7, 1, 12, 0));
        Long adminId = user("admin-" + nonce);
        Long workerId = user("worker-" + nonce);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        FeatureFlagTestSupport.enable(flags, cacheManager, "FEATURE_SHIFT_ENABLED");

        Long scheduleId = schedules.save(ShiftScheduleEntity.builder().teamId(teamId)
                .title("勤務日時給の公開ガード").periodType(ShiftPeriodType.WEEKLY)
                .startDate(WORK_DATE).endDate(WORK_DATE.plusDays(6))
                .status(ShiftScheduleStatus.ADJUSTING).createdBy(adminId).build()).getId();
        slots.save(ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(WORK_DATE)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1)
                .assignedUserIds(ShiftAssignedUserIds.serialize(List.of(workerId))).build());
        allocations.save(ShiftBudgetAllocationEntity.builder().organizationId(orgId).teamId(teamId)
                .fiscalYearId(1821L).budgetCategoryId(1821L).periodStart(WORK_DATE.withDayOfMonth(1))
                .periodEnd(WORK_DATE.withDayOfMonth(WORK_DATE.lengthOfMonth()))
                .allocatedAmount(new BigDecimal("500000")).consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(adminId).version(0L).build());
        em.flush();
        em.clear();

        // fixture不備による403や非予算扱いを、目的のREDと取り違えない。
        assertThat(budgetFeature.isEnabled(orgId)).isTrue();
        assertThat(allocations.findOrganizationIdsWithTeamAllocationContaining(teamId, WORK_DATE))
                .containsExactly(orgId);
        assertThat(rates.findEffectiveRate(workerId, teamId, WORK_DATE)).isEmpty();

        mockMvc.perform(post("/api/v1/shifts/schedules/{scheduleId}/transition", scheduleId)
                        .param("status", "PUBLISHED")
                        .header("Authorization", "Bearer " + tokens.issueAccessToken(adminId, List.of("MEMBER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details.missingHourlyRates.length()").value(1))
                .andExpect(jsonPath("$.error.details.missingHourlyRates[0].userId").value(workerId))
                .andExpect(jsonPath("$.error.details.missingHourlyRates[0].slotDate").value(WORK_DATE.toString()));

        em.clear();
        ShiftScheduleEntity unchanged = schedules.findById(scheduleId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ShiftScheduleStatus.ADJUSTING);
        assertThat(unchanged.getPublishedAt()).isNull();
        assertThat(((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM shift_budget_consumptions WHERE shift_id = :id")
                .setParameter("id", scheduleId).getSingleResult()).longValue()).isZero();
    }

    private Long user(String label) {
        return users.save(UserEntity.builder().email("publication-rate-" + label + "@example.com")
                .lastName("試練").firstName(label).displayName(label).status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }
}
