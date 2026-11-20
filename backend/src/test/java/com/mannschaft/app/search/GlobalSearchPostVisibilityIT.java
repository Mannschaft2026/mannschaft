package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.timeline.PostDeliveryScope;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * グローバル検索 POST（タイムライン投稿）種別の受け入れテスト（CMP-261116-0722 PR-2 試練・red 先行）。
 *
 * <p>最重要条件（マスター逐語）: 「くれぐれも他の人や別チーム、組織など権限範囲外のデータが取得されることだけは
 * 防いで」。各テストは「出ないこと」を<b>結果（results）と件数（typeCounts / meta.total）の両方</b>で確かめ、
 * 同じ fixture で「権限があれば出ること」（対照）も確かめて、締めすぎで検索の価値を潰していないことを固定する。</p>
 *
 * <p><b>可視性の正本</b>: {@code TimelinePostVisibilityAccessGuard#isVisible} と
 * {@code TimelinePostRepository#SEARCH_QUERY}（既存 {@code GET /timeline/search}）の scope 述語
 * （PUBLIC・所属 TEAM/ORG・配信祖先組織・本人 PERSONAL）。VILLAGE・FRIEND_* は対象外。状態は PUBLISHED のみ
 * （投稿者本人の DRAFT / SCHEDULED / HIDDEN も検索には出さない。timeline 設計書「フィード API・検索 API の
 * status = 'PUBLISHED' 条件は維持する」）。所属スコープは集約側 {@code SearchViewer}（GUEST のみ・削除済み・
 * アーカイブ済みを除外済み）を使う。</p>
 *
 * <p><b>返信の扱い（本試練で固定）</b>: 公開済み返信は検索に<b>出す</b>。根拠は timeline 設計書
 * （CMP-260929-1517 節「公開済み返信は独立した投稿として扱い…公開済み返信自身の既存の可視性…は変更しない」）、
 * 返信は親の scope_type / scope_id / delivery_scope を継承して作られること（{@code TimelinePostService#createPost}）、
 * 既存 {@code SEARCH_QUERY} が parent_id で絞っていないこと、および {@code TimelinePostStatusVisibilityContractIT}
 * が「非公開親の公開済み返信も詳細取得は 200」を固定していること。検索に出した返信は遷移先
 * {@code /timeline/{id}} で開ける（不可視の投稿を検索だけが出すことはない）。</p>
 *
 * <p><b>FULLTEXT</b>: テストプロファイルは ddl-auto で FULLTEXT を作らないため、本番 V4.001 と同じ
 * {@code ft_timeline_posts_content}（ngram）を補完する（{@code TimelineSearchScopeContractIT} と同じ作法）。
 * InnoDB の FULLTEXT はコミット済みの行だけを拾うため、fixture は基底の {@link #inTx} で commit する。
 * キーワードは ngram の既定ストップワード（a / i / in / of ...）を含まない子音だけで作り直す。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 POST 種別の認可境界（CMP-261116-0722 PR-2 試練）")
class GlobalSearchPostVisibilityIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";

    @Autowired private TimelinePostRepository posts;
    @Autowired private VillageRepository villages;
    @Autowired private VillageMembershipRepository villageMemberships;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdPostIds = new ArrayList<>();
    private final List<String> createdVillageSlugs = new ArrayList<>();
    private final List<Long> anchoredTeamIds = new ArrayList<>();

    @BeforeEach
    void prepare() {
        ensureFulltextIndex();
        StringBuilder sb = new StringBuilder("zq");
        for (int i = 0; i < 8; i++) {
            sb.append(CONSONANTS.charAt(ThreadLocalRandom.current().nextInt(CONSONANTS.length())));
        }
        kw = sb.toString();
    }

    @AfterEach
    void cleanUpPosts() {
        inTx(() -> {
            for (Long id : createdPostIds) {
                em.createNativeQuery("DELETE FROM timeline_posts WHERE id = :id").setParameter("id", id)
                        .executeUpdate();
            }
            for (Long teamId : anchoredTeamIds) {
                em.createNativeQuery("DELETE FROM team_org_memberships WHERE team_id = :id").setParameter("id", teamId)
                        .executeUpdate();
            }
            for (String slug : createdVillageSlugs) {
                em.createNativeQuery("DELETE FROM village_memberships WHERE village_id IN "
                        + "(SELECT id FROM villages WHERE slug = :slug)").setParameter("slug", slug).executeUpdate();
                em.createNativeQuery("DELETE FROM villages WHERE slug = :slug").setParameter("slug", slug)
                        .executeUpdate();
            }
            return null;
        });
        createdPostIds.clear();
        createdVillageSlugs.clear();
        anchoredTeamIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A1 / A2 / A7: スコープの境界
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ境界（A1 / A2 / A7）")
    class ScopeBoundary {

        @Test
        @DisplayName("AC-A1(POST): チーム X の投稿は X 非所属者の結果にも typeCounts.POST にも出ない（X の所属者には出る）")
        void AC_A1_POST_非所属チームの投稿は出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw);
            Long teamY = privateTeam("Y-" + kw);
            Long author = viewer("a1-author");
            teamMember(author, teamX);
            Long insider = viewer("a1-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1-outsider");
            teamMember(outsider, teamY);
            Long teamPost = post(PostScopeType.TEAM, teamX, author, kw + " チームX内の連絡");

            assertNothingVisible(search(outsider, kw), "POST");
            JsonNode outsiderDetail = search(outsider, kw, "POST");
            assertNothingVisible(outsiderDetail, "POST");
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), "POST", teamPost, 1);
        }

        @Test
        @DisplayName("AC-A1(POST): チームの SUPPORTER にも所属チームの投稿は出る（timeline は所属一致＝可視）")
        void AC_A1_POST_サポーターには出る() throws Exception {
            Long team = privateTeam("S-" + kw);
            Long author = viewer("sup-author");
            teamMember(author, team);
            Long supporter = viewer("supporter");
            inTx(() -> {
                MembershipTestHelper.insertMembership(em, supporter, ScopeType.TEAM, team, RoleKind.SUPPORTER);
                return null;
            });
            Long teamPost = post(PostScopeType.TEAM, team, author, kw + " サポーター向け");

            assertVisible(search(supporter, kw), "POST", teamPost, 1);
        }

        @Test
        @DisplayName("AC-A2(POST): 組織 O の投稿は O にも O の配下配信先にも所属しない別組織ユーザーに出ない（O 所属者には出る）")
        void AC_A2_POST_別組織の投稿は出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long orgP = privateOrg("P-" + kw);
            Long author = viewer("a2-author");
            orgMember(author, orgO);
            Long insider = viewer("a2-insider");
            orgMember(insider, orgO);
            Long outsider = viewer("a2-outsider");
            orgMember(outsider, orgP);
            Long direct = orgPost(orgO, PostDeliveryScope.DIRECT, author, kw + " 組織内 DIRECT");
            Long descendants = orgPost(orgO, PostDeliveryScope.DESCENDANTS, author, kw + " 組織内 DESCENDANTS");

            assertNothingVisible(search(outsider, kw), "POST");
            assertNothingVisible(search(outsider, kw, "POST"), "POST");

            JsonNode insiderBody = search(insider, kw, "POST");
            assertThat(resultIds(insiderBody, "POST")).containsExactlyInAnyOrder(direct, descendants);
            assertThat(typeCount(insiderBody, "POST")).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A2(POST): 配下配信は下向きのみ。子組織 C の投稿は親組織 O の所属者に出ない")
        void AC_A2_POST_子組織の投稿は親組織所属者に出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long childC = privateOrg("C-" + kw);
            setParent(childC, orgO);
            Long author = viewer("a2c-author");
            orgMember(author, childC);
            Long parentMember = viewer("a2c-parent");
            orgMember(parentMember, orgO);
            orgPost(childC, PostDeliveryScope.DESCENDANTS, author, kw + " 子組織の投稿");

            assertNothingVisible(search(parentMember, kw), "POST");
        }

        @Test
        @DisplayName("AC-A7(POST): PUBLIC 投稿は無所属ユーザーにも出る")
        void AC_A7_POST_PUBLICは無所属にも出る() throws Exception {
            Long author = viewer("a7-author");
            Long nobody = viewer("a7-nobody");
            Long publicPost = post(PostScopeType.PUBLIC, 0L, author, kw + " 全体公開");

            assertVisible(search(nobody, kw), "POST", publicPost, 1);
            JsonNode detail = search(nobody, kw, "POST");
            assertVisible(detail, "POST", publicPost, 1);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A7(POST): 組織 O の CHILDREN/DESCENDANTS 投稿は O にアンカーされたチームの所属者に出る。DIRECT は出ない")
        void AC_A7_POST_組織投稿は配下配信先チームの所属者に出る() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long teamT = privateTeam("T-" + kw);
            anchorTeam(teamT, orgO);
            Long author = viewer("a7o-author");
            orgMember(author, orgO);
            Long teamOnly = viewer("a7o-team");
            teamMember(teamOnly, teamT);
            Long children = orgPost(orgO, PostDeliveryScope.CHILDREN, author, kw + " 周知 CHILDREN");
            Long descendants = orgPost(orgO, PostDeliveryScope.DESCENDANTS, author, kw + " 周知 DESCENDANTS");
            Long direct = orgPost(orgO, PostDeliveryScope.DIRECT, author, kw + " 周知 DIRECT");

            JsonNode body = search(teamOnly, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactlyInAnyOrder(children, descendants);
            assertThat(resultIds(body, "POST")).doesNotContain(direct);
            assertThat(typeCount(body, "POST")).isEqualTo(2);
            assertThat(typeCount(search(teamOnly, kw), "POST")).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-A7(POST): 距離2（アンカー組織の親 G）には DESCENDANTS だけが届き、CHILDREN・DIRECT は届かない")
        void AC_A7_POST_距離2はDESCENDANTSのみ() throws Exception {
            Long orgG = privateOrg("G-" + kw);
            Long orgO = privateOrg("O-" + kw);
            setParent(orgO, orgG);
            Long teamT = privateTeam("T-" + kw);
            anchorTeam(teamT, orgO);
            Long author = viewer("a7g-author");
            orgMember(author, orgG);
            Long teamOnly = viewer("a7g-team");
            teamMember(teamOnly, teamT);
            Long descendants = orgPost(orgG, PostDeliveryScope.DESCENDANTS, author, kw + " 全国 DESCENDANTS");
            orgPost(orgG, PostDeliveryScope.CHILDREN, author, kw + " 全国 CHILDREN");
            orgPost(orgG, PostDeliveryScope.DIRECT, author, kw + " 全国 DIRECT");

            JsonNode body = search(teamOnly, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactly(descendants);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A7(POST): 子組織の直接所属者には親組織の CHILDREN 投稿が出て DIRECT は出ない")
        void AC_A7_POST_子組織所属者への配信() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long childC = privateOrg("C-" + kw);
            setParent(childC, orgO);
            Long author = viewer("a7c-author");
            orgMember(author, orgO);
            Long childMember = viewer("a7c-child");
            orgMember(childMember, childC);
            Long children = orgPost(orgO, PostDeliveryScope.CHILDREN, author, kw + " 支部へ CHILDREN");
            orgPost(orgO, PostDeliveryScope.DIRECT, author, kw + " 本部内 DIRECT");

            JsonNode body = search(childMember, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactly(children);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN でもバイパスしない: 非所属チームの投稿は出ない（PUBLIC は出る）")
        void SYSTEM_ADMINはバイパスしない() throws Exception {
            Long team = privateTeam("SA-" + kw);
            Long author = viewer("sa-author");
            teamMember(author, team);
            Long admin = viewer("sysadmin");
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
                return null;
            });
            Long teamPost = post(PostScopeType.TEAM, team, author, kw + " チーム内");
            Long publicPost = post(PostScopeType.PUBLIC, 0L, author, kw + " 全体");

            JsonNode body = search(admin, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactly(publicPost);
            assertThat(resultIds(body, "POST")).doesNotContain(teamPost);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("FRIEND_FORWARD は転送先チーム非所属者に出ない")
        void FRIEND_FORWARDは非所属者に出ない() throws Exception {
            Long team = privateTeam("FF-" + kw);
            Long author = viewer("ff-author");
            teamMember(author, team);
            Long outsider = viewer("ff-outsider");
            post(PostScopeType.FRIEND_FORWARD, team, author, kw + " 転送");

            assertNothingVisible(search(outsider, kw), "POST");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A3: スコープ指定の存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A3(POST): 非所属スコープ指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）。自スコープ指定ではそのスコープの投稿だけが出る")
    void AC_A3_POST_存在オラクルなし() throws Exception {
        Long foreign = privateTeam("F-" + kw);
        Long foreignAuthor = viewer("a3-foreign");
        teamMember(foreignAuthor, foreign);
        post(PostScopeType.TEAM, foreign, foreignAuthor, kw + " 他所の投稿");
        Long mine = privateTeam("M-" + kw);
        Long me = viewer("a3-me");
        teamMember(me, mine);
        Long minePost = post(PostScopeType.TEAM, mine, me, kw + " 自チームの投稿");
        Long publicPost = post(PostScopeType.PUBLIC, 0L, foreignAuthor, kw + " 全体公開");
        Long personal = post(PostScopeType.PERSONAL, me, me, kw + " 自分のメモ");

        JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
        JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));
        JsonNode foreignDetail = search(me, Map.of("q", kw, "type", "POST", "scopeType", "TEAM",
                "scopeId", foreign.toString()));
        JsonNode missingDetail = search(me, Map.of("q", kw, "type", "POST", "scopeType", "TEAM",
                "scopeId", "987654321987"));

        for (String t : ALL_TYPES) {
            assertThat(typeCount(foreignScope, t)).as("非所属スコープ %s", t).isZero();
        }
        assertThat(foreignScope.path("data").path("results")).isEmpty();
        assertThat(foreignScope.path("error").isMissingNode() || foreignScope.path("error").isNull()).isTrue();
        assertThat(foreignScope.path("data").path("typeCounts")).isEqualTo(missingScope.path("data").path("typeCounts"));
        assertThat(foreignScope.path("data").path("results")).isEqualTo(missingScope.path("data").path("results"));
        assertThat(foreignScope.path("data").path("timedOutTypes"))
                .isEqualTo(missingScope.path("data").path("timedOutTypes"));
        assertThat(foreignScope.path("meta")).isEqualTo(missingScope.path("meta"));
        assertThat(foreignScope.path("error")).isEqualTo(missingScope.path("error"));
        assertThat(foreignDetail.path("data")).isEqualTo(missingDetail.path("data"));
        assertThat(foreignDetail.path("meta")).isEqualTo(missingDetail.path("meta"));
        assertThat(foreignDetail.path("meta").path("total").asLong()).isZero();

        JsonNode ownScope = search(me, Map.of("q", kw, "type", "POST", "scopeType", "TEAM",
                "scopeId", mine.toString()));
        assertThat(resultIds(ownScope, "POST")).as("スコープ指定時はそのスコープの投稿だけ（PUBLIC・PERSONAL は含めない）")
                .containsExactly(minePost);
        assertThat(resultIds(ownScope, "POST")).doesNotContain(publicPost, personal);
        assertThat(typeCount(ownScope, "POST")).isEqualTo(1);
        assertThat(ownScope.path("meta").path("total").asLong()).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A4 / A5
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4(POST): GUEST ロールしか持たないスコープの投稿は出ない（MEMBER なら出る）")
    void AC_A4_POST_GUESTのみのスコープは母集団外() throws Exception {
        Long team = privateTeam("G-" + kw);
        Long org = privateOrg("GO-" + kw);
        Long author = viewer("a4-author");
        teamMember(author, team);
        orgMember(author, org);
        Long guest = viewer("a4-guest");
        teamGuest(guest, team);
        orgGuest(guest, org);
        Long member = viewer("a4-member");
        teamMember(member, team);
        Long teamPost = post(PostScopeType.TEAM, team, author, kw + " チームのお知らせ");
        orgPost(org, PostDeliveryScope.DIRECT, author, kw + " 組織のお知らせ");

        assertNothingVisible(search(guest, kw), "POST");
        assertNothingVisible(search(guest, kw, "POST"), "POST");
        assertVisible(search(member, kw), "POST", teamPost, 1);
    }

    @Nested
    @DisplayName("論理削除（A5）")
    class Deleted {

        @Test
        @DisplayName("AC-A5(POST): 論理削除済み投稿は出ない（status=DELETED でも deleted_at だけでも）")
        void AC_A5_POST_論理削除済み投稿は出ない() throws Exception {
            Long team = privateTeam("D-" + kw);
            Long me = viewer("a5-me");
            teamMember(me, team);
            Long alive = post(PostScopeType.TEAM, team, me, kw + " 生存");
            Long softDeleted = post(PostScopeType.TEAM, team, me, kw + " 削除済み");
            nativeUpdate("UPDATE timeline_posts SET deleted_at = NOW(), status = 'DELETED' WHERE id = :id", softDeleted);
            Long deletedAtOnly = post(PostScopeType.PUBLIC, 0L, me, kw + " 削除日時だけ");
            nativeUpdate("UPDATE timeline_posts SET deleted_at = NOW() WHERE id = :id", deletedAtOnly);

            JsonNode body = search(me, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactly(alive);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A5(POST): 論理削除済み・アーカイブ済みのチーム／組織の投稿は所属者にも出ない")
        void AC_A5_POST_削除済みスコープの投稿は出ない() throws Exception {
            Long deletedTeam = privateTeam("DT-" + kw);
            Long archivedTeam = privateTeam("AT-" + kw);
            Long deletedOrg = privateOrg("DO-" + kw);
            Long liveTeam = privateTeam("LT-" + kw);
            Long me = viewer("a5s-me");
            teamMember(me, deletedTeam);
            teamMember(me, archivedTeam);
            orgMember(me, deletedOrg);
            teamMember(me, liveTeam);
            post(PostScopeType.TEAM, deletedTeam, me, kw + " 削除チーム");
            post(PostScopeType.TEAM, archivedTeam, me, kw + " アーカイブチーム");
            orgPost(deletedOrg, PostDeliveryScope.DIRECT, me, kw + " 削除組織");
            Long live = post(PostScopeType.TEAM, liveTeam, me, kw + " 生存チーム");
            softDeleteTeam(deletedTeam);
            archiveTeam(archivedTeam);
            softDeleteOrg(deletedOrg);

            JsonNode body = search(me, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactly(live);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A5(POST): 論理削除済みの配信元組織（祖先）の配下配信投稿は配下の所属者に出ない")
        void AC_A5_POST_削除済み祖先組織の配信投稿は出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long childC = privateOrg("C-" + kw);
            setParent(childC, orgO);
            Long author = viewer("a5d-author");
            orgMember(author, orgO);
            Long childMember = viewer("a5d-child");
            orgMember(childMember, childC);
            orgPost(orgO, PostDeliveryScope.DESCENDANTS, author, kw + " 削除される本部の周知");
            softDeleteOrg(orgO);

            assertNothingVisible(search(childMember, kw), "POST");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-P1: PERSONAL / VILLAGE / 非 PUBLISHED / 返信
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("POST 固有（P1）")
    class PostSpecific {

        @Test
        @DisplayName("AC-P1: 他人の PERSONAL 投稿は出ず、本人の PERSONAL は本人に出る")
        void AC_P1_PERSONAL() throws Exception {
            Long owner = viewer("p1-owner");
            Long other = viewer("p1-other");
            Long personal = post(PostScopeType.PERSONAL, owner, owner, kw + " 個人メモ");

            assertNothingVisible(search(other, kw), "POST");
            assertVisible(search(owner, kw), "POST", personal, 1);
        }

        @Test
        @DisplayName("AC-P1: VILLAGE 投稿は村の現役メンバー本人にも出ない（検索対象外）")
        void AC_P1_VILLAGEは出ない() throws Exception {
            Long villager = viewer("p1-villager");
            UUID villageId = village(villager);
            Long villagePost = savePost(TimelinePostEntity.builder().scopeType(PostScopeType.VILLAGE).scopeId(0L)
                    .scopeVillageId(villageId).userId(villager).content(kw + " 村の話題")
                    .status(PostStatus.PUBLISHED).build());

            JsonNode body = search(villager, kw);
            assertThat(resultIds(body, "POST")).doesNotContain(villagePost);
            assertThat(typeCount(body, "POST")).isZero();
        }

        @ParameterizedTest(name = "{0} は他人にも投稿者本人にも出ない")
        @EnumSource(value = PostStatus.class, names = {"DRAFT", "SCHEDULED", "HIDDEN", "DELETED"})
        @DisplayName("AC-P1: 非 PUBLISHED（下書き・予約・非表示・削除）は他人にも投稿者本人にも出ない")
        void AC_P1_非PUBLISHEDは出ない(PostStatus state) throws Exception {
            Long team = privateTeam("NP-" + kw);
            Long owner = viewer("np-owner");
            teamMember(owner, team);
            Long peer = viewer("np-peer");
            teamMember(peer, team);
            savePost(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(team).userId(owner)
                    .content(kw + " 非公開状態 " + state).status(state)
                    .scheduledAt(state == PostStatus.SCHEDULED ? LocalDateTime.now().minusDays(1) : null).build());
            savePost(TimelinePostEntity.builder().scopeType(PostScopeType.PUBLIC).scopeId(0L).userId(owner)
                    .content(kw + " 公開スコープの非公開状態 " + state).status(state).build());
            savePost(TimelinePostEntity.builder().scopeType(PostScopeType.PERSONAL).scopeId(owner).userId(owner)
                    .content(kw + " 個人の非公開状態 " + state).status(state).build());

            assertNothingVisible(search(peer, kw), "POST");
            assertNothingVisible(search(owner, kw), "POST");
            assertNothingVisible(search(owner, kw, "POST"), "POST");
        }

        @Test
        @DisplayName("AC-P1 返信: 公開済み返信は親のスコープを継承した独立投稿として出す（所属者に出て、非所属者に出ない）")
        void AC_P1_返信はスコープに従って出る() throws Exception {
            Long team = privateTeam("R-" + kw);
            Long author = viewer("r-author");
            teamMember(author, team);
            Long member = viewer("r-member");
            teamMember(member, team);
            Long outsider = viewer("r-outsider");
            Long parent = post(PostScopeType.TEAM, team, author, "親投稿（キーワードなし）");
            Long reply = savePost(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(team)
                    .userId(member).parentId(parent).content(kw + " への返信").status(PostStatus.PUBLISHED).build());

            assertVisible(search(member, kw), "POST", reply, 1);
            JsonNode detail = search(member, kw, "POST");
            assertVisible(detail, "POST", reply, 1);
            assertThat(urlOf(detail, reply)).isEqualTo("/timeline/" + reply);
            assertNothingVisible(search(outsider, kw), "POST");
        }

        @Test
        @DisplayName("AC-P1 返信: 非公開（HIDDEN）親の公開済み返信は詳細取得と同じく所属者に出る（独立性の維持）")
        void AC_P1_非公開親の公開済み返信() throws Exception {
            Long team = privateTeam("RH-" + kw);
            Long author = viewer("rh-author");
            teamMember(author, team);
            Long member = viewer("rh-member");
            teamMember(member, team);
            Long parent = savePost(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(team)
                    .userId(author).content(kw + " 非表示になった親").status(PostStatus.HIDDEN).build());
            Long reply = savePost(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(team)
                    .userId(member).parentId(parent).content(kw + " 公開済み返信").status(PostStatus.PUBLISHED).build());

            JsonNode body = search(member, kw, "POST");
            assertThat(resultIds(body, "POST")).containsExactly(reply);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("authorId 指定: その投稿者の可視投稿だけが出て、不可視の投稿は authorId 指定でも出ない")
        void authorIdでも不可視は出ない() throws Exception {
            Long hidden = privateTeam("AU-" + kw);
            Long author = viewer("au-author");
            teamMember(author, hidden);
            Long other = viewer("au-other");
            Long me = viewer("au-me");
            Long publicByAuthor = post(PostScopeType.PUBLIC, 0L, author, kw + " 著者の公開投稿");
            post(PostScopeType.TEAM, hidden, author, kw + " 著者のチーム投稿");
            post(PostScopeType.PUBLIC, 0L, other, kw + " 別人の公開投稿");

            JsonNode body = search(me, Map.of("q", kw, "type", "POST", "authorId", author.toString()));
            assertThat(resultIds(body, "POST")).containsExactly(publicByAuthor);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A6 / AC-C8: 件数と結果の同一述語・頭打ちなし
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("件数（A6 / C8）")
    class Counts {

        @Test
        @DisplayName("AC-A6(POST): 不可視投稿を足しても typeCounts.POST は不変。詳細ビューの meta.total と typeCounts.POST が一致")
        void AC_A6_POST_不可視を足しても件数不変() throws Exception {
            Long mine = privateTeam("A6M-" + kw);
            Long foreign = privateTeam("A6F-" + kw);
            Long me = viewer("a6-me");
            teamMember(me, mine);
            Long stranger = viewer("a6-stranger");
            teamMember(stranger, foreign);
            post(PostScopeType.TEAM, mine, me, kw + " 1");
            post(PostScopeType.PUBLIC, 0L, stranger, kw + " 2");

            JsonNode before = search(me, kw);
            assertThat(typeCount(before, "POST")).isEqualTo(2);

            post(PostScopeType.TEAM, foreign, stranger, kw + " 他チーム");
            post(PostScopeType.PERSONAL, stranger, stranger, kw + " 他人の個人");
            savePost(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM).scopeId(mine).userId(me)
                    .content(kw + " 自分の下書き").status(PostStatus.DRAFT).build());

            JsonNode after = search(me, kw);
            JsonNode detail = search(me, kw, "POST");
            assertThat(typeCount(after, "POST")).isEqualTo(typeCount(before, "POST"));
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(typeCount(detail, "POST"));
            assertThat(typeCount(detail, "POST")).isEqualTo(typeCount(after, "POST"));
            assertThat(resultIds(detail, "POST")).hasSize(2);
        }

        @Test
        @DisplayName("AC-C8(POST): 可視11件・不可視3件 → typeCounts.POST=11（頭打ちしない）。概要は5件、詳細のページ送りで11件を過不足なく返す")
        void AC_C8_POST_真の件数とページ送り() throws Exception {
            Long mine = privateTeam("C8M-" + kw);
            Long foreign = privateTeam("C8F-" + kw);
            Long me = viewer("c8-me");
            teamMember(me, mine);
            Long stranger = viewer("c8-stranger");
            teamMember(stranger, foreign);
            Set<Long> visible = new HashSet<>();
            for (int i = 0; i < 5; i++) {
                visible.add(post(PostScopeType.TEAM, mine, me, kw + " チーム" + i));
            }
            for (int i = 0; i < 4; i++) {
                visible.add(post(PostScopeType.PUBLIC, 0L, stranger, kw + " 公開" + i));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(post(PostScopeType.PERSONAL, me, me, kw + " 自分" + i));
            }
            Set<Long> invisible = new HashSet<>();
            invisible.add(post(PostScopeType.TEAM, foreign, stranger, kw + " 他チーム"));
            invisible.add(post(PostScopeType.PERSONAL, stranger, stranger, kw + " 他人の個人"));
            invisible.add(savePost(TimelinePostEntity.builder().scopeType(PostScopeType.PUBLIC).scopeId(0L)
                    .userId(stranger).content(kw + " 他人の下書き").status(PostStatus.DRAFT).build()));

            JsonNode overview = search(me, kw);
            assertThat(typeCount(overview, "POST")).isEqualTo(11);
            assertThat(resultIds(overview, "POST")).hasSize(5).doesNotContainAnyElementsOf(invisible);

            List<Long> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode detail = search(me, Map.of("q", kw, "type", "POST", "page", String.valueOf(page),
                        "size", "5"));
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
                assertThat(detail.path("meta").path("totalPages").asInt()).isEqualTo(3);
                assertThat(typeCount(detail, "POST")).isEqualTo(11);
                all.addAll(resultIds(detail, "POST"));
            }
            assertThat(all).as("ページ間で重複も欠落もなく、不可視は混ざらない").containsExactlyInAnyOrderElementsOf(visible);

            JsonNode big = search(me, Map.of("q", kw, "type", "POST", "size", "50"));
            assertThat(resultIds(big, "POST")).hasSize(11);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12 / URL
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入出力のエスケープと遷移先")
    class EscapingAndUrl {

        @ParameterizedTest(name = "q に [{0}] を含めても 200 で、不可視の投稿は混ざらない")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11(POST): FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならない")
        void AC_C11_POST_演算子でも500にならない(String op) throws Exception {
            Long foreign = privateTeam("C11F-" + kw);
            Long stranger = viewer("c11-stranger");
            teamMember(stranger, foreign);
            Long me = viewer("c11-me");
            Long invisible = post(PostScopeType.TEAM, foreign, stranger, op + kw + op + " 他チーム");
            Long visible = post(PostScopeType.PUBLIC, 0L, stranger, op + kw + op + " 公開");

            for (String q : List.of(kw + op, op + kw + op, kw + " " + op)) {
                JsonNode body = search(me, Map.of("q", q, "type", "POST"));
                assertThat(resultIds(body, "POST")).doesNotContain(invisible);
                assertThat(typeCount(body, "POST")).isLessThanOrEqualTo(1);
            }
            JsonNode plain = search(me, kw);
            assertVisible(plain, "POST", visible, 1);
        }

        @ParameterizedTest(name = "[{0}] を前置した語は演算子ではなく文字としてヒットする")
        @ValueSource(strings = {"+", "-", "~", "<", ">", "(", ")", "*", "@", "%", "_", "\\"})
        @DisplayName("AC-C11(POST): 演算子を含む語は文字として検索される（- で除外・* で前方一致にならない）")
        void AC_C11_POST_演算子は文字(String op) throws Exception {
            Long author = viewer("c11l-author");
            Long me = viewer("c11l-me");
            Long literal = post(PostScopeType.PUBLIC, 0L, author, "前置き " + op + kw + " 後置き");

            JsonNode body = search(me, Map.of("q", op + kw, "type", "POST"));
            assertThat(resultIds(body, "POST")).contains(literal);
            assertThat(typeCount(body, "POST")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-C12(POST): 本文の <script> は snippet で HTML エスケープされ、一致箇所だけが <mark>。snippet は200文字以下")
        void AC_C12_POST_snippetエスケープ() throws Exception {
            Long author = viewer("c12-author");
            Long me = viewer("c12-me");
            String content = "<script>alert(1)</script> 前文 " + kw + " 本文<b>太字</b>" + "あ".repeat(300);
            Long id = post(PostScopeType.PUBLIC, 0L, author, content);

            JsonNode hit = hitOf(search(me, kw, "POST"), id);
            String snippet = hit.path("snippet").asText();
            assertThat(snippet).contains("<mark>" + kw + "</mark>");
            assertThat(snippet).doesNotContain("<script>").doesNotContain("<b>");
            assertThat(snippet).contains("&lt;script&gt;");
            assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<").hasSizeLessThanOrEqualTo(200);
            assertThat(snippet.split("<mark>", -1)).as("<mark> は一致箇所の1か所だけ").hasSize(2);
        }

        @Test
        @DisplayName("POST の結果は type=POST・url=/timeline/{id}")
        void URLは_timeline_id() throws Exception {
            Long author = viewer("url-author");
            Long me = viewer("url-me");
            Long id = post(PostScopeType.PUBLIC, 0L, author, kw + " 遷移先");

            JsonNode overview = search(me, kw);
            JsonNode hit = hitOf(overview, id);
            assertThat(hit.path("type").asText()).isEqualTo("POST");
            assertThat(hit.path("url").asText()).isEqualTo("/timeline/" + id);
            assertThat(typeCount(overview, "POST")).as("POST の typeCounts は 0 固定ではなく実数").isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private Long post(PostScopeType scopeType, Long scopeId, Long userId, String content) {
        return savePost(TimelinePostEntity.builder().scopeType(scopeType).scopeId(scopeId).userId(userId)
                .content(content).status(PostStatus.PUBLISHED).build());
    }

    private Long orgPost(Long orgId, PostDeliveryScope deliveryScope, Long userId, String content) {
        return savePost(TimelinePostEntity.builder().scopeType(PostScopeType.ORGANIZATION).scopeId(orgId)
                .deliveryScope(deliveryScope).userId(userId).content(content).status(PostStatus.PUBLISHED).build());
    }

    private Long savePost(TimelinePostEntity entity) {
        Long id = inTx(() -> posts.saveAndFlush(entity).getId());
        createdPostIds.add(id);
        return id;
    }

    private void setParent(Long childOrgId, Long parentOrgId) {
        inTx(() -> em.createNativeQuery("UPDATE organizations SET parent_organization_id = :p WHERE id = :id")
                .setParameter("p", parentOrgId).setParameter("id", childOrgId).executeUpdate());
    }

    /** チーム T を組織 O に ACTIVE で所属させる（T の所属者にとって O は距離1のアンカー組織）。 */
    private void anchorTeam(Long teamId, Long orgId) {
        inTx(() -> {
            em.persist(TeamOrgMembershipEntity.builder().teamId(teamId).organizationId(orgId)
                    .status(TeamOrgMembershipEntity.Status.ACTIVE).invitedAt(LocalDateTime.now()).build());
            return null;
        });
        anchoredTeamIds.add(teamId);
    }

    private UUID village(Long villager) {
        String slug = "gsp-" + UUID.randomUUID().toString().substring(0, 12);
        createdVillageSlugs.add(slug);
        return inTx(() -> {
            VillageEntity village = villages.save(VillageEntity.builder().slug(slug).name("検索試練の村")
                    .type(VillageType.COMMUNITY).joinPolicy(VillageJoinPolicy.FREE)
                    .visibility(VillageVisibility.PUBLIC)
                    .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY).build());
            villageMemberships.save(VillageMembershipEntity.builder().villageId(village.getId())
                    .subjectType(VillageSubjectType.USER).subjectId(villager).role(VillageRole.VILLAGER)
                    .joinedAt(LocalDateTime.now()).build());
            return village.getId();
        });
    }

    private void ensureFulltextIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'timeline_posts' "
                        + "AND index_name = 'ft_timeline_posts_content'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE timeline_posts ADD FULLTEXT INDEX ft_timeline_posts_content (content) "
                    + "WITH PARSER ngram");
        }
    }

    private JsonNode hitOf(JsonNode body, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if ("POST".equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("POST id=" + id + " が結果に無い（応答: " + body + "）");
    }

    private String urlOf(JsonNode body, Long id) {
        return hitOf(body, id).path("url").asText();
    }
}
