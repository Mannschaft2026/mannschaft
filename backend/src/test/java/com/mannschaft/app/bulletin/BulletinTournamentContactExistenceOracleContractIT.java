package com.mannschaft.app.bulletin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.bulletin.entity.BulletinAttachmentEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinAttachmentRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.ParticipantStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261116-0726 大会連絡スペース（掲示板）の存在オラクル是正 — 受け入れテスト（試練・red）。
 *
 * <p><b>背景</b>: 大会／ディビジョン連絡スペースの掲示板は、閲覧権のない者に対し実在 ID なら 403（TOUR_030 / TOUR_031）、
 * 不在 ID なら 404 を返していたため、応答の差から非公開大会・スレッド・返信・添付の実在が判別できた（存在オラクル）。
 * 本戦役で「閲覧権なし＝不在と同一の応答」に畳む。正本: {@code .claude/campaigns/2026-11-18-tournament-contact-existence-oracle.md}
 * の AC、設計 {@code 2026-11-18-0726-design.md} §3（EP 別表）・§5（テストデータ）。</p>
 *
 * <p><b>判定の型</b>（{@code FriendContentForwardExistenceOracleContractIT#assertSameAsMissing} と同型）:
 * 同じ操作者で「秘匿される実在 ID」と「不在 ID（{@code Long.MAX_VALUE - n}）」を叩き、status・error.code・error.message・
 * 応答本文全体が一致すること、期待する絶対値（例 404 / TOUR_029）であること、かつ DB の副作用（スレッド・返信・既読・
 * リアクション・添付・storage_acls・storage_usage_logs と、既存行の内容）が不変であることを確かめる。</p>
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL（TEST_CONVENTION §3.1.2）。新設予定の本番部品
 * （{@code TournamentContactSpaceAccessGate} 等）は参照せず、HTTP 契約だけを検査する。
 * fixture は別 TX で commit し、所有行だけを後始末する。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261116-0726 大会連絡スペース掲示板の存在オラクル是正（実JWT/filter・試練）")
class BulletinTournamentContactExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    // --- 不在 ID（秘匿の対照群） ---
    private static final long MISSING_SCOPE_ID = Long.MAX_VALUE - 11;
    private static final long MISSING_THREAD_ID = Long.MAX_VALUE - 12;
    private static final long MISSING_REPLY_ID = Long.MAX_VALUE - 13;
    private static final long MISSING_ATTACHMENT_ID = Long.MAX_VALUE - 14;

    private static final String TOUR_029 = "TOUR_029";
    private static final String TOUR_031 = "TOUR_031";
    private static final String THREAD_NF = "BULLETIN_002";
    private static final String REPLY_NF = "BULLETIN_003";
    private static final String ATTACHMENT_NF = "BULLETIN_007";
    private static final String TARGET_NF = "BULLETIN_025";

    private static final String API = "/api/v1/bulletin";
    private static final String EMOJI = "👍";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private BulletinThreadRepository threadRepository;
    @Autowired private BulletinReplyRepository replyRepository;
    @Autowired private BulletinAttachmentRepository attachmentRepository;
    @PersistenceContext private EntityManager em;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> teamIds = new ArrayList<>();
    private final List<Long> orgIds = new ArrayList<>();
    private final List<Long> tournamentIds = new ArrayList<>();
    private final List<Long> divisionIds = new ArrayList<>();
    private final List<Long> threadIds = new ArrayList<>();
    private final List<Long> attachmentIds = new ArrayList<>();
    private final Map<Long, String> names = new HashMap<>();

    // --- 人物 ---
    private Long orgAdmin;      // 主催組織 ADMIN
    private Long orgMember;     // 主催組織の一般会員
    private Long sysAdmin;      // SYSTEM_ADMIN
    private Long foreignAdmin;  // 他テナント組織 ADMIN
    private Long pAdmin;        // 参加チーム P（REGISTERED）ADMIN
    private Long pDeputy;       // 参加チーム P DEPUTY_ADMIN
    private Long pMember;       // 参加チーム P 一般メンバー（閲覧可・投稿不可）
    private Long wAdmin;        // WITHDRAWN チーム W の ADMIN（AC-16 の投稿者）
    private Long wMember;       // WITHDRAWN チーム W の一般メンバー
    private Long xAdmin;        // DISQUALIFIED チーム X の ADMIN
    private Long xMember;       // DISQUALIFIED チーム X の一般メンバー
    private Long outsider;      // どこにも所属しない部外者

    // --- スコープ ---
    private Long organizerOrg;
    private Long teamP;
    /** T1: BULLETIN/CHAT とも非公開。D1 も非公開。P(REGISTERED)/W(WITHDRAWN)/X(DISQUALIFIED) が D1 参加。 */
    private Long t1;
    private Long d1;
    /** T2: BULLETIN 公開・CHAT 非公開（AC-17）。 */
    private Long t2;
    /** T3: BULLETIN 非公開・CHAT 公開（AC-17）。 */
    private Long t3;
    /** T4: BULLETIN スペース無し（CHAT のみ）・P 参加（AC-19）。 */
    private Long t4;
    /** T5: BULLETIN スペース論理削除済み・P 参加（AC-19）。 */
    private Long t5;
    private Long d1Participants; // D1 の参加チーム数（AC-20 の前提確認用）

    // --- 掲示板 ---
    private Long thA;   // T1, orgAdmin, INDIVIDUAL
    private Long thB;   // T1, orgAdmin, COUNT_ONLY
    private Long thD;   // D1, orgAdmin
    private Long thW;   // D1, wAdmin（AC-16）
    private Long th2;   // T2（BULLETIN 公開）
    private Long th3;   // T3（BULLETIN 非公開・CHAT 公開）
    private Long rA;    // thA への返信（orgAdmin）
    private Long rW;    // thW への返信（wAdmin）
    private Long attA;  // thA の添付（orgAdmin 作成）
    private Long attRA; // rA の添付（orgAdmin 作成）
    private Long attW;  // thW の添付（wAdmin 作成）

    @BeforeEach
    void fixtureを別TXでcommitする() {
        tx(() -> {
            orgAdmin = user("主催管理者");
            orgMember = user("主催一般");
            sysAdmin = user("システム管理者");
            foreignAdmin = user("他組織管理者");
            pAdmin = user("参加代表");
            pDeputy = user("参加副代表");
            pMember = user("参加一般");
            wAdmin = user("辞退チーム代表");
            wMember = user("辞退チーム一般");
            xAdmin = user("失格チーム代表");
            xMember = user("失格チーム一般");
            outsider = user("部外者");

            organizerOrg = organization("CMP0726 主催組織");
            Long foreignOrg = organization("CMP0726 他組織");
            teamP = team("CMP0726 参加チームP");
            Long teamW = team("CMP0726 辞退チームW");
            Long teamX = team("CMP0726 失格チームX");

            orgMembership(orgAdmin, organizerOrg, true);
            orgMembership(orgMember, organizerOrg, false);
            orgMembership(foreignAdmin, foreignOrg, true);
            MembershipTestHelper.insertUserRole(em, sysAdmin, "SYSTEM_ADMIN", null, null);
            teamMembership(pAdmin, teamP, "ADMIN");
            teamMembership(pDeputy, teamP, "DEPUTY_ADMIN");
            teamMembership(pMember, teamP, null);
            teamMembership(wAdmin, teamW, "ADMIN");
            teamMembership(wMember, teamW, null);
            teamMembership(xAdmin, teamX, "ADMIN");
            teamMembership(xMember, teamX, null);

            String sfx = UUID.randomUUID().toString().substring(0, 8);
            t1 = tournament(organizerOrg, "CMP0726 T1 " + sfx);
            d1 = division(t1, "CMP0726 D1 " + sfx);
            participant(d1, teamP, ParticipantStatus.REGISTERED);
            participant(d1, teamW, ParticipantStatus.WITHDRAWN);
            participant(d1, teamX, ParticipantStatus.DISQUALIFIED);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t1, ContactSpaceKind.BULLETIN, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t1, ContactSpaceKind.CHAT, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, d1, ContactSpaceKind.BULLETIN, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT_DIVISION, d1, ContactSpaceKind.CHAT, false, false);

            t2 = tournament(organizerOrg, "CMP0726 T2 " + sfx);
            participant(division(t2, "CMP0726 D2 " + sfx), teamP, ParticipantStatus.REGISTERED);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t2, ContactSpaceKind.BULLETIN, true, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t2, ContactSpaceKind.CHAT, false, false);

            t3 = tournament(organizerOrg, "CMP0726 T3 " + sfx);
            participant(division(t3, "CMP0726 D3 " + sfx), teamP, ParticipantStatus.REGISTERED);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t3, ContactSpaceKind.BULLETIN, false, false);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t3, ContactSpaceKind.CHAT, true, false);

            t4 = tournament(organizerOrg, "CMP0726 T4 " + sfx);
            participant(division(t4, "CMP0726 D4 " + sfx), teamP, ParticipantStatus.REGISTERED);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t4, ContactSpaceKind.CHAT, false, false);

            t5 = tournament(organizerOrg, "CMP0726 T5 " + sfx);
            participant(division(t5, "CMP0726 D5 " + sfx), teamP, ParticipantStatus.REGISTERED);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t5, ContactSpaceKind.BULLETIN, false, true);
            contactSpace(ContactSpaceScopeType.TOURNAMENT, t5, ContactSpaceKind.CHAT, false, false);
            return null;
        });

        thA = thread(ScopeType.TOURNAMENT, t1, orgAdmin, "CMP0726 thA", ReadTrackingMode.INDIVIDUAL);
        thB = thread(ScopeType.TOURNAMENT, t1, orgAdmin, "CMP0726 thB", ReadTrackingMode.COUNT_ONLY);
        thD = thread(ScopeType.TOURNAMENT_DIVISION, d1, orgAdmin, "CMP0726 thD", ReadTrackingMode.INDIVIDUAL);
        thW = thread(ScopeType.TOURNAMENT_DIVISION, d1, wAdmin, "CMP0726 thW", ReadTrackingMode.INDIVIDUAL);
        th2 = thread(ScopeType.TOURNAMENT, t2, orgAdmin, "CMP0726 th2", ReadTrackingMode.INDIVIDUAL);
        th3 = thread(ScopeType.TOURNAMENT, t3, orgAdmin, "CMP0726 th3", ReadTrackingMode.INDIVIDUAL);
        rA = reply(thA, orgAdmin, "CMP0726 rA");
        rW = reply(thW, wAdmin, "CMP0726 rW");
        attA = attachment(TargetType.THREAD, thA, orgAdmin);
        attRA = attachment(TargetType.REPLY, rA, orgAdmin);
        attW = attachment(TargetType.THREAD, thW, wAdmin);
        readStatus(thA, orgAdmin);
        readStatus(thB, orgAdmin);
        reaction(TargetType.THREAD, thA, orgAdmin);
        d1Participants = 3L;
    }

    @AfterEach
    void 所有行だけ片付ける() {
        try {
            String threads = "(SELECT t.id FROM (SELECT id FROM bulletin_threads WHERE "
                    + scopeCondition() + ") t)";
            String replies = "(SELECT r.id FROM (SELECT id FROM bulletin_replies WHERE thread_id IN " + threads + ") r)";
            jdbc.update("DELETE FROM bulletin_reactions WHERE (target_type = 'THREAD' AND target_id IN " + threads
                    + ") OR (target_type = 'REPLY' AND target_id IN " + replies + ")");
            jdbc.update("DELETE FROM bulletin_attachments WHERE (target_type = 'THREAD' AND target_id IN " + threads
                    + ") OR (target_type = 'REPLY' AND target_id IN " + replies + ")");
            jdbc.update("DELETE FROM bulletin_read_status WHERE thread_id IN " + threads);
            jdbc.update("DELETE FROM bulletin_replies WHERE thread_id IN " + threads);
            jdbc.update("DELETE FROM bulletin_threads WHERE " + scopeCondition());
            if (!userIds.isEmpty()) {
                jdbc.update("DELETE FROM storage_acls WHERE owner_id IN " + in(userIds));
                jdbc.update("DELETE FROM storage_usage_logs WHERE actor_id IN " + in(userIds));
            }
            for (Long id : tournamentIds) {
                jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT' AND scope_id = ?", id);
            }
            for (Long id : divisionIds) {
                jdbc.update("DELETE FROM tournament_contact_space WHERE scope_type = 'TOURNAMENT_DIVISION' AND scope_id = ?", id);
                jdbc.update("DELETE FROM tournament_participants WHERE division_id = ?", id);
                jdbc.update("DELETE FROM tournament_divisions WHERE id = ?", id);
            }
            for (Long id : tournamentIds) {
                jdbc.update("DELETE FROM tournaments WHERE id = ?", id);
            }
            for (Long id : userIds) {
                jdbc.update("DELETE FROM user_roles WHERE user_id = ?", id);
                jdbc.update("DELETE FROM memberships WHERE user_id = ?", id);
            }
            for (Long id : teamIds) {
                jdbc.update("DELETE FROM teams WHERE id = ?", id);
            }
            for (Long id : orgIds) {
                jdbc.update("DELETE FROM organizations WHERE id = ?", id);
            }
            for (Long id : userIds) {
                jdbc.update("DELETE FROM users WHERE id = ?", id);
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** 閲覧権のない者（T1 / D1 の非公開スペースに対して）。 */
    private List<Long> nonViewers() {
        return List.of(outsider, orgMember, foreignAdmin, wMember, xAdmin);
    }

    // ========================================================================
    // AC-1: 一覧系（GET threads / GET categories / POST read-all）
    // ========================================================================

    @Test
    @DisplayName("AC-1: 閲覧権なし（部外者・主催一般会員・他テナントADMIN・WITHDRAWN/DISQUALIFIED会員）の GET threads は不在大会/ディビジョンと同一の 404 TOUR_029")
    void ac1_スレッド一覧は不在と同一() throws Exception {
        List<Long> actors = List.of(outsider, orgMember, foreignAdmin, wMember, wAdmin, xMember, xAdmin);
        for (Long actor : actors) {
            assertConcealed("GET threads(T)", actor, id -> listThreads("TOURNAMENT", id), t1, MISSING_SCOPE_ID, 404, TOUR_029);
            assertConcealed("GET threads(D)", actor, id -> listThreads("TOURNAMENT_DIVISION", id), d1, MISSING_SCOPE_ID,
                    404, TOUR_029);
        }
    }

    @Test
    @DisplayName("AC-1: 閲覧権なしの GET categories は不在大会/ディビジョンと同一の 404 TOUR_029")
    void ac1_カテゴリ一覧は不在と同一() throws Exception {
        for (Long actor : List.of(outsider, orgMember, foreignAdmin, wMember, xMember)) {
            assertConcealed("GET categories(T)", actor, id -> listCategories("TOURNAMENT", id), t1, MISSING_SCOPE_ID,
                    404, TOUR_029);
            assertConcealed("GET categories(D)", actor, id -> listCategories("TOURNAMENT_DIVISION", id), d1,
                    MISSING_SCOPE_ID, 404, TOUR_029);
        }
    }

    @Test
    @DisplayName("AC-1: 閲覧権なしの POST read-all は不在大会/ディビジョンと同一の 404 TOUR_029 で、既読行を作らない")
    void ac1_一括既読は不在と同一() throws Exception {
        for (Long actor : List.of(outsider, orgMember, foreignAdmin, wMember, xMember)) {
            assertConcealed("POST read-all(T)", actor, id -> readAll("TOURNAMENT", id), t1, MISSING_SCOPE_ID, 404, TOUR_029);
            assertConcealed("POST read-all(D)", actor, id -> readAll("TOURNAMENT_DIVISION", id), d1, MISSING_SCOPE_ID,
                    404, TOUR_029);
        }
    }

    // ========================================================================
    // AC-2 / AC-3: スレッド作成
    // ========================================================================

    @Test
    @DisplayName("AC-2: 閲覧権なしのスレッド作成（JSON）は不在大会/ディビジョンと同一の 404 TOUR_029 で、DB 行を作らない")
    void ac2_閲覧権なしのスレッド作成は不在と同一() throws Exception {
        for (Long actor : nonViewers()) {
            assertConcealed("POST threads(T)", actor, id -> createThread("TOURNAMENT", id, "CMP0726 越境"), t1,
                    MISSING_SCOPE_ID, 404, TOUR_029);
            assertConcealed("POST threads(D)", actor, id -> createThread("TOURNAMENT_DIVISION", id, "CMP0726 越境"), d1,
                    MISSING_SCOPE_ID, 404, TOUR_029);
        }
        assertThat(countThreadsTitled("CMP0726 越境")).isZero();
    }

    @Test
    @DisplayName("AC-2: 閲覧権なしのスレッド作成（multipart）も不在と同一の 404 TOUR_029 で、DB 行を作らない")
    void ac2_閲覧権なしのmultipart作成は不在と同一() throws Exception {
        for (Long actor : List.of(outsider, foreignAdmin)) {
            assertConcealed("POST threads multipart", actor, id -> createThreadMultipart("TOURNAMENT", id, "CMP0726 越境M"),
                    t1, MISSING_SCOPE_ID, 404, TOUR_029);
        }
        assertThat(countThreadsTitled("CMP0726 越境M")).isZero();
    }

    @Test
    @DisplayName("AC-3: 閲覧可・投稿権なし（参加チーム一般会員・公開スペースの部外者）のスレッド作成は 403 TOUR_031 のまま、DB 行なし")
    void ac3_閲覧可投稿不可は403のまま() throws Exception {
        assertStatusCode(http(createThread("TOURNAMENT", t1, "CMP0726 一般投稿"), pMember), 403, TOUR_031);
        assertStatusCode(http(createThread("TOURNAMENT_DIVISION", d1, "CMP0726 一般投稿"), pMember), 403, TOUR_031);
        assertStatusCode(http(createThread("TOURNAMENT", t2, "CMP0726 一般投稿"), outsider), 403, TOUR_031);
        assertThat(countThreadsTitled("CMP0726 一般投稿")).isZero();
    }

    // ========================================================================
    // AC-4a: スレッド ID 閲覧系
    // ========================================================================

    @Test
    @DisplayName("AC-4a: 閲覧権なしのスレッド詳細・既読・既読者・返信一覧は不在スレッドIDと同一の 404 BULLETIN_002")
    void ac4a_スレッドID閲覧系は不在と同一() throws Exception {
        for (Long actor : nonViewers()) {
            assertConcealed("GET thread", actor, id -> get(API + "/threads/" + id), thA, MISSING_THREAD_ID, 404, THREAD_NF);
            assertConcealed("GET thread(D)", actor, id -> get(API + "/threads/" + id), thD, MISSING_THREAD_ID, 404, THREAD_NF);
            assertConcealed("POST read", actor, id -> post(API + "/threads/" + id + "/read"), thA, MISSING_THREAD_ID,
                    404, THREAD_NF);
            assertConcealed("GET readers", actor, id -> get(API + "/threads/" + id + "/readers"), thA, MISSING_THREAD_ID,
                    404, THREAD_NF);
            assertConcealed("GET readers unread", actor,
                    id -> get(API + "/threads/" + id + "/readers").param("filter", "unread"), thA, MISSING_THREAD_ID,
                    404, THREAD_NF);
            assertConcealed("GET replies", actor, id -> get(API + "/threads/" + id + "/replies"), thA, MISSING_THREAD_ID,
                    404, THREAD_NF);
        }
    }

    @Test
    @DisplayName("AC-4a: 閲覧可（参加チーム一般会員・主催ADMIN・SYSTEM_ADMIN）はスレッド詳細・返信一覧・既読者 200、既読 201")
    void ac4a_閲覧可はスレッドID閲覧系が成功する() throws Exception {
        for (Long actor : List.of(pMember, orgAdmin, sysAdmin)) {
            assertStatus(http(get(API + "/threads/" + thA), actor), 200);
            assertStatus(http(get(API + "/threads/" + thA + "/replies"), actor), 200);
            assertStatus(http(get(API + "/threads/" + thA + "/readers"), actor), 200);
            assertStatus(http(post(API + "/threads/" + thA + "/read"), actor), 201);
        }
    }

    // ========================================================================
    // AC-4b: スレッド ID 投稿・管理系
    // ========================================================================

    @Test
    @DisplayName("AC-4b: 閲覧権なしのスレッド更新・削除・priority・pin・lock・archive・返信投稿は不在スレッドIDと同一の 404 BULLETIN_002、DB 不変")
    void ac4b_スレッドID投稿管理系は不在と同一() throws Exception {
        for (Long actor : nonViewers()) {
            for (Map.Entry<String, Req> ep : threadWriteEndpoints().entrySet()) {
                assertConcealed(ep.getKey(), actor, ep.getValue(), thA, MISSING_THREAD_ID, 404, THREAD_NF);
            }
        }
    }

    @Test
    @DisplayName("AC-4b: 閲覧可・権限なし（参加チーム一般会員）のスレッド投稿・管理系は 403 TOUR_031、DB 不変")
    void ac4b_閲覧可権限なしは403() throws Exception {
        for (Map.Entry<String, Req> ep : threadWriteEndpoints().entrySet()) {
            String before = fingerprint();
            assertStatusCode(http(ep.getValue().at(thA), pMember), 403, TOUR_031);
            assertThat(fingerprint()).as(ep.getKey() + " 副作用なし").isEqualTo(before);
        }
    }

    // ========================================================================
    // AC-5: 返信 ID 系
    // ========================================================================

    @Test
    @DisplayName("AC-5: 閲覧権なしの返信への返信・返信更新・返信削除は不在返信IDと同一の 404 BULLETIN_003、DB 不変")
    void ac5_返信ID系は不在と同一() throws Exception {
        for (Long actor : nonViewers()) {
            for (Map.Entry<String, Req> ep : replyWriteEndpoints().entrySet()) {
                assertConcealed(ep.getKey(), actor, ep.getValue(), rA, MISSING_REPLY_ID, 404, REPLY_NF);
            }
        }
    }

    @Test
    @DisplayName("AC-5: 閲覧可・権限なし（参加チーム一般会員）の返信への返信・他人の返信の更新削除は 403 TOUR_031、DB 不変")
    void ac5_閲覧可権限なしは403() throws Exception {
        for (Map.Entry<String, Req> ep : replyWriteEndpoints().entrySet()) {
            String before = fingerprint();
            assertStatusCode(http(ep.getValue().at(rA), pMember), 403, TOUR_031);
            assertThat(fingerprint()).as(ep.getKey() + " 副作用なし").isEqualTo(before);
        }
    }

    @Test
    @DisplayName("AC-5/6/7: 親スレッドが論理削除された返信・その添付は、不在返信ID/不在添付IDと同一の 404（BULLETIN_003 / BULLETIN_007）で、実在を漏らさない")
    void 親スレッド削除済みの子は不在IDと同一() throws Exception {
        jdbc.update("UPDATE bulletin_threads SET deleted_at = NOW() WHERE id = ?", thA);
        for (Long actor : List.of(orgAdmin, outsider)) {
            for (Map.Entry<String, Req> ep : replyWriteEndpoints().entrySet()) {
                assertConcealed(ep.getKey() + "(親削除)", actor, ep.getValue(), rA, MISSING_REPLY_ID, 404, REPLY_NF);
            }
            assertConcealed("POST reactions REPLY(親削除)", actor, id -> addReaction("REPLY", id), rA,
                    MISSING_REPLY_ID, 404, REPLY_NF);
            assertConcealed("DELETE attachment(親削除)", actor, id -> delete(API + "/attachments/" + id), attA,
                    MISSING_ATTACHMENT_ID, 404, ATTACHMENT_NF);
            assertConcealed("DELETE attachment(返信の親削除)", actor, id -> delete(API + "/attachments/" + id), attRA,
                    MISSING_ATTACHMENT_ID, 404, ATTACHMENT_NF);
        }
    }

    // ========================================================================
    // AC-6: リアクション 4EP
    // ========================================================================

    @Test
    @DisplayName("AC-6: 閲覧権なしのリアクション追加・削除・一覧・集計は THREAD→BULLETIN_002 / REPLY→BULLETIN_003 の不在対象と同一、行作成なし")
    void ac6_リアクションは不在対象と同一() throws Exception {
        for (Long actor : nonViewers()) {
            for (String type : List.of("THREAD", "REPLY")) {
                long secret = "THREAD".equals(type) ? thA : rA;
                long missing = "THREAD".equals(type) ? MISSING_THREAD_ID : MISSING_REPLY_ID;
                String code = "THREAD".equals(type) ? THREAD_NF : REPLY_NF;
                assertConcealed("POST reactions " + type, actor, id -> addReaction(type, id), secret, missing, 404, code);
                assertConcealed("DELETE reactions " + type, actor,
                        id -> delete(API + "/reactions").param("targetType", type).param("targetId", String.valueOf(id))
                                .param("emoji", EMOJI), secret, missing, 404, code);
                assertConcealed("GET reactions " + type, actor,
                        id -> get(API + "/reactions").param("targetType", type).param("targetId", String.valueOf(id)),
                        secret, missing, 404, code);
                assertConcealed("GET reactions/summary " + type, actor,
                        id -> get(API + "/reactions/summary").param("targetType", type)
                                .param("targetId", String.valueOf(id)), secret, missing, 404, code);
            }
        }
    }

    // ========================================================================
    // AC-7: 添付（入口別の不在コード）
    // ========================================================================

    @Test
    @DisplayName("AC-7: 閲覧権なしの添付一覧(threadId)は BULLETIN_002、一覧(replyId)は BULLETIN_025、DL URL は BULLETIN_007 の不在と同一")
    void ac7_添付閲覧系は入口別の不在と同一() throws Exception {
        for (Long actor : nonViewers()) {
            assertConcealed("GET thread attachments", actor, id -> get(API + "/threads/" + id + "/attachments"), thA,
                    MISSING_THREAD_ID, 404, THREAD_NF);
            assertConcealed("GET reply attachments", actor, id -> get(API + "/replies/" + id + "/attachments"), rA,
                    MISSING_REPLY_ID, 404, TARGET_NF);
            assertConcealed("GET download-url", actor, id -> get(API + "/attachments/" + id + "/download-url"), attA,
                    MISSING_ATTACHMENT_ID, 404, ATTACHMENT_NF);
            assertConcealed("GET download-url(reply)", actor, id -> get(API + "/attachments/" + id + "/download-url"),
                    attRA, MISSING_ATTACHMENT_ID, 404, ATTACHMENT_NF);
        }
    }

    @Test
    @DisplayName("AC-7: 閲覧権なしの upload-url・確定は BULLETIN_025、削除は BULLETIN_007 の不在と同一で、presign 発行・行作成なし")
    void ac7_添付投稿系は入口別の不在と同一() throws Exception {
        for (Long actor : nonViewers()) {
            for (String type : List.of("THREAD", "REPLY")) {
                long secret = "THREAD".equals(type) ? thA : rA;
                long missing = "THREAD".equals(type) ? MISSING_THREAD_ID : MISSING_REPLY_ID;
                assertConcealed("POST upload-url " + type, actor, id -> presign(type, id), secret, missing, 404, TARGET_NF);
                assertConcealed("POST attachments " + type, actor, id -> confirm(type, id), secret, missing, 404, TARGET_NF);
            }
            assertConcealed("DELETE attachment", actor, id -> delete(API + "/attachments/" + id), attA,
                    MISSING_ATTACHMENT_ID, 404, ATTACHMENT_NF);
            assertThat(countStorageAcls(actor)).as("presign を発行しない（storage_acls 無し） actor=" + names.get(actor))
                    .isZero();
        }
    }

    @Test
    @DisplayName("AC-7: 閲覧可・投稿権なし（参加チーム一般会員）の upload-url・確定・他人の添付削除は 403 TOUR_031、presign・行作成なし")
    void ac7_閲覧可投稿不可の添付は403() throws Exception {
        String before = fingerprint();
        assertStatusCode(http(presign("THREAD", thA), pMember), 403, TOUR_031);
        assertStatusCode(http(confirm("THREAD", thA), pMember), 403, TOUR_031);
        assertStatusCode(http(delete(API + "/attachments/" + attA), pMember), 403, TOUR_031);
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(countStorageAcls(pMember)).isZero();
    }

    // ========================================================================
    // AC-12（掲示板部分）: 正常系回帰
    // ========================================================================

    @Test
    @DisplayName("AC-12: 主催ADMIN・参加チーム代表/副代表・SYSTEM_ADMIN は大会/ディビジョンへスレッド作成 201・一覧 200")
    void ac12_正当な投稿者は作成と一覧ができる() throws Exception {
        for (Long actor : List.of(orgAdmin, pAdmin, pDeputy, sysAdmin)) {
            String title = "CMP0726 正当投稿 " + names.get(actor);
            assertStatus(http(createThread("TOURNAMENT", t1, title), actor), 201);
            assertStatus(http(createThread("TOURNAMENT_DIVISION", d1, title + " D"), actor), 201);
            MvcResult listed = http(listThreads("TOURNAMENT", t1), actor);
            assertStatus(listed, 200);
            assertThat(titles(listed)).contains("CMP0726 thA", title);
            assertStatus(http(listCategories("TOURNAMENT", t1), actor), 200);
            assertStatus(http(readAll("TOURNAMENT", t1), actor), 200);
        }
        assertThat(countThreadsTitledLike("CMP0726 正当投稿 %")).isEqualTo(8);
    }

    @Test
    @DisplayName("AC-12: 主催ADMIN は他人のスレッドの pin・lock・返信投稿ができ、参加チーム代表は返信できる（200/201）")
    void ac12_正当な管理者は管理操作ができる() throws Exception {
        assertStatus(http(json(patch(API + "/threads/" + thA + "/pin"), Map.of("pinned", true)), orgAdmin), 200);
        assertStatus(http(json(post(API + "/threads/" + thA + "/replies"), Map.of("body", "CMP0726 主催返信")), orgAdmin),
                201);
        assertStatus(http(json(post(API + "/threads/" + thA + "/replies"), Map.of("body", "CMP0726 代表返信")), pAdmin),
                201);
        assertStatus(http(addReaction("THREAD", thA), pMember), 201);
        assertStatus(http(get(API + "/threads/" + thA + "/attachments"), pMember), 200);
    }

    @Test
    @DisplayName("AC-12: 公開スペース（T2 BULLETIN 公開）の部外者は一覧・詳細・返信一覧を 200 で閲覧できる")
    void ac12_公開スペースは部外者も閲覧可() throws Exception {
        MvcResult listed = http(listThreads("TOURNAMENT", t2), outsider);
        assertStatus(listed, 200);
        assertThat(titles(listed)).contains("CMP0726 th2").doesNotContain("CMP0726 thA", "CMP0726 th3");
        assertStatus(http(get(API + "/threads/" + th2), outsider), 200);
        assertStatus(http(get(API + "/threads/" + th2 + "/replies"), outsider), 200);
    }

    // ========================================================================
    // AC-13（掲示板）: 未認証は実在・不在とも同一 401
    // ========================================================================

    @Test
    @DisplayName("AC-13: 未認証は掲示板の各 EP で実在・不在とも同一の 401（本文まで一致）")
    void ac13_未認証は実在不在とも同一の401() throws Exception {
        assertConcealed("GET threads", null, id -> listThreads("TOURNAMENT", id), t1, MISSING_SCOPE_ID, 401, null);
        assertConcealed("GET threads(公開)", null, id -> listThreads("TOURNAMENT", id), t2, MISSING_SCOPE_ID, 401, null);
        assertConcealed("GET categories", null, id -> listCategories("TOURNAMENT", id), t1, MISSING_SCOPE_ID, 401, null);
        assertConcealed("POST threads", null, id -> createThread("TOURNAMENT", id, "CMP0726 未認証"), t1,
                MISSING_SCOPE_ID, 401, null);
        assertConcealed("POST read-all", null, id -> readAll("TOURNAMENT", id), t1, MISSING_SCOPE_ID, 401, null);
        assertConcealed("GET thread", null, id -> get(API + "/threads/" + id), thA, MISSING_THREAD_ID, 401, null);
        assertConcealed("GET readers", null, id -> get(API + "/threads/" + id + "/readers"), thA, MISSING_THREAD_ID,
                401, null);
        for (Map.Entry<String, Req> ep : threadWriteEndpoints().entrySet()) {
            assertConcealed(ep.getKey(), null, ep.getValue(), thA, MISSING_THREAD_ID, 401, null);
        }
        for (Map.Entry<String, Req> ep : replyWriteEndpoints().entrySet()) {
            assertConcealed(ep.getKey(), null, ep.getValue(), rA, MISSING_REPLY_ID, 401, null);
        }
        assertConcealed("GET replies", null, id -> get(API + "/threads/" + id + "/replies"), thA, MISSING_THREAD_ID,
                401, null);
        assertConcealed("POST reactions", null, id -> addReaction("THREAD", id), thA, MISSING_THREAD_ID, 401, null);
        assertConcealed("GET reactions/summary", null,
                id -> get(API + "/reactions/summary").param("targetType", "THREAD").param("targetId", String.valueOf(id)),
                thA, MISSING_THREAD_ID, 401, null);
        assertConcealed("GET thread attachments", null, id -> get(API + "/threads/" + id + "/attachments"), thA,
                MISSING_THREAD_ID, 401, null);
        assertConcealed("GET download-url", null, id -> get(API + "/attachments/" + id + "/download-url"), attA,
                MISSING_ATTACHMENT_ID, 401, null);
        assertConcealed("POST upload-url", null, id -> presign("THREAD", id), thA, MISSING_THREAD_ID, 401, null);
        assertConcealed("DELETE attachment", null, id -> delete(API + "/attachments/" + id), attA,
                MISSING_ATTACHMENT_ID, 401, null);
    }

    // ========================================================================
    // AC-15: readers の応答（500 にならない）
    // ========================================================================

    @Test
    @DisplayName("AC-15: 非モデレーター閲覧者の readers は INDIVIDUAL=200（既読者あり）・COUNT_ONLY=200空配列・unread=403 COMMON_002（500 にならない）")
    void ac15_readersは非モデレーターでも500にならない() throws Exception {
        MvcResult individual = http(get(API + "/threads/" + thA + "/readers"), pMember);
        assertStatus(individual, 200);
        assertThat(parse(individual).path("data").size()).as(body(individual)).isEqualTo(1);

        MvcResult countOnly = http(get(API + "/threads/" + thB + "/readers"), pMember);
        assertStatus(countOnly, 200);
        assertThat(parse(countOnly).path("data").isArray()).as(body(countOnly)).isTrue();
        assertThat(parse(countOnly).path("data").size()).as(body(countOnly)).isZero();

        MvcResult unread = http(get(API + "/threads/" + thA + "/readers").param("filter", "unread"), pMember);
        assertStatusCode(unread, 403, "COMMON_002");
    }

    @Test
    @DisplayName("AC-15: モデレーター相当（参加チーム代表・主催ADMIN）の readers は COUNT_ONLY でも 200 で既読者を返し、unread も 200")
    void ac15_モデレーターはreadersを参照できる() throws Exception {
        for (Long actor : List.of(pAdmin, orgAdmin)) {
            MvcResult countOnly = http(get(API + "/threads/" + thB + "/readers"), actor);
            assertStatus(countOnly, 200);
            assertThat(parse(countOnly).path("data").size()).as(body(countOnly)).isEqualTo(1);
            assertStatus(http(get(API + "/threads/" + thA + "/readers").param("filter", "unread"), actor), 200);
        }
    }

    // ========================================================================
    // AC-16: 投稿後に資格を失った本人
    // ========================================================================

    @Test
    @DisplayName("AC-16: WITHDRAWN チームの投稿者本人による非公開スペースの自スレッド更新・削除は不在スレッドIDと同一の 404 BULLETIN_002、内容不変")
    void ac16_資格喪失者の自スレッド更新削除は不在と同一() throws Exception {
        assertConcealed("PUT own thread", wAdmin, id -> updateThread(id), thW, MISSING_THREAD_ID, 404, THREAD_NF);
        assertConcealed("DELETE own thread", wAdmin, id -> delete(API + "/threads/" + id), thW, MISSING_THREAD_ID,
                404, THREAD_NF);
    }

    @Test
    @DisplayName("AC-16: WITHDRAWN チームの投稿者本人による自返信の更新・削除は不在返信IDと同一の 404 BULLETIN_003、内容不変")
    void ac16_資格喪失者の自返信更新削除は不在と同一() throws Exception {
        assertConcealed("PUT own reply", wAdmin, id -> json(put(API + "/replies/" + id), Map.of("body", "CMP0726 改竄")),
                rW, MISSING_REPLY_ID, 404, REPLY_NF);
        assertConcealed("DELETE own reply", wAdmin, id -> delete(API + "/replies/" + id), rW, MISSING_REPLY_ID,
                404, REPLY_NF);
    }

    @Test
    @DisplayName("AC-16: WITHDRAWN チームの作成者本人による自添付の削除は不在添付IDと同一の 404 BULLETIN_007、件数・使用量不変")
    void ac16_資格喪失者の自添付削除は不在と同一() throws Exception {
        long usageBefore = countUsageLogs();
        assertConcealed("DELETE own attachment", wAdmin, id -> delete(API + "/attachments/" + id), attW,
                MISSING_ATTACHMENT_ID, 404, ATTACHMENT_NF);
        assertThat(countUsageLogs()).as("使用量ログ不変").isEqualTo(usageBefore);
        Integer att = jdbc.queryForObject("SELECT COUNT(*) FROM bulletin_attachments WHERE id = ?", Integer.class, attW);
        assertThat(att).isEqualTo(1);
    }

    // ========================================================================
    // AC-17（BULLETIN 側）: 同一大会で BULLETIN と CHAT の公開設定が異なる
    // ========================================================================

    @Test
    @DisplayName("AC-17: BULLETIN公開/CHAT非公開（T2）の部外者は掲示板を閲覧 200、作成・スレッド管理・返信は 403 TOUR_031")
    void ac17_BULLETIN公開側は403() throws Exception {
        assertStatus(http(listThreads("TOURNAMENT", t2), outsider), 200);
        assertStatus(http(get(API + "/threads/" + th2), outsider), 200);
        String before = fingerprint();
        assertStatusCode(http(createThread("TOURNAMENT", t2, "CMP0726 T2越境"), outsider), 403, TOUR_031);
        for (Map.Entry<String, Req> ep : threadWriteEndpoints().entrySet()) {
            assertStatusCode(http(ep.getValue().at(th2), outsider), 403, TOUR_031);
        }
        assertThat(fingerprint()).isEqualTo(before);
        assertThat(countThreadsTitled("CMP0726 T2越境")).isZero();
    }

    @Test
    @DisplayName("AC-17: BULLETIN非公開/CHAT公開（T3）の部外者の掲示板は一覧・作成=TOUR_029、スレッドID系=BULLETIN_002 の不在応答")
    void ac17_BULLETIN非公開側は不在と同一() throws Exception {
        assertConcealed("GET threads(T3)", outsider, id -> listThreads("TOURNAMENT", id), t3, MISSING_SCOPE_ID, 404, TOUR_029);
        assertConcealed("POST read-all(T3)", outsider, id -> readAll("TOURNAMENT", id), t3, MISSING_SCOPE_ID, 404, TOUR_029);
        assertConcealed("POST threads(T3)", outsider, id -> createThread("TOURNAMENT", id, "CMP0726 T3越境"), t3,
                MISSING_SCOPE_ID, 404, TOUR_029);
        assertConcealed("GET thread(T3)", outsider, id -> get(API + "/threads/" + id), th3, MISSING_THREAD_ID, 404, THREAD_NF);
        for (Map.Entry<String, Req> ep : threadWriteEndpoints().entrySet()) {
            assertConcealed(ep.getKey() + "(T3)", outsider, ep.getValue(), th3, MISSING_THREAD_ID, 404, THREAD_NF);
        }
        assertThat(countThreadsTitled("CMP0726 T3越境")).isZero();
    }

    // ========================================================================
    // AC-19: 大会・参加資格は残し BULLETIN スペースだけ欠落／論理削除
    // ========================================================================

    @Test
    @DisplayName("AC-19: BULLETIN スペースが無い大会（T4）への参加チーム代表・主催ADMINのスレッド作成は不在と同一の 404 TOUR_029、DB 不変")
    void ac19_BULLETINスペース欠落なら作成は404() throws Exception {
        for (Long actor : List.of(pAdmin, orgAdmin)) {
            assertConcealed("POST threads(T4)", actor, id -> createThread("TOURNAMENT", id, "CMP0726 欠落投稿"), t4,
                    MISSING_SCOPE_ID, 404, TOUR_029);
        }
        assertThat(countThreadsTitled("CMP0726 欠落投稿")).isZero();
    }

    @Test
    @DisplayName("AC-19: BULLETIN スペースが論理削除済みの大会（T5）への参加チーム代表・主催ADMINのスレッド作成は不在と同一の 404 TOUR_029、DB 不変")
    void ac19_BULLETINスペース論理削除なら作成は404() throws Exception {
        for (Long actor : List.of(pAdmin, orgAdmin)) {
            assertConcealed("POST threads(T5)", actor, id -> createThread("TOURNAMENT", id, "CMP0726 削除済投稿"), t5,
                    MISSING_SCOPE_ID, 404, TOUR_029);
        }
        assertThat(countThreadsTitled("CMP0726 削除済投稿")).isZero();
    }

    // ========================================================================
    // AC-20: 認可 SQL 本数が参加チーム数に比例しない
    // ========================================================================

    @Test
    @DisplayName("AC-20: 参加チーム数（と閲覧者の所属チーム数）を増やしても、閲覧系 EP の SQL 本数は変わらない")
    void ac20_SQL本数は参加チーム数に比例しない() throws Exception {
        Map<String, Supplier<MockHttpServletRequestBuilder>> eps = new LinkedHashMap<>();
        eps.put("GET threads", () -> listThreads("TOURNAMENT", t1));
        eps.put("GET threads(D)", () -> listThreads("TOURNAMENT_DIVISION", d1));
        eps.put("GET thread", () -> get(API + "/threads/" + thA));
        eps.put("GET replies", () -> get(API + "/threads/" + thA + "/replies"));
        eps.put("GET readers", () -> get(API + "/threads/" + thA + "/readers"));
        eps.put("GET reactions/summary", () -> get(API + "/reactions/summary").param("targetType", "THREAD")
                .param("targetId", String.valueOf(thA)));
        eps.put("GET thread attachments", () -> get(API + "/threads/" + thA + "/attachments"));

        Map<String, Long> base = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<MockHttpServletRequestBuilder>> ep : eps.entrySet()) {
            base.put(ep.getKey(), measurePreparedStatements(ep.getValue(), pMember));
        }

        // 参加チームを 5 つ足し、閲覧者（pMember）と代表（pAdmin）をそれら全てに所属させる。
        tx(() -> {
            for (int i = 0; i < 5; i++) {
                Long extra = team("CMP0726 追加参加チーム" + i);
                participant(d1, extra, ParticipantStatus.REGISTERED);
                teamMembership(pMember, extra, null);
                teamMembership(pAdmin, extra, "ADMIN");
            }
            return null;
        });
        d1Participants += 5;
        Integer participants = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tournament_participants WHERE division_id = ?", Integer.class, d1);
        assertThat(participants.longValue()).as("前提: 参加チームが増えている").isEqualTo(d1Participants);

        for (Map.Entry<String, Supplier<MockHttpServletRequestBuilder>> ep : eps.entrySet()) {
            long after = measurePreparedStatements(ep.getValue(), pMember);
            System.out.printf("AC-20 実測 ep=%s before=%d after=%d%n", ep.getKey(), base.get(ep.getKey()), after);
            assertThat(after).as("%s: 参加チーム 3→8 で SQL 本数が増えないこと", ep.getKey())
                    .isEqualTo(base.get(ep.getKey()));
        }
    }

    // ========================================================================
    // 補助: EP 群
    // ========================================================================

    /** スレッド ID を受ける投稿・管理系 EP（AC-4b）。 */
    private Map<String, Req> threadWriteEndpoints() {
        Map<String, Req> eps = new LinkedHashMap<>();
        eps.put("PUT thread", this::updateThread);
        eps.put("DELETE thread", id -> delete(API + "/threads/" + id));
        eps.put("PATCH priority", id -> json(patch(API + "/threads/" + id + "/priority"), Map.of("priority", "IMPORTANT")));
        eps.put("PATCH pin", id -> json(patch(API + "/threads/" + id + "/pin"), Map.of("pinned", true)));
        eps.put("PATCH lock", id -> json(patch(API + "/threads/" + id + "/lock"), Map.of("locked", true)));
        eps.put("POST archive", id -> json(post(API + "/threads/" + id + "/archive"), Map.of("is_archived", true)));
        eps.put("POST replies", id -> json(post(API + "/threads/" + id + "/replies"), Map.of("body", "CMP0726 越境返信")));
        return eps;
    }

    /** 返信 ID を受ける投稿・管理系 EP（AC-5）。 */
    private Map<String, Req> replyWriteEndpoints() {
        Map<String, Req> eps = new LinkedHashMap<>();
        eps.put("POST nested reply", id -> json(post(API + "/replies/" + id + "/replies"), Map.of("body", "CMP0726 越境入れ子")));
        eps.put("PUT reply", id -> json(put(API + "/replies/" + id), Map.of("body", "CMP0726 改竄")));
        eps.put("DELETE reply", id -> delete(API + "/replies/" + id));
        return eps;
    }

    private MockHttpServletRequestBuilder listThreads(String scopeType, long scopeId) {
        return get(API + "/threads").param("scope_type", scopeType).param("scope_id", String.valueOf(scopeId));
    }

    private MockHttpServletRequestBuilder listCategories(String scopeType, long scopeId) {
        return get(API + "/categories").param("scope_type", scopeType).param("scope_id", String.valueOf(scopeId));
    }

    private MockHttpServletRequestBuilder createThread(String scopeType, long scopeId, String title) throws Exception {
        return json(post(API + "/threads"), threadBody(scopeType, scopeId, title));
    }

    private MockHttpServletRequestBuilder createThreadMultipart(String scopeType, long scopeId, String title)
            throws Exception {
        org.springframework.mock.web.MockMultipartFile data = new org.springframework.mock.web.MockMultipartFile(
                "data", "", MediaType.APPLICATION_JSON_VALUE,
                mapper.writeValueAsBytes(threadBody(scopeType, scopeId, title)));
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(API + "/threads").file(data);
    }

    private Map<String, Object> threadBody(String scopeType, long scopeId, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("scopeId", String.valueOf(scopeId));
        body.put("title", title);
        body.put("body", "CMP0726 本文");
        return body;
    }

    private MockHttpServletRequestBuilder readAll(String scopeType, long scopeId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scopeType", scopeType);
        body.put("scopeId", scopeId);
        return json(post(API + "/threads/read-all"), body);
    }

    private MockHttpServletRequestBuilder updateThread(long id) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "CMP0726 改竄");
        body.put("body", "CMP0726 改竄本文");
        body.put("priority", "INFO");
        return json(put(API + "/threads/" + id), body);
    }

    private MockHttpServletRequestBuilder addReaction(String targetType, long targetId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("emoji", EMOJI);
        return json(post(API + "/reactions"), body);
    }

    private MockHttpServletRequestBuilder presign(String targetType, long targetId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("fileName", "cmp0726.png");
        body.put("contentType", "image/png");
        body.put("fileSize", 128);
        return json(post(API + "/attachments/upload-url"), body);
    }

    private MockHttpServletRequestBuilder confirm(String targetType, long targetId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", targetType);
        body.put("targetId", targetId);
        body.put("fileKey", String.join("/", "bulletin", "cmp0726", UUID.randomUUID() + ".png"));
        body.put("originalFilename", "cmp0726.png");
        body.put("fileSize", 128);
        body.put("contentType", "image/png");
        return json(post(API + "/attachments"), body);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) throws Exception {
        return builder.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    // ========================================================================
    // 補助: 判定
    // ========================================================================

    /** 秘匿 ID / 不在 ID を受け取ってリクエストを組み立てる。 */
    @FunctionalInterface
    private interface Req {
        MockHttpServletRequestBuilder at(long id) throws Exception;
    }

    /**
     * 秘匿される実在 ID への応答が、不在 ID への応答と status・error.code・error.message・本文全体まで一致し、
     * 期待の絶対値（status / code）であり、DB の副作用が無いことを検査する。
     *
     * @param expectedCode 期待する error.code（401 のように本文の code を問わない場合は null）
     */
    private void assertConcealed(String label, Long actor, Req req, long secretId, long missingId,
                                 int expectedStatus, String expectedCode) throws Exception {
        String before = fingerprint();
        MvcResult missing = http(req.at(missingId), actor);
        MvcResult secret = http(req.at(secretId), actor);
        String l = label + " actor=" + names.get(actor) + " secret=" + body(secret) + " missing=" + body(missing);

        assertThat(missing.getResponse().getStatus()).as("不在の status: " + l).isEqualTo(expectedStatus);
        assertThat(secret.getResponse().getStatus()).as("秘匿 status は不在と同一: " + l)
                .isEqualTo(missing.getResponse().getStatus());
        if (expectedCode != null) {
            assertThat(code(missing)).as("不在の error.code: " + l).isEqualTo(expectedCode);
            assertThat(code(secret)).as("秘匿 error.code は不在と同一: " + l).isEqualTo(code(missing));
            assertThat(parse(secret).path("error").path("message").asText()).as("error.message 同一: " + l)
                    .isEqualTo(parse(missing).path("error").path("message").asText());
        }
        assertThat(parse(secret)).as("本文全体が不在と同一: " + l).isEqualTo(parse(missing));
        assertThat(fingerprint()).as("副作用なし: " + l).isEqualTo(before);
    }

    private void assertStatus(MvcResult r, int status) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
    }

    private void assertStatusCode(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(status);
        assertThat(code(r)).as(body(r)).isEqualTo(code);
    }

    private MvcResult http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            List<String> roles = actor.equals(sysAdmin) ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, roles));
        }
        return mvc.perform(request).andReturn();
    }

    /** 1 リクエストの SQL 本数（Hibernate Statistics の prepareStatementCount）。キャッシュの影響を除くため 1 回空打ちする。 */
    private long measurePreparedStatements(Supplier<MockHttpServletRequestBuilder> request, Long actor) throws Exception {
        assertStatus(http(request.get(), actor), 200);
        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = stats.isStatisticsEnabled();
        try {
            stats.setStatisticsEnabled(true);
            stats.clear();
            MvcResult r = http(request.get(), actor);
            long prepared = stats.getPrepareStatementCount();
            assertStatus(r, 200);
            return prepared;
        } finally {
            stats.setStatisticsEnabled(originallyEnabled);
        }
    }

    private JsonNode parse(MvcResult result) throws Exception {
        String s = body(result);
        return s.isBlank() ? mapper.createObjectNode() : mapper.readTree(s);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String code(MvcResult result) throws Exception {
        return parse(result).path("error").path("code").asText();
    }

    private List<String> titles(MvcResult result) throws Exception {
        List<String> titles = new ArrayList<>();
        parse(result).path("data").forEach(n -> titles.add(n.path("title").asText()));
        return titles;
    }

    // ========================================================================
    // 補助: DB 観測
    // ========================================================================

    private String in(List<Long> ids) {
        return ids.isEmpty() ? "(NULL)" : ids.stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
    }

    private String scopeCondition() {
        return "((scope_type = 'TOURNAMENT' AND scope_id IN " + in(tournamentIds)
                + ") OR (scope_type = 'TOURNAMENT_DIVISION' AND scope_id IN " + in(divisionIds) + "))";
    }

    /**
     * 副作用の指紋。所有スコープのスレッド（件数と内容）、それらへの返信（内容）、既読・リアクション・添付の行、
     * 所有ユーザーの storage_acls（presign）と storage_usage_logs を連結する。
     */
    private String fingerprint() {
        String threads = "(SELECT id FROM bulletin_threads WHERE " + scopeCondition() + ")";
        String replies = "(SELECT id FROM bulletin_replies WHERE thread_id IN " + threads + ")";
        StringBuilder sb = new StringBuilder();
        sb.append("threads=").append(jdbc.queryForList(
                "SELECT CONCAT_WS('|', id, title, body, priority, is_pinned, is_locked, is_archived, "
                        + "IFNULL(deleted_at, '-'), reply_count, read_count) FROM bulletin_threads WHERE "
                        + scopeCondition() + " ORDER BY id", String.class));
        sb.append(" replies=").append(jdbc.queryForList(
                "SELECT CONCAT_WS('|', id, body, is_edited, IFNULL(deleted_at, '-'), reply_count) FROM bulletin_replies "
                        + "WHERE thread_id IN " + threads + " ORDER BY id", String.class));
        sb.append(" reads=").append(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bulletin_read_status WHERE thread_id IN " + threads, Long.class));
        sb.append(" reactions=").append(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bulletin_reactions WHERE (target_type = 'THREAD' AND target_id IN " + threads
                        + ") OR (target_type = 'REPLY' AND target_id IN " + replies + ")", Long.class));
        sb.append(" attachments=").append(jdbc.queryForList(
                "SELECT CONCAT_WS('|', id, target_type, target_id) FROM bulletin_attachments WHERE "
                        + "(target_type = 'THREAD' AND target_id IN " + threads + ") OR (target_type = 'REPLY' AND target_id IN "
                        + replies + ") ORDER BY id", String.class));
        sb.append(" acls=").append(jdbc.queryForObject(
                "SELECT COUNT(*) FROM storage_acls WHERE owner_id IN " + in(userIds), Long.class));
        sb.append(" usage=").append(countUsageLogs());
        return sb.toString();
    }

    private long countUsageLogs() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM storage_usage_logs WHERE actor_id IN " + in(userIds)
                + " OR (reference_type = 'bulletin_attachments' AND reference_id IN " + in(attachmentIds) + ")", Long.class);
        return n == null ? 0 : n;
    }

    private long countStorageAcls(Long ownerId) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM storage_acls WHERE owner_id = ?", Long.class, ownerId);
        return n == null ? 0 : n;
    }

    private long countThreadsTitled(String title) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM bulletin_threads WHERE title = ?", Long.class, title);
        return n == null ? 0 : n;
    }

    private long countThreadsTitledLike(String pattern) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM bulletin_threads WHERE " + scopeCondition()
                + " AND title LIKE ?", Long.class, pattern);
        return n == null ? 0 : n;
    }

    // ========================================================================
    // 補助: fixture
    // ========================================================================

    private <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long user(String label) {
        Long id = users.save(UserEntity.builder().email("cmp0726-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
        userIds.add(id);
        names.put(id, label);
        return id;
    }

    private Long organization(String name) {
        Long id = organizations.save(OrganizationEntity.builder()
                .slug("cmp0726-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build()).getId();
        orgIds.add(id);
        return id;
    }

    private Long team(String name) {
        Long id = teams.save(TeamEntity.builder().slug("cmp0726-" + UUID.randomUUID().toString().substring(0, 8))
                .name(name).visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId();
        teamIds.add(id);
        return id;
    }

    private void orgMembership(Long userId, Long orgId, boolean admin) {
        MembershipTestHelper.insertMembership(em, userId, com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION,
                orgId, RoleKind.MEMBER);
        if (admin) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, orgId);
        }
    }

    /** チーム所属（memberships）と、必要なら権限ロール（ADMIN / DEPUTY_ADMIN）を張る。 */
    private void teamMembership(Long userId, Long teamId, String adminRole) {
        MembershipTestHelper.insertMembership(em, userId, com.mannschaft.app.membership.domain.ScopeType.TEAM,
                teamId, RoleKind.MEMBER);
        if (adminRole != null) {
            MembershipTestHelper.insertUserRole(em, userId, adminRole, teamId, null);
        }
    }

    /** 大会を seed する（列は BulletinTournamentScopeGlobalContractIT#tournament と同一）。 */
    private Long tournament(Long orgId, String name) {
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
                .setParameter("createdBy", orgAdmin)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournaments WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        tournamentIds.add(id);
        return id;
    }

    private Long division(Long tid, String name) {
        em.createNativeQuery(
                        "INSERT INTO tournament_divisions (tournament_id, name, level, promotion_slots, "
                                + "relegation_slots, playoff_promotion_slots, sort_order, created_at, updated_at) "
                                + "VALUES (:tid, :name, 1, 0, 0, 0, 0, NOW(), NOW())")
                .setParameter("tid", tid)
                .setParameter("name", name)
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT id FROM tournament_divisions WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
        divisionIds.add(id);
        return id;
    }

    /** 参加チームを seed する（status 引数化: REGISTERED / WITHDRAWN / DISQUALIFIED）。 */
    private void participant(Long divId, Long teamId, ParticipantStatus status) {
        em.createNativeQuery(
                        "INSERT INTO tournament_participants (division_id, team_id, status, joined_at) "
                                + "VALUES (:divId, :teamId, :status, NOW())")
                .setParameter("divId", divId)
                .setParameter("teamId", teamId)
                .setParameter("status", status.name())
                .executeUpdate();
    }

    /** 連絡スペースを seed する（kind・公開・論理削除を引数化）。 */
    private void contactSpace(ContactSpaceScopeType scopeType, Long scopeId, ContactSpaceKind kind, boolean isPublic,
                              boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO tournament_contact_space (id, scope_type, scope_id, space_kind, ref_id, is_public, "
                                + "created_at, updated_at, deleted_at) "
                                + "VALUES (UNHEX(REPLACE(UUID(), '-', '')), :st, :sid, :kind, 0, :pub, NOW(), NOW(), "
                                + (deleted ? "NOW()" : "NULL") + ")")
                .setParameter("st", scopeType.name())
                .setParameter("sid", scopeId)
                .setParameter("kind", kind.name())
                .setParameter("pub", isPublic)
                .executeUpdate();
    }

    private Long thread(ScopeType scopeType, Long scopeId, Long authorId, String title, ReadTrackingMode mode) {
        Long id = tx(() -> threadRepository.save(BulletinThreadEntity.builder()
                .scopeType(scopeType).scopeId(scopeId).authorId(authorId)
                .title(title).body("CMP0726 seed 本文").readTrackingMode(mode).build()).getId());
        threadIds.add(id);
        return id;
    }

    private Long reply(Long threadId, Long authorId, String body) {
        return tx(() -> replyRepository.save(BulletinReplyEntity.builder()
                .threadId(threadId).authorId(authorId).body(body).build()).getId());
    }

    private Long attachment(TargetType targetType, Long targetId, Long createdBy) {
        Long id = tx(() -> attachmentRepository.save(BulletinAttachmentEntity.builder()
                .targetType(targetType).targetId(targetId)
                .fileKey(String.join("/", "bulletin", "cmp0726", UUID.randomUUID() + ".png"))
                .originalFilename("cmp0726.png").fileSize(128L).contentType("image/png")
                .createdBy(createdBy).build()).getId());
        attachmentIds.add(id);
        return id;
    }

    private void readStatus(Long threadId, Long userId) {
        jdbc.update("INSERT INTO bulletin_read_status (thread_id, user_id, read_at) VALUES (?, ?, NOW())", threadId, userId);
    }

    private void reaction(TargetType targetType, Long targetId, Long userId) {
        jdbc.update("INSERT INTO bulletin_reactions (target_type, target_id, user_id, emoji, created_at) "
                + "VALUES (?, ?, ?, ?, NOW())", targetType.name(), targetId, userId, EMOJI);
    }
}
