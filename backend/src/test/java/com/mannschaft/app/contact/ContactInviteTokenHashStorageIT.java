package com.mannschaft.app.contact;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.contact.entity.ContactInviteTokenEntity;
import com.mannschaft.app.contact.repository.ContactInviteTokenRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W2・contact 系統）: 連絡先招待トークンをハッシュで保存し、平文は発行応答にだけ出す。
 *
 * <ul>
 *   <li>AC-x1: 実体の列は {@code token_hash} だけで、保存値は {@code hash(平文)}（平文と異なる）。</li>
 *   <li>AC-x2: 発行応答の平文で preview / accept が通り、DB のハッシュ値そのものでは通らない。</li>
 *   <li>AC-x3: 一覧応答は許可フィールドと完全一致し、平文・URL・QR の URL を含まない。QR エンドポイントは廃止。</li>
 *   <li>AC-x4: 失効済み・期限切れ・不存在は今まで通り拒否（preview は isValid=false、accept は 404）。</li>
 *   <li>AC-x5: サービスのログに平文を出さない。</li>
 * </ul>
 *
 * <p>red の理由: いまの main は {@code contact_invite_tokens.token} へ UUID の平文を保存し、
 * 一覧 API も token・inviteUrl・qrCodeUrl を返し、QR エンドポイントが存在する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W2 contact: 連絡先招待トークンのハッシュ保存")
class ContactInviteTokenHashStorageIT extends AbstractMySqlIntegrationTest {

    private static final String BASE64URL_43 = "^[A-Za-z0-9_-]{43}$";
    private static final String SHA256_HEX = "^[0-9a-f]{64}$";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SecretTokenVault vault;
    @Autowired private ContactInviteTokenRepository repository;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;

