package com.mannschaft.app.social.announcement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.inbox.service.adapter.AnnouncementInboxAdapter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.social.announcement.visibility.AnnouncementFeedVisibilityResolver;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.entity.SurveyEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261122-1300: アンケート（SURVEY）・回覧板（CIRCULATION_DOCUMENT）の元が非公開状態のお知らせの扱い
 * （実 MySQL・Controller 経由。N9 {@link AnnouncementSourcePublicationIT} と同じ流儀）。
 *
 * <ul>
 *   <li><b>お知らせ化</b>（POST）: アンケート DRAFT / ARCHIVED、回覧板 DRAFT / CANCELLED、論理削除済みは、
 *       ADMIN・著者 MEMBER・非著者 MEMBER・他テナントの誰が叩いても不在の sourceId と同一応答（404 {@code ANNOUNCE_006}）。
 *       許可状態（アンケート PUBLISHED / CLOSED、回覧板 ACTIVE / COMPLETED）では従来どおり
 *       （ADMIN・著者は 201、非著者は 403 {@code ANNOUNCE_002}、他テナントはスコープ不一致の 400 {@code ANNOUNCE_005}）。
 *       TEAM・ORGANIZATION の双方で確認する（AC1〜AC4・AC6）。</li>
 *   <li><b>既存のお知らせ</b>: 元が非公開状態（後から戻した・最初から下書き・論理削除・孤児）の行は、一覧・未読件数・
 *       ページング・単件既読・read-batch・read-all・ピン留め・ピン解除・お知らせ解除・インボックス・F00 Resolver で
 *       不在のお知らせと同じ扱い（AC5）。</li>
 * </ul>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261122-1300 アンケート・回覧板の元が非公開: お知らせ化・既存お知らせは不在と同一（IT）")
class AnnouncementSurveyCirculationSourcePublicationIT extends AbstractMySqlIntegrationTest {

    /** 確実に存在しない ID。 */
    private static final long MISSING_ID = Long.MAX_VALUE;
    private static final String SURVEY = "SURVEY";
    private static final String CIRCULATION = "CIRCULATION_DOCUMENT";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AnnouncementFeedRepository feedRepository;
    @Autowired private AnnouncementInboxAdapter inboxAdapter;
    @Autowired private AnnouncementFeedVisibilityResolver visibilityResolver;
    @PersistenceContext private EntityManager em;

    private String key;
    private Long teamId;
    private Long otherTeamId;
    private Long orgId;
    private Long otherOrgId;
    /** teamId・orgId の ADMIN。 */
    private Long adminId;
    /** teamId・orgId のメンバーで、元コンテンツの著者（createdBy）。 */
    private Long authorId;
    /** teamId・orgId のメンバーで、著者ではない。 */
    private Long nonAuthorId;
    /** 他テナント（otherTeamId・otherOrgId）のメンバー。 */
    private Long otherTenantId;

