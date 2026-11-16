package com.mannschaft.app.shiftbudget;

import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.budget.entity.BudgetConfigEntity;
import com.mannschaft.app.budget.repository.BudgetConfigRepository;
import com.mannschaft.app.budget.repository.BudgetTransactionRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.PermissionGroupEntity;
import com.mannschaft.app.role.entity.PermissionGroupPermissionEntity;
import com.mannschaft.app.role.entity.UserPermissionGroupEntity;
import com.mannschaft.app.shiftbudget.entity.BudgetThresholdAlertEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.BudgetThresholdAlertRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetNotificationResendService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.workflow.ApprovalType;
import com.mannschaft.app.workflow.ApproverType;
import com.mannschaft.app.workflow.entity.WorkflowTemplateEntity;
import com.mannschaft.app.workflow.entity.WorkflowTemplateStepEntity;
import com.mannschaft.app.workflow.repository.WorkflowTemplateRepository;
import com.mannschaft.app.workflow.repository.WorkflowTemplateStepRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260909-2152 試練（再試行・ワークフロー・並行）の共通実DB土台。
 *
 * <h2>なぜクラスに {@code @Transactional} を付けないか</h2>
 * <p>本土台を継承する試練はいずれも「2 スレッドが別トランザクションで同じ行を奪い合う」ことを
 * 実 MySQL 上で起こして検査する。テストメソッドを外側トランザクションで包むと、
 * 投入したフィクスチャが他スレッドから見えず競合そのものが成立しない。
 * そこで投入は {@link TransactionTemplate} / Spring Data の既定トランザクションで<b>実コミット</b>し、
 * 検証の読み取りは {@link JdbcTemplate} で DB を直接引く（金型: {@code MonthlyShiftBudgetCloseTransactionIT}）。
 * データは組織ごとに採番して分離し、他テストの行と混ざらないようにする。</p>
 *
 * <h2>モックの扱い</h2>
 * <p>DB・認可・自分の Bean はモックしない。{@link MockitoSpyBean} は「実行途中で止める」
 * 「途中で失敗させる」ための差し込み口としてのみ使い、既定では実体へ委譲する。</p>
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "feature.shift-budget.enabled=true",
        "feature.shift-budget.retry-batch-enabled=true"
})
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
public abstract class ShiftBudgetCmp2152RealDbTestBase extends AbstractMySqlIntegrationTest {

