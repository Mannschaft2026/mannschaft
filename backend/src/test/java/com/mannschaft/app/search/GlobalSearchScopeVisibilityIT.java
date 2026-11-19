package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * USER / TEAM / ORGANIZATION 種別の認可境界（CMP-261116-0722 PR-1 試練・red 先行）。
 *
 * <p>最重要条件: <b>権限範囲外のデータを、結果にも件数（typeCounts / meta.total）にも出さない</b>。
 * 各テストは「出ないこと」と同時に「権限があれば出ること」（対照）を同じ fixture で確かめ、
 * 締めすぎで検索の価値を潰していないことも固定する。</p>
 *
 * <p>対象 AC: A1 / A2 / A3 / A4 / A5 / A6 / A7（USER・TEAM・ORGANIZATION）、U1、G1。
 * 正本: {@code docs/features/F04.6_search.md} §2 / §6「認可（種別ごとの述語）」。
 * A2(TEAM) は TEAM の可視性が組織所属に依存しない（PUBLIC または本人所属）ため A1(TEAM) と同一述語で担保する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 USER/TEAM/ORGANIZATION の認可境界（CMP-261116-0722 試練）")
class GlobalSearchScopeVisibilityIT extends AbstractGlobalSearchIT {

    // ═════════════════════════════════════════════════════════════════════
    // USER
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("USER")
    class Users {

        @Test
        @DisplayName("AC-A1(USER): チーム X だけに所属するユーザーは、X に所属しない者の結果にも件数にも出ない（X の所属者には出る）")
        void AC_A1_USER_非所属チームのユーザーは出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw.substring(2));
            Long teamY = privateTeam("Y-" + kw.substring(2));
            Long target = user(kw + " 対象");
            teamMember(target, teamX);
            Long insider = viewer("insider");
            teamMember(insider, teamX);
            Long outsider = viewer("outsider");
            teamMember(outsider, teamY);

