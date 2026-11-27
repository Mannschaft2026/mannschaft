package com.mannschaft.app.schedule.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CreateScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: {@link ScheduleService} の SYSTEM_ADMIN 直書き素通し
 * （予定の作成・編集・削除・取消・複製、出欠の閲覧・CSV、他人の PERSONAL 予定）を除去する受け入れテスト。
 *
 * <p>分類表の A 行（checkScheduleManagementAccess:716・checkMemberScheduleDeleteAccess:741・
 * checkScopeAdminAccess:771・checkScopeViewAccess:795・checkCreateScopeAccess:816）に対応する。
 * 所属の無い運営アカウントは、所属の無い一般利用者と同じ既存の拒否（403 COMMON_002）になる
 * （予定ドメインの非所属への応答。404 への統一は本陣の範囲外）。</p>
 *
 * <p>運営業務への影響: CMP-260902-0059 の「SYSTEM_ADMIN が削除済みチームを復元し、既存の予定 API で孤児予定を掃除する」
 * 経路はこの短絡に依存している。除去後は復元したチームの ADMIN が掃除する（/system-admin に掃除用の専用 API は無い。分類表 C 行）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: ScheduleService の SYSTEM_ADMIN 直書き素通しの除去")
class ScheduleServiceZc1DirectBypassRemovalTest {

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ScheduleCreationWriter creationWriter;
    @Mock private EventSurveyService eventSurveyService;
    @Mock private ScheduleReminderService reminderService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ScheduleEventCategoryService eventCategoryService;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private ScheduleQueryService queryService;
    @Mock private ScheduleRecurrenceService recurrenceService;
    @Mock private ScheduleScheduledTaskService scheduledTaskService;
    @Mock private TeamOrgMembershipRepository teamOrgMembershipRepository;
    @Mock private ScheduleTargetService scheduleTargetService;
    @Mock private AccessControlService accessControlService;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks private ScheduleService scheduleService;

    private static final Long SCHEDULE_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long PLATFORM = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long TEAM_ADMIN = 902L;
    private static final Long PERSONAL_OWNER = 903L;

