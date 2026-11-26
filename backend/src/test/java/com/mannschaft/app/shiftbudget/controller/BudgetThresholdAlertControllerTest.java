package com.mannschaft.app.shiftbudget.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.AlertResponse;
import com.mannschaft.app.shiftbudget.service.BudgetThresholdAlertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.mannschaft.app.common.security.AccessGuard;

/**
 * {@link BudgetThresholdAlertController} の MockMvc 結合テスト（Phase 9-δ 第2段、API #9 / #10）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>GET /alerts → 200 + 配列形状</li>
 *   <li>POST /alerts/{id}/acknowledge → 200 + acknowledged フィールド</li>
 *   <li>POST /alerts/{id}/acknowledge: 別組織 → 404 (ALERT_NOT_FOUND)</li>
 *   <li>POST /alerts/{id}/acknowledge: 権限なし → 403 (BUDGET_ADMIN_REQUIRED)</li>
 *   <li>POST /alerts/{id}/acknowledge: comment 500 文字超 → 400 (Bean Validation)</li>
 * </ul>
 *
 * <p>{@code ProxyInputConsentRepository} + {@code ProxyInputContext} を {@code @MockitoBean} で必須宣言
 * （引き継ぎメモ #7 のハマリポイント踏襲）。</p>
 */
@WebMvcTest(BudgetThresholdAlertController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("BudgetThresholdAlertController 結合テスト")
class BudgetThresholdAlertControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 1L;
    private static final Long ALERT_ID = 50L;
    private static final Long ALLOCATION_ID = 42L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private BudgetThresholdAlertService alertService;

    @MockitoBean
    private com.mannschaft.app.shiftbudget.service.BudgetAlertAcknowledgerNameResolver acknowledgerNameResolver;

    @MockitoBean
    private AuthTokenService authTokenService;

    @MockitoBean
    private UserLocaleCache userLocaleCache;

    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private AlertResponse sampleAlert() {
        return AlertResponse.builder()
                .id(ALERT_ID)
                .allocationId(ALLOCATION_ID)
                .thresholdPercent(80)
                .triggeredAt(LocalDateTime.of(2026, 6, 25, 14, 30))
                .consumedAmountAtTrigger(new BigDecimal("80000"))
                .workflowRequestId(null)
                .acknowledgedAt(null)
                .acknowledgedBy(null)
                .build();
    }

    @Test
    @DisplayName("GET /alerts → 200 + 一覧 JSON 配列")
    void list_200() throws Exception {
        given(alertService.list(eq(ORG_ID), anyInt(), anyInt()))
                .willReturn(List.of(sampleAlert()));

        mockMvc.perform(get("/api/v1/shift-budget/alerts")
                        .header("X-Organization-Id", ORG_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(ALERT_ID))
                .andExpect(jsonPath("$.data[0].allocation_id").value(ALLOCATION_ID))
                .andExpect(jsonPath("$.data[0].threshold_percent").value(80));
    }

    @Test
    @DisplayName("POST /alerts/{id}/acknowledge → 200 + acknowledged_by/at セット")
    void acknowledge_200() throws Exception {
        AlertResponse acked = AlertResponse.builder()
                .id(ALERT_ID).allocationId(ALLOCATION_ID).thresholdPercent(80)
                .triggeredAt(LocalDateTime.of(2026, 6, 25, 14, 30))
                .consumedAmountAtTrigger(new BigDecimal("80000"))
                .acknowledgedAt(LocalDateTime.of(2026, 6, 25, 15, 0))
                .acknowledgedBy(USER_ID)
                .build();
        given(alertService.acknowledge(eq(ORG_ID), eq(ALERT_ID), any(), anyBoolean()))
                .willReturn(acked);

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"了解、来月予算追加\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ALERT_ID))
                .andExpect(jsonPath("$.data.acknowledged_by").value(USER_ID));
    }

    @Test
    @DisplayName("POST acknowledge: overwrite=true はサービスへ引き渡される")
    void acknowledge_overwrite引き渡し() throws Exception {
        AlertResponse acked = AlertResponse.builder().id(ALERT_ID).allocationId(ALLOCATION_ID)
                .thresholdPercent(80).acknowledgedBy(USER_ID).build();
        given(alertService.acknowledge(eq(ORG_ID), eq(ALERT_ID), any(), eq(true))).willReturn(acked);

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"overwrite\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST acknowledge: 確認済み → 409 + details に確認者・時刻")
    void acknowledge_409() throws Exception {
        given(acknowledgerNameResolver.resolve(7L, ORG_ID)).willReturn("山田 太郎");
        willThrow(new com.mannschaft.app.shiftbudget.BudgetAlertAlreadyAcknowledgedException(
                7L, null, java.time.OffsetDateTime.of(2026, 6, 25, 15, 0, 0, 0, java.time.ZoneOffset.ofHours(9))))
                .given(alertService).acknowledge(eq(ORG_ID), eq(ALERT_ID), any(), anyBoolean());

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_033"))
                .andExpect(jsonPath("$.error.details.acknowledgedBy").value(7))
                .andExpect(jsonPath("$.error.details.acknowledgedByName").value("山田 太郎"));
    }

    @Test
    @DisplayName("POST acknowledge: 氏名を解決できない（組織外の確認者等）→ 409 の氏名は null")
    void acknowledge_409_組織外の確認者の氏名はnull() throws Exception {
        given(acknowledgerNameResolver.resolve(7L, ORG_ID)).willReturn(null);
        willThrow(new com.mannschaft.app.shiftbudget.BudgetAlertAlreadyAcknowledgedException(
                7L, null, java.time.OffsetDateTime.of(2026, 6, 25, 15, 0, 0, 0, java.time.ZoneOffset.ofHours(9))))
                .given(alertService).acknowledge(eq(ORG_ID), eq(ALERT_ID), any(), anyBoolean());

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_BUDGET_033"))
                .andExpect(jsonPath("$.error.details.acknowledgedBy").value(7))
                .andExpect(jsonPath("$.error.details.acknowledgedByName").doesNotExist());
    }

    @Test
    @DisplayName("POST acknowledge: ALERT_NOT_FOUND → 404")
    void acknowledge_404() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.ALERT_NOT_FOUND))
                .given(alertService).acknowledge(eq(ORG_ID), eq(ALERT_ID), any(), anyBoolean());

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST acknowledge: BUDGET_ADMIN_REQUIRED → 403")
    void acknowledge_403() throws Exception {
        willThrow(new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED))
                .given(alertService).acknowledge(eq(ORG_ID), eq(ALERT_ID), any(), anyBoolean());

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST acknowledge: comment 501 文字 → 400 (Bean Validation)")
    void acknowledge_comment長すぎ400() throws Exception {
        String longComment = "あ".repeat(501);
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("comment", longComment));

        mockMvc.perform(post("/api/v1/shift-budget/alerts/" + ALERT_ID + "/acknowledge")
                        .header("X-Organization-Id", ORG_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
