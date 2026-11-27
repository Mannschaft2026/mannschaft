package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: {@link ShiftAutoAssignFacade#requireRunAdminConcealed} の
 * SYSTEM_ADMIN 直書き素通し（自動割当 run の詳細・目視確認）を除去する受け入れテスト。
 *
 * <p>分類表の A 行（requireRunAdminConcealed:141）に対応する。run の詳細（割当結果＝誰がどの枠に入ったか）は、
 * 所属の無い運営アカウントに部外者と同じ不在の 404 SHIFT_024 になる。目視確認（書込）も同じ。
 * IT 側は ShiftDeletedParentConcealmentContractIT の「運営アカウント単独で run の詳細が 200」を 404 へ反転する
 * （反転一覧 flip_inventory.tsv）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: ShiftAutoAssignFacade の SYSTEM_ADMIN 直書き素通しの除去")
class ShiftAutoAssignFacadeZc1DirectBypassRemovalTest {

    @Mock private ShiftAutoAssignService autoAssignService;
    @Mock private ScopeConcealingAccessGate accessGate;
    @Mock private AccessControlService accessControlService;

    private ShiftAutoAssignFacade facade;

    private static final Long RUN_ID = 50L;
    private static final Long TEAM_ID = 10L;
    private static final Long PLATFORM = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long TEAM_ADMIN = 902L;

    @BeforeEach
    void setUp() {
        facade = new ShiftAutoAssignFacade(autoAssignService, accessGate, accessControlService);
        given(autoAssignService.resolveRunTeamId(RUN_ID, null)).willReturn(TEAM_ID);
        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(OUTSIDER)).willReturn(false);
        given(accessControlService.isSystemAdmin(TEAM_ADMIN)).willReturn(false);
        given(accessControlService.isAdminOrAbove(any(), anyLong(), anyString()))
                .willAnswer(inv -> TEAM_ADMIN.equals(inv.getArgument(0)));
    }

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
    @DisplayName("A(getAssignmentRunDetail): run の詳細は運営アカウントに不在と同一の 404 SHIFT_024（部外者と同じ）。詳細を読まない")
    void runDetail_platformIsConcealed() {
        assertThat(outcome(() -> facade.getAssignmentRunDetail(RUN_ID, OUTSIDER)))
                .isEqualTo(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        assertThat(outcome(() -> facade.getAssignmentRunDetail(RUN_ID, PLATFORM)))
                .as("運営アカウントがテナントの自動割当結果を読めてはならない")
                .isEqualTo(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        verify(autoAssignService, never()).getAssignmentRunDetail(RUN_ID);
    }

    @Test
    @DisplayName("A(confirmVisualReview): 目視確認は運営アカウントに 404 SHIFT_024、書込を呼ばない")
    void visualReview_platformIsConcealed() {
        assertThat(outcome(() -> facade.confirmVisualReview(RUN_ID, "ok", PLATFORM)))
                .isEqualTo(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        verify(autoAssignService, never()).confirmVisualReview(any(), any(), any());
    }

    @Test
    @DisplayName("回帰: チーム ADMIN は run の詳細を取得できる")
    void regression_teamAdmin() {
        assertThat(outcome(() -> facade.getAssignmentRunDetail(RUN_ID, TEAM_ADMIN))).isEqualTo("許可");
    }
}
