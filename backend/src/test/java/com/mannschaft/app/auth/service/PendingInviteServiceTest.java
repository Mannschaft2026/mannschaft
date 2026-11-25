package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.PendingInviteSnapshot;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.service.InviteAcceptanceFacade;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PendingInviteService} 単体テスト（CMP-261107-1833 設計 §2 承認手順①〜④・§3・§9-3・§9-4）。
 *
 * <p>AC6（失効・上限→EXPIRED、ブロック→UNAVAILABLE）、AC7（二重押し・既在籍・古い attempt）、AC8（辞退）、
 * AC9（再招待要請の可否と7日判定）の写像を固定する。実 DB の競合は RegistrationPendingInviteIT・PendingInviteStoreIT。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PendingInviteService 単体テスト（CMP-261107-1833）")
class PendingInviteServiceTest {

    private static final Long USER_ID = 700L;
    private static final Long TEAM_ID = 70L;
    private static final String HASH = "h".repeat(64);
    private static final UUID ATTEMPT = UUID.fromString("01938a00-0000-7000-8000-0000000000b1");

    @Mock private PendingInviteStore store;
    @Mock private InviteAcceptanceFacade facade;
    @Mock private AccessControlService accessControlService;
    @Mock private TeamService teamService;
    @Mock private OrganizationService organizationService;

    @InjectMocks private PendingInviteService service;

    private static PendingInviteSnapshot row(PendingInviteStatus status) {
        return new PendingInviteSnapshot(UUID.fromString("01938a00-0000-7000-8000-0000000000c1"), USER_ID, HASH, "TEAM",
                TEAM_ID, status, null, null, null);
    }

    private void givenAcquired() {
        given(store.tryStartAccepting(USER_ID)).willReturn(Optional.of(ATTEMPT));
        lenient().when(store.find(USER_ID)).thenReturn(Optional.of(row(PendingInviteStatus.ACCEPTING)));
        lenient().when(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).thenReturn(false);
        lenient().when(store.completeAttempt(eq(USER_ID), eq(ATTEMPT), any(), any())).thenReturn(true);
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo(code);
    }

    private void givenJoinability(boolean joinable) {
        TeamService.JoinabilitySummary summary = mock(TeamService.JoinabilitySummary.class);
        lenient().when(summary.joinable()).thenReturn(joinable);
        lenient().when(teamService.findJoinabilitySummary(TEAM_ID)).thenReturn(Optional.of(summary));
    }

    @Test
    @DisplayName("AC11: PendingInviteService は @Transactional を一切持たない（外側TXなしで Facade を呼ぶ）")
    void hasNoTransactional() {
        assertThat(PendingInviteService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(Arrays.stream(PendingInviteService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class)).map(Method::getName)).isEmpty();
    }

    // ------------------------------------------------------------------
    // 承認（accept）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("accept")
    class Accept {

        @Test
        @DisplayName("AC2: 取得→未在籍→ハッシュ入口で参加成功→ JOINED（失敗コードなし）")
        void success_joined() {
            givenAcquired();

            PendingInviteAcceptResponse response = service.accept(USER_ID);

            assertThat(response.status()).isEqualTo("JOINED");
            verify(facade).joinByInviteTokenHash(HASH, USER_ID);
            verify(store).completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.JOINED, null);
            verify(facade, never()).joinByInvite(anyString(), anyLong());
        }

