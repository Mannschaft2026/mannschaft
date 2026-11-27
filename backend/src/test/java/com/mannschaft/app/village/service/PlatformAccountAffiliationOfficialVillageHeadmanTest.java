package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.village.dto.RoleChangeRequest;
import com.mannschaft.app.village.dto.VillageResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.repository.VillageSearchRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2 検分指摘対応: 公式村の運営村長の扱い（本物の {@link PlatformAccountAffiliationPolicy} を使う）。
 *
 * <ul>
 *   <li>プロフィール更新: 公式村の運営村長は可、公式村でも村長でなければ 403、通常村は 403。</li>
 *   <li>ロール変更: 運営アカウントの非村長ロールへの変更（自己・他者から）は 403。</li>
 *   <li>公式村の応答の isOfficial は true（村種別を OFFICIAL で作る仕様変更の固定）。</li>
 *   <li>例外用の入村枠メソッドは package-private。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 公式村の運営村長（戦役Z-A PR2 検分指摘）")
class PlatformAccountAffiliationOfficialVillageHeadmanTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long OTHER_HEADMAN_ID = 9002L;
    static final UUID VILLAGE_ID = UUID.fromString("0190aaaa-0000-7000-8000-000000000001");

    @Mock UserRoleRepository userRoleRepository;
    @Mock VillageMembershipRepository membershipRepository;
    @Mock VillageAccessGate accessGate;
    @Mock VillageMembershipSlotService slotService;
    @Mock MediaUrlResolver mediaUrlResolver;
    @Mock AuditLogService auditLogService;
    @Mock VillageRepository villageRepository;
    @Mock VillageSearchRepository villageSearchRepository;
    @Mock UserVillagePinRepository pinRepository;
    @Mock AccessControlService accessControlService;

    PlatformAccountAffiliationPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new PlatformAccountAffiliationPolicy(userRoleRepository);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
    }

    private static VillageEntity village(VillageType type, Long createdBy) {
        VillageEntity v = VillageEntity.builder()
                .slug("v-village").name("村").type(type)
                .joinPolicy(VillageJoinPolicy.FREE).visibility(VillageVisibility.PUBLIC)
                .memberCountCache(1L).createdByUserId(createdBy).build();
        v.setId(VILLAGE_ID);
        return v;
    }

    private static VillageMembershipEntity membership(Long userId, VillageRole role) {
        VillageMembershipEntity m = new VillageMembershipEntity();
        m.setVillageId(VILLAGE_ID);
        m.setSubjectType(VillageSubjectType.USER);
        m.setSubjectId(userId);
        m.setRole(role);
        m.setId(UUID.randomUUID());
        return m;
    }

    private static void assertPlatformForbidden(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    // ---------------- プロフィール更新 ----------------

    private VillageMembershipProfileService profileService(VillageEntity v, VillageMembershipEntity m) {
        given(accessGate.loadVillageAllowingArchived(VILLAGE_ID, PLATFORM_USER_ID)).willReturn(v);
        given(membershipRepository.findActiveByVillageIdAndSubject(
                VILLAGE_ID, VillageSubjectType.USER, PLATFORM_USER_ID)).willReturn(Optional.of(m));
        return new VillageMembershipProfileService(
                villageRepository, accessGate, membershipRepository, mediaUrlResolver, auditLogService, policy);
    }

    @Test
    @DisplayName("公式村の運営村長（作成者かつ村長）は所属プロフィールの公開設定を更新できる")
    void 公式村の運営村長はプロフィール更新できる() {
        VillageMembershipProfileService s = profileService(
                village(VillageType.OFFICIAL, PLATFORM_USER_ID), membership(PLATFORM_USER_ID, VillageRole.HEADMAN));
        assertThatCode(() -> s.updateMyProfileVisibility(VILLAGE_ID, PLATFORM_USER_ID, true))
                .doesNotThrowAnyException();
        verify(membershipRepository).save(any());
    }

    @Test
    @DisplayName("公式村でも村長でない運営アカウントは 403")
    void 公式村でも村長でなければプロフィール更新は403() {
        VillageMembershipProfileService s = profileService(
                village(VillageType.OFFICIAL, PLATFORM_USER_ID), membership(PLATFORM_USER_ID, VillageRole.ELDER));
        assertPlatformForbidden(() -> s.updateMyProfileVisibility(VILLAGE_ID, PLATFORM_USER_ID, true));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("公式村でも作成者でない運営村長は 403")
    void 公式村でも作成者でなければプロフィール更新は403() {
        VillageMembershipProfileService s = profileService(
                village(VillageType.OFFICIAL, OTHER_HEADMAN_ID), membership(PLATFORM_USER_ID, VillageRole.HEADMAN));
        assertPlatformForbidden(() -> s.updateMyProfileVisibility(VILLAGE_ID, PLATFORM_USER_ID, true));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("通常村の運営アカウントは村長・作成者でも 403")
    void 通常村はプロフィール更新403() {
        VillageMembershipProfileService s = profileService(
                village(VillageType.COMMUNITY, PLATFORM_USER_ID), membership(PLATFORM_USER_ID, VillageRole.HEADMAN));
        assertPlatformForbidden(() -> s.updateMyProfileVisibility(VILLAGE_ID, PLATFORM_USER_ID, true));
        verify(membershipRepository, never()).save(any());
    }

    // ---------------- ロール変更 ----------------

    private VillageMembershipService membershipService(Long actorId, VillageMembershipEntity actor,
                                                       VillageMembershipEntity target) {
        given(membershipRepository.findActiveByVillageIdAndSubject(VILLAGE_ID, VillageSubjectType.USER, actorId))
                .willReturn(Optional.of(actor));
        given(membershipRepository.findById(target.getId())).willReturn(Optional.of(target));
        given(membershipRepository.countByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNull(
                eq(VILLAGE_ID), eq(VillageRole.HEADMAN))).willReturn(2L);
        given(membershipRepository.countByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNull(
                eq(VILLAGE_ID), eq(VillageRole.ELDER))).willReturn(1L);
        given(membershipRepository.save(any())).willAnswer(i -> i.getArgument(0));
        return new VillageMembershipService(membershipRepository, slotService, accessGate, policy);
    }

    @Test
    @DisplayName("公式村の運営村長が自分を村人・長老へ降格するのは 403（例外の範囲外の所属を残さない）")
    void 運営村長の自己降格は403() {
        VillageMembershipEntity self = membership(PLATFORM_USER_ID, VillageRole.HEADMAN);
        VillageMembershipService s = membershipService(PLATFORM_USER_ID, self, self);
        assertPlatformForbidden(() -> s.changeRole(
                VILLAGE_ID, self.getId(), PLATFORM_USER_ID, new RoleChangeRequest(VillageRole.VILLAGER)));
        assertPlatformForbidden(() -> s.changeRole(
                VILLAGE_ID, self.getId(), PLATFORM_USER_ID, new RoleChangeRequest(VillageRole.ELDER)));
        assertThat(self.getRole()).isEqualTo(VillageRole.HEADMAN);
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("他の村長が運営アカウントの長老への変更を試みても 403")
    void 他者からの運営アカウント降格も403() {
        VillageMembershipEntity actor = membership(OTHER_HEADMAN_ID, VillageRole.HEADMAN);
        VillageMembershipEntity target = membership(PLATFORM_USER_ID, VillageRole.HEADMAN);
        VillageMembershipService s = membershipService(OTHER_HEADMAN_ID, actor, target);
        assertPlatformForbidden(() -> s.changeRole(
                VILLAGE_ID, target.getId(), OTHER_HEADMAN_ID, new RoleChangeRequest(VillageRole.ELDER)));
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("運営アカウント以外の降格は従来どおり通る")
    void 一般利用者の降格は従来どおり() {
        VillageMembershipEntity actor = membership(OTHER_HEADMAN_ID, VillageRole.HEADMAN);
        VillageMembershipService s = membershipService(OTHER_HEADMAN_ID, actor, actor);
        assertThatCode(() -> s.changeRole(
                VILLAGE_ID, actor.getId(), OTHER_HEADMAN_ID, new RoleChangeRequest(VillageRole.ELDER)))
                .doesNotThrowAnyException();
        assertThat(actor.getRole()).isEqualTo(VillageRole.ELDER);
    }

    // ---------------- 応答・可視性 ----------------

    @Test
    @DisplayName("公式村（OFFICIAL）の応答は isOfficial=true（村種別を OFFICIAL で作る仕様変更の固定）")
    void 公式村の応答はisOfficialがtrue() {
        VillageEntity v = village(VillageType.OFFICIAL, PLATFORM_USER_ID);
        given(accessGate.loadReadableVillage(VILLAGE_ID, PLATFORM_USER_ID)).willReturn(v);
        VillageService vs = new VillageService(villageRepository, accessGate, villageSearchRepository,
                membershipRepository, pinRepository, accessControlService, null, mediaUrlResolver);

        VillageResponse res = vs.get(VILLAGE_ID, PLATFORM_USER_ID);

        assertThat(res.isOfficial()).isTrue();
        assertThat(res.type()).isEqualTo(VillageType.OFFICIAL);
    }

    @Test
    @DisplayName("例外用の入村枠メソッドは package-private（公式村の作成経路以外から呼べない）")
    void 例外用メソッドはpackagePrivate() throws Exception {
        for (Method m : new Method[] {
                VillageMembershipSlotService.class.getDeclaredMethod("allocateForOfficialVillageCreator", Long.class),
                VillageMembershipSlotService.class.getDeclaredMethod(
                        "admitOfficialVillageCreator", VillageMembershipEntity.class, VillageEntity.class)}) {
            assertThat(Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers()))
                    .as(m.getName()).isFalse();
        }
    }

    @Test
    @DisplayName("admitOfficialVillageCreator は村の種別・作成者の不一致を自前で拒否する")
    void admitは村の種別と作成者を自前で確認する() {
        VillageMembershipSlotService slots = new VillageMembershipSlotService(
                null, membershipRepository, new VillageParticipationLimitProvider(null));
        VillageMembershipEntity m = membership(PLATFORM_USER_ID, VillageRole.HEADMAN);
        assertPlatformForbidden(() -> PlatformAccountAffiliationPolicy.callJudged(policy.judge(java.util.List.of(PLATFORM_USER_ID)), () -> slots.admitOfficialVillageCreator(m, village(VillageType.COMMUNITY, PLATFORM_USER_ID))));
        assertPlatformForbidden(() -> PlatformAccountAffiliationPolicy.callJudged(policy.judge(java.util.List.of(PLATFORM_USER_ID)), () -> slots.admitOfficialVillageCreator(m, village(VillageType.OFFICIAL, OTHER_HEADMAN_ID))));
        verify(membershipRepository, never()).save(any());
    }
}
