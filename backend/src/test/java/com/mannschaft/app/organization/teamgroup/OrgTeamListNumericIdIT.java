package com.mannschaft.app.organization.teamgroup;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261115-2056: GET /organizations/{slug}/teams が、確認通知の宛先選択用に各チームの数値 ID（numericId）を返す。
 * 既存の id（スラッグ）と認可は変えない。実 MySQL＋実 Security＋MockMvc。
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("組織所属チーム一覧の numericId")
class OrgTeamListNumericIdIT extends AbstractOrgTeamGroupAssignmentIT {

    private OrganizationEntity org;

    @BeforeEach
    void setUp() {
        org = newOrg(false);
        seedOrgPerson(AXA, org.getId(), "ADMIN");
        seedUserOnly(AN);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("各チームに numericId（チームの BIGINT ID）が入り、id と slug は従来どおりスラッグ")
    void listContainsNumericIdAndKeepsSlugId() throws Exception {
        TeamOrgMembershipEntity m = activeTeam(org.getId(), null);
        em.flush();
        em.clear();

        JsonNode rows = orgTeams(AXA, org.getSlug(), null);

        assertThat(rows).hasSize(1);
        String teamSlug = slugOf(m);
        assertThat(rows.get(0).path("id").asText()).isEqualTo(teamSlug);
        assertThat(rows.get(0).path("slug").asText()).isEqualTo(teamSlug);
        assertThat(rows.get(0).path("numericId").asLong()).isEqualTo(m.getTeamId());
    }

    @Test
    @DisplayName("見られない者（非公開組織の非メンバー）は従来どおり拒否され、numericId も漏れない")
    void nonViewerStillDenied() throws Exception {
        OrganizationEntity priv = organizationRepository.saveAndFlush(OrganizationEntity.builder()
                .name("非公開組織numericId")
                .slug("tg-priv-" + System.nanoTime() % 1_000_000_000L)
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .teamGroupsEnabled(false)
                .build());
        activeTeam(priv.getId(), null);
        em.flush();
        em.clear();

        String body = mockMvc.perform(get(ORG_TEAMS, priv.getSlug()).with(user(AN.toString())))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("numericId");
    }
}
