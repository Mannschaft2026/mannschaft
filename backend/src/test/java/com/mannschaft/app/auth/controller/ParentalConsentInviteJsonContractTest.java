package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard;
import com.mannschaft.app.auth.service.ParentalConsentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261123-1125 試練（red 先行）AC5: 保護者招待 POST /api/v1/parental-consent/invitations の JSON 契約。
 *
 * <p>FE（useParentalConsentApi）は {@code {"parentEmail": ...}}（camelCase）を送るが、
 * 旧 {@code InviteParentRequest} は {@code @JsonProperty("parent_email")} で受けていたため
 * {@code parentEmail} が null になり 400 で落ちていた。命名は camelCase が正
 * （Jackson 既定・同機能の他 DTO も camelCase）。{@code @JsonAlias} で両受けはしない。</p>
 *
 * <p>実 JSON を MockMvc（standaloneSetup）に流し、Jackson のバインド＋Bean Validation の実挙動で検証する。
 * Spring コンテキストを起こさない（@MockitoBean による構成キー・認可型 override を増やさない。ContextOverrideCensusGuardTest）。
 * Service はモック（業務ロジックは ParentalConsentServiceTest が担う）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("保護者招待 POST /invitations の JSON 契約（CMP-261123-1125 AC5）")
class ParentalConsentInviteJsonContractTest {

    private static final String URL = "/api/v1/parental-consent/invitations";
    private static final Long CHILD_USER_ID = 100L;

    @Mock
    private ParentalConsentService parentalConsentService;
    @Mock
    private AuthenticationCriticalOperationGuard authenticationCriticalOperationGuard;
    @InjectMocks
    private ParentalConsentController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CHILD_USER_ID.toString(), null, List.of()));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        // 実の GlobalExceptionHandler と実の messages_*.properties を載せ、FE が使う error.code・翻訳文言まで検証する
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setValidator(validator)
                .setControllerAdvice(new GlobalExceptionHandler(messages))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        LocaleContextHolder.resetLocaleContext();
    }

    private MvcResult postJson(String json) throws Exception {
        return mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    @DisplayName("AC5-1: camelCase の {\"parentEmail\"} で 200、Service に宛先が渡る")
    void camelCaseParentEmail_200() throws Exception {
        MvcResult result = postJson("{\"parentEmail\":\"parent@example.com\"}");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        verify(parentalConsentService).inviteParent(CHILD_USER_ID, "parent@example.com");
    }

    @Test
    @DisplayName("AC5-2: parentEmail 欠落で 400、Service は呼ばれない")
    void missingParentEmail_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(parentalConsentService, never()).inviteParent(anyLong(), any());
    }

    @Test
    @DisplayName("AC5-3: parentEmail が null で 400")
    void nullParentEmail_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"parentEmail\":null}"))
                .andExpect(status().isBadRequest());
        verify(parentalConsentService, never()).inviteParent(anyLong(), any());
    }

    @ParameterizedTest(name = "AC5-4: parentEmail=\"{0}\" は 400")
    @ValueSource(strings = {"", "   ", "not-an-email", "parent@", "@example.com"})
    @DisplayName("AC5-4: 空・空白・不正形式のメールで 400")
    void blankOrInvalidParentEmail_400(String value) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentEmail\":\"" + value + "\"}"))
                .andExpect(status().isBadRequest());
        verify(parentalConsentService, never()).inviteParent(anyLong(), any());
    }

    @Test
    @DisplayName("AC5-5: snake_case の {\"parent_email\"} は受けない（@JsonAlias で両受けしない）→ 400")
    void snakeCaseParentEmail_notAccepted_400() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parent_email\":\"parent@example.com\"}"))
                .andExpect(status().isBadRequest());
        verify(parentalConsentService, never()).inviteParent(anyLong(), any());
    }

    @Test
    @DisplayName("AC5-6: 登録済み／未登録の保護者で応答（ステータス・本文）が分岐しない（存在オラクルを作らない）")
    void registeredAndUnregisteredParent_sameResponse() throws Exception {
        MvcResult registered = postJson("{\"parentEmail\":\"registered-parent@example.com\"}");
        MvcResult unregistered = postJson("{\"parentEmail\":\"unknown-parent@example.com\"}");

        assertThat(registered.getResponse().getStatus()).isEqualTo(200);
        assertThat(unregistered.getResponse().getStatus()).isEqualTo(registered.getResponse().getStatus());
        assertThat(unregistered.getResponse().getContentAsString())
                .isEqualTo(registered.getResponse().getContentAsString());
    }

    // ---- エラー応答の契約（CMP-261123-1124）: ステータス・error.code・翻訳文言 ----

    @Test
    @DisplayName("Validation 失敗は 400 と error.code=COMMON_001（FE が不正メールの案内に使う）")
    void validationFailure_commonCode() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"parentEmail\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));
    }

    @Test
    @DisplayName("業務例外 AUTH_068（重複招待）・AUTH_069（自己招待）は 400 とコード、ja は日本語・en は英訳")
    void businessErrors_statusCodeAndMessages() throws Exception {
        for (String code : List.of("AUTH_068", "AUTH_069")) {
            doThrow(new BusinessException(AuthErrorCode.valueOf(code)))
                    .when(parentalConsentService).inviteParent(anyLong(), any());

            mockMvc.perform(post(URL).locale(Locale.JAPANESE).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"parentEmail\":\"parent@example.com\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(code))
                    .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.matchesPattern(".*[\\p{IsHan}\\p{IsHiragana}].*")));
            mockMvc.perform(post(URL).locale(Locale.ENGLISH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"parentEmail\":\"parent@example.com\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value(code))
                    .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.matchesPattern("[\\x00-\\x7F’]+")));
        }
    }

    @Test
    @DisplayName("同意待ち以外の招待（AUTH_088）は 409、en は英訳")
    void notPendingConsent_409() throws Exception {
        doThrow(new BusinessException(AuthErrorCode.valueOf("AUTH_088")))
                .when(parentalConsentService).inviteParent(anyLong(), any());

        mockMvc.perform(post(URL).locale(Locale.ENGLISH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentEmail\":\"parent@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_088"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("parental consent")));
    }
}
