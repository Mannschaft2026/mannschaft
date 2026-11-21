package com.mannschaft.app.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.support.test.MembershipTestHelper;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * グローバル検索 MESSAGE（チャットメッセージ）種別の受け入れテスト（CMP-261116-0722 PR-3 試練・red 先行）。
 *
 * <p>最重要条件（マスター逐語）: 「くれぐれも他の人や別チーム、組織など権限範囲外のデータが取得されることだけは
 * 防いで」。各テストは「出ないこと」を<b>結果（results）と件数（typeCounts / meta.total）の両方</b>で確かめ、
 * 同じ fixture で「権限があれば出ること」（対照）も確かめて、締めすぎで検索の価値を潰していないことを固定する。</p>
 *
 * <p><b>可視性（台帳 D4 / 設計書 §7）</b>: 次の<b>すべて</b>を満たすメッセージだけが出る。</p>
 * <ol>
 *   <li>閲覧者本人が {@code chat_channel_members} に行を持つチャンネル（未参加の公開チャンネルも対象外）。</li>
 *   <li>チャンネル種別が {@link ChannelType#isScopeChannel()}＝{@code TEAM_PUBLIC / TEAM_PRIVATE / ORG_PUBLIC /
 *       ORG_PRIVATE} のいずれか。DM / GROUP_DM は当事者にも出さない。VILLAGE_LOBBY / EVENT_CHAT /
 *       TOURNAMENT_CHAT / TOURNAMENT_DIVISION_CHAT はメンバー行ではなく別ドメインのアクセスモデルで認可される
 *       横断スペース（{@code ChannelType#MEMBERSHIP_GATED_TYPES} の Javadoc）なので、メンバー行があっても出さない。</li>
 *   <li>チャンネルが属するスコープ（{@code team_id} / {@code organization_id}）が集約側 {@link SearchViewer} の
 *       母集団に含まれる（GUEST のみ・削除済み・アーカイブ済みスコープは除外済み）。チャンネルメンバー行が
 *       残っていてもスコープから外れていれば出さない。</li>
 *   <li>チャンネルが論理削除・アーカイブされていない。</li>
 *   <li>メッセージが論理削除されておらず、{@code is_system=false}、{@code message_type='TEXT'}（INVITE_CARD 除外）、
 *       予約未送信（{@code scheduled_at IS NOT NULL AND scheduled_sent_at IS NULL}）でない。</li>
 * </ol>
 * <p>{@code ChatMessageVisibilityResolver} はチャンネルメンバーシップを見ないため単独ゲートに使わない（同 Javadoc）。
 * 退出（leave）・除名（kick）は {@code ChatMemberService#removeMember} がメンバー行を物理削除する
 * （{@code ChatChannelMemberEntity} に状態列は無い）ので、本試練ではメンバー行の削除で表現する。</p>
 *
 * <p><b>FULLTEXT</b>: 本番 V4.014 の {@code ft_msg_body}（ngram）をテストスキーマにも補う
 * （{@code test-schema-fulltext-indexes.sql} と本クラスの {@link #ensureFulltextIndex()}）。InnoDB の FULLTEXT は
 * コミット済みの行だけを拾うため、fixture は基底の {@link #inTx} で commit する。キーワードは ngram の
 * ストップワードを含まない子音だけで作る。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("グローバル検索 MESSAGE 種別の認可境界（CMP-261116-0722 PR-3 試練）")
class GlobalSearchMessageVisibilityIT extends AbstractGlobalSearchIT {

    private static final String CONSONANTS = "bcdfghjklmnpqrstvwxz";
    private static final String MESSAGE = "MESSAGE";

    @Autowired private JdbcTemplate jdbc;

    private final List<Long> createdChannelIds = new ArrayList<>();

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
    void cleanUpChat() {
        inTx(() -> {
            for (Long channelId : createdChannelIds) {
                for (String sql : List.of(
                        "DELETE FROM chat_messages WHERE channel_id = :id",
                        "DELETE FROM chat_channel_members WHERE channel_id = :id",
                        "DELETE FROM chat_channels WHERE id = :id")) {
                    em.createNativeQuery(sql).setParameter("id", channelId).executeUpdate();
                }
            }
            return null;
        });
        createdChannelIds.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A1 / A2: スコープの境界
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スコープ境界（A1 / A2）")
    class ScopeBoundary {

        @Test
        @DisplayName("AC-A1(MESSAGE): チーム X のチャンネルのメッセージは X 非所属者の結果にも typeCounts.MESSAGE にも出ない（X の参加者には出る）")
        void AC_A1_MESSAGE_非所属チームのメッセージは出ない() throws Exception {
            Long teamX = privateTeam("X-" + kw);
            Long teamY = privateTeam("Y-" + kw);
            Long author = viewer("a1-author");
            teamMember(author, teamX);
            Long insider = viewer("a1-insider");
            teamMember(insider, teamX);
            Long outsider = viewer("a1-outsider");
            teamMember(outsider, teamY);
            Long channel = teamChannel(ChannelType.TEAM_PUBLIC, teamX, author, insider);
            Long message = message(channel, author, kw + " チームXの連絡");

            assertNothingVisible(search(outsider, kw), MESSAGE);
            JsonNode outsiderDetail = search(outsider, kw, MESSAGE);
            assertNothingVisible(outsiderDetail, MESSAGE);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            assertVisible(search(insider, kw), MESSAGE, message, 1);
            JsonNode insiderDetail = search(insider, kw, MESSAGE);
            assertVisible(insiderDetail, MESSAGE, message, 1);
            assertThat(insiderDetail.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A1(MESSAGE): チャンネルのメンバー行が残っていても、チームに所属していなければ出ない（チャンネル参加とスコープ所属の両方が要る）")
        void AC_A1_MESSAGE_メンバー行だけではチーム外に出ない() throws Exception {
            Long teamX = privateTeam("XR-" + kw);
            Long author = viewer("a1r-author");
            teamMember(author, teamX);
            Long formerMember = viewer("a1r-former");
            Long channel = teamChannel(ChannelType.TEAM_PRIVATE, teamX, author, formerMember);
            message(channel, author, kw + " 退団者が見てはいけない");

            assertNothingVisible(search(formerMember, kw), MESSAGE);
            assertNothingVisible(search(formerMember, kw, MESSAGE), MESSAGE);
        }

        @Test
        @DisplayName("AC-A2(MESSAGE): 組織 O のチャンネルのメッセージは別組織ユーザーに出ない（O の参加者には出る）")
        void AC_A2_MESSAGE_別組織のメッセージは出ない() throws Exception {
            Long orgO = privateOrg("O-" + kw);
            Long orgP = privateOrg("P-" + kw);
            Long author = viewer("a2-author");
            orgMember(author, orgO);
            Long insider = viewer("a2-insider");
            orgMember(insider, orgO);
            Long outsider = viewer("a2-outsider");
            orgMember(outsider, orgP);
            Long pub = orgChannel(ChannelType.ORG_PUBLIC, orgO, author, insider);
            Long priv = orgChannel(ChannelType.ORG_PRIVATE, orgO, author, insider);
            Long m1 = message(pub, author, kw + " 組織公開チャンネル");
            Long m2 = message(priv, author, kw + " 組織非公開チャンネル");

            assertNothingVisible(search(outsider, kw), MESSAGE);
            JsonNode outsiderDetail = search(outsider, kw, MESSAGE);
            assertNothingVisible(outsiderDetail, MESSAGE);
            assertThat(outsiderDetail.path("meta").path("total").asLong()).isZero();

            JsonNode insiderBody = search(insider, kw, MESSAGE);
            assertThat(resultIds(insiderBody, MESSAGE)).containsExactlyInAnyOrder(m1, m2);
            assertThat(typeCount(insiderBody, MESSAGE)).isEqualTo(2);
            assertThat(insiderBody.path("meta").path("total").asLong()).isEqualTo(2);
        }

        @Test
        @DisplayName("SYSTEM_ADMIN でもバイパスしない: 非所属チームのチャンネルのメッセージは出ない")
        void SYSTEM_ADMINはバイパスしない() throws Exception {
            Long team = privateTeam("SA-" + kw);
            Long author = viewer("sa-author");
            teamMember(author, team);
            Long admin = viewer("sysadmin");
            inTx(() -> {
                MembershipTestHelper.insertUserRole(em, admin, "SYSTEM_ADMIN", null, null);
                return null;
            });
            Long channel = teamChannel(ChannelType.TEAM_PUBLIC, team, author);
            message(channel, author, kw + " チーム内");

            assertNothingVisible(search(admin, kw), MESSAGE);
            assertNothingVisible(search(admin, kw, MESSAGE), MESSAGE);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A3: スコープ指定の存在オラクル
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A3(MESSAGE): 非所属スコープ指定と存在しない scopeId 指定の応答が同一（200・結果0・件数0）。自スコープ指定ではそのスコープのチャンネルのメッセージだけが出る")
    void AC_A3_MESSAGE_存在オラクルなし() throws Exception {
        Long foreign = privateTeam("F-" + kw);
        Long foreignAuthor = viewer("a3-foreign");
        teamMember(foreignAuthor, foreign);
        message(teamChannel(ChannelType.TEAM_PUBLIC, foreign, foreignAuthor), foreignAuthor, kw + " 他所のメッセージ");
        Long mine = privateTeam("M-" + kw);
        Long myOrg = privateOrg("MO-" + kw);
        Long me = viewer("a3-me");
        teamMember(me, mine);
        orgMember(me, myOrg);
        Long mineMessage = message(teamChannel(ChannelType.TEAM_PUBLIC, mine, me), me, kw + " 自チームのメッセージ");
        Long orgMessage = message(orgChannel(ChannelType.ORG_PUBLIC, myOrg, me), me, kw + " 自組織のメッセージ");

        JsonNode foreignScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", foreign.toString()));
        JsonNode missingScope = search(me, Map.of("q", kw, "scopeType", "TEAM", "scopeId", "987654321987"));
        JsonNode foreignDetail = search(me, Map.of("q", kw, "type", MESSAGE, "scopeType", "TEAM",
                "scopeId", foreign.toString()));
        JsonNode missingDetail = search(me, Map.of("q", kw, "type", MESSAGE, "scopeType", "TEAM",
                "scopeId", "987654321987"));

        for (String t : ALL_TYPES) {
            assertThat(typeCount(foreignScope, t)).as("非所属スコープ %s", t).isZero();
        }
        assertThat(foreignScope.path("data").path("results")).isEmpty();
        assertThat(foreignScope.path("data")).isEqualTo(missingScope.path("data"));
        assertThat(foreignScope.path("meta")).isEqualTo(missingScope.path("meta"));
        assertThat(foreignScope.path("error")).isEqualTo(missingScope.path("error"));
        assertThat(foreignDetail.path("data")).isEqualTo(missingDetail.path("data"));
        assertThat(foreignDetail.path("meta")).isEqualTo(missingDetail.path("meta"));
        assertThat(foreignDetail.path("error")).isEqualTo(missingDetail.path("error"));
        assertThat(typeCount(foreignDetail, MESSAGE)).isZero();
        assertThat(foreignDetail.path("meta").path("total").asLong()).isZero();

        JsonNode ownScope = search(me, Map.of("q", kw, "type", MESSAGE, "scopeType", "TEAM",
                "scopeId", mine.toString()));
        assertThat(resultIds(ownScope, MESSAGE)).as("スコープ指定時はそのスコープのチャンネルのメッセージだけ")
                .containsExactly(mineMessage);
        assertThat(resultIds(ownScope, MESSAGE)).doesNotContain(orgMessage);
        assertThat(typeCount(ownScope, MESSAGE)).isEqualTo(1);
        assertThat(ownScope.path("meta").path("total").asLong()).isEqualTo(1);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A4 / A5
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-A4(MESSAGE): GUEST ロールしか持たないスコープのチャンネルのメッセージは、チャンネルのメンバー行があっても出ない（MEMBER なら出る）")
    void AC_A4_MESSAGE_GUESTのみのスコープは母集団外() throws Exception {
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
        Long teamCh = teamChannel(ChannelType.TEAM_PUBLIC, team, author, guest, member);
        Long orgCh = orgChannel(ChannelType.ORG_PUBLIC, org, author, guest);
        Long teamMessage = message(teamCh, author, kw + " チームのお知らせ");
        message(orgCh, author, kw + " 組織のお知らせ");

        assertNothingVisible(search(guest, kw), MESSAGE);
        JsonNode guestDetail = search(guest, kw, MESSAGE);
        assertNothingVisible(guestDetail, MESSAGE);
        assertThat(guestDetail.path("meta").path("total").asLong()).isZero();
        assertVisible(search(member, kw), MESSAGE, teamMessage, 1);
    }

    @Nested
    @DisplayName("論理削除・アーカイブ（A5）")
    class Deleted {

        @Test
        @DisplayName("AC-A5(MESSAGE): 論理削除済みメッセージは出ない")
        void AC_A5_MESSAGE_削除済みメッセージは出ない() throws Exception {
            Long team = privateTeam("D-" + kw);
            Long me = viewer("a5-me");
            teamMember(me, team);
            Long channel = teamChannel(ChannelType.TEAM_PUBLIC, team, me);
            Long alive = message(channel, me, kw + " 生存");
            Long deleted = message(channel, me, kw + " 削除済み");
            nativeUpdate("UPDATE chat_messages SET deleted_at = NOW() WHERE id = :id", deleted);

            JsonNode body = search(me, kw, MESSAGE);
            assertThat(resultIds(body, MESSAGE)).containsExactly(alive);
            assertThat(typeCount(body, MESSAGE)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A5(MESSAGE): 論理削除済み・アーカイブ済みチャンネルのメッセージは参加者にも出ない")
        void AC_A5_MESSAGE_削除済みアーカイブ済みチャンネルは出ない() throws Exception {
            Long team = privateTeam("DC-" + kw);
            Long me = viewer("a5c-me");
            teamMember(me, team);
            Long deletedCh = teamChannel(ChannelType.TEAM_PUBLIC, team, me);
            Long archivedCh = teamChannel(ChannelType.TEAM_PRIVATE, team, me);
            Long liveCh = teamChannel(ChannelType.TEAM_PUBLIC, team, me);
            message(deletedCh, me, kw + " 削除チャンネル");
            message(archivedCh, me, kw + " アーカイブチャンネル");
            Long live = message(liveCh, me, kw + " 生存チャンネル");
            nativeUpdate("UPDATE chat_channels SET deleted_at = NOW() WHERE id = :id", deletedCh);
            nativeUpdate("UPDATE chat_channels SET is_archived = TRUE WHERE id = :id", archivedCh);

            JsonNode body = search(me, kw, MESSAGE);
            assertThat(resultIds(body, MESSAGE)).containsExactly(live);
            assertThat(typeCount(body, MESSAGE)).isEqualTo(1);
            assertThat(typeCount(search(me, kw), MESSAGE)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-A5(MESSAGE): 論理削除済み・アーカイブ済みのチーム／組織のチャンネルのメッセージは参加者にも出ない")
        void AC_A5_MESSAGE_削除済みスコープは出ない() throws Exception {
            Long deletedTeam = privateTeam("DT-" + kw);
            Long archivedTeam = privateTeam("AT-" + kw);
            Long deletedOrg = privateOrg("DO-" + kw);
            Long archivedOrg = privateOrg("AO-" + kw);
            Long liveTeam = privateTeam("LT-" + kw);
            Long me = viewer("a5s-me");
            teamMember(me, deletedTeam);
            teamMember(me, archivedTeam);
            orgMember(me, deletedOrg);
            orgMember(me, archivedOrg);
            teamMember(me, liveTeam);
            message(teamChannel(ChannelType.TEAM_PUBLIC, deletedTeam, me), me, kw + " 削除チーム");
            message(teamChannel(ChannelType.TEAM_PUBLIC, archivedTeam, me), me, kw + " アーカイブチーム");
            message(orgChannel(ChannelType.ORG_PUBLIC, deletedOrg, me), me, kw + " 削除組織");
            message(orgChannel(ChannelType.ORG_PUBLIC, archivedOrg, me), me, kw + " アーカイブ組織");
            Long live = message(teamChannel(ChannelType.TEAM_PUBLIC, liveTeam, me), me, kw + " 生存チーム");
            softDeleteTeam(deletedTeam);
            archiveTeam(archivedTeam);
            softDeleteOrg(deletedOrg);
            archiveOrg(archivedOrg);

            JsonNode body = search(me, kw, MESSAGE);
            assertThat(resultIds(body, MESSAGE)).containsExactly(live);
            assertThat(typeCount(body, MESSAGE)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-M1 / M2 / M3 / 対象外種別
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MESSAGE 固有（M1 / M2 / M3）")
    class MessageSpecific {

        @Test
        @DisplayName("AC-M1: 所属チームの未参加の公開チャンネル・未参加の非公開チャンネルのメッセージは出ない。参加済みの公開・非公開チャンネルのものは出る")
        void AC_M1_未参加チャンネルは出ない() throws Exception {
            Long team = privateTeam("M1-" + kw);
            Long org = privateOrg("M1O-" + kw);
            Long author = viewer("m1-author");
            teamMember(author, team);
            orgMember(author, org);
            Long me = viewer("m1-me");
            teamMember(me, team);
            orgMember(me, org);
            Long notJoinedPublic = teamChannel(ChannelType.TEAM_PUBLIC, team, author);
            Long notJoinedPrivate = teamChannel(ChannelType.TEAM_PRIVATE, team, author);
            Long notJoinedOrgPublic = orgChannel(ChannelType.ORG_PUBLIC, org, author);
            Long joinedPrivate = teamChannel(ChannelType.TEAM_PRIVATE, team, author, me);
            Long joinedPublic = teamChannel(ChannelType.TEAM_PUBLIC, team, author, me);
            Long joinedOrgPrivate = orgChannel(ChannelType.ORG_PRIVATE, org, author, me);
            Set<Long> invisible = Set.of(
                    message(notJoinedPublic, author, kw + " 未参加の公開"),
                    message(notJoinedPrivate, author, kw + " 未参加の非公開"),
                    message(notJoinedOrgPublic, author, kw + " 未参加の組織公開"));
            Long inPrivate = message(joinedPrivate, author, kw + " 参加済みの非公開");
            Long inPublic = message(joinedPublic, author, kw + " 参加済みの公開");
            Long inOrgPrivate = message(joinedOrgPrivate, author, kw + " 参加済みの組織非公開");

            JsonNode body = search(me, kw, MESSAGE);
            assertThat(resultIds(body, MESSAGE)).containsExactlyInAnyOrder(inPrivate, inPublic, inOrgPrivate)
                    .doesNotContainAnyElementsOf(invisible);
            assertThat(typeCount(body, MESSAGE)).isEqualTo(3);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(3);
            assertThat(typeCount(search(me, kw), MESSAGE)).isEqualTo(3);
        }

        @ParameterizedTest(name = "{0} のメッセージは参加者本人にも出ない")
        @EnumSource(value = ChannelType.class, names = {"DM", "GROUP_DM"})
        @DisplayName("AC-M2: DM / GROUP_DM のメッセージは参加者本人にも出ない（チームの team_id が付いていても出ない）")
        void AC_M2_DMは出ない(ChannelType dmType) throws Exception {
            Long team = privateTeam("M2-" + kw);
            Long me = viewer("m2-me");
            Long peer = viewer("m2-peer");
            Long third = viewer("m2-third");
            teamMember(me, team);
            teamMember(peer, team);
            teamMember(third, team);
            Long dm = channel(ChatChannelEntity.builder().channelType(dmType).createdBy(me), me, peer, third);
            Long dmWithTeam = channel(ChatChannelEntity.builder().channelType(dmType).teamId(team).createdBy(me),
                    me, peer, third);
            message(dm, peer, kw + " DM 本文");
            message(dmWithTeam, peer, kw + " team_id 付き DM 本文");
            message(dm, me, kw + " 自分が送った DM");

            assertNothingVisible(search(me, kw), MESSAGE);
            JsonNode detail = search(me, kw, MESSAGE);
            assertNothingVisible(detail, MESSAGE);
            assertThat(detail.path("meta").path("total").asLong()).isZero();
            assertNothingVisible(search(peer, kw), MESSAGE);
        }

        @ParameterizedTest(name = "{0} のメッセージはメンバー行があっても出ない")
        @EnumSource(value = ChannelType.class,
                names = {"VILLAGE_LOBBY", "EVENT_CHAT", "TOURNAMENT_CHAT", "TOURNAMENT_DIVISION_CHAT"})
        @DisplayName("対象外チャンネル種別: VILLAGE_LOBBY / EVENT_CHAT / TOURNAMENT* は参加者本人にも出ない（所属チームの team_id が付いていても出ない）")
        void 対象外種別は出ない(ChannelType excluded) throws Exception {
            Long team = privateTeam("EX-" + kw);
            Long me = viewer("ex-me");
            teamMember(me, team);
            Long bare = channel(ChatChannelEntity.builder().channelType(excluded).name("対象外 " + excluded)
                    .createdBy(me), me);
            Long withTeam = channel(ChatChannelEntity.builder().channelType(excluded).name("対象外 " + excluded)
                    .teamId(team).createdBy(me), me);
            message(bare, me, kw + " 対象外種別 " + excluded);
            message(withTeam, me, kw + " team_id 付き対象外種別 " + excluded);
            Long control = message(teamChannel(ChannelType.TEAM_PUBLIC, team, me), me, kw + " 対照");

            JsonNode body = search(me, kw, MESSAGE);
            assertThat(resultIds(body, MESSAGE)).containsExactly(control);
            assertThat(typeCount(body, MESSAGE)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-M3: 退出（leave）後は、そのチャンネルのメッセージが出ない（退出前は出る）")
        void AC_M3_退出後は出ない() throws Exception {
            Long team = privateTeam("M3L-" + kw);
            Long author = viewer("m3l-author");
            teamMember(author, team);
            Long me = viewer("m3l-me");
            teamMember(me, team);
            Long channel = teamChannel(ChannelType.TEAM_PUBLIC, team, author, me);
            Long msg = message(channel, author, kw + " 退出前の発言");

            assertVisible(search(me, kw), MESSAGE, msg, 1);

            removeChannelMember(channel, me);

            assertNothingVisible(search(me, kw), MESSAGE);
            JsonNode detail = search(me, kw, MESSAGE);
            assertNothingVisible(detail, MESSAGE);
            assertThat(detail.path("meta").path("total").asLong()).isZero();
            assertVisible(search(author, kw), MESSAGE, msg, 1);
        }

        @Test
        @DisplayName("AC-M3: 除名（kick）後は、非公開チャンネルのメッセージが出ない（残るメンバーには出る）")
        void AC_M3_除名後は出ない() throws Exception {
            Long team = privateTeam("M3K-" + kw);
            Long owner = viewer("m3k-owner");
            teamMember(owner, team);
            Long kicked = viewer("m3k-kicked");
            teamMember(kicked, team);
            Long channel = teamChannel(ChannelType.TEAM_PRIVATE, team, owner, kicked);
            Long msg = message(channel, owner, kw + " 除名後も残る発言");

            assertVisible(search(kicked, kw, MESSAGE), MESSAGE, msg, 1);

            removeChannelMember(channel, kicked);

            assertNothingVisible(search(kicked, kw), MESSAGE);
            assertNothingVisible(search(kicked, kw, MESSAGE), MESSAGE);
            assertVisible(search(owner, kw, MESSAGE), MESSAGE, msg, 1);
        }

        @Test
        @DisplayName("AC-M3: システムメッセージ・INVITE_CARD・予約未送信（未来予約／配信待ち）・削除済みは参加者にも送信者本人にも出ない。配信済みの予約メッセージは出る")
        void AC_M3_対象外メッセージは出ない() throws Exception {
            Long team = privateTeam("M3X-" + kw);
            Long me = viewer("m3x-me");
            teamMember(me, team);
            Long peer = viewer("m3x-peer");
            teamMember(peer, team);
            Long channel = teamChannel(ChannelType.TEAM_PUBLIC, team, me, peer);
            Long normal = message(channel, me, kw + " 通常");
            Set<Long> invisible = new HashSet<>();
            invisible.add(saveMessage(ChatMessageEntity.builder().channelId(channel).senderId(me)
                    .body(kw + " システム").isSystem(true)));
            invisible.add(saveMessage(ChatMessageEntity.builder().channelId(channel).senderId(me)
                    .body(kw + " 招待カード").messageType("INVITE_CARD").inviteTokenId(1L)));
            invisible.add(saveMessage(ChatMessageEntity.builder().channelId(channel).senderId(me)
                    .body(kw + " 未来予約").scheduledAt(LocalDateTime.now().plusDays(3))));
            invisible.add(saveMessage(ChatMessageEntity.builder().channelId(channel).senderId(me)
                    .body(kw + " 配信待ちの予約").scheduledAt(LocalDateTime.now().minusMinutes(5))));
            Long deleted = message(channel, me, kw + " 削除済み");
            nativeUpdate("UPDATE chat_messages SET deleted_at = NOW() WHERE id = :id", deleted);
            invisible.add(deleted);
            Long delivered = saveMessage(ChatMessageEntity.builder().channelId(channel).senderId(me)
                    .body(kw + " 配信済みの予約").scheduledAt(LocalDateTime.now().minusHours(1))
                    .scheduledSentAt(LocalDateTime.now().minusMinutes(59)));

            for (Long viewerId : List.of(me, peer)) {
                JsonNode body = search(viewerId, kw, MESSAGE);
                assertThat(resultIds(body, MESSAGE)).containsExactlyInAnyOrder(normal, delivered)
                        .doesNotContainAnyElementsOf(invisible);
                assertThat(typeCount(body, MESSAGE)).isEqualTo(2);
                assertThat(body.path("meta").path("total").asLong()).isEqualTo(2);
                assertThat(typeCount(search(viewerId, kw), MESSAGE)).isEqualTo(2);
            }
        }

        @Test
        @DisplayName("authorId 指定: その送信者の可視メッセージだけが出て、不可視チャンネルのものは authorId 指定でも出ない")
        void authorIdでも不可視は出ない() throws Exception {
            Long team = privateTeam("AU-" + kw);
            Long hiddenTeam = privateTeam("AUH-" + kw);
            Long author = viewer("au-author");
            teamMember(author, team);
            teamMember(author, hiddenTeam);
            Long other = viewer("au-other");
            teamMember(other, team);
            Long me = viewer("au-me");
            teamMember(me, team);
            Long shared = teamChannel(ChannelType.TEAM_PUBLIC, team, author, other, me);
            Long byAuthor = message(shared, author, kw + " 著者の発言");
            message(shared, other, kw + " 別人の発言");
            message(teamChannel(ChannelType.TEAM_PUBLIC, hiddenTeam, author), author, kw + " 著者の別チーム発言");
            message(teamChannel(ChannelType.TEAM_PRIVATE, team, author), author, kw + " 著者の未参加チャンネル発言");

            JsonNode body = search(me, Map.of("q", kw, "type", MESSAGE, "authorId", author.toString()));
            assertThat(resultIds(body, MESSAGE)).containsExactly(byAuthor);
            assertThat(typeCount(body, MESSAGE)).isEqualTo(1);
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-A6 / AC-C8: 件数と結果の同一述語・頭打ちなし
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("件数（A6 / C8）")
    class Counts {

        @Test
        @DisplayName("AC-A6(MESSAGE): 不可視メッセージを足しても typeCounts.MESSAGE は不変。詳細ビューの meta.total と typeCounts.MESSAGE が一致")
        void AC_A6_MESSAGE_不可視を足しても件数不変() throws Exception {
            Long mine = privateTeam("A6M-" + kw);
            Long foreign = privateTeam("A6F-" + kw);
            Long me = viewer("a6-me");
            teamMember(me, mine);
            Long stranger = viewer("a6-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            Long myChannel = teamChannel(ChannelType.TEAM_PUBLIC, mine, me, stranger);
            message(myChannel, me, kw + " 1");
            message(myChannel, stranger, kw + " 2");

            JsonNode before = search(me, kw);
            assertThat(typeCount(before, MESSAGE)).isEqualTo(2);

            message(teamChannel(ChannelType.TEAM_PUBLIC, foreign, stranger), stranger, kw + " 他チーム");
            message(teamChannel(ChannelType.TEAM_PUBLIC, mine, stranger), stranger, kw + " 未参加の公開");
            message(channel(ChatChannelEntity.builder().channelType(ChannelType.DM).createdBy(me), me, stranger),
                    stranger, kw + " DM");
            saveMessage(ChatMessageEntity.builder().channelId(myChannel).senderId(me).body(kw + " システム")
                    .isSystem(true));

            JsonNode after = search(me, kw);
            JsonNode detail = search(me, kw, MESSAGE);
            assertThat(typeCount(after, MESSAGE)).isEqualTo(typeCount(before, MESSAGE));
            assertThat(detail.path("meta").path("total").asLong()).isEqualTo(typeCount(detail, MESSAGE));
            assertThat(typeCount(detail, MESSAGE)).isEqualTo(typeCount(after, MESSAGE));
            assertThat(resultIds(detail, MESSAGE)).hasSize(2);
        }

        @Test
        @DisplayName("AC-C8(MESSAGE): 可視11件・不可視3件 → typeCounts.MESSAGE=11（頭打ちしない）。概要は5件、詳細のページ送りで11件を過不足なく返す")
        void AC_C8_MESSAGE_真の件数とページ送り() throws Exception {
            Long mine = privateTeam("C8M-" + kw);
            Long myOrg = privateOrg("C8O-" + kw);
            Long foreign = privateTeam("C8F-" + kw);
            Long me = viewer("c8-me");
            teamMember(me, mine);
            orgMember(me, myOrg);
            Long stranger = viewer("c8-stranger");
            teamMember(stranger, foreign);
            teamMember(stranger, mine);
            Long teamCh = teamChannel(ChannelType.TEAM_PUBLIC, mine, me, stranger);
            Long privCh = teamChannel(ChannelType.TEAM_PRIVATE, mine, me);
            Long orgCh = orgChannel(ChannelType.ORG_PUBLIC, myOrg, me);
            Set<Long> visible = new HashSet<>();
            for (int i = 0; i < 5; i++) {
                visible.add(message(teamCh, i % 2 == 0 ? me : stranger, kw + " チーム" + i));
            }
            for (int i = 0; i < 4; i++) {
                visible.add(message(privCh, me, kw + " 非公開" + i));
            }
            for (int i = 0; i < 2; i++) {
                visible.add(message(orgCh, me, kw + " 組織" + i));
            }
            Set<Long> invisible = new HashSet<>();
            invisible.add(message(teamChannel(ChannelType.TEAM_PUBLIC, foreign, stranger), stranger, kw + " 他チーム"));
            invisible.add(message(teamChannel(ChannelType.TEAM_PRIVATE, mine, stranger), stranger, kw + " 未参加"));
            invisible.add(message(channel(ChatChannelEntity.builder().channelType(ChannelType.GROUP_DM)
                    .createdBy(me), me, stranger), stranger, kw + " グループDM"));

            JsonNode overview = search(me, kw);
            assertThat(typeCount(overview, MESSAGE)).isEqualTo(11);
            assertThat(resultIds(overview, MESSAGE)).hasSize(5).doesNotContainAnyElementsOf(invisible);

            List<Long> all = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                JsonNode detail = search(me, Map.of("q", kw, "type", MESSAGE, "page", String.valueOf(page),
                        "size", "5"));
                assertThat(detail.path("meta").path("total").asLong()).isEqualTo(11);
                assertThat(detail.path("meta").path("totalPages").asInt()).isEqualTo(3);
                assertThat(typeCount(detail, MESSAGE)).isEqualTo(11);
                all.addAll(resultIds(detail, MESSAGE));
            }
            assertThat(all).as("ページ間で重複も欠落もなく、不可視は混ざらない").containsExactlyInAnyOrderElementsOf(visible);

            JsonNode big = search(me, Map.of("q", kw, "type", MESSAGE, "size", "50"));
            assertThat(resultIds(big, MESSAGE)).hasSize(11).doesNotContainAnyElementsOf(invisible);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-C11 / C12 / URL / createdAt
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("入出力のエスケープ・遷移先・作成日時")
    class EscapingUrlAndTime {

        @ParameterizedTest(name = "q に [{0}] を含めても 200 で、不可視のメッセージは混ざらない")
        @ValueSource(strings = {"+", "-", "*", "\"", "(", ")", "~", "<", ">", "@", "%", "_", "\\", "+-*\"()~<>@%_\\"})
        @DisplayName("AC-C11(MESSAGE): FULLTEXT BOOLEAN 演算子や % _ \\ を含めても 500 にならない")
        void AC_C11_MESSAGE_演算子でも500にならない(String op) throws Exception {
            Long foreign = privateTeam("C11F-" + kw);
            Long mine = privateTeam("C11M-" + kw);
            Long stranger = viewer("c11-stranger");
            teamMember(stranger, foreign);
            Long me = viewer("c11-me");
            teamMember(me, mine);
            Long invisible = message(teamChannel(ChannelType.TEAM_PUBLIC, foreign, stranger), stranger,
                    op + kw + op + " 他チーム");
            Long visible = message(teamChannel(ChannelType.TEAM_PUBLIC, mine, me), me, op + kw + op + " 自チーム");

            for (String q : List.of(kw + op, op + kw + op, kw + " " + op)) {
                JsonNode body = search(me, Map.of("q", q, "type", MESSAGE));
                assertThat(resultIds(body, MESSAGE)).doesNotContain(invisible);
                assertThat(typeCount(body, MESSAGE)).isLessThanOrEqualTo(1);
                assertThat(body.path("data").path("timedOutTypes").toString()).doesNotContain(MESSAGE);
            }
            assertVisible(search(me, kw), MESSAGE, visible, 1);
        }

        @Test
        @DisplayName("AC-C12(MESSAGE): 本文の <script> は snippet で HTML エスケープされ、一致箇所だけが <mark>。snippet は200文字以下")
        void AC_C12_MESSAGE_snippetエスケープ() throws Exception {
            Long team = privateTeam("C12-" + kw);
            Long me = viewer("c12-me");
            teamMember(me, team);
            String body = "<script>alert(1)</script> 前文 " + kw + " 本文<b>太字</b>" + "あ".repeat(300);
            Long id = message(teamChannel(ChannelType.TEAM_PUBLIC, team, me), me, body);

            JsonNode hit = hitOf(search(me, kw, MESSAGE), id);
            String snippet = hit.path("snippet").asText();
            assertThat(snippet).contains("<mark>" + kw + "</mark>");
            assertThat(snippet).doesNotContain("<script>").doesNotContain("<b>");
            assertThat(snippet).contains("&lt;script&gt;");
            assertThat(snippet.replace("<mark>", "").replace("</mark>", "")).doesNotContain("<")
                    .hasSizeLessThanOrEqualTo(200);
            assertThat(snippet.split("<mark>", -1)).as("<mark> は一致箇所の1か所だけ").hasSize(2);
        }

        @Test
        @DisplayName("MESSAGE の結果は type=MESSAGE・url=/chat?channel={channelId}（メッセージ ID ではなくチャンネル ID）")
        void URLは_chat_channel() throws Exception {
            Long team = privateTeam("URL-" + kw);
            Long me = viewer("url-me");
            teamMember(me, team);
            Long channel = teamChannel(ChannelType.TEAM_PUBLIC, team, me);
            Long id = message(channel, me, kw + " 遷移先");

            JsonNode overview = search(me, kw);
            JsonNode hit = hitOf(overview, id);
            assertThat(hit.path("type").asText()).isEqualTo(MESSAGE);
            assertThat(hit.path("url").asText()).isEqualTo("/chat?channel=" + channel);
            assertThat(typeCount(overview, MESSAGE)).as("MESSAGE の typeCounts は 0 固定ではなく実数").isEqualTo(1);
        }

        @Test
        @DisplayName("createdAt: JST 09:00 に送信したメッセージの createdAt は 09:00+09:00（= DB の UTC 格納値 00:00Z と同一瞬間）")
        void createdAtは同一瞬間() throws Exception {
            Long team = privateTeam("CA-" + kw);
            Long me = viewer("ca-me");
            teamMember(me, team);
            Long id = message(teamChannel(ChannelType.TEAM_PUBLIC, team, me), me, kw + " 作成日時");
            jdbc.update("UPDATE chat_messages SET created_at = ? WHERE id = ?", "2026-03-10 00:00:00", id);

            JsonNode hit = hitOf(search(me, kw, MESSAGE), id);
            String text = hit.path("createdAt").asText();
            assertThat(text).as("createdAt が出ていること（%s）", hit).isNotBlank();
            assertThat(OffsetDateTime.parse(text).toInstant()).isEqualTo(Instant.parse("2026-03-10T00:00:00Z"));
        }
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    /** チームスコープのチャンネル（TEAM_PUBLIC / TEAM_PRIVATE）を作り、members をメンバー行として入れる。 */
    private Long teamChannel(ChannelType type, Long teamId, Long... members) {
        return channel(ChatChannelEntity.builder().channelType(type).teamId(teamId).name("ch-" + kw)
                .isPrivate(type == ChannelType.TEAM_PRIVATE).createdBy(members.length > 0 ? members[0] : null),
                members);
    }

    /** 組織スコープのチャンネル（ORG_PUBLIC / ORG_PRIVATE）を作り、members をメンバー行として入れる。 */
    private Long orgChannel(ChannelType type, Long orgId, Long... members) {
        return channel(ChatChannelEntity.builder().channelType(type).organizationId(orgId).name("ch-" + kw)
                .isPrivate(type == ChannelType.ORG_PRIVATE).createdBy(members.length > 0 ? members[0] : null),
                members);
    }

    private Long channel(ChatChannelEntity.ChatChannelEntityBuilder<?, ?> builder, Long... members) {
        Long id = inTx(() -> {
            ChatChannelEntity channel = builder.build();
            em.persist(channel);
            em.flush();
            for (int i = 0; i < members.length; i++) {
                em.persist(ChatChannelMemberEntity.builder().channelId(channel.getId()).userId(members[i])
                        .role(i == 0 ? ChannelMemberRole.OWNER : ChannelMemberRole.MEMBER).build());
            }
            return channel.getId();
        });
        createdChannelIds.add(id);
        return id;
    }

    /** 通常の本文メッセージ（TEXT・非システム・即時送信）。 */
    private Long message(Long channelId, Long senderId, String body) {
        return saveMessage(ChatMessageEntity.builder().channelId(channelId).senderId(senderId).body(body));
    }

    private Long saveMessage(ChatMessageEntity.ChatMessageEntityBuilder<?, ?> builder) {
        return inTx(() -> {
            ChatMessageEntity message = builder.build();
            em.persist(message);
            em.flush();
            return message.getId();
        });
    }

    /** 退出・除名の表現: {@code ChatMemberService#removeMember} と同じくメンバー行を物理削除する。 */
    private void removeChannelMember(Long channelId, Long userId) {
        inTx(() -> em.createNativeQuery("DELETE FROM chat_channel_members WHERE channel_id = :c AND user_id = :u")
                .setParameter("c", channelId).setParameter("u", userId).executeUpdate());
    }

    private JsonNode hitOf(JsonNode body, Long id) {
        for (JsonNode r : body.path("data").path("results")) {
            if (MESSAGE.equals(r.path("type").asText()) && r.path("id").asLong() == id) {
                return r;
            }
        }
        throw new AssertionError("MESSAGE id=" + id + " が結果に無い（応答: " + body + "）");
    }

    private void ensureFulltextIndex() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS "
                        + "WHERE table_schema = DATABASE() AND table_name = 'chat_messages' "
                        + "AND index_name = 'ft_msg_body'",
                Integer.class);
        if (count == null || count == 0) {
            jdbc.execute("ALTER TABLE chat_messages ADD FULLTEXT INDEX ft_msg_body (body) WITH PARSER ngram");
        }
    }
}
