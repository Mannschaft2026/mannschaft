package com.mannschaft.app.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260930-0233 試練: 駐車場申請の閲覧範囲・車両所有・取消の API 契約（TEAM / ORGANIZATION 共通部）。
 *
 * <p>正本: {@code docs/features/F09.3_parking.md} §2 権限表・申請一覧/申請/取消 EP、
 * マスター裁定（MANAGE_PARKING 新設・他人/不存在 vehicleId は 404 統一・取消は申請者本人のみ・
 * 非所属 SYSTEM_ADMIN は 403 維持）。</p>
 *
 * <p>試練規約（{@code .claude/commands/試練.md}・{@code TEST_CONVENTION.md} §3.1.2）に従い、
 * 基底 {@link AbstractMySqlIntegrationTest} の MOCK 設定（{@code @AutoConfigureMockMvc}・addFilters=true）を
 * そのまま継承し、<b>実 Security フィルタ鎖・実 Controller・実 AccessControlService・実 MySQL</b> を通す。
 * 認証は {@code JwtAuthenticationFilter} が作るのと同じ形（principal=userId の生 String）の Authentication を
 * {@code authentication(...)} で差す。DB・認可・自前 Bean はモックしない。</p>
 *
 * <p>本基底は TEAM / ORGANIZATION の両スコープで同じ受け入れ条件
 * （AC-1, 2, 4, 7, 11, 12, 16 = AC-18 の対象）を検証するための共通テスト群を持つ。
 * スコープ固有の差分（スコープ作成・URL・user_roles/権限グループの列）は派生クラスが与える。</p>
 *
 * <p>JUnit の {@code @EnabledIf} は継承されないため、派生クラスで再宣言すること。
 * {@code @Transactional} は本基底に置く（基底で宣言した試験メソッドは宣言クラス側の注釈で
 * トランザクション属性が解決されるため、派生クラスだけに付けると基底のメソッドが非トランザクションになる）。</p>
 */
@Transactional
abstract class AbstractParkingApplicationVisibilityContractIT extends AbstractMySqlIntegrationTest {

    /** 新設権限（V183 MANAGE_RECRUITMENTS と同型: ADMIN 自動付与・DEPUTY_ADMIN 手動付与）。 */
    protected static final String MANAGE_PARKING = "MANAGE_PARKING";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @PersistenceContext
    protected EntityManager em;

    /** 検証対象スコープ A（主役）。 */
    protected Long scopeAId;
    /** 越境確認用のスコープ B。 */
    protected Long scopeBId;

    protected Long adminAId;
    protected Long adminBId;
    /** スコープ A の一般メンバー（主役）。 */
    protected Long memberAId;
    /** スコープ A の別の一般メンバー（「他人」役）。 */
    protected Long memberA2Id;
    /** スコープ A の DEPUTY_ADMIN（MANAGE_PARKING 個別付与なし）。 */
    protected Long deputyNoPermId;
    /** スコープ A の DEPUTY_ADMIN（権限グループ経由で MANAGE_PARKING 付与あり）。 */
    protected Long deputyWithPermId;
    protected Long outsiderId;

    /** 申請受付中の区画（スコープ A）。 */
    protected Long spaceA1Id;
    protected Long spaceA2Id;
    protected Long spaceA3Id;
    /** スコープ B の区画。 */
    protected Long spaceB1Id;

    // ─── 派生クラスが与えるスコープ固有部 ───────────────────────────────

    /** {@code "TEAM"} または {@code "ORGANIZATION"}。 */
    protected abstract String scopeType();

    /** スコープ（チーム / 組織）を作成し id を返す。 */
    protected abstract Long insertScope(String name);

    /** URL のスコープ区分（{@code teams} / {@code organizations}）。 */
    protected abstract String urlSegment();

    /** テストデータのメールアドレス接頭辞（他クラスとの衝突回避）。 */
    protected abstract String emailPrefix();

    protected final ScopeType membershipScopeType() {
        return ScopeType.valueOf(scopeType());
    }

    protected final String parkingBase(Long scopeId) {
        return "/api/v1/" + urlSegment() + "/" + scopeId + "/parking";
    }

    // ─── fixture ─────────────────────────────────────────────────────

