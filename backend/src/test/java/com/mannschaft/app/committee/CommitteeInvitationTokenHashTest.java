package com.mannschaft.app.committee;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.committee.dto.CommitteeInvitationIssuedResponse;
import com.mannschaft.app.committee.dto.CommitteeInvitationResponse;
import com.mannschaft.app.committee.dto.CommitteeInviteRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationResolution;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.committee.service.CommitteeInvitationService;
import com.mannschaft.app.committee.service.CommitteeInvitationService.SendInvitationsResult;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.token.SecretTokenVault;
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

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-260826-1457 W2 committee: 委員会招集トークンのハッシュ保存化の受け入れ試験（AC-x1〜x5・AC-A の業務層）。
 *
 * <p>DB に平文を残さない（保存値は {@code vault.hash(平文)}）、発行応答の平文で受諾・辞退の照合が成功する、
 * 一覧応答に平文が出ない、宛先本人でない・不在・解決済みの各分岐の応答コードが変わらない、ログに平文を出さない、
 * 失効済み（解決済み）の招集が再招集を止めない、を検証する。列の形と旧招集の全失効は
 * {@code FlywayFromScratchMigrationTest} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("委員会招集トークンのハッシュ保存化（CMP-260826-1457 W2）")
class CommitteeInvitationTokenHashTest {

    private static final Long COMMITTEE_ID = 100L;
    private static final Long ORG_ID = 7L;
    private static final Long CHAIR_ID = 10L;
    private static final Long INVITEE_ID = 20L;
    private static final Long OTHER_ID = 30L;

    @Mock
    private CommitteeRepository committeeRepository;
    @Mock
    private CommitteeMemberRepository committeeMemberRepository;
    @Mock
    private CommitteeInvitationRepository committeeInvitationRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private UserRowLockService userRowLockService;
    @Mock
    private CommitteeAccessGuard committeeAccessGuard;
    @Spy
    private SecretTokenVault vault = new SecretTokenVault();

    @org.mockito.Mock
    private com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;

    @InjectMocks
    private CommitteeInvitationService service;

    private ListAppender<ILoggingEvent> logAppender;
    private Logger serviceLogger;

    @BeforeEach
    void attachLogAppender() {
        serviceLogger = (Logger) LoggerFactory.getLogger(CommitteeInvitationService.class);
        serviceLogger.setLevel(Level.DEBUG);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        serviceLogger.detachAppender(logAppender);
    }

