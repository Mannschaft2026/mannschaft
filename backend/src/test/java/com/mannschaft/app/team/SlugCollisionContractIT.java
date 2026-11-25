package com.mannschaft.app.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.dto.CreateEventRequest;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.service.EventService;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.entity.OrganizationSlugHistoryEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.repository.OrganizationSlugHistoryRepository;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.provisioning.dto.ProvisioningOrganizationCreateRequest;
import com.mannschaft.app.provisioning.dto.ProvisioningTeamCreateRequest;
import com.mannschaft.app.provisioning.service.ProvisioningService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamSlugHistoryEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamSlugHistoryRepository;
import com.mannschaft.app.team.service.TeamService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CMP-261021-0838 試練: 自動採番・ユーザー指定 slug が論理削除済み行／slug 履歴／並行作成と衝突しても
 * 500（一意制約違反の素通し）にならないことを実 MySQL の一意索引で検証する。
 *
 * <p>DB 側は {@code uq_teams_slug}・{@code uq_organizations_slug}・{@code uq_events_slug} がいずれも
 * 論理削除行を含めた全行一意。マスター決定（2026-11-25）: 論理削除済みの slug は永久予約（DB 変更なし）。</p>
 *
 * <p>Service の公開メソッドを（テスト側トランザクション無しで）呼び、commit まで通す。Controller は
 * {@code BusinessException} を 4xx、それ以外（{@code DataIntegrityViolationException} 等）を 500 へ写すため、
 * 「例外なし＝201」「{@code BusinessException}＝4xx」「その他の例外＝500」と読み替える。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261021-0838: slug 衝突で 500 にならない（実DB）")
class SlugCollisionContractIT extends AbstractMySqlIntegrationTest {

    /** 非 ASCII の名前を作るための文字表（SlugGenerator が全て落とすため基底 slug は "team" に揃う）。 */
    private static final String KANJI = "〇一二三四五六七八九甲乙丙丁戊己";

    @Autowired private TeamService teamService;
    @Autowired private OrganizationService organizationService;
    @Autowired private ProvisioningService provisioningService;
    @Autowired private EventService eventService;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TeamSlugHistoryRepository teamSlugHistoryRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private OrganizationSlugHistoryRepository organizationSlugHistoryRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired @Qualifier("event-pool") private ThreadPoolTaskExecutor eventPool;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<String> teamNames = new ArrayList<>();
    private final List<String> orgNames = new ArrayList<>();
    private final List<Long> eventScopeTeamIds = new ArrayList<>();
    private LocalDateTime startedAt;
    private Long userId;
    private Long systemAdminId;

