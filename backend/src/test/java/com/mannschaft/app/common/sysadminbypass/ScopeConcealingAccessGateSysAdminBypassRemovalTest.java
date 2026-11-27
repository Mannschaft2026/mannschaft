package com.mannschaft.app.common.sysadminbypass;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.shift.ShiftErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR3（試練）: {@link ScopeConcealingAccessGate} から SYSTEM_ADMIN の短絡を撤去する受け入れテスト。
 *
 * <p>AC1: 所属の無い SYSTEM_ADMIN は、Gate の 4 メソッド（requireAdminOrConceal・requireOwnerOrAdminOrConceal・
 * requireMemberOrConceal・requireOrConceal）で<b>越境者（＝不在 ID）と完全に同じ拒否</b>になる。
 * 判定は「管理者 → 呼び出し側の許可条件 → isMember による 404/403 の振り分け」の順で、
 * SYSTEM_ADMIN かどうかは<b>一切参照しない</b>（「SYSTEM_ADMIN なら拒否」を新設するのでもない）。</p>
 *
 * <p>AC-兼任（回帰）: 所属のある一般利用者（ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER）の判定は撤去前と同じ。
 * 回帰側は撤去前から緑で、撤去後も緑でなければならない。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR3: ScopeConcealingAccessGate の SYSTEM_ADMIN 短絡撤去")
class ScopeConcealingAccessGateSysAdminBypassRemovalTest {

    private static final Long SYSADMIN = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long TEAM = 1L;
    private static final String TEAM_T = "TEAM";
    private static final ErrorCode NOT_FOUND = ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND;

    @Mock
    private AccessControlService acs;

    @InjectMocks
    private ScopeConcealingAccessGate gate;

