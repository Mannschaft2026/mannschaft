package com.mannschaft.app.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventGuestInviteTokenRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 試練（CMP-260826-1457 W2・event 系統）: イベントゲスト招待トークンをハッシュで保存し、平文は発行応答にだけ出す。
 *
 * <ul>
 *   <li>AC-x1: 実体の列は {@code token_hash} だけで、保存値は {@code hash(平文)}（平文と異なる）。Entity に平文フィールドが無い。</li>
 *   <li>AC-x2: 発行応答の平文でゲスト登録が通り、DB のハッシュ値そのものでは通らない。</li>
 *   <li>AC-x3: 一覧・無効化の応答に平文を含まない（キー集合が許可フィールドと完全一致）。</li>
 *   <li>AC-x4: 他イベント・無効化済み・期限切れ・不存在は INVALID_INVITE_TOKEN（404 秘匿）で拒否。</li>
 *   <li>AC-x5: ログに平文を出さない。</li>
 * </ul>
 *
 * <p>red の理由: いまの main は {@code event_guest_invite_tokens.token} へ UUID の平文を保存し、
 * 一覧 API も token を返す。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260826-1457 W2 event: ゲスト招待トークンのハッシュ保存")
class EventGuestInviteTokenHashStorageIT extends AbstractMySqlIntegrationTest {

    private static final String BASE64URL_43 = "^[A-Za-z0-9_-]{43}$";
    private static final String SHA256_HEX = "^[0-9a-f]{64}$";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SecretTokenVault vault;
    @Autowired private EventRepository eventRepository;
    @Autowired private EventGuestInviteTokenRepository tokenRepository;
    @Autowired private EventTicketTypeRepository ticketTypeRepository;
    @PersistenceContext private EntityManager em;

    private Long adminId;
    private Long eventAId;
    private Long eventBId;
    private Long ticketTypeAId;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger appLogger;

    @BeforeEach
    void setUp() {
        String uniq = Long.toString(System.nanoTime(), 36);
        Long teamAId = insertTeam("EGH チームA " + uniq);
        Long teamBId = insertTeam("EGH チームB " + uniq);
        adminId = insertUser("egh-admin-" + uniq + "@example.test");
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamAId, null);
        eventAId = insertEvent(teamAId, "egh-a-" + uniq);
        eventBId = insertEvent(teamBId, "egh-b-" + uniq);
        ticketTypeAId = ticketTypeRepository.save(EventTicketTypeEntity.builder()
                .eventId(eventAId).name("EGH 一般")
                .price(BigDecimal.ZERO).currency("JPY").maxQuantity(100).build()).getId();
        em.flush();
        logAppender = new ListAppender<>();
        logAppender.start();
        appLogger = (Logger) LoggerFactory.getLogger("com.mannschaft.app.event");
        appLogger.setLevel(Level.TRACE);
        appLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        appLogger.detachAppender(logAppender);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-x1: 実スキーマは token_hash だけを持ち、保存値は hash(平文) で平文と異なる")
    void DBにはハッシュだけが残る() throws Exception {
        JsonNode issued = issue(eventAId);
        String plaintext = issued.path("token").asText();

        assertThat(columns()).contains("token_hash").doesNotContain("token");
        String stored = jdbc.queryForObject("SELECT token_hash FROM event_guest_invite_tokens WHERE id = ?",
                String.class, issued.path("id").asLong());
        assertThat(stored).matches(SHA256_HEX).isEqualTo(vault.hash(plaintext)).isNotEqualTo(plaintext);
    }

    @Test
    @DisplayName("AC-x1: Entity に平文の token フィールドが無く、tokenHash がある")
    void Entityに平文フィールドが無い() {
        assertThat(Arrays.stream(EventGuestInviteTokenEntity.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)).contains("tokenHash").doesNotContain("token");
    }

    @Test
    @DisplayName("AC-x2・x3: 発行応答だけが平文（Base64URL 43字）を含み、キー集合が許可フィールドと一致する")
    void 発行応答にだけ平文が載る() throws Exception {
        JsonNode issued = issue(eventAId);

        assertThat(keys(issued)).containsExactlyInAnyOrder("id", "eventId", "token", "label", "maxUses",
                "usedCount", "expiresAt", "isActive", "createdBy", "createdAt", "updatedAt");
        assertThat(issued.path("token").asText()).matches(BASE64URL_43);
    }