    /** AC6 の 4 区分。 */
    enum Actor { ADMIN, AUTHOR, NON_AUTHOR, OTHER_TENANT }

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        teamId = insertTeam("a");
        otherTeamId = insertTeam("b");
        orgId = insertOrganization("a");
        otherOrgId = insertOrganization("b");
        adminId = insertUser("admin");
        authorId = insertUser("author");
        nonAuthorId = insertUser("nonauthor");
        otherTenantId = insertUser("other");

        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", null, orgId);
        for (Long member : List.of(authorId, nonAuthorId)) {
            MembershipTestHelper.insertMembership(em, member, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, member, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertMembership(em, otherTenantId, ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherTenantId, ScopeType.ORGANIZATION, otherOrgId, RoleKind.MEMBER);
        em.flush();
    }

    // ═════════════════════════════════════════════════════════════════════
    // お知らせ化（POST）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST お知らせ化")
    class Create {

        @ParameterizedTest(name = "AC1/AC2/AC3/AC6: {0} {1} × TEAM/ORGANIZATION × 4 区分 → 不在と同一")
        @CsvSource({
                "SURVEY, DRAFT", "SURVEY, ARCHIVED", "SURVEY, DELETED",
                "CIRCULATION_DOCUMENT, DRAFT", "CIRCULATION_DOCUMENT, CANCELLED", "CIRCULATION_DOCUMENT, DELETED"})
        @DisplayName("AC1/AC2/AC3/AC6: 非公開状態・論理削除の元は、ADMIN・著者・非著者・他テナントの誰でも不在の sourceId と同一"
                + "（404 ANNOUNCE_006。スコープ不一致の ANNOUNCE_005 より先行）・行を作らない")
        void nonPublicSource_sameAsMissing_forAllActors(String sourceType, String state) throws Exception {
            for (AnnouncementScopeType scope : List.of(AnnouncementScopeType.TEAM, AnnouncementScopeType.ORGANIZATION)) {
                Long sourceId = source(sourceType, state, scope, ownScopeId(scope), null);
                for (Actor actor : Actor.values()) {
                    Long pathScopeId = pathScopeId(actor, scope);
                    MvcResult missing = perform(actorId(actor), create(scope, pathScopeId, sourceType, MISSING_ID));
                    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
                    assertThat(code(missing)).isEqualTo("ANNOUNCE_006");

                    MvcResult actual = perform(actorId(actor), create(scope, pathScopeId, sourceType, sourceId));
                    String label = sourceType + " " + state + " " + scope + " " + actor;
                    assertThat(actual.getResponse().getStatus()).as(label + ": status は不在と同一").isEqualTo(404);
                    assertThat(json(actual)).as(label + ": 本文（error.code・message）は不在と同一").isEqualTo(json(missing));
                }
                assertThat(feedCount(sourceType, sourceId)).as(sourceType + " " + state + " " + scope + ": 行を作らない")
                        .isZero();
            }
        }

        @ParameterizedTest(name = "AC4/AC6: {0} {1} × TEAM/ORGANIZATION × 4 区分 → 従来どおり")
        @CsvSource({
                "SURVEY, PUBLISHED", "SURVEY, CLOSED",
                "CIRCULATION_DOCUMENT, ACTIVE", "CIRCULATION_DOCUMENT, COMPLETED"})
        @DisplayName("AC4/AC6: 許可状態の元は従来どおり（ADMIN・著者は 201、非著者は 403 ANNOUNCE_002、"
                + "他テナントはスコープ不一致の 400 ANNOUNCE_005）")
        void allowedSource_existingBehavior(String sourceType, String state) throws Exception {
            for (AnnouncementScopeType scope : List.of(AnnouncementScopeType.TEAM, AnnouncementScopeType.ORGANIZATION)) {
                String label = sourceType + " " + state + " " + scope;
                Long forAdmin = source(sourceType, state, scope, ownScopeId(scope), null);
                MvcResult admin = perform(adminId, create(scope, ownScopeId(scope), sourceType, forAdmin));
                assertThat(admin.getResponse().getStatus()).as(label + " ADMIN は 201").isEqualTo(201);
                assertThat(feedCount(sourceType, forAdmin)).isEqualTo(1);

                Long forAuthor = source(sourceType, state, scope, ownScopeId(scope), null);
                MvcResult author = perform(authorId, create(scope, ownScopeId(scope), sourceType, forAuthor));
                assertThat(author.getResponse().getStatus()).as(label + " 著者 MEMBER は 201").isEqualTo(201);

                Long forOthers = source(sourceType, state, scope, ownScopeId(scope), null);
                MvcResult nonAuthor = perform(nonAuthorId, create(scope, ownScopeId(scope), sourceType, forOthers));
                assertThat(nonAuthor.getResponse().getStatus()).as(label + " 非著者 MEMBER は 403").isEqualTo(403);
                assertThat(code(nonAuthor)).isEqualTo("ANNOUNCE_002");

                MvcResult other = perform(otherTenantId,
                        create(scope, pathScopeId(Actor.OTHER_TENANT, scope), sourceType, forOthers));
                assertThat(other.getResponse().getStatus()).as(label + " 他テナントはスコープ不一致 400").isEqualTo(400);
                assertThat(code(other)).isEqualTo("ANNOUNCE_005");
                assertThat(feedCount(sourceType, forOthers)).as(label + " 拒否では行を作らない").isZero();
            }
        }

        @Test
        @DisplayName("AC4: 期限切れの CLOSED アンケート・COMPLETED 回覧板はお知らせ化できるが、期限切れ非表示の既存条件どおり一覧に出ない")
        void expiredAllowedSource_createdButHidden() throws Exception {
            Long survey = source(SURVEY, "CLOSED", AnnouncementScopeType.TEAM, teamId, LocalDateTime.now().minusDays(2));
            Long circulation = source(CIRCULATION, "COMPLETED", AnnouncementScopeType.TEAM, teamId,
                    LocalDateTime.now().minusDays(5));
            MvcResult s = perform(adminId, create(AnnouncementScopeType.TEAM, teamId, SURVEY, survey));
            MvcResult c = perform(adminId, create(AnnouncementScopeType.TEAM, teamId, CIRCULATION, circulation));
            assertThat(s.getResponse().getStatus()).isEqualTo(201);
            assertThat(c.getResponse().getStatus()).isEqualTo(201);
            Long surveyFeed = json(s).path("data").path("id").asLong();
            Long circulationFeed = json(c).path("data").path("id").asLong();

            Long live = source(SURVEY, "PUBLISHED", AnnouncementScopeType.TEAM, teamId, LocalDateTime.now().plusDays(3));
            MvcResult l = perform(adminId, create(AnnouncementScopeType.TEAM, teamId, SURVEY, live));
            assertThat(l.getResponse().getStatus()).isEqualTo(201);
            Long liveFeed = json(l).path("data").path("id").asLong();

            assertThat(listIds(authorId, teamList(50))).contains(liveFeed).doesNotContain(surveyFeed, circulationFeed);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 既存のお知らせ（元が非公開状態）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC5 既存のお知らせ: 元が非公開状態なら不在のお知らせと同じ扱い")
    class ExistingFeeds {

        @Test
        @DisplayName("AC5: チーム一覧・未読件数・ページングは公開状態の元のお知らせだけで数える")
        void teamListUnreadCountAndPaging() throws Exception {
            List<Long> published = publishedFeeds(AnnouncementScopeType.TEAM, teamId);
            Map<String, Long> hidden = hiddenFeeds(AnnouncementScopeType.TEAM, teamId);

            JsonNode page = body(authorId, teamList(50));
            assertThat(ids(page)).as("一覧は公開状態の元のお知らせだけ").containsExactlyInAnyOrderElementsOf(published);
            assertThat(page.path("meta").path("unreadCount").asLong()).as("未読件数に非公開元を数えない")
                    .isEqualTo(published.size());

            JsonNode first = body(authorId, teamList(published.size()));
            assertThat(ids(first)).as("1 ページ目は公開状態の元で埋まる（非公開元が枠を食わない）")
                    .containsExactlyInAnyOrderElementsOf(published);
            assertThat(first.path("meta").path("hasNext").asBoolean()).as("非公開元のために次ページを出さない").isFalse();

            List<Long> collected = new ArrayList<>();
            Long cursor = null;
            for (int i = 0; i < 20; i++) {
                MockHttpServletRequestBuilder req = teamList(1);
                if (cursor != null) {
                    req.param("cursor", cursor.toString());
                }
                JsonNode p = body(authorId, req);
                collected.addAll(ids(p));
                if (!p.path("meta").path("hasNext").asBoolean()) {
                    break;
                }
                cursor = p.path("meta").path("nextCursor").asLong();
            }
            assertThat(collected).as("ページを辿っても非公開元のお知らせは出ない")
                    .containsExactlyInAnyOrderElementsOf(published).doesNotContainAnyElementsOf(hidden.values());
        }

        @Test
        @DisplayName("AC5: 組織一覧・個人横断（/announcements/me）にも非公開元のお知らせは出ない")
        void orgAndPersonalList() throws Exception {
            List<Long> orgPublished = publishedFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
            Map<String, Long> orgHidden = hiddenFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
            List<Long> teamPublished = publishedFeeds(AnnouncementScopeType.TEAM, teamId);
            Map<String, Long> teamHidden = hiddenFeeds(AnnouncementScopeType.TEAM, teamId);

            JsonNode org = body(authorId, get("/api/v1/organizations/{o}/announcements", orgId).param("limit", "50"));
            assertThat(ids(org)).containsExactlyInAnyOrderElementsOf(orgPublished);
            assertThat(org.path("meta").path("unreadCount").asLong()).isEqualTo(orgPublished.size());

            List<Long> personal = ids(body(authorId,
                    get("/api/v1/announcements/me").param("limit", "50").param("include_read", "true")));
            assertThat(personal).containsAll(orgPublished).containsAll(teamPublished)
                    .doesNotContainAnyElementsOf(orgHidden.values()).doesNotContainAnyElementsOf(teamHidden.values());
        }

        @Test
        @DisplayName("AC5/AC6: チーム単件既読（著者・非著者 MEMBER）: 非公開元のお知らせは不在 ID と同一・既読行を作らない")
        void teamSingleRead() throws Exception {
            Map<String, Long> hidden = hiddenFeeds(AnnouncementScopeType.TEAM, teamId);
            for (Long actor : List.of(authorId, nonAuthorId)) {
                for (Map.Entry<String, Long> e : hidden.entrySet()) {
                    assertSameAsMissing(e.getKey(), actor,
                            post("/api/v1/teams/{t}/announcements/{id}/read", teamId, e.getValue()),
                            post("/api/v1/teams/{t}/announcements/{id}/read", teamId, MISSING_ID));
                    assertThat(countRead(e.getValue(), actor)).as(e.getKey() + ": 既読行を作らない").isZero();
                }
            }
        }

        @Test
        @DisplayName("AC5: 組織 read-batch: 非公開元・重複 ID は黙って除外し、公開状態の元だけ既読にする")
        void orgReadBatch() throws Exception {
            List<Long> published = publishedFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
            Map<String, Long> hidden = hiddenFeeds(AnnouncementScopeType.ORGANIZATION, orgId);
            List<Long> request = new ArrayList<>(published);
            request.addAll(hidden.values());
            request.addAll(published);
            request.addAll(hidden.values());

            MvcResult result = perform(authorId, post("/api/v1/organizations/{o}/announcements/read-batch", orgId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("feedIds", request))));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(result).path("data").path("markedCount").asInt()).isEqualTo(published.size());
            for (Long id : published) {
                assertThat(countRead(id, authorId)).isEqualTo(1);
            }
            hidden.forEach((label, id) -> assertThat(countRead(id, authorId)).as(label + ": 既読行を作らない").isZero());
        }

        @Test
        @DisplayName("AC5: チーム read-all: 非公開元のお知らせは既読化の対象外")
        void teamReadAll() throws Exception {
            List<Long> published = publishedFeeds(AnnouncementScopeType.TEAM, teamId);
            Map<String, Long> hidden = hiddenFeeds(AnnouncementScopeType.TEAM, teamId);

            MvcResult result = perform(nonAuthorId, post("/api/v1/teams/{t}/announcements/read-all", teamId));
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(result).path("data").path("markedCount").asInt()).isEqualTo(published.size());
            hidden.forEach((label, id) -> assertThat(countRead(id, nonAuthorId)).as(label + ": 既読行を作らない").isZero());
        }

        @Test
        @DisplayName("AC5/AC6: ピン留め（チーム・組織 ADMIN）: 公開状態の元は 200、非公開元は不在 ID と同一（タイトルを返さない）")
        void pin() throws Exception {
            Long control = publishedFeeds(AnnouncementScopeType.TEAM, teamId).get(0);
            assertThat(perform(adminId, teamPin(control)).getResponse().getStatus()).as("公開状態の元は 200").isEqualTo(200);

            for (Map.Entry<String, Long> e : hiddenFeeds(AnnouncementScopeType.TEAM, teamId).entrySet()) {
                assertSameAsMissing("TEAM " + e.getKey(), adminId, teamPin(e.getValue()), teamPin(MISSING_ID));
                assertThat(isPinned(e.getValue())).as(e.getKey() + ": ピン留めされない").isFalse();
            }
            for (Map.Entry<String, Long> e : hiddenFeeds(AnnouncementScopeType.ORGANIZATION, orgId).entrySet()) {
                assertSameAsMissing("ORGANIZATION " + e.getKey(), adminId, orgPin(e.getValue()), orgPin(MISSING_ID));
                assertThat(isPinned(e.getValue())).as(e.getKey() + ": ピン留めされない").isFalse();
            }
        }

        @Test
        @DisplayName("AC5: ピン解除（ADMIN）: ピン留め済みのお知らせの元を後から下書きに戻したら不在 ID と同一・ピン状態は変わらない")
        void unpinAfterRevert() throws Exception {
            Long surveyFeed = feed(AnnouncementScopeType.TEAM, teamId, SURVEY,
                    source(SURVEY, "PUBLISHED", AnnouncementScopeType.TEAM, teamId, null));
            Long circulationFeed = feed(AnnouncementScopeType.TEAM, teamId, CIRCULATION,
                    source(CIRCULATION, "ACTIVE", AnnouncementScopeType.TEAM, teamId, null));
            for (Long id : List.of(surveyFeed, circulationFeed)) {
                em.createNativeQuery("UPDATE announcement_feeds SET is_pinned = 1 WHERE id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            revertToDraft(SURVEY, sourceIdOf(surveyFeed));
            revertToDraft(CIRCULATION, sourceIdOf(circulationFeed));

            for (Long id : List.of(surveyFeed, circulationFeed)) {
                assertSameAsMissing("pinned " + id, adminId, teamPin(id), teamPin(MISSING_ID));
                assertThat(isPinned(id)).as("拒否された要求でピンは外れない").isTrue();
            }
        }

        @Test
        @DisplayName("AC5: お知らせ解除（DELETE、チーム・組織 ADMIN）: 非公開元のお知らせは不在 ID と同一・行は残る")
        void deleteAnnouncement() throws Exception {
            for (Map.Entry<String, Long> e : hiddenFeeds(AnnouncementScopeType.TEAM, teamId).entrySet()) {
                assertSameAsMissing("TEAM " + e.getKey(), adminId,
                        delete("/api/v1/teams/{t}/announcements/{id}", teamId, e.getValue()),
                        delete("/api/v1/teams/{t}/announcements/{id}", teamId, MISSING_ID));
                assertThat(feedExists(e.getValue())).isTrue();
            }
            for (Map.Entry<String, Long> e : hiddenFeeds(AnnouncementScopeType.ORGANIZATION, orgId).entrySet()) {
                assertSameAsMissing("ORGANIZATION " + e.getKey(), adminId,
                        delete("/api/v1/organizations/{o}/announcements/{id}", orgId, e.getValue()),
                        delete("/api/v1/organizations/{o}/announcements/{id}", orgId, MISSING_ID));
                assertThat(feedExists(e.getValue())).isTrue();
            }
        }

        @Test
        @DisplayName("AC5: インボックスの可視判定・F00 Resolver: 公開状態の元だけ可視、非公開元は不可視")
        void inboxAndResolver() {
            List<Long> published = publishedFeeds(AnnouncementScopeType.TEAM, teamId);
            Map<String, Long> hidden = hiddenFeeds(AnnouncementScopeType.TEAM, teamId);
            em.flush();
            for (Long id : published) {
                assertThat(inboxAdapter.isVisibleTo(authorId, id)).isTrue();
                assertThat(visibilityResolver.canView(id, authorId)).isTrue();
            }
            hidden.forEach((label, id) -> {
                assertThat(inboxAdapter.isVisibleTo(authorId, id)).as(label + ": インボックス不可視").isFalse();
                assertThat(visibilityResolver.canView(id, authorId)).as(label + ": Resolver 閲覧不可").isFalse();
            });
            List<Long> all = new ArrayList<>(published);
            all.addAll(hidden.values());
            assertThat(visibilityResolver.filterAccessible(all, authorId)).containsExactlyInAnyOrderElementsOf(published);
        }

        private MockHttpServletRequestBuilder teamPin(Long feedId) {
            return patch("/api/v1/teams/{t}/announcements/{id}/pin", teamId, feedId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}");
        }

        private MockHttpServletRequestBuilder orgPin(Long feedId) {
            return patch("/api/v1/organizations/{o}/announcements/{id}/pin", orgId, feedId)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true}");
        }

        private void assertSameAsMissing(String label, Long actorId, MockHttpServletRequestBuilder actual,
                                         MockHttpServletRequestBuilder missing) throws Exception {
            MvcResult missingResult = perform(actorId, missing);
            assertThat(missingResult.getResponse().getStatus()).isEqualTo(404);
            assertThat(code(missingResult)).isEqualTo("ANNOUNCE_001");

            MvcResult actualResult = perform(actorId, actual);
            assertThat(actualResult.getResponse().getStatus())
                    .as(label + ": status は不在 ID と同一であること（存在オラクル）")
                    .isEqualTo(missingResult.getResponse().getStatus());
            assertThat(json(actualResult))
                    .as(label + ": 本文（error.code・message 含む）は不在 ID と同一であること（タイトル・抜粋を返さない）")
                    .isEqualTo(json(missingResult));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    /** 公開状態の元（アンケート PUBLISHED / CLOSED、回覧板 ACTIVE / COMPLETED）のお知らせ 4 件（古い順に作る）。 */
    private List<Long> publishedFeeds(AnnouncementScopeType scope, Long scopeId) {
        List<Long> ids = new ArrayList<>();
        ids.add(feed(scope, scopeId, SURVEY, source(SURVEY, "PUBLISHED", scope, scopeId, null)));
        ids.add(feed(scope, scopeId, SURVEY, source(SURVEY, "CLOSED", scope, scopeId, null)));
        ids.add(feed(scope, scopeId, CIRCULATION, source(CIRCULATION, "ACTIVE", scope, scopeId, null)));
        ids.add(feed(scope, scopeId, CIRCULATION, source(CIRCULATION, "COMPLETED", scope, scopeId, null)));
        return ids;
    }

    /**
     * 元が非公開状態のお知らせ（公開状態のものより後に作る＝新しい）。最初から下書き・アーカイブ・取消・論理削除・
     * 孤児・公開後に下書きへ戻したもの。
     */
    private Map<String, Long> hiddenFeeds(AnnouncementScopeType scope, Long scopeId) {
        Map<String, Long> feeds = new LinkedHashMap<>();
        for (String state : List.of("DRAFT", "ARCHIVED", "DELETED")) {
            feeds.put("SURVEY " + state, feed(scope, scopeId, SURVEY, source(SURVEY, state, scope, scopeId, null)));
        }
        for (String state : List.of("DRAFT", "CANCELLED", "DELETED")) {
            feeds.put("CIRCULATION " + state,
                    feed(scope, scopeId, CIRCULATION, source(CIRCULATION, state, scope, scopeId, null)));
        }
        feeds.put("SURVEY 孤児", feed(scope, scopeId, SURVEY, MISSING_ID - 7));
        feeds.put("CIRCULATION 孤児", feed(scope, scopeId, CIRCULATION, MISSING_ID - 7));

        Long revertedSurvey = source(SURVEY, "PUBLISHED", scope, scopeId, null);
        feeds.put("SURVEY 公開後に下書きへ戻した", feed(scope, scopeId, SURVEY, revertedSurvey));
        revertToDraft(SURVEY, revertedSurvey);
        Long revertedCirculation = source(CIRCULATION, "ACTIVE", scope, scopeId, null);
        feeds.put("CIRCULATION 公開後に下書きへ戻した", feed(scope, scopeId, CIRCULATION, revertedCirculation));
        revertToDraft(CIRCULATION, revertedCirculation);
        return feeds;
    }

    /**
     * 元コンテンツを作り ID を返す。{@code state} はステータス名、または {@code DELETED}（公開状態で作って論理削除）。
     *
     * @param expiry アンケートは expires_at、回覧板は due_date（日付部分）。null で期限なし
     */
    private Long source(String sourceType, String state, AnnouncementScopeType scope, Long scopeId,
                        LocalDateTime expiry) {
        boolean deleted = "DELETED".equals(state);
        if (SURVEY.equals(sourceType)) {
            SurveyStatus status = deleted ? SurveyStatus.PUBLISHED : SurveyStatus.valueOf(state);
            SurveyEntity survey = SurveyEntity.builder().scopeType(scope.name()).scopeId(scopeId)
                    .title("CMP1300 秘匿アンケート " + key).description("秘匿説明").status(status)
                    .createdBy(authorId).expiresAt(expiry)
                    .publishedAt(status == SurveyStatus.DRAFT ? null : LocalDateTime.now().minusDays(1)).build();
            em.persist(survey);
            em.flush();
            if (deleted) {
                survey.softDelete();
                em.flush();
            }
            return survey.getId();
        }
        CirculationStatus status = deleted ? CirculationStatus.ACTIVE : CirculationStatus.valueOf(state);
        LocalDate dueDate = expiry == null ? null : expiry.toLocalDate();
        CirculationDocumentEntity doc = CirculationDocumentEntity.builder().scopeType(scope.name()).scopeId(scopeId)
                .createdBy(authorId).title("CMP1300 秘匿回覧 " + key).body("秘匿本文").status(status)
                .dueDate(dueDate).build();
        em.persist(doc);
        em.flush();
        if (deleted) {
            doc.softDelete();
            em.flush();
        }
        return doc.getId();
    }

    private void revertToDraft(String sourceType, Long sourceId) {
        em.flush();
        String table = SURVEY.equals(sourceType) ? "surveys" : "circulation_documents";
        em.createNativeQuery("UPDATE " + table + " SET status = 'DRAFT' WHERE id = :id")
                .setParameter("id", sourceId).executeUpdate();
    }

    private Long feed(AnnouncementScopeType scopeType, Long scopeId, String sourceType, Long sourceId) {
        AnnouncementFeedEntity feed = feedRepository.saveAndFlush(AnnouncementFeedEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).sourceType(AnnouncementSourceType.valueOf(sourceType))
                .sourceId(sourceId).authorId(authorId).titleCache("CMP1300 秘匿タイトル " + key)
                .excerptCache("CMP1300 秘匿抜粋").visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE).build());
        return feed.getId();
    }

    private Long sourceIdOf(Long feedId) {
        return ((Number) em.createNativeQuery("SELECT source_id FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult()).longValue();
    }

    private Long actorId(Actor actor) {
        return switch (actor) {
            case ADMIN -> adminId;
            case AUTHOR -> authorId;
            case NON_AUTHOR -> nonAuthorId;
            case OTHER_TENANT -> otherTenantId;
        };
    }

    private Long ownScopeId(AnnouncementScopeType scope) {
        return scope == AnnouncementScopeType.TEAM ? teamId : orgId;
    }

    /** 他テナントは自分の所属スコープ（他チーム・他組織）のパスから、元のスコープ外の sourceId を指す。 */
    private Long pathScopeId(Actor actor, AnnouncementScopeType scope) {
        if (actor == Actor.OTHER_TENANT) {
            return scope == AnnouncementScopeType.TEAM ? otherTeamId : otherOrgId;
        }
        return ownScopeId(scope);
    }

    private MockHttpServletRequestBuilder create(AnnouncementScopeType scope, Long scopeId, String sourceType,
                                                 Long sourceId) {
        String path = scope == AnnouncementScopeType.TEAM
                ? "/api/v1/teams/{s}/announcements" : "/api/v1/organizations/{s}/announcements";
        return post(path, scopeId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"%s\",\"sourceId\":%d}".formatted(sourceType, sourceId));
    }

    private MockHttpServletRequestBuilder teamList(int limit) {
        return get("/api/v1/teams/{t}/announcements", teamId).param("limit", String.valueOf(limit));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MvcResult perform(Long actorId, MockHttpServletRequestBuilder request) throws Exception {
        em.flush();
        em.clear();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actorId.toString(), null, List.of()));
        request.header("Accept-Language", "ja");
        MvcResult result = mockMvc.perform(request).andReturn();
        em.flush();
        return result;
    }

    private JsonNode body(Long actorId, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = perform(actorId, request);
        assertThat(result.getResponse().getStatus()).as("一覧は 200").isEqualTo(200);
        return json(result);
    }

    private List<Long> listIds(Long actorId, MockHttpServletRequestBuilder request) throws Exception {
        return ids(body(actorId, request));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("data").forEach(n -> ids.add(n.path("id").asLong()));
        return ids;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String code(MvcResult result) throws Exception {
        return json(result).path("error").path("code").asText();
    }

    private long feedCount(String sourceType, Long sourceId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_feeds "
                        + "WHERE source_type = :t AND source_id = :s")
                .setParameter("t", sourceType).setParameter("s", sourceId).getSingleResult()).longValue();
    }

    private boolean feedExists(Long feedId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult()).longValue() == 1;
    }

