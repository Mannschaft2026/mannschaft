package com.mannschaft.app.cms;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.common.BusinessException;
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
import static org.mockito.Mockito.doReturn;

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
        return jdbcTemplate.queryForList(
                "SELECT slug FROM blog_posts WHERE user_id = ? AND deleted_at IS NULL ORDER BY id", String.class, userId);
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
}
