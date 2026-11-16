package com.mannschaft.app.shiftbudget.closecorrection;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260909-2152 試練（認可）: 10・24・41。
 *
 * <p>新規 API（訂正・追加・取消・履歴・未計上勤務）は割当の実組織で判定する。登録系は組織 BUDGET_ADMIN、
 * 履歴は組織 BUDGET_VIEW。SYSTEM_ADMIN 単独では拒否する。他組織の割当・組織外チームの割当は 404。
 * 実権限 DB（memberships / user_roles / permission_groups）で検証し、拒否時の変更行0を確かめる。</p>
 *
 * <p>既存 reader（割当一覧・詳細）の SYSTEM_ADMIN 単独通過はマスター御裁定3により別CMPで扱う残存リスクであり、
 * ここでは現状を固定する characterization（初回から green が正常）として置く。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260909-2152 月次締め後訂正の認可（実DB・実Security）")
class MonthlyCloseCorrectionAuthorizationIT extends MonthlyCloseCorrectionTestBase {

    private Long allocationId;
    private Long lineA;

    @BeforeEach
    void closeMonth() {
        allocationId = allocation("50000");
        lineA = planned(allocationId, workerId, "1200", "4");
        closeJune();
    }

    private void assertNothingChanged() {
        assertThat(journals(allocationId, SOURCE_CORRECTION)).as("拒否時は差額仕訳0").isEmpty();
        assertThat(allocationCache(allocationId).get("confirmed_amount").toString()).isEqualTo("4800");
    }

    private Long sysAdminOnly() {
        Long id = newUser();
        grantSystemAdmin(id);
        return id;
    }

    private Long viewOnly() {
        Long id = newUser();
        grantBudget(id, orgId, false);
        return id;
    }

    // ───────────── 試練10 認可表（AC11） ─────────────

