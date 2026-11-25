package com.mannschaft.app.signage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 試練（CMP-260826-1457 W2・signage 系統）: サイネージアクセストークンをハッシュで保存し、平文は発行応答にだけ出す。
 *
 * <p>AC-x1（DB に平文が無い・Entity に平文フィールドが無い）、AC-x2（発行時の平文で表示が成功し、保存値は
 * {@code hash(平文)}）、AC-x3（一覧応答に平文・URL を含めない）、AC-x4（無効化済み・期限切れ・ハッシュ値そのもの・
 * 不在はすべて SIGNAGE_002、他スコープの管理者は一覧・無効化できない）、AC-x5（ログに平文を出さない）。
 * AC-A（移行前の有効トークンの失効）は実 Flyway で {@code FlywaySignageAccessTokenHashMigrationTest} が検証する。</p>
 *
 * <p>red の理由: いまの main は {@code signage_access_tokens.token} に UUID の平文を保存し、一覧 API も平文を返す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W2: サイネージアクセストークンのハッシュ保存")
class SignageAccessTokenHashStorageIT extends AbstractMySqlIntegrationTest {

    private static final String BASE64URL_43 = "^[A-Za-z0-9_-]{43}$";
    private static final String SHA256_HEX = "^[0-9a-f]{64}$";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecretTokenVault vault;
    @Autowired private JdbcTemplate jdbc;
    @PersistenceContext private EntityManager em;

    private Long teamAId;
    private Long adminAId;
    private Long teamBId;
    private Long adminBId;
    private Long screenId;

    @BeforeEach
    void setUp() throws Exception {
        insertRoleIfAbsent("ADMIN", "管理者", 2);
        teamAId = insertTeam("SIGNAGE-HASHチームA");
        teamBId = insertTeam("SIGNAGE-HASHチームB");
        adminAId = insertUser("signage-hash-admin-a@example.com");
        adminBId = insertUser("signage-hash-admin-b@example.com");
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        em.flush();
        em.clear();
        screenId = createScreenAsAdminA();
    }

    private List<String> columns() {
        return jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'signage_access_tokens'", String.class);
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return new ArrayList<>(new TreeSet<>(names));
    }