    private SendInvitationsResult send(Long... inviteeIds) {
        given(committeeRepository.findById(COMMITTEE_ID)).willReturn(Optional.of(committee()));
        given(committeeInvitationRepository.save(any(CommitteeInvitationEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        CommitteeInviteRequest request = new CommitteeInviteRequest();
        request.setInviteeUserIds(List.of(inviteeIds));
        request.setProposedRole(CommitteeRole.MEMBER);
        return service.sendInvitations(COMMITTEE_ID, request, CHAIR_ID);
    }

    private static CommitteeEntity committee() {
        return CommitteeEntity.builder().id(COMMITTEE_ID).organizationId(ORG_ID).name("委員会")
                .status(CommitteeStatus.ACTIVE).build();
    }

    private CommitteeInvitationEntity pendingInvitationFor(String plaintext) {
        return CommitteeInvitationEntity.builder().id(1L).committeeId(COMMITTEE_ID).inviteeUserId(INVITEE_ID)
                .proposedRole(CommitteeRole.MEMBER).inviteTokenHash(vault.hash(plaintext)).invitedBy(CHAIR_ID)
                .expiresAt(LocalDateTime.now().plusDays(3)).build();
    }

    // ---------------- AC-x1 / AC-x2: 保存値はハッシュ、発行応答の平文で照合が成功する ----------------

    @Test
    @DisplayName("AC-x2: 発行応答に平文（Base64URL 43字）が載り、保存値は vault.hash(平文) で平文とは異なる")
    void 発行応答の平文とハッシュ保存() {
        SendInvitationsResult result = send(INVITEE_ID);

        assertThat(result.sentInvitations()).hasSize(1);
        String plaintext = result.sentInvitations().get(0).inviteToken();
        assertThat(plaintext).matches("^[A-Za-z0-9_-]{43}$");
        ArgumentCaptor<CommitteeInvitationEntity> captor = ArgumentCaptor.forClass(CommitteeInvitationEntity.class);
        verify(committeeInvitationRepository).save(captor.capture());
        assertThat(captor.getValue().getInviteTokenHash()).isEqualTo(vault.hash(plaintext)).isNotEqualTo(plaintext);
        assertThat(captor.getValue().getInviteTokenHash()).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("AC-x2: 複数人へ招集すると、人ごとに異なる平文が発行される")
    void 人ごとに異なるトークンが発行される() {
        SendInvitationsResult result = send(INVITEE_ID, OTHER_ID);

        assertThat(result.sentInvitations()).hasSize(2);
        assertThat(result.sentInvitations().get(0).inviteToken())
                .isNotEqualTo(result.sentInvitations().get(1).inviteToken());
    }

    @Test
    @DisplayName("AC-x1: Entity に平文のトークンを持つフィールドが無い（inviteTokenHash だけ）")
    void entityに平文フィールドが無い() {
        List<String> names = Arrays.stream(CommitteeInvitationEntity.class.getDeclaredFields())
                .map(Field::getName).toList();
        assertThat(names).contains("inviteTokenHash").doesNotContain("inviteToken");
    }

    @Test
    @DisplayName("AC-x2: 発行応答の平文で受諾が成功し、ハッシュで検索され、宛先本人の確認が通る")
    void 発行応答の平文で受諾が成功する() {
        String plaintext = send(INVITEE_ID).sentInvitations().get(0).inviteToken();
        CommitteeInvitationEntity invitation = pendingInvitationFor(plaintext);
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(plaintext)))
                .willReturn(Optional.of(invitation));
        given(committeeRepository.findByIdForUpdate(COMMITTEE_ID)).willReturn(Optional.of(committee()));
        given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                .willReturn(List.of());
        given(committeeInvitationRepository.findByIdForUpdate(1L)).willReturn(Optional.of(invitation));
        given(committeeMemberRepository.save(any(CommitteeMemberEntity.class))).willAnswer(inv -> inv.getArgument(0));

        CommitteeMemberEntity member = service.acceptInvitation(plaintext, INVITEE_ID);

        assertThat(member.getUserId()).isEqualTo(INVITEE_ID);
        assertThat(invitation.getResolution()).isEqualTo(CommitteeInvitationResolution.ACCEPTED);
        verify(committeeAccessGuard).requireInvitee(invitation, INVITEE_ID);
    }

    @Test
    @DisplayName("AC-x2: 発行応答の平文で辞退が成功し、ハッシュで検索され、宛先本人の確認が通る")
    void 発行応答の平文で辞退が成功する() {
        String plaintext = send(INVITEE_ID).sentInvitations().get(0).inviteToken();
        CommitteeInvitationEntity invitation = pendingInvitationFor(plaintext);
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(plaintext)))
                .willReturn(Optional.of(invitation));

        service.declineInvitation(plaintext, INVITEE_ID);

        assertThat(invitation.getResolution()).isEqualTo(CommitteeInvitationResolution.DECLINED);
        verify(committeeAccessGuard).requireInvitee(invitation, INVITEE_ID);
    }

    // ---------------- AC-x4: 拒否（応答コードは今のまま） ----------------

