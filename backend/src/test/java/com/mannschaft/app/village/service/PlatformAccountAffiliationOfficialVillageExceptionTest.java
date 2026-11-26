package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageCreationRequestCreateRequest;
import com.mannschaft.app.village.dto.VillageCreationRequestResponse;
import com.mannschaft.app.village.entity.VillageCreationRequestEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRequestStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.repository.VillageCreationRequestRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageUserAdmissionLockRepository;
import jakarta.persistence.EntityManager;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2: 運営アカウントの所属禁止の<b>唯一の例外</b>（マスター決定 2026-11-26）の範囲を、
 * 本物の {@link PlatformAccountAffiliationPolicy} と本物の入村枠サービスで固定する。
 *
 * <p>公式村（OFFICIAL）の作成者としての村長所属だけを許す。通常村の作成申請・他経路
 * （招待受諾・参加申請・申請の承認で使う通常の admit）での所属は引き続き 403。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 公式村の作成者の例外（戦役Z-A PR2）")
class PlatformAccountAffiliationOfficialVillageExceptionTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final UUID VILLAGE_ID = UUID.fromString("0190aaaa-0000-7000-8000-000000000001");

    @Mock VillageUserAdmissionLockRepository admissionLockRepository;
    @Mock VillageMembershipRepository membershipRepository;
    @Mock VillageCreationRequestRepository requestRepository;
    @Mock VillageRepository villageRepository;
    @Mock EntityManager entityManager;
    @Mock AccessControlService accessControlService;
    @Mock UserRoleRepository userRoleRepository;

    VillageMembershipSlotService slots;
    VillageCreationRequestService service;

    @BeforeEach
    void setUp() {
        PlatformAccountAffiliationPolicy policy = new PlatformAccountAffiliationPolicy(userRoleRepository);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        slots = new VillageMembershipSlotService(
                admissionLockRepository, membershipRepository, new VillageParticipationLimitProvider(null), policy);
        service = new VillageCreationRequestService(
                requestRepository, villageRepository, membershipRepository, slots, entityManager,
                accessControlService, policy);
        given(requestRepository.countByRequesterUserIdAndCreatedAtAfter(eq(PLATFORM_USER_ID), any())).willReturn(0L);
        given(villageRepository.existsBySlug(any())).willReturn(false);
        given(requestRepository.save(any())).willAnswer(inv -> {
            VillageCreationRequestEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            }
            e.setCreatedAt(LocalDateTime.now());
            return e;
        });
        given(villageRepository.save(any())).willAnswer(inv -> {
            VillageEntity v = inv.getArgument(0);
            ReflectionTestUtils.setField(v, "id", UUID.randomUUID());
            return v;
        });
        given(membershipRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
    }

    private static VillageCreationRequestCreateRequest request(VillageType type) {
        return new VillageCreationRequestCreateRequest(
                "公式村", "official-village", "公式", "運営が認定する公式村",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5), type, null);
    }

    private static VillageMembershipEntity userMembership(VillageRole role) {
        VillageMembershipEntity m = new VillageMembershipEntity();
        m.setVillageId(VILLAGE_ID);
        m.setSubjectType(VillageSubjectType.USER);
        m.setSubjectId(PLATFORM_USER_ID);
        m.setRole(role);
        return m;
    }

    private static void assertPlatformForbidden(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    @Test
    @DisplayName("運営アカウントは公式村（OFFICIAL）を作成でき、作成者として村長に所属する")
    void 運営アカウントは公式村を作成し村長になれる() {
        given(accessControlService.isSystemAdmin(PLATFORM_USER_ID)).willReturn(true);

        VillageCreationRequestResponse res = service.createRequest(PLATFORM_USER_ID, request(VillageType.OFFICIAL));

        assertThat(res.status()).isEqualTo(VillageRequestStatus.APPROVED);
        ArgumentCaptor<VillageEntity> village = ArgumentCaptor.forClass(VillageEntity.class);
        verify(villageRepository).save(village.capture());
        assertThat(village.getValue().getType()).isEqualTo(VillageType.OFFICIAL);
        assertThat(village.getValue().getCreatedByUserId()).isEqualTo(PLATFORM_USER_ID);
        ArgumentCaptor<VillageMembershipEntity> membership = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository).save(membership.capture());
        assertThat(membership.getValue().getSubjectId()).isEqualTo(PLATFORM_USER_ID);
        assertThat(membership.getValue().getRole()).isEqualTo(VillageRole.HEADMAN);
    }

    @Test
    @DisplayName("通常村（COMMUNITY）の作成申請は運営アカウントなら 403 のまま・村も村長行も作らない")
    void 通常村の作成申請は403のまま() {
        assertPlatformForbidden(() -> service.createRequest(PLATFORM_USER_ID, request(VillageType.COMMUNITY)));
        verify(villageRepository, never()).save(any());
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("運営の認可を満たさない運営アカウント（停止中など）の公式村申請は OFFICIAL_VILLAGE_FORBIDDEN で止まる")
    void 運営の認可を満たさなければ公式村も作れない() {
        given(accessControlService.isSystemAdmin(PLATFORM_USER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.createRequest(PLATFORM_USER_ID, request(VillageType.OFFICIAL)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(VillageErrorCode.OFFICIAL_VILLAGE_FORBIDDEN);
        verify(villageRepository, never()).save(any());
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("別経路（通常の admit・allocate）では、村長の所属行でも運営アカウントは 403（招待受諾・参加申請・承認は例外にならない）")
    void 通常のadmitでは村長でも403() {
        assertPlatformForbidden(() -> slots.admit(userMembership(VillageRole.HEADMAN)));
        assertPlatformForbidden(() -> slots.admit(userMembership(VillageRole.VILLAGER)));
        assertPlatformForbidden(() -> slots.allocate(PLATFORM_USER_ID));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("公式村の作成者の例外は村長（HEADMAN）の所属行に限る（村人の行は 403）")
    void 例外の入口でも村長以外の所属行は403() {
        assertPlatformForbidden(() -> slots.admitOfficialVillageCreator(userMembership(VillageRole.VILLAGER)));
        verify(membershipRepository, never()).save(any());
    }
}
