package com.mannschaft.app.role;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import com.mannschaft.app.role.dto.InviteClaimAuditContext;
import com.mannschaft.app.role.dto.InviteClaimLock;
import com.mannschaft.app.role.dto.InviteClaimTicket;
import com.mannschaft.app.role.dto.InviteDeclineResult;
import com.mannschaft.app.role.dto.InviteLocator;
import com.mannschaft.app.role.dto.InviteTokenSnapshot;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.InviteAcceptanceWriter;
import com.mannschaft.app.role.service.InviteScopeStateService;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link InviteAcceptanceWriter} の単体テスト。エラーの優先順位（陣立て書 §6）と claim の状態遷移を固定する。
 * 並行性・実DBでの副作用は {@code InviteAcceptance*IT} が担う。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteAcceptanceWriter 単体テスト")
class InviteAcceptanceWriterTest {

    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private InviteScopeStateService scopeStates;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private InviteAcceptanceWriter writer;

    private static final InviteLocator LOCATOR = new InviteLocator.ByTokenHash("hash-1");
    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TOKEN_ID = 100L;
    private static final Long ROLE_ID = 3L;
    private static final UUID CLAIM_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final Instant NOW = java.time.LocalDateTime.of(2026, 11, 8, 12, 0).toInstant(java.time.ZoneOffset.UTC);

    private InviteTokenSnapshot token(Instant expiresAt, Integer maxUses, int usedCount,
                                      Instant revokedAt, Long targetUserId) {
        return new InviteTokenSnapshot(TOKEN_ID, TEAM_ID, null, ROLE_ID, expiresAt, maxUses, usedCount, revokedAt,
                0L, 2L, targetUserId, NOW);
    }

    private InviteTokenSnapshot validToken() {
        return token(NOW.plus(1, java.time.temporal.ChronoUnit.DAYS), 3, 0, null, null);
    }

    private void lock(InviteTokenSnapshot snapshot) {
        given(inviteTokenRepository.lockSnapshotByTokenHash("hash-1")).willReturn(Optional.of(snapshot));
    }

    private void memberRole() {
        given(roleRepository.findById(ROLE_ID)).willReturn(
                Optional.of(RoleEntity.builder().id(ROLE_ID).name("MEMBER").build()));
    }