    private Long issuerId;
    private Long accepterId;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger contactLogger;

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);
        issuerId = insertUser("cih-issuer-" + uniq + "@example.test", "cih-i-" + uniq);
        accepterId = insertUser("cih-accepter-" + uniq + "@example.test", "cih-a-" + uniq);
        logAppender = new ListAppender<>();
        logAppender.start();
        contactLogger = (Logger) LoggerFactory.getLogger("com.mannschaft.app.contact");
        contactLogger.setLevel(Level.TRACE);
        contactLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        contactLogger.detachAppender(logAppender);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-x1: 実スキーマは token_hash だけを持ち、保存値は hash(平文) で平文と異なる")
    void DBにはハッシュだけが残る() throws Exception {
        JsonNode issued = issue(issuerId, "7d");
        String plaintext = issued.path("token").asText();

        assertThat(columns()).contains("token_hash").doesNotContain("token");
        String stored = jdbc.queryForObject("SELECT token_hash FROM contact_invite_tokens WHERE id = ?",
                String.class, issued.path("id").asLong());
        assertThat(stored).matches(SHA256_HEX).isEqualTo(vault.hash(plaintext)).isNotEqualTo(plaintext);
    }

    @Test
    @DisplayName("AC-x1: Entity に平文の token フィールドが無く、tokenHash がある。サービスに QR 生成が残らない")
    void Entityに平文フィールドが無い() {
        assertThat(java.util.Arrays.stream(ContactInviteTokenEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).contains("tokenHash").doesNotContain("token");
        assertThat(java.util.Arrays.stream(
                com.mannschaft.app.contact.service.ContactInviteTokenService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)).doesNotContain("generateQrCode");
    }

    @Test
    @DisplayName("AC-x2・x3: 発行応答だけが平文（Base64URL 43字）と招待URLを含み、キー集合が許可フィールドと一致する")
    void 発行応答にだけ平文が載る() throws Exception {
        JsonNode issued = issue(issuerId, "7d");

        assertThat(keys(issued)).containsExactlyInAnyOrder(
                "id", "token", "inviteUrl", "label", "maxUses", "usedCount", "expiresAt", "createdAt");
        String plaintext = issued.path("token").asText();
        assertThat(plaintext).matches(BASE64URL_43);
        assertThat(issued.path("inviteUrl").asText()).endsWith("/contact-invite/" + plaintext);
    }

    @Test
    @DisplayName("AC-x3: 一覧応答は許可フィールドと完全一致し、平文（の一部も）・URL・QR の URL を含まない")
    void 一覧応答は平文を返さない() throws Exception {
        String plaintext = issue(issuerId, "7d").path("token").asText();

        MockHttpServletResponse response = perform(issuerId, get("/api/v1/contact-invite-tokens"));
        assertThat(response.getStatus()).isEqualTo(200);
        JsonNode rows = mapper.readTree(response.getContentAsString()).path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(keys(rows.get(0))).containsExactlyInAnyOrder(
                "id", "label", "maxUses", "usedCount", "expiresAt", "createdAt");
        String body = response.getContentAsString();
        assertThat(body).doesNotContain(plaintext)
                .doesNotContain(plaintext.substring(0, 8))
                .doesNotContain(plaintext.substring(plaintext.length() - 8))
                .doesNotContain("inviteUrl").doesNotContain("qrCodeUrl").doesNotContain("/qr");
    }

    @Test
    @DisplayName("AC-x3: QR エンドポイント（GET /contact-invite-tokens/{token}/qr）は廃止され、発行者でも 404")
    void QRエンドポイントは廃止() throws Exception {
        String plaintext = issue(issuerId, "7d").path("token").asText();

        assertThat(perform(issuerId, get("/api/v1/contact-invite-tokens/{token}/qr", plaintext)).getStatus())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("AC-x2: 発行時の平文でプレビュー・承諾が通り、DB のハッシュ値そのものでは通らない")
    void 平文で照合しハッシュ値では通らない() throws Exception {
        String plaintext = issue(issuerId, "7d").path("token").asText();
        String stored = jdbc.queryForObject("SELECT token_hash FROM contact_invite_tokens WHERE user_id = ?",
                String.class, issuerId);

        SecurityContextHolder.clearContext();
        JsonNode preview = mapper.readTree(mvc.perform(get("/api/v1/contact-invite/{token}", plaintext))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(preview.path("isValid").asBoolean()).isTrue();

        JsonNode byHash = mapper.readTree(mvc.perform(get("/api/v1/contact-invite/{token}", stored))
                .andReturn().getResponse().getContentAsString()).path("data");
        assertThat(byHash.path("isValid").asBoolean()).isFalse();
        assertThat(byHash.has("issuer") && !byHash.path("issuer").isNull()).isFalse();

        assertThat(perform(accepterId, post("/api/v1/contact-invite/{token}/accept", stored)).getStatus())
                .isEqualTo(404);
        assertThat(perform(accepterId, post("/api/v1/contact-invite/{token}/accept", plaintext)).getStatus())
                .isEqualTo(200);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT used_count FROM contact_invite_tokens WHERE user_id = ?",
                Integer.class, issuerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-x4: 失効済み・期限切れ・自分発行は拒否され、応答コードは従来のまま")
    void 失効_期限切れ_自己発行は拒否される() throws Exception {
        String revoked = issue(issuerId, "7d").path("token").asText();
        long revokedId = jdbc.queryForObject("SELECT id FROM contact_invite_tokens WHERE token_hash = ?",
                Long.class, vault.hash(revoked));
        assertThat(perform(issuerId, delete("/api/v1/contact-invite-tokens/{id}", revokedId)).getStatus())
                .isEqualTo(204);
        assertThat(perform(accepterId, post("/api/v1/contact-invite/{token}/accept", revoked)).getStatus())
                .isEqualTo(404);

        String expired = vault.issueBase64Url().plaintext();
        repository.save(ContactInviteTokenEntity.builder().userId(issuerId).tokenHash(vault.hash(expired))
                .expiresAt(LocalDateTime.now().minusDays(1)).build());
        assertThat(perform(accepterId, post("/api/v1/contact-invite/{token}/accept", expired)).getStatus())
                .isEqualTo(404);

        String own = issue(issuerId, "7d").path("token").asText();
        assertThat(perform(issuerId, post("/api/v1/contact-invite/{token}/accept", own)).getStatus())
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    @DisplayName("AC-x5: 発行・プレビュー・承諾・失敗経路のログに平文のトークンを出さない")
    void ログに平文を出さない() throws Exception {
        String plaintext = issue(issuerId, "7d").path("token").asText();
        SecurityContextHolder.clearContext();
        mvc.perform(get("/api/v1/contact-invite/{token}", plaintext));
        perform(accepterId, post("/api/v1/contact-invite/{token}/accept", plaintext));
        perform(accepterId, post("/api/v1/contact-invite/{token}/accept", "unknown-" + plaintext));

        assertThat(logAppender.list).noneMatch(e -> e.getFormattedMessage().contains(plaintext)
                || (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                && e.getThrowableProxy().getMessage().contains(plaintext)));
    }

    private JsonNode issue(Long actor, String expiresIn) throws Exception {
        MockHttpServletResponse response = perform(actor, post("/api/v1/contact-invite-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"試練\",\"maxUses\":5,\"expiresIn\":\"" + expiresIn + "\"}"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    private MockHttpServletResponse perform(Long actor, RequestBuilder request) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
        return mvc.perform(request).andReturn().getResponse();
    }

    private Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private Set<String> columns() {
        return new TreeSet<>(jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'contact_invite_tokens'", String.class));
    }

    private Long insertUser(String email, String handle) {
        jdbc.update("INSERT INTO users (email, last_name, first_name, display_name, status, contact_handle, "
                + "is_searchable, handle_searchable, contact_approval_required, online_visibility, dm_receive_from, "
                + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, created_at, updated_at) "
                + "VALUES (?, 'CIH', 'テスト', 'CIH テスト', 'ACTIVE', ?, 1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', "
                + "'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())", email, handle);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }
}
