package com.mannschaft.app.committee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.committee.dto.CommitteeDistributeRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeDistributionLogRepository;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.committee.service.CommitteeDistributionService;
import com.mannschaft.app.committee.service.CommitteeInvitationService;
import com.mannschaft.app.committee.service.CommitteeMinutesService;
import com.mannschaft.app.committee.service.CommitteeService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 委員会の存在オラクル封じ: 不可視ターゲットへの要求で行ロック・副作用を起こさないこと（CMP-261125-2349 AC X5）。
 *
 * <p>判定（可視性・委員会メンバーであるか）は {@code findByIdForUpdate}・{@link UserRowLockService} による行ロック、
 * お知らせ・確認通知の生成、伝達ログ・招集状・メンバー行・活動記録の保存より<b>前</b>に置く。
 * 不可視の要求でロックを取ると、ロック待ちの時間差が実在の答えになり、他人の正当な操作も詰まらせる。</p>
 *
 * <p>主体: 他テナント（組織 A に在籍せず、委員会のメンバーでもなく、ADMIN でもない）と、
 * 組織 A の一般メンバーで委員会の非メンバー（NAME_ONLY で委員会は可視だが書込は 404）。</p>
 *
 * <p>本テストは判定部品として実物の {@link CommitteeAccessGuard} を、その下のリポジトリと
 * {@link AccessControlService} をモックにして組み立てる。実装で判定部品の依存が増えた場合は、
 * ここでの組み立てを合わせて更新すること（期待値は変えない）。</p>
 *
 * <p>監査ログ・レート制限カウンタは現時点で委員会ドメインに存在しないため対象外（足すときは本テストに追加する）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("委員会の存在オラクル封じ X5: 不可視ターゲットでロック・副作用なし")
class CommitteeExistenceOracleLockOrderTest {

    private static final Long ORG_ID = 10L;
    private static final Long COMMITTEE_ID = 100L;
    private static final Long INVITATION_ID = 300L;
    private static final Long RECORD_ID = 400L;
    private static final Long CHAIR_ID = 1_001L;
    private static final Long TARGET_MEMBER_ID = 1_002L;
    private static final Long INVITEE_ID = 1_003L;
    /** 他テナント。 */
    private static final Long OUTSIDER_ID = 9_001L;
    /** 組織 A の一般メンバーで委員会の非メンバー。 */
    private static final Long ORG_NON_MEMBER_ID = 9_002L;
    private static final String TOKEN = "valid-token-of-invitee";

    @Mock private CommitteeRepository committeeRepository;
    @Mock private CommitteeMemberRepository committeeMemberRepository;
    @Mock private CommitteeInvitationRepository committeeInvitationRepository;
    @Mock private CommitteeDistributionLogRepository distributionLogRepository;
    @Mock private ActivityResultRepository activityResultRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private UserRowLockService userRowLockService;
    @Mock private AnnouncementFeedService announcementFeedService;
    @Mock private ConfirmableNotificationService confirmableNotificationService;

    private CommitteeService committeeService;
    private CommitteeInvitationService invitationService;
    private CommitteeDistributionService distributionService;
    private CommitteeMinutesService minutesService;

