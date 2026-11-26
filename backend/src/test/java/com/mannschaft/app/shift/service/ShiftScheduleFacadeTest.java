package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ManualRemindResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftScheduleFacade}（認可ファサード）の単体テスト。
 *
 * <p>CMP-260923-0954 W6a: 旧 {@code ShiftScheduleServiceTest}・{@code ShiftPreferenceReminderBatchServiceTest}
 * が持っていた認可の検証（越境の 404 隠蔽・同チーム権限不足の 403・SUPPORTER・SYSTEM_ADMIN 短絡）を、
 * 認可の移設先である Facade へ移したもの（消していない）。ゲートは実物
 * （{@link ScopeConcealingAccessGate}）を {@link AccessControlService} のモックの上に組む。
 * 応答の status・code・message の全体は {@code ShiftScheduleSlotFacadeContractIT} が実 DB で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftScheduleFacade 認可 単体テスト")
class ShiftScheduleFacadeTest {

    private static final Long TEAM_ID = 1L;
    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 10L;

    @Mock
    private ShiftScheduleService scheduleService;
    @Mock
    private ShiftPreferenceReminderBatchService reminderService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ShiftPublicationBudgetContextPort publicationBudgetContextPort;
    @Mock
    private com.mannschaft.app.team.service.TeamShiftSettingsService teamShiftSettingsService;

    private ShiftScheduleFacade facade;

    @BeforeEach
    void setUp() {
        facade = new ShiftScheduleFacade(scheduleService, reminderService,
                new ShiftManageAccessGate(accessControlService, teamShiftSettingsService), accessControlService,
                publicationBudgetContextPort);
    }

    /**
     * 操作者を「ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN」にする（CMP-261125-2114）。
     * 判定は実物の {@link ShiftManageAccessGate} が AccessControlService のモックの上で行うので、
     * 閲覧側（isAdminOrAbove）と管理側（hasAdminOrPermissionInScope）の両方を、経路によって使われなくても許す形で用意する。
     */
    private void stubManager() {
        org.mockito.Mockito.lenient().when(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM"))
                .thenReturn(true);
        org.mockito.Mockito.lenient().when(accessControlService.hasAdminOrPermissionInScope(
                USER_ID, TEAM_ID, "TEAM", "MANAGE_SHIFTS")).thenReturn(true);
        org.mockito.Mockito.lenient().when(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).thenReturn(true);
    }

    private static ShiftScheduleScope published() {
        return new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.PUBLISHED,
                true);
    }

