package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentContactSpaceEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentContactSpaceRepository;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: 大会連絡スペースの SYSTEM_ADMIN 直書き素通し
 * （{@link TournamentContactSpaceAccessGate}・{@link TournamentContactAccessService}）を除去する受け入れテスト。
 *
 * <p>分類表の A 行（Gate の requireView:82・requireVisibilityManage:171・canPost:208・
 * isOrganizerAdminOrSystemAdmin:222・loadParticipation:313／AccessService の checkPost:107・
 * checkVisibilityManage:130・isOrganizerAdminOrSystemAdmin:149）に対応する。非公開スペースの中身
 * （掲示板スレッド・チャット・大会フォルダのファイル）は、主催組織にも参加チームにも属さない運営アカウントに
 * <b>不在と同一の 404 TOUR_029</b>（呼び出し側が渡す不在コード）になる。公開設定の書換は運営からは行わない
 * （マスター決定 2026-11-26）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: 大会連絡スペースの SYSTEM_ADMIN 直書き素通しの除去")
class TournamentContactZc1DirectBypassRemovalTest {

    @Mock private TournamentContactSpaceRepository contactSpaceRepository;
    @Mock private TournamentParticipantRepository participantRepository;
    @Mock private TournamentRepository tournamentRepository;
    @Mock private TournamentDivisionRepository divisionRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private TournamentContactSpaceAccessGate mockedGate;

    private TournamentContactSpaceAccessGate gate;
    private TournamentContactAccessService accessService;

    private static final Long TOURNAMENT_ID = 100L;
    private static final Long ORG_ID = 5L;
    private static final Long PLATFORM = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long ORGANIZER_ADMIN = 902L;
    private static final ErrorCode NOT_FOUND = TournamentErrorCode.CONTACT_SPACE_NOT_FOUND;
    private static final ContactSpaceScopeType T = ContactSpaceScopeType.TOURNAMENT;
    private static final ContactSpaceKind BULLETIN = ContactSpaceKind.BULLETIN;

    private TournamentContactSpaceEntity privateBulletin;