    private long countRead(Long feedId, Long userId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM announcement_read_status "
                        + "WHERE announcement_feed_id = :f AND user_id = :u")
                .setParameter("f", feedId).setParameter("u", userId).getSingleResult()).longValue();
    }

    private boolean isPinned(Long feedId) {
        Object value = em.createNativeQuery("SELECT is_pinned FROM announcement_feeds WHERE id = :id")
                .setParameter("id", feedId).getSingleResult();
        return value instanceof Boolean b ? b : ((Number) value).intValue() == 1;
    }

    private Long insertTeam(String label) {
        String name = "CMP1300 チーム" + label + " " + key;
        em.createNativeQuery("INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                        + "created_at, updated_at) VALUES (:n, 'PUBLIC', 1, 0, 0, :s, NOW(), NOW())")
                .setParameter("n", name).setParameter("s", "c1300" + label + "-" + key).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private Long insertOrganization(String label) {
        String name = "CMP1300 組織" + label + " " + key;
        em.createNativeQuery("INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                        + "supporter_enabled, version, slug, created_at, updated_at) "
                        + "VALUES (:n, 'OTHER', 'PUBLIC', 'NONE', 1, 0, :s, NOW(), NOW())")
                .setParameter("n", name).setParameter("s", "c1300o" + label + "-" + key).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :n")
                .setParameter("n", name).getSingleResult()).longValue();
    }

    private Long insertUser(String label) {
        String email = "c1300-" + label + "-" + key + "@example.com";
        em.createNativeQuery("INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, online_visibility, "
                        + "dm_receive_from, encryption_key_version, locale, timezone, reporting_restricted, "
                        + "follow_list_visibility, care_notification_enabled, offline_only, created_at, updated_at) "
                        + "VALUES (:e, 'C1300', 'テスト', 'C1300 テスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("e", email).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :e")
                .setParameter("e", email).getSingleResult()).longValue();
    }
}