            assertNothingVisible(search(outsider, kw), "USER");
            assertNothingVisible(search(outsider, kw, "USER"), "USER");
            assertThat(search(outsider, kw, "USER").path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), "USER", target, 1);
        }

        @Test
        @DisplayName("AC-A2(USER): 組織 O だけに所属するユーザーは、別組織の者に出ない（O の所属者には出る）")
        void AC_A2_USER_別組織のユーザーは出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw.substring(2));
            Long orgP = privateOrg("P-" + kw.substring(2));
            Long target = user(kw + " 組織員");
            orgMember(target, orgO);
            Long insider = viewer("org-insider");
            orgMember(insider, orgO);
            Long outsider = viewer("org-outsider");
            orgMember(outsider, orgP);

            assertNothingVisible(search(outsider, kw), "USER");
            assertVisible(search(insider, kw), "USER", target, 1);
        }

        @Test
        @DisplayName("AC-A3(USER): 非所属スコープ指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）")
        void AC_A3_USER_存在オラクルなし() throws Exception {
            Long foreign = privateTeam("F-" + kw.substring(2));
            Long target = user(kw + " 他所");
            teamMember(target, foreign);
            Long mine = privateTeam("M-" + kw.substring(2));
            Long mate = user(kw + " 仲間");
            teamMember(mate, mine);
            Long me = viewer("a3-user");
            teamMember(me, mine);

            JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
            JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));

            for (String t : ALL_TYPES) {
                assertThat(typeCount(foreignScope, t)).as("非所属スコープ %s", t).isZero();
            }
            assertThat(foreignScope.path("data").path("results")).isEmpty();
            assertThat(foreignScope.path("data").path("typeCounts"))
                    .isEqualTo(missingScope.path("data").path("typeCounts"));
            assertThat(foreignScope.path("data").path("results"))
                    .isEqualTo(missingScope.path("data").path("results"));
            assertThat(foreignScope.path("meta")).isEqualTo(missingScope.path("meta"));

            JsonNode ownScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", mine.toString()));
            assertVisible(ownScope, "USER", mate, 1);
        }

        @Test
        @DisplayName("AC-A4(USER): GUEST ロールしか持たないスコープの仲間は出ない（MEMBER なら出る）")
        void AC_A4_USER_GUESTのみのスコープは母集団外() throws Exception {
            Long team = privateTeam("G-" + kw.substring(2));
            Long target = user(kw + " 正会員");
            teamMember(target, team);
            Long guest = viewer("guest");
            teamGuest(guest, team);
            Long member = viewer("member");
            teamMember(member, team);

            assertNothingVisible(search(guest, kw), "USER");
            assertVisible(search(member, kw), "USER", target, 1);
        }

        @Test
        @DisplayName("AC-A5(USER): 論理削除済みユーザー・論理削除済みスコープ経由のユーザーは出ない")
        void AC_A5_USER_論理削除は出ない() throws Exception {
            Long team = privateTeam("D-" + kw.substring(2));
            Long deletedUser = user(kw + " 削除済み");
            teamMember(deletedUser, team);
            nativeUpdate("UPDATE users SET deleted_at = NOW() WHERE id = :id", deletedUser);
            Long alive = user(kw + " 生存");
            teamMember(alive, team);
            Long me = viewer("a5-user");
            teamMember(me, team);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "USER")).containsExactly(alive);
            assertThat(typeCount(body, "USER")).isEqualTo(1);

            Long deletedScope = privateTeam("DS-" + kw.substring(2));
            Long viaDeleted = user(kw + " 削除スコープ経由");
            teamMember(viaDeleted, deletedScope);
            teamMember(me, deletedScope);
            softDeleteTeam(deletedScope);

            JsonNode after = search(me, kw);
            assertThat(resultIds(after, "USER")).doesNotContain(viaDeleted);
            assertThat(typeCount(after, "USER")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A6(USER): 不可視ユーザーを足しても typeCounts は変わらず、詳細ビューの total・結果件数と一致する")
        void AC_A6_USER_件数と結果は同一述語() throws Exception {
            Long team = privateTeam("C-" + kw.substring(2));
            Long me = viewer("a6-user");
            teamMember(me, team);
            for (int i = 0; i < 3; i++) {
                teamMember(user(kw + " 見える" + i), team);
            }
            long before = typeCount(search(me, kw), "USER");

            Long foreign = privateTeam("CF-" + kw.substring(2));
            teamMember(user(kw + " 他所"), foreign);
            teamMember(user(kw + " 非公開", null, false), team);

            JsonNode overview = search(me, kw);
            JsonNode detail = search(me, Map.of("q", kw, "type", "USER", "size", "50"));
            assertThat(before).isEqualTo(3);
            assertThat(typeCount(overview, "USER")).isEqualTo(3);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(3);
            assertThat(resultIds(detail, "USER")).hasSize(3);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-U1
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-U1: USER 固有")
    class UserSpecific {

        @Test
        @DisplayName("AC-U1: is_searchable=false のユーザーは同一スコープでも出ない")
        void AC_U1_検索不可は出ない() throws Exception {
            Long team = privateTeam("U-" + kw.substring(2));
            Long hidden = user(kw + " 検索不可", null, false);
            Long shown = user(kw + " 検索可");
            teamMember(hidden, team);
            teamMember(shown, team);
            Long me = viewer("u1-searchable");
            teamMember(me, team);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "USER")).containsExactly(shown);
            assertThat(typeCount(body, "USER")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-U1: 退会者（匿名化＋論理削除）と ACTIVE 以外は出ない")
        void AC_U1_退会者と非ACTIVEは出ない() throws Exception {
            Long team = privateTeam("W-" + kw.substring(2));
            Long withdrawn = user(kw + " 退会予定");
            Long frozen = user(kw + " 凍結");
            Long active = user(kw + " 活動中");
            for (Long u : new Long[]{withdrawn, frozen, active}) {
                teamMember(u, team);
            }
            inTx(() -> {
                users.findById(withdrawn).ifPresent(u -> {
                    u.anonymize();
                    users.save(u);
                });
                return null;
            });
            // 退会フローの二段階（anonymize → softDelete）を再現する。匿名化後の表示名「退会済みユーザー」で
            // 検索しても出ないことを下で確かめる（匿名化だけでは同一スコープの仲間に「退会済みユーザー」として出うる）。
            nativeUpdate("UPDATE users SET deleted_at = NOW() WHERE id = :id", withdrawn);
            nativeUpdate("UPDATE users SET status = 'FROZEN' WHERE id = :id", frozen);
            Long me = viewer("u1-withdrawn");
            teamMember(me, team);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "USER")).containsExactly(active);
            assertThat(typeCount(body, "USER")).isEqualTo(1);
            assertThat(search(me, "退会済みユーザー", "USER").path("data").path("results")).isEmpty();
        }

        @Test
        @DisplayName("AC-U1: 暗号化された氏名ではヒットせず、平文の display_name / nickname2 でヒットする")
        void AC_U1_平文列のみ() throws Exception {
            Long team = privateTeam("N-" + kw.substring(2));
            Long byLastName = user("表示名のみ-" + kw.substring(0, 4), null, true, kw + "姓");
            Long byNickname = user("表示名-" + kw.substring(0, 4), kw + "ニック", true);
            Long byDisplay = user(kw + " 表示名", null, true);
            for (Long u : new Long[]{byLastName, byNickname, byDisplay}) {
                teamMember(u, team);
            }
            Long me = viewer("u1-plain");
            teamMember(me, team);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "USER")).containsExactlyInAnyOrder(byNickname, byDisplay);
            assertThat(typeCount(body, "USER")).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-U1: 同一スコープを共有しないユーザーは出ない（どこにも所属しない閲覧者には誰も出ない）")
        void AC_U1_無所属には誰も出ない() throws Exception {
            Long team = privateTeam("L-" + kw.substring(2));
            teamMember(user(kw + " 誰か"), team);
            Long loner = viewer("loner");

            assertNothingVisible(search(loner, kw), "USER");
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // TEAM
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("TEAM")
    class Teams {

        @Test
        @DisplayName("AC-A1(TEAM)/AC-G1: 非公開かつ非所属のチームは結果にも件数にも出ず、本人所属の非公開チームは出る")
        void AC_A1_G1_TEAM_非公開非所属は出ない() throws Exception {
            Long hidden = privateTeam(kw + " 非公開");
            Long outsider = viewer("team-outsider");
            Long insider = viewer("team-insider");
            teamMember(insider, hidden);

            assertNothingVisible(search(outsider, kw), "TEAM");
            JsonNode detail = search(outsider, kw, "TEAM");
            assertNothingVisible(detail, "TEAM");
            assertThat(detail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), "TEAM", hidden, 1);
        }

        @Test
        @DisplayName("AC-A7(TEAM): 公開チームは非所属者にも出る")
        void AC_A7_TEAM_公開は出る() throws Exception {
            Long open = publicTeam(kw + " 公開");
            Long outsider = viewer("team-public");

            assertVisible(search(outsider, kw), "TEAM", open, 1);
        }

        @Test
        @DisplayName("AC-A3(TEAM): 非所属スコープ指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）")
        void AC_A3_TEAM_存在オラクルなし() throws Exception {
            Long foreign = privateTeam(kw + " 他所");
            Long me = viewer("a3-team");

            JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
            JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));

            assertNothingVisible(foreignScope, "TEAM");
            assertThat(foreignScope.path("data").path("typeCounts"))
                    .isEqualTo(missingScope.path("data").path("typeCounts"));
            assertThat(foreignScope.path("data").path("results"))
                    .isEqualTo(missingScope.path("data").path("results"));
        }

        @Test
        @DisplayName("AC-A4(TEAM): GUEST ロールしか持たない非公開チームは出ない（MEMBER なら出る）")
        void AC_A4_TEAM_GUESTのみは出ない() throws Exception {
            Long team = privateTeam(kw + " ゲスト先");
            Long guest = viewer("team-guest");
            teamGuest(guest, team);
            Long member = viewer("team-member");
            teamMember(member, team);

            assertNothingVisible(search(guest, kw), "TEAM");
            assertVisible(search(member, kw), "TEAM", team, 1);
        }

        @Test
        @DisplayName("AC-A5(TEAM): 論理削除済みチームは公開でも本人所属でも出ない")
        void AC_A5_TEAM_論理削除は出ない() throws Exception {
            Long deletedPublic = publicTeam(kw + " 削除公開");
            Long deletedOwn = privateTeam(kw + " 削除所属");
            Long alive = publicTeam(kw + " 生存");
            Long me = viewer("a5-team");
            teamMember(me, deletedOwn);
            softDeleteTeam(deletedPublic);
            softDeleteTeam(deletedOwn);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "TEAM")).containsExactly(alive);
            assertThat(typeCount(body, "TEAM")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-G1(TEAM): アーカイブ済みチームは本人所属でも結果・件数ともに出ない")
        void AC_G1_TEAM_アーカイブは所属者にも出ない() throws Exception {
            Long archived = privateTeam(kw + " アーカイブ");
            Long archivedPublic = publicTeam(kw + " アーカイブ公開");
            Long alive = privateTeam(kw + " 現役");
            Long me = viewer("g1-team");
            teamMember(me, archived);
            teamMember(me, alive);
            archiveTeam(archived);
            archiveTeam(archivedPublic);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "TEAM")).containsExactly(alive);
            assertThat(typeCount(body, "TEAM")).isEqualTo(1);
            JsonNode detail = search(me, kw, "TEAM");
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-G1(TEAM): 承諾前（PROVISIONED）のチームは公開でも出ない（ACTIVE のみ）")
        void AC_G1_TEAM_PROVISIONEDは出ない() throws Exception {
            Long provisioned = publicTeam(kw + " 事前作成");
            provisionTeam(provisioned);
            Long me = viewer("g1-provisioned");

            assertNothingVisible(search(me, kw), "TEAM");
        }

        @Test
        @DisplayName("AC-A6(TEAM): 不可視チームを足しても typeCounts は不変で、詳細ビューの total・結果件数と一致する")
        void AC_A6_TEAM_件数と結果は同一述語() throws Exception {
            Long me = viewer("a6-team");
            publicTeam(kw + " 公開1");
            Long own = privateTeam(kw + " 所属");
            teamMember(me, own);
            long before = typeCount(search(me, kw), "TEAM");

            privateTeam(kw + " 他所");
            Long archived = publicTeam(kw + " 保管");
            archiveTeam(archived);
            Long guestOnly = privateTeam(kw + " ゲスト");
            teamGuest(me, guestOnly);

            JsonNode overview = search(me, kw);
            JsonNode detail = search(me, Map.of("q", kw, "type", "TEAM", "size", "50"));
            assertThat(before).isEqualTo(2);
            assertThat(typeCount(overview, "TEAM")).isEqualTo(2);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(resultIds(detail, "TEAM")).hasSize(2);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ORGANIZATION
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ORGANIZATION")
    class Organizations {

        @Test
        @DisplayName("AC-A2(ORG)/AC-G1: 非公開組織 O は別組織の者の結果にも件数にも出ず、O の所属者には出る")
        void AC_A2_G1_ORG_別組織は出ない() throws Exception {
            Long orgO = privateOrg(kw + " 組織O");
            Long orgP = privateOrg("P-" + kw.substring(2));
            Long insider = viewer("org-insider");
            orgMember(insider, orgO);
            Long outsider = viewer("org-outsider");
            orgMember(outsider, orgP);

            assertNothingVisible(search(outsider, kw), "ORGANIZATION");
            JsonNode detail = search(outsider, kw, "ORGANIZATION");
            assertThat(detail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), "ORGANIZATION", orgO, 1);
        }

        @Test
        @DisplayName("AC-A7(ORG): 公開組織は非所属者にも出る")
        void AC_A7_ORG_公開は出る() throws Exception {
            Long open = publicOrg(kw + " 公開組織");
            Long outsider = viewer("org-public");

            assertVisible(search(outsider, kw), "ORGANIZATION", open, 1);
        }

        @Test
        @DisplayName("AC-A3(ORG): 非所属組織指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）")
        void AC_A3_ORG_存在オラクルなし() throws Exception {
            Long foreign = privateOrg(kw + " 他所組織");
            Long me = viewer("a3-org");

            JsonNode foreignScope = search(me,
                    Map.of("q", kw, "scopeType", "ORGANIZATION", "scopeId", foreign.toString()));
            JsonNode missingScope = search(me,
                    Map.of("q", kw, "scopeType", "ORGANIZATION", "scopeId", "987654321987"));

            assertNothingVisible(foreignScope, "ORGANIZATION");
            assertThat(foreignScope.path("data").path("typeCounts"))
                    .isEqualTo(missingScope.path("data").path("typeCounts"));
            assertThat(foreignScope.path("data").path("results"))
                    .isEqualTo(missingScope.path("data").path("results"));
        }

        @Test
        @DisplayName("AC-A4(ORG): GUEST ロールしか持たない非公開組織は出ない（MEMBER なら出る）")
        void AC_A4_ORG_GUESTのみは出ない() throws Exception {
            Long org = privateOrg(kw + " ゲスト組織");
            Long guest = viewer("org-guest");
            orgGuest(guest, org);
            Long member = viewer("org-member");
            orgMember(member, org);

            assertNothingVisible(search(guest, kw), "ORGANIZATION");
            assertVisible(search(member, kw), "ORGANIZATION", org, 1);
        }

        @Test
        @DisplayName("AC-A5(ORG): 論理削除済み組織は公開でも本人所属でも出ない")
        void AC_A5_ORG_論理削除は出ない() throws Exception {
            Long deletedPublic = publicOrg(kw + " 削除公開");
            Long deletedOwn = privateOrg(kw + " 削除所属");
            Long alive = publicOrg(kw + " 生存");
            Long me = viewer("a5-org");
            orgMember(me, deletedOwn);
            softDeleteOrg(deletedPublic);
            softDeleteOrg(deletedOwn);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "ORGANIZATION")).containsExactly(alive);
            assertThat(typeCount(body, "ORGANIZATION")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-G1(ORG): アーカイブ済み組織は本人所属でも結果・件数ともに出ない")
        void AC_G1_ORG_アーカイブは所属者にも出ない() throws Exception {
            Long archived = privateOrg(kw + " アーカイブ");
            Long alive = privateOrg(kw + " 現役");
            Long me = viewer("g1-org");
            orgMember(me, archived);
            orgMember(me, alive);
            archiveOrg(archived);

            JsonNode body = search(me, kw);
            assertThat(resultIds(body, "ORGANIZATION")).containsExactly(alive);
            assertThat(typeCount(body, "ORGANIZATION")).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A6(ORG): 不可視組織を足しても typeCounts は不変で、詳細ビューの total・結果件数と一致する")
        void AC_A6_ORG_件数と結果は同一述語() throws Exception {
            Long me = viewer("a6-org");
            publicOrg(kw + " 公開1");
            Long own = privateOrg(kw + " 所属");
            orgMember(me, own);
            long before = typeCount(search(me, kw), "ORGANIZATION");

            privateOrg(kw + " 他所");
            Long archived = publicOrg(kw + " 保管");
            archiveOrg(archived);
            Long guestOnly = privateOrg(kw + " ゲスト");
            orgGuest(me, guestOnly);

            JsonNode overview = search(me, kw);
            JsonNode detail = search(me, Map.of("q", kw, "type", "ORGANIZATION", "size", "50"));
            assertThat(before).isEqualTo(2);
            assertThat(typeCount(overview, "ORGANIZATION")).isEqualTo(2);
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(2);
            assertThat(resultIds(detail, "ORGANIZATION")).hasSize(2);
        }
    }
}