    private static ShiftScheduleScope draft() {
        return new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.DRAFT, false);
    }

    static Stream<ShiftScheduleScope> hiddenSummaryScopes() {
        return Stream.of(draft(),
                new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, ShiftScheduleStatus.ARCHIVED, false),
                new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, null, false),
                new ShiftScheduleScope(SCHEDULE_ID, TEAM_ID, null, true));
    }

    @ParameterizedTest
    @MethodSource("hiddenSummaryScopes")
    @DisplayName("CMP-260905-1630: 未公開サマリは一般メンバーに404を返し集計を呼ばない（nullもfail-closed）")
    void 未公開サマリは404で集計しない(ShiftScheduleScope scope) {
        given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(scope);
        // 修正前は管理者ゲートが在籍を問い合わせ、修正後は未公開判定で先に拒否する。
        org.mockito.Mockito.lenient().when(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM"))
                .thenReturn(true);

        assertCode(catchThrowable(() -> facade.getScheduleSummary(SCHEDULE_ID, USER_ID)),
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        verify(scheduleService).resolveScope(SCHEDULE_ID);
        verify(scheduleService, never()).getScheduleSummary(anyLong());
        verifyNoInteractions(reminderService);
    }

    @Test
    @DisplayName("未公開サマリでもSUPPORTERは従来どおり403で集計しない")
    void 未公開サマリでもSUPPORTERは403() {
        given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
        given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        // 修正前の管理者ゲートはSUPPORTERを直接調べないが、主体の条件は両版で同じにする。
        org.mockito.Mockito.lenient().when(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM"))
                .thenReturn(true);

        assertCode(catchThrowable(() -> facade.getScheduleSummary(SCHEDULE_ID, USER_ID)),
                CommonErrorCode.COMMON_002);
        verify(scheduleService).resolveScope(SCHEDULE_ID);
        verify(scheduleService, never()).getScheduleSummary(anyLong());
    }

    private static void assertCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(expected);
    }

    // ========================================
    // 一覧（teamId 直接指定。越境も不在も 403）
    // ========================================

    @Nested
    @DisplayName("listSchedules")
    class ListSchedules {

        @Test
        @DisplayName("非メンバーは 403 COMMON_002（越境も不在も同じ・隠さない）")
        void 非メンバーは403() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.listSchedules(TEAM_ID, null, null, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("SUPPORTER は 403 COMMON_002")
        void SUPPORTERは403() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.listSchedules(TEAM_ID, null, null, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("一般メンバーは privileged=false で呼ぶ（未公開は一覧から除外）")
        void 一般メンバーは公開のみ() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(scheduleService.listSchedules(TEAM_ID, false)).willReturn(List.of());

            assertThat(facade.listSchedules(TEAM_ID, null, null, USER_ID)).isEmpty();
            verify(scheduleService).listSchedules(TEAM_ID, false);
        }

        @Test
        @DisplayName("期間指定は from・to が両方あるときだけ listSchedulesByPeriod を呼ぶ")
        void 期間指定() {
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            stubManager();
            given(scheduleService.listSchedulesByPeriod(TEAM_ID, from, to, true)).willReturn(List.of());

            assertThat(facade.listSchedules(TEAM_ID, from, to, USER_ID)).isEmpty();
            verify(scheduleService, never()).listSchedules(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("CMP-261125-2114: チームロールを持たない SYSTEM_ADMIN は短絡せず（SYSTEM_ADMIN 判定を撃たない）、非メンバーとして 403")
        void SYSTEM_ADMINは短絡しない() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.listSchedules(TEAM_ID, null, null, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("CMP-261125-2114: 閲覧切替 ON の MANAGE_SHIFTS 無し DEPUTY_ADMIN は privileged=true（切替 OFF は false）")
        void 閲覧切替で副管理者は全量() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.hasAdminOrPermissionInScope(USER_ID, TEAM_ID, "TEAM", "MANAGE_SHIFTS"))
                    .willReturn(false);
            given(teamShiftSettingsService.findDeputyViewToggles(TEAM_ID))
                    .willReturn(new com.mannschaft.app.team.dto.DeputyShiftViewToggles(true, false),
                            com.mannschaft.app.team.dto.DeputyShiftViewToggles.OFF);
            given(scheduleService.listSchedules(TEAM_ID, true)).willReturn(List.of());
            given(scheduleService.listSchedules(TEAM_ID, false)).willReturn(List.of());

            assertThat(facade.listSchedules(TEAM_ID, null, null, USER_ID)).isEmpty();
            assertThat(facade.listSchedules(TEAM_ID, null, null, USER_ID)).isEmpty();
            verify(scheduleService).listSchedules(TEAM_ID, true);
            verify(scheduleService).listSchedules(TEAM_ID, false);
        }
    }

    // ========================================
    // 詳細（越境は不在と同じ 404）
    // ========================================

    @Nested
    @DisplayName("getSchedule")
    class GetSchedule {

        @Test
        @DisplayName("越境（所属していない）は不在と同じ 404 SHIFT_001（BOLA 封鎖・存在オラクル解消）")
        void 越境は404() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(scheduleService, never()).getSchedule(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("SUPPORTER は 403 COMMON_002")
        void SUPPORTERは403() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("SUPPORTER は未公開でも公開済みと同じ 403 COMMON_002（D-2127-1: 公開状態を応答差で漏らさない）")
        void 未公開でもSUPPORTERは403() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isSupporter(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("一般メンバーには未公開は 404")
        void 未公開は404() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }

        @Test
        @DisplayName("一般メンバーは privileged=false で取得する")
        void 一般メンバーは通る() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(scheduleService.getSchedule(SCHEDULE_ID, false)).willReturn(response);

            assertThat(facade.getSchedule(SCHEDULE_ID, USER_ID)).isSameAs(response);
        }

        @Test
        @DisplayName("user_roles のみの ADMIN（退会済みでロールだけ残った）は在籍が無いので未公開を見られず、所属者向けの 403")
        void userRolesのみのADMINは在籍が無ければ拒否() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
            stubManager();
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)), CommonErrorCode.COMMON_002);
            verify(scheduleService, never()).getSchedule(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("CMP-261125-2114: チームロールを持たない SYSTEM_ADMIN は短絡せず、部外者と同じ 404")
        void SYSTEM_ADMINは短絡しない() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(draft());
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertCode(catchThrowable(() -> facade.getSchedule(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
        }
    }

    // ========================================
    // 作成（teamId 直接指定。権限不足は 403）
    // ========================================

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        private final CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                "3月第1週シフト", "WEEKLY", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7), null, null);

        @Test
        @DisplayName("非権限者は 403 COMMON_002 で tx 本体を呼ばない")
        void 非権限者は403() {
            // hasAdminOrPermissionInScope は既定で false（MANAGE_SHIFTS 無しの DEPUTY_ADMIN・一般メンバーなど）。
            assertCode(catchThrowable(() -> facade.createSchedule(TEAM_ID, req, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("CMP-261125-2114: SYSTEM_ADMIN は短絡して作成できない（SYSTEM_ADMIN 判定を撃たず、チームロールが無ければ 403）")
        void SYSTEM_ADMINは短絡しない() {
            assertCode(catchThrowable(() -> facade.createSchedule(TEAM_ID, req, USER_ID)),
                    CommonErrorCode.COMMON_002);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
            verifyNoInteractions(scheduleService);
        }

        @Test
        @DisplayName("チーム ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN は作成できる")
        void ADMINは作成できる() {
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            stubManager();
            given(scheduleService.createSchedule(TEAM_ID, req, USER_ID)).willReturn(response);

            assertThat(facade.createSchedule(TEAM_ID, req, USER_ID)).isSameAs(response);
            verify(accessControlService).hasAdminOrPermissionInScope(USER_ID, TEAM_ID, "TEAM", "MANAGE_SHIFTS");
        }
    }

    // ========================================
    // 管理系（更新・削除・遷移・サマリ・remind・複製）: 越境 404 / 権限不足 403 / 管理者・SA は通る
    // ========================================

    @Nested
    @DisplayName("管理系 EP（更新・削除・遷移・サマリ・remind・複製）")
    class AdminOperations {

        private final UpdateShiftScheduleRequest updateReq = new UpdateShiftScheduleRequest(
                "タイトル", null, null, null, null, null, null);

        @BeforeEach
        void scope() {
            given(scheduleService.resolveScope(SCHEDULE_ID)).willReturn(published());
        }

        private void asOutsider() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
        }

        private void asPlainMember() {
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        }

        private void asAdmin() {
            stubManager();
        }

        @Test
        @DisplayName("越境は不在と同じ 404 SHIFT_001（remind も。是正前は 403 でオラクルだった）で tx 本体を呼ばない")
        void 越境は404() {
            asOutsider();

            for (Runnable op : List.<Runnable>of(
                    () -> facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID),
                    () -> facade.deleteSchedule(SCHEDULE_ID, USER_ID),
                    () -> facade.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID),
                    () -> facade.getScheduleSummary(SCHEDULE_ID, USER_ID),
                    () -> facade.remindUnsubmitted(SCHEDULE_ID, USER_ID),
                    () -> facade.duplicateSchedule(SCHEDULE_ID, USER_ID))) {
                assertCode(catchThrowable(op::run), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            }
            verifyNoInteractions(reminderService);
            verify(scheduleService, never()).updateSchedule(anyLong(), any());
            verify(scheduleService, never()).deleteSchedule(anyLong(), anyLong());
            verify(scheduleService, never()).transitionStatus(anyLong(), any(), anyLong());
            verify(scheduleService, never()).getScheduleSummary(anyLong());
            verify(scheduleService, never()).duplicateSchedule(anyLong(), anyLong());
        }

        @Test
        @DisplayName("同チームの権限不足（一般メンバー）は 403 COMMON_002（隠す必要が無い側）")
        void 権限不足は403() {
            asPlainMember();

            for (Runnable op : List.<Runnable>of(
                    () -> facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID),
                    () -> facade.deleteSchedule(SCHEDULE_ID, USER_ID),
                    () -> facade.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID),
                    () -> facade.getScheduleSummary(SCHEDULE_ID, USER_ID),
                    () -> facade.remindUnsubmitted(SCHEDULE_ID, USER_ID),
                    () -> facade.duplicateSchedule(SCHEDULE_ID, USER_ID))) {
                assertCode(catchThrowable(op::run), CommonErrorCode.COMMON_002);
            }
            verifyNoInteractions(reminderService);
            verify(scheduleService, never()).transitionStatus(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("チーム ADMIN は各 tx 本体へ進む（remind は手動リマインド本体へ）")
        void 管理者は通る() {
            asAdmin();
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            ShiftScheduleSummaryResponse summary = ShiftScheduleSummaryResponse.builder()
                    .scheduleId(SCHEDULE_ID).summaryByDate(List.of()).build();
            ManualRemindResponse remind = ManualRemindResponse.builder().scheduleId(SCHEDULE_ID).build();
            given(scheduleService.updateSchedule(SCHEDULE_ID, updateReq)).willReturn(response);
            given(scheduleService.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID)).willReturn(response);
            given(scheduleService.getScheduleSummary(SCHEDULE_ID)).willReturn(summary);
            given(scheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID)).willReturn(response);
            given(reminderService.triggerManualReminder(SCHEDULE_ID, USER_ID)).willReturn(remind);

            assertThat(facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID)).isSameAs(response);
            facade.deleteSchedule(SCHEDULE_ID, USER_ID);
            assertThat(facade.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID)).isSameAs(response);
            assertThat(facade.getScheduleSummary(SCHEDULE_ID, USER_ID)).isSameAs(summary);
            assertThat(facade.remindUnsubmitted(SCHEDULE_ID, USER_ID)).isSameAs(remind);
            assertThat(facade.duplicateSchedule(SCHEDULE_ID, USER_ID)).isSameAs(response);
            verify(scheduleService).deleteSchedule(SCHEDULE_ID, USER_ID);
        }

        @Test
        @DisplayName("CMP-261115-1821: 公開は認可の後に枠日付の範囲で予算 context を取得し、値として tx 本体へ渡す")
        void 公開は認可後に予算contextを固定して渡す() {
            asAdmin();
            LocalDate from = LocalDate.of(2026, 7, 1);
            LocalDate to = LocalDate.of(2026, 7, 31);
            ShiftPublicationBudgetContext context = new ShiftPublicationBudgetContext(
                    from, to, List.of(), java.util.Map.of());
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.findSlotDateRange(SCHEDULE_ID)).willReturn(new ShiftSlotDateRange(from, to));
            given(publicationBudgetContextPort.load(TEAM_ID, from, to)).willReturn(context);
            given(scheduleService.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID, context)).willReturn(response);

            assertThat(facade.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID)).isSameAs(response);
            verify(scheduleService, never()).transitionStatus(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("CMP-261115-1821: 枠が無い公開は候補を引かず空 context で tx 本体へ進む")
        void 枠なしの公開は候補を引かない() {
            asAdmin();
            ShiftScheduleResponse response = ShiftScheduleResponse.builder().id(SCHEDULE_ID).build();
            given(scheduleService.findSlotDateRange(SCHEDULE_ID)).willReturn(new ShiftSlotDateRange(null, null));
            given(scheduleService.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID,
                    ShiftPublicationBudgetContext.noSlots())).willReturn(response);

            assertThat(facade.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID)).isSameAs(response);
            verifyNoInteractions(publicationBudgetContextPort);
        }

        @Test
        @DisplayName("CMP-261115-1821: 認可で拒否された公開は予算 context を取得しない（認可前に予算情報へ触れない）")
        void 拒否された公開は予算contextを取得しない() {
            asPlainMember();

            assertCode(catchThrowable(() -> facade.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID)),
                    CommonErrorCode.COMMON_002);
            verifyNoInteractions(publicationBudgetContextPort);
            verify(scheduleService, never()).findSlotDateRange(anyLong());
        }

        @Test
        @DisplayName("CMP-261125-2114: チームロールを持たない SYSTEM_ADMIN は短絡して通らず、部外者と同じ 404（SYSTEM_ADMIN 判定を撃たない）")
        void SYSTEM_ADMINは短絡しない() {
            asOutsider();

            assertCode(catchThrowable(() -> facade.remindUnsubmitted(SCHEDULE_ID, USER_ID)),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
            verifyNoInteractions(reminderService);
        }

        @Test
        @DisplayName("CMP-261125-2114: 管理操作は閲覧切替 ON の MANAGE_SHIFTS 無し DEPUTY_ADMIN にも許さない（403）が、サマリ（閲覧扱い）は通る")
        void 閲覧切替ONでも操作は403でサマリだけ通る() {
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(teamShiftSettingsService.findDeputyViewToggles(TEAM_ID))
                    .willReturn(new com.mannschaft.app.team.dto.DeputyShiftViewToggles(true, false));
            ShiftScheduleSummaryResponse summary = ShiftScheduleSummaryResponse.builder()
                    .scheduleId(SCHEDULE_ID).summaryByDate(List.of()).build();
            given(scheduleService.getScheduleSummary(SCHEDULE_ID)).willReturn(summary);

            for (Runnable op : List.<Runnable>of(
                    () -> facade.updateSchedule(SCHEDULE_ID, updateReq, USER_ID),
                    () -> facade.deleteSchedule(SCHEDULE_ID, USER_ID),
                    () -> facade.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID),
                    () -> facade.remindUnsubmitted(SCHEDULE_ID, USER_ID),
                    () -> facade.duplicateSchedule(SCHEDULE_ID, USER_ID))) {
                assertCode(catchThrowable(op::run), CommonErrorCode.COMMON_002);
            }
            assertThat(facade.getScheduleSummary(SCHEDULE_ID, USER_ID)).isSameAs(summary);
            verifyNoInteractions(reminderService);
        }
    }
}
