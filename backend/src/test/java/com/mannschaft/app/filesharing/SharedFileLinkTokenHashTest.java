package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.MembersOnlyTeamQuery;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.PublicFileLinkResponse;
import com.mannschaft.app.filesharing.dto.LinkIssuedResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFileLinkService;
import com.mannschaft.app.filesharing.service.SharedFileService;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * CMP-260826-1457 W2 filesharing: 共有リンクトークンのハッシュ保存化の受け入れ試験（AC-x1〜x5・AC-A の業務層）。
 *
 * <p>DB に平文を残さない（保存値は {@code vault.hash(平文)}）、発行応答の平文で照合が成功する、
 * 一覧応答に平文が出ない、不在・失効・期限切れ・パスワード不一致・DL 不許可の各分岐の応答コードが変わらない、
 * ログに平文を出さない、を検証する。列の形と旧リンクの全失効は {@code FlywayFromScratchMigrationTest} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileLink トークンのハッシュ保存化（CMP-260826-1457 W2）")
class SharedFileLinkTokenHashTest {

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;

    @Mock
    private SharedFileLinkRepository linkRepository;
    @Mock
    private SharedFileService fileService;
    @Spy
    private FileSharingMapper fileSharingMapper = new FileSharingMapperImpl();
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;
    @Mock
    private SharedFolderQueryService folderQueryService;
    @Spy
    private SecretTokenVault vault = new SecretTokenVault();

    /** CMP-261124-0159: 親チーム上限の判定（既定では上限に掛からない）。 */
    @Mock
    private MembersOnlyTeamQuery membersOnlyTeamQuery;

