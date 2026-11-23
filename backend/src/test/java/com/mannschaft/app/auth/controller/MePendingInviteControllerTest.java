package com.mannschaft.app.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MePendingInviteController} 契約テスト（CMP-261107-1833 AC10、AC4/AC7/AC8 のエラー写像）。
 *
 * <p>{@code standaloneSetup} + {@code MockedStatic<SecurityUtils>} で Controller のみを構成する
 * （{@link GuardianChildViewControllerTest} と同流儀）。利用者は SecurityContext のみから取り、パスに ID を持たない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MePendingInviteController 契約テスト（CMP-261107-1833）")
class MePendingInviteControllerTest {

    private static final Long USER_ID = 100L;
    private static final String BASE = "/api/v1/me/pending-invite";

    @Mock
    private PendingInviteService pendingInviteService;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        objectMapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new MePendingInviteController(pendingInviteService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private static PendingInviteResponse pending(String status) {
        return new PendingInviteResponse(UUID.fromString("01938a00-0000-7000-8000-0000000000a1"), status, "TEAM",
                "招待元チーム", "MEMBER", false, null, null, null);
    }

    // ------------------------------------------------------------------
    // 正常系: SecurityContext の利用者だけを Service に渡す
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC10: GET 保留なしは 200 data:null")
    void get_none_returnsNull() throws Exception {
        given(pendingInviteService.findMine(USER_ID)).willReturn(null);

        mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("AC10: GET は本人の保留を返し、招待のハッシュに当たる項目を出さない")
    void get_pending_hasNoHash() throws Exception {
        given(pendingInviteService.findMine(USER_ID)).willReturn(pending("PENDING"));

        MvcResult result = mockMvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.scopeName").value("招待元チーム"))
                .andExpect(jsonPath("$.data.tokenHash").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("hash");
    }

    @Test
    @DisplayName("AC10: 応答 DTO はハッシュ・トークン・利用者 ID の項目を持たない")
    void responseDtoHasNoSecretFields() {
        for (Class<?> dto : new Class<?>[] {PendingInviteResponse.class, PendingInviteAcceptResponse.class,
                PendingInviteReissueResponse.class}) {
            assertThat(Arrays.stream(dto.getRecordComponents()).map(RecordComponent::getName))
                    .as(dto.getSimpleName())
                    .noneMatch(name -> name.toLowerCase().contains("hash") || name.toLowerCase().contains("token")
                            || name.equals("userId"));
        }
    }

    @Test
    @DisplayName("AC7: POST /accept は SecurityContext の利用者で承認し、結果状態を返す")
    void accept_usesCurrentUser() throws Exception {
        given(pendingInviteService.accept(USER_ID)).willReturn(new PendingInviteAcceptResponse("JOINED"));

        mockMvc.perform(post(BASE + "/accept"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("JOINED"));
        verify(pendingInviteService).accept(USER_ID);
    }

    @Test
    @DisplayName("AC8: POST /decline は SecurityContext の利用者で辞退する")
    void decline_usesCurrentUser() throws Exception {
        mockMvc.perform(post(BASE + "/decline")).andExpect(status().isOk());
        verify(pendingInviteService).decline(USER_ID);
    }

    @Test
    @DisplayName("AC9: POST /reissue-request は 200 {alreadyRequested}")
    void reissue_returnsAlreadyRequested() throws Exception {
        given(pendingInviteService.requestReissue(USER_ID)).willReturn(new PendingInviteReissueResponse(true));

        mockMvc.perform(post(BASE + "/reissue-request"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.alreadyRequested").value(true));
        verify(pendingInviteService).requestReissue(USER_ID);
    }

    @Test
    @DisplayName("AC6: POST /dismiss は SecurityContext の利用者で閉じる")
    void dismiss_usesCurrentUser() throws Exception {
        mockMvc.perform(post(BASE + "/dismiss")).andExpect(status().isOk());
        verify(pendingInviteService).dismiss(USER_ID);
    }

    // ------------------------------------------------------------------
    // エラー写像（AUTH_084 保留なし 404 / AUTH_085 状態不正 409 / AUTH_086 処理中 409）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"AUTH_084,404", "AUTH_085,409", "AUTH_086,409"})
    @DisplayName("AC7/AC8: accept のエラーは AUTH_084=404・AUTH_085=409・AUTH_086=409")
    void accept_errorMapping(String code, int httpStatus) throws Exception {
        BusinessException error = new BusinessException(AuthErrorCode.valueOf(code));
        given(pendingInviteService.accept(USER_ID)).willThrow(error);

        mockMvc.perform(post(BASE + "/accept"))
                .andExpect(status().is(httpStatus))
                .andExpect(jsonPath("$.error.code").value(code));
    }

    @Test
    @DisplayName("AC4: AUTH_083（宛先付き招待で登録不可）は GlobalExceptionHandler で 403 に写像される")
    void auth083_isForbidden() throws Exception {
        BusinessException error = new BusinessException(AuthErrorCode.valueOf("AUTH_083"));
        given(pendingInviteService.accept(USER_ID)).willThrow(error);

        mockMvc.perform(post(BASE + "/accept"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("AUTH_083"));
    }

    @Test
    @DisplayName("AC8: decline の状態不正は 409 AUTH_085")
    void decline_invalidState_409() throws Exception {
        BusinessException error = new BusinessException(AuthErrorCode.valueOf("AUTH_085"));
        willThrow(error).given(pendingInviteService).decline(USER_ID);

        mockMvc.perform(post(BASE + "/decline"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_085"));
    }

    @Test
    @DisplayName("AC6: 公開先への再招待要請は 409 AUTH_085")
    void reissue_public_409() throws Exception {
        BusinessException error = new BusinessException(AuthErrorCode.valueOf("AUTH_085"));
        given(pendingInviteService.requestReissue(USER_ID)).willThrow(error);

        mockMvc.perform(post(BASE + "/reissue-request"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("AUTH_085"));
    }

    // ------------------------------------------------------------------
    // 未認証
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"GET:", "POST:/accept", "POST:/decline", "POST:/reissue-request", "POST:/dismiss"})
    @DisplayName("AC10: 未認証は 401 COMMON_000 で Service に到達しない")
    void unauthenticated_401(String route) throws Exception {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId)
                .thenThrow(new BusinessException(CommonErrorCode.COMMON_000));
        String[] parts = route.split(":", -1);
        var request = "GET".equals(parts[0]) ? get(BASE + parts[1]) : post(BASE + parts[1]);

        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_000"));
        verifyNoInteractions(pendingInviteService);
    }

    // ------------------------------------------------------------------
    // 構造: パスに ID を持たず、本文・クエリで利用者を受け取らない。認証必須
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC10: Controller は Bean（@RestController）で、全メソッドが isAuthenticated() で守られる")
    void controllerIsRegisteredAndAuthenticated() {
        assertThat(MePendingInviteController.class.isAnnotationPresent(RestController.class))
                .as("@RestController").isTrue();
        PreAuthorize classLevel = MePendingInviteController.class.getAnnotation(PreAuthorize.class);
        for (Method m : handlerMethods()) {
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class) != null ? m.getAnnotation(PreAuthorize.class) : classLevel;
            assertThat(pre).as(m.getName() + " の @PreAuthorize").isNotNull();
            assertThat(pre.value()).as(m.getName()).isEqualTo("isAuthenticated()");
        }
    }

    @Test
    @DisplayName("AC10: ハンドラは利用者・行 ID を引数（パス・クエリ・本文）で受け取らない（他人の行に触れられない）")
    void handlersTakeNoIdentifiers() {
        for (Method m : handlerMethods()) {
            for (Parameter p : m.getParameters()) {
                assertThat(p.isAnnotationPresent(PathVariable.class) || p.isAnnotationPresent(RequestParam.class)
                        || p.isAnnotationPresent(RequestBody.class))
                        .as(m.getName() + " の引数 " + p.getName()).isFalse();
            }
        }
    }

    private static java.util.List<Method> handlerMethods() {
        return Arrays.stream(MePendingInviteController.class.getDeclaredMethods())
                .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers()))
                .filter(m -> !m.isSynthetic())
                .toList();
    }
}
