package com.mannschaft.app.shift;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.event.ShiftPublishedEvent;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftPublicationBudgetContext;
import com.mannschaft.app.shift.service.ShiftPublicationBudgetContextPort;
import com.mannschaft.app.shift.service.ShiftScheduleService;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261115-1821 G07: 公開 TX の外で固定した予算 context と、親ロック後に読み直した枠の食い違い。
 *
 * <p>Facade が context を取得した後・公開 TX が親を FOR UPDATE する前に枠が変わった状況を、context 取得と
 * tx 本体呼び出しの間に枠を書き換えて再現する（同一テスト TX 内の逐次実行。ロック待機そのものの観測は対象外）。
 * 範囲外・空→非空は 409 COMMON_003 で公開更新 0・公開イベント 0、再操作（Facade 経由で context を取り直す）で
 * 成功する。範囲内の別日・割当変更は読み直した枠で時給を判定する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@RecordApplicationEvents
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261115-1821 公開の予算 context 境界（G07）")
class ShiftPublicationBudgetContextBoundaryIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate DAY1 = LocalDate.of(2026, 7, 15);

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService tokens;
    @Autowired private UserRepository users;
    @Autowired private ShiftScheduleRepository schedules;
    @Autowired private ShiftSlotRepository slots;
    @Autowired private ShiftHourlyRateRepository rates;
    @Autowired private ShiftBudgetAllocationRepository allocations;
    @Autowired private ShiftScheduleService scheduleService;
    @Autowired private ShiftPublicationBudgetContextPort contextPort;
    @Autowired private FeatureFlagRepository flags;
    @Autowired private CacheManager cacheManager;
    @Autowired private ApplicationEvents events;
    @PersistenceContext private EntityManager em;

    private Long teamId;
    private Long adminId;
    private Long ratedWorkerId;
    private Long unratedWorkerId;
    private Long scheduleId;

    @AfterEach
    void フラグのキャッシュを残さない() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    private void fixture() {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        Long orgId = TeamOrgFixtureHelper.insertOrganization(em, "1821境界組織", "o-" + nonce);
        teamId = TeamOrgFixtureHelper.insertTeam(em, "1821境界チーム", "t-" + nonce);
        TeamOrgFixtureHelper.insertTeamOrgMembership(em, teamId, orgId, "ACTIVE",
                LocalDateTime.of(2026, 7, 1, 12, 0));
        adminId = user("admin-" + nonce);
        ratedWorkerId = user("rated-" + nonce);
        unratedWorkerId = user("unrated-" + nonce);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, ratedWorkerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, unratedWorkerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        FeatureFlagTestSupport.enable(flags, cacheManager, "FEATURE_SHIFT_ENABLED");
        rates.save(ShiftHourlyRateEntity.builder().userId(ratedWorkerId).teamId(teamId)
                .hourlyRate(new BigDecimal("1200")).effectiveFrom(DAY1.withDayOfMonth(1)).build());
        allocations.save(ShiftBudgetAllocationEntity.builder().organizationId(orgId).teamId(teamId)
                .fiscalYearId(1821L).budgetCategoryId(1821L).periodStart(DAY1.withDayOfMonth(1))
                .periodEnd(DAY1.withDayOfMonth(DAY1.lengthOfMonth()))
                .allocatedAmount(new BigDecimal("500000")).consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(adminId).version(0L).build());
        scheduleId = schedules.save(ShiftScheduleEntity.builder().teamId(teamId)
                .title("予算context境界").periodType(ShiftPeriodType.WEEKLY)
                .startDate(DAY1).endDate(DAY1.plusDays(6))
                .status(ShiftScheduleStatus.ADJUSTING).createdBy(adminId).build()).getId();
    }

    private ShiftSlotEntity addSlot(LocalDate date, Long userId) {
        return slots.save(ShiftSlotEntity.builder().scheduleId(scheduleId).slotDate(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1)
                .assignedUserIds(ShiftAssignedUserIds.serialize(List.of(userId))).build());
    }

    private void flushClear() {
        em.flush();
        em.clear();
    }

    private void assertNotPublished() {
        em.clear();
        ShiftScheduleEntity unchanged = schedules.findById(scheduleId).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(ShiftScheduleStatus.ADJUSTING);
        assertThat(unchanged.getPublishedAt()).isNull();
        assertThat(events.stream(ShiftPublishedEvent.class)).isEmpty();
    }

    private void assertConflict(Throwable thrown) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_003);
    }

    private void publishViaApiSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/shifts/schedules/{scheduleId}/transition", scheduleId)
                        .param("status", "PUBLISHED")
                        .header("Authorization", "Bearer " + tokens.issueAccessToken(adminId, List.of("MEMBER"))))
                .andExpect(status().isOk());
        em.clear();
        assertThat(schedules.findById(scheduleId).orElseThrow().getStatus()).isEqualTo(ShiftScheduleStatus.PUBLISHED);
        assertThat(events.stream(ShiftPublishedEvent.class)).hasSize(1);
    }

    @Test
    void G07_context取得後に範囲外の枠が増えたら409で公開せず_再操作で成功する() throws Exception {
        fixture();
        addSlot(DAY1, ratedWorkerId);
        flushClear();
        ShiftPublicationBudgetContext context = contextPort.load(teamId, DAY1, DAY1);

        addSlot(DAY1.plusDays(1), ratedWorkerId);
        flushClear();

        assertConflict(catchThrowable(() ->
                scheduleService.transitionStatus(scheduleId, "PUBLISHED", adminId, context)));
        assertNotPublished();

        publishViaApiSucceeds();
    }

    @Test
    void G07_事前に枠が無いcontextで枠が現れたら409で公開せず_再操作で成功する() throws Exception {
        fixture();
        flushClear();
        ShiftPublicationBudgetContext context = ShiftPublicationBudgetContext.noSlots();

        addSlot(DAY1, ratedWorkerId);
        flushClear();

        assertConflict(catchThrowable(() ->
                scheduleService.transitionStatus(scheduleId, "PUBLISHED", adminId, context)));
        assertNotPublished();

        publishViaApiSucceeds();
    }

    @Test
    void G07_範囲内の別日と割当変更は読み直した枠で時給を判定する() throws Exception {
        fixture();
        addSlot(DAY1, ratedWorkerId);
        ShiftSlotEntity later = addSlot(DAY1.plusDays(2), ratedWorkerId);
        flushClear();
        ShiftPublicationBudgetContext context = contextPort.load(teamId, DAY1, DAY1.plusDays(2));

        // context 取得後、範囲内で別日の枠を増やし、既存枠の割当を時給なしの人へ替える
        addSlot(DAY1.plusDays(1), ratedWorkerId);
        ShiftSlotEntity reloaded = slots.findById(later.getId()).orElseThrow();
        reloaded.applyUpdate(null, null, null, null, null,
                ShiftAssignedUserIds.serialize(List.of(unratedWorkerId)), null, null);
        slots.save(reloaded);
        flushClear();

        Throwable thrown = catchThrowable(() ->
                scheduleService.transitionStatus(scheduleId, "PUBLISHED", adminId, context));

        assertThat(thrown).isInstanceOf(ShiftHourlyRateMissingException.class);
        assertThat(((ShiftHourlyRateMissingException) thrown).getMissingHourlyRates())
                .containsExactly(new ShiftHourlyRateMissingException.Missing(unratedWorkerId, DAY1.plusDays(2)));
        assertNotPublished();
    }

    @Test
    void G01対照_時給なしの割当者はHTTPでも400で対象者日付を返す() throws Exception {
        fixture();
        addSlot(DAY1, unratedWorkerId);
        flushClear();

        mockMvc.perform(post("/api/v1/shifts/schedules/{scheduleId}/transition", scheduleId)
                        .param("status", "PUBLISHED")
                        .header("Authorization", "Bearer " + tokens.issueAccessToken(adminId, List.of("MEMBER"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SHIFT_047"))
                .andExpect(jsonPath("$.error.details.missingHourlyRates[0].userId").value(unratedWorkerId));
        assertNotPublished();
    }

    private Long user(String label) {
        return users.save(UserEntity.builder().email("publication-ctx-" + label + "@example.com")
                .lastName("試練").firstName(label).displayName(label).status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }
}
