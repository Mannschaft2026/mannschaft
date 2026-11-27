package com.mannschaft.app.supportaccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.entity.SupportAccessLogEntity;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import com.mannschaft.app.supportaccess.repository.SupportAccessLogRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 戦役Z-B 試練（IT）: 実 SecurityConfig・実フィルター順で、依頼→本人の再認証つき許可→閲覧開始→閲覧→取消を通す。
 *
 * <p>S1（無効・取消直後は 403）、S2（未開始は 403）、S3（書込は 003・DB 不変）、S4（/me/support-access・
 * /system-admin は代理中 GET でも 004）、S5（本人の視点・本人の状態ゲート）、S6（他の運営者は 404・DENIED 記録）、
 * S8（理由必須）、S9（再認証なしは許可不可）、S10（閲覧記録）、S13（旧ヘッダー 400）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("戦役Z-B サポート閲覧 HTTP IT")
class SupportAccessHttpIT extends AbstractMySqlIntegrationTest {

    private static final String HEADER = SupportAccessHeaders.GRANT_ID;
    private static final String PASSWORD = "Correct-Horse-9";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SupportAccessGrantRepository grants;
    @Autowired private SupportAccessLogRepository logs;

    private Long operatorId;
    private Long otherOperatorId;
    private Long targetId;

    @BeforeEach
    void setUp() {
        operatorId = saveUser("運営担当", UserEntity.UserStatus.ACTIVE).getId();
        otherOperatorId = saveUser("別の運営", UserEntity.UserStatus.ACTIVE).getId();
        targetId = saveUser("本人", UserEntity.UserStatus.ACTIVE).getId();
    }

