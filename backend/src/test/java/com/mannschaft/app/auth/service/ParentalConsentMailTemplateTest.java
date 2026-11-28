package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * CMP-261123-1124 実機 D-1: 保護者招待メールは件名・本文を持ち、本文のリンクから FE の承認画面へ到達できる。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("保護者招待メールの内容（CMP-261123-1124 D-1）")
class ParentalConsentMailTemplateTest {

    private static final Long CHILD_USER_ID = 1L;
    private static final String BASE_URL = "https://app.example.com";

    @InjectMocks private ParentalConsentService service;
    @Mock private ParentalConsentLinkRepository parentalConsentLinkRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuthTokenService authTokenService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private PendingInviteStore pendingInviteStore;

    private EmailOutboxRequest invite() {
        UserEntity child = UserEntity.builder()
                .email("child@example.com").lastName("山田").firstName("太郎").displayName("たろう")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT)
                .isSearchable(true).birthDate("2012-01-01").build();
        given(userRepository.findById(CHILD_USER_ID)).willReturn(Optional.of(child));
        given(parentalConsentLinkRepository.countByChildUserIdAndStatus(
                CHILD_USER_ID, ParentalConsentLinkStatus.PENDING)).willReturn(0L);
        given(authTokenService.hashToken(anyString())).willReturn("hashed");
        given(emailOutboxService.enqueue(any())).willReturn(UUID.randomUUID());
        ReflectionTestUtils.setField(service, "baseUrl", BASE_URL);

        service.inviteParent(CHILD_USER_ID, "parent@example.com");

        ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
        verify(emailOutboxService).enqueue(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("招待メールは subject と body を持ち、本文に承認画面への完全な URL を載せる")
    void invitationHasSubjectBodyAndApproveUrl() throws Exception {
        EmailOutboxRequest request = invite();
        Map<String, String> vars = request.payloadVars();

        assertThat(vars.get("subject")).isNotBlank();
        assertThat(vars.get("body")).isNotBlank().doesNotContain("null");

        Matcher m = Pattern.compile("(https://app\\.example\\.com)(/[^\"'<\\s?]*)\\?token=([A-Za-z0-9_\\-]+)")
                .matcher(vars.get("body"));
        assertThat(m.find()).as("本文に承認 URL（token 付き）があること").isTrue();
        String pagePath = m.group(2);
        assertThat(pagePath).isEqualTo("/parental-consent/approve");

        // リンク先は FE の実ページ（pages/parental-consent/approve.vue）と一致する
        Path page = Path.of("..", "frontend", "app", "pages", pagePath.substring(1) + ".vue");
        assertThat(Files.exists(page)).as("FE に %s が存在すること", page).isTrue();
        assertThat(Files.readString(page)).contains("route.query.token");
    }

    @Test
    @DisplayName("承認・否認・失効削除・自動解放の通知も件名と本文を持つ")
    void otherNotificationsHaveSubjectAndBody() {
        for (Map<String, String> vars : java.util.List.of(
                ParentalConsentMailContent.approved("たろう"),
                ParentalConsentMailContent.rejected(),
                ParentalConsentMailContent.expiredAccountDeleted(),
                ParentalConsentMailContent.released("たろう"))) {
            assertThat(vars.get("subject")).isNotBlank();
            assertThat(vars.get("body")).isNotBlank().doesNotContain("null");
        }
    }
}
