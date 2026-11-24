package com.mannschaft.app.cms;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * slug の同時作成競合（事前確認の後に別リクエストが同じ slug を確定させた）を、実 Flyway スキーマの
 * 生存行一意制約（V267: {@code uq_bp_slug_user_active}）で再現し、500 にならないことを固定する。
 *
 * <p>通常の統合テストは {@code ddl-auto=create} で生成列を含む一意制約が入らないため、本番と同じ Flyway
 * スキーマを使う。競合は「事前確認だけが未使用と答える」ように Repository の spy で作る（その時点で
 * 同 slug の行は既に確定済み）。テスト全体のトランザクションは置かず、Controller からの呼び出しと同じく
 * 呼び出し元にトランザクションが無い状態で {@link BlogPostService} を呼ぶ。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.cms.BlogPostSlugConflictRetryFlywayIT#isDockerAvailable")
@DisplayName("ブログ記事 slug の同時作成競合（Flyway 実スキーマ）")
class BlogPostSlugConflictRetryFlywayIT {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_blog_slug_conflict_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    /** 競合の検証に認可判定を混ぜないため、チーム・組織の所属確認だけ許可スタブにする。 */
    @MockitoBean
    private AccessControlService accessControlService;

    /** Spring Data の Repository は JDK 動的プロキシのため、spy の既定 Answer（元 Bean への委譲）で実体を呼ぶ。 */
    @MockitoSpyBean
    private BlogPostRepository blogPostRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private BlogPostService blogPostService;

    private static Object callReal(InvocationOnMock inv) throws Throwable {
        return Mockito.mockingDetails(inv.getMock()).getMockCreationSettings().getDefaultAnswer().answer(inv);
    }

    private Long persistUser(String email) {
        return transactionTemplate.execute(status -> {
            UserEntity user = UserEntity.builder()
                    .email(email)
                    .lastName("競合")
                    .firstName("slug")
                    .displayName("slug競合")
                    .status(UserEntity.UserStatus.ACTIVE)
                    .locale("ja")
                    .timezone("Asia/Tokyo")
                    .isSearchable(true)
                    .build();
            em.persist(user);
            return user.getId();
        });
    }

