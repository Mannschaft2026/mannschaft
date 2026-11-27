package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-A PR2 受け入れ条件 P5: 運営アカウント所属禁止の判定窓口
 * {@link PlatformAccountAffiliationPolicy} の単体テスト（試練）。
 *
 * <p>判定は既存の {@code UserRoleRepository#existsSystemAdminByUserId} だけで行い、
 * role ドメインの {@link UserRoleRepository} 以外に依存しないこと（DI 循環回避）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformAccountAffiliationPolicy（戦役Z-A PR2 / P5）")
class PlatformAccountAffiliationPolicyTest {

    private static final Long PLATFORM_USER_ID = 9001L;
    private static final Long GENERAL_USER_ID = 101L;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private PlatformAccountAffiliationPolicy policy;

    @Test
    @DisplayName("P5: 運営アカウントは assertAffiliatable で MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN になる")
    void P5_運営アカウントは所属不可として拒否される() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);

        assertThatThrownBy(() -> policy.assertAffiliatable(PLATFORM_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    @Test
    @DisplayName("P4/P5: 一般利用者は assertAffiliatable を素通りする")
    void P5_一般利用者は素通りする() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(GENERAL_USER_ID)).willReturn(0L);

        assertThatCode(() -> policy.assertAffiliatable(GENERAL_USER_ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("例外: 公式村・作成者本人・村長の 3 条件がそろったときだけ運営アカウントでも通り、1 つでも欠けたら拒否する")
    void 例外は公式村の作成者の村長だけを通す() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        Long other = 202L;

        assertThatCode(() -> policy.assertAffiliatableAsOfficialVillageCreator(
                PLATFORM_USER_ID, true, PLATFORM_USER_ID, true)).doesNotThrowAnyException();
        for (boolean[] c : new boolean[][] {{false, true}, {true, false}}) {
            assertThatThrownBy(() -> policy.assertAffiliatableAsOfficialVillageCreator(
                    PLATFORM_USER_ID, c[0], PLATFORM_USER_ID, c[1]))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        }
        assertThatThrownBy(() -> policy.assertAffiliatableAsOfficialVillageCreator(
                PLATFORM_USER_ID, true, other, true))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.assertAffiliatableAsOfficialVillageCreator(
                PLATFORM_USER_ID, true, null, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("P5: isPlatformAccount は運営アカウントで true・一般利用者で false（後継候補の除外用）")
    void P5_isPlatformAccountは運営ロール保持を返す() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(GENERAL_USER_ID)).willReturn(0L);

        assertThat(policy.isPlatformAccount(PLATFORM_USER_ID)).isTrue();
        assertThat(policy.isPlatformAccount(GENERAL_USER_ID)).isFalse();
    }

    @Test
    @DisplayName("P5: 停止中・非 ACTIVE の運営アカウントも対象（状態を問わない照会だけで判定し、ACTIVE 限定の照会に頼らない）")
    void P5_停止中の運営アカウントも拒否する() {
        // ACTIVE・未削除に限る照会（existsSystemAdminByUserId）は停止中だと 0 を返すが、Policy はそれを使わない。
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);

        assertThatThrownBy(() -> policy.assertAffiliatable(PLATFORM_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
        assertThat(policy.isPlatformAccount(PLATFORM_USER_ID)).isTrue();
        org.mockito.Mockito.verify(userRoleRepository, org.mockito.Mockito.never())
                .existsSystemAdminByUserId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("P1: MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN は HTTP 403 に解決される")
    void P1_エラーコードは403に解決される() {
        assertThat(GlobalExceptionHandler.resolveStatus(
                MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN.getMessage())
                .isEqualTo("運営アカウントはチーム・組織・委員会・村に所属したり、サポーター（フォロー）になったりできません");
    }

    @Test
    @DisplayName("P5: Policy の依存は UserRoleRepository だけ（DI 循環回避）")
    void P5_依存はUserRoleRepositoryだけ() {
        Constructor<?>[] ctors = PlatformAccountAffiliationPolicy.class.getDeclaredConstructors();
        assertThat(ctors).hasSize(1);
        assertThat(Arrays.asList(ctors[0].getParameterTypes())).containsExactly(UserRoleRepository.class);
    }
}
