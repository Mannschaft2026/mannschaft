package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 組織広告 billing 系 API のクエリ {@code organizationId} に slug を渡したときの越境 IT
 * （試練 / red 先行。CMP-261125-2124・CMP-261022-0234 ε1 G4/G5）。
 *
 * <h3>越境の再現配置</h3>
 * <ul>
 *   <li>組織 A: slug = {@code SHARED_SLUG}。利用者 adminAB は ADMIN。</li>
 *   <li>組織 B: id = X（明示採番）。利用者 adminAB は ADMIN（権限検査は通ってしまう配置）。</li>
 *   <li>チーム T: id = X（組織 B と同じ数値）、slug = {@code SHARED_SLUG}（組織 A と同名）。</li>
 * </ul>
 * <p>現行の {@code ScopeSlugIdConverter} は {@code /api/v1/advertiser/account?organizationId=SHARED_SLUG}
 * をチーム T の ID（= X）へ解決するため、組織 A を見たつもりの利用者に<b>組織 B</b>の広告主アカウントが返り、
 * 更新も組織 B に書き込まれる。</p>
 *
 * <p>修正方針（組織として解決 / 400）は実装役に委ねるため、各テストは「200 なら組織 A、そうでなければ 400」
 * を許容し、組織 B のデータが返る・書き換わることだけを禁止する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("組織広告 billing API のクエリ organizationId slug 越境（CMP-261125-2124 / ε1 G4・G5）")
class OrganizationAdvertiserQuerySlugCrossScopeIT extends AbstractMySqlIntegrationTest {