        @Test
        @DisplayName("AC7: 既に在籍済みなら Facade の参加を呼ばず、残った claim を確定して JOINED")
        void alreadyMember_settlesOrphanAndJoined() {
            givenAcquired();
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            PendingInviteAcceptResponse response = service.accept(USER_ID);

            assertThat(response.status()).isEqualTo("JOINED");
            verify(facade).settleOrphanClaim(HASH, USER_ID);
            verify(facade, never()).joinByInviteTokenHash(anyString(), anyLong());
            verify(store).completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.JOINED, null);
        }

        @Test
        @DisplayName("AC7: 参加処理が TEAM_003（競合で在籍済み）なら JOINED")
        void team003_joined() {
            givenAcquired();
            willThrow(new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT))
                    .given(facade).joinByInviteTokenHash(HASH, USER_ID);

            assertThat(service.accept(USER_ID).status()).isEqualTo("JOINED");
            verify(store).completeAttempt(eq(USER_ID), eq(ATTEMPT), eq(PendingInviteStatus.JOINED), any());
        }

        @Test
        @DisplayName("AC2: 参加確定後のフォルダ配置失敗（部分成功）は JOINED")
        void folderFailure_joined() {
            givenAcquired();
            willThrow(new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND))
                    .given(facade).joinByInviteTokenHash(HASH, USER_ID);

            assertThat(service.accept(USER_ID).status()).isEqualTo("JOINED");
            verify(store).completeAttempt(eq(USER_ID), eq(ATTEMPT), eq(PendingInviteStatus.JOINED), any());
        }

        @Test
        @DisplayName("AC6: 失効・期限切れ（ROLE_002）は EXPIRED、失敗コードを残す")
        void role002_expired() {
            givenAcquired();
            willThrow(new BusinessException(RoleErrorCode.ROLE_002)).given(facade).joinByInviteTokenHash(HASH, USER_ID);

            assertThat(service.accept(USER_ID).status()).isEqualTo("EXPIRED");
            verify(store).completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.EXPIRED, "ROLE_002");
        }

        @Test
        @DisplayName("AC6: 上限到達（ROLE_003）は EXPIRED")
        void role003_expired() {
            givenAcquired();
            willThrow(new BusinessException(RoleErrorCode.ROLE_003)).given(facade).joinByInviteTokenHash(HASH, USER_ID);

            assertThat(service.accept(USER_ID).status()).isEqualTo("EXPIRED");
            verify(store).completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.EXPIRED, "ROLE_003");
        }

        @Test
        @DisplayName("AC6: ブロック（TEAM_004）は UNAVAILABLE")
        void team004_unavailable() {
            givenAcquired();
            willThrow(new BusinessException(TeamErrorCode.TEAM_004)).given(facade).joinByInviteTokenHash(HASH, USER_ID);

            assertThat(service.accept(USER_ID).status()).isEqualTo("UNAVAILABLE");
            verify(store).completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.UNAVAILABLE, "TEAM_004");
        }

        @Test
        @DisplayName("AC6: 特権ロール・宛先違い（ROLE_009）は UNAVAILABLE")
        void role009_unavailable() {
            givenAcquired();
            willThrow(new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY))
                    .given(facade).joinByInviteTokenHash(HASH, USER_ID);

            assertThat(service.accept(USER_ID).status()).isEqualTo("UNAVAILABLE");
            verify(store).completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.UNAVAILABLE, "ROLE_009");
        }

        @Test
        @DisplayName("AC7/§9-3: 原因不明の失敗は自分の attempt で PENDING に戻し、例外をそのまま返す（EXPIRED にしない）")
        void unknownFailure_revertsAndRethrows() {
            givenAcquired();
            DataAccessResourceFailureException boom = new DataAccessResourceFailureException("試練");
            willThrow(boom).given(facade).joinByInviteTokenHash(HASH, USER_ID);

            Throwable thrown = catchThrowable(() -> service.accept(USER_ID));

            assertThat(thrown).isSameAs(boom);
            verify(store).revertAttempt(USER_ID, ATTEMPT);
            verify(store, never()).completeAttempt(any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC7/§9-3: 想定外の業務例外も PENDING に戻して返す（握りつぶさない）")
        void unexpectedBusiness_revertsAndRethrows() {
            givenAcquired();
            BusinessException other = new BusinessException(TeamErrorCode.TEAM_001);
            willThrow(other).given(facade).joinByInviteTokenHash(HASH, USER_ID);

            Throwable thrown = catchThrowable(() -> service.accept(USER_ID));

            assertThat(thrown).isSameAs(other);
            verify(store).revertAttempt(USER_ID, ATTEMPT);
            verify(store, never()).completeAttempt(any(), any(), any(), any());
        }

        @Test
        @DisplayName("AC7/§9-4: 完了更新が0行（古い attempt）なら何も書かず、現状態（他の attempt が確定した JOINED）を返す")
        void staleAttempt_returnsCurrentState() {
            given(store.tryStartAccepting(USER_ID)).willReturn(Optional.of(ATTEMPT));
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.ACCEPTING)),
                    Optional.of(row(PendingInviteStatus.JOINED)));
            lenient().when(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).thenReturn(false);
            willThrow(new BusinessException(RoleErrorCode.ROLE_003)).given(facade).joinByInviteTokenHash(HASH, USER_ID);
            given(store.completeAttempt(USER_ID, ATTEMPT, PendingInviteStatus.EXPIRED, "ROLE_003")).willReturn(false);

            assertThat(service.accept(USER_ID).status()).isEqualTo("JOINED");
            verify(store, never()).revertAttempt(any(), any());
        }

        @Test
        @DisplayName("AC7: 取得できず現状態が JOINED（二重押しの負け側）なら 200 JOINED、Facade は呼ばない")
        void notAcquired_joined() {
            given(store.tryStartAccepting(USER_ID)).willReturn(Optional.empty());
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.JOINED)));

            assertThat(service.accept(USER_ID).status()).isEqualTo("JOINED");
            verify(facade, never()).joinByInviteTokenHash(anyString(), anyLong());
        }

        @Test
        @DisplayName("AC7: 取得できず処理中（ACCEPTING・5分以内）なら AUTH_086")
        void notAcquired_accepting_auth086() {
            given(store.tryStartAccepting(USER_ID)).willReturn(Optional.empty());
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.ACCEPTING)));

            assertCode(catchThrowable(() -> service.accept(USER_ID)), "AUTH_086");
            verify(facade, never()).joinByInviteTokenHash(anyString(), anyLong());
        }

        @ParameterizedTest
        @EnumSource(value = PendingInviteStatus.class,
                names = {"DECLINED", "EXPIRED", "UNAVAILABLE", "REISSUE_REQUESTED", "DISMISSED", "DISCARDED"})
        @DisplayName("AC8: 取得できず終了・辞退済みの状態なら AUTH_085")
        void notAcquired_terminal_auth085(PendingInviteStatus status) {
            given(store.tryStartAccepting(USER_ID)).willReturn(Optional.empty());
            given(store.find(USER_ID)).willReturn(Optional.of(row(status)));

            assertCode(catchThrowable(() -> service.accept(USER_ID)), "AUTH_085");
            verify(facade, never()).joinByInviteTokenHash(anyString(), anyLong());
        }

        @Test
        @DisplayName("AC10: 保留が無ければ AUTH_084")
        void none_auth084() {
            given(store.tryStartAccepting(USER_ID)).willReturn(Optional.empty());
            given(store.find(USER_ID)).willReturn(Optional.empty());

            assertCode(catchThrowable(() -> service.accept(USER_ID)), "AUTH_084");
        }
    }

    // ------------------------------------------------------------------
    // 辞退・閉じる
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("decline / dismiss")
    class DeclineAndDismiss {

        @Test
        @DisplayName("AC8: 辞退は PENDING→DECLINED（参加処理は呼ばない）")
        void decline_pending() {
            given(store.transition(USER_ID, Set.of(PendingInviteStatus.PENDING), PendingInviteStatus.DECLINED))
                    .willReturn(true);

            service.decline(USER_ID);

            verify(store).transition(USER_ID, Set.of(PendingInviteStatus.PENDING), PendingInviteStatus.DECLINED);
            verify(facade, never()).joinByInviteTokenHash(anyString(), anyLong());
        }

        @Test
        @DisplayName("AC8: PENDING 以外の辞退は AUTH_085、保留なしは AUTH_084")
        void decline_invalid() {
            given(store.transition(eq(USER_ID), any(), eq(PendingInviteStatus.DECLINED))).willReturn(false);
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.JOINED)), Optional.empty());

            assertCode(catchThrowable(() -> service.decline(USER_ID)), "AUTH_085");
            assertCode(catchThrowable(() -> service.decline(USER_ID)), "AUTH_084");
        }

        @Test
        @DisplayName("AC6: 閉じるは EXPIRED/REISSUE_DECLINED→DISMISSED、それ以外（依頼済みの REISSUE_REQUESTED を含む）は AUTH_085")
        void dismiss() {
            // CMP-261107-1833 PR3: 見送られた依頼（REISSUE_DECLINED）の案内も閉じられる。依頼済み（REISSUE_REQUESTED）は
            // 閉じても取り下げにならない（実機 D-1・殿の裁定。依頼の取り下げは機能として用意しない）。
            Set<PendingInviteStatus> from = Set.of(PendingInviteStatus.EXPIRED, PendingInviteStatus.REISSUE_DECLINED);
            given(store.transition(USER_ID, from, PendingInviteStatus.DISMISSED)).willReturn(true, false);
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.PENDING)));

            service.dismiss(USER_ID);
            assertCode(catchThrowable(() -> service.dismiss(USER_ID)), "AUTH_085");
        }
    }

    // ------------------------------------------------------------------
    // 再招待要請
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("requestReissue")
    class RequestReissue {

        @Test
        @DisplayName("AC9: 失効かつ非公開なら要請を記録し alreadyRequested=false")
        void expiredPrivate_requested() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.EXPIRED)));
            givenJoinability(false);
            given(store.markReissueRequested(USER_ID)).willReturn(true);

            PendingInviteReissueResponse response = service.requestReissue(USER_ID);

            assertThat(response.alreadyRequested()).isFalse();
            verify(store).markReissueRequested(USER_ID);
        }

        @Test
        @DisplayName("AC9: 7日以内の再押下は通知せず 200 alreadyRequested=true")
        void withinSevenDays_alreadyRequested() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.REISSUE_REQUESTED)));
            givenJoinability(false);
            given(store.markReissueRequested(USER_ID)).willReturn(false);

            assertThat(service.requestReissue(USER_ID).alreadyRequested()).isTrue();
        }

        @Test
        @DisplayName("AC6: 公開・参加可能な招待先への要請は 409 AUTH_085（参加申し込みへ誘導）")
        void publicScope_conflict() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.EXPIRED)));
            givenJoinability(true);

            assertCode(catchThrowable(() -> service.requestReissue(USER_ID)), "AUTH_085");
            verify(store, never()).markReissueRequested(any());
        }

        @ParameterizedTest
        @EnumSource(value = PendingInviteStatus.class,
                names = {"PENDING", "ACCEPTING", "JOINED", "UNAVAILABLE", "DECLINED", "DISMISSED", "DISCARDED"})
        @DisplayName("AC9: EXPIRED/REISSUE_REQUESTED 以外からの要請は AUTH_085")
        void invalidState_conflict(PendingInviteStatus status) {
            given(store.find(USER_ID)).willReturn(Optional.of(row(status)));
            lenient().when(teamService.findJoinabilitySummary(anyLong())).thenReturn(Optional.empty());

            assertCode(catchThrowable(() -> service.requestReissue(USER_ID)), "AUTH_085");
            verify(store, never()).markReissueRequested(any());
        }
    }

    // ------------------------------------------------------------------
    // 参照（GET）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("findMine")
    class FindMine {

        @Test
        @DisplayName("AC10: 保留なしは null")
        void none_null() {
            given(store.find(USER_ID)).willReturn(Optional.empty());

            assertThat(service.findMine(USER_ID)).isNull();
        }

        @Test
        @DisplayName("AC6: 失効・非公開は joinable=false・publicPath なし")
        void expiredPrivate_noPublicPath() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.EXPIRED)));
            givenJoinability(false);

            PendingInviteResponse response = service.findMine(USER_ID);

            assertThat(response.status()).isEqualTo("EXPIRED");
            assertThat(response.joinable()).isFalse();
            assertThat(response.publicPath()).isNull();
        }

        @Test
        @DisplayName("A-1: PENDING で既に在籍していれば JOINED に確定して返す（招待の使用・確保には触れない）")
        void pendingButAlreadyMember_confirmsJoined() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.PENDING)),
                    Optional.of(row(PendingInviteStatus.JOINED)));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(store.transition(USER_ID, EnumSet.of(PendingInviteStatus.PENDING), PendingInviteStatus.JOINED))
                    .willReturn(true);
            givenJoinability(false);

            assertThat(service.findMine(USER_ID).status()).isEqualTo("JOINED");
            verify(facade, never()).settleOrphanClaim(anyString(), anyLong());
            verify(facade, never()).joinByInviteTokenHash(anyString(), anyLong());
        }

        @Test
        @DisplayName("A-1: PENDING で未在籍なら PENDING のまま（確定しない）")
        void pendingNotMember_staysPending() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.PENDING)));
            givenJoinability(true);

            assertThat(service.findMine(USER_ID).status()).isEqualTo("PENDING");
            verify(store, never()).transition(anyLong(), any(), any());
        }

        @ParameterizedTest
        @EnumSource(value = PendingInviteStatus.class, names = {"ACCEPTING", "EXPIRED", "REISSUE_REQUESTED", "UNAVAILABLE"})
        @DisplayName("A-1: PENDING 以外は在籍していても触れない")
        void nonPending_untouched(PendingInviteStatus status) {
            given(store.find(USER_ID)).willReturn(Optional.of(row(status)));
            lenient().when(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).thenReturn(true);
            givenJoinability(false);

            assertThat(service.findMine(USER_ID).status()).isEqualTo(status.name());
            verify(store, never()).transition(anyLong(), any(), any());
        }

        @Test
        @DisplayName("A-1: 確定が0行（承認が先に ACCEPTING にした）なら現状態を返す")
        void confirmLosesRace_returnsCurrent() {
            given(store.find(USER_ID)).willReturn(Optional.of(row(PendingInviteStatus.PENDING)));
            given(accessControlService.isMember(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(store.transition(USER_ID, EnumSet.of(PendingInviteStatus.PENDING), PendingInviteStatus.JOINED))
                    .willReturn(false);
            givenJoinability(false);

            assertThat(service.findMine(USER_ID).status()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("§9-4: ACCEPTING は retryAvailableAt = accepting_started_at + 5分 を返す")
        void accepting_retryAvailableAt() {
            Instant started = Instant.parse("2026-11-22T03:00:00Z");
            given(store.find(USER_ID)).willReturn(Optional.of(new PendingInviteSnapshot(
                    UUID.fromString("01938a00-0000-7000-8000-0000000000c1"), USER_ID, HASH, "TEAM", TEAM_ID,
                    PendingInviteStatus.ACCEPTING, null, started, null)));
            givenJoinability(false);

            assertThat(service.findMine(USER_ID).retryAvailableAt()).isEqualTo(started.plus(Duration.ofMinutes(5)));
        }
    }
}
