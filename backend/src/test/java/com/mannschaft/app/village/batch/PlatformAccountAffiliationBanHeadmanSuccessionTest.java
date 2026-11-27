package com.mannschaft.app.village.batch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2 試練: 村長の自動引き継ぎ（{@link VillageHeadmanSuccessionBatchService}）は
 * 運営アカウントを村長に昇格させない（P1・軍議検分 Z-A (3)）。
 *
 * <p>後継候補の選出から運営アカウントを外す判定は {@link PlatformAccountAffiliationPolicy#isPlatformAccount}
 * を使う（バッチは例外で止めず、候補から除外して次の候補へ進む・候補が尽きれば従来どおり凍結）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 村長の自動引き継ぎ（戦役Z-A PR2 / P1）")
class PlatformAccountAffiliationBanHeadmanSuccessionTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final UUID VILLAGE_ID = UUID.fromString("0190cccc-0000-7000-8000-000000000003");

    @Mock PlatformAccountAffiliationPolicy policy;
    @Mock VillageRepository villageRepository;
    @Mock VillageMembershipRepository membershipRepository;
    @Mock UserRepository userRepository;
    @Mock AuditLogService auditLogService;
    @InjectMocks VillageHeadmanSuccessionBatchService service;

    static VillageMembershipEntity userMembership(Long userId, VillageRole role) {
        VillageMembershipEntity m = new VillageMembershipEntity();
        m.setVillageId(VILLAGE_ID);
        m.setSubjectType(VillageSubjectType.USER);
        m.setSubjectId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    @DisplayName("P1: 最古参 ELDER が運営アカウントなら村長に昇格させない")
    void P1_運営アカウントは村長に昇格しない() {
        VillageMembershipEntity platformElder = userMembership(PLATFORM_USER_ID, VillageRole.ELDER);
        given(policy.isPlatformAccount(PLATFORM_USER_ID)).willReturn(true);
        given(policy.isPlatformAccount(GENERAL_USER_ID)).willReturn(false);
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.HEADMAN)).willReturn(Optional.empty());
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(Optional.of(platformElder));

        service.processVillage(VILLAGE_ID);

        assertThat(platformElder.getRole())
                .as("運営アカウントの村所属を村長へ昇格させてはならない")
                .isEqualTo(VillageRole.ELDER);
    }

    @Test
    @DisplayName("P4/P5: 一般利用者の ELDER は従来どおり昇格し、候補判定で Policy を通る")
    void P4_一般利用者のELDERは従来どおり昇格する() {
        VillageMembershipEntity elder = userMembership(GENERAL_USER_ID, VillageRole.ELDER);
        given(policy.isPlatformAccount(anyLong())).willReturn(false);
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.HEADMAN)).willReturn(Optional.empty());
        given(membershipRepository.findFirstByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNullOrderByJoinedAtAsc(
                VILLAGE_ID, VillageRole.ELDER)).willReturn(Optional.of(elder));

        service.processVillage(VILLAGE_ID);

        ArgumentCaptor<VillageMembershipEntity> saved = ArgumentCaptor.forClass(VillageMembershipEntity.class);
        verify(membershipRepository, atLeastOnce()).save(saved.capture());
        assertThat(saved.getValue().getRole()).isEqualTo(VillageRole.HEADMAN);
        verify(policy, atLeastOnce()).isPlatformAccount(GENERAL_USER_ID);
    }
}
