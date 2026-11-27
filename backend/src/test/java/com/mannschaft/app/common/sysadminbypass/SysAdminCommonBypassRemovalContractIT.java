package com.mannschaft.app.common.sysadminbypass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 戦役 Z-A PR3（試練）: 共通の素通し点 1〜2 の SYSTEM_ADMIN 短絡撤去を、代表 EP の実 HTTP 応答で固定する契約テスト。
 *
 * <h2>AC1（Gate 経由の代表 EP）— 所属の無い SYSTEM_ADMIN は不在 ID と完全に同じ応答（status・code・message）</h2>
 * <pre>
 * Gate メソッド                         | 代表 EP                                   | 不在時の応答
 * requireAdminOrConceal（requireOrConceal 経由） | GET    /shifts/requests?scheduleId=   | 404 SHIFT_001
 * requireOrConceal（許可条件=isMember）  | GET    /shifts/change-requests?scheduleId= | 404 SHIFT_001
 * requireMemberOrConceal                | POST   /shifts/requests                   | 404 SHIFT_001
 * requireOwnerOrAdminOrConceal          | DELETE /shifts/requests/{id}              | 404 SHIFT_003
 * </pre>
 *
 * <h2>AC2（@accessGuard 経由の代表 EP）— 所属の無い SYSTEM_ADMIN は false（403 COMMON_002）</h2>
 * <pre>
 * isScopeAdmin  | GET /admin/teams/{teamId}/supporter-name-disclosure/history
 * isScopeMember | GET /teams/{teamId}/beta-perks
 * （isScopeStrictAdmin・hasScopePermission は main に @accessGuard の呼出しが無いため単体テストで固定）
 * </pre>
 *
 * <p>AC-兼任（回帰）: 所属のある一般利用者（ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER）の許可・拒否は撤去前と同じ。</p>
 *
 * <p>SYSTEM_ADMIN の利用者には {@code ROLE_SYSTEM_ADMIN} の authority も持たせる（実運用の JWT と同じ形）。
 * authority があってもテナントの判定には使われないことを併せて固定する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("Z-A PR3: 共通の素通し点 1〜2 の SYSTEM_ADMIN 短絡撤去（代表 EP 契約）")
class SysAdminCommonBypassRemovalContractIT extends AbstractMySqlIntegrationTest {

    /** どのテーブルにも存在しない ID。 */
    private static final long MISSING_ID = 987_654_321L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftRequestRepository requestRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long adminId;
    private Long deputyId;
    private Long memberId;
    private Long supporterId;
    private Long systemAdminId;
    private Long outsiderId;

    private Long scheduleAId;
    private Long requestAId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("ZAPR3 チームA");

        adminId = insertUser("zapr3-admin@example.com");
        deputyId = insertUser("zapr3-deputy@example.com");
        memberId = insertUser("zapr3-member@example.com");
        supporterId = insertUser("zapr3-supporter@example.com");
        systemAdminId = insertUser("zapr3-system-admin@example.com");
        outsiderId = insertUser("zapr3-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, deputyId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, deputyId, "DEPUTY_ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        // 運営アカウント: プラットフォームロールのみ。テナントには所属しない（PR2 の所属禁止）。
        MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);