    @BeforeEach
    void setUp() {
        CommitteeAccessGuard guard = new CommitteeAccessGuard(committeeMemberRepository);
        committeeService = new CommitteeService(committeeRepository, committeeMemberRepository,
                accessControlService, userRowLockService, guard);
        invitationService = new CommitteeInvitationService(committeeRepository, committeeMemberRepository,
                committeeInvitationRepository, accessControlService, userRowLockService, guard);
        distributionService = new CommitteeDistributionService(committeeRepository, committeeMemberRepository,
                distributionLogRepository, guard, new ObjectMapper(), announcementFeedService,
                confirmableNotificationService);
        minutesService = new CommitteeMinutesService(committeeRepository, activityResultRepository,
                new ObjectMapper(), guard);

        CommitteeEntity committee = CommitteeEntity.builder()
                .organizationId(ORG_ID)
                .name("委員会")
                .status(CommitteeStatus.ACTIVE)
                .visibilityToOrg(CommitteeVisibility.NAME_ONLY)
                .createdBy(CHAIR_ID)
                .build();
        ReflectionTestUtils.setField(committee, "id", COMMITTEE_ID);
        given(committeeRepository.findById(COMMITTEE_ID)).willReturn(Optional.of(committee));
        given(committeeRepository.findByIdForUpdate(COMMITTEE_ID)).willReturn(Optional.of(committee));

        CommitteeMemberEntity chair = member(CHAIR_ID, CommitteeRole.CHAIR);
        CommitteeMemberEntity target = member(TARGET_MEMBER_ID, CommitteeRole.MEMBER);
        given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                .willReturn(List.of(chair, target));
        given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNull(COMMITTEE_ID))
                .willReturn(List.of(chair, target));
        given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, CHAIR_ID))
                .willReturn(Optional.of(chair));
        given(committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, TARGET_MEMBER_ID))
                .willReturn(Optional.of(target));
        given(committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, CHAIR_ID))
                .willReturn(true);
        given(committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(COMMITTEE_ID, TARGET_MEMBER_ID))
                .willReturn(true);

        // 組織 A: ORG_NON_MEMBER は在籍、OUTSIDER は非在籍。どちらも ADMIN・SYSTEM_ADMIN ではない。
        given(accessControlService.isMember(ORG_NON_MEMBER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(accessControlService.isMember(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(OUTSIDER_ID, ORG_ID, "ORGANIZATION");

        CommitteeInvitationEntity invitation = CommitteeInvitationEntity.builder()
                .committeeId(COMMITTEE_ID)
                .inviteeUserId(INVITEE_ID)
                .proposedRole(CommitteeRole.MEMBER)
                .inviteToken(TOKEN)
                .invitedBy(CHAIR_ID)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        ReflectionTestUtils.setField(invitation, "id", INVITATION_ID);
        given(committeeInvitationRepository.findById(INVITATION_ID)).willReturn(Optional.of(invitation));
        given(committeeInvitationRepository.findByInviteToken(TOKEN)).willReturn(Optional.of(invitation));
        given(committeeInvitationRepository.findByIdForUpdate(INVITATION_ID)).willReturn(Optional.of(invitation));
    }

    @Nested
    @DisplayName("メンバー管理（FOR UPDATE を取る経路）")
    class MemberManagement {

        @Test
        @DisplayName("X5: 他テナントのロール変更は404で、委員会・メンバー行のロックも保存もしない")
        void X5_他テナントのロール変更はロックせず404() {
            assertNotFound(() -> committeeService.updateMemberRole(
                    COMMITTEE_ID, TARGET_MEMBER_ID, CommitteeRole.SECRETARY, OUTSIDER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            assertNoCommitteeLockNorMemberWrite();
        }

        @Test
        @DisplayName("X5: 他テナントの解任は404で、ロックも保存もしない")
        void X5_他テナントの解任はロックせず404() {
            assertNotFound(() -> committeeService.removeMember(COMMITTEE_ID, TARGET_MEMBER_ID, OUTSIDER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            assertNoCommitteeLockNorMemberWrite();
        }

        @Test
        @DisplayName("X5: 他テナントの離脱は404で、ロックも保存もしない")
        void X5_他テナントの離脱はロックせず404() {
            assertNotFound(() -> committeeService.leaveCommittee(COMMITTEE_ID, OUTSIDER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            assertNoCommitteeLockNorMemberWrite();
        }

        @Test
        @DisplayName("X3/X5: 組織メンバーの非委員の解任は（委員会が可視でも）404で、ロックも保存もしない")
        void X3_X5_組織メンバー非委員の解任はロックせず404() {
            assertNotFound(() -> committeeService.removeMember(COMMITTEE_ID, TARGET_MEMBER_ID, ORG_NON_MEMBER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            assertNoCommitteeLockNorMemberWrite();
        }

        @Test
        @DisplayName("X3/X5: 組織メンバーの非委員の離脱は404で、ロックも保存もしない")
        void X3_X5_組織メンバー非委員の離脱はロックせず404() {
            assertNotFound(() -> committeeService.leaveCommittee(COMMITTEE_ID, ORG_NON_MEMBER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            assertNoCommitteeLockNorMemberWrite();
        }
    }

    @Nested
    @DisplayName("招集状（トークン・招集状 ID）")
    class Invitations {

        @Test
        @DisplayName("X5/X7: 宛先でない利用者の受諾は無効トークンと同じ応答で、利用者行・委員会・招集状のロックを取らない")
        void X5_X7_宛先でない利用者の受諾はロックせず無効トークン() {
            assertNotFound(() -> invitationService.acceptInvitation(TOKEN, OUTSIDER_ID),
                    CommitteeErrorCode.INVITATION_TOKEN_INVALID);
            verifyNoInteractions(userRowLockService);
            verify(committeeRepository, never()).findByIdForUpdate(anyLong());
            verify(committeeInvitationRepository, never()).findByIdForUpdate(anyLong());
            verify(committeeInvitationRepository, never()).save(any());
            verify(committeeMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("X5/X7: 宛先でない利用者の辞退は無効トークンと同じ応答で、招集状を保存しない")
        void X5_X7_宛先でない利用者の辞退は無効トークン() {
            assertNotFound(() -> invitationService.declineInvitation(TOKEN, ORG_NON_MEMBER_ID),
                    CommitteeErrorCode.INVITATION_TOKEN_INVALID);
            verify(committeeInvitationRepository, never()).findByIdForUpdate(anyLong());
            verify(committeeInvitationRepository, never()).save(any());
        }

        @Test
        @DisplayName("X5/X6: 他テナントの招集状取り下げは招集状不在と同じ404で、招集状を保存しない")
        void X5_X6_他テナントの取り下げは不在と同じ404() {
            assertNotFound(() -> invitationService.cancelInvitation(INVITATION_ID, OUTSIDER_ID),
                    CommitteeErrorCode.INVITATION_NOT_FOUND);
            verify(committeeInvitationRepository, never()).findByIdForUpdate(anyLong());
            verify(committeeInvitationRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("伝達・議事録（通知・お知らせ生成を伴う経路）")
    class SideEffects {

        @Test
        @DisplayName("X5: 他テナントの伝達実行は404で、お知らせ・確認通知を生成せず伝達ログも保存しない")
        void X5_他テナントの伝達実行は通知を出さず404() {
            CommitteeDistributeRequest request = new CommitteeDistributeRequest();
            ReflectionTestUtils.setField(request, "contentType", "CUSTOM_MESSAGE");
            ReflectionTestUtils.setField(request, "customTitle", "伝達");
            ReflectionTestUtils.setField(request, "customBody", "本文");
            ReflectionTestUtils.setField(request, "announcementEnabled", true);
            ReflectionTestUtils.setField(request, "confirmationMode", ConfirmationMode.REQUIRED);

            assertNotFound(() -> distributionService.distribute(COMMITTEE_ID, request, OUTSIDER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            verifyNoInteractions(announcementFeedService, confirmableNotificationService);
            verify(distributionLogRepository, never()).save(any());
        }

        @Test
        @DisplayName("X3/X5: 組織メンバーの非委員の議事録確定は404で、活動記録を読まず保存もしない")
        void X3_X5_組織メンバー非委員の議事録確定は404() {
            assertNotFound(() -> minutesService.confirmMinutes(COMMITTEE_ID, RECORD_ID, ORG_NON_MEMBER_ID),
                    CommitteeErrorCode.NOT_FOUND);
            verifyNoInteractions(activityResultRepository);
        }
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private void assertNotFound(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .as("不可視ターゲットは不在と同じコードで拒否する")
                        .isEqualTo(expected));
    }

    private void assertNoCommitteeLockNorMemberWrite() {
        verify(committeeRepository, never()).findByIdForUpdate(anyLong());
        verify(committeeMemberRepository, never()).findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(anyLong());
        verify(committeeMemberRepository, never()).save(any());
    }

    private static CommitteeMemberEntity member(Long userId, CommitteeRole role) {
        return CommitteeMemberEntity.builder()
                .committeeId(COMMITTEE_ID)
                .userId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }
}
