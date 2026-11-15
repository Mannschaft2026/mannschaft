package com.mannschaft.app.membership;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.MemberCardService;
import com.mannschaft.app.membership.service.QrTokenService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 会員証 cardId 直指定 4 経路の存在秘匿契約（CMP-261113-1506）。
 *
 * <p>閲覧権限の無い他人の会員証には、存在しない会員証とまったく同じ応答
 * （エラーコード・メッセージ。HTTP ステータスは同一コードの写像で一致）を返す。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemberCardService cardId直指定4経路の存在秘匿契約")
class MemberCardOwnershipConcealmentTest {

    @Mock private MemberCardRepository memberCardRepository;
    @Mock private MemberCardCheckinRepository checkinRepository;
    @Mock private CheckinLocationRepository locationRepository;
    @Mock private QrTokenService qrTokenService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private NameResolverService nameResolverService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks private MemberCardService service;

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;
    private static final Long SAME_SCOPE_ADMIN = 3L;
    private static final Long OTHER_SCOPE_ADMIN = 4L;
    private static final Long CARD_ID = 10L;
    private static final Long MISSING_CARD_ID = 99L;
    private static final Long SCOPE_ID = 100L;

    private MemberCardEntity card(ScopeType type) {
        return MemberCardEntity.builder()
                .userId(OWNER).scopeType(type).scopeId(type == ScopeType.PLATFORM ? null : SCOPE_ID)
                .cardCode("c").cardNumber("N-1").displayName("d")
                .status(CardStatus.ACTIVE).issuedAt(LocalDateTime.now().minusDays(1))
                .checkinCount(0).totalSpend(BigDecimal.ZERO).qrSecret("s").build();
    }

    private void stubCard(ScopeType type) {
        given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(card(type)));
        given(memberCardRepository.findByIdAndDeletedAtIsNull(MISSING_CARD_ID)).willReturn(Optional.empty());
        given(qrTokenService.generateMemberCardQrToken(any(), any())).willReturn("tok");
        given(qrTokenService.generateSecret()).willReturn("new");
        given(memberCardRepository.save(any(MemberCardEntity.class))).willAnswer(i -> i.getArgument(0));
        given(checkinRepository.findByMemberCardIdAndCheckedInAtBetweenOrderByCheckedInAtDesc(
                anyLong(), any(), any())).willReturn(List.of());
        // 本人以外の ADMIN 判定: 同スコープの ADMIN のみ true
        given(accessControlService.isAdminOrAbove(eq(SAME_SCOPE_ADMIN), eq(SCOPE_ID), eq("TEAM"))).willReturn(true);
    }

    private static BusinessException thrown(Runnable r) {
        try {
            r.run();
        } catch (BusinessException e) {
            return e;
        }
        throw new AssertionError("BusinessException が送出されなかった");
    }

    /** 他人の会員証と不在会員証で、コード・メッセージが完全一致し MEMBERSHIP_001 であること。 */
    private void assertSameAsMissing(Consumer<Long> call, Long cardIdOthers) {
        BusinessException others = thrown(() -> call.accept(cardIdOthers));
        BusinessException missing = thrown(() -> call.accept(MISSING_CARD_ID));
        assertThat(missing.getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_001);
        assertThat(others.getErrorCode()).isEqualTo(missing.getErrorCode());
        assertThat(others.getErrorCode().getCode()).isEqualTo(missing.getErrorCode().getCode());
        assertThat(others.getMessage()).isEqualTo(missing.getMessage());
    }

    @Test
    @DisplayName("詳細取得: 他人の会員証は不在と同一応答（MEMBERSHIP_001）")
    void getCardDetail_othersEqualsMissing() {
        stubCard(ScopeType.TEAM);
        assertSameAsMissing(id -> service.getCardDetail(id, OTHER), CARD_ID);
    }

    @Test
    @DisplayName("チェックイン履歴: 他人の会員証は不在と同一応答（MEMBERSHIP_001）")
    void getCheckinHistory_othersEqualsMissing() {
        stubCard(ScopeType.TEAM);
        assertSameAsMissing(id -> service.getCheckinHistory(id, OTHER, null, null), CARD_ID);
    }

    @Test
    @DisplayName("QR表示: 他人の会員証は不在と同一応答（MEMBERSHIP_001）")
    void getQrToken_othersEqualsMissing() {
        stubCard(ScopeType.TEAM);
        assertSameAsMissing(id -> service.getQrToken(id, OTHER), CARD_ID);
    }

    @Test
    @DisplayName("QR再生成: 他人の会員証は不在と同一応答（MEMBERSHIP_001）")
    void regenerateQr_othersEqualsMissing() {
        stubCard(ScopeType.TEAM);
        assertSameAsMissing(id -> service.regenerateQr(id, OTHER), CARD_ID);
    }

    @Test
    @DisplayName("詳細取得・履歴: 本人は閲覧できる")
    void owner_canReadDetailAndHistory() {
        stubCard(ScopeType.TEAM);
        assertThat(service.getCardDetail(CARD_ID, OWNER).getData()).isNotNull();
        assertThat(service.getCheckinHistory(CARD_ID, OWNER, null, null).getData()).isEmpty();
    }

    @Test
    @DisplayName("詳細取得・履歴: 同スコープの ADMIN は閲覧できる")
    void sameScopeAdmin_canReadDetailAndHistory() {
        stubCard(ScopeType.TEAM);
        assertThatCode(() -> service.getCardDetail(CARD_ID, SAME_SCOPE_ADMIN)).doesNotThrowAnyException();
        assertThatCode(() -> service.getCheckinHistory(CARD_ID, SAME_SCOPE_ADMIN, null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("詳細取得・履歴: 他スコープの ADMIN は不在と同じ MEMBERSHIP_001")
    void otherScopeAdmin_concealed() {
        stubCard(ScopeType.TEAM);
        assertThatThrownBy(() -> service.getCardDetail(CARD_ID, OTHER_SCOPE_ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_001));
        assertThatThrownBy(() -> service.getCheckinHistory(CARD_ID, OTHER_SCOPE_ADMIN, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_001));
    }

    @Test
    @DisplayName("QR表示・再生成: 同スコープの ADMIN でも所有者でなければ MEMBERSHIP_001")
    void qr_sameScopeAdminIsNotOwner_concealed() {
        stubCard(ScopeType.TEAM);
        assertThatThrownBy(() -> service.getQrToken(CARD_ID, SAME_SCOPE_ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_001));
        assertThatThrownBy(() -> service.regenerateQr(CARD_ID, SAME_SCOPE_ADMIN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(MembershipErrorCode.MEMBERSHIP_001));
    }

    @Test
    @DisplayName("PLATFORM会員証: 他人は詳細・履歴とも MEMBERSHIP_001、本人は閲覧できる")
    void platformCard_ownerOnly() {
        stubCard(ScopeType.PLATFORM);
        assertSameAsMissing(id -> service.getCardDetail(id, OTHER), CARD_ID);
        assertSameAsMissing(id -> service.getCheckinHistory(id, OTHER, null, null), CARD_ID);
        assertThat(service.getCardDetail(CARD_ID, OWNER).getData()).isNotNull();
    }
}