    @BeforeEach
    void setUpVisibilityFixture() {
        seedManageParkingPermission();

        scopeAId = insertScope("PKVIS-" + scopeType() + "-A-" + shortUuid());
        scopeBId = insertScope("PKVIS-" + scopeType() + "-B-" + shortUuid());

        adminAId = insertUser(emailPrefix() + "-admin-a");
        adminBId = insertUser(emailPrefix() + "-admin-b");
        memberAId = insertUser(emailPrefix() + "-member-a");
        memberA2Id = insertUser(emailPrefix() + "-member-a2");
        deputyNoPermId = insertUser(emailPrefix() + "-deputy-noperm");
        deputyWithPermId = insertUser(emailPrefix() + "-deputy-perm");
        outsiderId = insertUser(emailPrefix() + "-outsider");

        makeAdmin(adminAId, scopeAId);
        makeAdmin(adminBId, scopeBId);
        makeMember(memberAId, scopeAId);
        makeMember(memberA2Id, scopeAId);
        makeDeputy(deputyNoPermId, scopeAId);
        makeDeputy(deputyWithPermId, scopeAId);
        grantManageParkingToDeputy(deputyWithPermId, scopeAId);

        spaceA1Id = insertParkingSpace(scopeAId, "VA-01");
        spaceA2Id = insertParkingSpace(scopeAId, "VA-02");
        spaceA3Id = insertParkingSpace(scopeAId, "VA-03");
        spaceB1Id = insertParkingSpace(scopeBId, "VB-01");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-1 / AC-2 / AC-4 / AC-12: 一覧の閲覧範囲
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1: MEMBER の申請一覧は自分の申請のみ（他人の申請が1件も含まれない）")
    void AC1_MEMBERの申請一覧は自分の申請のみ() throws Exception {
        Long own = insertApplication(spaceA1Id, memberAId, "PENDING");
        insertApplication(spaceA1Id, memberA2Id, "PENDING");
        insertApplication(spaceA2Id, memberA2Id, "APPROVED");
        insertApplication(spaceA3Id, adminAId, "PENDING");
        em.flush();

        JsonNode body = getJson(memberAId, parkingBase(scopeAId) + "/applications");

        assertThat(userIdsOf(body)).as("MEMBER の一覧に他人の申請が含まれてはならない").containsOnly(memberAId);
        assertThat(idsOf(body)).containsExactly(own);
    }

    @Test
    @DisplayName("AC-2: ADMIN は同スコープ全員分の申請を取得できる")
    void AC2_ADMINは同スコープ全員分を取得() throws Exception {
        Long a = insertApplication(spaceA1Id, memberAId, "PENDING");
        Long b = insertApplication(spaceA1Id, memberA2Id, "PENDING");
        Long c = insertApplication(spaceA2Id, memberA2Id, "APPROVED");
        em.flush();

        JsonNode body = getJson(adminAId, parkingBase(scopeAId) + "/applications");

        assertThat(idsOf(body)).containsExactlyInAnyOrder(a, b, c);
        assertThat(body.path("meta").path("total").asLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("AC-4: 両スコープに所属する ADMIN でも、スコープ A の URL では B の申請を得られない（B の URL でも A を得られない）")
    void AC4_他スコープの申請を得られない() throws Exception {
        // adminA はスコープ B にも一般メンバーとして所属させる（B の URL は叩けるが、B では MEMBER）
        makeMember(adminAId, scopeBId);
        Long inA = insertApplication(spaceA1Id, memberAId, "PENDING");
        Long inB = insertApplication(spaceB1Id, adminBId, "PENDING");
        Long ownInB = insertApplication(spaceB1Id, adminAId, "PENDING");
        em.flush();

        JsonNode viaA = getJson(adminAId, parkingBase(scopeAId) + "/applications");
        assertThat(idsOf(viaA)).contains(inA).doesNotContain(inB, ownInB);
        assertThat(spaceIdsOf(viaA)).doesNotContain(spaceB1Id);

        // B の URL では A の申請は出ない（B での本人限定は AC-1 の検査対象なのでここでは問わない）
        JsonNode viaB = getJson(adminAId, parkingBase(scopeBId) + "/applications");
        assertThat(idsOf(viaB)).contains(ownInB).doesNotContain(inA);
        assertThat(spaceIdsOf(viaB)).doesNotContain(spaceA1Id, spaceA2Id, spaceA3Id);
    }

    @Test
    @DisplayName("AC-4: 他スコープの ADMIN（非所属）がスコープ A の申請一覧を叩くと 403")
    void AC4_他スコープADMINは403() throws Exception {
        insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        mockMvc.perform(as(adminBId, get(parkingBase(scopeAId) + "/applications")))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isEqualTo(403));
    }

    @Test
    @DisplayName("AC-12: MEMBER の meta.total / totalPages は絞り込み後の件数（他人の申請数が漏れない）")
    void AC12_metaは絞り込み後の件数() throws Exception {
        insertApplication(spaceA1Id, memberAId, "PENDING");
        insertApplication(spaceA1Id, memberA2Id, "PENDING");
        insertApplication(spaceA2Id, memberA2Id, "PENDING");
        insertApplication(spaceA3Id, memberA2Id, "REJECTED");
        insertApplication(spaceA3Id, adminAId, "PENDING");
        em.flush();

        JsonNode body = getJson(memberAId, parkingBase(scopeAId) + "/applications?page=0&size=1");

        assertThat(body.path("meta").path("total").asLong()).as("meta.total に他人の申請数が漏れない").isEqualTo(1L);
        assertThat(body.path("meta").path("totalPages").asInt()).as("totalPages に他人の申請数が漏れない").isEqualTo(1);
        assertThat(userIdsOf(body)).containsOnly(memberAId);
    }

    @Test
    @DisplayName("AC-12: status 併用時も meta.total / totalPages は本人分のみ")
    void AC12_status併用時もmetaは本人分() throws Exception {
        insertApplication(spaceA1Id, memberAId, "PENDING");
        insertApplication(spaceA2Id, memberAId, "REJECTED");
        insertApplication(spaceA1Id, memberA2Id, "PENDING");
        insertApplication(spaceA2Id, memberA2Id, "PENDING");
        insertApplication(spaceA3Id, adminAId, "PENDING");
        em.flush();

        JsonNode body = getJson(memberAId, parkingBase(scopeAId) + "/applications?status=PENDING&page=0&size=1");

        assertThat(body.path("meta").path("total").asLong()).isEqualTo(1L);
        assertThat(body.path("meta").path("totalPages").asInt()).isEqualTo(1);
        assertThat(userIdsOf(body)).containsOnly(memberAId);
        assertThat(statusesOf(body)).containsOnly("PENDING");
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-11: MANAGE_PARKING と DEPUTY_ADMIN
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11: MANAGE_PARKING なしの DEPUTY_ADMIN の申請一覧は本人分のみ")
    void AC11_権限なしDEPUTYの一覧は本人分のみ() throws Exception {
        Long own = insertApplication(spaceA1Id, deputyNoPermId, "PENDING");
        insertApplication(spaceA1Id, memberAId, "PENDING");
        insertApplication(spaceA2Id, memberA2Id, "PENDING");
        em.flush();

        JsonNode body = getJson(deputyNoPermId, parkingBase(scopeAId) + "/applications");

        assertThat(idsOf(body)).containsExactly(own);
        assertThat(body.path("meta").path("total").asLong()).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-11: MANAGE_PARKING ありの DEPUTY_ADMIN は全件を取得できる")
    void AC11_権限ありDEPUTYは全件() throws Exception {
        Long a = insertApplication(spaceA1Id, memberAId, "PENDING");
        Long b = insertApplication(spaceA2Id, memberA2Id, "PENDING");
        em.flush();

        JsonNode body = getJson(deputyWithPermId, parkingBase(scopeAId) + "/applications");

        assertThat(idsOf(body)).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("AC-11: MANAGE_PARKING なしの DEPUTY_ADMIN の承認は 403（COMMON_002）・申請は PENDING のまま")
    void AC11_権限なしDEPUTYの承認は403() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(deputyNoPermId,
                patch(parkingBase(scopeAId) + "/applications/" + appId + "/approve"))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("COMMON_002");
        assertThat(applicationStatus(appId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("AC-11: MANAGE_PARKING ありの DEPUTY_ADMIN の承認は成功（200・APPROVED）")
    void AC11_権限ありDEPUTYの承認は成功() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(deputyWithPermId,
                patch(parkingBase(scopeAId) + "/applications/" + appId + "/approve"))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(r.getResponse().getContentAsString())
                .path("data").path("status").asText()).isEqualTo("APPROVED");
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-7: 他人 / 存在しない vehicleId での申請は 404（区別不能）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-7: 他人の vehicleId / 存在しない vehicleId で POST /applications は 404・応答本文も区別不能・申請は作られない")
    void AC7_他人または不存在の車両は404で区別不能() throws Exception {
        Long othersVehicle = insertVehicle(memberA2Id);
        insertVehicle(memberAId);
        long missingVehicle = othersVehicle + 900_000L;
        em.flush();

        MvcResult others = postApplication(memberAId, spaceA1Id, othersVehicle);
        MvcResult missing = postApplication(memberAId, spaceA2Id, missingVehicle);

        assertThat(others.getResponse().getStatus()).as("他人の車両").isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).as("存在しない車両").isEqualTo(404);
        JsonNode e1 = objectMapper.readTree(others.getResponse().getContentAsString()).path("error");
        JsonNode e2 = objectMapper.readTree(missing.getResponse().getContentAsString()).path("error");
        assertThat(e1.path("code").asText()).as("他人/不存在で同一エラーコード（存在オラクル封じ）")
                .isEqualTo(e2.path("code").asText()).isNotBlank();
        assertThat(e1.path("message").asText()).isEqualTo(e2.path("message").asText());
        assertThat(countApplicationsOf(memberAId)).as("404 のとき申請行は作られない").isZero();
    }

    @Test
    @DisplayName("AC-7（対照）: 自分の vehicleId なら申請できる（201）")
    void AC7_自分の車両なら201() throws Exception {
        Long ownVehicle = insertVehicle(memberAId);
        em.flush();

        MvcResult r = postApplication(memberAId, spaceA1Id, ownVehicle);

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(data.path("userId").asLong()).isEqualTo(memberAId);
        assertThat(data.path("vehicleId").asLong()).isEqualTo(ownVehicle);
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-16: 取消は申請者本人のみ・決定済みは 409
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-16: 他メンバーの申請 id への DELETE は 403（申請は取り消されない）")
    void AC16_他メンバーの申請取消は403() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberA2Id, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(memberAId, delete(parkingBase(scopeAId) + "/applications/" + appId))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(applicationStatus(appId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("AC-16: ADMIN でも他人の申請の DELETE は 403（取消は申請者本人のみ）")
    void AC16_ADMINの他人申請取消も403() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(adminAId, delete(parkingBase(scopeAId) + "/applications/" + appId))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("COMMON_002");
        assertThat(applicationStatus(appId)).as("ADMIN の取消は無効").isEqualTo("PENDING");
    }

    @Test
    @DisplayName("AC-16: 本人の決定済み（APPROVED）申請の DELETE は 409（PARKING_015）")
    void AC16_本人の承認済み申請取消は409() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "APPROVED");
        em.flush();

        MvcResult r = mockMvc.perform(as(memberAId, delete(parkingBase(scopeAId) + "/applications/" + appId))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("PARKING_015");
        assertThat(applicationStatus(appId)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("AC-16: 本人の決定済み（REJECTED）申請の DELETE は 409（PARKING_015）")
    void AC16_本人の却下済み申請取消は409() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "REJECTED");
        em.flush();

        MvcResult r = mockMvc.perform(as(memberAId, delete(parkingBase(scopeAId) + "/applications/" + appId))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo("PARKING_015");
    }

    @Test
    @DisplayName("AC-16（対照）: 本人の PENDING 申請の DELETE は 204 で CANCELLED になる")
    void AC16_本人のPENDING申請取消は204() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(memberAId, delete(parkingBase(scopeAId) + "/applications/" + appId))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(204);
        em.flush();
        em.clear();
        assertThat(applicationStatus(appId)).isEqualTo("CANCELLED");
    }

    // ═════════════════════════════════════════════════════════════════
    // HTTP ヘルパー
    // ═════════════════════════════════════════════════════════════════

    /** JwtAuthenticationFilter と同じ形（principal=userId の生 String）の Authentication を差す。 */
    protected MockHttpServletRequestBuilder as(Long userId, MockHttpServletRequestBuilder builder) {
        return builder.with(authentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of())));
    }

    protected JsonNode getJson(Long userId, String url) throws Exception {
        MvcResult r = mockMvc.perform(as(userId, get(url))).andReturn();
        assertThat(r.getResponse().getStatus()).as("GET %s の応答: %s", url, r.getResponse().getContentAsString())
                .isEqualTo(200);
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    protected MvcResult postApplication(Long userId, Long spaceId, Long vehicleId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", spaceId);
        body.put("vehicleId", vehicleId);
        return mockMvc.perform(as(userId, post(parkingBase(scopeAId) + "/applications")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))).andReturn();
    }

    protected String errorCode(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString()).path("error").path("code").asText();
    }

    protected static List<Long> idsOf(JsonNode body) {
        return longsOf(body, "id");
    }

    protected static List<Long> userIdsOf(JsonNode body) {
        return longsOf(body, "userId");
    }

    protected static List<Long> spaceIdsOf(JsonNode body) {
        return longsOf(body, "spaceId");
    }

    protected static List<String> statusesOf(JsonNode body) {
        List<String> out = new ArrayList<>();
        body.path("data").forEach(n -> out.add(n.path("status").asText()));
        return out;
    }

    private static List<Long> longsOf(JsonNode body, String field) {
        assertThat(body.path("data").isArray()).as("data は配列であること: %s", body).isTrue();
        List<Long> out = new ArrayList<>();
        body.path("data").forEach(n -> out.add(n.path(field).asLong()));
        return out;
    }

    // ═════════════════════════════════════════════════════════════════
    // DB fixture ヘルパー
    // ═════════════════════════════════════════════════════════════════

    protected static String shortUuid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    protected void makeAdmin(Long userId, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, membershipScopeType(), scopeId, RoleKind.MEMBER);
        insertScopedUserRole(userId, "ADMIN", scopeId);
    }

    protected void makeDeputy(Long userId, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, membershipScopeType(), scopeId, RoleKind.MEMBER);
        insertScopedUserRole(userId, "DEPUTY_ADMIN", scopeId);
    }

    protected void makeMember(Long userId, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, membershipScopeType(), scopeId, RoleKind.MEMBER);
    }