    @BeforeEach
    void setUp() {
        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(OUTSIDER)).willReturn(false);
        given(accessControlService.isSystemAdmin(TEAM_ADMIN)).willReturn(false);
        given(accessControlService.isSystemAdmin(PERSONAL_OWNER)).willReturn(false);
        given(accessControlService.isAdminOrAbove(any(), anyLong(), anyString()))
                .willAnswer(inv -> TEAM_ADMIN.equals(inv.getArgument(0)));
        given(accessControlService.resolveEffectiveRoleName(any(), anyLong(), anyString()))
                .willAnswer(inv -> TEAM_ADMIN.equals(inv.getArgument(0)) ? "ADMIN" : null);
        given(accessControlService.hasPermission(any(), anyLong(), anyString(), anyString())).willReturn(false);
        for (Long nonMember : new Long[] {PLATFORM, OUTSIDER}) {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(eq(nonMember), anyLong(), anyString());
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(eq(nonMember), anyLong(), anyString());
        }
    }

    private static ScheduleEntity teamSchedule() {
        return ScheduleEntity.builder()
                .teamId(TEAM_ID).title("練習")
                .startAt(LocalDateTime.of(2030, 4, 1, 10, 0)).endAt(LocalDateTime.of(2030, 4, 1, 12, 0))
                .allDay(false).eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED).attendanceRequired(true)
                .commentOption(CommentOption.OPTIONAL).isException(false).createdBy(TEAM_ADMIN)
                .build();
    }

    private static ScheduleEntity personalSchedule() {
        return ScheduleEntity.builder()
                .userId(PERSONAL_OWNER).title("通院")
                .startAt(LocalDateTime.of(2030, 4, 1, 10, 0)).endAt(LocalDateTime.of(2030, 4, 1, 12, 0))
                .allDay(false).eventType(EventType.MEETING)
                .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED).attendanceRequired(false)
                .commentOption(CommentOption.OPTIONAL).isException(false).createdBy(PERSONAL_OWNER)
                .build();
    }

    /** BusinessException ならそのコード、例外なしなら "許可"、それ以外の例外ならその型名を返す（赤の理由を明示するため）。 */
    private static Object outcome(ThrowingCallable call) {
        try {
            call.call();
            return "許可";
        } catch (BusinessException e) {
            return e.getErrorCode();
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    @Test
    @DisplayName("A(checkScheduleManagementAccess): チーム予定の管理操作は運営アカウントに 403 COMMON_002（部外者と同じ）")
    void management_platformIsDenied() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));

        assertThat(outcome(() -> scheduleService.checkScheduleManagementAccess(SCHEDULE_ID, OUTSIDER)))
                .isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(outcome(() -> scheduleService.checkScheduleManagementAccess(SCHEDULE_ID, PLATFORM)))
                .as("運営アカウントがテナントの予定を編集・取消・複製できてはならない")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("A(deleteSchedule 経路: checkScheduleManagementAccess + checkMemberScheduleDeleteAccess): 運営アカウントはチーム予定を削除できず 403、予定は保存されない")
    void delete_platformIsDenied() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));

        assertThat(outcome(() -> scheduleService.deleteSchedule(SCHEDULE_ID, "THIS_ONLY", PLATFORM)))
                .isEqualTo(CommonErrorCode.COMMON_002);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("A(checkScopeAdminAccess): 出欠管理などのスコープ管理操作は運営アカウントに 403 COMMON_002")
    void scopeAdmin_platformIsDenied() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));

        assertThat(outcome(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, PLATFORM)))
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("A(checkScopeAdminAccess): 他人の PERSONAL 予定は運営アカウントにも 403 COMMON_002（本人以外と同じ）")
    void scopeAdmin_otherPersonal_platformIsDenied() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(personalSchedule()));

        assertThat(outcome(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, OUTSIDER)))
                .isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(outcome(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, PLATFORM)))
                .as("運営アカウントが他人の個人予定を操作できてはならない")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("A(checkScopeViewAccess): 出欠一覧・出欠 CSV の閲覧は運営アカウントに 403 COMMON_002（部外者と同じ）")
    void scopeView_platformIsDenied() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));

        assertThat(outcome(() -> scheduleService.checkScopeViewAccess(SCHEDULE_ID, OUTSIDER)))
                .isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(outcome(() -> scheduleService.checkScopeViewAccess(SCHEDULE_ID, PLATFORM)))
                .as("運営アカウントがテナントの出欠（個人の回答）を読めてはならない")
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("A(checkCreateScopeAccess): チーム予定の作成は運営アカウントに 403 COMMON_002。作成は行われない")
    void create_platformIsDenied() {
        // 開始＞終了の不正な日時にしておく。認可を通ってしまうと INVALID_DATE_RANGE になるので、
        // COMMON_002 が返れば「認可で止まった」ことがわかる（部外者と同じ順序）。
        java.time.OffsetDateTime start = java.time.OffsetDateTime.of(2030, 4, 1, 12, 0, 0, 0, java.time.ZoneOffset.ofHours(9));
        java.time.OffsetDateTime end = start.minusHours(2);
        CreateScheduleRequest req = new CreateScheduleRequest(
                "練習", null, null, start, end, false, "PRACTICE", null, null, null,
                false, null, null, null, null, null, null, null, null, null, false, false);

        assertThat(outcome(() -> scheduleService.createSchedule(req, TEAM_ID, "TEAM", OUTSIDER)))
                .isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(outcome(() -> scheduleService.createSchedule(req, TEAM_ID, "TEAM", PLATFORM)))
                .as("認可で止まる（運営アカウントが認可を通って作成処理へ進んではならない）")
                .isEqualTo(CommonErrorCode.COMMON_002);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("回帰: チーム ADMIN は管理・出欠閲覧・スコープ管理が通る。PERSONAL 予定は本人だけが通る")
    void regression_admin_and_personalOwner() {
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(teamSchedule()));
        assertThatCode(() -> scheduleService.checkScheduleManagementAccess(SCHEDULE_ID, TEAM_ADMIN)).doesNotThrowAnyException();
        assertThatCode(() -> scheduleService.checkScopeViewAccess(SCHEDULE_ID, TEAM_ADMIN)).doesNotThrowAnyException();
        assertThatCode(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, TEAM_ADMIN)).doesNotThrowAnyException();

        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(personalSchedule()));
        assertThatCode(() -> scheduleService.checkScopeAdminAccess(SCHEDULE_ID, PERSONAL_OWNER)).doesNotThrowAnyException();
        assertThatCode(() -> scheduleService.checkScheduleManagementAccess(SCHEDULE_ID, PERSONAL_OWNER))
                .as("PERSONAL 予定の管理は本人なら通る（checkScopeAdminAccess の所有者一致）")
                .doesNotThrowAnyException();
        assertThat(outcome(() -> scheduleService.checkScheduleManagementAccess(SCHEDULE_ID, OUTSIDER)))
                .isEqualTo(CommonErrorCode.COMMON_002);
    }
}
