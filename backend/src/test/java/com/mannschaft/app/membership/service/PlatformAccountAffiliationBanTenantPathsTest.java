package com.mannschaft.app.membership.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.duplicatename.DuplicateNameGuardService;
import com.mannschaft.app.joinrequest.repository.JoinRequestRepository;
import com.mannschaft.app.joinrequest.service.JoinRequestService;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.provisioning.service.ProvisioningGate;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.general;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.judged;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.platform;
import com.mannschaft.app.supporter.repository.SupporterApplicationRepository;
import com.mannschaft.app.supporter.service.SupporterService;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.service.TeamService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 戦役 Z-A PR2 試練: memberships 合流点・チーム/組織の作成・参加申請・サポーター（フォロー）の
 * 経路が運営アカウントを拒否する（P1/P2/P3/P5）。
 *
 * <p>各サービスは取引の中で、Controller が取引の外で済ませた判定結果（{@code PlatformAccountJudgedFixture} で置く）
 * を確かめる。運営アカウント {@link #PLATFORM_USER_ID} と判定された利用者を拒否せずに保存へ進むと red になる。
 * 判定結果の確認は各メソッドの冒頭に置くこと（D-3T のため判定そのものは取引の外）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: memberships・チーム/組織・参加申請・サポーターの経路（戦役Z-A PR2）")
class PlatformAccountAffiliationBanTenantPathsTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final Long SCOPE_ID = 11L;

    static void assertPlatformForbidden(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    static void stubPlatformAccount(PlatformAccountAffiliationPolicy policy) {
        doThrow(new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN))
                .when(policy).assertAffiliatable(PLATFORM_USER_ID);
    }

    static MembershipCreateRequest joinRequest(Long userId, RoleKind roleKind) {
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType(ScopeType.TEAM);
        req.setScopeId(SCOPE_ID);
        req.setRoleKind(roleKind);
        req.setSource("TEST");
        return req;
    }

    @Nested
    @DisplayName("合流点 MembershipService.join（Org/Team/Supporter/JoinRequest/Invite/RoleService の入会）")
    class JoinMergePoint {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock MembershipRepository membershipRepository;
        @Mock UserRowLockService userRowLockService;
        @InjectMocks MembershipService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1: 運営アカウントの MEMBER 入会は 403・memberships を作らない")
        void P1_運営アカウントのMEMBER入会を拒否する() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.join(joinRequest(PLATFORM_USER_ID, RoleKind.MEMBER))));
            verify(membershipRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1: 運営アカウントの SUPPORTER 入会（フォロー）も 403・memberships を作らない")
        void P1_運営アカウントのSUPPORTER入会を拒否する() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.join(joinRequest(PLATFORM_USER_ID, RoleKind.SUPPORTER))));
            verify(membershipRepository, never()).save(any());
        }

        @Test
        @DisplayName("P5: 一般利用者の入会も Policy の判定を通る（取引の外の判定が無ければ判定漏れとして止め、所属行を作らない）")
        void P5_一般利用者の入会もPolicyを通る() {
            assertThatThrownBy(() -> service.join(joinRequest(GENERAL_USER_ID, RoleKind.MEMBER)))
                    .isInstanceOf(IllegalStateException.class);
            verify(membershipRepository, never()).save(any());

            // 一般利用者と判定されていれば所属禁止の判定は素通りする（後段の検証で落ちても、判定漏れ・403 ではない）。
            Throwable thrown = catchThrowable(() -> judged(general(GENERAL_USER_ID),
                    () -> service.join(joinRequest(GENERAL_USER_ID, RoleKind.MEMBER))));
            assertThat(thrown).isNotInstanceOf(IllegalStateException.class);
            if (thrown instanceof BusinessException be) {
                assertThat(be.getErrorCode()).isNotEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
            }
        }
    }

    @Nested
    @DisplayName("TeamService（チームの作成・プロビジョニング作成）")
    class TeamPaths {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock UserRoleRepository userRoleRepository;
        @Mock MembershipService membershipService;
        @Mock AdminRoleMutationLockService adminRoleMutationLockService;
        @Mock DuplicateNameGuardService duplicateNameGuardService;
        @InjectMocks TeamService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1: 運営アカウントによるチーム作成は 403・user_roles / memberships を作らない")
        void P1_運営アカウントはチームを作成できない() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.createTeam(PLATFORM_USER_ID, mock(CreateTeamRequest.class))));
            verify(userRoleRepository, never()).save(any());
            verifyNoInteractions(membershipService);
        }

        @Test
        @DisplayName("P3: 運営によるプロビジョニング作成（createProvisionedTeam）は所属禁止の対象外")
        void P3_プロビジョニング作成は対象外() {
            Throwable thrown = catchThrowable(() -> service.createProvisionedTeam(
                    "販促チーム", "promo-team", PLATFORM_USER_ID, false, null));
            // 判定結果を置かずに呼んでも例外にならない（所属禁止の判定に掛からない）。
            assertThat(thrown).isNull();
        }
    }

    @Nested
    @DisplayName("OrganizationService（組織の作成・プロビジョニング作成）")
    class OrganizationPaths {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock UserRoleRepository userRoleRepository;
        @Mock MembershipService membershipService;
        @Mock AdminRoleMutationLockService adminRoleMutationLockService;
        @Mock DuplicateNameGuardService duplicateNameGuardService;
        @InjectMocks OrganizationService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1: 運営アカウントによる組織作成は 403・user_roles / memberships を作らない")
        void P1_運営アカウントは組織を作成できない() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID), () -> service.createOrganization(
                    PLATFORM_USER_ID, mock(CreateOrganizationRequest.class))));
            verify(userRoleRepository, never()).save(any());
            verifyNoInteractions(membershipService);
        }

        @Test
        @DisplayName("P3: 運営によるプロビジョニング作成（createProvisionedOrganization）は所属禁止の対象外")
        void P3_プロビジョニング作成は対象外() {
            Throwable thrown = catchThrowable(() -> service.createProvisionedOrganization(
                    "販促組織", "promo-org", PLATFORM_USER_ID, false, null));
            // 判定結果を置かずに呼んでも例外にならない（所属禁止の判定に掛からない）。
            assertThat(thrown).isNull();
        }
    }

    @Nested
    @DisplayName("JoinRequestService（チーム/組織への参加申請の作成）")
    class JoinRequestCreate {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock JoinRequestRepository joinRequestRepository;
        @Mock MembershipGrantService membershipGrantService;
        @InjectMocks JoinRequestService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P2: 運営アカウントの参加申請は作成時点で 403・申請行を作らない")
        void P2_運営アカウントの参加申請は作成時点で拒否する() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.createRequest("TEAM", SCOPE_ID, PLATFORM_USER_ID, null)));
            verify(joinRequestRepository, never()).save(any());
            verify(joinRequestRepository, never()).saveAndFlush(any());
            verifyNoInteractions(membershipGrantService);
        }
    }

    @Nested
    @DisplayName("SupporterService（サポーター＝フォローの登録・申請）")
    class SupporterFollow {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock SupporterApplicationRepository applicationRepository;
        @Mock MembershipService membershipService;
        @Mock MembershipRepository membershipRepository;
        @Mock ProvisioningGate provisioningGate;
        @InjectMocks SupporterService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1/P2: 運営アカウントのサポーター登録（即時承認・手動承認とも）は 403・所属行も申請行も作らない")
        void P1_運営アカウントはサポーター登録できない() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.follow(PLATFORM_USER_ID, "TEAM", SCOPE_ID)));
            verify(applicationRepository, never()).save(any());
            verify(membershipService, never()).join(any());
        }

        @Test
        @DisplayName("P5: 一般利用者のサポーター登録も Policy の判定を通る（判定が無ければ止め、所属行も申請行も作らない）")
        void P5_一般利用者のサポーター登録もPolicyを通る() {
            assertThatThrownBy(() -> service.follow(GENERAL_USER_ID, "ORGANIZATION", SCOPE_ID))
                    .isInstanceOf(IllegalStateException.class);
            verify(applicationRepository, never()).save(any());
            verify(membershipService, never()).join(any());
        }
    }
}
