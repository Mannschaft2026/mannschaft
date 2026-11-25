package com.mannschaft.app.common;

import com.mannschaft.app.shift.ShiftErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * CMP-261124-0159 PR-01 試練: {@link ScopeConcealingAccessGate} の成功経路にも親チーム上限を AND する
 * （F00 §20.4・AC-0159-94）。
 *
 * <p>所属チームが非公開でスコープのロールが MEMBER 未満なら、所有者一致・{@code permitted} 成功・在籍メンバー経路でも
 * 不在と同一の 404（{@code notFoundCode}）。拒否経路でも、上限を越える在籍者は「所属者の権限不足 403」ではなく
 * 越境と同じ 404 に倒す（拒否理由から公開範囲の段を推測させない）。公開チームでは本戦役前と同一。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-261124-0159 秘匿ゲートの成功経路の親チーム上限（AC-0159-94）")
class ScopeConcealingAccessGateMembersOnlyCapTest {

    private static final Long USER = 10L;
    private static final Long TEAM = 1L;
    private static final String TEAM_T = "TEAM";
    private static final ErrorCode NOT_FOUND = ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND;

    @Mock private AccessControlService acs;
    @InjectMocks private ScopeConcealingAccessGate gate;

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    private void membersOnlyAndBelowMember(boolean beyond) {
        given(acs.isBeyondTeamContentCap(USER, TEAM, TEAM_T)).willReturn(beyond);
    }

    @Nested
    @DisplayName("所有者経路 requireOwnerOrAdminOrConceal")
    class Owner {

        @Test
        @DisplayName("V-0159-94-1: 非公開チームで MEMBER 未満の所有者（外された元依頼者・在籍サポーター）は不在と同一の 404")
        void 上限越えの所有者は404() {
            membersOnlyAndBelowMember(true);
            assertThatThrownBy(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, USER, NOT_FOUND))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("V-0159-94-3: 公開チーム（上限を越えない）では所有者は従来どおり通る")
        void 公開チームの所有者は通る() {
            membersOnlyAndBelowMember(false);
            assertThatCode(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, USER, NOT_FOUND))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は上限を課さない（AC-0159-07）")
        void システム管理者() {
            given(acs.isSystemAdmin(USER)).willReturn(true);
            membersOnlyAndBelowMember(true);
            assertThatCode(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, 999L, NOT_FOUND))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("個別許可経路 requireOrConceal")
    class Permitted {

        @Test
        @DisplayName("V-0159-94-2: permitted が真でも上限を越えるなら不在と同一の 404")
        void 上限越えのpermittedは404() {
            membersOnlyAndBelowMember(true);
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireOrConceal(USER, TEAM, TEAM_T, () -> true, NOT_FOUND,
                    CommonErrorCode.COMMON_002))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("公開チームでは permitted で通る（本戦役前と同一）")
        void 公開チームのpermittedは通る() {
            membersOnlyAndBelowMember(false);
            assertThatCode(() -> gate.requireOrConceal(USER, TEAM, TEAM_T, () -> true, NOT_FOUND,
                    CommonErrorCode.COMMON_002)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("拒否経路: 上限を越える在籍者は 403 ではなく越境と同じ 404（公開範囲の段を推測させない）")
        void 拒否経路の在籍者も404() {
            membersOnlyAndBelowMember(true);
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireOrConceal(USER, TEAM, TEAM_T, () -> false, NOT_FOUND,
                    CommonErrorCode.COMMON_002))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("管理者（ADMIN / DEPUTY_ADMIN）は MEMBER 以上なので通る")
        void 管理者は通る() {
            given(acs.isAdminOrAbove(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireAdminOrConceal(USER, TEAM, TEAM_T, NOT_FOUND)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("在籍メンバー経路 requireMemberOrConceal")
    class Member {

        @Test
        @DisplayName("V-0159-94-4: 非公開チームに在籍するサポーター（是正前データ）は excludeSupporter=false でも不在と同一の 404")
        void 在籍サポーターは404() {
            membersOnlyAndBelowMember(true);
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            given(acs.isSupporter(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, false))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
            assertThatThrownBy(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("公開チームのサポーターは従来どおり（excludeSupporter=false で通り、true で 403）")
        void 公開チームのサポーターは従来どおり() {
            membersOnlyAndBelowMember(false);
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            given(acs.isSupporter(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, false))
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(CommonErrorCode.COMMON_002));
        }
    }
}
