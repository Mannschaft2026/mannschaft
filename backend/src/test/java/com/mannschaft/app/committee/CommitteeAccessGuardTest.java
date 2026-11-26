package com.mannschaft.app.committee;

import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link CommitteeAccessGuard} の単体テスト。
 *
 * <p>委員会メンバーシップ・委員会内ロール・招集状の宛先本人性という 3 つの判定軸が、
 * それぞれ<b>実体（{@code committee_members} の現役行 / 招集状エンティティ）</b>に基づくことを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommitteeAccessGuard 単体テスト")
class CommitteeAccessGuardTest {

    private static final Long COMMITTEE_ID = 1L;
    private static final Long OTHER_COMMITTEE_ID = 2L;
    private static final Long USER_ID = 900_000_001L;
    private static final Long OTHER_USER_ID = 900_000_002L;

    @Mock
    private CommitteeMemberRepository committeeMemberRepository;

    /**
     * CMP-261125-2349: 取り下げの拒否を「可視の非許可者 403 / 非委員 404」に分けるため、
     * 実装で組織 ADMIN・SYSTEM_ADMIN の判定が入る見込み（既定では全て false = 権限なし）。
     */
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private CommitteeAccessGuard guard;

    @Nested
    @DisplayName("requireCommitteeMember / requireCommitteeRole")
    class Membership {

        @Test
        @DisplayName("現役メンバーでなければ COMMON_002 で拒否される")
        void 非メンバーは拒否される() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requireCommitteeMember(COMMITTEE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("現役メンバーならメンバー行が返る")
        void メンバーは通過する() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.MEMBER)));

            CommitteeMemberEntity result = guard.requireCommitteeMember(COMMITTEE_ID, USER_ID);

            assertThat(result.getRole()).isEqualTo(CommitteeRole.MEMBER);
        }

        @Test
        @DisplayName("要求ロールを満たさないメンバーは COMMON_002 で拒否される")
        void ロール不足は拒否される() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.MEMBER)));

            assertThatThrownBy(() -> guard.requireCommitteeRole(
                    COMMITTEE_ID, USER_ID, CommitteeRole.CHAIR, CommitteeRole.VICE_CHAIR))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("要求ロールのいずれかを満たすメンバーは通過する")
        void ロールを満たすと通過する() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.VICE_CHAIR)));

            assertThatCode(() -> guard.requireCommitteeRole(
                    COMMITTEE_ID, USER_ID, CommitteeRole.CHAIR, CommitteeRole.VICE_CHAIR))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("hasCommitteeRole は非メンバーに対して false を返す")
        void hasCommitteeRoleは非メンバーでfalse() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThat(guard.hasCommitteeRole(COMMITTEE_ID, USER_ID, CommitteeRole.CHAIR)).isFalse();
        }
    }

    @Nested
    @DisplayName("requireInvitee / requireInvitationCanceller")
    class Invitation {

        @Test
        @DisplayName("X7/X8: 宛先でない利用者は無効トークンと同じ INVITATION_TOKEN_INVALID で拒否される")
        void 宛先でない利用者は無効トークンと同じコードで拒否される() {
            // CMP-261125-2349 X7/X8: 旧期待値は COMMON_002（403）。403 だと他人の有効トークンであることが
            // 無効トークン（400 COMMITTEE_INVITATION_TOKEN_INVALID）との応答差で判別できるため、等価にする。
            assertThatThrownBy(() -> guard.requireInvitee(invitation(COMMITTEE_ID, OTHER_USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommitteeErrorCode.INVITATION_TOKEN_INVALID));
        }

        @Test
        @DisplayName("宛先本人は通過する")
        void 宛先本人は通過する() {
            assertThatCode(() -> guard.requireInvitee(invitation(COMMITTEE_ID, USER_ID), USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("招集者本人はリポジトリ照会なしで取り下げられる")
        void 招集者本人は取り下げられる() {
            CommitteeInvitationEntity invitation = CommitteeInvitationEntity.builder()
                    .committeeId(COMMITTEE_ID)
                    .inviteeUserId(OTHER_USER_ID)
                    .invitedBy(USER_ID)
                    .inviteToken("token")
                    .expiresAt(LocalDateTime.now().plusDays(1))
                    .build();

            assertThatCode(() -> guard.requireInvitationCanceller(invitation, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("X6/X8: 招集状の委員会の非メンバー（ADMIN でもない）は招集状不在と同じ INVITATION_NOT_FOUND で拒否される")
        void 委員会の非メンバーは招集状不在と同じコードで拒否される() {
            // CMP-261125-2349 X6/X8: 旧期待値は COMMON_002（403）。招集状 ID は連番のため、非委員に 403 を返すと
            // 招集状 ID の実在を列挙できる。不在（COMMITTEE_INVITATION_NOT_FOUND / 404）と等価にする。
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.requireInvitationCanceller(
                    invitation(COMMITTEE_ID, OTHER_USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommitteeErrorCode.INVITATION_NOT_FOUND));
        }

        @Test
        @DisplayName("X3: 招集状の委員会のメンバーで CHAIR でない利用者（招集者でもない）は COMMON_002 で拒否される")
        void 委員会メンバーの役職不足は403のまま() {
            given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, USER_ID))
                    .willReturn(Optional.of(member(CommitteeRole.MEMBER)));

            assertThatThrownBy(() -> guard.requireInvitationCanceller(
                    invitation(COMMITTEE_ID, OTHER_USER_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("requireSameCommittee")
    class SameCommittee {

        @Test
        @DisplayName("パスの委員会と実体の委員会が食い違う場合は NOT_FOUND で拒否される")
        void 委員会不一致は拒否される() {
            assertThatThrownBy(() -> guard.requireSameCommittee(COMMITTEE_ID, OTHER_COMMITTEE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommitteeErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("一致する場合は通過する")
        void 一致すれば通過する() {
            assertThatCode(() -> guard.requireSameCommittee(COMMITTEE_ID, COMMITTEE_ID))
                    .doesNotThrowAnyException();
        }
    }

    // ─────────────────────────────────────────────
    // フィクスチャ
    // ─────────────────────────────────────────────

    private static CommitteeMemberEntity member(CommitteeRole role) {
        return CommitteeMemberEntity.builder()
                .committeeId(COMMITTEE_ID)
                .userId(USER_ID)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private static CommitteeInvitationEntity invitation(Long committeeId, Long inviteeUserId) {
        return CommitteeInvitationEntity.builder()
                .committeeId(committeeId)
                .inviteeUserId(inviteeUserId)
                .invitedBy(OTHER_USER_ID)
                .inviteToken("token")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();
    }
}