    private static final String SHARED_SLUG = "eps1-shared-slug";
    private static final String COMPANY_A = "ε1組織A広告主";
    private static final String COMPANY_B = "ε1組織B広告主";
    private static final String COMPANY_C = "ε1組織C広告主";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long orgAId;
    private Long orgBId;
    private Long orgCId;
    private Long adminABId;
    private Long adminCId;

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2, false);
        Long adminRoleId = roleId("ADMIN");

        adminABId = insertUser("eps1-admin-ab@example.com");
        adminCId = insertUser("eps1-admin-c@example.com");

        orgAId = insertOrganization(null, "ε1 組織A", SHARED_SLUG);
        // 組織 B とチーム T を同じ数値 ID で作る（既存行と衝突しない十分大きい値）
        long sharedNumericId = nextFreeSharedId();
        orgBId = insertOrganization(sharedNumericId, "ε1 組織B", "eps1-org-b");
        Long teamTId = insertTeam(sharedNumericId, "ε1 チームT", SHARED_SLUG);
        assertThat(teamTId).isEqualTo(orgBId);
        orgCId = insertOrganization(null, "ε1 組織C", "eps1-org-c");

        insertUserRole(adminABId, adminRoleId, orgAId);
        insertUserRole(adminABId, adminRoleId, orgBId);
        insertUserRole(adminCId, adminRoleId, orgCId);
        MembershipTestHelper.insertMembership(em, adminABId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminABId, ScopeType.ORGANIZATION, orgBId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, adminCId, ScopeType.ORGANIZATION, orgCId, RoleKind.MEMBER);

        insertAdvertiserAccount(orgAId, COMPANY_A);
        insertAdvertiserAccount(orgBId, COMPANY_B);
        insertAdvertiserAccount(orgCId, COMPANY_C);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("G4: 組織 A の ADMIN が account?organizationId=<同名slug> → 組織 A（200）か 400。同名チームと同 ID の組織 B は返らない")
    void G4_同名slugのクエリで別組織の広告主アカウントが返らない() throws Exception {
        setAuthentication(adminABId);

        MvcResult result = mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", SHARED_SLUG))
                .andReturn();

        assertOrgAOrBadRequest(result);
    }

    @Test
    @DisplayName("G4: 組織 A の ADMIN が PATCH account?organizationId=<同名slug> → 組織 B の広告主アカウントは書き換わらない")
    void G4_同名slugのクエリ更新で別組織の広告主アカウントが書き換わらない() throws Exception {
        setAuthentication(adminABId);

        MvcResult result = mockMvc.perform(patch("/api/v1/advertiser/account")
                        .param("organizationId", SHARED_SLUG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"ε1改ざん\"}"))
                .andReturn();

        int httpStatus = result.getResponse().getStatus();
        assertThat(httpStatus).as("組織として解決（200）か 400").isIn(200, 400);
        em.clear();
        assertThat(companyNameOf(orgBId)).as("組織 B（同名チームと同じ数値 ID）は不変").isEqualTo(COMPANY_B);
        if (httpStatus == 200) {
            assertThat(companyNameOf(orgAId)).as("200 なら組織 A が更新される").isEqualTo("ε1改ざん");
        }
    }

    @Test
    @DisplayName("G4（対照）: 数値の組織 A ID なら従来どおり組織 A の広告主アカウントが 200")
    void G4_数値IDは従来どおり組織Aを返す() throws Exception {
        setAuthentication(adminABId);

        mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.companyName").value(COMPANY_A));
    }

    @Test
    @DisplayName("G5: 他テナント（組織 C のみの ADMIN）が account?organizationId=<組織A slug> → 403/404")
    void G5_他テナントADMINは組織Aのslugで照会できない() throws Exception {
        setAuthentication(adminCId);

        MvcResult result = mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", SHARED_SLUG))
                .andReturn();

        assertThat(result.getResponse().getStatus()).as("他テナントの組織は 403/404（400 も入力拒否として許容）")
                .isIn(400, 403, 404);
    }

    @Test
    @DisplayName("G5: 他テナント（組織 C のみの ADMIN）が account?organizationId=<組織A 数値ID> → 403/404")
    void G5_他テナントADMINは組織Aの数値IDで照会できない() throws Exception {
        setAuthentication(adminCId);

        MvcResult result = mockMvc.perform(get("/api/v1/advertiser/account").param("organizationId", orgAId.toString()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isIn(403, 404);
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void assertOrgAOrBadRequest(MvcResult result) throws Exception {
        int httpStatus = result.getResponse().getStatus();
        assertThat(httpStatus).as("組織として解決（200）か 400").isIn(200, 400);
        if (httpStatus == 200) {
            String companyName = JsonPath.read(result.getResponse().getContentAsString(), "$.data.companyName");
            assertThat(companyName).as("同名チームの ID と一致する組織 B のデータを返してはならない").isEqualTo(COMPANY_A);
        }
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private long nextFreeSharedId() {
        Number maxOrg = (Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM organizations").getSingleResult();
        Number maxTeam = (Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM teams").getSingleResult();
        return Math.max(maxOrg.longValue(), maxTeam.longValue()) + 1000L;
    }

    private String companyNameOf(Long orgId) {
        return (String) em.createNativeQuery(
                        "SELECT company_name FROM advertiser_accounts WHERE scope_type = 'ORGANIZATION' AND scope_id = :sid")
                .setParameter("sid", orgId)
                .getSingleResult();
    }

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        // roles はグローバル参照テーブルのため、既存なら再利用し二重 INSERT しない
        Number existing = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existing.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

    private Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, 'EPS1', 'テスト', 'EPS1 テスト', 'ACTIVE', "
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

    private Long insertOrganization(Long explicitId, String name, String slug) {
        // 明示採番が無い場合は AUTO_INCREMENT に任せる（null の型不明バインドを避けて SQL を分ける）
        String idColumn = explicitId == null ? "" : "id, ";
        String idValue = explicitId == null ? "" : explicitId + ", ";
        em.createNativeQuery(
                        "INSERT INTO organizations (" + idColumn + "name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (" + idValue + ":name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(Long explicitId, String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (id, name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:id, :name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("id", explicitId)
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }

    private void insertUserRole(Long uid, Long roleIdParam, Long orgIdParam) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, NULL, :oid, NOW(), NOW())")
                .setParameter("uid", uid)
                .setParameter("rid", roleIdParam)
                .setParameter("oid", orgIdParam)
                .executeUpdate();
    }

    private void insertAdvertiserAccount(Long orgId, String companyName) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :sid, 'ACTIVE', :cn, 'ads@example.com', 'STRIPE', 100000, "
                                + "NOW(), NOW())")
                .setParameter("sid", orgId)
                .setParameter("cn", companyName)
                .executeUpdate();
    }
}