    @Test
    @DisplayName("試練10 未認証は全新規 API が401")
    void 試練10_未認証は401() throws Exception {
        String b = base(allocationId, JUNE_KEY);
        mvc.perform(post(b + "/corrections").header("X-Organization-Id", orgId).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(modify(lineA, "1", "1200", 0, "未認証"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(b + "/corrections").header("X-Organization-Id", orgId)).andExpect(status().isUnauthorized());
        mvc.perform(get(b + "/uncounted-shifts").header("X-Organization-Id", orgId)).andExpect(status().isUnauthorized());
        assertNothingChanged();
    }

    @Test
    @DisplayName("試練10 組織 BUDGET_ADMIN は登録でき、BUDGET_VIEW のみは履歴を読めるが登録・取消・未計上勤務は403")
    void 試練10_予算担当と閲覧者() throws Exception {
        String correctionId = data(postCorrection(adminId, allocationId, modify(lineA, "3", "1200", 0, "担当者の訂正"))
                .andExpect(status().isCreated())).path("id").asText();
        Long viewer = viewOnly();

        history(viewer, allocationId).andExpect(status().isOk());
        postCorrection(viewer, allocationId, modify(lineA, "2", "1200", 1, "閲覧者の訂正")).andExpect(status().isForbidden());
        postCorrection(viewer, allocationId, add(workerId, LocalDate.of(2026, 6, 15), null, null, "1", "1200", 1,
                "閲覧者の追加")).andExpect(status().isForbidden());
        postReversal(viewer, allocationId, correctionId, 1, "閲覧者の取消").andExpect(status().isForbidden());
        uncounted(viewer, allocationId).andExpect(status().isForbidden());
        assertThat(journals(allocationId, SOURCE_CORRECTION)).hasSize(1);
    }

    @Test
    @DisplayName("試練10 SYSTEM_ADMIN でも組織所属のみ（予算権限なし）では登録も履歴も403")
    void 試練10_SYSでも組織所属だけでは拒否() throws Exception {
        Long sys = sysAdminOnly();
        tx.executeWithoutResult(s -> MembershipTestHelper.insertMembership(em, sys, ScopeType.ORGANIZATION, orgId,
                RoleKind.MEMBER));
        postCorrection(sys, allocationId, modify(lineA, "1", "1200", 0, "SYS+所属")).andExpect(status().isForbidden());
        history(sys, allocationId).andExpect(status().isForbidden());
        assertNothingChanged();
    }

    @Test
    @DisplayName("試練10 他組織の予算担当は、自組織ヘッダでも当組織ヘッダでも他組織の割当を操作できない（404/403）")
    void 試練10_他組織の予算担当() throws Exception {
        Long otherOrg = tx.execute(s -> insertOrganization());
        Long foreignAdmin = newUser();
        grantBudget(foreignAdmin, otherOrg, true);

        postCorrection(foreignAdmin, otherOrg, allocationId, JUNE_KEY, modify(lineA, "1", "1200", 0, "他組織ヘッダ"))
                .andExpect(status().isNotFound());
        postCorrection(foreignAdmin, orgId, allocationId, JUNE_KEY, modify(lineA, "1", "1200", 0, "当組織ヘッダ"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get(base(allocationId, JUNE_KEY) + "/corrections"), foreignAdmin, otherOrg))
                .andExpect(status().isNotFound());
        assertNothingChanged();
    }

    @Test
    @DisplayName("試練10 組織に加盟していないチームの割当は、当組織の予算担当でも404")
    void 試練10_組織外チームの割当は404() throws Exception {
        Long outsideTeam = tx.execute(s -> insertTeam());
        Long outsideFy = tx.execute(s -> insertFiscalYear(orgId, adminId));
        Long outsideAllocation = allocation(orgId, outsideTeam, outsideFy, tx.execute(s -> insertCategory(outsideFy)),
                "50000");
        Long outsideLine = planned(outsideAllocation, workerId, "1200", "4");
        jdbc.update("INSERT INTO budget_transactions (fiscal_year_id, category_id, scope_type, scope_id, transaction_type, "
                + "amount, transaction_date, title, is_auto_recorded, source_type, source_id, approval_status, recorded_by, "
                + "version, created_at, updated_at) SELECT fiscal_year_id, budget_category_id, 'TEAM', team_id, 'EXPENSE', "
                + "4800, '2026-06-30', '2026年6月 シフト人件費（自動集計）', 1, ?, id, 'APPROVED', ?, 0, NOW(), NOW() "
                + "FROM shift_budget_allocations WHERE id = ?", SOURCE_MONTHLY, adminId, outsideAllocation);
        jdbc.update("UPDATE shift_budget_consumptions SET status = 'CONFIRMED' WHERE id = ?", outsideLine);

        postCorrection(adminId, outsideAllocation, modify(outsideLine, "1", "1200", 0, "組織外チーム"))
                .andExpect(status().isNotFound());
        history(adminId, outsideAllocation).andExpect(status().isNotFound());
        assertThat(journals(outsideAllocation, SOURCE_CORRECTION)).isEmpty();
    }

    // ───────────── 試練24 SYSTEM_ADMIN 単独（AC11改） ─────────────

    @Test
    @DisplayName("試練24 SYSTEM_ADMIN 単独は 訂正・追加・取消・履歴 がすべて403で変更0")
    void 試練24_SYSTEM_ADMIN単独は新規APIすべて403() throws Exception {
        String correctionId = data(postCorrection(adminId, allocationId, modify(lineA, "3", "1200", 0, "担当者の訂正"))
                .andExpect(status().isCreated())).path("id").asText();
        Long sys = sysAdminOnly();
        int before = journals(allocationId, SOURCE_CORRECTION).size();

        postCorrection(sys, allocationId, modify(lineA, "1", "1200", 1, "SYS単独の訂正")).andExpect(status().isForbidden());
        postCorrection(sys, allocationId, cancel(lineA, 1, "SYS単独の取消訂正")).andExpect(status().isForbidden());
        postCorrection(sys, allocationId, add(workerId, LocalDate.of(2026, 6, 15), null, null, "1", "1200", 1,
                "SYS単独の追加")).andExpect(status().isForbidden());
        postReversal(sys, allocationId, correctionId, 1, "SYS単独の取消").andExpect(status().isForbidden());
        history(sys, allocationId).andExpect(status().isForbidden());

        assertThat(journals(allocationId, SOURCE_CORRECTION)).hasSize(before);
        assertAllocationAmounts(adminId, allocationId, "3600", "3600", "-1200");
    }

    @Test
    @DisplayName("試練24（現状固定・残存リスク）既存 reader の割当一覧・詳細は SYSTEM_ADMIN 単独で通り、summary は403のまま")
    void 試練24_既存readerのSYSTEM_ADMIN単独通過を現状固定() throws Exception {
        Long sys = sysAdminOnly();
        list(sys).andExpect(status().isOk());
        detail(sys, allocationId).andExpect(status().isOk());
        summary(sys, allocationId).andExpect(status().isForbidden());
    }

    // ───────────── 試練41 ワンクリック追加の権限（AC21） ─────────────

    @Test
    @DisplayName("試練41 未計上勤務の一覧とそこからの ADD は、閲覧者・SYSTEM_ADMIN 単独では403")
    void 試練41_未計上勤務の一覧とボタンの権限() throws Exception {
        long[] slot = publishedSlot(teamId, LocalDate.of(2026, 6, 20), java.time.LocalTime.of(9, 0),
                java.time.LocalTime.of(13, 0), workerId);
        hourlyRate(workerId, teamId, "1200", LocalDate.of(2026, 1, 1));
        uncounted(adminId, allocationId).andExpect(status().isOk());

        for (Long actor : List.of(viewOnly(), sysAdminOnly())) {
            uncounted(actor, allocationId).andExpect(status().isForbidden());
            Map<String, Object> body = add(workerId, LocalDate.of(2026, 6, 20), slot[1], slot[0], "4", "1200", 0,
                    "候補からの追加");
            ResultActions r = postCorrection(actor, allocationId, body);
            r.andExpect(status().isForbidden());
        }
        assertNothingChanged();
    }
}