    @BeforeEach
    void setUp() {
        startedAt = LocalDateTime.now().minusSeconds(1);
        userId = newUser();
        systemAdminId = newUser();
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, systemAdminId, "SYSTEM_ADMIN", null, null);
            return null;
        });
    }

    // ===================== E1 =====================

    @Test
    void E1_チーム作成_論理削除済みチームと同じ英字名_500にならず別slugで作成される() {
        String name = asciiName("e1t");
        String base = SlugGenerator.generate(name);
        softDeletedTeam(name, base);

        String[] slug = new String[1];
        assertThatCode(() -> slug[0] = teamService.createTeam(userId, teamReq(name, null)).getData().getSlug())
                .doesNotThrowAnyException();
        assertThat(slug[0]).isNotEqualTo(base);
    }

    @Test
    void E1_組織作成_論理削除済み組織と同じ英字名_500にならず別slugで作成される() {
        String name = asciiName("e1o");
        String base = SlugGenerator.generate(name);
        softDeletedOrganization(name, base);

        String[] slug = new String[1];
        assertThatCode(() -> slug[0] = organizationService.createOrganization(userId, orgReq(name, null))
                .getData().getSlug()).doesNotThrowAnyException();
        assertThat(slug[0]).isNotEqualTo(base);
    }

    @Test
    void E1_プロビジョニングでチーム作成_論理削除済みチームと同じ名前_500にならず作成される() {
        String name = asciiName("e1pt");
        softDeletedTeam(name, SlugGenerator.generate(name));

        assertThatCode(() -> provisioningService.createTeam(systemAdminId,
                new ProvisioningTeamCreateRequest(name, "slug-collision-team@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void E1_プロビジョニングで組織作成_論理削除済み組織と同じ名前_500にならず作成される() {
        String name = asciiName("e1po");
        softDeletedOrganization(name, SlugGenerator.generate(name));

        assertThatCode(() -> provisioningService.createOrganization(systemAdminId,
                new ProvisioningOrganizationCreateRequest(name, "slug-collision-org@example.com")))
                .doesNotThrowAnyException();
    }

    @Test
    void E1_イベント作成_slug未指定で論理削除済みイベントと同じ副題_500にならず別slugで作成される() {
        Long scopeTeamId = activeTeam(asciiName("e1es"));
        eventScopeTeamIds.add(scopeTeamId);
        String subtitle = asciiName("e1ev");
        String base = SlugGenerator.generate(subtitle);
        softDeletedEvent(scopeTeamId, base);

        String[] slug = new String[1];
        assertThatCode(() -> slug[0] = eventService.createEvent(EventScopeType.TEAM, scopeTeamId, userId,
                eventReq(null, subtitle)).getContent().slug()).doesNotThrowAnyException();
        assertThat(slug[0]).isNotEqualTo(base);
    }

    // ===================== E2 =====================

    @Test
    void E2_チーム作成_指定slugが論理削除済みチームのslug_TEAM_062で500にしない() {
        String deletedSlug = SlugGenerator.generate(asciiName("e2t"));
        softDeletedTeam(asciiName("e2td"), deletedSlug);

        Throwable thrown = catchThrowable(() -> teamService.createTeam(userId, teamReq(asciiName("e2tn"), deletedSlug)));

        assertBusinessCode(thrown, "TEAM_062");
    }

    @Test
    void E2_組織作成_指定slugが論理削除済み組織のslug_ORG_062で500にしない() {
        String deletedSlug = SlugGenerator.generate(asciiName("e2o"));
        softDeletedOrganization(asciiName("e2od"), deletedSlug);

        Throwable thrown = catchThrowable(() ->
                organizationService.createOrganization(userId, orgReq(asciiName("e2on"), deletedSlug)));

        assertBusinessCode(thrown, "ORG_062");
    }

    @Test
    void E2_イベント作成_指定slugが論理削除済みイベントのslug_EVENT_016で500にしない() {
        Long scopeTeamId = activeTeam(asciiName("e2es"));
        eventScopeTeamIds.add(scopeTeamId);
        String deletedSlug = SlugGenerator.generate(asciiName("e2ev"));
        softDeletedEvent(scopeTeamId, deletedSlug);

        Throwable thrown = catchThrowable(() -> eventService.createEvent(EventScopeType.TEAM, scopeTeamId, userId,
                eventReq(deletedSlug, "副題")));

        assertBusinessCode(thrown, "EVENT_016");
    }

    // ===================== E3 =====================

    @Test
    void E3_チームslug可用性_論理削除済みチームのslug_使用中と返す() {
        String deletedSlug = SlugGenerator.generate(asciiName("e3t"));
        softDeletedTeam(asciiName("e3td"), deletedSlug);

        var res = teamService.checkSlugAvailability(deletedSlug);

        assertThat(res.available()).isFalse();
        assertThat(res.reason()).isEqualTo("SLUG_ALREADY_TAKEN");
    }

    @Test
    void E3_組織slug可用性_論理削除済み組織のslug_使用中と返す() {
        String deletedSlug = SlugGenerator.generate(asciiName("e3o"));
        softDeletedOrganization(asciiName("e3od"), deletedSlug);

        var res = organizationService.checkSlugAvailability(deletedSlug);

        assertThat(res.available()).isFalse();
        assertThat(res.reason()).isEqualTo("SLUG_ALREADY_TAKEN");
    }

    // ===================== E4 =====================

    @Test
    void E4_チーム作成_基底slugが他チームのslug履歴に予約済み_旧slugを奪わず別slugで作成される() {
        String name = asciiName("e4t");
        String base = SlugGenerator.generate(name);
        Long owner = activeTeam(asciiName("e4to"));
        inTx(() -> teamSlugHistoryRepository.saveAndFlush(TeamSlugHistoryEntity.builder()
                .teamId(owner).oldSlug(base).createdAt(LocalDateTime.now()).build()));

        String slug = teamService.createTeam(userId, teamReq(name, null)).getData().getSlug();

        assertThat(slug).isNotEqualTo(base);
    }

    @Test
    void E4_組織作成_基底slugが他組織のslug履歴に予約済み_旧slugを奪わず別slugで作成される() {
        String name = asciiName("e4o");
        String base = SlugGenerator.generate(name);
        Long owner = activeOrganization(asciiName("e4oo"));
        inTx(() -> organizationSlugHistoryRepository.saveAndFlush(OrganizationSlugHistoryEntity.builder()
                .organizationId(owner).oldSlug(base).createdAt(LocalDateTime.now()).build()));

        String slug = organizationService.createOrganization(userId, orgReq(name, null)).getData().getSlug();

        assertThat(slug).isNotEqualTo(base);
    }

    // ===================== E5 =====================

    @Test
    void E5_チーム作成_日本語名を連続作成_どちらも成功し別slugが採番される() {
        String first = kanjiName("連続試練");
        String second = kanjiName("連続試練");

        String a = teamService.createTeam(userId, teamReq(first, null)).getData().getSlug();
        String b = teamService.createTeam(userId, teamReq(second, null)).getData().getSlug();

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void E5_チーム作成_名前の異なる日本語名2件を同時作成_どちらも成功し別slugが採番される() throws Exception {
        assertConcurrentCreatesSucceed(name -> teamService.createTeam(userId, teamReq(name, null)).getData().getSlug(),
                teamNames);
    }

    @Test
    void E5_組織作成_名前の異なる日本語名2件を同時作成_どちらも成功し別slugが採番される() throws Exception {
        assertConcurrentCreatesSucceed(name -> organizationService.createOrganization(userId, orgReq(name, null))
                .getData().getSlug(), orgNames);
    }

    // ===================== helpers =====================

    private void assertConcurrentCreatesSucceed(java.util.function.Function<String, String> create,
                                                List<String> registry) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 競合窓は確率的なので複数ラウンド回し、一意制約違反の素通しを炙り出す
            for (int round = 0; round < 5; round++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                List<Future<String>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    String name = kanjiName("並行試練");
                    registry.add(name);
                    futures.add(pool.submit(() -> {
                        barrier.await(10, TimeUnit.SECONDS);
                        return create.apply(name);
                    }));
                }
                Set<String> slugs = new HashSet<>();
                for (Future<String> f : futures) {
                    // ExecutionException（一意制約違反＝500 相当）なら red
                    slugs.add(f.get(60, TimeUnit.SECONDS));
                }
                assertThat(slugs).as("round %d: 2件とも別 slug", round).hasSize(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertBusinessCode(Throwable thrown, String code) {
        assertThat(thrown).as("500（業務例外以外）にせず %s を返すこと", code).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo(code);
    }

    private CreateTeamRequest teamReq(String name, String slug) {
        teamNames.add(name);
        return new CreateTeamRequest(name, null, null, null, null, null, null, slug, false, null);
    }

    private CreateOrganizationRequest orgReq(String name, String slug) {
        orgNames.add(name);
        return new CreateOrganizationRequest(name, "OTHER", null, null, null, null, slug);
    }

    private static CreateEventRequest eventReq(String slug, String subtitle) {
        return new CreateEventRequest(
                null, slug, subtitle, null, null,
                null, null, null, null, null, "MEMBERS_ONLY",
                null, null, null, false, null, null, null, null, null);
    }

    /** ASCII のみ・3〜30 文字・一意な名前（そのまま基底 slug になる）。 */
    private static String asciiName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 非 ASCII のみ・一意な名前（基底 slug は "team" に落ちる）。 */
    private static String kanjiName(String prefix) {
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        StringBuilder sb = new StringBuilder(prefix);
        for (char c : hex.toCharArray()) {
            sb.append(KANJI.charAt(Character.digit(c, 16)));
        }
        return sb.toString();
    }

    private Long activeTeam(String name) {
        teamNames.add(name);
        return inTx(() -> teamRepository.saveAndFlush(TeamEntity.builder()
                .slug(SlugGenerator.generate(name)).name(name).template("sports")
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId());
    }

    private void softDeletedTeam(String name, String slug) {
        teamNames.add(name);
        inTx(() -> {
            Long id = teamRepository.saveAndFlush(TeamEntity.builder()
                    .slug(slug).name(name).template("sports")
                    .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
            em.createNativeQuery("UPDATE teams SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", id).executeUpdate();
            return null;
        });
    }

    private Long activeOrganization(String name) {
        orgNames.add(name);
        return inTx(() -> organizationRepository.saveAndFlush(orgEntity(name, SlugGenerator.generate(name))).getId());
    }

    private void softDeletedOrganization(String name, String slug) {
        orgNames.add(name);
        inTx(() -> {
            Long id = organizationRepository.saveAndFlush(orgEntity(name, slug)).getId();
            em.createNativeQuery("UPDATE organizations SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", id).executeUpdate();
            return null;
        });
    }

    private static OrganizationEntity orgEntity(String name, String slug) {
        return OrganizationEntity.builder().slug(slug).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build();
    }

    private void softDeletedEvent(Long scopeTeamId, String slug) {
        inTx(() -> {
            Long id = eventRepository.saveAndFlush(EventEntity.builder()
                    .scopeType(EventScopeType.TEAM).scopeId(scopeTeamId).slug(slug)
                    .status(EventStatus.DRAFT).visibility(EventVisibility.MEMBERS_ONLY)
                    .isApprovalRequired(false).createdBy(userId).build()).getId();
            em.createNativeQuery("UPDATE events SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", id).executeUpdate();
            return null;
        });
    }

    private Long newUser() {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        userIds.add(id);
        inTx(() -> {
            MembershipTestHelper.insertActiveUser(em, id);
            return null;
        });
        return id;
    }

    private <T> T inTx(Supplier<T> action) {
        return new TransactionTemplate(txManager).execute(status -> action.get());
    }

    /** このテストが作成したデータ（名前・ID・作成時刻で特定）だけを消す。 */
    @AfterEach
    void cleanUp() {
        await().atMost(Duration.ofSeconds(20)).until(() -> eventPool.getActiveCount() == 0
                && eventPool.getThreadPoolExecutor().getQueue().isEmpty());
        inTx(() -> {
            List<Long> eventIds = new ArrayList<>();
            for (Long scopeId : eventScopeTeamIds) {
                for (Object id : em.createNativeQuery(
                                "SELECT id FROM events WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", scopeId).getResultList()) {
                    eventIds.add(((Number) id).longValue());
                }
            }
            for (Long eventId : eventIds) {
                em.createNativeQuery("DELETE FROM chat_channels WHERE source_type = 'EVENT' AND source_id = :id")
                        .setParameter("id", eventId).executeUpdate();
                em.createNativeQuery("DELETE FROM events WHERE id = :id").setParameter("id", eventId).executeUpdate();
            }
            for (Long teamId : idsByName("teams", teamNames)) {
                for (String table : List.of("activity_feed", "user_calendar_layer_settings")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE scope_type = 'TEAM' AND scope_id = :id")
                            .setParameter("id", teamId).executeUpdate();
                }
                for (String table : List.of("provisioning_invitations", "team_slug_history", "team_shift_settings",
                        "audit_logs", "user_roles")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE team_id = :id")
                            .setParameter("id", teamId).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", teamId).executeUpdate();
            }
            for (Long orgId : idsByName("organizations", orgNames)) {
                for (String table : List.of("activity_feed", "user_calendar_layer_settings")) {
                    em.createNativeQuery("DELETE FROM " + table
                                    + " WHERE scope_type = 'ORGANIZATION' AND scope_id = :id")
                            .setParameter("id", orgId).executeUpdate();
                }
                for (String table : List.of("provisioning_invitations", "organization_slug_history", "audit_logs",
                        "user_roles")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE organization_id = :id")
                            .setParameter("id", orgId).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'ORGANIZATION' AND scope_id = :id")
                        .setParameter("id", orgId).executeUpdate();
                em.createNativeQuery("DELETE FROM organizations WHERE id = :id")
                        .setParameter("id", orgId).executeUpdate();
            }
            // プロビジョニング招待メール（本テストの時間窓・専用テンプレ種別のみ）
            em.createNativeQuery("DELETE FROM email_outbox WHERE template_kind = 'PROVISIONING_ADMIN_INVITE'"
                            + " AND created_at >= :since")
                    .setParameter("since", startedAt).executeUpdate();
            for (Long id : userIds) {
                for (String table : List.of("audit_logs", "user_calendar_layer_settings", "user_roles", "memberships")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE user_id = :id")
                            .setParameter("id", id).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            return null;
        });
    }

    @SuppressWarnings("unchecked")
    private List<Long> idsByName(String table, List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        // 論理削除済みを含めて拾う（native なので @SQLRestriction は効かない）
        List<Object> rows = em.createNativeQuery("SELECT id FROM " + table + " WHERE name IN (:names)")
                .setParameter("names", names).getResultList();
        return rows.stream().map(r -> ((Number) r).longValue()).toList();
    }
}
