package com.mannschaft.app.village.batch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserStatusLookupService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2 試練: 村長の自動引き継ぎ（{@link VillageHeadmanSuccessionBatchService}）は
 * 運営アカウントを村長に昇格させない（P1・軍議検分 Z-A (3)）。
 *
 * <p>後継候補が運営アカウントかの判定（user_roles）は {@link VillageHeadmanSuccessionBatchScheduler} が
 * 村の取引の外で本物の {@link PlatformAccountAffiliationPolicy#judge} により済ませ、村の取引は判定結果だけを見る
 * （D-3T）。バッチは例外で止めず、候補から除外して次の候補へ進む・候補が尽きれば従来どおり凍結する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 村長の自動引き継ぎ（戦役Z-A PR2 / P1）")
class PlatformAccountAffiliationBanHeadmanSuccessionTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final UUID VILLAGE_ID = UUID.fromString("0190cccc-0000-7000-8000-000000000003");

    @Mock VillageRepository villageRepository;
    @Mock VillageMembershipRepository membershipRepository;
    @Mock UserRepository userRepository;
    @Mock AuditLogService auditLogService;
    @Mock UserRoleRepository userRoleRepository;
    @Mock UserStatusLookupService userStatusLookupService;
    @InjectMocks VillageHeadmanSuccessionBatchService service;

    VillageHeadmanSuccessionBatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(GENERAL_USER_ID)).willReturn(0L);
        scheduler = new VillageHeadmanSuccessionBatchScheduler(villageRepository, membershipRepository, service,
                userStatusLookupService, new PlatformAccountAffiliationPolicy(userRoleRepository));
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.HEADMAN)).willReturn(Optional.empty());
    }

    static VillageMembershipEntity userMembership(Long userId, VillageRole role) {
        VillageMembershipEntity m = new VillageMembershipEntity();
        m.setVillageId(VILLAGE_ID);
        m.setSubjectType(VillageSubjectType.USER);
        m.setSubjectId(userId);
        m.setRole(role);
        return m;
    }

    private void eldersAre(VillageMembershipEntity... elders) {
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(Optional.of(elders[0]));
        given(membershipRepository.findByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(List.of(elders));
    }

    @Test
    @DisplayName("P1: 最古参 ELDER が運営アカウントなら村長に昇格させず、次の現役 ELDER（一般利用者）を昇格させる")
    void P1_運営アカウントは村長に昇格しない() {
        VillageMembershipEntity platformElder = userMembership(PLATFORM_USER_ID, VillageRole.ELDER);
        VillageMembershipEntity generalElder = userMembership(GENERAL_USER_ID, VillageRole.ELDER);
        eldersAre(platformElder, generalElder);

        scheduler.processVillage(VILLAGE_ID);

        assertThat(platformElder.getRole())
                .as("運営アカウントの村所属を村長へ昇格させてはならない")
                .isEqualTo(VillageRole.ELDER);
        assertThat(generalElder.getRole()).isEqualTo(VillageRole.HEADMAN);
    }

    @Test
    @DisplayName("P4/P5: 一般利用者の ELDER は従来どおり昇格し、候補判定で Policy（取引の外の判定）を通る")
    void P4_一般利用者のELDERは従来どおり昇格する() {
        VillageMembershipEntity elder = userMembership(GENERAL_USER_ID, VillageRole.ELDER);
        eldersAre(elder);

        scheduler.processVillage(VILLAGE_ID);

        ArgumentCaptor<VillageMembershipEntity> saved = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(VillageRole.HEADMAN);
        verify(userRoleRepository, atLeastOnce()).existsPlatformAdminRoleByUserId(GENERAL_USER_ID);
    }

    @Test
    @DisplayName("D-3T: 取引の外の判定が無い候補は昇格させず判定漏れとして止める（判定なしに村長にしない）")
    void 判定の無い候補は昇格させない() {
        VillageMembershipEntity elder = userMembership(GENERAL_USER_ID, VillageRole.ELDER);
        eldersAre(elder);

        assertThatThrownBy(() -> service.processVillage(VILLAGE_ID)).isInstanceOf(IllegalStateException.class);
        assertThat(elder.getRole()).isEqualTo(VillageRole.ELDER);
        verify(membershipRepository, never()).save(any());
    }

    @Test
    @DisplayName("D-3T: 判定する候補は引き継ぎが要りうる村だけ（有効な USER 村長の村は判定しない・有効でなければ現役 ELDER / VILLAGER を判定）")
    void 判定する候補は引き継ぎが要りうる村だけ() {
        VillageMembershipEntity headman = userMembership(GENERAL_USER_ID, VillageRole.HEADMAN);
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.HEADMAN)).willReturn(Optional.of(headman));
        eldersAre(userMembership(PLATFORM_USER_ID, VillageRole.ELDER));

        given(userStatusLookupService.isActive(GENERAL_USER_ID)).willReturn(true);
        assertThat(scheduler.successorCandidateUserIds(VILLAGE_ID)).isEmpty();

        given(userStatusLookupService.isActive(GENERAL_USER_ID)).willReturn(false);
        assertThat(scheduler.successorCandidateUserIds(VILLAGE_ID)).containsExactly(PLATFORM_USER_ID);
    }
}