    @BeforeEach
    void setUp() {
        gate = new TournamentContactSpaceAccessGate(contactSpaceRepository, participantRepository,
                tournamentRepository, divisionRepository, accessControlService);
        accessService = new TournamentContactAccessService(tournamentRepository, divisionRepository,
                participantRepository, accessControlService, mockedGate);

        privateBulletin = TournamentContactSpaceEntity.builder()
                .scopeType(T).scopeId(TOURNAMENT_ID).spaceKind(BULLETIN).refId(999L).isPublic(false).build();
        privateBulletin.setId(UUID.randomUUID());

        given(contactSpaceRepository.findByScopeTypeAndScopeIdAndSpaceKind(T, TOURNAMENT_ID, BULLETIN))
                .willReturn(Optional.of(privateBulletin));
        given(contactSpaceRepository.findByScopeTypeAndScopeId(T, TOURNAMENT_ID)).willReturn(List.of(privateBulletin));
        given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(
                TournamentEntity.builder().organizationId(ORG_ID).name("大会").createdBy(1L).build()));

        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(OUTSIDER)).willReturn(false);
        given(accessControlService.isSystemAdmin(ORGANIZER_ADMIN)).willReturn(false);
        given(accessControlService.isAdmin(any(), anyLong(), anyString()))
                .willAnswer(inv -> ORGANIZER_ADMIN.equals(inv.getArgument(0)) && ORG_ID.equals(inv.getArgument(1)));
        // 運営アカウント・部外者は参加チームに属さない（participantRepository の count 系は 0）。
    }

    private static ErrorCode codeOf(ThrowingCallable call) {
        try {
            call.call();
        } catch (BusinessException e) {
            return e.getErrorCode();
        } catch (Throwable t) {
            throw new AssertionError("BusinessException 以外が送出された", t);
        }
        return null;
    }

    // ---------------------------------------------------------------- Gate

    @Test
    @DisplayName("A(Gate.requireView): 非公開スペースの閲覧は運営アカウントに不在と同一の 404 TOUR_029（部外者と同じ）")
    void requireView_platformIsConcealed() {
        assertThat(codeOf(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, OUTSIDER, NOT_FOUND))).isEqualTo(NOT_FOUND);
        assertThat(codeOf(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, PLATFORM, NOT_FOUND)))
                .as("運営アカウントが非公開の大会連絡（掲示板・チャット・大会フォルダ）を読めてはならない")
                .isEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("A(Gate.requirePost / canPost): 非公開スペースへの投稿は運営アカウントに 404 TOUR_029（部外者と同じ）")
    void requirePost_platformIsConcealed() {
        assertThat(codeOf(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, OUTSIDER, NOT_FOUND))).isEqualTo(NOT_FOUND);
        assertThat(codeOf(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, PLATFORM, NOT_FOUND)))
                .isEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("A(Gate.requireViewResolvingLevel): 既読者一覧などの閲覧水準も運営アカウントに 404 TOUR_029（POST を返さない）")
    void requireViewResolvingLevel_platformIsConcealed() {
        assertThat(codeOf(() -> gate.requireViewResolvingLevel(T, TOURNAMENT_ID, BULLETIN, PLATFORM, NOT_FOUND)))
                .isEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("A(Gate.requireVisibilityManage): 公開設定の管理は運営アカウントに不在と同一の 404 TOUR_029（部外者と同じ）")
    void requireVisibilityManage_platformIsConcealed() {
        assertThat(codeOf(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, OUTSIDER))).isEqualTo(NOT_FOUND);
        assertThat(codeOf(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, PLATFORM)))
                .as("運営アカウントがテナントの公開設定を書き換えてはならない（マスター決定 2026-11-26）")
                .isEqualTo(NOT_FOUND);
    }

    @Test
    @DisplayName("A(Gate.resolveLevelsForSpaces / loadParticipation): 一括解決で運営アカウントに非公開スペースの水準を返さない（部外者と同じ空マップ）")
    void resolveLevelsForSpaces_platformGetsNothing() {
        Map<UUID, ContactAccessLevel> outsider = gate.resolveLevelsForSpaces(OUTSIDER, TOURNAMENT_ID, List.of(privateBulletin));
        assertThat(outsider).isEmpty();

        assertThat(gate.resolveLevelsForSpaces(PLATFORM, TOURNAMENT_ID, List.of(privateBulletin)))
                .as("運営アカウントに POST 水準を返してはならない")
                .isEqualTo(outsider);
    }

    @Test
    @DisplayName("回帰: 主催組織 ADMIN は非公開スペースを閲覧・投稿・公開設定管理でき、一括解決は POST")
    void regression_organizerAdmin() {
        assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, ORGANIZER_ADMIN, NOT_FOUND))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, ORGANIZER_ADMIN, NOT_FOUND))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.requireVisibilityManage(TOURNAMENT_ID, null, ORGANIZER_ADMIN))
                .doesNotThrowAnyException();
        assertThat(gate.resolveLevelsForSpaces(ORGANIZER_ADMIN, TOURNAMENT_ID, List.of(privateBulletin)))
                .containsExactlyEntriesOf(Map.of(privateBulletin.getId(), ContactAccessLevel.POST));
    }

    @Test
    @DisplayName("回帰: 参加チームの有効メンバーは非公開スペースを閲覧でき、投稿は 403 TOUR_031")
    void regression_participantMember() {
        Long member = 903L;
        given(accessControlService.isSystemAdmin(member)).willReturn(false);
        given(participantRepository.countActiveMemberOfAnyParticipantTeam(TOURNAMENT_ID, member)).willReturn(1);

        assertThatCode(() -> gate.requireView(T, TOURNAMENT_ID, BULLETIN, member, NOT_FOUND)).doesNotThrowAnyException();
        assertThat(codeOf(() -> gate.requirePost(T, TOURNAMENT_ID, BULLETIN, member, NOT_FOUND)))
                .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
    }

    // ---------------------------------------------------------------- AccessService（tx 内の多層防御）

    @Test
    @DisplayName("A(AccessService.checkPost): tx 内の多層防御でも運営アカウントは投稿できない（403 TOUR_031、部外者と同じ）")
    void accessServiceCheckPost_platformIsDenied() {
        assertThat(codeOf(() -> accessService.checkPost(T, TOURNAMENT_ID, OUTSIDER)))
                .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        assertThat(codeOf(() -> accessService.checkPost(T, TOURNAMENT_ID, PLATFORM)))
                .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
    }

    @Test
    @DisplayName("A(AccessService.checkVisibilityManage): tx 内の多層防御でも運営アカウントは公開設定を変えられない（403 TOUR_032、部外者と同じ）")
    void accessServiceCheckVisibilityManage_platformIsDenied() {
        assertThat(codeOf(() -> accessService.checkVisibilityManage(T, TOURNAMENT_ID, OUTSIDER)))
                .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
        assertThat(codeOf(() -> accessService.checkVisibilityManage(T, TOURNAMENT_ID, PLATFORM)))
                .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VISIBILITY_FORBIDDEN);
    }

    @Test
    @DisplayName("回帰: AccessService は主催組織 ADMIN の投稿・公開設定を許可する")
    void regression_accessServiceOrganizerAdmin() {
        assertThatCode(() -> accessService.checkPost(T, TOURNAMENT_ID, ORGANIZER_ADMIN)).doesNotThrowAnyException();
        assertThatCode(() -> accessService.checkVisibilityManage(T, TOURNAMENT_ID, ORGANIZER_ADMIN))
                .doesNotThrowAnyException();
        // 運営アカウントの判定で組織 ID を引く前提（大会不在なら 404）も維持されていること
        given(tournamentRepository.findById(eq(404L))).willReturn(Optional.empty());
        assertThat(codeOf(() -> accessService.checkPost(T, 404L, ORGANIZER_ADMIN))).isEqualTo(NOT_FOUND);
    }
}
