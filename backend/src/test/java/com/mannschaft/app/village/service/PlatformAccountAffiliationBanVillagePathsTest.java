package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.village.dto.VillageInvitationCreateRequest;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageInvitationRepository;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 戦役 Z-A PR2 試練: 村（village_memberships / 招待 / 参加申請 / 作成申請）の経路が運営アカウントを拒否する
 * （P1/P2/P5。軍議検分 Z-A (3)）。
 *
 * <p>村の主体が USER のとき、所属行の主体（= 操作者本人・宛先）を Policy に通す。
 * Policy 呼び出しはメソッドの冒頭に置くこと（招待の発行だけは、宛先の判定で運営アカウントの実在が
 * 非村人へ漏れないよう、村長・長老の認可の直後に置く）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 村の経路（戦役Z-A PR2 / P1・P2・P5）")
class PlatformAccountAffiliationBanVillagePathsTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final Long OPERATOR_USER_ID = 201L;
    static final UUID VILLAGE_ID = UUID.fromString("0190aaaa-0000-7000-8000-000000000001");

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

    static VillageMembershipEntity userMembership(Long userId, VillageRole role) {
        VillageMembershipEntity m = new VillageMembershipEntity();
        m.setVillageId(VILLAGE_ID);
        m.setSubjectType(VillageSubjectType.USER);
        m.setSubjectId(userId);
        m.setRole(role);
        return m;
    }

    @Nested
    @DisplayName("VillageMembershipService.join（自由参加の村への参加）")
    class Join {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock VillageMembershipRepository membershipRepository;
        @Mock VillageMembershipSlotService slotService;
        @InjectMocks VillageMembershipService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1: 運営アカウントの村参加は 403・village_memberships を作らない")
        void P1_運営アカウントは村に参加できない() {
            assertPlatformForbidden(() -> service.join(VILLAGE_ID, PLATFORM_USER_ID, null, null));
            verify(slotService, never()).admit(any());
            verify(membershipRepository, never()).save(any());
        }

        @Test
        @DisplayName("P5: 一般利用者の村参加も Policy を通る")
        void P5_一般利用者の村参加もPolicyを通る() {
            catchThrowable(() -> service.join(VILLAGE_ID, GENERAL_USER_ID, null, null));
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
        }
    }

    @Nested
    @DisplayName("VillageInvitationService（村への招待の発行・受諾）")
    class Invitations {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock VillageInvitationRepository invitationRepository;
        @Mock VillageMembershipRepository membershipRepository;
        @Mock VillageMembershipSlotService slotService;
        @Mock VillageAccessGate villageAccessGate;
        @Mock SecretTokenVault secretTokenVault;
        @InjectMocks VillageInvitationService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            given(membershipRepository.findActiveByVillageIdAndSubject(
                    eq(VILLAGE_ID), eq(VillageSubjectType.USER), eq(OPERATOR_USER_ID)))
                    .willReturn(Optional.of(userMembership(OPERATOR_USER_ID, VillageRole.HEADMAN)));
            given(secretTokenVault.issueBase64Url()).willReturn(new IssuedToken("plain", "hash"));
        }

        @Test
        @DisplayName("P2: 運営アカウント宛ての村招待は発行時点で 403・招待行を作らない")
        void P2_運営アカウント宛ての村招待は発行時点で拒否する() {
            assertPlatformForbidden(() -> service.issue(
                    VILLAGE_ID, OPERATOR_USER_ID, new VillageInvitationCreateRequest(1, 24, PLATFORM_USER_ID)));
            verify(invitationRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1: 運営アカウントによる村招待の受諾は 403・village_memberships を作らない")
        void P1_運営アカウントは村招待を受諾できない() {
            assertPlatformForbidden(() -> service.accept("plain-token", PLATFORM_USER_ID));
            verify(slotService, never()).admit(any());
            verify(membershipRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("VillageJoinRequestService.createRequest（承認制の村への参加申請）")
    class JoinRequests {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock VillageJoinRequestRepository joinRequestRepository;
        @Mock VillageMembershipRepository membershipRepository;
        @Mock VillageRepository villageRepository;
        @Mock VillageMembershipSlotService slotService;
        @InjectMocks VillageJoinRequestService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P2: 運営アカウントの村参加申請は作成時点で 403・申請行を作らない")
        void P2_運営アカウントの村参加申請は作成時点で拒否する() {
            assertPlatformForbidden(() -> service.createRequest(VILLAGE_ID, PLATFORM_USER_ID, null, null));
            verify(joinRequestRepository, never()).save(any());
            verify(joinRequestRepository, never()).saveAndFlush(any());
        }
    }

    @Nested
    @DisplayName("VillageCreationRequestService（村の作成申請・承認）")
    class CreationRequests {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock VillageCreationRequestRepository requestRepository;
        @Mock VillageRepository villageRepository;
        @Mock VillageMembershipRepository membershipRepository;
        @Mock VillageMembershipSlotService slotService;
        @InjectMocks VillageCreationRequestService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1/P2: 運営アカウントによる村の作成申請は 403・申請も村も村長行も作らない")
        void P1_運営アカウントは村の作成申請ができない() {
            com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest community =
                    new com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest(
                            "運営の村", "ops-village", null, "目的",
                            java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC),
                            com.mannschaft.app.village.entity.enums.VillageType.COMMUNITY, null);
            assertPlatformForbidden(() -> service.createRequest(PLATFORM_USER_ID, community));
            verify(requestRepository, never()).save(any());
            verify(villageRepository, never()).save(any());
            verify(slotService, never()).admit(any());
        }

        @Test
        @DisplayName("P1: 申請者が運営アカウントの作成申請を承認しても 403・村長行を作らない")
        void P1_申請者が運営アカウントの作成申請は承認できない() {
            UUID requestId = UUID.fromString("0190bbbb-0000-7000-8000-000000000002");
            given(requestRepository.findAdmissionRequesterId(requestId)).willReturn(Optional.of(PLATFORM_USER_ID));
            given(slotService.lockUser(PLATFORM_USER_ID)).willReturn(true);
            assertPlatformForbidden(() -> service.approve(requestId, OPERATOR_USER_ID, null));
            verify(villageRepository, never()).save(any());
            verify(slotService, never()).admit(any());
        }
    }

    @Nested
    @DisplayName("VillageMembershipSlotService（USER の入村枠の割当・所属行の保存）")
    class Slot {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock VillageMembershipRepository membershipRepository;
        @InjectMocks VillageMembershipSlotService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
        }

        @Test
        @DisplayName("P1: 運営アカウントへの入村枠の割当は 403")
        void P1_運営アカウントには入村枠を割り当てない() {
            assertPlatformForbidden(() -> service.allocate(PLATFORM_USER_ID));
        }

        @Test
        @DisplayName("P1: 運営アカウントを主体とする所属行の保存（admit）は 403・保存しない")
        void P1_運営アカウントの所属行はadmitで保存しない() {
            assertPlatformForbidden(() -> service.admit(userMembership(PLATFORM_USER_ID, VillageRole.VILLAGER)));
            verify(membershipRepository, never()).save(any());
        }

        @Test
        @DisplayName("P4/P5: 一般利用者の所属行は Policy を通ったうえで従来どおり保存する")
        void P4_一般利用者の所属行は保存する() {
            VillageMembershipEntity m = userMembership(GENERAL_USER_ID, VillageRole.VILLAGER);
            given(membershipRepository.save(m)).willReturn(m);
            service.admit(m);
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
            verify(membershipRepository).save(m);
        }
    }

    @Nested
    @DisplayName("Policy を経由しない村経路が無いこと（招待の受諾は入村枠より前に拒否）")
    class NoSideEffects {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock VillageInvitationRepository invitationRepository;
        @Mock VillageMembershipRepository membershipRepository;
        @Mock VillageMembershipSlotService slotService;
        @InjectMocks VillageInvitationService service;

        @Test
        @DisplayName("P1: 運営アカウントの招待受諾では招待の使用回数も入村ロックも消費しない")
        void P1_招待受諾は副作用の前に拒否する() {
            stubPlatformAccount(policy);
            catchThrowable(() -> service.accept("plain-token", PLATFORM_USER_ID));
            verifyNoInteractions(slotService);
        }
    }
}
