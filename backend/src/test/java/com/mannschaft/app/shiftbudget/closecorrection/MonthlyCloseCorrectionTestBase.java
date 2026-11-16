package com.mannschaft.app.shiftbudget.closecorrection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.service.MonthlyShiftBudgetCloseService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-260909-2152 月次締め後訂正の試練（実DB・実HTTP）共通フィクスチャ。
 *
 * <h2>起動構成</h2>
 * <p>{@link AbstractMySqlIntegrationTest}（Testcontainers MySQL・MOCK）に {@code @AutoConfigureMockMvc} を足し、
 * 実 Controller・実 Security フィルタ・実 DB を通す。途中失敗試練（試練06）のために
 * {@link BudgetTransactionRepository} を spy にする。spy は stub しない限り実体へ委譲する。
 * 派生クラスはこの構成を変えないこと（Spring TestContext を1つに保つ）。</p>
 *
 * <h2>クラスに {@code @Transactional} を付けない理由</h2>
 * <p>月次締め {@link MonthlyShiftBudgetCloseService#close} は {@code Propagation.NEVER} であり、
 * 訂正は「各ドメインの TX が実際にコミットされること」を観測する必要がある。フィクスチャは
 * {@link TransactionTemplate} で明示コミットし、試験ごとに新しい組織・チーム・年度を作って分離する。</p>
 *
 * <h2>契約（試練が固定する API）</h2>
 * <ul>
 *   <li>{@code POST /api/v1/shift-budget/allocations/{id}/monthly-closes/{yyyy-MM}/corrections} → 201,
 *       {@code data} は訂正行（id, revision, kind, line_kind, consumption_id, added_line_id, user_id,
 *       before_/after_ hours・hourly_rate・amount・line_status, exact_delta, posted_delta, content, reason,
 *       actor_user_id, created_at, budget_transaction_id, reverses_correction_id）</li>
 *   <li>{@code POST .../corrections/{correctionId}/reversals} → 201、data は REVERSAL 訂正行</li>
 *   <li>{@code GET .../corrections?page&size} → 200、data は {@code close}（original_amount,
 *       original_line_count, closed_at, close_actor_kind, close_actor_user_id, revision, posted_total,
 *       added_count, added_amount, correction_delta_total）と {@code items}・{@code page}・{@code size}・{@code total}</li>
 *   <li>{@code GET .../uncounted-shifts} → 200、data.items は slot_id, shift_id, user_id, work_date, hours,
 *       hourly_rate（未設定は null）, add_available, unavailable_reason</li>
 *   <li>既存 割当一覧・詳細・更新結果・summary は訂正後の consumed_amount / confirmed_amount と
 *       {@code correction_delta_total} を返す</li>
 * </ul>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
abstract class MonthlyCloseCorrectionTestBase extends AbstractMySqlIntegrationTest {

    protected static final YearMonth JUNE = YearMonth.of(2026, 6);
    protected static final String JUNE_KEY = "2026-06";
    protected static final LocalDate JUNE_1 = LocalDate.of(2026, 6, 1);
    protected static final LocalDate JUNE_END = LocalDate.of(2026, 6, 30);
    protected static final String SOURCE_MONTHLY = MonthlyShiftBudgetCloseService.SOURCE_TYPE_SHIFT_BUDGET_MONTHLY;
    protected static final String SOURCE_CORRECTION = "SHIFT_BUDGET_CORRECTION";

    /** 利用者・スロットの採番（同一 JVM の全派生クラスで共有し、衝突させない）。 */
    private static final AtomicLong USER_SEQ = new AtomicLong(2_152_000_000L);
    private static final AtomicLong SLOT_SEQ = new AtomicLong(2_152_000_000L);

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected FeatureFlagRepository featureFlags;
    @Autowired protected CacheManager caches;
    @Autowired protected MonthlyShiftBudgetCloseService closeService;
    @MockitoSpyBean protected BudgetTransactionRepository budgetTransactionRepository;
    @PersistenceContext protected EntityManager em;

    /** 試験対象の組織・チーム（チームは組織に加盟済み）。 */
    protected Long orgId;
    protected Long teamId;
    /** 組織の BUDGET_VIEW + BUDGET_ADMIN を持つ予算担当者。 */
    protected Long adminId;
    /** 勤務者（組織メンバー）。 */
    protected Long workerId;
    protected Long fiscalYearId;
    protected Long categoryId;

    @BeforeEach
    void setUpCorrectionFixture() {
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        FeatureFlagTestSupport.enable(featureFlags, caches, "FEATURE_SHIFT_ENABLED");
        tx.executeWithoutResult(s -> {
            orgId = insertOrganization();
            teamId = insertTeam();
            insertTeamOrgMembership(teamId, orgId);
            adminId = newUser();
            grantBudget(adminId, orgId, true);
            workerId = newUser();
            MembershipTestHelper.insertMembership(em, workerId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, workerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            fiscalYearId = insertFiscalYear(orgId, adminId);
            categoryId = insertCategory(fiscalYearId);
            em.flush();
        });
    }

    @AfterEach
    void tearDownCorrectionFixture() {
        SecurityContextHolder.clearContext();
        // authentication() 後処理が積んだテスト用の認証が次の試験の未認証リクエストへ漏れないようにする
        org.springframework.security.test.context.TestSecurityContextHolder.clearContext();
        Mockito.reset(budgetTransactionRepository);
    }

    // ───────────────────────── フィクスチャ ─────────────────────────

    /** 6月のチーム割当を作る（キャッシュ consumed / confirmed は 0）。 */
    protected Long allocation(String allocated) {
        return allocation(orgId, teamId, fiscalYearId, categoryId, allocated);
    }

    protected Long allocation(Long org, Long team, Long fy, Long category, String allocated) {
        return tx.execute(s -> {
            ShiftBudgetAllocationEntity a = ShiftBudgetAllocationEntity.builder()
                    .organizationId(org).teamId(team).fiscalYearId(fy).budgetCategoryId(category)
                    .periodStart(JUNE_1).periodEnd(JUNE_END)
                    .allocatedAmount(new BigDecimal(allocated)).consumedAmount(BigDecimal.ZERO)
                    .confirmedAmount(BigDecimal.ZERO).currency("JPY").createdBy(adminId).version(0L).build();
            em.persist(a);
            em.flush();
            return a.getId();
        });
    }

    /** PLANNED 消化を作り、記録時と同じく consumed_amount キャッシュへ（DECIMAL(12,0) へ丸めて）加算する。 */
    protected Long planned(Long allocationId, Long userId, String rate, String hours) {
        return consumption(allocationId, nextSlot(), userId, rate, hours, ShiftBudgetConsumptionStatus.PLANNED);
    }

    protected Long consumption(Long allocationId, Long slotId, Long userId, String rate, String hours,
                               ShiftBudgetConsumptionStatus status) {
        return tx.execute(s -> {
            BigDecimal r = new BigDecimal(rate);
            BigDecimal h = new BigDecimal(hours);
            BigDecimal amount = r.multiply(h).setScale(2, RoundingMode.HALF_UP);
            ShiftBudgetConsumptionEntity c = ShiftBudgetConsumptionEntity.builder()
                    .allocationId(allocationId).shiftId(1L).slotId(slotId).userId(userId)
                    .hourlyRateSnapshot(r).hours(h).amount(amount).currency("JPY").status(status)
                    .recordedAt(LocalDateTime.of(2026, 6, 1, 9, 0)).build();
            em.persist(c);
            em.flush();
            if (status == ShiftBudgetConsumptionStatus.PLANNED) {
                em.createNativeQuery("UPDATE shift_budget_allocations SET consumed_amount = consumed_amount + :a "
                                + "WHERE id = :id")
                        .setParameter("a", amount).setParameter("id", allocationId).executeUpdate();
            }
            return c.getId();
        });
    }

    protected static long nextSlot() {
        return SLOT_SEQ.incrementAndGet();
    }

    /** 予算担当者による手動の月次締め（MANUAL）。 */
    protected void closeJune() {
        asUser(adminId, () -> closeService.close(orgId, JUNE));
    }

    /** cron 経路の月次締め（BATCH、SecurityContext なし）。 */
    protected void closeJuneFromBatch() {
        SecurityContextHolder.clearContext();
        closeService.closeFromBatch(orgId, JUNE);
    }

    protected void asUser(Long userId, Runnable action) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        try {
            action.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    protected Long newUser() {
        Long id = USER_SEQ.incrementAndGet();
        if (tx.execute(s -> {
            MembershipTestHelper.insertActiveUser(em, id);
            return Boolean.TRUE;
        }) == null) {
            throw new IllegalStateException("利用者を作れない");
        }
        return id;
    }

    /** 組織所属 + DEPUTY_ADMIN + 権限グループ（BUDGET_VIEW、admin なら BUDGET_ADMIN も）。 */
    protected void grantBudget(Long userId, Long scopeOrgId, boolean budgetAdmin) {
        tx.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, scopeOrgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, scopeOrgId);
            PermissionGroupEntity group = PermissionGroupEntity.builder().organizationId(scopeOrgId)
                    .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).name("2152担当")
                    .createdBy(userId).build();
            em.persist(group);
            em.flush();
            for (String permission : budgetAdmin ? List.of("BUDGET_VIEW", "BUDGET_ADMIN") : List.of("BUDGET_VIEW")) {
                em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :p, :p, 'ORGANIZATION', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :p)")
                        .setParameter("p", permission).executeUpdate();
                Long permissionId = ((Number) em.createNativeQuery("SELECT id FROM permissions WHERE name = :p")
                        .setParameter("p", permission).getSingleResult()).longValue();
                em.persist(PermissionGroupPermissionEntity.builder().groupId(group.getId())
                        .permissionId(permissionId).build());
            }
            em.persist(UserPermissionGroupEntity.builder().userId(userId).groupId(group.getId())
                    .assignedBy(userId).build());
            em.flush();
        });
    }

    protected void grantSystemAdmin(Long userId) {
        tx.executeWithoutResult(s -> MembershipTestHelper.insertUserRole(em, userId, "SYSTEM_ADMIN", null, null));
    }

    /** 汎用予算 API（取引の削除・取消）を使えるチーム ADMIN。 */
    protected Long teamBudgetAdmin() {
        Long id = newUser();
        tx.executeWithoutResult(s -> {
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, id, "ADMIN", teamId, null);
        });
        return id;
    }

    protected Long insertOrganization() {
        OrganizationEntity organization = OrganizationEntity.builder()
                .name("2152締め後訂正")
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

    protected Long insertTeam() {
        String name = "2152チーム" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, "
                        + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    protected void insertTeamOrgMembership(Long team, Long org) {
        em.createNativeQuery("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                        + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                .setParameter("tid", team).setParameter("oid", org).executeUpdate();
        em.flush();
    }

    protected Long insertFiscalYear(Long org, Long createdBy) {
        String name = "2152年度" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO budget_fiscal_years (scope_type, scope_id, name, start_date, end_date, "
                        + "status, created_by, version, created_at, updated_at) VALUES ('ORGANIZATION', :org, :name, "
                        + "'2026-04-01', '2027-03-31', 'OPEN', :uid, 0, NOW(), NOW())")
                .setParameter("org", org).setParameter("name", name).setParameter("uid", createdBy).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_fiscal_years WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    protected Long insertCategory(Long fy) {
        String name = "2152費目" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO budget_categories (fiscal_year_id, name, category_type, sort_order, "
                        + "version, created_at, updated_at) VALUES (:fy, :name, 'EXPENSE', 0, 0, NOW(), NOW())")
                .setParameter("fy", fy).setParameter("name", name).executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_categories WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    /** 公開済みシフトに1スロットを作り、勤務者を割り当てる。戻り値は {scheduleId, slotId}。 */
    protected long[] publishedSlot(Long team, LocalDate date, LocalTime start, LocalTime end, Long... userIds) {
        return tx.execute(s -> {
            ShiftScheduleEntity schedule = ShiftScheduleEntity.builder().teamId(team).title("2152シフト")
                    .periodType(ShiftPeriodType.WEEKLY).startDate(date).endDate(date.plusDays(6))
                    .status(ShiftScheduleStatus.PUBLISHED).publishedAt(LocalDateTime.of(2026, 5, 20, 10, 0))
                    .publishedBy(adminId).createdBy(adminId).build();
            em.persist(schedule);
            em.flush();
            StringBuilder assigned = new StringBuilder("[");
            for (int i = 0; i < userIds.length; i++) {
                assigned.append(i == 0 ? "" : ",").append(userIds[i]);
            }
            assigned.append(']');
            ShiftSlotEntity slot = ShiftSlotEntity.builder().scheduleId(schedule.getId()).slotDate(date)
                    .startTime(start).endTime(end).requiredCount(userIds.length)
                    .assignedUserIds(assigned.toString()).build();
            em.persist(slot);
            em.flush();
            return new long[] {schedule.getId(), slot.getId()};
        });
    }

    protected void hourlyRate(Long userId, Long team, String rate, LocalDate from) {
        tx.executeWithoutResult(s -> em.persist(ShiftHourlyRateEntity.builder().userId(userId).teamId(team)
                .hourlyRate(new BigDecimal(rate)).effectiveFrom(from).build()));
    }

    // ───────────────────────── HTTP ─────────────────────────

    protected String base(Long allocationId, String yearMonth) {
        return "/api/v1/shift-budget/allocations/" + allocationId + "/monthly-closes/" + yearMonth;
    }

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, Long actorId, Long headerOrg) {
        return builder.header("X-Organization-Id", headerOrg)
                .with(authentication(new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of())));
    }

    protected ResultActions postCorrection(Long actorId, Long allocationId, Map<String, Object> body) throws Exception {
        return postCorrection(actorId, orgId, allocationId, JUNE_KEY, body);
    }

    protected ResultActions postCorrection(Long actorId, Long headerOrg, Long allocationId, String yearMonth,
                                           Map<String, Object> body) throws Exception {
        return mvc.perform(as(post(base(allocationId, yearMonth) + "/corrections"), actorId, headerOrg)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions postReversal(Long actorId, Long allocationId, String correctionId, long expectedRevision,
                                         String reason) throws Exception {
        return postReversal(actorId, orgId, allocationId, JUNE_KEY, correctionId,
                reversal(expectedRevision, reason));
    }

    protected ResultActions postReversal(Long actorId, Long headerOrg, Long allocationId, String yearMonth,
                                         String correctionId, Map<String, Object> body) throws Exception {
        return mvc.perform(as(post(base(allocationId, yearMonth) + "/corrections/" + correctionId + "/reversals"),
                actorId, headerOrg).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions history(Long actorId, Long allocationId) throws Exception {
        return mvc.perform(as(get(base(allocationId, JUNE_KEY) + "/corrections"), actorId, orgId));
    }

    protected ResultActions uncounted(Long actorId, Long allocationId) throws Exception {
        return mvc.perform(as(get(base(allocationId, JUNE_KEY) + "/uncounted-shifts"), actorId, orgId));
    }

    protected ResultActions detail(Long actorId, Long allocationId) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/allocations/" + allocationId), actorId, orgId));
    }

    protected ResultActions list(Long actorId) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/allocations").param("page", "0").param("size", "100"),
                actorId, orgId));
    }

    protected ResultActions summary(Long actorId, Long allocationId) throws Exception {
        return mvc.perform(as(get("/api/v1/shift-budget/allocations/" + allocationId + "/consumption-summary"),
                actorId, orgId));
    }

    protected ResultActions update(Long actorId, Long allocationId, String allocated) throws Exception {
        Long version = jdbc.queryForObject("SELECT version FROM shift_budget_allocations WHERE id = ?",
                Long.class, allocationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("allocated_amount", new BigDecimal(allocated));
        body.put("note", "2152更新");
        body.put("version", version);
        return mvc.perform(as(put("/api/v1/shift-budget/allocations/" + allocationId), actorId, orgId)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    protected ResultActions deleteAllocation(Long actorId, Long allocationId) throws Exception {
        return mvc.perform(as(delete("/api/v1/shift-budget/allocations/" + allocationId), actorId, orgId));
    }

    protected Map<String, Object> modify(Long consumptionId, String hours, String rate, long expectedRevision,
                                         String reason) {
        Map<String, Object> body = command("MODIFY", expectedRevision, reason);
        body.put("line_kind", "ORIGINAL");
        body.put("consumption_id", consumptionId);
        body.put("hours", new BigDecimal(hours));
        body.put("hourly_rate", new BigDecimal(rate));
        body.put("content", "2152 勤務時間・時給の訂正");
        return body;
    }

    protected Map<String, Object> modifyAdded(String addedLineId, String hours, String rate, long expectedRevision,
                                              String reason) {
        Map<String, Object> body = command("MODIFY", expectedRevision, reason);
        body.put("line_kind", "ADDED");
        body.put("added_line_id", addedLineId);
        body.put("hours", new BigDecimal(hours));
        body.put("hourly_rate", new BigDecimal(rate));
        body.put("content", "2152 追加明細の訂正");
        return body;
    }

    protected Map<String, Object> cancel(Long consumptionId, long expectedRevision, String reason) {
        Map<String, Object> body = command("CANCEL", expectedRevision, reason);
        body.put("line_kind", "ORIGINAL");
        body.put("consumption_id", consumptionId);
        return body;
    }

    protected Map<String, Object> cancelAdded(String addedLineId, long expectedRevision, String reason) {
        Map<String, Object> body = command("CANCEL", expectedRevision, reason);
        body.put("line_kind", "ADDED");
        body.put("added_line_id", addedLineId);
        return body;
    }

    /** 締め後追加（ADD）。slotId / shiftId は null 可。rate は文字列のまま送る（null 可で 400 試験に使う）。 */
    protected Map<String, Object> add(Long userId, LocalDate workDate, Long slotId, Long shiftId, String hours,
                                      String rate, long expectedRevision, String reason) {
        Map<String, Object> body = command("ADD", expectedRevision, reason);
        body.put("user_id", userId);
        body.put("work_date", workDate.toString());
        body.put("slot_id", slotId);
        body.put("shift_id", shiftId);
        body.put("hours", hours == null ? null : new BigDecimal(hours));
        body.put("hourly_rate", rate == null ? null : new BigDecimal(rate));
        body.put("content", "2152 締め後に判明した勤務の追加");
        return body;
    }

    protected Map<String, Object> reversal(long expectedRevision, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", UUID.randomUUID().toString());
        body.put("expected_revision", expectedRevision);
        body.put("reason", reason);
        return body;
    }

    private Map<String, Object> command(String kind, long expectedRevision, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command_id", UUID.randomUUID().toString());
        body.put("expected_revision", expectedRevision);
        body.put("kind", kind);
        body.put("reason", reason);
        return body;
    }

    protected JsonNode data(ResultActions result) throws Exception {
        MvcResult r = result.andReturn();
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
    }

    // ───────────────────────── DB 観測 ─────────────────────────

    protected Long consumptionIdOf(Long allocationId, String rate) {
        return jdbc.queryForObject("SELECT MIN(id) FROM shift_budget_consumptions WHERE allocation_id = ? "
                + "AND hourly_rate_snapshot = ? AND deleted_at IS NULL", Long.class, allocationId, new BigDecimal(rate));
    }

    /** 消化行の全列スナップショット（原本不変の検査用）。 */
    protected List<Map<String, Object>> consumptionRows(Long allocationId) {
        return jdbc.queryForList("SELECT id, slot_id, user_id, status, hours, hourly_rate_snapshot, amount, deleted_at "
                + "FROM shift_budget_consumptions WHERE allocation_id = ? ORDER BY id", allocationId);
    }

    protected Map<String, Object> allocationCache(Long allocationId) {
        return jdbc.queryForMap("SELECT consumed_amount, confirmed_amount FROM shift_budget_allocations WHERE id = ?",
                allocationId);
    }

    protected List<Map<String, Object>> journals(Long allocationId, String sourceType) {
        return jdbc.queryForList("SELECT id, amount, transaction_type, transaction_date, fiscal_year_id, category_id, "
                        + "scope_type, scope_id, reversal_of_id, deleted_at FROM budget_transactions "
                        + "WHERE source_type = ? AND source_id = ? ORDER BY id", sourceType, allocationId);
    }

    protected Long originalJournalId(Long allocationId) {
        return jdbc.queryForObject("SELECT id FROM budget_transactions WHERE source_type = ? AND source_id = ? "
                + "AND deleted_at IS NULL", Long.class, SOURCE_MONTHLY, allocationId);
    }

    protected int budgetTransactionCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM budget_transactions WHERE fiscal_year_id = ?",
                Integer.class, fiscalYearId);
        return n == null ? 0 : n;
    }

    protected static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** JSON の数値欄が存在し、値が期待額と等しいこと（欠落を 0 と取り違えない）。 */
    protected static void assertNumber(JsonNode node, String field, String expected) {
        org.assertj.core.api.Assertions.assertThat(node.path(field).isNumber())
                .as("応答に数値欄 %s が無い: %s", field, node)
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(node.path(field).decimalValue())
                .as("%s", field)
                .isEqualByComparingTo(expected);
    }

    /** 割当詳細の訂正後 consumed / confirmed / correction_delta_total。 */
    protected void assertAllocationAmounts(Long actorId, Long allocationId, String consumed, String confirmed,
                                           String delta) throws Exception {
        ResultActions r = detail(actorId, allocationId)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        JsonNode d = data(r);
        assertNumber(d, "consumed_amount", consumed);
        assertNumber(d, "confirmed_amount", confirmed);
        assertNumber(d, "correction_delta_total", delta);
    }
}
