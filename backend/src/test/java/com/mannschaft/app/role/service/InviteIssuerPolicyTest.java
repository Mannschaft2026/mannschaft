package com.mannschaft.app.role.service;

import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 招待発行権限の判定と再招待要請の通知先解決（CMP-261107-1833 AC9・設計 §9-2）。
 *
 * <p>判定は MembershipInviteService の canIssueWithRole と同一（ADMIN、または INVITE_MEMBERS 権限を持つ DEPUTY_ADMIN）。
 * 通知先は RoleService.getAdminUserIdsBy{Team|Organization}Id をこの判定で絞る。</p>
 */
@DisplayName("InviteIssuerPolicy / InviteReissueRecipientResolver（CMP-261107-1833 AC9）")
class InviteIssuerPolicyTest {

    @ExtendWith(MockitoExtension.class)
    @org.junit.jupiter.api.Nested
    @DisplayName("InviteIssuerPolicy")
    class Policy {

        @Mock private AccessControlService accessControlService;
        @InjectMocks private InviteIssuerPolicy policy;

        @Test
        @DisplayName("AC9: ADMIN は権限照会なしで発行可")
        void admin() {
            assertThat(policy.canIssueWithRole(1L, 70L, "TEAM", "ADMIN")).isTrue();
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("AC9: DEPUTY_ADMIN は INVITE_MEMBERS 権限があるときだけ発行可")
        void deputy() {
            given(accessControlService.hasPermission(2L, 70L, "TEAM", "INVITE_MEMBERS")).willReturn(true);
            given(accessControlService.hasPermission(3L, 70L, "TEAM", "INVITE_MEMBERS")).willReturn(false);

            assertThat(policy.canIssueWithRole(2L, 70L, "TEAM", "DEPUTY_ADMIN")).isTrue();
            assertThat(policy.canIssueWithRole(3L, 70L, "TEAM", "DEPUTY_ADMIN")).isFalse();
        }

        @Test
        @DisplayName("AC9: MEMBER・SUPPORTER・ロールなしは発行不可（権限照会もしない）")
        void others() {
            assertThat(policy.canIssueWithRole(4L, 70L, "TEAM", "MEMBER")).isFalse();
            assertThat(policy.canIssueWithRole(5L, 70L, "TEAM", "SUPPORTER")).isFalse();
            assertThat(policy.canIssueWithRole(6L, 70L, "TEAM", null)).isFalse();
            verify(accessControlService, never()).hasPermission(anyLong(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("AC9: canIssue は現在のロール名を引いて同じ判定をする")
        void canIssueResolvesRole() {
            given(accessControlService.getRoleName(1L, 80L, "ORGANIZATION")).willReturn("ADMIN");
            given(accessControlService.getRoleName(4L, 80L, "ORGANIZATION")).willReturn("MEMBER");

            assertThat(policy.canIssue(1L, 80L, "ORGANIZATION")).isTrue();
            assertThat(policy.canIssue(4L, 80L, "ORGANIZATION")).isFalse();
        }
    }

    @ExtendWith(MockitoExtension.class)
    @org.junit.jupiter.api.Nested
    @DisplayName("InviteReissueRecipientResolver")
    class Resolver {

        @Mock private RoleService roleService;
        @Mock private InviteIssuerPolicy policy;
        @InjectMocks private InviteReissueRecipientResolver resolver;

        @Test
        @DisplayName("AC9: チームは getAdminUserIdsByTeamId を招待権限で絞る（権限なし DEPUTY は除く）")
        void team() {
            given(roleService.getAdminUserIdsByTeamId(70L)).willReturn(List.of(1L, 2L, 3L));
            given(policy.canIssue(1L, 70L, "TEAM")).willReturn(true);
            given(policy.canIssue(2L, 70L, "TEAM")).willReturn(true);
            given(policy.canIssue(3L, 70L, "TEAM")).willReturn(false);

            assertThat(resolver.findInviterUserIds("TEAM", 70L)).containsExactly(1L, 2L);
            verify(roleService, never()).getAdminUserIdsByOrganizationId(anyLong());
        }

        @Test
        @DisplayName("AC9: 組織は getAdminUserIdsByOrganizationId を招待権限で絞る（他テナントは引かない）")
        void organization() {
            given(roleService.getAdminUserIdsByOrganizationId(80L)).willReturn(List.of(7L));
            given(policy.canIssue(7L, 80L, "ORGANIZATION")).willReturn(true);

            assertThat(resolver.findInviterUserIds("ORGANIZATION", 80L)).containsExactly(7L);
            verify(roleService, never()).getAdminUserIdsByTeamId(anyLong());
        }
    }

    @Test
    @DisplayName("AC11(§9-2): InviteIssuerPolicy・InviteReissueRecipientResolver は @Transactional を持たない")
    void noTransactional() {
        for (Class<?> type : List.of(InviteIssuerPolicy.class, InviteReissueRecipientResolver.class)) {
            assertThat(type.isAnnotationPresent(Transactional.class)).as(type.getSimpleName()).isFalse();
            assertThat(Arrays.stream(type.getDeclaredMethods()).filter(m -> m.isAnnotationPresent(Transactional.class))
                    .map(Method::getName)).as(type.getSimpleName()).isEmpty();
        }
    }
}
