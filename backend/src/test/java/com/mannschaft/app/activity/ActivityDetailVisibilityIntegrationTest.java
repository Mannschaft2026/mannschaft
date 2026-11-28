package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261124-0158: 活動記録の ID 指定経路（詳細・コメント・複製・更新・参加者・CSV）が、
 * 一覧と同じ可視性（{@code resolveVisibleLevels} → {@code ActivityVisibilityMapper}）を適用することを
 * 実 HTTP 境界と実 MySQL で固定する受け入れテスト。
 *
 * <p>設計書 {@code docs/features/F06.4_activity_records.md}「SUPPORTER は公開記録の閲覧のみ」。
 * 可視でない記録は「存在しない ID」と同じ 404 {@code ACTIVITY_001}（存在秘匿）。
 * スコープ非所属（他組織）は従来どおり 403 {@code COMMON_002}。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("活動記録 ID 指定経路の可視性（CMP-261124-0158）")
class ActivityDetailVisibilityIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long ADMIN = 941124001L;
    private static final long MEMBER = 941124002L;
    private static final long SUPPORTER = 941124003L;
    private static final long ORG_MEMBER = 941124004L;
    private static final long OTHER_ORG_MEMBER = 941124005L;
    private static final long DRAFT_AUTHOR = 941124006L;
    private static final long NONEXISTENT_ID = 9_999_999_991L;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    private TransactionTemplate tx;
    private Long teamId;
    private Long orgId;
    private Long otherOrgId;
    private String suffix;

    @BeforeEach
    void setup() {
        tx = new TransactionTemplate(transactionManager);
        suffix = UUID.randomUUID().toString().substring(0, 12);
        tx.executeWithoutResult(t -> {
            for (long id : new long[]{ADMIN, MEMBER, SUPPORTER, ORG_MEMBER, OTHER_ORG_MEMBER, DRAFT_AUTHOR}) {
                MembershipTestHelper.insertActiveUser(em, id);
            }
            teamId = teams.saveAndFlush(TeamEntity.builder().name("可視性試練")
                    .slug("vis-team-" + suffix).visibility(TeamEntity.Visibility.PUBLIC)
                    .supporterEnabled(true).build()).getId();
            orgId = saveOrg("vis-org-" + suffix);
            otherOrgId = saveOrg("vis-org2-" + suffix);
            MembershipTestHelper.insertMembership(em, ADMIN, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, ADMIN, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, MEMBER, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, DRAFT_AUTHOR, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, SUPPORTER, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
            MembershipTestHelper.insertMembership(em, ORG_MEMBER, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, OTHER_ORG_MEMBER, ScopeType.ORGANIZATION, otherOrgId, RoleKind.MEMBER);
        });
    }

    private Long saveOrg(String slug) {
        return organizations.saveAndFlush(OrganizationEntity.builder().name("可視性組織試練")
                .slug(slug).orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(true).build()).getId();
    }

    @AfterEach
    void cleanup() {
        if (teamId == null) return;
        tx.executeWithoutResult(t -> {
            for (Object[] scope : new Object[][]{{"TEAM", teamId}, {"ORGANIZATION", orgId}, {"ORGANIZATION", otherOrgId}}) {
                String where = " IN (SELECT id FROM activity_results WHERE scope_type=:type AND scope_id=:id)";
                em.createNativeQuery("DELETE FROM activity_comments WHERE activity_result_id" + where)
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_participants WHERE activity_result_id" + where)
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_results WHERE scope_type=:type AND scope_id=:id")
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type=:type AND scope_id=:id")
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM schedules WHERE team_id=:id").setParameter("id", teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE team_id=:id").setParameter("id", teamId).executeUpdate();
            teams.deleteById(teamId);
            organizations.deleteById(orgId);
            organizations.deleteById(otherOrgId);
        });
    }

    // ───────────────────────── 詳細 GET /api/v1/activities/{id} ─────────────────────────

    @Test
    @DisplayName("SUPPORTER は MEMBERS_ONLY の詳細を開けず、存在しない ID と同じ 404 ACTIVITY_001 になる（本文に題名を含まない）")
    void サポーターはMEMBERS_ONLY詳細を存在しないIDと同じ応答で拒否される() throws Exception {
        long id = activity(ActivityScopeType.TEAM, teamId, ADMIN, "内輪の記録-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);

        MvcResult denied = detail(id, SUPPORTER);
        MvcResult missing = detail(NONEXISTENT_ID, SUPPORTER);

        assertThat(denied.getResponse().getStatus()).isEqualTo(404);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(denied)).isEqualTo("ACTIVITY_001").isEqualTo(errorCode(missing));
        assertThat(errorMessage(denied)).isEqualTo(errorMessage(missing));
        assertThat(body(denied)).doesNotContain("内輪の記録-" + suffix);
    }

    @Test
    @DisplayName("SUPPORTER は PUBLIC の詳細を 200 で開ける（対照）")
    void サポーターはPUBLIC詳細を開ける() throws Exception {
        long id = activity(ActivityScopeType.TEAM, teamId, ADMIN, "公開の記録-" + suffix,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, null, false);

        MvcResult result = detail(id, SUPPORTER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(result).path("title").asText()).isEqualTo("公開の記録-" + suffix);
    }

    @Test
    @DisplayName("MEMBER は MEMBERS_ONLY の詳細を 200 で開ける（対照）")
    void メンバーはMEMBERS_ONLY詳細を開ける() throws Exception {
        long id = activity(ActivityScopeType.TEAM, teamId, ADMIN, "内輪の記録-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);

        MvcResult result = detail(id, MEMBER);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(result).path("title").asText()).isEqualTo("内輪の記録-" + suffix);
    }

    @Test
    @DisplayName("他組織の所属者は MEMBERS_ONLY・PUBLIC とも拒否される（従来の 403 COMMON_002）。当該組織の所属者は開ける")
    void 他組織の所属者は拒否される() throws Exception {
        long membersOnly = activity(ActivityScopeType.ORGANIZATION, orgId, ORG_MEMBER, "組織内輪-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);
        long publicRecord = activity(ActivityScopeType.ORGANIZATION, orgId, ORG_MEMBER, "組織公開-" + suffix,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, null, false);

        for (long id : new long[]{membersOnly, publicRecord}) {
            MvcResult denied = detail(id, OTHER_ORG_MEMBER);
            assertThat(denied.getResponse().getStatus()).isEqualTo(403);
            assertThat(errorCode(denied)).isEqualTo("COMMON_002");
            assertThat(body(denied)).doesNotContain("組織内輪-" + suffix).doesNotContain("組織公開-" + suffix);
            assertThat(detail(id, ORG_MEMBER).getResponse().getStatus()).isEqualTo(200);
        }
    }

    @Test
    @DisplayName("DRAFT は従来どおり: 作成者本人と管理者は 200、他の MEMBER・SUPPORTER は存在しない ID と同じ 404")
    void DRAFTは従来どおり() throws Exception {
        long id = activity(ActivityScopeType.TEAM, teamId, DRAFT_AUTHOR, "下書き-" + suffix,
                ActivityVisibility.PUBLIC, ActivityStatus.DRAFT, null, false);

        assertThat(detail(id, DRAFT_AUTHOR).getResponse().getStatus()).isEqualTo(200);
        assertThat(detail(id, ADMIN).getResponse().getStatus()).isEqualTo(200);
        for (long viewer : new long[]{MEMBER, SUPPORTER}) {
            MvcResult denied = detail(id, viewer);
            assertThat(denied.getResponse().getStatus()).isEqualTo(404);
            assertThat(errorCode(denied)).isEqualTo("ACTIVITY_001");
        }
    }

    @Test
    @DisplayName("予定から自動生成された公開済み MEMBERS_ONLY 記録: 元予定が見える SUPPORTER でも 404、MEMBER は 200")
    void 自動生成の公開済みMEMBERS_ONLYはサポーターに見えない() throws Exception {
        long schedule = schedule(MinViewRole.SUPPORTER_PLUS);
        long id = activity(ActivityScopeType.TEAM, teamId, ADMIN, "自動内輪-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, schedule, true);

        MvcResult denied = detail(id, SUPPORTER);
        assertThat(denied.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(denied)).isEqualTo("ACTIVITY_001");
        assertThat(body(denied)).doesNotContain("自動内輪-" + suffix);
        assertThat(detail(id, MEMBER).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("予定から自動生成された未公開（DRAFT・MEMBERS_ONLY）記録の metadata 詳細も SUPPORTER には 404（一覧と同じく出さない）、MEMBER には metadata で 200")
    void 自動生成の未公開MEMBERS_ONLYのmetadataもサポーターに見えない() throws Exception {
        long schedule = schedule(MinViewRole.SUPPORTER_PLUS);
        long id = activity(ActivityScopeType.TEAM, teamId, ADMIN, "自動下書き-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.DRAFT, schedule, true);

        MvcResult denied = detail(id, SUPPORTER);
        assertThat(denied.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(denied)).isEqualTo("ACTIVITY_001");
        MvcResult member = detail(id, MEMBER);
        assertThat(member.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(member).path("metadataOnly").asBoolean()).isTrue();
    }

    // ───────────────────────── 詳細以外の ID 指定経路 ─────────────────────────

    @Test
    @DisplayName("SUPPORTER は MEMBERS_ONLY のコメント一覧・投稿もできず 404 ACTIVITY_001。PUBLIC では一覧 200・投稿 201（対照）")
    void サポーターはMEMBERS_ONLYのコメントに触れない() throws Exception {
        long hidden = activity(ActivityScopeType.TEAM, teamId, ADMIN, "内輪-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);
        long open = activity(ActivityScopeType.TEAM, teamId, ADMIN, "公開-" + suffix,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, null, false);
        String comment = json.writeValueAsString(Map.of("body", "応援しています"));

        MvcResult list = mvc.perform(get("/api/v1/activities/" + hidden + "/comments").with(user(Long.toString(SUPPORTER))))
                .andReturn();
        assertThat(list.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(list)).isEqualTo("ACTIVITY_001");
        MvcResult create = mvc.perform(post("/api/v1/activities/" + hidden + "/comments").with(user(Long.toString(SUPPORTER)))
                .contentType(MediaType.APPLICATION_JSON).content(comment)).andReturn();
        assertThat(create.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(create)).isEqualTo("ACTIVITY_001");

        assertThat(mvc.perform(get("/api/v1/activities/" + open + "/comments").with(user(Long.toString(SUPPORTER))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(post("/api/v1/activities/" + open + "/comments").with(user(Long.toString(SUPPORTER)))
                .contentType(MediaType.APPLICATION_JSON).content(comment)).andReturn().getResponse().getStatus())
                .isEqualTo(201);
        assertThat(mvc.perform(get("/api/v1/activities/" + hidden + "/comments").with(user(Long.toString(MEMBER))))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("SUPPORTER は MEMBERS_ONLY を複製して中身を取り出せず 404 ACTIVITY_001（複製行も作られない）")
    void サポーターはMEMBERS_ONLYを複製できない() throws Exception {
        long hidden = activity(ActivityScopeType.TEAM, teamId, ADMIN, "内輪-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);

        MvcResult result = mvc.perform(post("/api/v1/activities/" + hidden + "/duplicate").with(user(Long.toString(SUPPORTER)))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(errorCode(result)).isEqualTo("ACTIVITY_001");
        assertThat(body(result)).doesNotContain("内輪-" + suffix);
        Number count = tx.execute(t -> (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM activity_results WHERE scope_type='TEAM' AND scope_id=:id AND created_by=:u")
                .setParameter("id", teamId).setParameter("u", SUPPORTER).getSingleResult());
        assertThat(count.longValue()).isZero();
    }

    @Test
    @DisplayName("SUPPORTER の MEMBERS_ONLY への更新・参加者追加は、存在しない ID と同じ 404 ACTIVITY_001")
    void サポーターのMEMBERS_ONLY更新と参加者追加は存在秘匿() throws Exception {
        long hidden = activity(ActivityScopeType.TEAM, teamId, ADMIN, "内輪-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);
        String update = json.writeValueAsString(Map.of("title", "改ざん", "activityDate", "2026-10-15"));
        String participants = json.writeValueAsString(Map.of("userIds", java.util.List.of(SUPPORTER)));

        for (long id : new long[]{hidden, NONEXISTENT_ID}) {
            MvcResult put = mvc.perform(put("/api/v1/activities/" + id).with(user(Long.toString(SUPPORTER)))
                    .contentType(MediaType.APPLICATION_JSON).content(update)).andReturn();
            assertThat(put.getResponse().getStatus()).as("PUT id=%s", id).isEqualTo(404);
            assertThat(errorCode(put)).isEqualTo("ACTIVITY_001");
            MvcResult add = mvc.perform(post("/api/v1/activities/" + id + "/participants").with(user(Long.toString(SUPPORTER)))
                    .contentType(MediaType.APPLICATION_JSON).content(participants)).andReturn();
            assertThat(add.getResponse().getStatus()).as("participants id=%s", id).isEqualTo(404);
            assertThat(errorCode(add)).isEqualTo("ACTIVITY_001");
        }
    }

    @Test
    @DisplayName("CSV エクスポート: 管理者限定（CMP-261124-0159 AC-0159-81）。ADMIN には PUBLIC と MEMBERS_ONLY が出て他人の DRAFT は出ない。SUPPORTER・MEMBER・下書き作成者は 403 COMMON_002 で題名が出ない")
    void CSVエクスポートは管理者限定で一覧と同じ可視性() throws Exception {
        activity(ActivityScopeType.TEAM, teamId, ADMIN, "内輪CSV-" + suffix,
                ActivityVisibility.MEMBERS_ONLY, ActivityStatus.PUBLISHED, null, false);
        activity(ActivityScopeType.TEAM, teamId, ADMIN, "公開CSV-" + suffix,
                ActivityVisibility.PUBLIC, ActivityStatus.PUBLISHED, null, false);
        activity(ActivityScopeType.TEAM, teamId, DRAFT_AUTHOR, "下書きCSV-" + suffix,
                ActivityVisibility.PUBLIC, ActivityStatus.DRAFT, null, false);

        String adminCsv = export(ADMIN);
        assertThat(adminCsv).contains("公開CSV-" + suffix).contains("内輪CSV-" + suffix)
                .doesNotContain("下書きCSV-" + suffix);
        for (long viewer : new long[]{SUPPORTER, MEMBER, DRAFT_AUTHOR}) {
            MvcResult denied = mvc.perform(get("/api/v1/activities/export").with(user(Long.toString(viewer)))
                    .param("scope_type", "TEAM").param("scope_id", teamId.toString())).andReturn();
            assertThat(denied.getResponse().getStatus()).as("viewer=%s", viewer).isEqualTo(403);
            assertThat(errorCode(denied)).as("viewer=%s", viewer).isEqualTo("COMMON_002");
            assertThat(body(denied)).doesNotContain("公開CSV-" + suffix).doesNotContain("内輪CSV-" + suffix)
                    .doesNotContain("下書きCSV-" + suffix);
        }
    }

    // ───────────────────────── helpers ─────────────────────────

    private MvcResult detail(long id, long viewer) throws Exception {
        return mvc.perform(get("/api/v1/activities/" + id).with(user(Long.toString(viewer)))).andReturn();
    }

    private String export(long viewer) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/activities/export").with(user(Long.toString(viewer)))
                .param("scope_type", "TEAM").param("scope_id", teamId.toString())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(body(result)).path("data");
    }

    private String errorCode(MvcResult result) throws Exception {
        return json.readTree(body(result)).path("error").path("code").asText();
    }

    private String errorMessage(MvcResult result) throws Exception {
        return json.readTree(body(result)).path("error").path("message").asText();
    }

    private long activity(ActivityScopeType scopeType, Long scopeId, long author, String title,
                          ActivityVisibility visibility, ActivityStatus status, Long scheduleId, boolean automatic) {
        return tx.execute(t -> {
            ActivityResultEntity entity = ActivityResultEntity.builder().scopeType(scopeType).scopeId(scopeId)
                    .createdBy(author).title(title).activityDate(LocalDate.of(2026, 10, 15))
                    .visibility(visibility).status(status).scheduleId(scheduleId)
                    .autoGeneratedFromSchedule(automatic).build();
            em.persist(entity);
            em.flush();
            return entity.getId();
        });
    }

    private long schedule(MinViewRole minViewRole) {
        return tx.execute(t -> {
            ScheduleEntity schedule = ScheduleEntity.builder().title("元予定").teamId(teamId).createdBy(ADMIN)
                    .status(ScheduleStatus.SCHEDULED).eventType(EventType.PRACTICE)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY).minViewRole(minViewRole)
                    .startAt(LocalDateTime.of(2026, 10, 15, 12, 0)).endAt(LocalDateTime.of(2026, 10, 15, 13, 0))
                    .build();
            em.persist(schedule);
            em.flush();
            return schedule.getId();
        });
    }
}