    @Test
    @DisplayName("AC-x4: 不在のトークンは平文のまま検索されず、受諾・辞退とも INVITATION_TOKEN_INVALID のまま")
    void 不在トークンは無効() {
        String unknown = "unknown-token-value";
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(unknown))).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.declineInvitation(unknown, INVITEE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommitteeErrorCode.INVITATION_TOKEN_INVALID));
        assertThatThrownBy(() -> service.acceptInvitation(unknown, INVITEE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommitteeErrorCode.INVITATION_TOKEN_INVALID));
        verify(committeeInvitationRepository, never()).findByInviteTokenHash(eq(unknown));
    }

    @Test
    @DisplayName("AC-x4: 宛先本人でない利用者は受諾・辞退とも拒否され、招集は解決されない")
    void 宛先本人でなければ拒否される() {
        String plaintext = send(INVITEE_ID).sentInvitations().get(0).inviteToken();
        CommitteeInvitationEntity invitation = pendingInvitationFor(plaintext);
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(plaintext)))
                .willReturn(Optional.of(invitation));
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(committeeAccessGuard).requireInvitee(invitation, OTHER_ID);
        given(committeeRepository.findByIdForUpdate(COMMITTEE_ID)).willReturn(Optional.of(committee()));
        given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                .willReturn(List.of());
        given(committeeInvitationRepository.findByIdForUpdate(1L)).willReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.declineInvitation(plaintext, OTHER_ID)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.acceptInvitation(plaintext, OTHER_ID)).isInstanceOf(BusinessException.class);
        assertThat(invitation.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("AC-x4: 取り下げ・失効済みの招集は、正しい平文でも INVITATION_EXPIRED で拒否される")
    void 失効済みは拒否される() {
        String plaintext = "revoked-token-plaintext";
        CommitteeInvitationEntity invitation = pendingInvitationFor(plaintext);
        invitation.markCancelled();
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(plaintext)))
                .willReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.declineInvitation(plaintext, INVITEE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommitteeErrorCode.INVITATION_EXPIRED));
    }

    @Test
    @DisplayName("AC-x4: 期限切れの未解決招集は INVITATION_EXPIRED で拒否される")
    void 期限切れは拒否される() {
        String plaintext = "expired-token-plaintext";
        CommitteeInvitationEntity invitation = CommitteeInvitationEntity.builder().id(2L).committeeId(COMMITTEE_ID)
                .inviteeUserId(INVITEE_ID).inviteTokenHash(vault.hash(plaintext))
                .expiresAt(LocalDateTime.now().minusMinutes(1)).build();
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(plaintext)))
                .willReturn(Optional.of(invitation));

        assertThatThrownBy(() -> service.declineInvitation(plaintext, INVITEE_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommitteeErrorCode.INVITATION_EXPIRED));
    }

    // ---------------- 失効済みの招集は再招集を止めない ----------------

    @Test
    @DisplayName("失効（解決）済みの招集が残っていても、未解決の重複検査に当たらず再招集できる")
    void 失効済みの招集は再招集を止めない() {
        // 移行で失効した行・取り下げ済みの行は resolved_at を持つため、未解決の存在検査は false を返す
        given(committeeInvitationRepository.existsByCommitteeIdAndInviteeUserIdAndResolvedAtIsNull(
                COMMITTEE_ID, INVITEE_ID)).willReturn(false);

        SendInvitationsResult result = send(INVITEE_ID);

        assertThat(result.sentInvitations()).hasSize(1);
        assertThat(result.skippedExistingInvitationCount()).isZero();
    }

    @Test
    @DisplayName("未解決の招集が有効なうちは、従来どおり重複招集をスキップする")
    void 未解決の招集は重複スキップ() {
        given(committeeRepository.findById(COMMITTEE_ID)).willReturn(Optional.of(committee()));
        given(committeeInvitationRepository.existsByCommitteeIdAndInviteeUserIdAndResolvedAtIsNull(
                COMMITTEE_ID, INVITEE_ID)).willReturn(true);
        CommitteeInviteRequest request = new CommitteeInviteRequest();
        request.setInviteeUserIds(List.of(INVITEE_ID));

        SendInvitationsResult result = service.sendInvitations(COMMITTEE_ID, request, CHAIR_ID);

        assertThat(result.sentInvitations()).isEmpty();
        assertThat(result.skippedExistingInvitationCount()).isEqualTo(1);
        verify(committeeInvitationRepository, never()).save(any());
        verify(committeeInvitationRepository, never()).findByInviteTokenHash(anyString());
    }

    // ---------------- AC-x3: 一覧・詳細に平文が出ない ----------------

    @Test
    @DisplayName("AC-x3: 一覧・詳細の応答 DTO に平文のトークン項目が無く、JSON にも出ない")
    void 一覧応答に平文が出ない() throws Exception {
        List<String> fieldNames = Arrays.stream(CommitteeInvitationResponse.class.getDeclaredFields())
                .map(Field::getName).toList();
        assertThat(fieldNames).doesNotContain("inviteToken", "inviteTokenHash");

        String plaintext = "plaintext-should-never-appear-in-list";
        CommitteeInvitationEntity entity = pendingInvitationFor(plaintext);
        String json = mapper().writeValueAsString(CommitteeInvitationResponse.of(entity));
        assertThat(json).doesNotContain(plaintext).doesNotContain(vault.hash(plaintext))
                .doesNotContain("inviteToken");
    }

    @Test
    @DisplayName("AC-x2/x3: 発行応答だけが inviteToken を平らに載せ、ハッシュは載せない")
    void 発行応答だけが平文を載せる() throws Exception {
        String plaintext = send(INVITEE_ID).sentInvitations().get(0).inviteToken();
        CommitteeInvitationEntity entity = pendingInvitationFor(plaintext);

        JsonNode json = mapper().readTree(mapper().writeValueAsString(
                new CommitteeInvitationIssuedResponse(plaintext, CommitteeInvitationResponse.of(entity))));

        assertThat(json.get("inviteToken").asText()).isEqualTo(plaintext);
        assertThat(json.get("id").asLong()).isEqualTo(1L);
        assertThat(json.get("committeeId").asLong()).isEqualTo(COMMITTEE_ID);
        assertThat(json.has("invitation")).as("@JsonUnwrapped で平らに出る").isFalse();
        assertThat(json.toString()).doesNotContain(vault.hash(plaintext));
    }

    // ---------------- AC-x5: ログに平文を出さない ----------------

    @Test
    @DisplayName("AC-x5: 発行・辞退のどのログにも平文のトークンを出さない")
    void ログに平文を出さない() {
        String plaintext = send(INVITEE_ID).sentInvitations().get(0).inviteToken();
        CommitteeInvitationEntity invitation = pendingInvitationFor(plaintext);
        given(committeeInvitationRepository.findByInviteTokenHash(vault.hash(plaintext)))
                .willReturn(Optional.of(invitation));
        service.declineInvitation(plaintext, INVITEE_ID);

        assertThat(logAppender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(plaintext));
    }

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
