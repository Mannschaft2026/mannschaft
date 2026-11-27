package com.mannschaft.app.filesharing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.entity.SharedFileVersionEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileVersionRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.SharedFileLinkService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261124-0159 PR-P: 公開ファイルリンクの多層防御（F00 §20.5.3 ファイル行・AC-0159-26 / AC-0159-08）。
 *
 * <ol>
 *   <li>発行: 非公開チームのファイルへのリンク発行は（既存の管理者認可を通った後に）403 {@code FILE_SHARING_021}。</li>
 *   <li>一括無効化: {@link SharedFileLinkService#deactivateLinksForTeam} がそのチームのリンクだけを
 *       {@code is_active = FALSE} にする（非公開化フロー PR-T・是正 migration PR-M が使う）。</li>
 *   <li>アクセス時の再確認: {@code is_active = TRUE} のまま残ったリンクでも、所属チームが非公開なら
 *       access・download-url は無効化済みリンクと同一応答（410 {@code FILE_SHARING_018}）で、URL もファイル名も返さない。
 *       閲覧者（未ログイン・ログイン済み部外者・外されたサポーター）によらない。</li>
 * </ol>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 公開ファイルリンクの親チーム上限")
class SharedFileLinkMembersOnlyTeamIT extends AbstractMySqlIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SharedFolderRepository folderRepository;
    @Autowired private SharedFileRepository fileRepository;
    @Autowired private SharedFileVersionRepository versionRepository;
    @Autowired private SharedFileLinkRepository linkRepository;
    @Autowired private StorageAclService storageAclService;
    @Autowired private SecretTokenVault vault;
    @Autowired private SharedFileLinkService linkService;
    @PersistenceContext private EntityManager em;

    @MockitoBean
    private R2StorageService r2StorageService;

    private ScopeContentFixture fixture;
    private Long fileId;
    private String fileName;

    private record Resp(int status, String code, String message, String body) {
        boolean sameAs(Resp other) {
            return status == other.status && Objects.equals(code, other.code)
                    && Objects.equals(message, other.message);
        }
    }

    @BeforeEach
    void setUp() {
        given(r2StorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://r2.example.com/signed-c59");
        fixture = ScopeContentFixture.create(em);
        fileName = "c59-secret-" + UUID.randomUUID() + ".pdf";
        fileId = seedFile(fixture.teamId, fileName);
        em.flush();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("非公開チームのファイルへのリンク発行は ADMIN でも 403 FILE_SHARING_021 で、リンクは作られない")
    void 非公開チームのファイルにはリンクを発行しない() throws Exception {
        Resp resp = perform(post("/api/v1/files/{fileId}/links", fileId).contentType(MediaType.APPLICATION_JSON)
                .content(createBody()), fixture.user(Actor.ADMIN));

        assertThat(resp.status()).isEqualTo(403);
        assertThat(resp.code()).isEqualTo("FILE_SHARING_021");
        assertThat(linkRepository.countByFileId(fileId)).isZero();
    }

    @Test
    @DisplayName("公開チームのファイルへのリンク発行は従来どおり成功する")
    void 公開チームでは発行できる() throws Exception {
        setVisibility(fixture.teamId, "PUBLIC");
        Resp resp = perform(post("/api/v1/files/{fileId}/links", fileId).contentType(MediaType.APPLICATION_JSON)
                .content(createBody()), fixture.user(Actor.ADMIN));
        assertThat(resp.status()).as("%s", resp).isBetween(200, 201);
    }

    @Test
    @DisplayName("is_active=TRUE のまま残ったリンクも、非公開チームなら access・download-url は無効化済みリンクと同一応答（410 FILE_SHARING_018）で URL もファイル名も返さない")
    void 非公開チームのリンクは無効化済みと同一応答() throws Exception {
        String live = seedLink(fileId, true);
        Long otherFile = seedFile(fixture.otherTeamId, "c59-other-" + UUID.randomUUID() + ".pdf");
        setVisibility(fixture.otherTeamId, "PUBLIC");
        String inactive = seedLink(otherFile, false);

        for (String action : List.of("access", "download-url")) {
            Resp deactivated = perform(post("/api/v1/public/file-links/{token}/" + action, inactive), null);
            assertThat(deactivated.status()).isEqualTo(410);
            assertThat(deactivated.code()).isEqualTo("FILE_SHARING_018");
            for (Long viewer : Arrays.asList(null, fixture.user(Actor.OUTSIDER), fixture.user(Actor.SUPPORTER))) {
                Resp actual = perform(post("/api/v1/public/file-links/{token}/" + action, live), viewer);
                assertThat(actual.sameAs(deactivated))
                        .as("%s viewer=%s actual=%s deactivated=%s", action, viewer, actual, deactivated).isTrue();
                assertThat(actual.body()).doesNotContain(fileName).doesNotContain("r2.example.com");
            }
        }
    }

    @Test
    @DisplayName("公開チームのリンクは従来どおり access 200・download-url 200")
    void 公開チームのリンクは従来どおり() throws Exception {
        setVisibility(fixture.teamId, "SUPPORTERS_AND_ABOVE");
        String live = seedLink(fileId, true);
        assertThat(perform(post("/api/v1/public/file-links/{token}/access", live), null).status()).isEqualTo(200);
        assertThat(perform(post("/api/v1/public/file-links/{token}/download-url", live), null).status())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("deactivateLinksForTeam はそのチームのファイルのリンクだけを is_active=FALSE にし、件数を返す")
    void チーム単位の一括無効化() {
        seedLink(fileId, true);
        seedLink(fileId, true);
        Long otherFile = seedFile(fixture.otherTeamId, "c59-other-" + UUID.randomUUID() + ".pdf");
        seedLink(otherFile, true);
        em.flush();

        int deactivated = linkService.deactivateLinksForTeam(fixture.teamId);
        em.flush();
        em.clear();

        assertThat(deactivated).isEqualTo(2);
        assertThat(linkRepository.findByFileIdOrderByCreatedAtDesc(fileId))
                .allMatch(l -> !l.isActive());
        assertThat(linkRepository.findByFileIdOrderByCreatedAtDesc(otherFile))
                .allMatch(SharedFileLinkEntity::isActive);
    }

    // ─── ヘルパー ─────────────────────────────────────────

    private String createBody() throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(
                "expiresAt", LocalDateTime.now().plusDays(3).withNano(0).toString(),
                "downloadAllowed", true));
    }

    private Resp perform(MockHttpServletRequestBuilder builder, Long userId) throws Exception {
        if (userId == null) {
            SecurityContextHolder.clearContext();
        } else {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        }
        MvcResult result = mockMvc.perform(builder).andReturn();
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        String code = null;
        String message = null;
        if (!body.isBlank() && body.trim().startsWith("{")) {
            JsonNode error = objectMapper.readTree(body).path("error");
            code = error.path("code").isMissingNode() ? null : error.path("code").asText();
            message = error.path("message").isMissingNode() ? null : error.path("message").asText();
        }
        return new Resp(result.getResponse().getStatus(), code, message, body);
    }

    private void setVisibility(Long teamId, String visibility) {
        ScopeContentFixture.setTeamVisibility(em, teamId, visibility);
        em.flush();
        em.clear();
    }

    private Long seedFile(Long teamId, String name) {
        SharedFolderEntity folder = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM).teamId(teamId).name("上限試練フォルダ").createdBy(1L).build());
        String fileKey = "team/" + teamId + "/" + UUID.randomUUID() + "/" + name;
        SharedFileEntity file = fileRepository.save(SharedFileEntity.builder()
                .folderId(folder.getId()).name(name).fileKey(fileKey)
                .fileSize(1024L).contentType("application/pdf").createdBy(1L).build());
        SharedFileVersionEntity version = versionRepository.save(SharedFileVersionEntity.builder()
                .fileId(file.getId()).versionNumber(1).fileKey(fileKey).fileSize(1024L)
                .contentType("application/pdf").uploadedBy(1L).build());
        StorageAclScope scope = StorageAclScope.team(teamId);
        StorageAclContentReference parent = new StorageAclContentReference("SHARED_FOLDER", folder.getId().toString());
        storageAclService.registerPending(fileKey, 1L, scope, "application/pdf", Duration.ofMinutes(15), parent);
        storageAclService.claimPending(fileKey, 1L, scope, parent,
                new StorageAclAttachmentBinding("SHARED_FILE_VERSION", version.getId().toString()));
        return file.getId();
    }

    private String seedLink(Long targetFileId, boolean active) {
        String plaintext = vault.issueBase64Url().plaintext();
        SharedFileLinkEntity saved = linkRepository.save(SharedFileLinkEntity.builder()
                .fileId(targetFileId).tokenHash(vault.hash(plaintext))
                .expiresAt(LocalDateTime.now().plusDays(1)).downloadAllowed(true).accessCount(0).createdBy(1L)
                .build());
        if (!active) {
            saved.deactivate();
            linkRepository.save(saved);
        }
        return plaintext;
    }
}