    private UserEntity saveUser(String displayName, UserEntity.UserStatus status) {
        return users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID() + "@support-access.invalid")
                .passwordHash(passwordEncoder.encode(PASSWORD))
                .lastName("山田").firstName("太郎").lastNameKana("ヤマダ").firstNameKana("タロウ")
                .birthDate("1990-01-22").displayName(displayName).isSearchable(false)
                .locale("ja").timezone("Asia/Tokyo").status(status).build());
    }

    private RequestPostProcessor operator() {
        return user(String.valueOf(operatorId)).roles("MEMBER", "SYSTEM_ADMIN");
    }

    private RequestPostProcessor target() {
        return user(String.valueOf(targetId)).roles("MEMBER");
    }

    /** DB に直接、承認済み・閲覧開始済みの grant を置く（状態ごとの試験用）。 */
    private UUID approvedStartedGrant(Long op, Long tgt) {
        Instant now = Instant.now();
        return grants.saveAndFlush(SupportAccessGrantEntity.builder()
                .operatorUserId(op).targetUserId(tgt).status(SupportAccessStatus.APPROVED)
                .requestReason("問い合わせの表示崩れを本人の画面で確認するため")
                .requestedAt(now.minus(java.time.Duration.ofHours(1))).requestExpiresAt(now.plus(java.time.Duration.ofHours(71)))
                .decidedAt(now.minus(java.time.Duration.ofMinutes(30))).expiresAt(now.plus(java.time.Duration.ofHours(23)))
                .activeSessionStartedAt(now.minus(java.time.Duration.ofMinutes(1))).guardianApprovalRequired(false).build()).getId();
    }

    private static String errorCode(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    @DisplayName("S1/S2/S5/S9/S10 依頼→再認証つき許可→閲覧開始→本人の視点で閲覧→本人の取消で次リクエストから 403")
    void S1_一連の流れ() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/system-admin/support-access/grants").with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetId + ",\"reason\":\"問い合わせ #123 の表示崩れを確認するため\","
                                + "\"referenceType\":\"FEEDBACK\",\"referenceId\":123}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        String grantId = body.at("/data/id").asText();
        assertThat(grantId).isNotBlank();
        assertThat(body.at("/data/status").asText()).isEqualTo("PENDING");

        // S2: 許可前・開始前のヘッダーは 403
        mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId)).andExpect(status().isForbidden());

        // S9: 再認証なしの許可は通らない
        mvc.perform(post("/api/v1/me/support-access/grants/" + grantId + "/approve").with(target())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isBetween(400, 499));
        mvc.perform(post("/api/v1/me/support-access/grants/" + grantId + "/approve").with(target())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"currentPassword\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // S2: 許可済みでも閲覧開始前は 403
        mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId)).andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/system-admin/support-access/grants/" + grantId + "/sessions").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetUserId").value(targetId));

        // S5: 本人の視点で閲覧できる
        mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("本人"));

        // S10: 閲覧記録が残り、本人が自分の設定画面で見られる（運営者は表示名のみ・メールなし）
        assertThat(logs.findByGrantIdOrderByOccurredAtAsc(UUID.fromString(grantId)))
                .extracting(SupportAccessLogEntity::getEventKind)
                .contains(SupportAccessEventKind.SESSION_START, SupportAccessEventKind.VIEW);
        MvcResult myLogs = mvc.perform(get("/api/v1/me/support-access/logs").with(target()))
                .andExpect(status().isOk()).andReturn();
        String myLogsJson = myLogs.getResponse().getContentAsString();
        assertThat(myLogsJson).contains("運営担当").contains("/api/v1/users/me").doesNotContain("@support-access.invalid");

        // S1: 本人の取消の直後、次のリクエストから 403
        mvc.perform(post("/api/v1/me/support-access/grants/" + grantId + "/revoke").with(target()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId)).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S8 理由なし・短すぎる依頼は 400 SUPPORT_ACCESS_008")
    void S8_理由必須() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/system-admin/support-access/grants").with(operator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetUserId\":" + targetId + ",\"reason\":\"短い\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorCode(r)).contains("SUPPORT_ACCESS_008");
    }

    @Test
    @DisplayName("S3 代理中の PUT は 403 SUPPORT_ACCESS_003 で、本人のプロフィールは変わらない")
    void S3_書込は003でDB不変() throws Exception {
        UUID grantId = approvedStartedGrant(operatorId, targetId);

        MvcResult r = mvc.perform(put("/api/v1/users/me").with(operator()).header(HEADER, grantId.toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"書き換え\"}"))
                .andExpect(status().isForbidden()).andReturn();

        assertThat(errorCode(r)).contains("SUPPORT_ACCESS_003");
        assertThat(users.findById(targetId).orElseThrow().getDisplayName()).isEqualTo("本人");
    }

    @Test
    @DisplayName("S4 代理中は本人承認の API と運営 API を GET でも 004 で拒否する（自己許可の抜け道を塞ぐ）")
    void S4_本人承認と運営APIは代理中GETでも拒否() throws Exception {
        UUID grantId = approvedStartedGrant(operatorId, targetId);

        for (String path : new String[]{"/api/v1/me/support-access/grants", "/api/v1/system-admin/support-access/grants"}) {
            MvcResult r = mvc.perform(get(path).with(operator()).header(HEADER, grantId.toString()))
                    .andExpect(status().isForbidden()).andReturn();
            assertThat(errorCode(r)).as(path).contains("SUPPORT_ACCESS_004");
        }
    }

    @Test
    @DisplayName("S5 本人が保護者同意待ち・凍結なら、許可が有効でも代理閲覧は 403（本人の状態ゲートが毎回効く）")
    void S5_本人の状態ゲート() throws Exception {
        for (UserEntity.UserStatus status : new UserEntity.UserStatus[]{
                UserEntity.UserStatus.PENDING_PARENTAL_CONSENT, UserEntity.UserStatus.FROZEN}) {
            Long gated = saveUser("状態ゲートの本人", status).getId();
            UUID grantId = approvedStartedGrant(operatorId, gated);

            mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId.toString()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("S6 他の運営者の grant ID は 404 で、閲覧記録に DENIED が残る")
    void S6_他の運営者のgrantは404() throws Exception {
        UUID grantId = approvedStartedGrant(otherOperatorId, targetId);

        mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId.toString()))
                .andExpect(status().isNotFound());

        assertThat(logs.findByGrantIdOrderByOccurredAtAsc(grantId))
                .anySatisfy(l -> {
                    assertThat(l.getEventKind()).isEqualTo(SupportAccessEventKind.DENIED);
                    assertThat(l.getOperatorUserId()).isEqualTo(operatorId);
                    assertThat(l.getDenyCode()).isEqualTo("SUPPORT_ACCESS_001");
                });
    }

    @Test
    @DisplayName("S1 期限切れ（APPROVED のまま expires_at 経過）の grant は 403")
    void S1_期限切れは403() throws Exception {
        UUID grantId = approvedStartedGrant(operatorId, targetId);
        SupportAccessGrantEntity g = grants.findById(grantId).orElseThrow();
        grants.saveAndFlush(g.toBuilder().expiresAt(Instant.now().minus(java.time.Duration.ofSeconds(1))).build());

        mvc.perform(get("/api/v1/users/me").with(operator()).header(HEADER, grantId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S13 旧ヘッダー X-Admin-Impersonate-User-Id は 400 SUPPORT_ACCESS_012")
    void S13_旧ヘッダーは400() throws Exception {
        MvcResult r = mvc.perform(get("/api/v1/users/me").with(operator())
                        .header(SupportAccessHeaders.LEGACY_IMPERSONATE, targetId.toString()))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(errorCode(r)).contains("SUPPORT_ACCESS_012");
    }
}
