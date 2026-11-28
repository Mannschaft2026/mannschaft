package com.mannschaft.app.jobmatching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261127-1118: 求人詳細 {@code GET /api/v1/jobs/{id}} の存在オラクル是正（試練）。
 *
 * <p>欠陥: 閲覧できない者が実在する求人を引くと 403 {@code VISIBILITY_001}、不在の ID では
 * 404 {@code JOB_NOT_FOUND} と応答が割れ、連番の求人 ID の実在を判別できた
 * （{@code JobPostingService#findById} が存在確認の後に {@code ContentVisibilityChecker#assertCanView} を呼んでいた）。</p>
 *
 * <p>契約（{@code docs/security/01_authorization_baseline.md} §3.3.2）: 見えない実在求人への応答は、
 * 不在 ID への応答と status・{@code error.code}・{@code error.message}・本文全体まで完全一致する。
 * 一致だけでは両方壊れたとき偽 green になるため、絶対値（404 / {@code JOB_NOT_FOUND} / 本文言）も固定する。
 * 可視範囲の正当な閲覧者は従来どおり 200。</p>
 *
 * <p>fixture のチーム: 求人の可視範囲（visibility_scope × status）の契約を見るため、求人を置くチームは公開チーム
 * （{@code teams.visibility = PUBLIC}）にする。非公開チーム（{@code MEMBERS_AND_ABOVE}）では親チーム上限
 * （CMP-261124-0159・F00 §20.2）により、JOBBER_PUBLIC_BOARD でもそのチームの MEMBER 未満には見えないため、
 * 「PUBLIC 相当は誰でも」は公開チームでしか成り立たない。非公開チームの上限で見えない求人も不在 ID と同じ 404 になることは
 * AC-1118-1 の「非公開チーム」の組で固定する。</p>
 *
 * <p>注: DRAFT の閲覧範囲は 2026-11-27 マスター裁定（作成者のみ、SYSTEM_ADMIN も不可）で別行の戦役にしたため、本クラスでは SYSTEM_ADMIN の DRAFT 閲覧を固定しない。</p>
 *
 * <p>金型: {@link JobPostingApplicationWriteBodyParityContractIT}（実 DB・実フィルタ・実トークン、spy なし）。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261127-1118 求人詳細の越境/不在 本文一致契約")
class JobDetailExistenceOracleParityContractIT extends AbstractMySqlIntegrationTest {

    private static final long MISSING = Long.MAX_VALUE;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private JobPostingRepository postings;
    @Autowired private MembershipRepository memberships;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> postingIds = new ArrayList<>();
    private final List<Long> createdRoleIds = new ArrayList<>();

    private Long requester;      // 求人の作成者（チームの ADMIN でもある）
    private Long plainMember;    // 同 TEAM の一般メンバー
    private Long foreignAdmin;   // 他 TEAM の ADMIN
    private Long foreignMember;  // 他 TEAM の一般メンバー
    private Long outsider;       // どこにも所属しない利用者
    private Long systemAdmin;    // SYSTEM_ADMIN（スコープなしの user_roles）

    private Long openTeamMembers; // OPEN・TEAM_MEMBERS
    private Long draft;           // DRAFT・TEAM_MEMBERS（作成者本人のみ可視）
    private Long publicBoard;     // OPEN・JOBBER_PUBLIC_BOARD（PUBLIC 相当）
    private Long cancelled;       // CANCELLED・TEAM_MEMBERS（F00 上 ARCHIVED。SYSTEM_ADMIN のみ可視）
    private Long privateTeamBoard; // 非公開チームの OPEN・JOBBER_PUBLIC_BOARD（親チーム上限で非メンバーには不可視）

    @BeforeEach
    void Repositoryで自所有fixtureを確定する() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        SecurityContextHolder.clearContext();
        reset(redisTemplate);
        @SuppressWarnings("unchecked")
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(null);
        when(redisTemplate.hasKey(anyString())).thenReturn(false);
        tx(() -> {
            Long team = team(TeamEntity.Visibility.PUBLIC);
            Long foreignTeam = team(TeamEntity.Visibility.PUBLIC);
            Long privateTeam = team(TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            requester = user();
            member(requester, team);
            assignRole(requester, "ADMIN", team);
            plainMember = user();
            member(plainMember, team);
            foreignAdmin = user();
            member(foreignAdmin, foreignTeam);
            assignRole(foreignAdmin, "ADMIN", foreignTeam);
            foreignMember = user();
            member(foreignMember, foreignTeam);
            outsider = user();
            systemAdmin = user();
            assignSystemAdmin(systemAdmin);
            openTeamMembers = posting(team, VisibilityScope.TEAM_MEMBERS, JobPostingStatus.OPEN);
            draft = posting(team, VisibilityScope.TEAM_MEMBERS, JobPostingStatus.DRAFT);
            publicBoard = posting(team, VisibilityScope.JOBBER_PUBLIC_BOARD, JobPostingStatus.OPEN);
            cancelled = posting(team, VisibilityScope.TEAM_MEMBERS, JobPostingStatus.CANCELLED);
            privateTeamBoard = posting(privateTeam, VisibilityScope.JOBBER_PUBLIC_BOARD, JobPostingStatus.OPEN);
            assertThat(postings.existsById(MISSING)).isFalse();
            em.flush();
            return null;
        });
    }

    @AfterEach
    void 自所有行を削除して共有mockと認証状態を復元する() {
        try {
            tx(() -> {
                if (!postingIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM job_postings WHERE id IN (:ids)")
                            .setParameter("ids", postingIds).executeUpdate();
                }
                if (!userIds.isEmpty()) {
                    em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                            .setParameter("ids", userIds).executeUpdate();
                    em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                            .setParameter("ids", userIds).executeUpdate();
                    users.deleteAllById(userIds);
                }
                roles.deleteAllById(createdRoleIds);
                teams.deleteAllById(teamIds);
                em.flush();
                return null;
            });
        } finally {
            try { reset(redisTemplate); }
            finally { SecurityContextHolder.clearContext(); }
        }
    }

    @Test
    @DisplayName("AC-1118-1: 見えない実在求人は、不在IDと同じ 404 JOB_NOT_FOUND（status・code・message・本文全体が一致）")
    void AC_1118_1_見えない実在求人と不在IDの応答一致() throws Exception {
        record Case(String actorName, Long actor, String postingName, Long posting) { }
        List<Case> cases = List.of(
                new Case("他TEAMのADMIN", foreignAdmin, "OPEN/TEAM_MEMBERS", openTeamMembers),
                new Case("他TEAMの一般", foreignMember, "OPEN/TEAM_MEMBERS", openTeamMembers),
                new Case("所属なし", outsider, "OPEN/TEAM_MEMBERS", openTeamMembers),
                new Case("他TEAMのADMIN", foreignAdmin, "DRAFT", draft),
                new Case("他TEAMの一般", foreignMember, "DRAFT", draft),
                new Case("所属なし", outsider, "DRAFT", draft),
                new Case("同TEAMの一般（可視範囲外）", plainMember, "DRAFT", draft),
                new Case("他TEAMのADMIN", foreignAdmin, "CANCELLED", cancelled),
                new Case("他TEAMの一般", foreignMember, "CANCELLED", cancelled),
                new Case("所属なし", outsider, "非公開チームのJOBBER_PUBLIC_BOARD", privateTeamBoard),
                new Case("他TEAMのADMIN", foreignAdmin, "非公開チームのJOBBER_PUBLIC_BOARD", privateTeamBoard));
        List<String> diffs = new ArrayList<>();
        for (Case c : cases) {
            String token = tokens.issueAccessToken(c.actor(), List.of("USER"));
            MvcResult missing = call(token, MISSING);
            MvcResult existing = call(token, c.posting());
            String label = c.actorName() + "→" + c.postingName();
            JsonNode missingBody = json(missing);
            if (missing.getResponse().getStatus() != 404
                    || !"JOB_NOT_FOUND".equals(missingBody.path("error").path("code").asText())
                    || !"求人が見つかりません".equals(missingBody.path("error").path("message").asText())) {
                diffs.add(label + ": 不在IDの応答が 404 JOB_NOT_FOUND でない: "
                        + missing.getResponse().getStatus() + " " + missingBody);
            }
            if (existing.getResponse().getStatus() != missing.getResponse().getStatus()
                    || !json(existing).equals(missingBody)) {
                diffs.add(label + ": 実在=" + existing.getResponse().getStatus() + " " + json(existing)
                        + " 不在=" + missing.getResponse().getStatus() + " " + missingBody);
            }
        }
        assertThat(diffs).as("見えない実在求人と不在IDで応答が割れている組").isEmpty();
    }

    @Test
    @DisplayName("AC-1118-2: 未ログインは実在（見えない／PUBLIC相当）・不在とも同一の 401")
    void AC_1118_2_未ログインは実在不在とも同一() throws Exception {
        MvcResult missing = call(null, MISSING);
        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        for (Long posting : List.of(openTeamMembers, draft, publicBoard, cancelled, privateTeamBoard)) {
            MvcResult existing = call(null, posting);
            assertThat(existing.getResponse().getStatus()).as("posting=%s", posting).isEqualTo(401);
            assertThat(json(existing)).as("posting=%s", posting).isEqualTo(json(missing));
        }
    }

    @Test
    @DisplayName("AC-1118-3: 可視範囲の正当な閲覧者は従来どおり 200（同TEAMメンバー・下書きの作成者・PUBLIC相当は誰でも・SYSTEM_ADMINはCANCELLEDも）")
    void AC_1118_3_正当な閲覧者は200() throws Exception {
        record Case(String name, Long actor, Long posting) { }
        List<Case> cases = List.of(
                new Case("同TEAMの一般→OPEN/TEAM_MEMBERS", plainMember, openTeamMembers),
                new Case("作成者→OPEN/TEAM_MEMBERS", requester, openTeamMembers),
                new Case("作成者→DRAFT", requester, draft),
                new Case("所属なし→JOBBER_PUBLIC_BOARD", outsider, publicBoard),
                new Case("他TEAMのADMIN→JOBBER_PUBLIC_BOARD", foreignAdmin, publicBoard),
                new Case("SYSTEM_ADMIN→CANCELLED", systemAdmin, cancelled));
        for (Case c : cases) {
            MvcResult result = call(tokens.issueAccessToken(c.actor(), List.of("USER")), c.posting());
            assertThat(result.getResponse().getStatus())
                    .as("%s: %s", c.name(), result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .isEqualTo(200);
            assertThat(json(result).path("data").path("id").asLong()).as(c.name()).isEqualTo(c.posting());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MvcResult call(String token, long id) throws Exception {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        var builder = get("/api/v1/jobs/{id}", id).header("Accept-Language", "ja");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        try { return mvc.perform(builder).andReturn(); }
        finally { SecurityContextHolder.clearContext(); }
    }

    private JsonNode json(MvcResult response) throws Exception {
        String body = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
    }

    private Long posting(Long team, VisibilityScope scope, JobPostingStatus status) {
        LocalDateTime start = LocalDateTime.now().plusDays(10).withNano(0);
        Long id = postings.save(JobPostingEntity.builder().teamId(team).createdByUserId(requester)
                .title("求人詳細試練").description("存在オラクルの求人").workLocationType(WorkLocationType.ONSITE)
                .workAddress("試練会場").workStartAt(start).workEndAt(start.plusHours(4))
                .rewardType(RewardType.LUMP_SUM).baseRewardJpy(3000).capacity(2)
                .applicationDeadlineAt(start.minusDays(1))
                .visibilityScope(scope).status(status).build()).getId();
        postingIds.add(id);
        return id;
    }

    private void member(Long user, Long scope) {
        memberships.save(MembershipEntity.builder().userId(user).scopeType(ScopeType.TEAM)
                .scopeId(scope).roleKind(RoleKind.MEMBER).build());
    }

    private Long user() {
        Long id = users.save(UserEntity.builder().email("job-detail-oracle-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName("求人").displayName("求人詳細").isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        return id;
    }

    private Long team(TeamEntity.Visibility visibility) {
        Long id = teams.save(TeamEntity.builder().slug("jd-" + UUID.randomUUID().toString().substring(0, 8))
                .name("求人詳細").visibility(visibility).supporterEnabled(false)
                .build()).getId();
        teamIds.add(id);
        return id;
    }

    private void assignRole(Long user, String name, Long scope) {
        RoleEntity role = roles.findByName(name).orElseGet(() -> {
            RoleEntity created = roles.save(RoleEntity.builder().name(name).displayName(name)
                    .priority(RolePriority.priority(name)).isSystem(false).build());
            createdRoleIds.add(created.getId());
            return created;
        });
        userRoles.save(UserRoleEntity.builder().userId(user).roleId(role.getId()).teamId(scope).build());
    }

    /** SYSTEM_ADMIN ロールをスコープなし（team/organization とも null）で付与する。 */
    private void assignSystemAdmin(Long user) {
        RoleEntity role = roles.findByName("SYSTEM_ADMIN").orElseGet(() -> {
            RoleEntity created = roles.save(RoleEntity.builder().name("SYSTEM_ADMIN").displayName("SYSTEM_ADMIN")
                    .priority(RolePriority.priority("SYSTEM_ADMIN")).isSystem(true).build());
            createdRoleIds.add(created.getId());
            return created;
        });
        userRoles.save(UserRoleEntity.builder().userId(user).roleId(role.getId()).build());
    }

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(transactions).execute(status -> work.get());
    }
}