    @Test
    @DisplayName("AC-x1: 発行した行には token_hash だけがあり、値は hash(発行時の平文) で平文と異なる。Entity に平文フィールドは無い")
    void DBにはハッシュだけが残る() throws Exception {
        JsonNode issued = issueTokenAsAdminA("ハッシュ確認", null);
        String plaintext = issued.path("token").asText();
        long id = issued.path("id").asLong();
        em.flush();

        assertThat(columns()).contains("token_hash").doesNotContain("token");
        String stored = jdbc.queryForObject("SELECT token_hash FROM signage_access_tokens WHERE id = ?", String.class, id);
        assertThat(stored).matches(SHA256_HEX).isEqualTo(vault.hash(plaintext)).isNotEqualTo(plaintext);
        assertThat(SignageAccessTokenEntity.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("tokenHash")
                .doesNotContain("token");
    }

    @Test
    @DisplayName("AC-x2・AC-x3: 発行応答だけが平文 token（Base64URL 43 字）を含み、キー集合が許可フィールドと一致する")
    void 発行応答にだけ平文が載る() throws Exception {
        JsonNode issued = issueTokenAsAdminA("発行応答確認", null);
        assertThat(keys(issued)).containsExactlyInAnyOrder(
                "id", "screenId", "token", "name", "isActive", "allowedIps", "expiredAt", "createdAt");
        assertThat(issued.path("token").asText()).matches(BASE64URL_43);
    }

    @Test
    @DisplayName("AC-x3: 一覧応答の各行は token を含まず、平文（の一部も）を返さない")
    void 一覧応答は平文を返さない() throws Exception {
        String plaintext = issueTokenAsAdminA("一覧確認", null).path("token").asText();

        setAuthentication(adminAId);
        String body = mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = objectMapper.readTree(body).path("data").get(0);
        assertThat(keys(row)).containsExactlyInAnyOrder(
                "id", "screenId", "name", "isActive", "allowedIps", "expiredAt", "createdAt");
        assertThat(body).doesNotContain(plaintext)
                .doesNotContain(plaintext.substring(0, 8))
                .doesNotContain(plaintext.substring(plaintext.length() - 8));
    }

    @Test
    @DisplayName("AC-x2: 発行時の平文で表示が成功し、DB のハッシュ値そのものでは SIGNAGE_002")
    void 平文で表示でき_ハッシュ値では表示できない() throws Exception {
        JsonNode issued = issueTokenAsAdminA("表示確認", LocalDateTime.now().plusDays(1));
        String plaintext = issued.path("token").asText();
        em.flush();
        String stored = jdbc.queryForObject("SELECT token_hash FROM signage_access_tokens WHERE id = ?",
                String.class, issued.path("id").asLong());

        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/signage/{token}", plaintext))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.screen.id").value(screenId));
        mockMvc.perform(get("/signage/{token}", stored))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SIGNAGE_002"));
    }

    @Test
    @DisplayName("AC-x4: 無効化後・期限切れ・不在はいずれも SIGNAGE_002（理由を区別しない）")
    void 無効_期限切れ_不在はSIGNAGE_002() throws Exception {
        JsonNode revoked = issueTokenAsAdminA("無効化対象", null);
        JsonNode expired = issueTokenAsAdminA("期限切れ", LocalDateTime.now().minusDays(1));

        setAuthentication(adminAId);
        mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", revoked.path("id").asLong()))
                .andExpect(status().isNoContent());

        SecurityContextHolder.clearContext();
        for (String token : List.of(revoked.path("token").asText(), expired.path("token").asText(),
                vault.issueBase64Url().plaintext())) {
            mockMvc.perform(get("/signage/{token}", token))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("SIGNAGE_002"));
        }
    }

    @Test
    @DisplayName("AC-x4: 他スコープの管理者は一覧も無効化もできず（403）、トークンは有効なまま")
    void 他スコープの管理者は操作できない() throws Exception {
        JsonNode issued = issueTokenAsAdminA("他スコープ確認", null);

        setAuthentication(adminBId);
        mockMvc.perform(get("/api/v1/signage/screens/{id}/tokens", screenId)).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", issued.path("id").asLong()))
                .andExpect(status().isForbidden());

        SecurityContextHolder.clearContext();
        mockMvc.perform(get("/signage/{token}", issued.path("token").asText())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-x5: 発行・表示・無効化のログに平文トークンを出さない")
    void ログに平文を出さない() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Level before = root.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.setLevel(Level.DEBUG);
        root.addAppender(appender);
        try {
            JsonNode issued = issueTokenAsAdminA("ログ確認", null);
            String plaintext = issued.path("token").asText();
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/signage/{token}", plaintext)).andExpect(status().isOk());
            setAuthentication(adminAId);
            mockMvc.perform(delete("/api/v1/signage/screens/tokens/{tokenId}", issued.path("id").asLong()))
                    .andExpect(status().isNoContent());
            assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(message -> message.contains(plaintext));
        } finally {
            root.detachAppender(appender);
            root.setLevel(before);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（SignageScopeContractIT と同じ流儀）
    // ═════════════════════════════════════════════════════════════════════

    private String tokenRequestJson(String name, LocalDateTime expiredAt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (expiredAt != null) {
            body.put("expiredAt", expiredAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        }
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode issueTokenAsAdminA(String name, LocalDateTime expiredAt) throws Exception {
        setAuthentication(adminAId);
        String resp = mockMvc.perform(post("/api/v1/signage/screens/{id}/tokens", screenId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenRequestJson(name, expiredAt)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data");
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long createScreenAsAdminA() throws Exception {
        setAuthentication(adminAId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", "TEAM");
        body.put("scopeId", teamAId);
        body.put("name", "ハッシュ試験画面 " + System.nanoTime());
        String resp = mockMvc.perform(post("/api/v1/signage/screens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'SIGNAGE-HASH', 'テスト', 'SIGNAGE-HASHテスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('signage-hash-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