    @Test
    @DisplayName("AC-x3: 一覧・無効化の応答は平文（の一部も）を含まず、token キーを持たない")
    void 一覧と無効化の応答は平文を返さない() throws Exception {
        JsonNode issued = issue(eventAId);
        String plaintext = issued.path("token").asText();

        MockHttpServletResponse list = perform(adminId, get("/api/v1/events/{id}/invite-tokens", eventAId));
        assertThat(list.getStatus()).isEqualTo(200);
        JsonNode rows = mapper.readTree(list.getContentAsString()).path("data");
        assertThat(rows.size()).isEqualTo(1);
        assertThat(keys(rows.get(0))).containsExactlyInAnyOrder("id", "eventId", "label", "maxUses",
                "usedCount", "expiresAt", "isActive", "createdBy", "createdAt", "updatedAt");
        assertThat(list.getContentAsString()).doesNotContain(plaintext)
                .doesNotContain(plaintext.substring(0, 8)).doesNotContain(plaintext.substring(plaintext.length() - 8))
                .doesNotContain("inviteUrl").doesNotContain("qrCodeUrl");

        MockHttpServletResponse deact = perform(adminId, post("/api/v1/events/{id}/invite-tokens/{tid}/deactivate",
                eventAId, issued.path("id").asLong()));
        assertThat(deact.getStatus()).isEqualTo(200);
        assertThat(deact.getContentAsString()).doesNotContain(plaintext).doesNotContain("\"token\"");
    }

    @Test
    @DisplayName("AC-x2: 発行時の平文でゲスト登録が通り、DB のハッシュ値そのものでは通らない")
    void 平文で照合しハッシュ値では通らない() throws Exception {
        String plaintext = issue(eventAId).path("token").asText();
        String stored = jdbc.queryForObject("SELECT token_hash FROM event_guest_invite_tokens WHERE event_id = ?",
                String.class, eventAId);

        assertThat(guest(eventAId, stored).getStatus()).isEqualTo(404);
        MockHttpServletResponse ok = guest(eventAId, plaintext);
        assertThat(ok.getStatus()).as(ok.getContentAsString()).isEqualTo(201);
        em.flush();
        assertThat(jdbc.queryForObject("SELECT used_count FROM event_guest_invite_tokens WHERE event_id = ?",
                Integer.class, eventAId)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-x4: 他イベントの招待・無効化済み・期限切れ・不存在は INVALID_INVITE_TOKEN（404 秘匿）で拒否")
    void 不正な招待は拒否される() throws Exception {
        String plaintext = issue(eventAId).path("token").asText();
        assertThat(guest(eventBId, plaintext).getStatus()).as("他イベント").isEqualTo(404);

        String expired = vault.issueBase64Url().plaintext();
        tokenRepository.save(EventGuestInviteTokenEntity.builder().eventId(eventAId).tokenHash(vault.hash(expired))
                .expiresAt(LocalDateTime.now().minusDays(1)).build());
        assertThat(guest(eventAId, expired).getStatus()).as("期限切れ").isEqualTo(404);

        assertThat(guest(eventAId, "unknown-" + plaintext).getStatus()).as("不存在").isEqualTo(404);

        long id = jdbc.queryForObject("SELECT id FROM event_guest_invite_tokens WHERE token_hash = ?",
                Long.class, vault.hash(plaintext));
        assertThat(perform(adminId, post("/api/v1/events/{id}/invite-tokens/{tid}/deactivate", eventAId, id))
                .getStatus()).isEqualTo(200);
        em.flush();
        em.clear();
        assertThat(guest(eventAId, plaintext).getStatus()).as("無効化済み").isEqualTo(404);
    }

    @Test
    @DisplayName("AC-x5: 発行・登録・失敗経路のログに平文のトークンを出さない")
    void ログに平文を出さない() throws Exception {
        String plaintext = issue(eventAId).path("token").asText();
        guest(eventAId, plaintext);
        guest(eventAId, "unknown-" + plaintext);
        guest(eventBId, plaintext);

        assertThat(logAppender.list).noneMatch(e -> e.getFormattedMessage().contains(plaintext)
                || (e.getThrowableProxy() != null && e.getThrowableProxy().getMessage() != null
                && e.getThrowableProxy().getMessage().contains(plaintext)));
    }

    private JsonNode issue(Long eventId) throws Exception {
        MockHttpServletResponse response = perform(adminId, post("/api/v1/events/{id}/invite-tokens", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"試練\",\"maxUses\":5}"));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
        return mapper.readTree(response.getContentAsString()).path("data");
    }

    private MockHttpServletResponse guest(Long eventId, String token) throws Exception {
        SecurityContextHolder.clearContext();
        return mvc.perform(post("/api/v1/events/{id}/registrations/guest", eventId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"ticketTypeId\":" + ticketTypeAId + ",\"guestName\":\"ゲスト\","
                        + "\"guestEmail\":\"guest@example.test\",\"quantity\":1,\"inviteToken\":\"" + token + "\"}"))
                .andReturn().getResponse();
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
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'event_guest_invite_tokens'", String.class));
    }

    private Long insertEvent(Long teamId, String slug) {
        return eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamId).slug(slug)
                .status(EventStatus.REGISTRATION_OPEN).visibility(EventVisibility.MEMBERS_ONLY)
                .isApprovalRequired(false).build()).getId();
    }

    private Long insertUser(String email) {
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                + "is_searchable, handle_searchable, contact_approval_required, online_visibility, dm_receive_from, "
                + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, created_at, updated_at) "
                + "VALUES (:email, 'EGH', 'テスト', 'EGH テスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("email", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                + "created_at, updated_at) VALUES (:name, 'PUBLIC', 1, 0, 0, "
                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }
}
