package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.team.entity.TeamEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TEAM / ORGANIZATION 種別が、スコープ本体の公開範囲の<b>段</b>を正準（チーム詳細・組織詳細の
 * {@code ContentVisibilityChecker}）と同じ規則で判定することの回帰固定（CMP-261116-0722 アリシゼーション指摘）。
 *
 * <p>欠陥: TEAM の述語が {@code visibility = PUBLIC OR id IN 閲覧者の所属チーム}（所属＝SUPPORTER 以上）だったため、
 * {@code MEMBERS_AND_ABOVE}（サポーター・ゲスト除外。F01.2 01_db_design.md）のチームが、そのチームのサポーターの
 * 検索結果・件数・サジェストに出ていた（チーム詳細はサポーターに 403）。</p>
 *
 * <p>正準（{@code TeamVisibilityMapper} → {@code AbstractContentVisibilityResolver#visibleByLevel}）:
 * PUBLIC=誰でも / GUESTS_AND_ABOVE=所属者 / SUPPORTERS_AND_ABOVE=SUPPORTER 以上 / MEMBERS_AND_ABOVE=MEMBER 以上。
 * 組織は PUBLIC=誰でも / PRIVATE=所属者（SCOPE_AFFILIATED。サポーターも可）で、検索の母集団（SUPPORTER 以上）と
 * 食い違わないため欠陥なし（下の ORGANIZATION で理由ごと固定する）。</p>
 *
 * <p>各ケースは結果・件数（概要の typeCounts と詳細の meta.total）・サジェストの 3 経路を同時に確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 TEAM/ORGANIZATION は公開範囲の段を正準と同じ規則で判定する（CMP-261116-0722）")
class GlobalSearchTeamOrgVisibilityLadderIT extends AbstractGlobalSearchIT {

    /** 結果・概要件数・詳細件数・サジェストのいずれにも出ないこと。 */
    private void assertHiddenEverywhere(Long viewer, String type, String idField, Long id) throws Exception {
        assertNothingVisible(search(viewer, kw), type);
        JsonNode detail = search(viewer, kw, type);
        assertThat(resultIds(detail, type)).as("詳細ビューの %s に出ないこと", type).doesNotContain(id);
        assertThat(detail.path("meta").path("total").asLong()).as("詳細ビューの total").isZero();
        assertThat(suggestionIds(suggest(viewer, kw), type, idField)).as("サジェストに出ないこと").doesNotContain(id);
    }

    /** 結果・概要件数・詳細件数・サジェストのすべてに出ること。 */
    private void assertShownEverywhere(Long viewer, String type, String idField, Long id) throws Exception {
        assertVisible(search(viewer, kw), type, id, 1);
        JsonNode detail = search(viewer, kw, type);
        assertThat(resultIds(detail, type)).containsExactly(id);
        assertThat(detail.path("meta").path("total").asLong()).isEqualTo(1);
        assertThat(suggestionIds(suggest(viewer, kw), type, idField)).containsExactly(id);
    }

    @Nested
    @DisplayName("TEAM")
    class Teams {

        @Test
        @DisplayName("MEMBERS_AND_ABOVE のチームはそのチームのサポーターの結果・件数・サジェストに出ない")
        void MEMBERS_AND_ABOVE_サポーターには出ない() throws Exception {
            Long team = team(kw + " 正会員限定", TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            Long supporter = viewer("ladder-supporter");
            teamSupporter(supporter, team);

            assertHiddenEverywhere(supporter, "TEAM", "teamId", team);
        }

        @Test
        @DisplayName("MEMBERS_AND_ABOVE のチームはそのチームの MEMBER には出る（対照）")
        void MEMBERS_AND_ABOVE_メンバーには出る() throws Exception {
            Long team = team(kw + " 正会員限定", TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            Long member = viewer("ladder-member");
            teamMember(member, team);

            assertShownEverywhere(member, "TEAM", "teamId", team);
        }

        @Test
        @DisplayName("SUPPORTERS_AND_ABOVE のチームはサポーターに出て、非所属者には出ない")
        void SUPPORTERS_AND_ABOVE_サポーターには出る() throws Exception {
            Long team = team(kw + " サポーター以上", TeamEntity.Visibility.SUPPORTERS_AND_ABOVE);
            Long supporter = viewer("ladder-sa-supporter");
            teamSupporter(supporter, team);
            Long outsider = viewer("ladder-sa-outsider");

            assertShownEverywhere(supporter, "TEAM", "teamId", team);
            assertHiddenEverywhere(outsider, "TEAM", "teamId", team);
        }

        @Test
        @DisplayName("GUESTS_AND_ABOVE のチームは所属者（サポーター）に出て、非所属者には出ない")
        void GUESTS_AND_ABOVE_所属者には出る() throws Exception {
            Long team = team(kw + " ゲスト以上", TeamEntity.Visibility.GUESTS_AND_ABOVE);
            Long supporter = viewer("ladder-ga-supporter");
            teamSupporter(supporter, team);
            Long outsider = viewer("ladder-ga-outsider");

            assertShownEverywhere(supporter, "TEAM", "teamId", team);
            assertHiddenEverywhere(outsider, "TEAM", "teamId", team);
        }

        @Test
        @DisplayName("PUBLIC のチームは非所属者にも結果・件数・サジェストで出る")
        void PUBLIC_非所属者にも出る() throws Exception {
            Long team = publicTeam(kw + " 公開");
            Long outsider = viewer("ladder-public");

            assertShownEverywhere(outsider, "TEAM", "teamId", team);
        }

        @Test
        @DisplayName("非公開チームは別チーム・別組織の所属者には出ない")
        void 他チーム他組織の所属者には出ない() throws Exception {
            Long team = team(kw + " 正会員限定", TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            Long otherTeam = team("他-" + kw.substring(2), TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            Long otherOrg = privateOrg("他組織-" + kw.substring(2));
            Long foreigner = viewer("ladder-foreigner");
            teamMember(foreigner, otherTeam);
            orgMember(foreigner, otherOrg);

            assertHiddenEverywhere(foreigner, "TEAM", "teamId", team);
        }

        @Test
        @DisplayName("あるチームの MEMBER であっても、別の MEMBERS_AND_ABOVE チームのサポーターには後者が出ない（スコープ単位で判定）")
        void ロールはスコープ単位で判定する() throws Exception {
            Long memberTeam = team("M-" + kw.substring(2), TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            Long supportedTeam = team(kw + " 応援先", TeamEntity.Visibility.MEMBERS_AND_ABOVE);
            Long viewer = viewer("ladder-mixed");
            teamMember(viewer, memberTeam);
            teamSupporter(viewer, supportedTeam);

            assertHiddenEverywhere(viewer, "TEAM", "teamId", supportedTeam);
        }
    }

    @Nested
    @DisplayName("ORGANIZATION")
    class Organizations {

        @Test
        @DisplayName("PRIVATE の組織は正準（SCOPE_AFFILIATED＝所属者可）どおり、その組織のサポーターに出る")
        void PRIVATE_サポーターには出る() throws Exception {
            Long org = privateOrg(kw + " 非公開組織");
            Long supporter = viewer("ladder-org-supporter");
            orgSupporter(supporter, org);

            assertShownEverywhere(supporter, "ORGANIZATION", "organizationId", org);
        }

        @Test
        @DisplayName("PRIVATE の組織は別組織・別チームの所属者には結果・件数・サジェストで出ない")
        void PRIVATE_他組織には出ない() throws Exception {
            Long org = privateOrg(kw + " 非公開組織");
            Long otherOrg = privateOrg("他組織-" + kw.substring(2));
            Long otherTeam = privateTeam("他-" + kw.substring(2));
            Long foreigner = viewer("ladder-org-foreigner");
            orgMember(foreigner, otherOrg);
            teamMember(foreigner, otherTeam);

            assertHiddenEverywhere(foreigner, "ORGANIZATION", "organizationId", org);
        }

        @Test
        @DisplayName("PUBLIC の組織は非所属者にも結果・件数・サジェストで出る")
        void PUBLIC_非所属者にも出る() throws Exception {
            Long org = publicOrg(kw + " 公開組織");
            Long outsider = viewer("ladder-org-public");

            assertShownEverywhere(outsider, "ORGANIZATION", "organizationId", org);
        }
    }
}