    protected static final YearMonth TARGET_MONTH = YearMonth.of(2026, 6);
    protected static final LocalDate PERIOD_START = LocalDate.of(2026, 6, 1);
    protected static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);
    protected static final String ALERT_SOURCE_TYPE = "BUDGET_THRESHOLD_ALERT";

    /** users.id / slot_id の採番器。他の試練クラスの固定 ID 帯（1559xxxxx 等）と重ならない帯を使う。 */
    private static final AtomicLong SEQ = new AtomicLong(2_152_000_000L + (System.nanoTime() % 1_000_000L) * 100);

    @Autowired protected MockMvc mvc;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected FeatureFlagRepository featureFlags;
    @Autowired protected CacheManager caches;
    @Autowired protected ShiftBudgetAllocationRepository allocationRepository;
    @Autowired protected ShiftBudgetConsumptionRepository consumptionRepository;
    @Autowired protected BudgetThresholdAlertRepository alertRepository;
    @Autowired protected ShiftBudgetFailedEventRepository failedEventRepository;
    @Autowired protected BudgetConfigRepository budgetConfigRepository;
    @Autowired protected WorkflowTemplateRepository workflowTemplateRepository;
    @Autowired protected WorkflowTemplateStepRepository workflowTemplateStepRepository;
    @PersistenceContext protected EntityManager em;

    /** 月次締め・訂正の途中で止めるための差し込み口（既定は実体へ委譲）。 */
    @MockitoSpyBean protected BudgetTransactionRepository budgetTransactionRepository;

    /** 通知再送の実行中に止めるための差し込み口（既定は実体へ委譲）。 */
    @MockitoSpyBean protected ShiftBudgetNotificationResendService notificationResendService;

    protected Long orgId;
    /** 組織の BUDGET_VIEW / BUDGET_ADMIN を実権限グループで持つ利用者（SYSTEM_ADMIN ではない）。 */
    protected Long budgetAdminId;
    protected Long fiscalYearId;
    protected Long categoryId;

    @BeforeEach
    protected void setUpCmp2152Organization() {
        FeatureFlagTestSupport.enable(featureFlags, caches, "FEATURE_SHIFT_ENABLED");
        transactionTemplate.executeWithoutResult(status -> {
            orgId = insertOrganization();
            budgetAdminId = nextId();
            MembershipTestHelper.insertActiveUser(em, budgetAdminId);
            grantBudgetAdmin(budgetAdminId, orgId);
            fiscalYearId = insertFiscalYear();
            categoryId = insertCategory(fiscalYearId);
            em.flush();
        });
    }

    @AfterEach
    protected void clearCmp2152Auth() {
        SecurityContextHolder.clearContext();
    }

    // ───────────── 採番・認証 ─────────────

    protected static long nextId() {
        return SEQ.incrementAndGet();
    }

    protected void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    // ───────────── HTTP ─────────────

    protected MvcResult postJson(Long actorId, String path, String json) throws Exception {
        return mvc.perform(post(path)
                        .header("X-Organization-Id", orgId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json == null ? "{}" : json)
                        .with(authentication(new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of()))))
                .andReturn();
    }

    protected MvcResult getAs(Long actorId, String path) throws Exception {
        return mvc.perform(get(path)
                        .header("X-Organization-Id", orgId)
                        .with(authentication(new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of()))))
                .andReturn();
    }

    /** 既存の手動再試行 API（認可規則は既存のまま: 組織 BUDGET_ADMIN）。 */
    protected int manualRetry(Long failedEventId) throws Exception {
        return postJson(budgetAdminId, "/api/v1/shift-budget/failed-events/" + failedEventId + "/retry", null)
                .getResponse().getStatus();
    }

    /** 既存の手動解決 API。 */
    protected int manualResolve(Long failedEventId) throws Exception {
        return postJson(budgetAdminId, "/api/v1/shift-budget/failed-events/" + failedEventId + "/resolve", null)
                .getResponse().getStatus();
    }

    // ───────────── フィクスチャ ─────────────

    protected Long seedAllocation(String allocated, String consumed, String confirmed) {
        return allocationRepository.save(ShiftBudgetAllocationEntity.builder()
                .organizationId(orgId)
                .fiscalYearId(fiscalYearId)
                .budgetCategoryId(categoryId)
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .allocatedAmount(new BigDecimal(allocated))
                .consumedAmount(new BigDecimal(consumed))
                .confirmedAmount(new BigDecimal(confirmed))
                .currency("JPY")
                .createdBy(budgetAdminId)
                .version(0L)
                .build()).getId();
    }

    protected Long seedConsumption(Long allocationId, Long slotId, Long userId, String rate, String hours,
                                   ShiftBudgetConsumptionStatus status) {
        BigDecimal r = new BigDecimal(rate);
        BigDecimal h = new BigDecimal(hours);
        return consumptionRepository.save(ShiftBudgetConsumptionEntity.builder()
                .allocationId(allocationId)
                .shiftId(1L)
                .slotId(slotId)
                .userId(userId)
                .hourlyRateSnapshot(r)
                .hours(h)
                .amount(r.multiply(h))
                .currency("JPY")
                .status(status)
                .recordedAt(LocalDateTime.of(2026, 6, 1, 9, 0))
                .build()).getId();
    }

    protected Long seedAlert(Long allocationId, int thresholdPercent, String consumedAtTrigger) {
        return alertRepository.save(BudgetThresholdAlertEntity.builder()
                .allocationId(allocationId)
                .thresholdPercent(thresholdPercent)
                .triggeredAt(LocalDateTime.now().minusHours(1))
                .consumedAmountAtTrigger(new BigDecimal(consumedAtTrigger))
                .notifiedUserIds("[]")
                .build()).getId();
    }

    protected Long seedFailedEvent(ShiftBudgetFailedEventType type, Long sourceId, String payloadJson) {
        return failedEventRepository.save(ShiftBudgetFailedEventEntity.builder()
                .organizationId(orgId)
                .eventType(type)
                .sourceId(sourceId)
                .payload(payloadJson)
                .errorMessage("CMP-260909-2152 試練: 初回失敗")
                .retryCount(0)
                .status(ShiftBudgetFailedEventStatus.PENDING)
                .build()).getId();
    }

    /** 承認者 1 名・1 段の組織ワークフロー雛形。{@code broken=true} なら提出時に承認者の解析で必ず落ちる。 */
    protected Long seedWorkflowTemplate(boolean broken) {
        WorkflowTemplateEntity template = workflowTemplateRepository.save(WorkflowTemplateEntity.builder()
                .scopeType("ORGANIZATION")
                .scopeId(orgId)
                .name("CMP2152 超過承認 " + nextId())
                .createdBy(budgetAdminId)
                .build());
        workflowTemplateStepRepository.save(WorkflowTemplateStepEntity.builder()
                .templateId(template.getId())
                .stepOrder(1)
                .name("予算責任者")
                .approvalType(ApprovalType.ALL)
                .approverType(ApproverType.USER)
                .approverUserIds(broken ? "[\"broken\"]" : "[" + budgetAdminId + "]")
                .build());
        return template.getId();
    }

    /** 壊した雛形の承認者を正しい値へ直す（運用者が原因を取り除いた状態）。 */
    protected void repairWorkflowTemplate(Long templateId) {
        jdbc.update("UPDATE workflow_template_steps SET approver_user_ids = ? WHERE template_id = ?",
                "[" + budgetAdminId + "]", templateId);
    }

    protected void configureOverLimitWorkflow(Long templateId) {
        budgetConfigRepository.save(BudgetConfigEntity.builder()
                .scopeType("ORGANIZATION")
                .scopeId(orgId)
                .overLimitWorkflowId(templateId)
                .build());
    }

    /** 実行中リース（10 分）を切らした状態にする。JVM 既定ゾーンで計算した値を渡す（DB の NOW() は使わない）。 */
    protected void expireLease(Long failedEventId) {
        jdbc.update("UPDATE shift_budget_failed_events SET last_retried_at = ? WHERE id = ?",
                LocalDateTime.now().minusMinutes(11), failedEventId);
    }

    /** 組織に属するチーム（team_org_memberships ACTIVE）を作る。 */
    protected Long seedTeam() {
        return transactionTemplate.execute(status -> {
            String name = "CMP2152 チーム " + nextId();
            em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                            + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, "
                            + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                    .setParameter("name", name).executeUpdate();
            em.flush();
            Long teamId = ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                    .setParameter("name", name).getSingleResult()).longValue();
            em.createNativeQuery("INSERT INTO team_org_memberships (team_id, organization_id, status, invited_at, created_at) "
                            + "VALUES (:tid, :oid, 'ACTIVE', NOW(), NOW())")
                    .setParameter("tid", teamId).setParameter("oid", orgId).executeUpdate();
            em.flush();
            return teamId;
        });
    }

    protected Long seedTeamAllocation(Long teamId, String allocated, String consumed, String confirmed) {
        return allocationRepository.save(ShiftBudgetAllocationEntity.builder()
                .organizationId(orgId)
                .teamId(teamId)
                .fiscalYearId(fiscalYearId)
                .budgetCategoryId(categoryId)
                .periodStart(PERIOD_START)
                .periodEnd(PERIOD_END)
                .allocatedAmount(new BigDecimal(allocated))
                .consumedAmount(new BigDecimal(consumed))
                .confirmedAmount(new BigDecimal(confirmed))
                .currency("JPY")
                .createdBy(budgetAdminId)
                .version(0L)
                .build()).getId();
    }

    // ───────────── 締め後訂正 API（新規。項目名は MonthlyCloseCorrectionRequest（訂正中核の試練）と同じ snake_case） ─────────────

    protected String correctionsPath(Long allocationId) {
        return "/api/v1/shift-budget/allocations/" + allocationId + "/monthly-closes/" + TARGET_MONTH + "/corrections";
    }

    protected String modifyJson(String commandId, long expectedRevision, Long consumptionId,
                                String hours, String hourlyRate, String reason) {
        return "{\"command_id\":\"" + commandId + "\",\"expected_revision\":" + expectedRevision
                + ",\"kind\":\"MODIFY\",\"line_kind\":\"ORIGINAL\",\"consumption_id\":" + consumptionId
                + ",\"hours\":" + hours + ",\"hourly_rate\":" + hourlyRate
                + ",\"content\":\"勤務時間・時給の訂正\",\"reason\":\"" + reason + "\"}";
    }

    protected String addJson(String commandId, long expectedRevision, Long userId, String workDate,
                             Long slotId, Long shiftId, String hours, String hourlyRate, String reason) {
        return "{\"command_id\":\"" + commandId + "\",\"expected_revision\":" + expectedRevision
                + ",\"kind\":\"ADD\",\"user_id\":" + userId + ",\"work_date\":\"" + workDate + "\""
                + (slotId == null ? "" : ",\"slot_id\":" + slotId)
                + (shiftId == null ? "" : ",\"shift_id\":" + shiftId)
                + ",\"hours\":" + hours + ",\"hourly_rate\":" + hourlyRate
                + ",\"content\":\"締め後に判明した勤務\",\"reason\":\"" + reason + "\"}";
    }

    /** 生存している訂正差額仕訳（source_type = SHIFT_BUDGET_CORRECTION）の件数。 */
    protected int correctionTransactionCount(Long allocationId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_transactions WHERE source_type = 'SHIFT_BUDGET_CORRECTION' "
                        + "AND source_id = ? AND deleted_at IS NULL", Integer.class, allocationId);
        return n == null ? 0 : n;
    }

    // ───────────── 読み取り（DB 直） ─────────────

    protected Map<String, Object> failedEventRow(Long id) {
        return jdbc.queryForMap(
                "SELECT status, retry_count, error_message, last_retried_at FROM shift_budget_failed_events WHERE id = ?",
                id);
    }

    protected String failedEventStatus(Long id) {
        return (String) failedEventRow(id).get("status");
    }

    protected int failedEventRetryCount(Long id) {
        return ((Number) failedEventRow(id).get("retry_count")).intValue();
    }

    /** 同じ連携元（source_type, source_id）を持つ生存申請の ID（古い順）。 */
    protected List<Long> liveWorkflowRequestIds(String sourceType, Long sourceId) {
        return jdbc.queryForList(
                "SELECT id FROM workflow_requests WHERE source_type = ? AND source_id = ? AND deleted_at IS NULL ORDER BY id",
                Long.class, sourceType, sourceId);
    }

    protected String workflowRequestStatus(Long requestId) {
        return jdbc.queryForObject("SELECT status FROM workflow_requests WHERE id = ?", String.class, requestId);
    }

    protected int workflowRequestStepCount(Long requestId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_request_steps WHERE request_id = ?", Integer.class, requestId);
        return n == null ? 0 : n;
    }

    /** 指定雛形から作られた申請に紐づくステップ数（申請が巻き戻ったかの確認用。論理削除行も含む）。 */
    protected int workflowStepCountForTemplate(Long templateId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_request_steps s JOIN workflow_requests r ON r.id = s.request_id "
                        + "WHERE r.template_id = ?", Integer.class, templateId);
        return n == null ? 0 : n;
    }

    protected int workflowRequestCountForTemplate(Long templateId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_requests WHERE template_id = ?", Integer.class, templateId);
        return n == null ? 0 : n;
    }

    protected Long alertWorkflowRequestId(Long alertId) {
        return jdbc.queryForObject(
                "SELECT workflow_request_id FROM budget_threshold_alerts WHERE id = ?", Long.class, alertId);
    }

    protected List<Integer> alertThresholds(Long allocationId) {
        return jdbc.queryForList(
                "SELECT threshold_percent FROM budget_threshold_alerts WHERE allocation_id = ? ORDER BY threshold_percent",
                Integer.class, allocationId);
    }

    protected Long alertId(Long allocationId, int thresholdPercent) {
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM budget_threshold_alerts WHERE allocation_id = ? AND threshold_percent = ?",
                Long.class, allocationId, thresholdPercent);
        return ids.isEmpty() ? null : ids.get(0);
    }

    protected BigDecimal consumedAmount(Long allocationId) {
        return jdbc.queryForObject("SELECT consumed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    protected BigDecimal confirmedAmount(Long allocationId) {
        return jdbc.queryForObject("SELECT confirmed_amount FROM shift_budget_allocations WHERE id = ?",
                BigDecimal.class, allocationId);
    }

    protected List<String> consumptionStatuses(Long allocationId) {
        return jdbc.queryForList(
                "SELECT status FROM shift_budget_consumptions WHERE allocation_id = ? AND deleted_at IS NULL ORDER BY id",
                String.class, allocationId);
    }

    protected int plannedCountForSlot(Long slotId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM shift_budget_consumptions WHERE slot_id = ? AND status = 'PLANNED' AND deleted_at IS NULL",
                Integer.class, slotId);
        return n == null ? 0 : n;
    }

    // ───────────── 組織・権限 ─────────────

    private Long insertOrganization() {
        OrganizationEntity organization = OrganizationEntity.builder()
                .name("CMP2152 試練 " + nextId())
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

    /** 金型: {@code ShiftBudgetSummaryRateBreakdownIT#grant}。組織 MEMBER＋DEPUTY_ADMIN＋権限グループで付与する。 */
    protected void grantBudgetAdmin(Long userId, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, scopeId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, userId, "DEPUTY_ADMIN", null, scopeId);
        PermissionGroupEntity group = PermissionGroupEntity.builder().organizationId(scopeId)
                .targetRole(PermissionGroupEntity.TargetRole.DEPUTY_ADMIN).name("2152担当").createdBy(userId).build();
        em.persist(group);
        em.flush();
        for (String permission : List.of("BUDGET_VIEW", "BUDGET_ADMIN")) {
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

    private Long insertFiscalYear() {
        String name = "CMP2152 年度 " + nextId();
        em.createNativeQuery(
                        "INSERT INTO budget_fiscal_years (scope_type, scope_id, name, start_date, end_date, "
                                + "status, created_by, version, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :orgId, :name, '2026-04-01', '2027-03-31', "
                                + "'OPEN', :userId, 0, NOW(), NOW())")
                .setParameter("orgId", orgId)
                .setParameter("name", name)
                .setParameter("userId", budgetAdminId)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_fiscal_years WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertCategory(Long fyId) {
        String name = "CMP2152 費目 " + nextId();
        em.createNativeQuery(
                        "INSERT INTO budget_categories (fiscal_year_id, name, category_type, sort_order, "
                                + "version, created_at, updated_at) "
                                + "VALUES (:fyId, :name, 'EXPENSE', 0, 0, NOW(), NOW())")
                .setParameter("fyId", fyId)
                .setParameter("name", name)
                .executeUpdate();
        em.flush();
        return ((Number) em.createNativeQuery("SELECT id FROM budget_categories WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
