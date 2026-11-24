package com.mannschaft.app.parking;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-260930-0233 試練（TEAM スコープ）: 駐車場申請の閲覧範囲・車両所有・取消の API 契約。
 *
 * <p>共通の受け入れ条件（AC-1, 2, 4, 7, 11, 12, 16）は {@link AbstractParkingApplicationVisibilityContractIT}。
 * 本クラスはチーム固有に置く AC-3, 5, 6, 13, 14, 15, 17, 20 を持つ。
 * 実 Security フィルタ鎖（addFilters=true・基底既定）＋実 MySQL で検証する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260930-0233 駐車場申請の閲覧範囲・車両所有・取消（TEAM・試練）")
class ParkingApplicationVisibilityContractIT extends AbstractParkingApplicationVisibilityContractIT {

    @Override
    protected String scopeType() {
        return "TEAM";
    }

    @Override
    protected String urlSegment() {
        return "teams";
    }

    @Override
    protected String emailPrefix() {
        return "pkvis-team";
    }

    @Override
    protected Long insertScope(String name) {
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
                                + "VALUES (:cid, 'PARKING_APPLICATION', NOW())")
                .setParameter("cid", consentId)
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
}
