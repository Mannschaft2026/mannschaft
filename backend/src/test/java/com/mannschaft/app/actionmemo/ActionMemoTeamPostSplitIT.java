package com.mannschaft.app.actionmemo;

import com.mannschaft.app.actionmemo.dto.PublishDailyRequest;
import com.mannschaft.app.actionmemo.dto.PublishToTeamRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.service.ActionMemoPublishingService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 行動メモの個人投稿IDとチーム投稿IDの列分割（{@code timeline_post_id} / {@code team_timeline_post_id}）の実DB試練。
 *
 * <p>不具合: publishToTeam がチーム投稿IDを {@code timeline_post_id} に書き、続く publishDaily が
 * それを旧個人投稿とみなして論理削除していた（チームタイムラインから投稿が消える）。</p>
 *
 * <p><b>クラスに {@code @Transactional} を付けない理由</b>: 受け入れ条件 AC-4 / AC-9 は
 * 「コミット後（または失敗ロールバック後）の別トランザクションで読み直した状態」を検証する。
 * 旧投稿の論理削除は save(旧) を廃して dirty checking に委ねるため、テスト側のトランザクションに
 * 包むと flush/commit の有無を観測できない。そのため各サービス呼び出しは自前のトランザクションで確定させ、
 * 検証は {@link JdbcTemplate}（別接続）で行い、後始末は {@link #cleanUp()} で行う。</p>
 *
 * <p>AC-9 の失敗注入は {@link TimelinePostRepository} の spy（既定は実処理へ委譲）で、
 * 新規 PERSONAL 投稿の save のときだけ例外を投げる。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("行動メモ 個人投稿ID／チーム投稿IDの列分割（実DB）")
class ActionMemoTeamPostSplitIT extends AbstractMySqlIntegrationTest {

    /** 投稿対象日（publishDaily はリクエストで日付を指定できるため固定値で TZ 境界を避ける）。 */
    private static final LocalDate MEMO_DATE = LocalDate.of(2026, 3, 15);

    @Autowired private ActionMemoPublishingService publishingService;
    @Autowired private ActionMemoRepository memoRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;

    @MockitoSpyBean private TimelinePostRepository timelinePostRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long ownerId;
    private Long teamAdminId;
    private Long memoId;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        transactionTemplate.executeWithoutResult(tx -> {
            teamId = insertTeam("AM列分割チーム" + suffix);
            ownerId = insertUser("am-split-owner-" + suffix + "@example.com");
            teamAdminId = insertUser("am-split-admin-" + suffix + "@example.com");
            MembershipTestHelper.insertMembership(em, ownerId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);

            ActionMemoEntity memo = ActionMemoEntity.builder()
                    .userId(ownerId)
                    .memoDate(MEMO_DATE)
                    .content("列分割の試練メモ")
                    .category(ActionMemoCategory.WORK)
                    .build();
            memoId = memoRepository.save(memo).getId();
        });
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        List<Long> userIds = List.of(ownerId, teamAdminId);
        for (Long uid : userIds) {
            jdbcTemplate.update("DELETE FROM timeline_posts WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM action_memos WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM memberships WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", uid);
        }
        jdbcTemplate.update("DELETE FROM teams WHERE id = ?", teamId);
    }

    @Test
    @DisplayName("AC-4 個人投稿→チーム投稿→再度個人投稿で、旧個人投稿のみ論理削除・チーム投稿は生存・生存 PERSONAL は新しい1件のみ（別トランザクションで読み直し）")
    void 再度の終業投稿は旧個人投稿だけを消しチーム投稿を残す() {
        Long personal1 = publishDaily();
        Long teamPost = publishToTeam();
        Long personal2 = publishDaily();

        assertThat(personal2).isNotEqualTo(personal1);
        assertThat(deletedAtOf(personal1)).as("旧個人投稿は論理削除される").isNotNull();
        assertThat(deletedAtOf(teamPost)).as("チーム投稿は生存する").isNull();
        assertThat(statusOf(teamPost)).isNotEqualTo("DELETED");
        assertThat(scopeTypeOf(teamPost)).isEqualTo("TEAM");

        List<Long> alivePersonal = jdbcTemplate.queryForList(
                "SELECT id FROM timeline_posts WHERE user_id = ? AND scope_type = 'PERSONAL' AND deleted_at IS NULL",
                Long.class, ownerId);
        assertThat(alivePersonal).as("当該ユーザーの生存 PERSONAL 投稿は新しい1件のみ").containsExactly(personal2);

        Map<String, Object> memo = memoRow();
        assertThat(toLong(memo.get("timeline_post_id"))).isEqualTo(personal2);
        assertThat(toLong(memo.get("team_timeline_post_id"))).isEqualTo(teamPost);
        assertThat(toLong(memo.get("posted_team_id"))).isEqualTo(teamId);
    }

    @Test
    @DisplayName("AC-9 新規個人投稿の save が失敗したら旧投稿の論理削除もロールバックされ、投稿とメモの参照は不変（別トランザクションで読み直し）")
    void 新規投稿の保存失敗で旧投稿の論理削除もロールバックされる() {
        Long personal1 = publishDaily();
        Long teamPost = publishToTeam();

        doAnswer(inv -> {
            TimelinePostEntity p = inv.getArgument(0);
            if (p.getId() == null && p.getScopeType() == PostScopeType.PERSONAL) {
                throw new IllegalStateException("試練: 新規個人投稿の保存失敗を注入");
            }
            return inv.callRealMethod();
        }).when(timelinePostRepository).save(any(TimelinePostEntity.class));

        PublishDailyRequest req = new PublishDailyRequest();
        req.setMemoDate(MEMO_DATE);
        assertThatThrownBy(() -> publishingService.publishDaily(req, ownerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注入");

        assertThat(deletedAtOf(personal1)).as("旧個人投稿の論理削除はロールバックされる").isNull();
        assertThat(deletedAtOf(teamPost)).as("チーム投稿は生存").isNull();
        Map<String, Object> memo = memoRow();
        assertThat(toLong(memo.get("timeline_post_id"))).isEqualTo(personal1);
        assertThat(toLong(memo.get("team_timeline_post_id"))).isEqualTo(teamPost);
    }

    @Test
    @DisplayName("AC-11 通常GET と管理者GET の JSON に timeline_post_id（個人投稿ID）と team_timeline_post_id（チーム投稿ID）が載る")
    void 応答JSONに個人投稿IDとチーム投稿IDの両方が載る() throws Exception {
        Long personal = publishDaily();
        Long teamPost = publishToTeam();

        setAuthentication(ownerId);
        mockMvc.perform(get("/api/v1/action-memos/{id}", memoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.timeline_post_id").value(personal))
                .andExpect(jsonPath("$.data.team_timeline_post_id").value(teamPost));

        setAuthentication(teamAdminId);
        mockMvc.perform(get("/api/v1/teams/{teamId}/members/{memberId}/action-memos", teamId, ownerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(memoId))
                .andExpect(jsonPath("$.data[0].timeline_post_id").value(personal))
                .andExpect(jsonPath("$.data[0].team_timeline_post_id").value(teamPost));
    }

    // ── helpers ────────────────────────────────────────────────

    private Long publishDaily() {
        PublishDailyRequest req = new PublishDailyRequest();
        req.setMemoDate(MEMO_DATE);
        return publishingService.publishDaily(req, ownerId).getTimelinePostId();
    }

    private Long publishToTeam() {
        return publishingService.publishToTeam(memoId, new PublishToTeamRequest(teamId, null), ownerId)
                .getTimelinePostId();
    }

    private Object deletedAtOf(Long postId) {
        return jdbcTemplate.queryForObject("SELECT deleted_at FROM timeline_posts WHERE id = ?", Object.class, postId);
    }

    private String statusOf(Long postId) {
        return jdbcTemplate.queryForObject("SELECT status FROM timeline_posts WHERE id = ?", String.class, postId);
    }

    private String scopeTypeOf(Long postId) {
        return jdbcTemplate.queryForObject("SELECT scope_type FROM timeline_posts WHERE id = ?", String.class, postId);
    }

    private Map<String, Object> memoRow() {
        return jdbcTemplate.queryForMap(
                "SELECT timeline_post_id, team_timeline_post_id, posted_team_id FROM action_memos WHERE id = ?",
                memoId);
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'AM列分割', 'テスト', 'AM列分割テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('amsplit-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
