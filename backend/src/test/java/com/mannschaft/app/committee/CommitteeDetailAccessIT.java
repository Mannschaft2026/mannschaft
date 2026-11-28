package com.mannschaft.app.committee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 委員会詳細 API（GET /committees/{id}）の認可を、実 Security・Controller・Service・MySQL で固定する（CMP-261028-0513）。
 *
 * <p>FE は詳細の {@code myRole} で委員会所属を判定し、非所属なら members API を呼ばない。
 * その前提となる「組織所属・委員会非所属は NAME_ONLY を 200（myRole=null）で読める一方、
 * メンバー一覧は 403」という BE 契約と、他テナント・匿名・HIDDEN の拒否を直接確かめる。</p>
 *
 * <p>組織所属の必須化（台帳 CMP-260930-0234）は別 PR の対象。ここでは他テナント利用者への
 * 非開示（2xx にならず本文に data を含まない）と匿名拒否を確かめる。</p>
 *
 * <p>既知の欠陥: 他テナント利用者への応答は 403（COMMON_002）、不在 ID は 404（COMMITTEE_NOT_FOUND）で、
 * 両者が区別できる存在オラクルになっている（docs/security/01_authorization_baseline.md §3.3.2 違反）。
 * 本 IT はこの差を正として固定しない。根治は別の台帳行で追跡する（行 ID は起票後に PR 本文へ記載）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class CommitteeDetailAccessIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager em;

    private Long organizationId;
    private Long orgOnlyUserId;
    private Long committeeMemberUserId;
    private Long outsiderUserId;
    private Long nameOnlyCommitteeId;
    private Long hiddenCommitteeId;

    @BeforeEach
    void setUp() {
        organizationId = organization();
        orgOnlyUserId = user();
        committeeMemberUserId = user();
        outsiderUserId = user();
        MembershipTestHelper.insertMembership(em, orgOnlyUserId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, committeeMemberUserId, ScopeType.ORGANIZATION, organizationId, RoleKind.MEMBER);
        // 別組織にだけ所属する他テナント利用者
        MembershipTestHelper.insertMembership(em, outsiderUserId, ScopeType.ORGANIZATION, organization(), RoleKind.MEMBER);
        nameOnlyCommitteeId = committee(CommitteeVisibility.NAME_ONLY);
        hiddenCommitteeId = committee(CommitteeVisibility.HIDDEN);
        member(nameOnlyCommitteeId, committeeMemberUserId);
        member(hiddenCommitteeId, committeeMemberUserId);
        em.flush();
    }

    @Test
    void 組織所属の委員会非所属者はNAME_ONLY詳細を200でmyRoleがnullになりメンバー一覧は403() throws Exception {
        JsonNode data = detail(orgOnlyUserId, nameOnlyCommitteeId, 200);
        assertThat(data.path("id").asLong()).isEqualTo(nameOnlyCommitteeId);
        assertThat(data.path("myRole").isNull()).isTrue();

        mockMvc.perform(get("/api/v1/committees/{id}/members", nameOnlyCommitteeId)
                        .with(as(orgOnlyUserId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void 委員会の現役所属者は詳細のmyRoleにロールが入りメンバー一覧も200() throws Exception {
        JsonNode data = detail(committeeMemberUserId, nameOnlyCommitteeId, 200);
        assertThat(data.path("myRole").asText()).isEqualTo("MEMBER");

        mockMvc.perform(get("/api/v1/committees/{id}/members", nameOnlyCommitteeId)
                        .with(as(committeeMemberUserId)))
                .andExpect(status().isOk());
    }

    @Test
    void 他テナントの利用者は組織内委員会の詳細を取得できない() throws Exception {
        // 拒否の具体的な status は固定しない（現状の 403 は存在オラクルで、不在 ID の 404 との不一致は
        // 既知の欠陥。別の台帳行で根治予定のため、ここでは「2xx でなく data を返さない」ことだけを確かめる）。
        var result = mockMvc.perform(get("/api/v1/committees/{id}", nameOnlyCommitteeId).with(as(outsiderUserId)))
                .andReturn().getResponse();
        assertThat(result.getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(objectMapper.readTree(result.getContentAsString()).has("data")).isFalse();
    }

    @Test
    void HIDDEN委員会は組織所属でも非所属者には存在を隠して404() throws Exception {
        var hidden = mockMvc.perform(get("/api/v1/committees/{id}", hiddenCommitteeId).with(as(orgOnlyUserId)))
                .andReturn().getResponse();
        var absent = mockMvc.perform(get("/api/v1/committees/{id}", Long.MAX_VALUE).with(as(orgOnlyUserId)))
                .andReturn().getResponse();
        assertThat(hidden.getStatus()).isEqualTo(404);
        // 存在を隠す: 不在 ID の応答と status・code・message が一致する
        assertThat(hidden.getStatus()).isEqualTo(absent.getStatus());
        JsonNode hiddenError = objectMapper.readTree(hidden.getContentAsString()).path("error");
        JsonNode absentError = objectMapper.readTree(absent.getContentAsString()).path("error");
        assertThat(hiddenError.path("code").asText()).isNotBlank().isEqualTo(absentError.path("code").asText());
        assertThat(hiddenError.path("message").asText()).isEqualTo(absentError.path("message").asText());
        detail(committeeMemberUserId, hiddenCommitteeId, 200);
    }

    @Test
    void 未認証は実Securityで401() throws Exception {
        mockMvc.perform(get("/api/v1/committees/{id}", nameOnlyCommitteeId))
                .andExpect(status().isUnauthorized());
    }

    private JsonNode detail(Long actor, Long committeeId, int expectedStatus) throws Exception {
        em.flush();
        String body = mockMvc.perform(get("/api/v1/committees/{id}", committeeId).with(as(actor)))
                .andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data");
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor as(Long userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private Long user() {
        UserEntity user = UserEntity.builder().email(UUID.randomUUID() + "@committee-detail.example")
                .lastName("詳細").firstName("契約").displayName("詳細契約")
                .isSearchable(false).locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(user);
        return user.getId();
    }

    private Long organization() {
        OrganizationEntity organization = OrganizationEntity.builder().name("委員会詳細契約")
                .slug("cmd-" + UUID.randomUUID().toString().substring(0, 20))
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(true).build();
        em.persist(organization);
        return organization.getId();
    }

    private Long committee(CommitteeVisibility visibility) {
        CommitteeEntity committee = CommitteeEntity.builder().organizationId(organizationId).name("詳細契約")
                .visibilityToOrg(visibility).status(CommitteeStatus.ACTIVE).build();
        em.persist(committee);
        return committee.getId();
    }

    private void member(Long committeeId, Long userId) {
        em.persist(CommitteeMemberEntity.builder().committeeId(committeeId).userId(userId)
                .role(CommitteeRole.MEMBER).joinedAt(LocalDateTime.now()).build());
    }
}
