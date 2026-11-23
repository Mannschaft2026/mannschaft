package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingInviteSnapshot;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.service.InviteAcceptanceFacade;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-261107-1833 PR3 D2: 見送られた保留（REISSUE_DECLINED）の依頼者側の扱い（設計 a の状態集合）。
 *
 * <p>状態集合を分ける: REISSUABLE={EXPIRED,REISSUE_REQUESTED}（requestReissue）、
 * DISMISSIBLE={EXPIRED,REISSUE_REQUESTED,REISSUE_DECLINED}（dismiss）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PendingInviteService 見送り後の依頼者側（CMP-261107-1833 PR3 D2）")
class PendingInviteServiceReissueDeclinedTest {

    private static final Long USER_ID = 701L;

    @Mock private PendingInviteStore store;
    @Mock private InviteAcceptanceFacade facade;
    @Mock private AccessControlService accessControlService;
    @Mock private TeamService teamService;
    @Mock private OrganizationService organizationService;

    @InjectMocks private PendingInviteService service;

    private static PendingInviteSnapshot row(PendingInviteStatus status) {
        return new PendingInviteSnapshot(UUID.fromString("01938a00-0000-7000-8000-0000000000d1"), USER_ID, "h".repeat(64),
                "TEAM", 71L, status, "ROLE_002", null, null);
    }

    @Test
    @DisplayName("D2: 見送り後の再依頼は 409 AUTH_085 で、依頼は記録しない（通知もしない）")
    void requestReissueAfterDeclineIsRejected() {
        given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.REISSUE_DECLINED)));

        Throwable thrown = catchThrowable(() -> service.requestReissue(USER_ID));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo("AUTH_085");
        verify(store, never()).markReissueRequested(anyLong());
    }

    @Test
    @DisplayName("D2: 見送りの案内を閉じる（REISSUE_DECLINED→DISMISSED）。dismiss の遷移元に REISSUE_DECLINED を含める")
    void dismissAcceptsDeclined() {
        given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.REISSUE_DECLINED)));
        given(store.transition(eq(USER_ID), any(), eq(PendingInviteStatus.DISMISSED))).willAnswer(inv -> {
            Set<PendingInviteStatus> from = inv.getArgument(1);
            return from.contains(PendingInviteStatus.REISSUE_DECLINED);
        });

        service.dismiss(USER_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<PendingInviteStatus>> from = ArgumentCaptor.forClass(Set.class);
        verify(store).transition(eq(USER_ID), from.capture(), eq(PendingInviteStatus.DISMISSED));
        assertThat(from.getValue()).containsExactlyInAnyOrder(PendingInviteStatus.EXPIRED,
                PendingInviteStatus.REISSUE_REQUESTED, PendingInviteStatus.REISSUE_DECLINED);
    }

    @Test
    @DisplayName("D2: 再依頼の遷移元（REISSUABLE）は EXPIRED と REISSUE_REQUESTED だけ。EXPIRED からは依頼できる")
    void requestReissueFromExpired() {
        given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.EXPIRED)));
        given(teamService.findJoinabilitySummary(71L)).willReturn(Optional.empty());
        given(store.markReissueRequested(USER_ID)).willReturn(true);

        assertThat(service.requestReissue(USER_ID).alreadyRequested()).isFalse();
    }

    @Test
    @DisplayName("a: REISSUE_DECLINED は未終了（破棄の対象）に含めない")
    void declinedIsNotOpen() {
        assertThat(PendingInviteStore.OPEN_STATUSES).doesNotContain(PendingInviteStatus.REISSUE_DECLINED.name());
    }
}
