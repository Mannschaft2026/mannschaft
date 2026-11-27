package com.mannschaft.app.role.controller;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 戦役 Z-A PR5 AC8 の実 DB 契約テスト: {@code /api/v1/me/teams}・{@code /api/v1/me/organizations} は
 * 実ロールを返し、"SYSTEM_ADMIN" を返さない。
 *
 * <ul>
 *   <li>所属の無い運営アカウント（SYSTEM_ADMIN のみ）は両 EP とも空配列。</li>
 *   <li>PR2 以前から所属を持つ兼任データ（点検 SQL の対象）でも、表示はそのスコープの実ロール。</li>
 *   <li>一般利用者（ADMIN・DEPUTY_ADMIN・MEMBER・SUPPORTER）は従来どおり（回帰）。</li>
 * </ul>
 */
@AutoConfigureMockMvc
@DisplayName("MeController 表示ロールの実ロール化 IT（戦役Z-A PR5 AC8）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@Transactional
class MeControllerRealRoleDisplayIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;
    @PersistenceContext
    private EntityManager em;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private UserRoleRepository userRoleRepository;
    @Autowired
    private MembershipRepository membershipRepository;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private OrganizationRepository organizationRepository;

    private Long systemRoleId;
    private Long adminRoleId;
    private Long deputyRoleId;

    @BeforeEach
    void setUp() {
        userRoleRepository.deleteAll();
        userRoleRepository.flush();
        membershipRepository.deleteAll();
        membershipRepository.flush();
        teamRepository.deleteAll();
        teamRepository.flush();
        organizationRepository.deleteAll();
        organizationRepository.flush();
        roleRepository.deleteAll();
        roleRepository.flush();
        systemRoleId = saveRole("SYSTEM_ADMIN", "システム管理者", 1, true);
        adminRoleId = saveRole("ADMIN", "管理者", 2, false);
        deputyRoleId = saveRole("DEPUTY_ADMIN", "副管理者", 3, false);
        saveRole("MEMBER", "メンバー", 4, false);
        saveRole("SUPPORTER", "サポーター", 5, false);
        saveRole("GUEST", "ゲスト", 6, false);
    }

    private Long saveRole(String name, String displayName, int priority, boolean isSystem) {
        return roleRepository.save(RoleEntity.builder().name(name).displayName(displayName)
                .priority(priority).isSystem(isSystem).build()).getId();
    }

    private TeamEntity saveTeam(String name) {
        return teamRepository.save(TeamEntity.builder()
                .slug("zapr5-team-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build());
    }

    private OrganizationEntity saveOrg(String name) {
        return organizationRepository.save(OrganizationEntity.builder()
                .slug("zapr5-org-" + SLUG_SEQ.incrementAndGet())
                .name(name)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true)
                .build());
    }

    private void grantSystemAdmin(Long userId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        userRoleRepository.save(UserRoleEntity.builder().userId(userId).roleId(systemRoleId).build());
    }

    private void saveTeamUserRole(Long userId, Long teamId, Long roleId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        userRoleRepository.save(UserRoleEntity.builder().userId(userId).roleId(roleId).teamId(teamId).build());
    }

    private void saveOrgUserRole(Long userId, Long orgId, Long roleId) {
        MembershipTestHelper.insertActiveUser(em, userId);
        userRoleRepository.save(UserRoleEntity.builder().userId(userId).roleId(roleId)
                .organizationId(orgId).build());
    }

    private void saveMembership(Long userId, ScopeType scopeType, Long scopeId, RoleKind kind) {
        MembershipTestHelper.insertActiveUser(em, userId);
        membershipRepository.save(MembershipEntity.builder().userId(userId).scopeType(scopeType)
                .scopeId(scopeId).roleKind(kind).joinedAt(LocalDateTime.now()).build());
    }

    @Test
    @WithMockUser(username = "5601")
    @DisplayName("所属の無い運営アカウントは /me/teams・/me/organizations とも空配列")
    void 所属なしの運営アカウントは空配列() throws Exception {
        grantSystemAdmin(5601L);
        saveTeam("未所属チーム");
        saveOrg("未所属組織");
        em.flush();

        mockMvc.perform(get("/api/v1/me/teams").param("includeArchived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/v1/me/organizations").param("includeArchived", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @WithMockUser(username = "5602")
    @DisplayName("兼任データ: チーム ADMIN・組織 DEPUTY_ADMIN の実ロールを返し SYSTEM_ADMIN を返さない")
    void 兼任データでも実ロールを返す() throws Exception {
        TeamEntity team = saveTeam("兼任チーム");
        OrganizationEntity org = saveOrg("兼任組織");
        saveTeamUserRole(5602L, team.getId(), adminRoleId);
        saveOrgUserRole(5602L, org.getId(), deputyRoleId);
        grantSystemAdmin(5602L);
        em.flush();

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].role").value("ADMIN"))
                .andExpect(jsonPath("$.data[*].role", not(hasItem("SYSTEM_ADMIN"))));
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].role").value("DEPUTY_ADMIN"))
                .andExpect(jsonPath("$.data[*].role", not(hasItem("SYSTEM_ADMIN"))));
    }

    @Test
    @WithMockUser(username = "5603")
    @DisplayName("兼任データ: memberships 由来の MEMBER/SUPPORTER は実ロールのまま")
    void 兼任データのmembership由来も実ロール() throws Exception {
        TeamEntity team = saveTeam("兼任サポーターチーム");
        OrganizationEntity org = saveOrg("兼任メンバー組織");
        saveMembership(5603L, ScopeType.TEAM, team.getId(), RoleKind.SUPPORTER);
        saveMembership(5603L, ScopeType.ORGANIZATION, org.getId(), RoleKind.MEMBER);
        grantSystemAdmin(5603L);
        em.flush();

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].role").value("SUPPORTER"));
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].role").value("MEMBER"));
    }

    @ParameterizedTest(name = "一般利用者 {0} は従来どおり（回帰）")
    @ValueSource(strings = {"ADMIN", "DEPUTY_ADMIN", "MEMBER", "SUPPORTER"})
    @WithMockUser(username = "5604")
    void 一般利用者は従来どおり(String role) throws Exception {
        TeamEntity team = saveTeam("一般チーム");
        OrganizationEntity org = saveOrg("一般組織");
        if ("ADMIN".equals(role) || "DEPUTY_ADMIN".equals(role)) {
            Long roleId = "ADMIN".equals(role) ? adminRoleId : deputyRoleId;
            saveTeamUserRole(5604L, team.getId(), roleId);
            saveOrgUserRole(5604L, org.getId(), roleId);
        } else {
            RoleKind kind = RoleKind.valueOf(role);
            saveMembership(5604L, ScopeType.TEAM, team.getId(), kind);
            saveMembership(5604L, ScopeType.ORGANIZATION, org.getId(), kind);
        }
        em.flush();

        mockMvc.perform(get("/api/v1/me/teams"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].role").value(role));
        mockMvc.perform(get("/api/v1/me/organizations"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].role").value(role));
    }
}
