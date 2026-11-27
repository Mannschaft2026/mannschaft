package com.mannschaft.app.committee.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.committee.dto.CommitteeCreateRequest;
import com.mannschaft.app.committee.dto.CommitteeInviteRequest;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.judged;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.judging;
import static com.mannschaft.app.role.service.PlatformAccountJudgedFixture.platform;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-A PR2 試練: 委員会（committee_members / committee_invitations）の経路が運営アカウントを拒否する
 * （P1/P2/P5）。Policy 呼び出しは各メソッドの冒頭（ユーザー行ロックの直後まで）に置くこと。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("運営アカウント所属禁止: 委員会の経路（戦役Z-A PR2 / P1・P2・P5）")
class PlatformAccountAffiliationBanCommitteePathsTest {

    static final Long PLATFORM_USER_ID = 9001L;
    static final Long GENERAL_USER_ID = 101L;
    static final Long OPERATOR_USER_ID = 201L;
    static final Long ORG_ID = 31L;
    static final Long COMMITTEE_ID = 41L;

    static void assertPlatformForbidden(ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN);
    }

    static void stubPlatformAccount(PlatformAccountAffiliationPolicy policy) {
        doThrow(new BusinessException(MembershipBasisErrorCode.MEMBERSHIP_PLATFORM_ACCOUNT_FORBIDDEN))
                .when(policy).assertAffiliatable(PLATFORM_USER_ID);
    }

    static CommitteeCreateRequest createRequest(Long initialChairUserId) {
        CommitteeCreateRequest req = new CommitteeCreateRequest();
        ReflectionTestUtils.setField(req, "name", "運営委員会");
        ReflectionTestUtils.setField(req, "initialChairUserId", initialChairUserId);
        return req;
    }

    @Nested
    @DisplayName("CommitteeService.createCommittee（委員会の作成・初期委員長）")
    class CreateCommittee {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock CommitteeRepository committeeRepository;
        @Mock CommitteeMemberRepository committeeMemberRepository;
        @Mock AccessControlService accessControlService;
        @Mock UserRowLockService userRowLockService;
        @InjectMocks CommitteeService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), any())).willReturn(true);
        }

        @Test
        @DisplayName("P1: 運営アカウントを初期委員長に指定した委員会作成は 403・委員会も委員行も作らない")
        void P1_運営アカウントを初期委員長にできない() {
            assertPlatformForbidden(() -> service.createCommittee(
                    ORG_ID, createRequest(PLATFORM_USER_ID), OPERATOR_USER_ID));
            verify(committeeRepository, never()).save(any());
            verify(committeeMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1: 運営アカウントによる委員会作成は 403・委員会も委員行も作らない")
        void P1_運営アカウントは委員会を作成できない() {
            assertPlatformForbidden(() -> service.createCommittee(
                    ORG_ID, createRequest(GENERAL_USER_ID), PLATFORM_USER_ID));
            verify(committeeRepository, never()).save(any());
            verify(committeeMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("認可を満たさない者が運営アカウントを初期委員長に指定しても 403 COMMON_002（運営アカウント性を探れない）")
        void 認可を満たさない者には宛先判定より認可エラーが先に返る() {
            given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), any())).willReturn(false);
            org.mockito.Mockito.doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkPermission(anyLong(), anyLong(), any(), any());

            assertThatThrownBy(() -> service.createCommittee(
                    ORG_ID, createRequest(PLATFORM_USER_ID), GENERAL_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            org.mockito.Mockito.verifyNoInteractions(policy);
            verify(committeeRepository, never()).save(any());
        }

        @Test
        @DisplayName("P5: 一般利用者の委員会作成でも初期委員長を Policy に通す")
        void P5_初期委員長はPolicyを通る() {
            org.assertj.core.api.Assertions.catchThrowable(() -> service.createCommittee(
                    ORG_ID, createRequest(GENERAL_USER_ID), OPERATOR_USER_ID));
            verify(policy).assertAffiliatable(GENERAL_USER_ID);
        }
    }

    @Nested
    @DisplayName("CommitteeInvitationService（委員会への招集の発行・受諾）")
    class CommitteeInvitations {
        @Mock PlatformAccountAffiliationPolicy policy;
        @Mock CommitteeRepository committeeRepository;
        @Mock CommitteeMemberRepository committeeMemberRepository;
        @Mock CommitteeInvitationRepository committeeInvitationRepository;
        @Mock UserRowLockService userRowLockService;
        @Mock CommitteeAccessGuard committeeAccessGuard;
        @InjectMocks CommitteeInvitationService service;

        @BeforeEach
        void setUp() {
            stubPlatformAccount(policy);
            given(committeeRepository.findById(COMMITTEE_ID)).willReturn(Optional.of(mock(CommitteeEntity.class)));
        }

        @Test
        @DisplayName("P2: 運営アカウント宛ての招集は発行時点で 403・招集行を作らない")
        void P2_運営アカウント宛ての招集は発行時点で拒否する() {
            CommitteeInviteRequest req = new CommitteeInviteRequest();
            req.setInviteeUserIds(List.of(GENERAL_USER_ID, PLATFORM_USER_ID));
            assertPlatformForbidden(() -> judged(judging(req.getInviteeUserIds(), PLATFORM_USER_ID),
                    () -> service.sendInvitations(COMMITTEE_ID, req, OPERATOR_USER_ID)));
            verify(committeeInvitationRepository, never()).save(any());
            verify(committeeInvitationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("P2: 認可の無い者には、宛先が運営アカウントでも認可エラーが先に返る（宛先の実在を漏らさない）")
        void P2_認可の無い者には宛先判定より認可エラーが先に返る() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(committeeAccessGuard)
                    .requireCommitteeRole(COMMITTEE_ID, GENERAL_USER_ID, CommitteeRole.CHAIR, CommitteeRole.VICE_CHAIR);
            CommitteeInviteRequest req = new CommitteeInviteRequest();
            req.setInviteeUserIds(List.of(PLATFORM_USER_ID));
            // 判定結果は「運営アカウント」でも、拒否は認可の後に置くので認可エラーが先に返る。
            assertThatThrownBy(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.sendInvitations(COMMITTEE_ID, req, GENERAL_USER_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verify(committeeInvitationRepository, never()).save(any());
        }

        @Test
        @DisplayName("P1: 運営アカウントによる招集の受諾は 403・委員行を作らない")
        void P1_運営アカウントは招集を受諾できない() {
            assertPlatformForbidden(() -> judged(platform(PLATFORM_USER_ID),
                    () -> service.acceptInvitation("plain-token", PLATFORM_USER_ID)));
            verify(committeeMemberRepository, never()).save(any());
        }

        @Test
        @DisplayName("D-3T: 取引の外の判定が無いまま受諾に来たら判定漏れとして止め、委員行を作らない")
        void 判定漏れの受諾は止まる() {
            assertThatThrownBy(() -> service.acceptInvitation("plain-token", GENERAL_USER_ID))
                    .isInstanceOf(IllegalStateException.class);
            verify(committeeMemberRepository, never()).save(any());
        }
    }
}