    private static void assertCode(Throwable thrown, com.mannschaft.app.common.ErrorCode code, HttpStatus status) {
        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getErrorCode()).isEqualTo(code);
            assertThat(e.getHttpStatusOverride()).isEqualTo(status);
        });
    }

    @Nested
    @DisplayName("claim のエラー優先順位")
    class ClaimErrors {

        @Test
        @DisplayName("招待が無い → ROLE_002")
        void 招待が無い() {
            given(inviteTokenRepository.lockSnapshotByTokenHash("hash-1")).willReturn(Optional.empty());

            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
        }

        @Test
        @DisplayName("ID 入口: 宛先本人でない・共有リンク型は、上限到達でも不在と同じ ROLE_002（状態を明かさない）")
        void ID入口は非宛先を隠す() {
            InviteLocator byId = new InviteLocator.ById(TOKEN_ID);
            // 上限到達済みでも、非宛先には ROLE_003 を返さない
            given(inviteTokenRepository.lockSnapshotById(TOKEN_ID))
                    .willReturn(Optional.of(token(null, 1, 1, null, 999L)));
            assertThatThrownBy(() -> writer.claim(byId, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));

            // 共有リンク型（宛先なし）はトークン入口では参加できるが、ID 入口では不在と同じ
            given(inviteTokenRepository.lockSnapshotById(TOKEN_ID))
                    .willReturn(Optional.of(token(null, 3, 0, null, null)));
            assertThatThrownBy(() -> writer.claim(byId, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
            verify(inviteTokenRepository, never()).incrementUsedCountIfAvailable(any());
        }

        @Test
        @DisplayName("上限到達 → ROLE_003、期限切れ・失効 → ROLE_002")
        void 無効の種類() {
            lock(token(NOW.plus(1, java.time.temporal.ChronoUnit.DAYS), 3, 3, null, null));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_003, null));
        }

        @Test
        @DisplayName("期限切れ → ROLE_002")
        void 期限切れ() {
            lock(token(NOW.minusSeconds(1), 3, 0, null, null));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
        }

        @Test
        @DisplayName("失効 → ROLE_002")
        void 失効() {
            lock(token(null, 3, 0, NOW.minus(1, java.time.temporal.ChronoUnit.DAYS), null));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
        }

        @Test
        @DisplayName("期限切れ＋ブロック → ROLE_002（無効が優先）")
        void 期限切れとブロックは期限切れ優先() {
            lock(token(NOW.minusSeconds(1), 3, 0, null, null));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, true, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
        }

        @Test
        @DisplayName("上限到達＋不正フォルダ → ROLE_003（無効が優先）")
        void 上限とフォルダは上限優先() {
            lock(token(null, 1, 1, null, null));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_003, null));
        }

        @Test
        @DisplayName("宛先違い → 403 ROLE_009（ブロックより優先）")
        void 宛先違い() {
            lock(token(null, 1, 0, null, 999L));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, true, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("特権ロール → 422 ROLE_009")
        void 特権ロール() {
            lock(validToken());
            given(roleRepository.findById(ROLE_ID)).willReturn(
                    Optional.of(RoleEntity.builder().id(ROLE_ID).name("ADMIN").build()));
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("閉鎖世代が古い → ROLE_002（ブロックより優先）")
        void 閉鎖世代() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(false);
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, true, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
        }

        @Test
        @DisplayName("ブロック → TEAM_004（在籍・フォルダより優先）")
        void ブロック() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(true);
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, true, ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND))
                    .satisfies(t -> assertCode(t, TeamErrorCode.TEAM_004, null));
        }

        @Test
        @DisplayName("在籍済み → 409 TEAM_003（フォルダ不正より優先）。回数は進めない")
        void 在籍済み() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(true);
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, TEAM_ID)).willReturn(true);
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND))
                    .satisfies(t -> assertCode(t, TeamErrorCode.TEAM_003, HttpStatus.CONFLICT));
            verify(inviteTokenRepository, never()).incrementUsedCountIfAvailable(anyLong());
        }

        @Test
        @DisplayName("フォルダ不正 → そのエラーコード。回数は進めない")
        void フォルダ不正() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(true);
            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH))
                    .satisfies(t -> assertCode(t, ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH, null));
            verify(inviteTokenRepository, never()).incrementUsedCountIfAvailable(anyLong());
        }
    }

    @Nested
    @DisplayName("claim の成功")
    class ClaimSuccess {

        @Test
        @DisplayName("条件付き加算→Entity refresh→確保記録の順に行い、チケットを返す")
        void 確保する() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(true);
            given(inviteTokenRepository.incrementUsedCountIfAvailable(TOKEN_ID)).willReturn(1);
            given(inviteTokenRepository.insertClaim(TOKEN_ID, USER_ID)).willReturn(CLAIM_ID);

            InviteClaimTicket ticket = writer.claim(LOCATOR, USER_ID, false, null);

            assertThat(ticket).isEqualTo(new InviteClaimTicket(CLAIM_ID, TOKEN_ID, "TEAM", TEAM_ID, ROLE_ID, 2L));
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(inviteTokenRepository);
            order.verify(inviteTokenRepository).incrementUsedCountIfAvailable(TOKEN_ID);
            order.verify(inviteTokenRepository).refreshIfManaged(TOKEN_ID);
            order.verify(inviteTokenRepository).insertClaim(TOKEN_ID, USER_ID);
        }

        @Test
        @DisplayName("条件付き加算が0行（直前に上限到達・失効）なら ROLE_003 で claim を作らない")
        void 加算失敗() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(true);
            given(inviteTokenRepository.incrementUsedCountIfAvailable(TOKEN_ID)).willReturn(0);

            assertThatThrownBy(() -> writer.claim(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_003, null));
            verify(inviteTokenRepository, never()).insertClaim(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("releaseClaim")
    class Release {

        @Test
        @DisplayName("招待行 → claim の順にロックし、CLAIMED→RELEASED が1行のときだけ使用回数を1戻す")
        void 戻す() {
            given(inviteTokenRepository.findClaimTokenId(CLAIM_ID)).willReturn(Optional.of(TOKEN_ID));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.decrementUsedCount(TOKEN_ID)).willReturn(1);

            assertThat(writer.releaseClaim(CLAIM_ID)).isEqualTo(InviteAcceptanceWriter.ReleaseOutcome.RELEASED);

            org.mockito.InOrder order = org.mockito.Mockito.inOrder(inviteTokenRepository);
            order.verify(inviteTokenRepository).lockSnapshotById(TOKEN_ID);
            order.verify(inviteTokenRepository).markClaimReleased(CLAIM_ID);
            order.verify(inviteTokenRepository).decrementUsedCount(TOKEN_ID);
            order.verify(inviteTokenRepository).refreshIfManaged(TOKEN_ID);
        }

        @Test
        @DisplayName("既に RELEASED／GRANTED なら何もしない（冪等）")
        void 冪等() {
            given(inviteTokenRepository.findClaimTokenId(CLAIM_ID)).willReturn(Optional.of(TOKEN_ID));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(0);
            given(inviteTokenRepository.lockClaim(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "GRANTED", true)));

            assertThat(writer.releaseClaim(CLAIM_ID)).isEqualTo(InviteAcceptanceWriter.ReleaseOutcome.SETTLED);

            verify(inviteTokenRepository, never()).decrementUsedCount(anyLong());
        }

        @Test
        @DisplayName("P1: 付与印のある CLAIMED（この claim の付与がコミット済み）は戻さず GRANT_COMMITTED")
        void 付与印ありは戻さない() {
            given(inviteTokenRepository.findClaimTokenId(CLAIM_ID)).willReturn(Optional.of(TOKEN_ID));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(0);
            given(inviteTokenRepository.lockClaim(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", true)));

            assertThat(writer.releaseClaim(CLAIM_ID)).isEqualTo(InviteAcceptanceWriter.ReleaseOutcome.GRANT_COMMITTED);

            verify(inviteTokenRepository, never()).decrementUsedCount(anyLong());
        }

        @Test
        @DisplayName("Codex P2（旧データ）: 付与印の導入前からの CLAIMED（付与されたか不明）は戻さず GRANT_UNKNOWN")
        void 旧データは戻さない() {
            given(inviteTokenRepository.findClaimTokenId(CLAIM_ID)).willReturn(Optional.of(TOKEN_ID));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(0);
            given(inviteTokenRepository.lockClaim(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false, true)));

            assertThat(writer.releaseClaim(CLAIM_ID)).isEqualTo(InviteAcceptanceWriter.ReleaseOutcome.GRANT_UNKNOWN);

            verify(inviteTokenRepository, never()).decrementUsedCount(anyLong());
        }

        @Test
        @DisplayName("存在しない claim は IllegalStateException")
        void 不存在() {
            given(inviteTokenRepository.findClaimTokenId(CLAIM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> writer.releaseClaim(CLAIM_ID)).isInstanceOf(IllegalStateException.class);
            verify(inviteTokenRepository, never()).markClaimReleased(any());
        }

        @Test
        @DisplayName("使用回数を戻せない（既に0）なら IllegalStateException で全巻き戻し")
        void 使用回数が既に0() {
            given(inviteTokenRepository.findClaimTokenId(CLAIM_ID)).willReturn(Optional.of(TOKEN_ID));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.decrementUsedCount(TOKEN_ID)).willReturn(0);

            assertThatThrownBy(() -> writer.releaseClaim(CLAIM_ID)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("claimOrResume（保留承認の T6'。CMP-261107-1833・Codex 検分 P1）")
    class ClaimOrResume {

        private InviteTokenSnapshot expiredToken() {
            return token(NOW.minusSeconds(1), 1, 1, null, null);
        }

        @Test
        @DisplayName("P1: 残った claim に付与印があれば、招待が期限切れでも戻さず grantCommitted=true で返す")
        void 付与印ありは期限切れでも戻さない() {
            lock(expiredToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", true)));

            InviteClaimTicket ticket = writer.claimOrResume(LOCATOR, USER_ID, false, null);

            assertThat(ticket.claimId()).isEqualTo(CLAIM_ID);
            assertThat(ticket.resumed()).isTrue();
            assertThat(ticket.grantCommitted()).isTrue();
            verify(inviteTokenRepository, never()).markClaimReleased(any());
            verify(inviteTokenRepository, never()).decrementUsedCount(anyLong());
            verify(inviteTokenRepository, never()).incrementUsedCountIfAvailable(anyLong());
        }

        @Test
        @DisplayName("付与印の無い claim で招待が期限切れなら、同じTXで戻して ROLE_002")
        void 付与印なしで期限切れは戻す() {
            lock(expiredToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false)));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.decrementUsedCount(TOKEN_ID)).willReturn(1);

            assertThatThrownBy(() -> writer.claimOrResume(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
            verify(inviteTokenRepository).decrementUsedCount(TOKEN_ID);
        }

        @Test
        @DisplayName("Codex P2（旧データ）: 付与されたか不明な旧 claim は、招待が期限切れでも戻さず ROLE_002（CLAIMED のまま）")
        void 旧データは期限切れでも戻さない() {
            lock(expiredToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false, true)));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(0);
            given(inviteTokenRepository.lockClaim(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false, true)));

            assertThatThrownBy(() -> writer.claimOrResume(LOCATOR, USER_ID, false, null))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
            verify(inviteTokenRepository, never()).decrementUsedCount(anyLong());
        }

        @Test
        @DisplayName("付与印の無い claim で判定が通れば、使用回数を進めず resumed=true・grantCommitted=false で返す")
        void 付与印なしは再開() {
            lock(validToken());
            memberRole();
            given(scopeStates.isCurrent("TEAM", TEAM_ID, 0L)).willReturn(true);
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false)));

            InviteClaimTicket ticket = writer.claimOrResume(LOCATOR, USER_ID, false, null);

            assertThat(ticket.resumed()).isTrue();
            assertThat(ticket.grantCommitted()).isFalse();
            verify(inviteTokenRepository, never()).incrementUsedCountIfAvailable(anyLong());
        }
    }

    @Nested
    @DisplayName("settleOrphanClaim（在籍済みでの残 claim の片付け。CMP-261107-1833・Codex 検分 P2）")
    class SettleOrphanClaim {

        @Test
        @DisplayName("付与印のある残 claim は GRANTED に確定して監査を出す")
        void 付与印ありは確定() {
            lock(validToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", true)));
            given(inviteTokenRepository.markClaimGranted(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.findClaimAuditContext(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimAuditContext(USER_ID, TEAM_ID, null, 2L)));

            assertThat(writer.settleOrphanClaim(LOCATOR, USER_ID))
                    .isEqualTo(InviteAcceptanceWriter.OrphanSettlement.CONFIRMED);

            verify(eventPublisher).publishEvent(any(TeamMemberAuditEvent.class));
            verify(inviteTokenRepository, never()).markClaimReleased(any());
        }

        @Test
        @DisplayName("P2: 付与印の無い残 claim は別経路の参加なので戻して使用回数を1戻す（確定・監査はしない）")
        void 付与印なしは戻す() {
            lock(validToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false)));
            given(inviteTokenRepository.markClaimReleased(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.decrementUsedCount(TOKEN_ID)).willReturn(1);

            assertThat(writer.settleOrphanClaim(LOCATOR, USER_ID))
                    .isEqualTo(InviteAcceptanceWriter.OrphanSettlement.RELEASED);

            verify(inviteTokenRepository).decrementUsedCount(TOKEN_ID);
            verify(inviteTokenRepository, never()).markClaimGranted(any());
            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("Codex P2（旧データ）: 付与されたか不明な旧 claim は、在籍済みなので戻さず GRANTED に確定する")
        void 旧データは確定() {
            lock(validToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID))
                    .willReturn(Optional.of(new InviteClaimLock(CLAIM_ID, "CLAIMED", false, true)));
            given(inviteTokenRepository.markClaimGranted(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.findClaimAuditContext(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimAuditContext(USER_ID, TEAM_ID, null, 2L)));

            assertThat(writer.settleOrphanClaim(LOCATOR, USER_ID))
                    .isEqualTo(InviteAcceptanceWriter.OrphanSettlement.CONFIRMED);

            verify(inviteTokenRepository, never()).markClaimReleased(any());
            verify(inviteTokenRepository, never()).decrementUsedCount(anyLong());
        }

        @Test
        @DisplayName("残 claim が無ければ何もしない")
        void 残claimなし() {
            lock(validToken());
            given(inviteTokenRepository.findClaimedForUpdate(TOKEN_ID, USER_ID)).willReturn(Optional.empty());

            assertThat(writer.settleOrphanClaim(LOCATOR, USER_ID))
                    .isEqualTo(InviteAcceptanceWriter.OrphanSettlement.NONE);
            verify(inviteTokenRepository, never()).markClaimReleased(any());
            verify(inviteTokenRepository, never()).markClaimGranted(any());
        }
    }

    @Nested
    @DisplayName("recordJoined")
    class RecordJoined {

        @Test
        @DisplayName("GRANTED 化が1行のとき TEAM は TeamMemberAuditEvent(INVITED) を発行する")
        void チームの監査() {
            given(inviteTokenRepository.markClaimGranted(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.findClaimAuditContext(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimAuditContext(USER_ID, TEAM_ID, null, 2L)));

            writer.recordJoined(CLAIM_ID);

            verify(eventPublisher).publishEvent(any(TeamMemberAuditEvent.class));
        }

        @Test
        @DisplayName("ORG は OrganizationMemberAuditEvent(JOINED) を発行する")
        void 組織の監査() {
            given(inviteTokenRepository.markClaimGranted(CLAIM_ID)).willReturn(1);
            given(inviteTokenRepository.findClaimAuditContext(CLAIM_ID))
                    .willReturn(Optional.of(new InviteClaimAuditContext(USER_ID, null, 20L, 2L)));

            writer.recordJoined(CLAIM_ID);

            verify(eventPublisher).publishEvent(any(OrganizationMemberAuditEvent.class));
        }

        @Test
        @DisplayName("既に GRANTED なら監査を出さず何もしない（重複・並行でも監査1回）")
        void 既にGRANTED() {
            given(inviteTokenRepository.markClaimGranted(CLAIM_ID)).willReturn(0);
            given(inviteTokenRepository.findClaimStatusLocked(CLAIM_ID)).willReturn(Optional.of("GRANTED"));

            writer.recordJoined(CLAIM_ID);

            verify(eventPublisher, never()).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("RELEASED／不存在は IllegalStateException")
        void 不正な遷移() {
            given(inviteTokenRepository.markClaimGranted(CLAIM_ID)).willReturn(0);
            given(inviteTokenRepository.findClaimStatusLocked(CLAIM_ID)).willReturn(Optional.of("RELEASED"));
            assertThatThrownBy(() -> writer.recordJoined(CLAIM_ID)).isInstanceOf(IllegalStateException.class);

            given(inviteTokenRepository.findClaimStatusLocked(CLAIM_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> writer.recordJoined(CLAIM_ID)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("decline")
    class Decline {

        private InviteTokenEntity entity(Long targetUserId, LocalDateTime revokedAt) {
            return InviteTokenEntity.builder().id(TOKEN_ID).tokenHash("hash-1").teamId(TEAM_ID).roleId(ROLE_ID)
                    .usedCount(0).targetUserId(targetUserId).revokedAt(revokedAt).build();
        }

        @Test
        @DisplayName("宛先本人なら失効させ newlyDeclined=true")
        void 辞退する() {
            InviteTokenEntity token = entity(USER_ID, null);
            given(inviteTokenRepository.lockFreshByTokenHash("hash-1")).willReturn(Optional.of(token));

            InviteDeclineResult result = writer.decline(LOCATOR, USER_ID);

            assertThat(result.newlyDeclined()).isTrue();
            assertThat(token.getRevokedAt()).isNotNull();
        }

        @Test
        @DisplayName("既に失効済みなら冪等（失効日時を上書きしない）")
        void 冪等() {
            LocalDateTime first = LocalDateTime.of(2026, 1, 1, 0, 0);
            InviteTokenEntity token = entity(USER_ID, first);
            given(inviteTokenRepository.lockFreshByTokenHash("hash-1")).willReturn(Optional.of(token));

            InviteDeclineResult result = writer.decline(LOCATOR, USER_ID);

            assertThat(result.newlyDeclined()).isFalse();
            assertThat(token.getRevokedAt()).isEqualTo(first);
        }

        @Test
        @DisplayName("宛先違い・共有リンク型は 403 ROLE_009")
        void 宛先違い() {
            given(inviteTokenRepository.lockFreshByTokenHash("hash-1")).willReturn(Optional.of(entity(999L, null)));
            assertThatThrownBy(() -> writer.decline(LOCATOR, USER_ID))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN));

            given(inviteTokenRepository.lockFreshByTokenHash("hash-1")).willReturn(Optional.of(entity(null, null)));
            assertThatThrownBy(() -> writer.decline(LOCATOR, USER_ID))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_009, HttpStatus.FORBIDDEN));
        }

        @Test
        @DisplayName("招待が無ければ ROLE_002")
        void 招待が無い() {
            given(inviteTokenRepository.lockFreshByTokenHash(anyString())).willReturn(Optional.empty());
            assertThatThrownBy(() -> writer.decline(LOCATOR, USER_ID))
                    .satisfies(t -> assertCode(t, RoleErrorCode.ROLE_002, null));
        }
    }
}