    protected void makeSupporter(Long userId, Long scopeId) {
        MembershipTestHelper.insertMembership(em, userId, membershipScopeType(), scopeId, RoleKind.SUPPORTER);
    }

    private void insertScopedUserRole(Long userId, String roleName, Long scopeId) {
        boolean team = "TEAM".equals(scopeType());
        MembershipTestHelper.insertUserRole(em, userId, roleName, team ? scopeId : null, team ? null : scopeId);
    }

    /**
     * {@code MANAGE_PARKING} を権限カタログへ登録し ADMIN へ is_default=1 で自動付与する
     * （出陣で追加される本番マイグレーションの写し。V183 MANAGE_RECRUITMENTS と同型）。
     * 試験環境は ddl-auto=create / flyway 無効のため permissions は空表で作られる。
     */
    private void seedManageParkingPermission() {
        for (String role : List.of("ADMIN", "DEPUTY_ADMIN")) {
            em.createNativeQuery(
                            "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                    + "SELECT :name, :name, :priority, 0, NOW(), NOW() FROM DUAL "
                                    + "WHERE NOT EXISTS (SELECT 1 FROM roles WHERE name = :name)")
                    .setParameter("name", role)
                    .setParameter("priority", "ADMIN".equals(role) ? 2 : 3)
                    .executeUpdate();
        }
        em.createNativeQuery(
                        "INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                                + "SELECT :name, '駐車場管理', 'TEAM', NOW(), NOW() FROM DUAL "
                                + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = :name)")
                .setParameter("name", MANAGE_PARKING)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO role_permissions (role_id, permission_id, is_default, created_at) "
                                + "SELECT r.id, p.id, 1, NOW() FROM roles r CROSS JOIN permissions p "
                                + "WHERE r.name = 'ADMIN' AND p.name = :name "
                                + "AND NOT EXISTS (SELECT 1 FROM role_permissions rp "
                                + "WHERE rp.role_id = r.id AND rp.permission_id = p.id)")
                .setParameter("name", MANAGE_PARKING)
                .executeUpdate();
    }

    /** 権限グループ（target_role=DEPUTY_ADMIN）経由で MANAGE_PARKING を個別付与する（F03.11 §13「手動付与」）。 */
    private void grantManageParkingToDeputy(Long userId, Long scopeId) {
        boolean team = "TEAM".equals(scopeType());
        String groupName = "駐車場管理-" + shortUuid();
        em.createNativeQuery(
                        "INSERT INTO permission_groups (team_id, organization_id, target_role, name, created_by, "
                                + "deleted_at, created_at, updated_at) "
                                + "VALUES (:teamId, :orgId, 'DEPUTY_ADMIN', :name, NULL, NULL, NOW(), NOW())")
                .setParameter("teamId", team ? scopeId : null)
                .setParameter("orgId", team ? null : scopeId)
                .setParameter("name", groupName)
                .executeUpdate();
        long groupId = ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", groupName).getSingleResult()).longValue();
        em.createNativeQuery(
                        "INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                                + "SELECT :groupId, p.id, NOW() FROM permissions p WHERE p.name = :perm")
                .setParameter("groupId", groupId)
                .setParameter("perm", MANAGE_PARKING)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO user_permission_groups (user_id, group_id, assigned_by, created_at) "
                                + "VALUES (:userId, :groupId, NULL, NOW())")
                .setParameter("userId", userId)
                .setParameter("groupId", groupId)
                .executeUpdate();
    }

    protected Long insertUser(String localPart) {
        String email = localPart + "-" + shortUuid() + "@example.com";
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'PKVis', 'テスト', 'PK閲覧範囲テスト', 'ACTIVE', "
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

    protected Long insertParkingSpace(Long scopeId, String spaceNumber) {
        em.createNativeQuery(
                        "INSERT INTO parking_spaces (scope_type, scope_id, space_number, space_type, "
                                + "status, application_status, created_by, created_at, updated_at) "
                                + "VALUES (:st, :sid, :num, 'INDOOR', 'VACANT', 'ACCEPTING', :createdBy, NOW(), NOW())")
                .setParameter("createdBy", adminAId != null ? adminAId : adminBId)
                .setParameter("st", scopeType())
                .setParameter("sid", scopeId)
                .setParameter("num", spaceNumber)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM parking_spaces WHERE scope_type = :st AND scope_id = :sid AND space_number = :num")
                .setParameter("st", scopeType())
                .setParameter("sid", scopeId)
                .setParameter("num", spaceNumber)
                .getSingleResult()).longValue();
    }

    protected Long insertVehicle(Long ownerUserId) {
        em.createNativeQuery(
                        "INSERT INTO registered_vehicles (user_id, vehicle_type, plate_number, plate_number_hash, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'CAR', :plate, SHA2(UUID(), 256), NOW(), NOW())")
                .setParameter("uid", ownerUserId)
                .setParameter("plate", ("ENC-PLATE-" + shortUuid()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM registered_vehicles WHERE user_id = :uid")
                .setParameter("uid", ownerUserId).getSingleResult()).longValue();
    }

    /**
     * 申請行を native INSERT する。vehicle_id は申請者本人の実車両を作って紐づける
     * （source_type / priority は @Builder.Default のみで DB デフォルトを持たないため明示）。
     */
    protected Long insertApplication(Long spaceId, Long userId, String status) {
        Long vehicleId = insertVehicle(userId);
        em.createNativeQuery(
                        "INSERT INTO parking_applications (space_id, user_id, vehicle_id, source_type, priority, "
                                + "status, created_at) "
                                + "VALUES (:spaceId, :userId, :vehicleId, 'VACANCY', 0, :status, NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("userId", userId)
                .setParameter("vehicleId", vehicleId)
                .setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_applications").getSingleResult()).longValue();
    }

    protected String applicationStatus(Long appId) {
        return (String) em.createNativeQuery("SELECT status FROM parking_applications WHERE id = :id")
                .setParameter("id", appId).getSingleResult();
    }

    protected long countApplicationsOf(Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM parking_applications WHERE user_id = :uid")
                .setParameter("uid", userId).getSingleResult()).longValue();
    }
}
