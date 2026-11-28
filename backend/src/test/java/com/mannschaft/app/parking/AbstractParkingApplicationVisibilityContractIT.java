package com.mannschaft.app.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.RoleSeedSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    // AC-3 / AC-20: 非メンバー・非所属 SYSTEM_ADMIN
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-3: 非メンバーの申請一覧は 403（COMMON_002）・申請データを返さない")
    void AC3_非メンバーの申請一覧は403() throws Exception {
        insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(outsiderId, get(parkingBase(scopeAId) + "/applications"))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("COMMON_002");
        assertThat(objectMapper.readTree(r.getResponse().getContentAsString()).path("data").isMissingNode()
                || objectMapper.readTree(r.getResponse().getContentAsString()).path("data").isNull())
                .as("403 応答に申請データを含めない").isTrue();
    }

    @Test
    @DisplayName("AC-20: 所属していない SYSTEM_ADMIN の申請一覧は 403")
    void AC20_非所属SYSTEM_ADMINの申請一覧は403() throws Exception {
        Long sysAdmin = insertUser(emailPrefix() + "-sysadmin");
        MembershipTestHelper.insertUserRole(em, sysAdmin, "SYSTEM_ADMIN", null, null);
        insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(sysAdmin, get(parkingBase(scopeAId) + "/applications"))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-5 / AC-6
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: status フィルタ併用でも MEMBER は自分の申請のみ")
    void AC5_status併用でも本人分のみ() throws Exception {
        Long ownPending = insertApplication(spaceA1Id, memberAId, "PENDING");
        insertApplication(spaceA2Id, memberAId, "REJECTED");
        insertApplication(spaceA1Id, memberA2Id, "PENDING");
        insertApplication(spaceA3Id, adminAId, "PENDING");
        em.flush();

        JsonNode body = getJson(memberAId, parkingBase(scopeAId) + "/applications?status=PENDING");

        assertThat(idsOf(body)).containsExactly(ownPending);
        assertThat(userIdsOf(body)).containsOnly(memberAId);
    }

    @Test
    @DisplayName("AC-6: 申請0件の MEMBER は空配列で 200（他人の申請があっても見えない）")
    void AC6_申請0件のMEMBERは空配列200() throws Exception {
        insertApplication(spaceA1Id, memberA2Id, "PENDING");
        insertApplication(spaceA2Id, adminAId, "APPROVED");
        em.flush();

        JsonNode body = getJson(memberAId, parkingBase(scopeAId) + "/applications");

        assertThat(body.path("data").isArray()).isTrue();
        assertThat(body.path("data")).as("自分の申請が無ければ空配列").isEmpty();
        assertThat(body.path("meta").path("total").asLong()).isZero();
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-13: 譲渡希望申込の車両所有
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-13: 他人の vehicleId で POST /listings/{id}/apply は 404・申込は作られない")
    void AC13_譲渡希望申込で他人の車両は404() throws Exception {
        Long listingId = insertOpenListing(spaceA1Id, memberA2Id);
        Long othersVehicle = insertVehicle(adminAId);
        em.flush();

        MvcResult r = applyListing(memberAId, listingId, othersVehicle);

        assertThat(r.getResponse().getStatus()).isEqualTo(404);
        assertThat(countApplicationsOf(memberAId)).isZero();
    }

    @Test
    @DisplayName("AC-13: 存在しない vehicleId で POST /listings/{id}/apply は 404（他人の車両と同一コード）")
    void AC13_譲渡希望申込で不存在の車両は404() throws Exception {
        Long listingId = insertOpenListing(spaceA1Id, memberA2Id);
        Long othersVehicle = insertVehicle(adminAId);
        em.flush();

        MvcResult missing = applyListing(memberAId, listingId, othersVehicle + 900_000L);
        MvcResult others = applyListing(memberAId, listingId, othersVehicle);

        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(missing)).isEqualTo(errorCode(others));
    }

    @Test
    @DisplayName("AC-13（対照）: 自分の vehicleId なら譲渡希望に申し込める（201）")
    void AC13_譲渡希望申込で自分の車両は201() throws Exception {
        Long listingId = insertOpenListing(spaceA1Id, memberA2Id);
        Long ownVehicle = insertVehicle(memberAId);
        em.flush();

        MvcResult r = applyListing(memberAId, listingId, ownVehicle);

        assertThat(r.getResponse().getStatus()).isEqualTo(201);
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-14: 代理入力
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-14: 代理入力の申請は申請者(user_id)が代理対象者になり、本人の一覧に出る")
    void AC14_代理入力の申請者は代理対象者() throws Exception {
        Long subjectVehicle = insertVehicle(memberAId);
        Long consentId = insertProxyConsent(memberAId, adminAId);
        em.flush();

        MvcResult r = postProxyApplication(adminAId, memberAId, consentId, spaceA1Id, subjectVehicle);

        assertThat(r.getResponse().getStatus()).as("代理申請の応答: %s", r.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString()).path("data");
        assertThat(data.path("userId").asLong()).as("申請者は代理者ではなく代理対象者").isEqualTo(memberAId);
        Long appId = data.path("id").asLong();
        Object isProxy = em.createNativeQuery("SELECT is_proxy_input FROM parking_applications WHERE id = :id")
                .setParameter("id", appId).getSingleResult();
        assertThat(isProxy.toString()).isIn("1", "true");

        JsonNode subjectList = getJson(memberAId, parkingBase(scopeAId) + "/applications");
        assertThat(idsOf(subjectList)).as("代理対象者本人の一覧に出る").contains(appId);
    }

    @Test
    @DisplayName("AC-14: 代理入力の車両所有照合は代理対象者で行う（代理者の車両では 404）")
    void AC14_代理入力の車両照合は代理対象者() throws Exception {
        Long proxyOwnVehicle = insertVehicle(adminAId);
        Long consentId = insertProxyConsent(memberAId, adminAId);
        em.flush();

        MvcResult r = postProxyApplication(adminAId, memberAId, consentId, spaceA1Id, proxyOwnVehicle);

        assertThat(r.getResponse().getStatus()).as("代理者の車両は代理対象者の車両ではない").isEqualTo(404);
        assertThat(countApplicationsOf(memberAId) + countApplicationsOf(adminAId)).isZero();
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-15: SUPPORTER
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-15: SUPPORTER の POST /applications は 403（自分の車両でも申請不可）")
    void AC15_SUPPORTERの申請は403() throws Exception {
        Long supporter = insertUser(emailPrefix() + "-supporter");
        makeSupporter(supporter, scopeAId);
        Long ownVehicle = insertVehicle(supporter);
        em.flush();

        MvcResult r = postApplication(supporter, spaceA1Id, ownVehicle);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("COMMON_002");
        assertThat(countApplicationsOf(supporter)).isZero();
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-17: 一覧の入力検証（500 にしない）
    // ═════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-17 query: {0}")
    @ValueSource(strings = {"status=BOGUS", "status=pending", "page=-1", "size=0", "size=-5"})
    @DisplayName("AC-17: status 不正値・page<0・size<=0 は 400（500 にしない）")
    void AC17_不正な一覧パラメータは400(String query) throws Exception {
        MvcResult r = mockMvc.perform(as(memberAId, get(parkingBase(scopeAId) + "/applications?" + query))).andReturn();

        assertThat(r.getResponse().getStatus()).as("query=%s 応答=%s", query, r.getResponse().getContentAsString())
                .isEqualTo(400);
    }

    // ═════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════

    private MvcResult applyListing(Long userId, Long listingId, Long vehicleId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        return mockMvc.perform(as(userId, post(parkingBase(scopeAId) + "/listings/" + listingId + "/apply")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))).andReturn();
    }

    private MvcResult postProxyApplication(Long proxyUserId, Long subjectUserId, Long consentId,
                                           Long spaceId, Long vehicleId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("spaceId", spaceId);
        body.put("vehicleId", vehicleId);
        return mockMvc.perform(as(proxyUserId, post(parkingBase(scopeAId) + "/applications")
                .header("X-Proxy-For-User-Id", subjectUserId.toString())
                .header("X-Proxy-Consent-Id", consentId.toString())
                .header("X-Proxy-Input-Source", "PAPER_FORM")
                .header("X-Proxy-Original-Storage", "cabinet-A-2026")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))).andReturn();
    }

    /** 承認済み・有効期間内の代理入力同意書（PARKING_APPLICATION スコープ）を作る。 */
    private Long insertProxyConsent(Long subjectUserId, Long proxyUserId) {
        return insertProxyConsent(subjectUserId, proxyUserId, "PARKING_APPLICATION");
    }

    /** 承認済み・有効期間内の代理入力同意書を、指定の機能スコープ 1 つだけで作る。 */
    private Long insertProxyConsent(Long subjectUserId, Long proxyUserId, String featureScope) {
        em.createNativeQuery(
                        "INSERT INTO proxy_input_consents (subject_user_id, proxy_user_id, organization_id, "
                                + "consent_method, effective_from, effective_until, approved_by_user_id, approved_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:subject, :proxy, :org, 'PAPER_SIGNED', DATE_SUB(CURDATE(), INTERVAL 1 DAY), "
                                + "DATE_ADD(CURDATE(), INTERVAL 30 DAY), :approver, NOW(), NOW(), NOW())")
                .setParameter("subject", subjectUserId)
                .setParameter("proxy", proxyUserId)
                .setParameter("org", scopeAId)
                .setParameter("approver", adminBId)
                .executeUpdate();
        Long consentId = ((Number) em.createNativeQuery(
                        "SELECT MAX(id) FROM proxy_input_consents WHERE subject_user_id = :s AND proxy_user_id = :p")
                .setParameter("s", subjectUserId).setParameter("p", proxyUserId).getSingleResult()).longValue();
        em.createNativeQuery(
                        "INSERT INTO proxy_input_consent_scopes (proxy_input_consent_id, feature_scope, created_at) "
                                + "VALUES (:cid, :scope, NOW())")
                .setParameter("cid", consentId)
                .setParameter("scope", featureScope)
                .executeUpdate();
        return consentId;
    }

    /** 他人（listedBy）に割り当て済みの区画に OPEN の譲渡希望を作る。 */
    private Long insertOpenListing(Long spaceId, Long listedBy) {
        em.createNativeQuery(
                        "INSERT INTO parking_assignments (space_id, user_id, assigned_by, assigned_at, created_at, updated_at) "
                                + "VALUES (:spaceId, :userId, :assignedBy, NOW(), NOW(), NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("userId", listedBy)
                .setParameter("assignedBy", adminAId)
                .executeUpdate();
        Long assignmentId = ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_assignments")
                .getSingleResult()).longValue();
        em.createNativeQuery(
                        "INSERT INTO parking_listings (space_id, assignment_id, listed_by, reason, status, "
                                + "created_at, updated_at) "
                                + "VALUES (:spaceId, :assignmentId, :listedBy, '転居のため', 'OPEN', NOW(), NOW())")
                .setParameter("spaceId", spaceId)
                .setParameter("assignmentId", assignmentId)
                .setParameter("listedBy", listedBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM parking_listings").getSingleResult()).longValue();
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-21: 承認・却下・抽選の入口（権限判定を申請 ID の存在に先行させる）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-21: MEMBER / 権限なし DEPUTY の approve・reject・lottery は 403（COMMON_002）・状態不変")
    void AC21_権限のない者の承認却下抽選は403で状態不変() throws Exception {
        for (Long actor : List.of(memberAId, deputyNoPermId)) {
            Long appId = insertApplication(spaceA1Id, memberA2Id, "PENDING");
            em.flush();

            MvcResult approve = mockMvc.perform(as(actor,
                    patch(parkingBase(scopeAId) + "/applications/" + appId + "/approve"))).andReturn();
            MvcResult reject = rejectApplication(actor, appId);
            MvcResult lottery = mockMvc.perform(as(actor,
                    post(parkingBase(scopeAId) + "/applications/lottery?spaceId=" + spaceA1Id))).andReturn();

            for (MvcResult r : List.of(approve, reject, lottery)) {
                assertThat(r.getResponse().getStatus()).as("actor=%s 応答=%s", actor,
                        r.getResponse().getContentAsString()).isEqualTo(403);
                assertThat(errorCode(r)).isEqualTo("COMMON_002");
            }
            assertThat(applicationStatus(appId)).as("申請は PENDING のまま").isEqualTo("PENDING");
        }
    }

    @Test
    @DisplayName("AC-21: MEMBER が存在しない申請 ID を approve / reject しても存在する ID と同じ 403（存在オラクルなし）")
    void AC21_不存在IDも存在IDと区別不能な403() throws Exception {
        Long existing = insertApplication(spaceA1Id, memberA2Id, "PENDING");
        em.flush();
        long missing = existing + 900_000L;

        MvcResult approveExisting = mockMvc.perform(as(memberAId,
                patch(parkingBase(scopeAId) + "/applications/" + existing + "/approve"))).andReturn();
        MvcResult approveMissing = mockMvc.perform(as(memberAId,
                patch(parkingBase(scopeAId) + "/applications/" + missing + "/approve"))).andReturn();
        MvcResult rejectExisting = rejectApplication(memberAId, existing);
        MvcResult rejectMissing = rejectApplication(memberAId, missing);

        assertThat(approveMissing.getResponse().getStatus()).isEqualTo(403);
        assertThat(approveMissing.getResponse().getContentAsString())
                .isEqualTo(approveExisting.getResponse().getContentAsString());
        assertThat(rejectMissing.getResponse().getStatus()).isEqualTo(403);
        assertThat(rejectMissing.getResponse().getContentAsString())
                .isEqualTo(rejectExisting.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("AC-21: MANAGE_PARKING ありの DEPUTY_ADMIN の reject は成功（200・REJECTED）")
    void AC21_権限ありDEPUTYの却下は成功() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = rejectApplication(deputyWithPermId, appId);

        assertThat(r.getResponse().getStatus()).as("応答: %s", r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(applicationStatus(appId)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("AC-21: MANAGE_PARKING ありの DEPUTY_ADMIN の lottery は成功（200・申請が LOTTERY_PENDING）")
    void AC21_権限ありDEPUTYの抽選は成功() throws Exception {
        Long appId = insertApplication(spaceA1Id, memberAId, "PENDING");
        em.flush();

        MvcResult r = mockMvc.perform(as(deputyWithPermId,
                post(parkingBase(scopeAId) + "/applications/lottery?spaceId=" + spaceA1Id))).andReturn();

        assertThat(r.getResponse().getStatus()).as("応答: %s", r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(applicationStatus(appId)).isEqualTo("LOTTERY_PENDING");
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-22: 代理入力の検査（同意書スコープ・subject の資格）
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-22: PARKING_APPLICATION を含まない同意書の代理申請は 403・申請は作られない")
    void AC22_他機能スコープのみの同意書は403() throws Exception {
        Long subjectVehicle = insertVehicle(memberAId);
        Long consentId = insertProxyConsent(memberAId, adminAId, "SURVEY");
        em.flush();

        MvcResult r = postProxyApplication(adminAId, memberAId, consentId, spaceA1Id, subjectVehicle);

        assertThat(r.getResponse().getStatus()).as("応答: %s", r.getResponse().getContentAsString()).isEqualTo(403);
        assertThat(countApplicationsOf(memberAId)).isZero();
    }

    @Test
    @DisplayName("AC-22: 代理対象者がスコープ非所属・SUPPORTER の代理申請は 403・申請は作られない")
    void AC22_代理対象者が非所属またはSUPPORTERは403() throws Exception {
        Long supporter = insertUser(emailPrefix() + "-proxy-supporter");
        makeSupporter(supporter, scopeAId);
        for (Long subject : List.of(outsiderId, supporter)) {
            Long vehicle = insertVehicle(subject);
            Long consentId = insertProxyConsent(subject, adminAId);
            em.flush();

            MvcResult r = postProxyApplication(adminAId, subject, consentId, spaceA1Id, vehicle);

            assertThat(r.getResponse().getStatus()).as("subject=%s 応答=%s", subject,
                    r.getResponse().getContentAsString()).isEqualTo(403);
            assertThat(countApplicationsOf(subject)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // AC-23: 譲渡希望申込 POST /listings/{id}/apply の資格・代理入力
    // ═════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-23: SUPPORTER の譲渡希望申込は 403（自分の車両でも不可）・申込は作られない")
    void AC23_SUPPORTERの譲渡希望申込は403() throws Exception {
        Long listingId = insertOpenListing(spaceA1Id, memberA2Id);
        Long supporter = insertUser(emailPrefix() + "-listing-supporter");
        makeSupporter(supporter, scopeAId);
        Long ownVehicle = insertVehicle(supporter);
        em.flush();

        MvcResult r = applyListing(supporter, listingId, ownVehicle);

        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        assertThat(errorCode(r)).isEqualTo("COMMON_002");
        assertThat(countApplicationsOf(supporter)).isZero();
    }

    @Test
    @DisplayName("AC-23: 代理入力の譲渡希望申込は申請者が代理対象者になり、車両照合も代理対象者で行う（201）")
    void AC23_代理入力の譲渡希望申込は代理対象者() throws Exception {
        Long listingId = insertOpenListing(spaceA1Id, memberA2Id);
        Long subjectVehicle = insertVehicle(memberAId);
        Long consentId = insertProxyConsent(memberAId, adminAId);
        em.flush();

        MvcResult r = applyListingAsProxy(adminAId, memberAId, consentId, listingId, subjectVehicle);

        assertThat(r.getResponse().getStatus()).as("応答: %s", r.getResponse().getContentAsString()).isEqualTo(201);
        assertThat(objectMapper.readTree(r.getResponse().getContentAsString()).path("data").path("userId").asLong())
                .isEqualTo(memberAId);
        assertThat(countApplicationsOf(adminAId)).isZero();
    }

    @Test
    @DisplayName("AC-23: 代理入力の譲渡希望申込で代理者の車両は 404、他機能スコープ同意書・非所属の対象者は 403")
    void AC23_代理入力の譲渡希望申込の異常系() throws Exception {
        Long listingId = insertOpenListing(spaceA1Id, memberA2Id);
        Long proxyOwnVehicle = insertVehicle(adminAId);
        Long consentOk = insertProxyConsent(memberAId, adminAId);
        Long surveyConsent = insertProxyConsent(memberA2Id, adminAId, "SURVEY");
        Long subjectVehicle2 = insertVehicle(memberA2Id);
        Long outsiderVehicle = insertVehicle(outsiderId);
        Long outsiderConsent = insertProxyConsent(outsiderId, adminAId);
        em.flush();

        assertThat(applyListingAsProxy(adminAId, memberAId, consentOk, listingId, proxyOwnVehicle)
                .getResponse().getStatus()).as("代理者の車両").isEqualTo(404);
        assertThat(applyListingAsProxy(adminAId, memberA2Id, surveyConsent, listingId, subjectVehicle2)
                .getResponse().getStatus()).as("他機能スコープのみ").isEqualTo(403);
        assertThat(applyListingAsProxy(adminAId, outsiderId, outsiderConsent, listingId, outsiderVehicle)
                .getResponse().getStatus()).as("非所属の対象者").isEqualTo(403);
        assertThat(countApplicationsOf(memberAId) + countApplicationsOf(memberA2Id)
                + countApplicationsOf(outsiderId) + countApplicationsOf(adminAId)).isZero();
    }

    private MvcResult rejectApplication(Long userId, long appId) throws Exception {
        return mockMvc.perform(as(userId, patch(parkingBase(scopeAId) + "/applications/" + appId + "/reject")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectionReason\":\"対象外\"}"))).andReturn();
    }

    private MvcResult applyListingAsProxy(Long proxyUserId, Long subjectUserId, Long consentId,
                                          Long listingId, Long vehicleId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("vehicleId", vehicleId);
        return mockMvc.perform(as(proxyUserId, post(parkingBase(scopeAId) + "/listings/" + listingId + "/apply")
                .header("X-Proxy-For-User-Id", subjectUserId.toString())
                .header("X-Proxy-Consent-Id", consentId.toString())
                .header("X-Proxy-Input-Source", "PAPER_FORM")
                .header("X-Proxy-Original-Storage", "cabinet-A-2026")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))).andReturn();
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
            RoleSeedSupport.ensureCanonical(em, role);
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
