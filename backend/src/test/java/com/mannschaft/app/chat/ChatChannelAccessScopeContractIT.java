package com.mannschaft.app.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventVisibility;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave 6 — chat ドメイン「チャンネル閲覧・参加・投稿」認可契約テスト（試練 / red 先行）。
 *
 * <p><b>是正対象の欠陥</b>: {@code ChatMessageService.checkChannelViewAccess} は Javadoc に自ら
 * 「通常チャンネルでは no-op」と明記されているとおり {@code TOURNAMENT_CHAT}/
 * {@code TOURNAMENT_DIVISION_CHAT} でしか認可せず、通常チャンネル（チーム／組織／DM／グループDM）は
 * 認可ゼロで素通ししていた。加えて {@code ChatMemberService.joinChannel} は
 * {@code channelType}/{@code isPrivate}/スコープ所属を一切見ず、
 * {@code ChatChannelService.getChannel} は非メンバーにも DM 相手を返し、
 * {@code convertToGroup} は呼出者 ID すら受け取っていなかった。</p>
 *
 * <p><b>採用した意味論の根拠（憶測ではない）</b>: WebSocket 購読認可の正準実装
 * {@code ChatChannelSubscriptionInterceptor.MEMBERSHIP_GATED_TYPES}（:84-90）が
 * 「{@code chat_channel_members} 行でメンバーシップが定義される種別」として
 * TEAM_PUBLIC / TEAM_PRIVATE / ORG_PUBLIC / ORG_PRIVATE / DM / GROUP_DM を列挙し、
 * VILLAGE_LOBBY / EVENT_CHAT / TOURNAMENT_* は「別ドメインのアクセスモデルで管理されるため素通し」と
 * 規定している。REST 側も同じ境界に揃える（{@code ChatMessageService#getActiveThreads} :385-389 の
 * 既存判定とも一致）。よって <b>TEAM_PUBLIC であってもチャンネルメンバーであることを要する</b>
 * （「公開」はチームメンバーが自分で参加できることを意味し、未参加での本文閲覧は許さない）。</p>
 *
 * <p>金型: {@code PhotoAlbumListPhotosScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)}
 * + 実 MySQL Testcontainers）。越境（存在を知り得ない利用者）は CMP-261105-1507 で不在と同一の 404（CHAT_001）へ改めた。公開チャンネルの未参加チームメンバー（存在は既知）は 403（CHAT_005）のまま。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("chat ドメイン チャンネル閲覧・参加・投稿 認可契約テスト（試練・Wave6）")
class ChatChannelAccessScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatChannelRepository channelRepository;

    @Autowired
    private ChatChannelMemberRepository memberRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository villageMembershipRepository;

    @Autowired
    private EventRepository eventRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;

    /** チャンネルメンバー かつ チームAメンバー。 */
    private Long channelMemberId;
    /** チームAメンバーだが「チャンネル未参加」。TEAM_PUBLIC のメンバーシップゲートを実証する主役。 */
    private Long teamOnlyMemberId;
    /** DM の相手（channelMemberId と 1:1 DM を持つ）。 */
    private Long dmPartnerId;
    /** チームにもチャンネルにも属さない完全な部外者。 */
    private Long outsiderId;

    private Long teamPublicChannelId;
    private Long teamPrivateChannelId;
    private Long dmChannelId;

    // 裏目付A: VILLAGE_LOBBY / EVENT_CHAT の素通し根治用データ
    /** 対象村の現役 USER メンバー（VILLAGE_LOBBY を閲覧できるべき主役）。 */
    private Long villageMemberId;
    private UUID villageId;
    private Long villageLobbyChannelId;
    /** EVENT_CHAT のイベントスコープ（teamA）メンバー（チャンネルメンバー行は持たない）。 */
    private Long eventScopeMemberId;
    private Long eventId;
    private Long eventChatChannelId;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("認可契約チャットチームA");

        channelMemberId = insertUser("chat-authz-channel-member@example.com");
        teamOnlyMemberId = insertUser("chat-authz-team-only@example.com");
        dmPartnerId = insertUser("chat-authz-dm-partner@example.com");
        outsiderId = insertUser("chat-authz-outsider@example.com");

        MembershipTestHelper.insertMembership(em, channelMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, teamOnlyMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        // dmPartnerId / outsiderId はチームAに所属しない。

        teamPublicChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(teamAId)
                .name("認可契約 公開チャンネル")
                .isPrivate(false)
                .createdBy(channelMemberId)
                .build()).getId();

        teamPrivateChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PRIVATE)
                .teamId(teamAId)
                .name("認可契約 非公開チャンネル")
                .isPrivate(true)
                .createdBy(channelMemberId)
                .build()).getId();

        dmChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.DM)
                .createdBy(channelMemberId)
                .build()).getId();

        // チャンネルメンバー行: channelMemberId のみ（teamOnlyMemberId は意図的に未参加）
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(teamPublicChannelId).userId(channelMemberId)
                .role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(teamPrivateChannelId).userId(channelMemberId)
                .role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(dmChannelId).userId(channelMemberId)
                .role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(dmChannelId).userId(dmPartnerId)
                .role(ChannelMemberRole.MEMBER).build());

        messageRepository.save(ChatMessageEntity.builder()
                .channelId(dmChannelId).senderId(channelMemberId)
                .body("DM の秘密の本文").build());
        messageRepository.save(ChatMessageEntity.builder()
                .channelId(teamPublicChannelId).senderId(channelMemberId)
                .body("公開チャンネルの本文").build());

        setUpVillageLobby();
        setUpEventChat();

        em.flush();
        em.clear();
    }

    /**
     * VILLAGE_LOBBY 認可用データ。村・村メンバー（USER 主体）・村ロビーチャンネルを用意する。
     * outsiderId は村メンバーではない（非メンバーの主役）。
     */
    private void setUpVillageLobby() {
        villageMemberId = insertUser("chat-authz-village-member@example.com");

        VillageEntity village = villageRepository.save(VillageEntity.builder()
                .slug("chat-authz-village-" + System.nanoTime())
                .name("認可契約テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .build());
        villageId = village.getId();

        villageMembershipRepository.save(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(villageMemberId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build());

        villageLobbyChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.VILLAGE_LOBBY)
                .villageId(villageId)
                .name("認可契約 村ロビー")
                .isPrivate(false)
                .createdBy(villageMemberId)
                .build()).getId();

        messageRepository.save(ChatMessageEntity.builder()
                .channelId(villageLobbyChannelId).senderId(villageMemberId)
                .body("村ロビーの本文").build());
    }

    /**
     * EVENT_CHAT 認可用データ。teamA スコープのイベントと EVENT_CHAT チャンネルを用意する。
     * イベントスコープのメンバー = teamA メンバー（{@code eventScopeMemberId}）。
     * outsiderId は teamA 非所属のためイベント非参加者（非メンバーの主役）。
     */
    private void setUpEventChat() {
        eventScopeMemberId = insertUser("chat-authz-event-member@example.com");
        MembershipTestHelper.insertMembership(em, eventScopeMemberId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        EventEntity event = eventRepository.save(EventEntity.builder()
                .scopeType(EventScopeType.TEAM).scopeId(teamAId)
                .slug("chat-authz-event-" + System.nanoTime())
                .status(EventStatus.REGISTRATION_OPEN)
                .visibility(EventVisibility.MEMBERS_ONLY)
                .build());
        eventId = event.getId();

        eventChatChannelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.EVENT_CHAT)
                .teamId(teamAId)
                .name("認可契約 イベントチャット")
                .isPrivate(false)
                .sourceType("EVENT")
                .sourceId(eventId)
                .createdBy(eventScopeMemberId)
                .build()).getId();

        messageRepository.save(ChatMessageEntity.builder()
                .channelId(eventChatChannelId).senderId(eventScopeMemberId)
                .body("イベントチャットの本文").build());
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージ一覧（GET /chat/channels/{id}/messages）— 最重要
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メッセージ一覧(listMessages)")
    class ListMessages {

        @Test
        @DisplayName("部外者による他人のDMメッセージ一覧取得は404（CHAT_001・不在と同一・DM本文の総当り閲覧を閉塞）")
        void 部外者のDMメッセージ一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", dmChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チームメンバーでもチャンネル未参加ならTEAM_PUBLICのメッセージ一覧は403（メンバーシップゲート）")
        void チャンネル未参加のチームメンバーは403() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPublicChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("部外者によるTEAM_PRIVATEのメッセージ一覧取得は不在と同一の404")
        void 部外者の非公開チャンネルメッセージ一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPrivateChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チャンネルメンバーのメッセージ一覧取得は200（非回帰）")
        void チャンネルメンバーのメッセージ一覧は200() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", teamPublicChannelId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("DM相手のメッセージ一覧取得は200（非回帰）")
        void DM相手のメッセージ一覧は200() throws Exception {
            setAuthentication(dmPartnerId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", dmChannelId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("不在チャンネルのメッセージ一覧取得は404（CHAT_001・従来はWARN既定の400だった）")
        void 不在チャンネルのメッセージ一覧は404() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", 999_999_999L))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージ検索・アクティブスレッド（同じ閲覧認可を通す派生経路）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メッセージ検索・スレッド一覧")
    class SearchAndThreads {

        @Test
        @DisplayName("部外者による他人のDMメッセージ検索は不在と同一の404（本文漏洩の裏口を閉塞）")
        void 部外者のDMメッセージ検索は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages/search", dmChannelId)
                            .param("keyword", "秘密"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("部外者によるアクティブスレッド一覧取得は不在と同一の404")
        void 部外者のスレッド一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", dmChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チャンネルメンバーのスレッド一覧取得は200（非回帰）")
        void メンバーのスレッド一覧は200() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", teamPublicChannelId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チャンネル詳細（GET /chat/channels/{id}）— DM 相手の関係グラフ漏洩
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チャンネル詳細(getChannel)")
    class GetChannel {

        @Test
        @DisplayName("部外者による他人のDM詳細取得は不在と同一の404（DM相手のuserId・表示名・アバターの漏洩を閉塞）")
        void 部外者のDM詳細は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}", dmChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チャンネル未参加のチームメンバーによるTEAM_PUBLIC詳細取得は403")
        void 未参加チームメンバーのチャンネル詳細は403() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}", teamPublicChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("CHAT_005"));
        }

        @Test
        @DisplayName("DM当事者の詳細取得は200で相手情報を返す（非回帰）")
        void DM当事者の詳細は200() throws Exception {
            setAuthentication(dmPartnerId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}", dmChannelId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.dmPartner.userId").value(channelMemberId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チャンネル参加（POST /chat/channels/{id}/join）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チャンネル参加(joinChannel)")
    class JoinChannel {

        @Test
        @DisplayName("他人のDMへの自己参加は不在と同一の404（DM/GROUP_DMは自己参加不可）")
        void 他人のDMへの自己参加は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", dmChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チームメンバーであっても非公開チャンネルへの自己参加は不在と同一の404（招待制）")
        void 非公開チャンネルへの自己参加は404() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", teamPrivateChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("非チームメンバーによる公開チャンネルへの自己参加は不在と同一の404（CHAT_001・他スコープの利用者は存在を知り得ない）")
        void 非チームメンバーの公開チャンネル参加は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", teamPublicChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チームメンバーによる公開チャンネルへの自己参加は201（非回帰・公開の本来の意味）")
        void チームメンバーの公開チャンネル参加は201() throws Exception {
            setAuthentication(teamOnlyMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/join", teamPublicChannelId))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // メッセージ送信・DM→グループDM変換（書き込み系）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("書き込み系(sendMessage / convertToGroup)")
    class WriteOperations {

        @Test
        @DisplayName("部外者による他人のDMへのメッセージ送信は不在と同一の404（投稿バイパスを閉塞）")
        void 部外者のDMへの送信は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", dmChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("チャンネルメンバーのメッセージ送信は201（非回帰）")
        void メンバーの送信は201() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", teamPublicChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(sendBody())))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("部外者による他人のDMのグループDM変換は不在と同一の404（callerId未受領の欠陥を閉塞）")
        void 部外者のグループDM変換は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/convert-to-group", dmChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("DMのOWNERによるグループDM変換は200（非回帰）")
        void OWNERのグループDM変換は200() throws Exception {
            setAuthentication(channelMemberId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/convert-to-group", dmChannelId))
                    .andExpect(status().isOk());
        }

        private Map<String, Object> sendBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("body", "認可契約テストの投稿");
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 裏目付A: VILLAGE_LOBBY / EVENT_CHAT の閲覧素通し根治
    //   旧実装は isMembershipGated() でない種別を無言 return で素通しし、
    //   村の非メンバー・イベント非参加者でも本文一覧・スレッド・検索・投稿が通っていた。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("村ロビー・イベントチャット認可(VILLAGE_LOBBY / EVENT_CHAT)")
    class VillageAndEventChat {

        // ---- AC-1a: 村の非メンバーは VILLAGE_LOBBY を閲覧できない ----

        @Test
        @DisplayName("AC-1a: 村の非メンバーによる村ロビーのメッセージ一覧は404（CHAT_001・不在と同一・素通し根治）")
        void 村非メンバーの村ロビーメッセージ一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", villageLobbyChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        // ---- AC-1b: イベント非参加者は EVENT_CHAT を閲覧できない ----

        @Test
        @DisplayName("AC-1b: イベント非参加者によるイベントチャットのメッセージ一覧は404（CHAT_001・不在と同一・素通し根治）")
        void イベント非参加者のイベントチャットメッセージ一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", eventChatChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        // ---- AC-1c: 正当な村メンバー / イベントスコープメンバーは従来どおり200（回帰防止） ----

        @Test
        @DisplayName("AC-1c: 村の現役メンバーによる村ロビーのメッセージ一覧は200（回帰防止）")
        void 村メンバーの村ロビーメッセージ一覧は200() throws Exception {
            setAuthentication(villageMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", villageLobbyChannelId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("AC-1c: イベントスコープのメンバーによるイベントチャットのメッセージ一覧は200（回帰防止）")
        void イベントスコープメンバーのイベントチャットメッセージ一覧は200() throws Exception {
            setAuthentication(eventScopeMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", eventChatChannelId))
                    .andExpect(status().isOk());
        }

        // ---- AC-1d: list / thread / search の全読取経路で非メンバー403（経路漏れ防止） ----

        @Test
        @DisplayName("AC-1d: 村の非メンバーによる村ロビーのスレッド一覧は不在と同一の404（経路漏れ防止）")
        void 村非メンバーの村ロビースレッド一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", villageLobbyChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("AC-1d: 村の非メンバーによる村ロビーのメッセージ検索は不在と同一の404（経路漏れ防止）")
        void 村非メンバーの村ロビーメッセージ検索は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages/search", villageLobbyChannelId)
                            .param("keyword", "本文"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("AC-1d: イベント非参加者によるイベントチャットのスレッド一覧は不在と同一の404（経路漏れ防止）")
        void イベント非参加者のイベントチャットスレッド一覧は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/threads", eventChatChannelId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("AC-1d: イベント非参加者によるイベントチャットのメッセージ検索は不在と同一の404（経路漏れ防止）")
        void イベント非参加者のイベントチャットメッセージ検索は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages/search", eventChatChannelId)
                            .param("keyword", "本文"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        // ---- 投稿経路も同じゲートを通す（非メンバーの書き込みバイパス閉塞） ----

        @Test
        @DisplayName("AC-1d: 村の非メンバーによる村ロビーへのメッセージ送信は不在と同一の404（投稿バイパス閉塞）")
        void 村非メンバーの村ロビー投稿は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", villageLobbyChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("body", "侵入投稿"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }

        @Test
        @DisplayName("AC-1d: イベント非参加者によるイベントチャットへのメッセージ送信は不在と同一の404（投稿バイパス閉塞）")
        void イベント非参加者のイベントチャット投稿は404() throws Exception {
            setAuthentication(outsiderId);
            mockMvc.perform(post("/api/v1/chat/channels/{id}/messages", eventChatChannelId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("body", "侵入投稿"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("CHAT_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // CMP-261116-0726: 大会連絡チャット（TOURNAMENT_CHAT / TOURNAMENT_DIVISION_CHAT）の存在秘匿・回帰固定
    //   チャット側のコードは本戦役で変えない（設計書 §2・§3「チャット（回帰のみ）」）。
    //   閲覧権なし → チャンネルID系は不在チャンネル（404 CHAT_001）、メッセージID系は不在メッセージ（404 CHAT_002）と
    //   本文全体まで一致。閲覧可・投稿不可は 403 TOUR_031。CHAT スペースの is_public で判定する（AC-17）。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CMP-261116-0726 大会連絡チャットの存在秘匿（AC-10 / AC-17）")
    class TournamentContactChat {

        private static final long ABSENT_CHANNEL_ID = Long.MAX_VALUE - 21;
        private static final long ABSENT_MESSAGE_ID = Long.MAX_VALUE - 22;

        private Long orgAdminId;
        private Long orgMemberId;
        private Long pAdminId;
        private Long pMemberId;
        private Long wAdminId;
        private Long tOutsiderId;

        /** T1: BULLETIN/CHAT とも非公開。 */
        private Long t1ChannelId;
        private Long t1MessageId;
        /** D1（T1 配下）: CHAT 非公開。 */
        private Long d1ChannelId;
        /** T2: BULLETIN 公開・CHAT 非公開。 */
        private Long t2ChannelId;
        private Long t2MessageId;
        /** T3: BULLETIN 非公開・CHAT 公開。 */
        private Long t3ChannelId;
        private Long t3MessageId;

        @BeforeEach
        void setUpTournamentChat() {
            String s = UUID.randomUUID().toString().substring(0, 8);
            orgAdminId = insertUser("cmp0726-chat-org-admin-" + s + "@example.com");
            orgMemberId = insertUser("cmp0726-chat-org-member-" + s + "@example.com");
            pAdminId = insertUser("cmp0726-chat-p-admin-" + s + "@example.com");
            pMemberId = insertUser("cmp0726-chat-p-member-" + s + "@example.com");
            wAdminId = insertUser("cmp0726-chat-w-admin-" + s + "@example.com");
            tOutsiderId = insertUser("cmp0726-chat-outsider-" + s + "@example.com");

            Long orgId = insertOrganization("CMP0726 チャット主催組織 " + s);
            Long pTeamId = insertTeam("CMP0726 チャット参加チーム " + s);
            Long wTeamId = insertTeam("CMP0726 チャット辞退チーム " + s);
            MembershipTestHelper.insertMembership(em, orgAdminId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, orgAdminId, "ADMIN", null, orgId);
            MembershipTestHelper.insertMembership(em, orgMemberId, ScopeType.ORGANIZATION, orgId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, pAdminId, ScopeType.TEAM, pTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, pAdminId, "ADMIN", pTeamId, null);
            MembershipTestHelper.insertMembership(em, pMemberId, ScopeType.TEAM, pTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, wAdminId, ScopeType.TEAM, wTeamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, wAdminId, "ADMIN", wTeamId, null);

            Long t1 = insertTournament(orgId, "CMP0726 チャットT1 " + s, orgAdminId);
            Long d1 = insertDivision(t1, "CMP0726 チャットD1 " + s);
            insertParticipant(d1, pTeamId, "REGISTERED");
            insertParticipant(d1, wTeamId, "WITHDRAWN");
            insertContactSpace("TOURNAMENT", t1, "BULLETIN", false);
            insertContactSpace("TOURNAMENT", t1, "CHAT", false);
            insertContactSpace("TOURNAMENT_DIVISION", d1, "CHAT", false);

            Long t2 = insertTournament(orgId, "CMP0726 チャットT2 " + s, orgAdminId);
            insertParticipant(insertDivision(t2, "CMP0726 チャットD2 " + s), pTeamId, "REGISTERED");
            insertContactSpace("TOURNAMENT", t2, "BULLETIN", true);
            insertContactSpace("TOURNAMENT", t2, "CHAT", false);

            Long t3 = insertTournament(orgId, "CMP0726 チャットT3 " + s, orgAdminId);
            insertParticipant(insertDivision(t3, "CMP0726 チャットD3 " + s), pTeamId, "REGISTERED");
            insertContactSpace("TOURNAMENT", t3, "BULLETIN", false);
            insertContactSpace("TOURNAMENT", t3, "CHAT", true);

            t1ChannelId = tournamentChannel(ChannelType.TOURNAMENT_CHAT, "TOURNAMENT", t1);
            d1ChannelId = tournamentChannel(ChannelType.TOURNAMENT_DIVISION_CHAT, "TOURNAMENT_DIVISION", d1);
            t2ChannelId = tournamentChannel(ChannelType.TOURNAMENT_CHAT, "TOURNAMENT", t2);
            t3ChannelId = tournamentChannel(ChannelType.TOURNAMENT_CHAT, "TOURNAMENT", t3);
            t1MessageId = message(t1ChannelId, "T1 非公開チャットの本文");
            message(d1ChannelId, "D1 非公開チャットの本文");
            t2MessageId = message(t2ChannelId, "T2 非公開チャットの本文");
            t3MessageId = message(t3ChannelId, "T3 公開チャットの本文");

            em.flush();
            em.clear();
        }

        @Test
        @DisplayName("AC-10: 閲覧権なし（部外者・主催組織一般・辞退チーム代表）の非公開大会チャットのチャンネルID系（一覧・検索・スレッド一覧・送信）は不在チャンネルと同一の 404 CHAT_001")
        void ac10_閲覧権なしのチャンネルID系は不在と同一() throws Exception {
            long before = messageRepository.count();
            for (Long actor : List.of(tOutsiderId, orgMemberId, wAdminId)) {
                for (Long channel : List.of(t1ChannelId, d1ChannelId)) {
                    assertSameAsMissing(actor, get("/api/v1/chat/channels/{id}/messages", channel),
                            get("/api/v1/chat/channels/{id}/messages", ABSENT_CHANNEL_ID), "CHAT_001");
                    assertSameAsMissing(actor,
                            get("/api/v1/chat/channels/{id}/messages/search", channel).param("keyword", "本文"),
                            get("/api/v1/chat/channels/{id}/messages/search", ABSENT_CHANNEL_ID).param("keyword", "本文"),
                            "CHAT_001");
                    assertSameAsMissing(actor, get("/api/v1/chat/channels/{id}/threads", channel),
                            get("/api/v1/chat/channels/{id}/threads", ABSENT_CHANNEL_ID), "CHAT_001");
                    assertSameAsMissing(actor, send(channel), send(ABSENT_CHANNEL_ID), "CHAT_001");
                }
            }
            assertThat(messageRepository.count()).as("メッセージが作られない").isEqualTo(before);
        }

        @Test
        @DisplayName("AC-10: 閲覧権なしの非公開大会チャットのメッセージID系（スレッド取得・ピン留め）は不在メッセージと同一の 404 CHAT_002")
        void ac10_閲覧権なしのメッセージID系は不在と同一() throws Exception {
            for (Long actor : List.of(tOutsiderId, orgMemberId, wAdminId)) {
                assertSameAsMissing(actor, get("/api/v1/chat/messages/{id}/thread", t1MessageId),
                        get("/api/v1/chat/messages/{id}/thread", ABSENT_MESSAGE_ID), "CHAT_002");
                assertSameAsMissing(actor, post("/api/v1/chat/messages/{id}/pin", t1MessageId).param("pinned", "true"),
                        post("/api/v1/chat/messages/{id}/pin", ABSENT_MESSAGE_ID).param("pinned", "true"), "CHAT_002");
            }
        }

        @Test
        @DisplayName("AC-10/AC-12: 参加チーム一般メンバー（閲覧可・投稿不可）は非公開大会チャットの一覧 200、送信・ピン留めは 403 TOUR_031。代表・主催ADMINは送信 201")
        void ac10_閲覧可投稿不可は403_投稿者は201() throws Exception {
            setAuthentication(pMemberId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", t1ChannelId))
                    .andExpect(status().isOk());
            mockMvc.perform(send(t1ChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_031"));
            mockMvc.perform(post("/api/v1/chat/messages/{id}/pin", t1MessageId).param("pinned", "true"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_031"));

            for (Long poster : List.of(pAdminId, orgAdminId)) {
                setAuthentication(poster);
                mockMvc.perform(send(t1ChannelId)).andExpect(status().isCreated());
            }
        }

        @Test
        @DisplayName("AC-17: T2（BULLETIN公開・CHAT非公開）の部外者はチャットの一覧・送信・スレッドとも不在と同一の 404（CHAT_001 / CHAT_002）")
        void ac17_CHAT非公開側は不在と同一() throws Exception {
            long before = messageRepository.count();
            assertSameAsMissing(tOutsiderId, get("/api/v1/chat/channels/{id}/messages", t2ChannelId),
                    get("/api/v1/chat/channels/{id}/messages", ABSENT_CHANNEL_ID), "CHAT_001");
            assertSameAsMissing(tOutsiderId, send(t2ChannelId), send(ABSENT_CHANNEL_ID), "CHAT_001");
            assertSameAsMissing(tOutsiderId, get("/api/v1/chat/messages/{id}/thread", t2MessageId),
                    get("/api/v1/chat/messages/{id}/thread", ABSENT_MESSAGE_ID), "CHAT_002");
            assertThat(messageRepository.count()).isEqualTo(before);
        }

        @Test
        @DisplayName("AC-17: T3（BULLETIN非公開・CHAT公開）の部外者はチャットの一覧・スレッド 200、送信・ピン留めは 403 TOUR_031")
        void ac17_CHAT公開側は閲覧可で投稿403() throws Exception {
            long before = messageRepository.count();
            setAuthentication(tOutsiderId);
            mockMvc.perform(get("/api/v1/chat/channels/{id}/messages", t3ChannelId))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/chat/messages/{id}/thread", t3MessageId))
                    .andExpect(status().isOk());
            mockMvc.perform(send(t3ChannelId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_031"));
            mockMvc.perform(post("/api/v1/chat/messages/{id}/pin", t3MessageId).param("pinned", "true"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("TOUR_031"));
            assertThat(messageRepository.count()).isEqualTo(before);
        }

        // ---- 補助 ----

        private MockHttpServletRequestBuilder send(Long channelId) throws Exception {
            return post("/api/v1/chat/channels/{id}/messages", channelId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("body", "CMP0726 投稿")));
        }

        /**
         * 秘匿対象への応答が不在 ID への応答と status・error.code・error.message・本文全体まで一致し、
         * 期待の絶対値（404 + expectedCode）であることを検査する。
         */
        private void assertSameAsMissing(Long actor, MockHttpServletRequestBuilder existing,
                                         MockHttpServletRequestBuilder missing, String expectedCode) throws Exception {
            setAuthentication(actor);
            MvcResult m = mockMvc.perform(missing).andReturn();
            MvcResult e = mockMvc.perform(existing).andReturn();
            String mb = m.getResponse().getContentAsString(StandardCharsets.UTF_8);
            String eb = e.getResponse().getContentAsString(StandardCharsets.UTF_8);
            String label = "actor=" + actor + " existing=" + eb + " missing=" + mb;
            JsonNode mj = objectMapper.readTree(mb.isBlank() ? "{}" : mb);
            JsonNode ej = objectMapper.readTree(eb.isBlank() ? "{}" : eb);
            assertThat(m.getResponse().getStatus()).as("不在側の絶対値 " + label).isEqualTo(404);
            assertThat(mj.path("error").path("code").asText()).as("不在側の絶対値 " + label).isEqualTo(expectedCode);
            assertThat(e.getResponse().getStatus()).as(label).isEqualTo(404);
            assertThat(ej.path("error").path("code").asText()).as(label).isEqualTo(expectedCode);
            assertThat(ej.path("error").path("message").asText()).as(label)
                    .isEqualTo(mj.path("error").path("message").asText());
            assertThat(ej).as("本文全体が不在と一致 " + label).isEqualTo(mj);
        }

        private Long tournamentChannel(ChannelType type, String sourceType, Long sourceId) {
            return channelRepository.save(ChatChannelEntity.builder()
                    .channelType(type)
                    .name("CMP0726 大会連絡 " + sourceType + " " + sourceId)
                    .isPrivate(false)
                    .sourceType(sourceType)
                    .sourceId(sourceId)
                    .createdBy(orgAdminId)
                    .build()).getId();
        }

        private Long message(Long channelId, String body) {
            return messageRepository.save(ChatMessageEntity.builder()
                    .channelId(channelId).senderId(orgAdminId).body(body).build()).getId();
        }

        private Long insertOrganization(String name) {
            em.createNativeQuery(
                            "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                    + "supporter_enabled, version, slug, created_at, updated_at) "
                                    + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                    + "CONCAT('c0726-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                    .setParameter("name", name)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        }

        private Long insertTournament(Long orgId, String name, Long createdBy) {
            em.createNativeQuery(
                            "INSERT INTO tournaments (organization_id, name, format, sport, "
                                    + "win_points, draw_points, loss_points, has_draw, has_sets, "
                                    + "has_extra_time, has_penalties, score_unit_label, league_round_type, "
                                    + "knockout_legs, visibility, status, version, created_by, created_at, updated_at) "
                                    + "VALUES (:orgId, :name, 'LEAGUE', 'SOCCER', "
                                    + "3, 1, 0, 1, 0, "
                                    + "0, 0, '点', 'SINGLE', "
                                    + "1, 'PUBLIC', 'OPEN', 0, :createdBy, NOW(), NOW())")
                    .setParameter("orgId", orgId)
                    .setParameter("name", name)
                    .setParameter("createdBy", createdBy)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        }

        private Long insertDivision(Long tid, String name) {
            em.createNativeQuery(
                            "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                    + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                    + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                    .setParameter("tid", tid)
                    .setParameter("name", name)
                    .executeUpdate();
            return ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                    .setParameter("name", name)
                    .getSingleResult()).longValue();
        }

        private void insertParticipant(Long divId, Long teamId, String status) {
            em.createNativeQuery(
                            "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                    + "VALUES (:divId, :teamId, :status, NOW())")
                    .setParameter("divId", divId)
                    .setParameter("teamId", teamId)
                    .setParameter("status", status)
                    .executeUpdate();
        }

        private void insertContactSpace(String scopeType, Long scopeId, String kind, boolean isPublic) {
            em.createNativeQuery(
                            "INSERT INTO tournament_contact_space (id, scope_type, scope_id, space_kind, ref_id, is_public, "
                                    + "created_at, updated_at) "
                                    + "VALUES (UNHEX(REPLACE(UUID(), '-', '')), :st, :sid, :kind, 0, :pub, NOW(), NOW())")
                    .setParameter("st", scopeType)
                    .setParameter("sid", scopeId)
                    .setParameter("kind", kind)
                    .setParameter("pub", isPublic)
                    .executeUpdate();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

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
                                + "VALUES (:email, 'チャット認可', 'テスト', 'チャット認可テスト', 'ACTIVE', "
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
                                + "CONCAT('chat-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