        scheduleAId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamAId)
                .title("ZAPR3 希望収集中シフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusDays(30))
                .createdBy(adminId)
                .build()).getId();
        requestAId = saveRequest(memberId, LocalDate.of(2026, 3, 2));

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC1: ScopeConcealingAccessGate 経由
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC1: Gate 経由の代表 EP で、所属の無い SYSTEM_ADMIN は不在 ID と完全に同じ応答")
    class GateRepresentativeEndpoints {

        @Test
        @DisplayName("requireAdminOrConceal: GET /shifts/requests?scheduleId= は 404 SHIFT_001（不在 scheduleId と同一）")
        void requireAdminOrConceal_listRequests() throws Exception {
            setSystemAdminAuth();
            assertSameAsMissing(
                    get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()),
                    get("/api/v1/shifts/requests").param("scheduleId", String.valueOf(MISSING_ID)),
                    404, "SHIFT_001");
        }

        @Test
        @DisplayName("requireOrConceal: GET /shifts/change-requests?scheduleId= は 404 SHIFT_001（不在 scheduleId と同一）")
        void requireOrConceal_listChangeRequests() throws Exception {
            setSystemAdminAuth();
            assertSameAsMissing(
                    get("/api/v1/shifts/change-requests").param("scheduleId", scheduleAId.toString()),
                    get("/api/v1/shifts/change-requests").param("scheduleId", String.valueOf(MISSING_ID)),
                    404, "SHIFT_001");
        }

        @Test
        @DisplayName("requireMemberOrConceal: POST /shifts/requests は 404 SHIFT_001（不在 scheduleId と同一）かつ行を作らない")
        void requireMemberOrConceal_submitRequest() throws Exception {
            setSystemAdminAuth();
            long before = countRequests();
            assertSameAsMissing(submit(scheduleAId), submit(MISSING_ID), 404, "SHIFT_001");
            assertThat(countRequests()).isEqualTo(before);
        }

        @Test
        @DisplayName("requireOwnerOrAdminOrConceal: DELETE /shifts/requests/{id} は 404 SHIFT_003（不在 requestId と同一）かつ削除しない")
        void requireOwnerOrAdminOrConceal_deleteRequest() throws Exception {
            setSystemAdminAuth();
            assertSameAsMissing(
                    delete("/api/v1/shifts/requests/{id}", requestAId),
                    delete("/api/v1/shifts/requests/{id}", MISSING_ID),
                    404, "SHIFT_003");
            assertThat(requestRepository.findById(requestAId)).isPresent();
        }

        @Test
        @DisplayName("前提: 越境者（所属なし・SYSTEM_ADMIN でない）の応答と SYSTEM_ADMIN の応答が一致する")
        void systemAdminEqualsOutsider() throws Exception {
            setAuth(outsiderId);
            ErrorView outsider = perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()));
            setSystemAdminAuth();
            ErrorView sysadmin = perform(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()));
            assertThat(sysadmin).isEqualTo(outsider);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC2: @accessGuard 経由
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC2: @accessGuard 経由の代表 EP で、所属の無い SYSTEM_ADMIN は 403（越境者・不在チームと同一）")
    class AccessGuardRepresentativeEndpoints {

        @Test
        @DisplayName("isScopeAdmin: GET /admin/teams/{teamId}/supporter-name-disclosure/history は 403 COMMON_002")
        void isScopeAdmin_disclosureHistory() throws Exception {
            setSystemAdminAuth();
            assertSameAsMissing(
                    get("/api/v1/admin/teams/{teamId}/supporter-name-disclosure/history", teamAId),
                    get("/api/v1/admin/teams/{teamId}/supporter-name-disclosure/history", MISSING_ID),
                    403, "COMMON_002");
        }

        @Test
        @DisplayName("isScopeMember: GET /teams/{teamId}/beta-perks は 403 COMMON_002")
        void isScopeMember_betaPerks() throws Exception {
            setSystemAdminAuth();
            assertSameAsMissing(
                    get("/api/v1/teams/{teamId}/beta-perks", teamAId),
                    get("/api/v1/teams/{teamId}/beta-perks", MISSING_ID),
                    403, "COMMON_002");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-兼任（回帰）: 所属のある一般利用者
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-兼任（回帰）: 所属のある一般利用者の判定は撤去前と同じ")
    class AffiliatedUsersUnchanged {

        @Test
        @DisplayName("ADMIN・DEPUTY_ADMIN: 希望一覧・変更依頼一覧・識別モード履歴・ベータ特典はすべて 200")
        void adminAndDeputy() throws Exception {
            for (Long actor : List.of(adminId, deputyId)) {
                setAuth(actor);
                assertStatus(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()), 200);
                assertStatus(get("/api/v1/shifts/change-requests").param("scheduleId", scheduleAId.toString()), 200);
                assertStatus(get("/api/v1/admin/teams/{teamId}/supporter-name-disclosure/history", teamAId), 200);
                assertStatus(get("/api/v1/teams/{teamId}/beta-perks", teamAId), 200);
            }
        }

        @Test
        @DisplayName("MEMBER: 変更依頼一覧・ベータ特典は 200、提出は 201、管理系は 403 COMMON_002（404 に化けない）")
        void member() throws Exception {
            setAuth(memberId);
            assertStatus(get("/api/v1/shifts/change-requests").param("scheduleId", scheduleAId.toString()), 200);
            assertStatus(get("/api/v1/teams/{teamId}/beta-perks", teamAId), 200);
            assertStatus(submit(scheduleAId), 201);
            expectError(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()), 403, "COMMON_002");
            expectError(get("/api/v1/admin/teams/{teamId}/supporter-name-disclosure/history", teamAId),
                    403, "COMMON_002");
        }

        @Test
        @DisplayName("MEMBER: 自分の希望は削除できる（本人経路）")
        void memberDeletesOwnRequest() throws Exception {
            setAuth(memberId);
            assertStatus(delete("/api/v1/shifts/requests/{id}", requestAId), 204);
        }

        @Test
        @DisplayName("SUPPORTER: ベータ特典は 200、提出は 403 COMMON_002（SUPPORTER 除外）、管理系は 403")
        void supporter() throws Exception {
            setAuth(supporterId);
            assertStatus(get("/api/v1/teams/{teamId}/beta-perks", teamAId), 200);
            expectError(submit(scheduleAId), 403, "COMMON_002");
            expectError(get("/api/v1/shifts/requests").param("scheduleId", scheduleAId.toString()), 403, "COMMON_002");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private RequestBuilder submit(Long scheduleId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scheduleId", scheduleId);
        body.put("slotDate", LocalDate.of(2026, 3, 3).toString());
        body.put("preference", "PREFERRED");
        body.put("note", "Z-A PR3 契約テスト");
        return post("/api/v1/shifts/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    /**
     * 実在リソースへの応答と不在 ID への応答が status・error.code・error.message まで一致することを検証する。
     * 両方とも壊れた偽 green を避けるため、期待する絶対値（status・code）も固定する。
     */
    private void assertSameAsMissing(RequestBuilder real, RequestBuilder missing,
                                     int expectedStatus, String expectedCode) throws Exception {
        ErrorView realView = perform(real);
        ErrorView missingView = perform(missing);
        assertThat(realView).as("実在IDへの応答と不在IDへの応答が一致すること").isEqualTo(missingView);
        assertThat(realView.status()).isEqualTo(expectedStatus);
        assertThat(realView.code()).isEqualTo(expectedCode);
        assertThat(realView.message()).isNotBlank();
    }

    private void expectError(RequestBuilder request, int expectedStatus, String expectedCode) throws Exception {
        ErrorView view = perform(request);
        assertThat(view.status()).isEqualTo(expectedStatus);
        assertThat(view.code()).isEqualTo(expectedCode);
    }

    private void assertStatus(RequestBuilder request, int expectedStatus) throws Exception {
        assertThat(mockMvc.perform(request).andReturn().getResponse().getStatus()).isEqualTo(expectedStatus);
    }

    private ErrorView perform(RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        JsonNode error = body.isBlank() ? null : objectMapper.readTree(body).path("error");
        return new ErrorView(result.getResponse().getStatus(),
                error == null ? null : error.path("code").asText(null),
                error == null ? null : error.path("message").asText(null));
    }

    private record ErrorView(int status, String code, String message) {
    }

    private long countRequests() {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM shift_requests").getSingleResult()).longValue();
    }

    private Long saveRequest(Long userId, LocalDate date) {
        return requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(scheduleAId)
                .userId(userId)
                .slotDate(date)
                .preference(ShiftPreference.PREFERRED)
                .note("希望します")
                .build()).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** 実運用の JWT と同じく ROLE_SYSTEM_ADMIN の authority を持たせる。 */
    private void setSystemAdminAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(systemAdminId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
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
                                + "VALUES (:email, 'ZAPR3', 'テスト', 'ZAPR3 テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
