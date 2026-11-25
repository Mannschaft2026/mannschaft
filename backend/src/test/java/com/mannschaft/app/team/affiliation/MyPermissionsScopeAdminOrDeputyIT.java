package com.mannschaft.app.team.affiliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261124-0159 PR-R — {@code GET /{teams|organizations}/{slug}/me/permissions} の
 * {@code scopeAdminOrDeputy} が BE の {@code AccessControlService#isAdminOrAbove} と一致することを実判定で固定する。
 *
 * <p>FE の {@code canManageScope} はこの値で管理者 UI を出し分ける。SYSTEM_ADMIN 単独（対象スコープの割当なし）は
 * false、SYSTEM_ADMIN とスコープ ADMIN/DEPUTY_ADMIN の兼任は true、MEMBER 以下は false。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 PR-R me/permissions の scopeAdminOrDeputy")
class MyPermissionsScopeAdminOrDeputyIT extends TeamAffiliationItSupport {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private TeamFx team;
    private OrgFx org;

    @BeforeEach
    void setUp() {
        team = newTeam();
        org = newOrg();
    }

    private JsonNode me(String base, String slug, long actor) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/" + base + "/{slug}/me/permissions", slug)
                .with(user(String.valueOf(actor)))).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private void assertFlag(String base, String slug, long actor, boolean expected, String label) throws Exception {
        JsonNode flag = me(base, slug, actor).path("scopeAdminOrDeputy");
        assertThat(flag.isBoolean()).as("%s: scopeAdminOrDeputy が応答に含まれる", label).isTrue();
        assertThat(flag.asBoolean()).as(label).isEqualTo(expected);
    }

    @Test
    @DisplayName("チーム: 5 区分（SYSTEM_ADMIN 単独 false／SA+ADMIN true／SA+DEPUTY true／ADMIN・DEPUTY true／MEMBER 以下 false）")
    void チームの5区分() throws Exception {
        long sysOnly = newUser();
        long sysAndAdmin = newUser();
        long sysAndDeputy = newUser();
        long admin = newUser();
        long deputy = newUser();
        long member = newUser();
        long outsider = newUser();
        MembershipTestHelper.insertUserRole(em, sysOnly, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, sysAndAdmin, "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertUserRole(em, sysAndDeputy, "SYSTEM_ADMIN", null, null);
        makeTeamAdmin(sysAndAdmin, team.id());
        makeTeamDeputy(sysAndDeputy, team.id());
        makeTeamAdmin(admin, team.id());
        makeTeamDeputy(deputy, team.id());
        makeTeamMember(member, team.id());
        em.flush();
        em.clear();

        assertFlag("teams", team.slug(), sysOnly, false, "SYSTEM_ADMIN 単独");
        assertFlag("teams", team.slug(), sysAndAdmin, true, "SYSTEM_ADMIN + ADMIN");
        assertFlag("teams", team.slug(), sysAndDeputy, true, "SYSTEM_ADMIN + DEPUTY_ADMIN");
        assertFlag("teams", team.slug(), admin, true, "ADMIN");
        assertFlag("teams", team.slug(), deputy, true, "DEPUTY_ADMIN");
        assertFlag("teams", team.slug(), member, false, "MEMBER");
        assertFlag("teams", team.slug(), outsider, false, "非所属");
    }

    @Test
    @DisplayName("組織: ADMIN は true、SYSTEM_ADMIN 単独と MEMBER は false")
    void 組織の区分() throws Exception {
        long sysOnly = newUser();
        long admin = newUser();
        long member = newUser();
        MembershipTestHelper.insertUserRole(em, sysOnly, "SYSTEM_ADMIN", null, null);
        makeOrgAdmin(admin, org.id());
        MembershipTestHelper.insertMembership(em, member, ScopeType.ORGANIZATION, org.id(), RoleKind.MEMBER);
        em.flush();
        em.clear();

        assertFlag("organizations", org.slug(), sysOnly, false, "SYSTEM_ADMIN 単独");
        assertFlag("organizations", org.slug(), admin, true, "組織 ADMIN");
        assertFlag("organizations", org.slug(), member, false, "組織 MEMBER");
    }
}
