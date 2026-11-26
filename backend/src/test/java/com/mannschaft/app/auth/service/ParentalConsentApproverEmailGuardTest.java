package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-261123-1124 Codex 第6回: 保護者の承認・拒否は、認証ユーザーの確認済みメールが招待先 parentEmail と一致するときだけ。
 * 別ユーザー（トークンを持ち越した別アカウントなど）が承認して保護者になる経路を塞ぐ。
 * 不一致は 403 AUTH_089。子の個人情報は例外・応答に載せない。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("承認者は招待先メールのアカウントに限る（CMP-261123-1124）")
class ParentalConsentApproverEmailGuardTest {

    private static final String TOKEN = "raw-token";
    private static final Long CHILD_ID = 200L;
    private static final Long PARENT_ID = 100L;

    @Mock private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private PendingInviteStore pendingInviteStore;
    @InjectMocks private ParentalConsentService service;

    private ParentalConsentLinkEntity link;
    private UserEntity child;

    @BeforeEach
    void setUp() {
        lenient().when(authTokenService.hashToken(TOKEN)).thenReturn("hash");
        link = mock(ParentalConsentLinkEntity.class);
        lenient().when(link.getStatus()).thenReturn(ParentalConsentLinkStatus.PENDING);
        lenient().when(link.getExpiresAt()).thenReturn(LocalDateTime.now().plusDays(1));
        lenient().when(link.getChildUserId()).thenReturn(CHILD_ID);
        lenient().when(link.getParentEmail()).thenReturn("Parent@Example.com");
        lenient().when(parentalConsentLinkRepository.findChildUserIdByTokenHash("hash")).thenReturn(Optional.of(CHILD_ID));
        lenient().when(parentalConsentLinkRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(link));
        lenient().when(parentalConsentLinkRepository.findByChildUserIdForUpdate(CHILD_ID)).thenReturn(List.of(link));
        child = mock(UserEntity.class);
        lenient().when(child.getEmail()).thenReturn("child@example.com");
        lenient().when(child.getDisplayName()).thenReturn("子");
        lenient().when(child.getId()).thenReturn(CHILD_ID);
        lenient().when(userRepository.findByIdForUpdateIncludingDeleted(CHILD_ID)).thenReturn(Optional.of(child));
        lenient().when(userRepository.findById(CHILD_ID)).thenReturn(Optional.of(child));
    }

    private UserEntity parent(String email, UserEntity.UserStatus status) {
        UserEntity parent = mock(UserEntity.class);
        lenient().when(parent.getEmail()).thenReturn(email);
        lenient().when(parent.getStatus()).thenReturn(status);
        lenient().when(parent.getBirthDate()).thenReturn(null);
        lenient().when(userRepository.findById(PARENT_ID)).thenReturn(Optional.of(parent));
        return parent;
    }

    private static void assertForbiddenMismatch(Throwable e) {
        assertThat(e).isInstanceOf(BusinessException.class);
        BusinessException be = (BusinessException) e;
        assertThat(be.getErrorCode().getCode()).isEqualTo("AUTH_089");
        // 子の個人情報（メール・氏名）を例外文言に載せない
        assertThat(String.valueOf(be.getMessage())).doesNotContain("child@example.com").doesNotContain("子");
    }

    @Test
    @DisplayName("承認: 招待先メールと一致（大文字小文字を区別しない）なら承認できる")
    void approve_matchingEmail_ignoresCase() {
        parent("parent@example.com", UserEntity.UserStatus.ACTIVE);

        assertThatCode(() -> service.approveParentalConsent(TOKEN, PARENT_ID, "127.0.0.1")).doesNotThrowAnyException();
        verify(link).approve(PARENT_ID);
        verify(child).activate();
    }

    @Test
    @DisplayName("承認: 別メールのアカウントは AUTH_089 で拒否し、リンクも子も変えない")
    void approve_mismatchedEmail_forbidden() {
        parent("someone-else@example.com", UserEntity.UserStatus.ACTIVE);

        assertThatThrownBy(() -> service.approveParentalConsent(TOKEN, PARENT_ID, "127.0.0.1"))
                .satisfies(ParentalConsentApproverEmailGuardTest::assertForbiddenMismatch);
        verify(link, never()).approve(any());
        verify(child, never()).activate();
        verify(emailOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("承認: メール未確認（PENDING_VERIFICATION）のアカウントは、メールが一致しても AUTH_089")
    void approve_unverifiedEmail_forbidden() {
        parent("parent@example.com", UserEntity.UserStatus.PENDING_VERIFICATION);

        assertThatThrownBy(() -> service.approveParentalConsent(TOKEN, PARENT_ID, "127.0.0.1"))
                .satisfies(ParentalConsentApproverEmailGuardTest::assertForbiddenMismatch);
        verify(link, never()).approve(any());
    }

    @Test
    @DisplayName("承認: 未認証（承認者 ID なし）は AUTH_089")
    void approve_anonymous_forbidden() {
        assertThatThrownBy(() -> service.approveParentalConsent(TOKEN, null, "127.0.0.1"))
                .satisfies(ParentalConsentApproverEmailGuardTest::assertForbiddenMismatch);
        verify(link, never()).approve(any());
    }

    @Test
    @DisplayName("承認: 無効なトークンは従来どおり AUTH_060（不一致の判定より先。トークンの存在は秘匿のまま）")
    void approve_invalidToken_stillAuth060() {
        when(parentalConsentLinkRepository.findChildUserIdByTokenHash("hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveParentalConsent(TOKEN, PARENT_ID, "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode()).isEqualTo("AUTH_060"));
    }

    @Test
    @DisplayName("拒否: 招待先メールと一致するアカウントなら拒否できる")
    void reject_matchingEmail() {
        parent("parent@example.com", UserEntity.UserStatus.ACTIVE);

        assertThatCode(() -> service.rejectParentalConsent(TOKEN, PARENT_ID, "127.0.0.1")).doesNotThrowAnyException();
        verify(link).reject();
    }

    @Test
    @DisplayName("拒否: 別メールのアカウント・未認証は AUTH_089 で、リンクも子も変えない（他人が拒否して子を削除できない）")
    void reject_mismatchedOrAnonymous_forbidden() {
        parent("someone-else@example.com", UserEntity.UserStatus.ACTIVE);

        assertThatThrownBy(() -> service.rejectParentalConsent(TOKEN, PARENT_ID, "127.0.0.1"))
                .satisfies(ParentalConsentApproverEmailGuardTest::assertForbiddenMismatch);
        assertThatThrownBy(() -> service.rejectParentalConsent(TOKEN, null, "127.0.0.1"))
                .satisfies(ParentalConsentApproverEmailGuardTest::assertForbiddenMismatch);
        verify(link, never()).reject();
        verify(child, never()).requestDeletion();
    }
}
