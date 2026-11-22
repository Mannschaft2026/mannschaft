package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * AC-77（Codex 指摘）: 活動テンプレート一覧の SQL 数がテンプレート件数 N に比例しないこと。
 *
 * <p>現状は {@code ActivityTemplateService#listTemplates} がテンプレートごとに field Repository を
 * 呼ぶ N+1 のため red。Statistics は SessionFactory 全体で共有されるため {@link Isolated}。
 * 2 件の一覧と 12 件の一覧で SQL 数を比べ、10 件増えても増分が 2 本以内であることを要求する
 * （認可・会員確認などの定数クエリは両方に同じだけ入る）。</p>
 */
@Isolated
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("AC-77: 活動テンプレート一覧の SQL 数は件数に比例しない")
class ActivityTemplateListQueryCountIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private ActivityTemplateRepository templates;
    @Autowired private ActivityTemplateFieldRepository fields;
    @PersistenceContext private EntityManager em;

    private Long admin;
    private TeamEntity team;

    @BeforeEach
    void fixtureをコミットする() {
        inTx(() -> {
            admin = users.save(UserEntity.builder().email("act-tpl-qc-" + UUID.randomUUID() + "@example.com")
                    .lastName("試練").firstName("admin").displayName("admin").isSearchable(false)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
            team = teams.save(TeamEntity.builder().slug("acttplqc-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("クエリ数チーム").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
            MembershipTestHelper.insertMembership(em, admin, ScopeType.TEAM, team.getId(), RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, admin, "ADMIN", team.getId(), null);
            return null;
        });
    }

    @AfterEach
    void 自分が作成したfixtureだけ削除する() {
        if (team == null) {
            return;
        }
        inTx(() -> {
            em.createNativeQuery("DELETE FROM activity_template_fields WHERE template_id IN "
                            + "(SELECT id FROM activity_templates WHERE scope_id = :id)")
                    .setParameter("id", team.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM activity_templates WHERE scope_id = :id")
                    .setParameter("id", team.getId()).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id").setParameter("id", admin).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id = :id").setParameter("id", admin).executeUpdate();
            teams.deleteAll(List.of(team));
            users.deleteAllById(List.of(admin));
            return null;
        });
    }

    @Test
    @DisplayName("AC-77: テンプレート2件→12件に増えても一覧の SQL 数の増分は2本以内（N+1 でない）")
    void ac77_一覧のSQL数が件数に比例しない() throws Exception {
        seed(2, 0);
        listAndCount(2); // ウォームアップ（初回のみ走る定数クエリを測定窓から外す）
        long few = listAndCount(2);

        seed(10, 2);
        long many = listAndCount(12);

        System.out.printf("AC-77 実測 N=2: prepareStatementCount=%d / N=12: prepareStatementCount=%d%n", few, many);
        assertThat(many)
                .as("N=2 の SQL 数 %d に対し N=12 が %d 本。テンプレートごとの field 取得が残っている（N+1）", few, many)
                .isLessThanOrEqualTo(few + 2);
    }

    private long listAndCount(int expectedSize) throws Exception {
        String token = tokens.issueAccessToken(admin, List.of("MEMBER"));
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = stats.isStatisticsEnabled();
        long prepared;
        MvcResult result;
        try {
            stats.setStatisticsEnabled(true);
            stats.clear();
            result = mockMvc.perform(get("/api/v1/activity-templates").param("scope_type", "TEAM")
                    .param("scope_id", String.valueOf(team.getId()))
                    .header("Authorization", "Bearer " + token)).andReturn();
            prepared = stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(originallyEnabled);
        }
        assertThat(result.getResponse().getStatus()).as("前提: 一覧は200").isEqualTo(200);
        JsonNode data = mapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.size()).isEqualTo(expectedSize);
        data.forEach(t -> assertThat(t.path("fields").size()).as("各テンプレートの項目が付いて返る").isEqualTo(2));
        return prepared;
    }

    private void seed(int count, int offset) {
        inTx(() -> {
            for (int i = 0; i < count; i++) {
                Long id = templates.save(ActivityTemplateEntity.builder().scopeType(ActivityScopeType.TEAM)
                        .scopeId(team.getId()).name("テンプレ" + (offset + i)).sortOrder(offset + i)
                        .createdBy(admin).build()).getId();
                for (int f = 0; f < 2; f++) {
                    fields.save(ActivityTemplateFieldEntity.builder().templateId(id).fieldKey("k" + f)
                            .fieldLabel("項目" + f).fieldType(FieldType.TEXT).sortOrder(f).build());
                }
            }
            return null;
        });
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }
}