    @InjectMocks
    private SharedFileLinkService service;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(SharedFileLinkService.class);
        serviceLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logAppender);
    }

    private LinkIssuedResponse issue(String password, boolean downloadAllowed) {
        given(linkRepository.save(any(SharedFileLinkEntity.class))).willAnswer(inv -> inv.getArgument(0));
        if (password != null) {
            given(passwordEncoder.encode(password)).willReturn("$2a$encoded");
        }
        return service.createLink(FILE_ID, USER_ID,
                new CreateLinkRequest(LocalDateTime.now().plusDays(7), password, downloadAllowed));
    }

    private SharedFileLinkEntity savedEntity() {
        ArgumentCaptor<SharedFileLinkEntity> captor = ArgumentCaptor.forClass(SharedFileLinkEntity.class);
        verify(linkRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---------------- AC-x1 / AC-x2: 保存値はハッシュ、発行応答の平文で照合が成功する ----------------

    @Test
    @DisplayName("AC-x2: 発行応答に平文が載り、保存値は vault.hash(平文) で平文とは異なる")
    void 発行応答の平文とハッシュ保存() {
        LinkIssuedResponse issued = issue(null, false);

        assertThat(issued.token()).as("平文は発行応答にだけ載る（Base64URL 43字）").matches("^[A-Za-z0-9_-]{43}$");
        SharedFileLinkEntity stored = savedEntity();
        assertThat(stored.getTokenHash()).isEqualTo(vault.hash(issued.token())).isNotEqualTo(issued.token());
        assertThat(stored.getTokenHash()).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("AC-x1: Entity に平文のトークンを持つフィールドが無い（tokenHash だけ）")
    void entityに平文フィールドが無い() {
        List<String> names = Arrays.stream(SharedFileLinkEntity.class.getDeclaredFields()).map(Field::getName).toList();
        assertThat(names).contains("tokenHash").doesNotContain("token");
    }

    @Test
    @DisplayName("AC-x2: 発行応答の平文で閲覧（access）が成功し、ハッシュで検索される")
    void 発行応答の平文で閲覧が成功する() {
        LinkIssuedResponse issued = issue(null, false);
        SharedFileLinkEntity stored = savedEntity();
        given(linkRepository.findByTokenHash(vault.hash(issued.token()))).willReturn(Optional.of(stored));
        FileResponse file = new FileResponse(FILE_ID, 1L, "a.pdf", "key", 1L, "application/pdf",
                null, USER_ID, 1, null, null, null, null);
        given(fileService.getFileForSharedLink(FILE_ID)).willReturn(file);

        PublicFileLinkResponse result = service.accessLinkPublic(issued.token(), null);

        assertThat(result.getName()).isEqualTo("a.pdf");
        assertThat(stored.getAccessCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-x2: DL 許可リンクは発行応答の平文で DL URL を発行できる")
    void 発行応答の平文でDL_URL発行() {
        LinkIssuedResponse issued = issue(null, true);
        SharedFileLinkEntity stored = savedEntity();
        given(linkRepository.findByTokenHash(vault.hash(issued.token()))).willReturn(Optional.of(stored));
        SharedFileDownloadUrlResponse dl = org.mockito.Mockito.mock(SharedFileDownloadUrlResponse.class);
        given(fileService.presignDownloadForSharedLink(FILE_ID)).willReturn(dl);

        assertThat(service.presignDownloadForLink(issued.token(), null)).isSameAs(dl);
    }

    @Test
    @DisplayName("AC-x2: パスワード付きリンクは、正しいパスワードで閲覧でき、誤りは LINK_PASSWORD_INVALID")
    void パスワード付きリンク() {
        LinkIssuedResponse issued = issue("secret", false);
        SharedFileLinkEntity stored = savedEntity();
        given(linkRepository.findByTokenHash(vault.hash(issued.token()))).willReturn(Optional.of(stored));
        given(passwordEncoder.matches("wrong", "$2a$encoded")).willReturn(false);
        given(passwordEncoder.matches("secret", "$2a$encoded")).willReturn(true);
        given(fileService.getFileForSharedLink(FILE_ID)).willReturn(org.mockito.Mockito.mock(FileResponse.class));

        assertThatThrownBy(() -> service.accessLinkPublic(issued.token(), new AccessLinkRequest("wrong")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FileSharingErrorCode.LINK_PASSWORD_INVALID));
        assertThat(service.accessLinkPublic(issued.token(), new AccessLinkRequest("secret"))).isNotNull();
    }

    // ---------------- AC-x4: 拒否される分岐（応答コードは変えない） ----------------

    @Test
    @DisplayName("AC-x4: 保存値（ハッシュ）を平文として提示しても照合に当たらない（404 LINK_NOT_FOUND）")
    void ハッシュ値の提示は拒否される() {
        LinkIssuedResponse issued = issue(null, false);
        String storedHash = savedEntity().getTokenHash();
        // 保存値（ハッシュ）を平文として提示すると hash(ハッシュ) で引かれるため、保存済みの行には当たらない
        given(linkRepository.findByTokenHash(vault.hash(storedHash))).willReturn(Optional.empty());

        assertThat(issued.token()).isNotEqualTo(storedHash);
        assertThatThrownBy(() -> service.accessLinkPublic(storedHash, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FileSharingErrorCode.LINK_NOT_FOUND));
    }

    @Test
    @DisplayName("AC-x4: 失効済みは LINK_INACTIVE、期限切れは LINK_EXPIRED、DL 不許可は LINK_DOWNLOAD_NOT_ALLOWED")
    void 失効_期限切れ_DL不許可() {
        LinkIssuedResponse issued = issue(null, false);
        SharedFileLinkEntity stored = savedEntity();
        given(linkRepository.findByTokenHash(vault.hash(issued.token()))).willReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.presignDownloadForLink(issued.token(), null))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FileSharingErrorCode.LINK_DOWNLOAD_NOT_ALLOWED));

        stored.deactivate();
        assertThatThrownBy(() -> service.accessLinkPublic(issued.token(), null))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FileSharingErrorCode.LINK_INACTIVE));

        SharedFileLinkEntity expired = SharedFileLinkEntity.builder().fileId(FILE_ID)
                .tokenHash(vault.hash("expired-plain")).expiresAt(LocalDateTime.now().minusDays(1)).build();
        given(linkRepository.findByTokenHash(vault.hash("expired-plain"))).willReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.accessLinkPublic("expired-plain", null))
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(FileSharingErrorCode.LINK_EXPIRED));
    }

    // ---------------- AC-x3: 一覧応答に平文が出ない ----------------

    @Test
    @DisplayName("AC-x3: 一覧応答（LinkResponse）に token・url を含むフィールド・JSON キーが無い")
    void 一覧応答に平文が無い() throws Exception {
        List<String> fields = Arrays.stream(LinkResponse.class.getDeclaredFields()).map(Field::getName).toList();
        assertThat(fields).noneMatch(name -> name.toLowerCase().contains("token") || name.toLowerCase().contains("url"));

        SharedFileLinkEntity stored = SharedFileLinkEntity.builder().fileId(FILE_ID).tokenHash(vault.hash("x"))
                .expiresAt(LocalDateTime.now().plusDays(1)).createdBy(USER_ID).build();
        given(linkRepository.findByFileIdOrderByCreatedAtDesc(FILE_ID)).willReturn(List.of(stored));
        List<LinkResponse> list = service.listLinks(FILE_ID);

        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode json = om.valueToTree(list.get(0));
        json.fieldNames().forEachRemaining(name ->
                assertThat(name.toLowerCase()).doesNotContain("token").doesNotContain("url"));
    }

    @Test
    @DisplayName("AC-x3: 発行応答の JSON は token と一覧と同じ項目（fileId・expiresAt 等）を平らに持つ")
    void 発行応答のJSON形() {
        LinkIssuedResponse issued = issue(null, false);
        ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonNode json = om.valueToTree(issued);

        assertThat(json.get("token").asText()).isEqualTo(issued.token());
        assertThat(json.has("fileId")).isTrue();
        assertThat(json.has("expiresAt")).isTrue();
        assertThat(json.has("tokenHash")).as("ハッシュも応答に出さない").isFalse();
    }

    // ---------------- AC-x5: ログに平文を出さない ----------------

    @Test
    @DisplayName("AC-x5: 発行・閲覧の各ログに平文トークン・ハッシュを出さない")
    void ログに平文を出さない() {
        LinkIssuedResponse issued = issue(null, true);
        SharedFileLinkEntity stored = savedEntity();
        given(linkRepository.findByTokenHash(vault.hash(issued.token()))).willReturn(Optional.of(stored));
        given(fileService.getFileForSharedLink(FILE_ID)).willReturn(org.mockito.Mockito.mock(FileResponse.class));
        service.accessLinkPublic(issued.token(), null);
        given(linkRepository.findByTokenHash(vault.hash("missing"))).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.accessLinkPublic("missing", null)).isInstanceOf(BusinessException.class);

        for (ILoggingEvent event : logAppender.list) {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain(issued.token()).doesNotContain(stored.getTokenHash())
                    .doesNotContain("missing");
        }
    }
}