    @BeforeEach
    void stubPlatformAccountWithoutAffiliation() {
        // 運営アカウント: プラットフォームロール SYSTEM_ADMIN のみ。チームには所属しない（PR2 の所属禁止）。
        given(acs.isSystemAdmin(SYSADMIN)).willReturn(true);
        given(acs.isAdminOrAbove(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        given(acs.isAdmin(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        given(acs.isMember(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        given(acs.isSupporter(SYSADMIN, TEAM, TEAM_T)).willReturn(false);
        // 越境者（同じく所属なし・SYSTEM_ADMIN でもない）= 不在 ID と同じ応答になる基準。
        given(acs.isSystemAdmin(OUTSIDER)).willReturn(false);
    }

    private static ErrorCode codeOf(Executable call) {
        try {
            call.execute();
        } catch (BusinessException e) {
            return e.getErrorCode();
        } catch (Throwable t) {
            throw new AssertionError("BusinessException 以外が投げられた: " + t, t);
        }
        return null;
    }

    private List<Executable> allGateCalls(Long actor) {
        List<Executable> calls = new ArrayList<>();
        calls.add(() -> gate.requireAdminOrConceal(actor, TEAM, TEAM_T, NOT_FOUND));
        calls.add(() -> gate.requireOwnerOrAdminOrConceal(actor, TEAM, TEAM_T, 12345L, NOT_FOUND));
        calls.add(() -> gate.requireOwnerOrAdminOrConceal(actor, TEAM, TEAM_T, null, NOT_FOUND));
        calls.add(() -> gate.requireMemberOrConceal(actor, TEAM, TEAM_T, NOT_FOUND, false));
        calls.add(() -> gate.requireMemberOrConceal(actor, TEAM, TEAM_T, NOT_FOUND, true));
        calls.add(() -> gate.requireOrConceal(actor, TEAM, TEAM_T, () -> false, NOT_FOUND,
                CommonErrorCode.COMMON_002));
        return calls;
    }

    @Nested
    @DisplayName("AC1: 所属の無い SYSTEM_ADMIN は不在と同じ notFoundCode")
    class SystemAdminWithoutAffiliation {

        @Test
        @DisplayName("requireAdminOrConceal（requireOrConceal 経由）: SYSTEM_ADMIN 単独は通さず notFoundCode")
        void requireAdminOrConceal_systemAdminAloneIsConcealed() {
            assertThatThrownBy(() -> gate.requireAdminOrConceal(SYSADMIN, TEAM, TEAM_T, NOT_FOUND))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(((BusinessException) t).getErrorCode()).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("requireOwnerOrAdminOrConceal: 所有者でない SYSTEM_ADMIN 単独は通さず notFoundCode")
        void requireOwnerOrAdminOrConceal_systemAdminAloneIsConcealed() {
            assertThatThrownBy(() -> gate.requireOwnerOrAdminOrConceal(SYSADMIN, TEAM, TEAM_T, 12345L, NOT_FOUND))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(((BusinessException) t).getErrorCode()).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("requireMemberOrConceal: 在籍の無い SYSTEM_ADMIN 単独は通さず notFoundCode（excludeSupporter の真偽に依らない）")
        void requireMemberOrConceal_systemAdminAloneIsConcealed() {
            for (boolean excludeSupporter : new boolean[] {false, true}) {
                assertThatThrownBy(() -> gate.requireMemberOrConceal(SYSADMIN, TEAM, TEAM_T, NOT_FOUND, excludeSupporter))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(t -> assertThat(((BusinessException) t).getErrorCode()).isEqualTo(NOT_FOUND));
            }
        }

        @Test
        @DisplayName("requireOrConceal: 呼び出し側の許可条件が偽なら SYSTEM_ADMIN 単独も notFoundCode")
        void requireOrConceal_systemAdminAloneIsConcealed() {
            assertThatThrownBy(() -> gate.requireOrConceal(SYSADMIN, TEAM, TEAM_T, () -> false, NOT_FOUND,
                    CommonErrorCode.COMMON_002))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(t -> assertThat(((BusinessException) t).getErrorCode()).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("★全メソッドで SYSTEM_ADMIN の応答コードが越境者（不在 ID と同一応答）と完全一致する")
        void systemAdminResponseEqualsOutsiderResponseForEveryMethod() {
            List<Executable> sysadminCalls = allGateCalls(SYSADMIN);
            List<Executable> outsiderCalls = allGateCalls(OUTSIDER);
            for (int i = 0; i < sysadminCalls.size(); i++) {
                ErrorCode outsider = codeOf(outsiderCalls.get(i));
                assertThat(outsider).as("前提: 越境者は notFoundCode（呼出 #%d）", i).isEqualTo(NOT_FOUND);
                assertThat(codeOf(sysadminCalls.get(i)))
                        .as("SYSTEM_ADMIN の応答が越境者と一致すること（呼出 #%d）", i)
                        .isEqualTo(outsider);
            }
        }

        @Test
        @DisplayName("Gate は SYSTEM_ADMIN かどうかを判定材料にしない（isSystemAdmin を一度も呼ばない）")
        void gateNeverConsultsIsSystemAdmin() {
            for (Executable call : allGateCalls(SYSADMIN)) {
                codeOf(call);
            }
            verify(acs, never()).isSystemAdmin(anyLong());
        }
    }

    @Nested
    @DisplayName("AC-兼任（回帰）: 所属のある一般利用者の判定は撤去前と同じ")
    class AffiliatedUsersUnchanged {

        private static final Long ADMIN = 11L;
        private static final Long DEPUTY = 12L;
        private static final Long MEMBER = 13L;
        private static final Long SUPPORTER = 14L;

        @BeforeEach
        void stubAffiliatedUsers() {
            for (Long u : List.of(ADMIN, DEPUTY, MEMBER, SUPPORTER)) {
                given(acs.isSystemAdmin(u)).willReturn(false);
                given(acs.isMember(u, TEAM, TEAM_T)).willReturn(true);
            }
            given(acs.isAdminOrAbove(ADMIN, TEAM, TEAM_T)).willReturn(true);
            given(acs.isAdminOrAbove(DEPUTY, TEAM, TEAM_T)).willReturn(true);
            given(acs.isSupporter(SUPPORTER, TEAM, TEAM_T)).willReturn(true);
        }

        @Test
        @DisplayName("ADMIN・DEPUTY_ADMIN: 管理操作・本人or管理者・汎用形はすべて通る")
        void adminAndDeputyPassAdminGates() {
            for (Long u : List.of(ADMIN, DEPUTY)) {
                assertThatCode(() -> gate.requireAdminOrConceal(u, TEAM, TEAM_T, NOT_FOUND)).doesNotThrowAnyException();
                assertThatCode(() -> gate.requireOwnerOrAdminOrConceal(u, TEAM, TEAM_T, 999L, NOT_FOUND))
                        .doesNotThrowAnyException();
                assertThatCode(() -> gate.requireOrConceal(u, TEAM, TEAM_T, () -> false, NOT_FOUND,
                        CommonErrorCode.COMMON_002)).doesNotThrowAnyException();
                assertThatCode(() -> gate.requireMemberOrConceal(u, TEAM, TEAM_T, NOT_FOUND, true))
                        .doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("MEMBER: メンバー操作・本人操作・許可条件つき汎用形は通り、管理操作は 403 COMMON_002（404 に化けない）")
        void memberKeepsMemberAccessAndGets403ForAdminOps() {
            assertThatCode(() -> gate.requireMemberOrConceal(MEMBER, TEAM, TEAM_T, NOT_FOUND, true))
                    .doesNotThrowAnyException();
            assertThatCode(() -> gate.requireOwnerOrAdminOrConceal(MEMBER, TEAM, TEAM_T, MEMBER, NOT_FOUND))
                    .doesNotThrowAnyException();
            assertThatCode(() -> gate.requireOrConceal(MEMBER, TEAM, TEAM_T, () -> true, NOT_FOUND,
                    CommonErrorCode.COMMON_002)).doesNotThrowAnyException();
            assertThat(codeOf(() -> gate.requireAdminOrConceal(MEMBER, TEAM, TEAM_T, NOT_FOUND)))
                    .isEqualTo(CommonErrorCode.COMMON_002);
            assertThat(codeOf(() -> gate.requireOwnerOrAdminOrConceal(MEMBER, TEAM, TEAM_T, 999L, NOT_FOUND)))
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("SUPPORTER: excludeSupporter=true は 403、false は通る。管理操作は 403")
        void supporterKeepsExistingContract() {
            assertThat(codeOf(() -> gate.requireMemberOrConceal(SUPPORTER, TEAM, TEAM_T, NOT_FOUND, true)))
                    .isEqualTo(CommonErrorCode.COMMON_002);
            assertThatCode(() -> gate.requireMemberOrConceal(SUPPORTER, TEAM, TEAM_T, NOT_FOUND, false))
                    .doesNotThrowAnyException();
            assertThat(codeOf(() -> gate.requireAdminOrConceal(SUPPORTER, TEAM, TEAM_T, NOT_FOUND)))
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }
}
