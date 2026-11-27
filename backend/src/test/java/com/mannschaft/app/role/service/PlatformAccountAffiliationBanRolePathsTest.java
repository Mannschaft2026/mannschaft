package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.auth.service.UserRowLockService.UserState;
import com.mannschaft.app.chat.service.ChatMembershipInviteCardService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.dto.MembershipInviteRequest;
import com.mannschaft.app.role.dto.RoleChangeRequest;
import com.mannschaft.app.role.dto.TransferOwnershipOfferCreateRequest;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.OwnershipTransferOfferRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
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

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 戦役 Z-A PR2 試練: role ドメインの所属行生成経路が運営アカウントを拒否する（P1/P2/P5）。
 *
 * <p>各経路は {@link PlatformAccountAffiliationPolicy#assertAffiliatable} を<b>所属行・宛先付き申込行を
 * 保存する前</b>に呼び、運営アカウントなら {@code MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN} で止める。
 * Policy は mock で「運営アカウント {@link #PLATFORM_USER_ID} なら拒否」とし、各サービスが
 * Policy を呼ばずに保存へ進むと red になる。</p>
 *
 * <p>実装への約束: Policy 呼び出しは各メソッドの冒頭（ユーザー行ロックの直後まで）に置き、
 * スコープ存在確認などの後段の検査より前で拒否する（後段は mock の既定値で通る保証が無い）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: role ドメインの経路（戦役Z-A PR2 / P1・P2・P5）")
class PlatformAccountAffiliationBanRolePathsTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final Long OPERATOR_USER_ID = 201L;
    static final Long SCOPE_ID = 11L;

    static BusinessException platformForbidden() {
        return new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    static void assertPlatformForbidden(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    static void stubPlatformAccount(PlatformAccountAffiliationPolicy policy) {
        doThrow(platformForbidden()).when(policy).assertAffiliatable(PLATFORM_USER_ID);
        lenient().when(policy.isPlatformAccount(PLATFORM_USER_ID)).thenReturn(true);
    }

    static void stubUsersActive(UserRowLockService lock) {
        lenient().when(lock.lock(anyLong())).thenReturn(UserState.ACTIVE);
        lenient().when(lock.lockAll(any(Long[].class))).thenAnswer(inv -> {
            Object[] raw = inv.getArguments();
            java.util.HashMap<Long, UserState> m = new java.util.HashMap<>();
            for (Object o : raw) {
                if (o instanceof Long l) {
                    m.put(l, UserState.ACTIVE);
                } else if (o instanceof Long[] arr) {
                    for (Long l : arr) {
                        m.put(l, UserState.ACTIVE);
                    }
                }
            }
            return Map.copyOf(m);
        });
    }

    @Nested
    @DisplayName("合流点 MembershipGrantService（参加申請承認・招待承諾・ロール招待）")
    class GrantMergePoint {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock UserRoleRepository userRoleRepository;
        @Mock MembershipService membershipService;
        @Mock UserRowLockService userRowLockService;
        @InjectMocks MembershipGrantService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            stubUsersActive(userRowLockService);
        }

        @Test
        @DisplayName("P1: grantMemberRole（参加申請の承認）で運営アカウントは 403・所属行を作らない")
        void P1_grantMemberRoleは運営アカウントを拒否する() {
            assertPlatformForbidden(() -> service.grantMemberRole(
                    "TEAM", SCOPE_ID, PLATFORM_USER_ID, OPERATOR_USER_ID, "JOIN_REQUEST"));
            verify(userRoleRepository, never()).save(any());
            verifyNoInteractions(membershipService);
        }

        @Test
        @DisplayName("P1: grantRole（招待の受諾・ロール付与）で運営アカウントは 403・所属行を作らない")
        void P1_grantRoleは運営アカウントを拒否する() {
            assertPlatformForbidden(() -> service.grantRole(
                    "ORGANIZATION", SCOPE_ID, PLATFORM_USER_ID, null, OPERATOR_USER_ID, "INVITE_TOKEN"));
            verify(userRoleRepository, never()).save(any());
            verifyNoInteractions(membershipService);
        }

        @Test
        @DisplayName("P5: grantMemberRole は一般利用者でも Policy を通る")
        void P5_grantMemberRoleは一般利用者でもPolicyを通る() {
            catchThrowable(() -> service.grantMemberRole(
                    "TEAM", SCOPE_ID, GENERAL_USER_ID, OPERATOR_USER_ID, "JOIN_REQUEST"));
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
        }

        @Test
        @DisplayName("P5: grantRole は一般利用者でも Policy を通る")
        void P5_grantRoleは一般利用者でもPolicyを通る() {
            catchThrowable(() -> service.grantRole(
                    "TEAM", SCOPE_ID, GENERAL_USER_ID, null, OPERATOR_USER_ID, "INVITE_TOKEN"));
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
        }
    }

    @Nested
    @DisplayName("RoleService（ロール付与・変更・オーナー移管の受け手）")
    class RoleServicePaths {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock UserRoleRepository userRoleRepository;
        @Mock MembershipService membershipService;
        @Mock UserRowLockService userRowLockService;
        @Mock RoleRepository roleRepository;
        @InjectMocks RoleService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            stubUsersActive(userRowLockService);
            // 操作者は当該チームの ADMIN（認可は通す。拒否理由を所属禁止だけに絞る）。
            UserRoleEntity operatorAdmin = UserRoleEntity.builder()
                    .userId(OPERATOR_USER_ID).roleId(2L).teamId(SCOPE_ID).build();
            given(userRoleRepository.isActiveUser(OPERATOR_USER_ID)).willReturn(true);
            given(userRoleRepository.findByUserIdAndTeamId(OPERATOR_USER_ID, SCOPE_ID))
                    .willReturn(Optional.of(operatorAdmin));
            RoleEntity admin = org.mockito.Mockito.mock(RoleEntity.class);
            given(admin.getName()).willReturn("ADMIN");
            given(roleRepository.findById(2L)).willReturn(Optional.of(admin));
            RoleEntity deputy = org.mockito.Mockito.mock(RoleEntity.class);
            given(deputy.getName()).willReturn("DEPUTY_ADMIN");
            given(deputy.getId()).willReturn(3L);
            given(roleRepository.findById(3L)).willReturn(Optional.of(deputy));
        }

        @Test
        @DisplayName("P1: assignRole の付与先が運営アカウントなら 403・user_roles を作らない")
        void P1_assignRoleは運営アカウントを拒否する() {
            assertPlatformForbidden(() -> service.assignRole(
                    SCOPE_ID, "TEAM", PLATFORM_USER_ID, 3L, OPERATOR_USER_ID));
            verify(userRoleRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1: changeRole の対象が運営アカウントなら 403・user_roles を作らない")
        void P1_changeRoleは運営アカウントを拒否する() {
            assertPlatformForbidden(() -> service.changeRole(
                    SCOPE_ID, "TEAM", PLATFORM_USER_ID, new RoleChangeRequest(3L), OPERATOR_USER_ID));
            verify(userRoleRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1: transferOwnership の譲渡先が運営アカウントなら 403・user_roles を作らない")
        void P1_transferOwnershipは運営アカウントへの移管を拒否する() {
            assertPlatformForbidden(() -> service.transferOwnership(
                    SCOPE_ID, "ORGANIZATION", OPERATOR_USER_ID, PLATFORM_USER_ID));
            verify(userRoleRepository, never()).save(any());
        }

        @Test
        @DisplayName("P5: assignRole は一般利用者でも付与先を Policy に通す")
        void P5_assignRoleは付与先をPolicyに通す() {
            catchThrowable(() -> service.assignRole(SCOPE_ID, "TEAM", GENERAL_USER_ID, 3L, OPERATOR_USER_ID));
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
        }
    }

    @Nested
    @DisplayName("AdminRoleMutationLockService.grantAdminRole（プロビジョニング招待の受諾者）")
    class AdminGrant {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock UserRoleRepository userRoleRepository;
        @Mock UserRowLockService userRowLockService;
        @InjectMocks AdminRoleMutationLockService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1: grantAdminRole の付与先が運営アカウントなら 403・user_roles を作らない")
        void P1_grantAdminRoleは運営アカウントを拒否する() {
            assertPlatformForbidden(() -> service.grantAdminRole(PLATFORM_USER_ID, 2L, SCOPE_ID, null));
            verify(userRoleRepository, never()).save(any());
        }

        @Test
        @DisplayName("P4/P5: grantAdminRole は一般利用者なら Policy を通ったうえで従来どおり保存する")
        void P4_grantAdminRoleは一般利用者なら保存する() {
            service.grantAdminRole(GENERAL_USER_ID, 2L, SCOPE_ID, null);
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
            verify(userRoleRepository).save(any());
        }
    }

    @Nested
    @DisplayName("RoleSuccessionService.forceAssignInitialAdminOnUnarchive（強制アーカイブ解除の指定）")
    class ForceUnarchive {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock UserRoleRepository userRoleRepository;
        @Mock UserRowLockService userRowLockService;
        @Mock MembershipService membershipService;
        @InjectMocks RoleSuccessionService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            stubUsersActive(userRowLockService);
        }

        @Test
        @DisplayName("P1: 強制アーカイブ解除で運営アカウントを初期 ADMIN に指定すると 403・user_roles を作らない")
        void P1_強制アーカイブ解除の指定先が運営アカウントなら拒否する() {
            assertPlatformForbidden(() -> service.forceAssignInitialAdminOnUnarchive(
                    SCOPE_ID, "TEAM", PLATFORM_USER_ID, OPERATOR_USER_ID));
            verify(userRoleRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("OwnershipTransferOfferService（オーナー移管の申し出・受諾）")
    class OwnershipOffer {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock OwnershipTransferOfferRepository offerRepository;
        @Mock UserRowLockService userRowLockService;
        @Mock AccessControlService accessControlService;
        @InjectMocks OwnershipTransferOfferService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            stubUsersActive(userRowLockService);
            given(accessControlService.isAdmin(OPERATOR_USER_ID, SCOPE_ID, "TEAM")).willReturn(true);
        }

        @Test
        @DisplayName("P2: 認可の無い者には、宛先が運営アカウントでも認可エラーが先に返る（宛先の実在を漏らさない）")
        void P2_認可の無い者には宛先判定より認可エラーが先に返る() {
            assertThatThrownBy(() -> service.createOffer(
                    SCOPE_ID, "TEAM", "team-slug",
                    new TransferOwnershipOfferCreateRequest(PLATFORM_USER_ID), GENERAL_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(policy, never()).assertAffiliatable(any());
        }

        @Test
        @DisplayName("P2: 運営アカウント宛ての移管申し出は作成時点で 403・申し出行を作らない")
        void P2_運営アカウント宛ての移管申し出は作成時点で拒否する() {
            assertPlatformForbidden(() -> service.createOffer(
                    SCOPE_ID, "TEAM", "team-slug",
                    new TransferOwnershipOfferCreateRequest(PLATFORM_USER_ID), OPERATOR_USER_ID));
            verify(offerRepository, never()).save(any());
            verify(offerRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("P1: 運営アカウントによる移管申し出の受諾は 403")
        void P1_運営アカウントは移管申し出を受諾できない() {
            assertPlatformForbidden(() -> service.acceptOffer(
                    SCOPE_ID, "TEAM", UUID.randomUUID(), PLATFORM_USER_ID));
        }
    }

    @Nested
    @DisplayName("InviteAcceptanceFacade（招待リンク・承諾型招待の受諾）")
    class InviteAcceptance {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock MembershipGrantService membershipGrantService;
        @Mock UserRowLockService userRowLockService;
        @InjectMocks InviteAcceptanceFacade facade;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            stubUsersActive(userRowLockService);
        }

        @Test
        @DisplayName("P1: 運営アカウントによる招待リンクの受諾は 403・付与に進まない")
        void P1_運営アカウントは招待リンクを受諾できない() {
            assertPlatformForbidden(() -> facade.joinByInvite("plain-token", PLATFORM_USER_ID));
            verifyNoInteractions(membershipGrantService);
        }

        @Test
        @DisplayName("P1: 運営アカウントによる承諾型招待（ID 指定）の受諾は 403・付与に進まない")
        void P1_運営アカウントは承諾型招待を受諾できない() {
            assertPlatformForbidden(() -> facade.acceptInvitationById(77L, PLATFORM_USER_ID, null));
            verifyNoInteractions(membershipGrantService);
        }
    }

    @Nested
    @DisplayName("MembershipInviteService（DM の承諾型招待の発行）")
    class MembershipInviteIssue {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock ChatMembershipInviteCardService cardService;
        @Mock MembershipInviteTokenIssuer tokenIssuer;
        @Mock InviteIssuerPolicy inviteIssuerPolicy;
        @Mock RoleRepository roleRepository;
        @Mock TeamService teamService;
        @InjectMocks MembershipInviteService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            given(cardService.resolveDmCounterpart(5L, OPERATOR_USER_ID)).willReturn(PLATFORM_USER_ID);
            given(cardService.resolveDmCounterpart(5L, GENERAL_USER_ID)).willReturn(PLATFORM_USER_ID);
            RoleEntity memberRole = org.mockito.Mockito.mock(RoleEntity.class);
            given(memberRole.getId()).willReturn(3L);
            given(roleRepository.findByName("MEMBER")).willReturn(Optional.of(memberRole));
            given(teamService.findTeamSummary(SCOPE_ID))
                    .willReturn(Optional.of(new TeamService.TeamSummary("チーム", false)));
            given(inviteIssuerPolicy.canIssue(OPERATOR_USER_ID, SCOPE_ID, "TEAM")).willReturn(true);
        }

        @Test
        @DisplayName("P2: 認可の無い者には、宛先が運営アカウントでも認可エラーが先に返る（宛先の実在を漏らさない）")
        void P2_認可の無い者には宛先判定より認可エラーが先に返る() {
            assertThatThrownBy(() -> service.issueMembershipInvite(
                    5L, new MembershipInviteRequest("TEAM", SCOPE_ID, null, null), GENERAL_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isNotEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
            verify(policy, never()).assertAffiliatable(any());
            verifyNoInteractions(tokenIssuer);
        }

        @Test
        @DisplayName("P2: 宛先が運営アカウントの承諾型招待は発行時点で 403・トークンを作らない")
        void P2_運営アカウント宛ての招待は発行時点で拒否する() {
            assertPlatformForbidden(() -> service.issueMembershipInvite(
                    5L, new MembershipInviteRequest("TEAM", SCOPE_ID, null, null), OPERATOR_USER_ID));
            verifyNoInteractions(tokenIssuer);
        }
    }
}