    private static CreateBlogPostRequest personal(String title, String slug) {
        return new CreateBlogPostRequest(null, null, null, title, slug, "本文",
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private List<String> liveSlugs(Long userId) {
        return liveSlugs("user_id", userId);
    }

    /** scopeColumn は本クラスの固定値（user_id / team_id / organization_id）のみ。 */
    private List<String> liveSlugs(String scopeColumn, Long scopeId) {
        return jdbcTemplate.queryForList("SELECT slug FROM blog_posts WHERE " + scopeColumn
                + " = ? AND deleted_at IS NULL ORDER BY id", String.class, scopeId);
    }

    private Long persistTeam(String slug) {
        return transactionTemplate.execute(status -> {
            TeamEntity team = TeamEntity.builder()
                    .slug(slug)
                    .name("slug競合チーム")
                    .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                    .supporterEnabled(true)
                    .build();
            em.persist(team);
            return team.getId();
        });
    }

    private Long persistOrganization(String slug) {
        return transactionTemplate.execute(status -> {
            OrganizationEntity organization = OrganizationEntity.builder()
                    .slug(slug)
                    .name("slug競合組織")
                    .orgType(OrganizationEntity.OrgType.ASSOCIATION)
                    .visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                    .supporterEnabled(true)
                    .build();
            em.persist(organization);
            return organization.getId();
        });
    }

    private static CreateBlogPostRequest scoped(Long teamId, Long organizationId, String title) {
        return new CreateBlogPostRequest(teamId == null ? null : teamId.toString(),
                organizationId == null ? null : organizationId.toString(), null, title, null, "本文",
                null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("自動採番: 事前確認の後に同 slug が確定していても、採番し直して -2 で作成できる")
    void 自動採番の競合は採番し直して作成できる() {
        Long userId = persistUser("slug-conflict-auto@example.com");
        blogPostService.createPost(userId, personal("Weekly Report", null));
        // 2 回目の作成の 1 回目の事前確認だけ「未使用」と答える（＝確認と INSERT の間に競合相手が確定した状態）
        doReturn(false).doAnswer(BlogPostSlugConflictRetryFlywayIT::callReal)
                .when(blogPostRepository).existsByUserIdAndSlug(eq(userId), eq("weeklyreport"));

        BlogPostResponse second = blogPostService.createPost(userId, personal("Weekly Report", null));

        assertThat(second.getContent().slug()).isEqualTo("weeklyreport-2");
        assertThat(liveSlugs(userId)).containsExactly("weeklyreport", "weeklyreport-2");
    }

    @Test
    @DisplayName("明示指定: 事前確認の後に同 slug が確定していた一意制約違反は 409（CMS_005）で、行は増えない")
    void 明示指定の競合は409() {
        Long userId = persistUser("slug-conflict-explicit@example.com");
        blogPostService.createPost(userId, personal("t1", "race-slug"));
        doReturn(false).when(blogPostRepository).existsByUserIdAndSlug(eq(userId), eq("race-slug"));

        assertThatThrownBy(() -> blogPostService.createPost(userId, personal("t2", "race-slug")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CmsErrorCode.DUPLICATE_SLUG));
        assertThat(liveSlugs(userId)).containsExactly("race-slug");
    }

    @Test
    @DisplayName("自動採番(チーム): uq_bp_slug_team_active の競合でも採番し直して -2 で作成できる")
    void チームスコープの競合は採番し直して作成できる() {
        Long userId = persistUser("slug-conflict-team@example.com");
        Long teamId = persistTeam("slug-conflict-team");
        blogPostService.createPost(userId, scoped(teamId, null, "Team Report"));
        doReturn(false).doAnswer(BlogPostSlugConflictRetryFlywayIT::callReal)
                .when(blogPostRepository).existsByTeamIdAndSlug(eq(teamId), eq("teamreport"));

        BlogPostResponse second = blogPostService.createPost(userId, scoped(teamId, null, "Team Report"));

        assertThat(second.getContent().slug()).isEqualTo("teamreport-2");
        assertThat(liveSlugs("team_id", teamId)).containsExactly("teamreport", "teamreport-2");
    }

    @Test
    @DisplayName("自動採番(組織): uq_bp_slug_org_active の競合でも採番し直して -2 で作成できる")
    void 組織スコープの競合は採番し直して作成できる() {
        Long userId = persistUser("slug-conflict-org@example.com");
        Long orgId = persistOrganization("slug-conflict-org");
        blogPostService.createPost(userId, scoped(null, orgId, "Org Report"));
        doReturn(false).doAnswer(BlogPostSlugConflictRetryFlywayIT::callReal)
                .when(blogPostRepository).existsByOrganizationIdAndSlug(eq(orgId), eq("orgreport"));

        BlogPostResponse second = blogPostService.createPost(userId, scoped(null, orgId, "Org Report"));

        assertThat(second.getContent().slug()).isEqualTo("orgreport-2");
        assertThat(liveSlugs("organization_id", orgId)).containsExactly("orgreport", "orgreport-2");
    }

    @Test
    @DisplayName("呼び出し元がトランザクション内なら再試行せず 1 回で 409（CMS_027）にし、呼び出し元ごとロールバックする")
    void 呼び出し元TX内では再試行しない() {
        Long userId = persistUser("slug-conflict-outer-tx@example.com");
        blogPostService.createPost(userId, personal("Outer Report", null));
        clearInvocations(blogPostRepository);
        // 事前確認は常に「未使用」と答える（再試行したとしても毎回競合する状態）
        doReturn(false).when(blogPostRepository).existsByUserIdAndSlug(eq(userId), eq("outerreport"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status ->
                blogPostService.createPost(userId, personal("Outer Report", null))))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(CmsErrorCode.SLUG_AUTO_ASSIGN_CONFLICT));
        verify(blogPostRepository, times(1)).existsByUserIdAndSlug(userId, "outerreport");
        verify(blogPostRepository, times(1)).saveAndFlush(org.mockito.ArgumentMatchers.any());
        assertThat(liveSlugs(userId)).containsExactly("outerreport");
    }
}
