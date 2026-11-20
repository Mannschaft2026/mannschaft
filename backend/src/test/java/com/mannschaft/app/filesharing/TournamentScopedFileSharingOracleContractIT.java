package com.mannschaft.app.filesharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.tournament.TournamentContactOracleFixture;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.mannschaft.app.tournament.TournamentContactOracleFixture.ABSENT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261116-0726 大会連絡スペースの存在オラクル是正 — 大会ファイル置き場・ファイル共有の受け入れテスト（試練・red）。
 *
 * <p>対象（設計書 §3「フォルダ・公開設定」「ファイル共有（TournamentFolderScopeAccessGuard 経由）」表）:</p>
 * <ul>
 *   <li>AC-8: {@code GET/POST /api/v1/tournaments/{t}/folders}・{@code .../divisions/{d}/folders}
 *       （GET は permitAll）。閲覧権なし（未認証含む）は大会／ディビジョン不在と同一の 404 TOUR_029、
 *       閲覧可・書込不可は 403 TOUR_031。</li>
 *   <li>AC-9: 大会スコープのフォルダ ID／ファイル ID を受けるファイル共有 EP。閲覧権なしは不在フォルダ
 *       （404 FILE_SHARING_001）／不在ファイル（404 FILE_SHARING_002）と同一。閲覧可・投稿不可は 403 TOUR_031。
 *       子リソース ID（commentId/tagId/linkId/permissionId）を受ける EP は本戦役対象外。</li>
 *   <li>AC-17（フォルダ側）: T2=BULLETIN 公開／T3=BULLETIN 非公開・CHAT 公開。フォルダは BULLETIN 基準で判定する。</li>
 *   <li>AC-19（フォルダ）: BULLETIN スペースだけが欠落／論理削除された大会では、参加チーム代表のフォルダ作成も
 *       404 TOUR_029 で DB 不変。</li>
 * </ul>
 *
 * <p><b>red の根拠（origin/main 33066499df）</b>: {@code TournamentContactAccessService#checkView} は閲覧権なしに
 * 403 TOUR_030、{@code checkPost} は投稿権なしに一律 403 TOUR_031 を返す（実在が漏れる）。大会フォルダ一覧の
 * 不在は TOUR_001／TOUR_002 で、閲覧拒否とコードが違う。{@code checkPost} はスペースの存在を見ないため
 * AC-19 の大会でも参加チーム代表のフォルダ作成が 201 で通る。</p>
 *
 * <p>判定は {@code FriendContentForwardExistenceOracleContractIT#assertSameAsMissing} の型（status・error.code・
 * error.message・本文全体の一致）＋ 副作用 COUNT 不変。実 JWT・実 Security フィルタ・実 MySQL（TEST_CONVENTION §3.1.2）。</p>
 */
@AutoConfigureMockMvc(addFilters = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261116-0726 大会ファイル置き場・ファイル共有の存在オラクル是正（実JWT/filter）")
class TournamentScopedFileSharingOracleContractIT extends AbstractMySqlIntegrationTest {

    /** 不在フォルダ ID（対照群）。 */
    private static final long ABSENT_FOLDER = ABSENT_ID - 11;
    /** 不在ファイル ID（対照群）。 */
    private static final long ABSENT_FILE = ABSENT_ID - 12;

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private SharedFolderRepository folderRepository;
    @Autowired private SharedFileRepository fileRepository;
    @Autowired private SharedFileVersionRepository versionRepository;
    @PersistenceContext private EntityManager em;

    private TournamentContactOracleFixture f;
    private String suffix;

    /** T1（非公開）のフォルダ・ファイル。 */
    private Long folderT1;
    private Long fileT1;
    /** D1（非公開）のフォルダ・ファイル。 */
    private Long folderD1;
    private Long fileD1;
    /** T2（BULLETIN 公開）のフォルダ・ファイル。 */
    private Long folderT2;
    private Long fileT2;
    /** T3（BULLETIN 非公開・CHAT 公開）のフォルダ・ファイル。 */
    private Long folderT3;
    private Long fileT3;

    @BeforeEach
    void fixtureを別TXでcommitする() {
        f = new TournamentContactOracleFixture(em, jdbc, txManager, users, organizations, teams, "CMP0726FS");
        f.setUp();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        f.tx(() -> {
            folderT1 = folder(FileScopeType.TOURNAMENT, f.t1, "T1");
            fileT1 = file(folderT1, "t1.pdf");
            folderD1 = folder(FileScopeType.TOURNAMENT_DIVISION, f.d1, "D1");
            fileD1 = file(folderD1, "d1.pdf");
            folderT2 = folder(FileScopeType.TOURNAMENT, f.t2, "T2");
            fileT2 = file(folderT2, "t2.pdf");
            folderT3 = folder(FileScopeType.TOURNAMENT, f.t3, "T3");
            fileT3 = file(folderT3, "t3.pdf");
            return null;
        });
    }

    @AfterEach
    void 所有行だけ片付ける() {
        try {
            String scopes = scopeCondition();
            String folderIds = "SELECT id FROM (SELECT id FROM shared_folders WHERE " + scopes + ") fx";
            String fileIds = "SELECT id FROM (SELECT id FROM shared_files WHERE folder_id IN (" + folderIds + ")) fy";
            jdbc.update("DELETE FROM file_permissions WHERE (target_type = 'FILE' AND target_id IN (" + fileIds
                    + ")) OR (target_type = 'FOLDER' AND target_id IN (" + folderIds + "))");
            for (String child : List.of("shared_file_stars", "shared_file_comments", "shared_file_tags",
                    "shared_file_links", "shared_file_versions")) {
                jdbc.update("DELETE FROM " + child + " WHERE file_id IN (" + fileIds + ")");
            }
            jdbc.update("DELETE FROM shared_files WHERE folder_id IN (" + folderIds + ")");
            jdbc.update("DELETE FROM shared_folders WHERE " + scopes);
            jdbc.update("DELETE FROM storage_acls WHERE owner_id IN (" + idList(f.userIds()) + ")");
            f.cleanup();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ========================================================================
    // AC-8: 大会／ディビジョンのフォルダ一覧（permitAll）・作成
    // ========================================================================

    @Test
    @DisplayName("AC-8: 閲覧権なし（部外者・主催組織一般・他テナントADMIN・辞退/失格チーム代表）の大会フォルダ一覧は不在大会と同一の 404 TOUR_029")
    void ac8_閲覧権なしの大会フォルダ一覧は不在と同一() throws Exception {
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(get("/api/v1/tournaments/{t}/folders", f.t1),
                    get("/api/v1/tournaments/{t}/folders", ABSENT_ID), actor, "TOUR_029");
        }
    }

    @Test
    @DisplayName("AC-8: 未認証の非公開大会フォルダ一覧（permitAll）は不在大会と同一の 404 TOUR_029、公開大会は 200")
    void ac8_未認証の大会フォルダ一覧() throws Exception {
        assertSameAsMissing(get("/api/v1/tournaments/{t}/folders", f.t1),
                get("/api/v1/tournaments/{t}/folders", ABSENT_ID), null, "TOUR_029");
        assertSameAsMissing(get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t1, f.d1),
                get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t1, ABSENT_ID), null, "TOUR_029");

        MvcResult pub = http(get("/api/v1/tournaments/{t}/folders", f.t2), null);
        assertThat(pub.getResponse().getStatus()).as(body(pub)).isEqualTo(200);
        assertThat(body(pub)).contains(folderName("T2"));
    }

    @Test
    @DisplayName("AC-8: 閲覧権なしのディビジョンフォルダ一覧は不在ディビジョンと同一の 404 TOUR_029（帰属違いのパスも同一）")
    void ac8_閲覧権なしのディビジョンフォルダ一覧は不在と同一() throws Exception {
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t1, f.d1),
                    get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t1, ABSENT_ID), actor, "TOUR_029");
        }
        // 帰属違い（D1 は T1 配下。T5 配下のパスで叩く）は主催 ADMIN でも不在と同一。
        assertSameAsMissing(get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t5, f.d1),
                get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t5, ABSENT_ID), f.orgAdminId, "TOUR_029");
    }

    @Test
    @DisplayName("AC-8: 閲覧権なしの大会／ディビジョンフォルダ作成は不在と同一の 404 TOUR_029 で、フォルダ行が作られない")
    void ac8_閲覧権なしのフォルダ作成は不在と同一で行なし() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : f.nonViewers()) {
            assertSameAsMissing(createTournamentFolder(f.t1, "越境T"), createTournamentFolder(ABSENT_ID, "越境T"),
                    actor, "TOUR_029");
            assertSameAsMissing(createDivisionFolder(f.t1, f.d1, "越境D"),
                    createDivisionFolder(f.t1, ABSENT_ID, "越境D"), actor, "TOUR_029");
        }
        assertThat(snapshot()).as("副作用なし").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-8: 閲覧可・書込不可（参加チーム一般メンバー）のフォルダ作成は 403 TOUR_031 で、フォルダ行が作られない")
    void ac8_閲覧可書込不可のフォルダ作成は403() throws Exception {
        Map<String, Object> before = snapshot();
        assertRejected(createTournamentFolder(f.t1, "一般T"), f.pMemberId, 403, "TOUR_031");
        assertRejected(createDivisionFolder(f.t1, f.d1, "一般D"), f.pMemberId, 403, "TOUR_031");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-8/AC-13: 未認証のフォルダ作成は実在・不在とも同一の 401")
    void ac8_未認証のフォルダ作成は同一の401() throws Exception {
        assertSameUnauthenticated(createTournamentFolder(f.t1, "未認証"), createTournamentFolder(ABSENT_ID, "未認証"));
        assertSameUnauthenticated(createDivisionFolder(f.t1, f.d1, "未認証"),
                createDivisionFolder(f.t1, ABSENT_ID, "未認証"));
    }

    @Test
    @DisplayName("AC-12: 主催ADMIN・SYSTEM_ADMIN・参加チーム代表/一般はフォルダ一覧 200、主催ADMIN・参加チーム代表/副代表は作成 201")
    void ac12_正常系のフォルダ一覧と作成() throws Exception {
        for (Long actor : List.of(f.orgAdminId, f.sysAdminId, f.pAdminId, f.pMemberId)) {
            MvcResult t = http(get("/api/v1/tournaments/{t}/folders", f.t1), actor);
            assertThat(t.getResponse().getStatus()).as("actor=" + actor + " " + body(t)).isEqualTo(200);
            assertThat(body(t)).contains(folderName("T1"));
            MvcResult d = http(get("/api/v1/tournaments/{t}/divisions/{d}/folders", f.t1, f.d1), actor);
            assertThat(d.getResponse().getStatus()).as("actor=" + actor + " " + body(d)).isEqualTo(200);
        }
        int i = 0;
        for (Long actor : List.of(f.orgAdminId, f.pAdminId, f.pDeputyId)) {
            MvcResult t = http(createTournamentFolder(f.t1, "正常T" + i), actor);
            assertThat(t.getResponse().getStatus()).as("actor=" + actor + " " + body(t)).isEqualTo(201);
            MvcResult d = http(createDivisionFolder(f.t1, f.d1, "正常D" + i), actor);
            assertThat(d.getResponse().getStatus()).as("actor=" + actor + " " + body(d)).isEqualTo(201);
            i++;
        }
    }

    // ========================================================================
    // AC-17（フォルダ側）: BULLETIN 公開／非公開でフォルダ側の判定が分かれる
    // ========================================================================

    @Test
    @DisplayName("AC-17: T2（BULLETIN公開・CHAT非公開）の部外者はフォルダ一覧 200・ファイル詳細 200、作成・ファイル削除は 403 TOUR_031")
    void ac17_BULLETIN公開の大会は閲覧可で書込403() throws Exception {
        Map<String, Object> before = snapshot();
        MvcResult listed = http(get("/api/v1/tournaments/{t}/folders", f.t2), f.outsiderId);
        assertThat(listed.getResponse().getStatus()).as(body(listed)).isEqualTo(200);
        MvcResult detail = http(get("/api/v1/files/{id}", fileT2), f.outsiderId);
        assertThat(detail.getResponse().getStatus()).as(body(detail)).isEqualTo(200);

        assertRejected(createTournamentFolder(f.t2, "公開越境"), f.outsiderId, 403, "TOUR_031");
        assertRejected(delete("/api/v1/files/{id}", fileT2), f.outsiderId, 403, "TOUR_031");
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-17: T3（BULLETIN非公開・CHAT公開）の部外者はフォルダ一覧・作成とも不在と同一の 404 TOUR_029、ファイル詳細は不在ファイルと同一")
    void ac17_BULLETIN非公開の大会は不在と同一() throws Exception {
        Map<String, Object> before = snapshot();
        assertSameAsMissing(get("/api/v1/tournaments/{t}/folders", f.t3),
                get("/api/v1/tournaments/{t}/folders", ABSENT_ID), f.outsiderId, "TOUR_029");
        assertSameAsMissing(createTournamentFolder(f.t3, "CHAT公開越境"), createTournamentFolder(ABSENT_ID, "CHAT公開越境"),
                f.outsiderId, "TOUR_029");
        assertSameAsMissing(get("/api/v1/files/{id}", fileT3), get("/api/v1/files/{id}", ABSENT_FILE),
                f.outsiderId, "FILE_SHARING_002");
        assertSameAsMissing(get("/api/v1/files/folders/{id}", folderT3), get("/api/v1/files/folders/{id}", ABSENT_FOLDER),
                f.outsiderId, "FILE_SHARING_001");
        assertThat(snapshot()).isEqualTo(before);
    }

    // ========================================================================
    // AC-19（フォルダ）: BULLETIN スペースのみ欠落／論理削除
    // ========================================================================

    @Test
    @DisplayName("AC-19: BULLETIN スペース行なしの大会（T4m）で参加チーム代表・副代表のフォルダ作成は不在と同一の 404 TOUR_029 で DB 不変")
    void ac19_BULLETINスペース欠落のフォルダ作成は404() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : List.of(f.pAdminId, f.pDeputyId)) {
            assertSameAsMissing(createTournamentFolder(f.t4Missing, "欠落"), createTournamentFolder(ABSENT_ID, "欠落"),
                    actor, "TOUR_029");
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(countFolders("TOURNAMENT", f.t4Missing)).isZero();
    }

    @Test
    @DisplayName("AC-19: BULLETIN スペース論理削除の大会（T4d）で参加チーム代表・副代表のフォルダ作成は不在と同一の 404 TOUR_029 で DB 不変")
    void ac19_BULLETINスペース論理削除のフォルダ作成は404() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : List.of(f.pAdminId, f.pDeputyId)) {
            assertSameAsMissing(createTournamentFolder(f.t4Deleted, "論削"), createTournamentFolder(ABSENT_ID, "論削"),
                    actor, "TOUR_029");
        }
        assertThat(snapshot()).isEqualTo(before);
        assertThat(countFolders("TOURNAMENT", f.t4Deleted)).isZero();
    }

    // ========================================================================
    // AC-9: 大会スコープのフォルダ ID／ファイル ID を受けるファイル共有 EP
    // ========================================================================

    @Test
    @DisplayName("AC-9: 閲覧系のフォルダID EP（ファイル一覧・フォルダ詳細・子フォルダ一覧・権限一覧FOLDER）は閲覧権なしに不在フォルダと同一の 404 FILE_SHARING_001")
    void ac9_閲覧系フォルダIDEPは不在フォルダと同一() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : f.nonViewers()) {
            for (Map.Entry<String, Function<Long, MockHttpServletRequestBuilder>> ep : folderViewEps().entrySet()) {
                for (Long folder : List.of(folderT1, folderD1)) {
                    assertSameAsMissing(ep.getValue().apply(folder), ep.getValue().apply(ABSENT_FOLDER), actor,
                            "FILE_SHARING_001", ep.getKey());
                }
            }
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-9: 閲覧系のファイルID EP（詳細・DL URL・コメント・タグ・版一覧・スター・権限一覧FILE）は閲覧権なしに不在ファイルと同一の 404 FILE_SHARING_002、行は増えない")
    void ac9_閲覧系ファイルIDEPは不在ファイルと同一() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : f.nonViewers()) {
            for (Map.Entry<String, Function<Long, MockHttpServletRequestBuilder>> ep : fileViewEps().entrySet()) {
                for (Long file : List.of(fileT1, fileD1)) {
                    assertSameAsMissing(ep.getValue().apply(file), ep.getValue().apply(ABSENT_FILE), actor,
                            "FILE_SHARING_002", ep.getKey());
                }
            }
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-9: 投稿系のフォルダID EP（ファイル登録・presign・フォルダ削除・権限付与FOLDER）は閲覧権なしに不在フォルダと同一の 404 FILE_SHARING_001、DB・ACL 不変")
    void ac9_投稿系フォルダIDEPは不在フォルダと同一() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : f.nonViewers()) {
            for (Map.Entry<String, Function<Long, MockHttpServletRequestBuilder>> ep : folderPostEps().entrySet()) {
                for (Long folder : List.of(folderT1, folderD1)) {
                    assertSameAsMissing(ep.getValue().apply(folder), ep.getValue().apply(ABSENT_FOLDER), actor,
                            "FILE_SHARING_001", ep.getKey());
                }
            }
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-9: 投稿系のファイルID EP（更新・削除・版追加・リンク一覧/発行・権限付与FILE）は閲覧権なしに不在ファイルと同一の 404 FILE_SHARING_002、DB 不変")
    void ac9_投稿系ファイルIDEPは不在ファイルと同一() throws Exception {
        Map<String, Object> before = snapshot();
        for (Long actor : f.nonViewers()) {
            for (Map.Entry<String, Function<Long, MockHttpServletRequestBuilder>> ep : filePostEps().entrySet()) {
                for (Long file : List.of(fileT1, fileD1)) {
                    assertSameAsMissing(ep.getValue().apply(file), ep.getValue().apply(ABSENT_FILE), actor,
                            "FILE_SHARING_002", ep.getKey());
                }
            }
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-9: 閲覧可・投稿不可（参加チーム一般メンバー）の投稿系フォルダID/ファイルID EP は 403 TOUR_031、DB 不変")
    void ac9_閲覧可投稿不可は403_TOUR_031() throws Exception {
        Map<String, Object> before = snapshot();
        for (Map.Entry<String, Function<Long, MockHttpServletRequestBuilder>> ep : folderPostEps().entrySet()) {
            assertRejected(ep.getValue().apply(folderT1), f.pMemberId, 403, "TOUR_031", ep.getKey());
        }
        for (Map.Entry<String, Function<Long, MockHttpServletRequestBuilder>> ep : filePostEps().entrySet()) {
            assertRejected(ep.getValue().apply(fileT1), f.pMemberId, 403, "TOUR_031", ep.getKey());
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-12: 閲覧可（参加チーム一般・主催ADMIN・SYSTEM_ADMIN）はファイル詳細・フォルダ詳細・コメント一覧を 200 で取得できる")
    void ac12_閲覧可は閲覧系EPで200() throws Exception {
        for (Long actor : List.of(f.pMemberId, f.orgAdminId, f.sysAdminId)) {
            for (MockHttpServletRequestBuilder req : List.of(
                    get("/api/v1/files/{id}", fileT1),
                    get("/api/v1/files/folders/{id}", folderT1),
                    get("/api/v1/files/{id}/comments", fileD1))) {
                MvcResult r = http(req, actor);
                assertThat(r.getResponse().getStatus()).as("actor=" + actor + " " + body(r)).isEqualTo(200);
            }
        }
    }

    @Test
    @DisplayName("AC-13: 未認証のファイル共有 EP（ファイル詳細・フォルダ詳細・ファイル削除）は実在・不在とも同一の 401")
    void ac13_未認証のファイル共有EPは同一の401() throws Exception {
        assertSameUnauthenticated(get("/api/v1/files/{id}", fileT1), get("/api/v1/files/{id}", ABSENT_FILE));
        assertSameUnauthenticated(get("/api/v1/files/folders/{id}", folderT1),
                get("/api/v1/files/folders/{id}", ABSENT_FOLDER));
        assertSameUnauthenticated(delete("/api/v1/files/{id}", fileT1), delete("/api/v1/files/{id}", ABSENT_FILE));
    }

    // ========================================================================
    // EP 定義（設計書 §3 ファイル共有表。子リソース ID EP は対象外）
    // ========================================================================

    private Map<String, Function<Long, MockHttpServletRequestBuilder>> folderViewEps() {
        Map<String, Function<Long, MockHttpServletRequestBuilder>> m = new LinkedHashMap<>();
        m.put("GET /files?folderId", id -> get("/api/v1/files").param("folderId", String.valueOf(id)));
        m.put("GET /files/folders/{id}", id -> get("/api/v1/files/folders/{id}", id));
        // 汎用フォルダ一覧の parent_id 指定は親フォルダ実体のスコープで認可する（大会フォルダも到達する）。
        m.put("GET /files/folders?parent_id", id -> get("/api/v1/files/folders")
                .param("scope_type", "TOURNAMENT").param("scope_id", "1").param("parent_id", String.valueOf(id)));
        m.put("GET /file-permissions FOLDER", id -> get("/api/v1/file-permissions")
                .param("targetType", "FOLDER").param("targetId", String.valueOf(id)));
        return m;
    }

    private Map<String, Function<Long, MockHttpServletRequestBuilder>> fileViewEps() {
        Map<String, Function<Long, MockHttpServletRequestBuilder>> m = new LinkedHashMap<>();
        m.put("GET /files/{id}", id -> get("/api/v1/files/{id}", id));
        m.put("GET /files/{id}/download-url", id -> get("/api/v1/files/{id}/download-url", id));
        m.put("GET /files/{id}/comments", id -> get("/api/v1/files/{id}/comments", id));
        m.put("POST /files/{id}/comments", id -> json(post("/api/v1/files/{id}/comments", id),
                Map.of("body", "CMP0726 越境コメント")));
        m.put("GET /files/{id}/tags", id -> get("/api/v1/files/{id}/tags", id));
        m.put("POST /files/{id}/tags", id -> json(post("/api/v1/files/{id}/tags", id), Map.of("tagName", "越境")));
        m.put("GET /files/{id}/versions", id -> get("/api/v1/files/{id}/versions", id));
        m.put("POST /files/{id}/stars", id -> post("/api/v1/files/{id}/stars", id));
        m.put("DELETE /files/{id}/stars", id -> delete("/api/v1/files/{id}/stars", id));
        m.put("GET /file-permissions FILE", id -> get("/api/v1/file-permissions")
                .param("targetType", "FILE").param("targetId", String.valueOf(id)));
        return m;
    }

    private Map<String, Function<Long, MockHttpServletRequestBuilder>> folderPostEps() {
        Map<String, Function<Long, MockHttpServletRequestBuilder>> m = new LinkedHashMap<>();
        m.put("POST /files", id -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("folderId", id);
            b.put("name", "cmp0726-越境.pdf");
            b.put("fileKey", "files/TOURNAMENT/0/cmp0726-" + UUID.randomUUID() + ".pdf");
            b.put("fileSize", 10L);
            b.put("contentType", "application/pdf");
            return json(post("/api/v1/files"), b);
        });
        m.put("POST /files/presign-upload", id -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("folderId", id);
            b.put("fileName", "cmp0726-越境.pdf");
            b.put("contentType", "application/pdf");
            b.put("fileSize", 10L);
            return json(post("/api/v1/files/presign-upload"), b);
        });
        m.put("DELETE /files/folders/{id}", id -> delete("/api/v1/files/folders/{id}", id));
        m.put("POST /file-permissions FOLDER", id -> json(post("/api/v1/file-permissions"), permissionBody("FOLDER", id)));
        return m;
    }

    private Map<String, Function<Long, MockHttpServletRequestBuilder>> filePostEps() {
        Map<String, Function<Long, MockHttpServletRequestBuilder>> m = new LinkedHashMap<>();
        m.put("PATCH /files/{id}", id -> json(patch("/api/v1/files/{id}", id), Map.of("name", "cmp0726-改竄.pdf")));
        m.put("DELETE /files/{id}", id -> delete("/api/v1/files/{id}", id));
        m.put("POST /files/{id}/versions", id -> {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("fileKey", "files/TOURNAMENT/0/cmp0726-v-" + UUID.randomUUID() + ".pdf");
            b.put("fileSize", 10L);
            b.put("contentType", "application/pdf");
            return json(post("/api/v1/files/{id}/versions", id), b);
        });
        m.put("GET /files/{id}/links", id -> get("/api/v1/files/{id}/links", id));
        m.put("POST /files/{id}/links", id -> json(post("/api/v1/files/{id}/links", id),
                Map.of("expiresAt", LocalDateTime.now().plusDays(1).withNano(0).toString())));
        m.put("POST /file-permissions FILE", id -> json(post("/api/v1/file-permissions"), permissionBody("FILE", id)));
        return m;
    }

    private Map<String, Object> permissionBody(String targetType, Long targetId) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("targetType", targetType);
        b.put("targetId", targetId);
        b.put("permissionType", "READ");
        b.put("permissionTargetType", "USER");
        b.put("permissionTargetId", f.outsiderId);
        return b;
    }

    private MockHttpServletRequestBuilder createTournamentFolder(Long tid, String name) throws Exception {
        return json(post("/api/v1/tournaments/{t}/folders", tid), folderBody("TOURNAMENT", name));
    }

    private MockHttpServletRequestBuilder createDivisionFolder(Long tid, Long did, String name) throws Exception {
        return json(post("/api/v1/tournaments/{t}/divisions/{d}/folders", tid, did),
                folderBody("TOURNAMENT_DIVISION", name));
    }

    private Map<String, Object> folderBody(String scopeType, String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "CMP0726 " + name + " " + suffix + " " + UUID.randomUUID().toString().substring(0, 4));
        b.put("scopeType", scopeType);
        return b;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder req, Map<String, ?> body) {
        try {
            return req.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ========================================================================
    // 判定
    // ========================================================================

    private void assertSameAsMissing(MockHttpServletRequestBuilder existing, MockHttpServletRequestBuilder missing,
                                     Long actor, String expectedCode) throws Exception {
        assertSameAsMissing(existing, missing, actor, expectedCode, "");
    }

    /**
     * 秘匿対象への応答が、不在 ID への応答と status・error.code・error.message・本文全体まで一致し、
     * 期待の絶対値（404 + expectedCode）であることを検査する（{@code FriendContentForwardExistenceOracleContractIT} の型）。
     */
    private void assertSameAsMissing(MockHttpServletRequestBuilder existing, MockHttpServletRequestBuilder missing,
                                     Long actor, String expectedCode, String ep) throws Exception {
        MvcResult m = http(missing, actor);
        MvcResult e = http(existing, actor);
        String label = ep + " actor=" + actor + " existing=" + body(e) + " missing=" + body(m);
        assertThat(m.getResponse().getStatus()).as("不在側の絶対値 " + label).isEqualTo(404);
        assertThat(json(m).path("error").path("code").asText()).as("不在側の絶対値 " + label).isEqualTo(expectedCode);
        assertThat(e.getResponse().getStatus()).as("status が不在と一致 " + label).isEqualTo(m.getResponse().getStatus());
        assertThat(json(e).path("error").path("code").asText()).as("error.code が不在と一致 " + label)
                .isEqualTo(expectedCode);
        assertThat(json(e).path("error").path("message").asText()).as("error.message が不在と一致 " + label)
                .isEqualTo(json(m).path("error").path("message").asText());
        assertThat(json(e)).as("本文全体が不在と一致 " + label).isEqualTo(json(m));
    }

    private void assertRejected(MockHttpServletRequestBuilder request, Long actor, int status, String code)
            throws Exception {
        assertRejected(request, actor, status, code, "");
    }

    private void assertRejected(MockHttpServletRequestBuilder request, Long actor, int status, String code, String ep)
            throws Exception {
        MvcResult r = http(request, actor);
        String label = ep + " actor=" + actor + " " + body(r);
        assertThat(r.getResponse().getStatus()).as(label).isEqualTo(status);
        assertThat(json(r).path("error").path("code").asText()).as(label).isEqualTo(code);
    }

    private void assertSameUnauthenticated(MockHttpServletRequestBuilder existing, MockHttpServletRequestBuilder missing)
            throws Exception {
        MvcResult m = http(missing, null);
        MvcResult e = http(existing, null);
        String label = "existing=" + body(e) + " missing=" + body(m);
        assertThat(m.getResponse().getStatus()).as(label).isEqualTo(401);
        assertThat(e.getResponse().getStatus()).as(label).isEqualTo(401);
        assertThat(json(e).path("error").path("code").asText()).as(label)
                .isEqualTo(json(m).path("error").path("code").asText());
    }

    /**
     * 副作用の観測点（フォルダ・ファイル・子行・権限・ストレージ ACL）。拒否の前後でこの写像が一致することを要求する。
     */
    private Map<String, Object> snapshot() {
        String scopes = scopeCondition();
        String folderIds = "SELECT id FROM shared_folders WHERE " + scopes;
        String fileIds = "SELECT id FROM shared_files WHERE folder_id IN (" + folderIds + ")";
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("folders", count("SELECT COUNT(*) FROM shared_folders WHERE deleted_at IS NULL AND " + scopes));
        s.put("files", count("SELECT COUNT(*) FROM shared_files WHERE deleted_at IS NULL AND folder_id IN ("
                + folderIds + ")"));
        s.put("fileNames", jdbc.queryForList("SELECT name FROM shared_files WHERE folder_id IN (" + folderIds
                + ") ORDER BY id", String.class));
        for (String child : List.of("shared_file_stars", "shared_file_comments", "shared_file_tags",
                "shared_file_links", "shared_file_versions")) {
            s.put(child, count("SELECT COUNT(*) FROM " + child + " WHERE file_id IN (" + fileIds + ")"));
        }
        s.put("file_permissions", count("SELECT COUNT(*) FROM file_permissions WHERE (target_type = 'FILE' AND target_id IN ("
                + fileIds + ")) OR (target_type = 'FOLDER' AND target_id IN (" + folderIds + "))"));
        s.put("storage_acls", count("SELECT COUNT(*) FROM storage_acls WHERE owner_id IN (" + idList(f.userIds()) + ")"));
        return s;
    }

    private String scopeCondition() {
        return "((scope_type = 'TOURNAMENT' AND scope_ref_id IN (" + idList(f.tournamentIds()) + "))"
                + " OR (scope_type = 'TOURNAMENT_DIVISION' AND scope_ref_id IN (" + idList(f.divisionIds()) + ")))";
    }

    private int countFolders(String scopeType, Long scopeRefId) {
        return count("SELECT COUNT(*) FROM shared_folders WHERE scope_type = '" + scopeType
                + "' AND scope_ref_id = " + scopeRefId);
    }

    private int count(String sql) {
        Integer n = jdbc.queryForObject(sql, Integer.class);
        return n == null ? 0 : n;
    }

    private static String idList(List<Long> ids) {
        List<Long> safe = new ArrayList<>(ids);
        if (safe.isEmpty()) {
            safe.add(-1L);
        }
        return safe.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // ========================================================================
    // HTTP・seed
    // ========================================================================

    private MvcResult http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        String s = body(result);
        return s.isBlank() ? mapper.createObjectNode() : mapper.readTree(s);
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String folderName(String label) {
        return "CMP0726 seed " + label + " " + suffix;
    }

    private Long folder(FileScopeType scopeType, Long scopeRefId, String label) {
        return folderRepository.save(SharedFolderEntity.builder()
                .scopeType(scopeType).organizationId(f.organizerOrgId).scopeRefId(scopeRefId)
                .name(folderName(label)).createdBy(f.orgAdminId).build()).getId();
    }

    private Long file(Long folderId, String name) {
        String fileKey = "files/TOURNAMENT/0/cmp0726-" + UUID.randomUUID() + "-" + name;
        Long id = fileRepository.save(SharedFileEntity.builder()
                .folderId(folderId).name("cmp0726-" + name).fileKey(fileKey)
                .fileSize(1024L).contentType("application/pdf").createdBy(f.orgAdminId).build()).getId();
        versionRepository.save(SharedFileVersionEntity.builder()
                .fileId(id).versionNumber(1).fileKey(fileKey)
                .fileSize(1024L).contentType("application/pdf").uploadedBy(f.orgAdminId).build());
        return id;
    }
}
