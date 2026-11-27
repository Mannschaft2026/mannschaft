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
import static org.mockito.ArgumentMatchers.any;
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

    // ------------------------------------------------------------------
    // 判定は取引の外・拒否は取引の中（D-3T 根治。他ドメインの取引から user_roles を読まない）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D-3T: judge で取引の外に判定し、callJudged の範囲では assertJudgedAffiliatable が DB を読まずに運営アカウントを 403 にする")
    void 取引の外の判定結果で取引の中が拒否する() {
        // 複数件の判定は 1 本の IN 句にまとめる（1 件ずつ user_roles を引かない）
        given(userRoleRepository.findPlatformAdminUserIdsIn(java.util.Set.of(PLATFORM_USER_ID, GENERAL_USER_ID)))
                .willReturn(java.util.List.of(PLATFORM_USER_ID));
        PlatformAccountAffiliationPolicy.Verdicts verdicts =
                policy.judge(java.util.List.of(PLATFORM_USER_ID, GENERAL_USER_ID));

        PlatformAccountAffiliationPolicy.runJudged(verdicts, () -> {
            assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(PLATFORM_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
            assertThatCode(() -> PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(GENERAL_USER_ID))
                    .doesNotThrowAnyException();
            assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(PLATFORM_USER_ID)).isTrue();
            assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(GENERAL_USER_ID)).isFalse();
        });
        // 判定は judge の 1 回の一括照会だけ（取引の中の確認は DB を読まない。1 件ずつの照会もしない）。
        org.mockito.Mockito.verify(userRoleRepository).findPlatformAdminUserIdsIn(any());
        org.mockito.Mockito.verify(userRoleRepository, org.mockito.Mockito.never())
                .existsPlatformAdminRoleByUserId(any());
    }

    @Test
    @DisplayName("判定は 1 件なら従来の単一照会、複数件なら重複・null を除いた 1 本の一括照会になる")
    void judgeは件数に応じて単一照会と一括照会を使い分ける() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        PlatformAccountAffiliationPolicy.runJudged(policy.judge(java.util.Arrays.asList(PLATFORM_USER_ID, null, PLATFORM_USER_ID)),
                () -> assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(PLATFORM_USER_ID)).isTrue());
        org.mockito.Mockito.verify(userRoleRepository, org.mockito.Mockito.never()).findPlatformAdminUserIdsIn(any());

        given(userRoleRepository.findPlatformAdminUserIdsIn(any())).willReturn(java.util.List.of());
        PlatformAccountAffiliationPolicy.runJudged(policy.judge(java.util.List.of(GENERAL_USER_ID, 303L)), () -> {
            assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(GENERAL_USER_ID)).isFalse();
            assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(303L)).isFalse();
        });
        org.mockito.Mockito.verify(userRoleRepository, org.mockito.Mockito.times(1)).findPlatformAdminUserIdsIn(any());
        assertThat(policy.judge(java.util.List.of())).isNotNull();
    }

    @Test
    @DisplayName("Verdicts は judge 以外から作れない（コンストラクタが private）")
    void Verdictsのコンストラクタは公開しない() {
        assertThat(PlatformAccountAffiliationPolicy.Verdicts.class.getDeclaredConstructors())
                .isNotEmpty()
                .allSatisfy(c -> assertThat(java.lang.reflect.Modifier.isPrivate(c.getModifiers())).isTrue());
    }

    @Test
    @DisplayName("D-3T: 判定結果が無い（範囲外・判定していない利用者・null の判定結果）ときは素通りさせず IllegalStateException")
    void 判定漏れは素通りさせない() {
        assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(GENERAL_USER_ID))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(GENERAL_USER_ID))
                .isInstanceOf(IllegalStateException.class);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(GENERAL_USER_ID)).willReturn(0L);
        PlatformAccountAffiliationPolicy.runJudged(policy.judge(java.util.List.of(GENERAL_USER_ID)), () ->
                assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(PLATFORM_USER_ID))
                        .isInstanceOf(IllegalStateException.class));
        PlatformAccountAffiliationPolicy.runJudged(null, () ->
                assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.assertJudgedAffiliatable(GENERAL_USER_ID))
                        .isInstanceOf(IllegalStateException.class));
    }

    @Test
    @DisplayName("D-3T: 範囲は入れ子で足し合わせ、抜ければ外側へ戻り、例外で抜けても残らない")
    void 範囲は入れ子で戻る() {
        given(userRoleRepository.existsPlatformAdminRoleByUserId(PLATFORM_USER_ID)).willReturn(1L);
        given(userRoleRepository.existsPlatformAdminRoleByUserId(GENERAL_USER_ID)).willReturn(0L);
        PlatformAccountAffiliationPolicy.runJudged(policy.judge(java.util.List.of(GENERAL_USER_ID)), () -> {
            PlatformAccountAffiliationPolicy.runJudged(policy.judge(java.util.List.of(PLATFORM_USER_ID)), () -> {
                assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(GENERAL_USER_ID)).isFalse();
                assertThat(PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(PLATFORM_USER_ID)).isTrue();
            });
            assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(PLATFORM_USER_ID))
                    .isInstanceOf(IllegalStateException.class);
        });
        assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.callJudged(
                policy.judge(java.util.List.of(PLATFORM_USER_ID)), () -> {
                    throw new IllegalArgumentException("work failed");
                })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlatformAccountAffiliationPolicy.isJudgedPlatformAccount(PLATFORM_USER_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("D-3T: 公式村の作成者の例外は取引内版でも判定結果を見ずに通し、それ以外は判定結果で拒否する")
    void 公式村の例外の取引内版() {
        assertThatCode(() -> PlatformAccountAffiliationPolicy.assertJudgedAffiliatableAsOfficialVillageCreator(
                PLATFORM_USER_ID, true, PLATFORM_USER_ID, true)).doesNotThrowAnyException();
        PlatformAccountAffiliationPolicy.runJudged(PlatformAccountJudgedFixture.platform(PLATFORM_USER_ID), () ->
                assertThatThrownBy(() -> PlatformAccountAffiliationPolicy
                        .assertJudgedAffiliatableAsOfficialVillageCreator(PLATFORM_USER_ID, true, PLATFORM_USER_ID, false))
                        .isInstanceOf(BusinessException.class));
    }
}
