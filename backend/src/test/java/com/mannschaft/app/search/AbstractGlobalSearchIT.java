package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * グローバル検索（F04.6 作り直し・CMP-261116-0722）の API 契約 IT の共通土台。
 *
 * <p><b>起動方式</b>（TEST_CONVENTION.md §3.1.2）: {@link AbstractMySqlIntegrationTest} の MOCK +
 * {@code @AutoConfigureMockMvc}（{@code addFilters=false} を付けない＝実 JWT・実 Security フィルタを通す）。
 * 業務 Bean・DB・認可はモックしない。外部 Valkey（Redis）だけ基底の Mock を継承する。</p>
 *
 * <p><b>テスト全体を {@code @Transactional} で包まない理由</b>: 検索は種別ごとの
 * {@link SearchContributor} を別スレッドで並列実行する（設計書 §6）。未 commit の fixture は
 * 別スレッドから見えないため、fixture は {@link #inTx} で commit し、{@link #cleanUp} で
 * 本クラスが作成した行だけを ID で絞って物理削除する。</p>
 *
 * <p><b>キーワード</b>: 他テストの残存データと交差しないよう、テストごとに乱数の英小文字キーワード
 * {@link #kw} を発行し、fixture の名前に埋め込む。</p>
 */
@AutoConfigureMockMvc
abstract class AbstractGlobalSearchIT extends AbstractMySqlIntegrationTest {

    protected static final String SEARCH = "/api/v1/search";

    /** 応答の {@code typeCounts} が必ず持つ 9 種別のキー（設計書 §3）。 */
    protected static final List<String> ALL_TYPES = List.of(
            "POST", "MESSAGE", "THREAD", "ARTICLE", "FILE", "USER", "TEAM", "ORGANIZATION", "ACTIVITY");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected AuthTokenService tokens;
    @Autowired protected PlatformTransactionManager txManager;
    @Autowired protected UserRepository users;
    @Autowired protected TeamRepository teams;
    @Autowired protected OrganizationRepository organizations;
    @PersistenceContext protected EntityManager em;

    /** テストごとの一意キーワード（英小文字 10 文字）。 */
    protected String kw;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdTeamIds = new ArrayList<>();
    private final List<Long> createdOrgIds = new ArrayList<>();

    @BeforeEach
    void issueKeyword() {
        StringBuilder sb = new StringBuilder("gs");
        for (int i = 0; i < 8; i++) {
            sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
        }
        kw = sb.toString();
    }

    @AfterEach
    void cleanUp() {
        inTx(() -> {
            for (Long userId : createdUserIds) {
                for (String sql : List.of(
                        "DELETE FROM user_roles WHERE user_id = :id",
                        "DELETE FROM memberships WHERE user_id = :id",
                        "DELETE FROM search_histories WHERE user_id = :id",
                        "DELETE FROM search_saved_queries WHERE user_id = :id")) {
                    em.createNativeQuery(sql).setParameter("id", userId).executeUpdate();
                }
            }
            for (Long teamId : createdTeamIds) {
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM user_roles WHERE team_id = :id")
                        .setParameter("id", teamId).executeUpdate();
                em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", teamId).executeUpdate();
            }
            for (Long orgId : createdOrgIds) {
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'ORGANIZATION' AND scope_id = :id")
                        .setParameter("id", orgId).executeUpdate();
                em.createNativeQuery("DELETE FROM user_roles WHERE organization_id = :id")
                        .setParameter("id", orgId).executeUpdate();
                em.createNativeQuery("DELETE FROM organizations WHERE id = :id")
                        .setParameter("id", orgId).executeUpdate();
            }
            for (Long userId : createdUserIds) {
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", userId).executeUpdate();
            }
            return null;
        });
        createdUserIds.clear();
        createdTeamIds.clear();
        createdOrgIds.clear();
    }

    // ------------------------------------------------------------------
    // fixture（すべて commit する）
    // ------------------------------------------------------------------

    protected <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    /** 検索可能（is_searchable=true）で ACTIVE なユーザー。 */
    protected Long user(String displayName) {
        return user(displayName, null, true);
    }

    protected Long user(String displayName, String nickname2, boolean searchable) {
        return user(displayName, nickname2, searchable, "試練");
    }

    /** 氏名（暗号化列）を指定してユーザーを作る。 */
    protected Long user(String displayName, String nickname2, boolean searchable, String lastName) {
        Long id = inTx(() -> users.save(UserEntity.builder()
                .email("global-search-" + UUID.randomUUID() + "@example.com")
                .lastName(lastName).firstName("検索").displayName(displayName).nickname2(nickname2)
                .isSearchable(searchable).status(UserEntity.UserStatus.ACTIVE)
                .locale("ja").timezone("Asia/Tokyo").build()).getId());
        createdUserIds.add(id);
        return id;
    }

    /** 検索に引っかからない名前の閲覧者。 */
    protected Long viewer(String label) {
        return user("閲覧者-" + label + "-" + UUID.randomUUID().toString().substring(0, 6), null, false);
    }

    protected Long publicTeam(String name) {
        return team(name, TeamEntity.Visibility.PUBLIC);
    }

    /** 所属者限定（非公開）チーム。 */
    protected Long privateTeam(String name) {
        return team(name, TeamEntity.Visibility.MEMBERS_AND_ABOVE);
    }

    protected Long team(String name, TeamEntity.Visibility visibility) {
        Long id = inTx(() -> teams.save(TeamEntity.builder()
                .slug("gs-" + UUID.randomUUID().toString().substring(0, 12))
                .name(name).visibility(visibility).supporterEnabled(false).build()).getId());
        createdTeamIds.add(id);
        return id;
    }

    protected Long publicOrg(String name) {
        return org(name, OrganizationEntity.Visibility.PUBLIC);
    }

    protected Long privateOrg(String name) {
        return org(name, OrganizationEntity.Visibility.PRIVATE);
    }

    protected Long org(String name, OrganizationEntity.Visibility visibility) {
        Long id = inTx(() -> organizations.save(OrganizationEntity.builder()
                .slug("gs-" + UUID.randomUUID().toString().substring(0, 12))
                .name(name).orgType(OrganizationEntity.OrgType.OTHER).visibility(visibility)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false).build()).getId());
        createdOrgIds.add(id);
        return id;
    }

    protected void teamMember(Long userId, Long teamId) {
        inTx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            return null;
        });
    }

    protected void orgMember(Long userId, Long orgId) {
        inTx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            return null;
        });
    }

    /** チームの GUEST ロールだけを付ける（memberships 行は作らない）。 */
    protected void teamGuest(Long userId, Long teamId) {
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, userId, "GUEST", teamId, null);
            return null;
        });
    }

    /** 組織の GUEST ロールだけを付ける（memberships 行は作らない）。 */
    protected void orgGuest(Long userId, Long orgId) {
        inTx(() -> {
            MembershipTestHelper.insertUserRole(em, userId, "GUEST", null, orgId);
            return null;
        });
    }

    protected void archiveTeam(Long teamId) {
        nativeUpdate("UPDATE teams SET archived_at = NOW() WHERE id = :id", teamId);
    }

    protected void softDeleteTeam(Long teamId) {
        nativeUpdate("UPDATE teams SET deleted_at = NOW() WHERE id = :id", teamId);
    }

    protected void provisionTeam(Long teamId) {
        nativeUpdate("UPDATE teams SET lifecycle_status = 'PROVISIONED' WHERE id = :id", teamId);
    }

    protected void archiveOrg(Long orgId) {
        nativeUpdate("UPDATE organizations SET archived_at = NOW() WHERE id = :id", orgId);
    }

    protected void softDeleteOrg(Long orgId) {
        nativeUpdate("UPDATE organizations SET deleted_at = NOW() WHERE id = :id", orgId);
    }

    protected void nativeUpdate(String sql, Long id) {
        inTx(() -> em.createNativeQuery(sql).setParameter("id", id).executeUpdate());
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    protected ResultActions http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mockMvc.perform(request);
    }

    /** {@code GET /api/v1/search} を actor として呼び、200 を確認して本文全体を返す。 */
    protected JsonNode search(Long actor, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get(SEARCH);
        params.forEach(request::param);
        String body = http(request, actor).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    protected JsonNode search(Long actor, String q) throws Exception {
        return search(actor, Map.of("q", q));
    }

    protected JsonNode search(Long actor, String q, String type) throws Exception {
        return search(actor, Map.of("q", q, "type", type));
    }

    /** {@code data.typeCounts[type]}。キーが無ければ失敗させる。 */
    protected long typeCount(JsonNode body, String type) {
        JsonNode counts = body.path("data").path("typeCounts");
        assertThat(counts.has(type)).as("typeCounts に %s のキーがあること（応答: %s）", type, body).isTrue();
        return counts.get(type).asLong();
    }

    /** {@code data.results} のうち指定種別の id 一覧。 */
    protected List<Long> resultIds(JsonNode body, String type) {
        JsonNode results = body.path("data").path("results");
        assertThat(results.isArray()).as("data.results が配列であること（応答: %s）", body).isTrue();
        List<Long> ids = new ArrayList<>();
        for (JsonNode r : results) {
            if (type.equals(r.path("type").asText())) {
                ids.add(r.path("id").asLong());
            }
        }
        return ids;
    }

    /** {@code data.results} の type 値一覧（重複あり）。 */
    protected List<String> resultTypes(JsonNode body) {
        List<String> types = new ArrayList<>();
        for (JsonNode r : body.path("data").path("results")) {
            types.add(r.path("type").asText());
        }
        return types;
    }

    /** {@code data.typeCounts} の全キー。 */
    protected List<String> typeCountKeys(JsonNode body) {
        List<String> keys = new ArrayList<>();
        Iterator<String> it = body.path("data").path("typeCounts").fieldNames();
        it.forEachRemaining(keys::add);
        return keys;
    }

    /** 結果・件数の両方が 0 であること（件数からの漏えいも封じる）。 */
    protected void assertNothingVisible(JsonNode body, String type) {
        assertThat(resultIds(body, type)).as("%s の結果に出ないこと", type).isEmpty();
        assertThat(typeCount(body, type)).as("%s の typeCounts に数えられないこと", type).isZero();
    }

    /** 結果に id が出て、件数にも数えられること（対照ケース）。 */
    protected void assertVisible(JsonNode body, String type, Long id, long expectedCount) {
        assertThat(resultIds(body, type)).as("%s の結果に id=%s が出ること", type, id).contains(id);
        assertThat(typeCount(body, type)).as("%s の typeCounts", type).isEqualTo(expectedCount);
    }

    protected static String upper(String s) {
        return s.toUpperCase(Locale.ROOT);
    }
}
