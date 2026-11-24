package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteClaimAuditContext;
import com.mannschaft.app.role.dto.InviteClaimLock;
import com.mannschaft.app.role.dto.InviteClaimTicket;
import com.mannschaft.app.role.dto.InviteDeclineResult;
import com.mannschaft.app.role.dto.InviteLocator;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.InviteTokenSnapshot;
import com.mannschaft.app.role.dto.InviteValidity;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 招待の承諾・辞退を構成する role ドメインの短いトランザクション群（CMP-260826-1457・陣立て書 §3/§4/§9）。
 *
 * <p>{@link InviteAcceptanceFacade}（トランザクションなし）が各ドメインの短TXを順に呼ぶ同期 Saga の、
 * role 側の部品。各メソッドは role の Repository のみを使う（他ドメインには触れない）。
 * 全て REQUIRED なので、外側トランザクション（登録経路など）があればそれに参加し物理1TXになる。</p>
 *
 * <ul>
 *   <li>{@link #inspectScope}: 所属先の射影を行ロックなしで読む（T2）</li>
 *   <li>{@link #claim}: 招待行を最新値でロックして判定し、使用回数を1進めて確保記録を残す（T6）</li>
 *   <li>{@link #releaseClaim}: 付与されなかった確保を戻す（T8。ちょうど1回。付与印のある確保は戻さない）</li>
 *   <li>{@link #recordJoined}: 確保を確定し招待監査を発行する（T9）</li>
 *   <li>{@link #decline}: 宛先本人が辞退する（失効）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteAcceptanceWriter {

    /** {@link #releaseClaim} の結果。 */
    public enum ReleaseOutcome {
        /** CLAIMED→RELEASED にして使用回数を1戻した。 */
        RELEASED,
        /** この claim による付与がコミット済み（付与印あり）なので戻さなかった。確定（T9）すべき確保である。 */
        GRANT_COMMITTED,
        /**
         * 付与印の導入前から CLAIMED だった旧データで、付与されたか不明なので戻さなかった（使用回数を戻さない安全側）。
         * 在籍済みなら確定（T9）してよい。
         */
        GRANT_UNKNOWN,
        /** 既に GRANTED／RELEASED で何もしなかった（冪等）。 */
        SETTLED
    }

    /** {@link #settleOrphanClaim} の結果。 */
    public enum OrphanSettlement {
        /** 残った CLAIMED が無い。 */
        NONE,
        /** 付与印のある確保、または付与されたか不明な旧データの確保を GRANTED に確定した。 */
        CONFIRMED,
        /** 付与印の無い（参加に使われていない）確保を戻した。 */
        RELEASED
    }

    /** F04.12 特権ロール（承諾型招待では付与不可・join 側の保険ガード用）。 */
    private static final Set<String> PRIVILEGED_INVITE_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");

    private final InviteTokenRepository inviteTokenRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final InviteScopeStateService scopeStates;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 招待の所属先を射影で返す（T2）。招待行をロックしない読み取りで、Entity は読まない。
     * 招待が無ければ空。
     */
    @Transactional(readOnly = true)
    public Optional<InviteScopeRef> inspectScope(InviteLocator locator) {
        return switch (locator) {
            case InviteLocator.ByTokenHash byHash -> inviteTokenRepository.findScopeByTokenHash(byHash.tokenHash());
            case InviteLocator.ById byId -> inviteTokenRepository.findScopeById(byId.id());
        };
    }

    /**
     * 承諾の確保（T6）。招待行を最新値でロックしたまま、現行どおりの優先順位で判定する。
     *
     * <ol>
     *   <li>招待が無い → ROLE_002（ID 入口では、宛先本人でない・共有リンク型もここで同じ ROLE_002）</li>
     *   <li>無効（上限到達 → ROLE_003、期限切れ・失効 → ROLE_002）</li>
     *   <li>宛先違い → 403 ROLE_009</li>
     *   <li>特権ロール → 422 ROLE_009</li>
     *   <li>閉鎖世代が古い → ROLE_002</li>
     *   <li>ブロック → TEAM_004</li>
     *   <li>在籍済み（user_roles ∪ memberships）→ 409 TEAM_003</li>
     *   <li>配置先フォルダ不正 → {@code folderError}</li>
     * </ol>
     *
     * <p>ブロックとフォルダの判定材料は他ドメインから事前に（T3・T5）読んだ結果を受け取り、所定の位置でだけ
     * 投げる。通ったら使用回数を条件付き UPDATE で1進め、確保記録（CLAIMED）を残す。
     * {@code clearAutomatically} は使わず、加算後に管理下の Entity を refresh する。</p>
     *
     * @param blocked     T3 のブロック判定結果
     * @param folderError T5 のフォルダ事前確認で不正だった場合のエラーコード（問題なし・指定なしは null）
     */
    @Transactional
    public InviteClaimTicket claim(InviteLocator locator, Long userId, boolean blocked, ErrorCode folderError) {
        InviteTokenSnapshot token = lockSnapshot(locator)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));
        return claimLocked(locator, token, userId, blocked, folderError);
    }

    /**
     * 確保、または同じ招待・同じ利用者に残った確保の再開（T6'。新規登録の保留承認の入口専用。CMP-261107-1833 設計 §9-3）。
     *
     * <p>招待行をロックした後、同じ利用者の CLAIMED が残っていれば（前回の承認がロール付与の前後で失敗した、または
     * 5 分を超えて取り直された古い attempt が処理中）、<b>使用回数を進めずに</b>その確保を {@code resumed=true} で返す。</p>
     * <ul>
     *   <li>確保に付与印がある（この確保による付与がコミット済み）: 判定をせず {@code grantCommitted=true} で返す。
     *       参加は成立しているので、招待がその後に失効・期限切れになっていても戻さない（Codex 検分 P1）。
     *       Facade は付与を行わず確定（T9）だけ行う。</li>
     *   <li>付与印が無い: 上限到達と在籍済みの判定は飛ばし（使用回数はその確保が消費済み。在籍済みは T7 が判定し、
     *       印が無ければ別経路の参加として確保を戻す）、失効・期限切れ・宛先違い・特権ロール・閉鎖世代・ブロック・
     *       フォルダの判定は残す。掛かったら同じトランザクションで確保を戻してから例外を投げる（業務例外では巻き戻さない）。
     *       確保の行はロック済みなので、並行する古い attempt の付与（T7 は claim 行をロックしてから印を立てる）とは
     *       直列化され、印の有無の判定と戻しの間に付与が割り込むことはない。付与印の導入前からの旧データ
     *       （付与されたか不明）は判定に掛かっても戻さない（CLAIMED のまま残し、使用回数を戻さない）。</li>
     * </ul>
     *
     * <p>残った確保が無ければ {@link #claim} と同じ判定と確保を行う。</p>
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public InviteClaimTicket claimOrResume(InviteLocator locator, Long userId, boolean blocked, ErrorCode folderError) {
        InviteTokenSnapshot token = lockSnapshot(locator)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));
        Optional<InviteClaimLock> orphan = inviteTokenRepository.findClaimedForUpdate(token.id(), userId);
        if (orphan.isEmpty()) {
            return claimLocked(locator, token, userId, blocked, folderError);
        }
        UUID claimId = orphan.get().claimId();
        if (orphan.get().grantCommitted()) {
            log.info("残っていた招待の確保は付与コミット済み（確定だけ行う）: claimId={}, tokenId={}, userId={}",
                    claimId, token.id(), userId);
            return new InviteClaimTicket(claimId, token.id(), token.scopeType(), token.scopeId(), token.roleId(),
                    token.createdBy(), true, true);
        }
        try {
            verifyResumable(locator, token, userId, blocked, folderError);
        } catch (BusinessException rejected) {
            releaseLocked(claimId, token.id());
            throw rejected;
        }
        log.info("残っていた招待の確保を再開（使用回数は進めない）: claimId={}, tokenId={}, userId={}",
                claimId, token.id(), userId);
        return new InviteClaimTicket(claimId, token.id(), token.scopeType(), token.scopeId(), token.roleId(),
                token.createdBy(), true);
    }

    /**
     * 在籍済みの利用者について、同じ招待・同じ利用者に残った CLAIMED の確保を片付ける（CMP-261107-1833 設計 §9-3）。
     *
     * <p>招待行 → 確保の順にロックし、付与印があれば（前回の attempt がこの確保で付与をコミットした）GRANTED に
     * 確定して招待監査を出す。付与印の導入前から CLAIMED だった旧データ（付与されたか不明）も、在籍済みなので
     * 確定する（戻すと上限を超えて招待を使わせうるため、使われた側に倒す）。付与印が無ければ、在籍は別経路
     * （参加申請の承認・別の招待など）によるもので
     * この確保は参加に使われていないので、戻して使用回数を1戻す（Codex 検分 P2）。残った確保が無ければ何もしない。</p>
     */
    @Transactional
    public OrphanSettlement settleOrphanClaim(InviteLocator locator, Long userId) {
        Optional<InviteTokenSnapshot> token = lockSnapshot(locator);
        if (token.isEmpty()) {
            return OrphanSettlement.NONE;
        }
        Optional<InviteClaimLock> orphan = inviteTokenRepository.findClaimedForUpdate(token.get().id(), userId);
        if (orphan.isEmpty()) {
            return OrphanSettlement.NONE;
        }
        UUID claimId = orphan.get().claimId();
        if (orphan.get().grantCommitted() || orphan.get().grantUnknown()) {
            // 付与印あり、または付与されたか不明な旧データ（在籍済みなので、この確保で参加した側に倒す。戻さない）。
            confirmLocked(claimId);
            return OrphanSettlement.CONFIRMED;
        }
        releaseLocked(claimId, token.get().id());
        return OrphanSettlement.RELEASED;
    }

    /** 招待のハッシュで、付与するロールの名前を引く（保留承認の表示用。CMP-261107-1833 設計 §2）。 */
    @Transactional(readOnly = true)
    public Optional<String> findRoleName(String tokenHash) {
        return inviteTokenRepository.findRoleNameByTokenHash(tokenHash);
    }

    /**
     * 招待のハッシュで、有効性を DB の現在時刻で判定する（保留の参照時の期限判定・承認時の処理中判定用。
     * CMP-261107-1833 PR3 設計 c・g）。招待が無ければ空。
     */
    @Transactional(readOnly = true)
    public Optional<InviteValidity> findValidity(String tokenHash, Long userId) {
        return inviteTokenRepository.findValidityByTokenHash(tokenHash, userId);
    }

    private void verifyResumable(InviteLocator locator, InviteTokenSnapshot token, Long userId, boolean blocked,
                                 ErrorCode folderError) {
        if (locator.concealsNonTarget() && !userId.equals(token.targetUserId())) {
            throw locator.targetMismatch();
        }
        // 上限は確保済みの1回を含むため判定しない（失効・期限切れだけを見る）。
        if (!InviteTokenEntity.isValidState(token.revokedAt(), token.expiresAt(), null, 0, token.dbNow())) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }
        if (token.targetUserId() != null && !token.targetUserId().equals(userId)) {
            throw locator.targetMismatch();
        }
        RoleEntity grantRole = roleRepository.findById(token.roleId()).orElse(null);
        if (grantRole != null && PRIVILEGED_INVITE_ROLES.contains(grantRole.getName())) {
            throw new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!scopeStates.isCurrent(token.scopeType(), token.scopeId(), token.scopeClosureGeneration())) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }
        if (blocked) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }
        if (folderError != null) {
            throw new BusinessException(folderError);
        }
    }

    private InviteClaimTicket claimLocked(InviteLocator locator, InviteTokenSnapshot token, Long userId, boolean blocked,
                                          ErrorCode folderError) {
        // ID 入口: 宛先本人でない・共有リンク型は、招待の状態（上限・期限）を明かす前に不在と同じ応答にする。
        if (locator.concealsNonTarget() && !userId.equals(token.targetUserId())) {
            throw locator.targetMismatch();
        }

        if (!InviteTokenEntity.isValidState(token.revokedAt(), token.expiresAt(), token.maxUses(),
                token.usedCount(), token.dbNow())) {
            if (token.maxUses() != null && token.usedCount() >= token.maxUses()) {
                throw new BusinessException(RoleErrorCode.ROLE_003);
            }
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        // F04.12 宛先照合（IDOR 防止）: 宛先付きトークンは指名相手のみ承諾可。NULL（共有リンク型）は照合スキップ。
        if (token.targetUserId() != null && !token.targetUserId().equals(userId)) {
            throw locator.targetMismatch();
        }

        // F04.12 特権ロール付与の保険ガード（C-1）。
        RoleEntity grantRole = roleRepository.findById(token.roleId()).orElse(null);
        if (grantRole != null && PRIVILEGED_INVITE_ROLES.contains(grantRole.getName())) {
            throw new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String scopeType = token.scopeType();
        Long scopeId = token.scopeId();

        // 判定点は親専属のfresh primary nonlocking read（既存の REQUIRES_NEW）。ロックなしで待ちが無い。
        if (!scopeStates.isCurrent(scopeType, scopeId, token.scopeClosureGeneration())) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        if (blocked) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }

        // CMP-027: user_roles ∪ memberships の在籍で判定（SUPPORTER・memberships のみも在籍）。
        boolean alreadyJoined = "TEAM".equals(scopeType)
                ? userRoleRepository.existsByUserIdAndTeamId(userId, scopeId)
                : userRoleRepository.existsByUserIdAndOrganizationId(userId, scopeId);
        if (alreadyJoined) {
            throw new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
        }

        if (folderError != null) {
            throw new BusinessException(folderError);
        }

        // 失効または上限到達なら 0 行。招待行をロック済みなので、ここで 0 行になるのは異常系のみ。
        if (inviteTokenRepository.incrementUsedCountIfAvailable(token.id()) != 1) {
            throw new BusinessException(RoleErrorCode.ROLE_003);
        }
        inviteTokenRepository.refreshIfManaged(token.id());

        UUID claimId = inviteTokenRepository.insertClaim(token.id(), userId);
        log.info("招待の使用回数を確保: claimId={}, tokenId={}, userId={}", claimId, token.id(), userId);
        return new InviteClaimTicket(claimId, token.id(), scopeType, scopeId, token.roleId(), token.createdBy());
    }

    /**
     * 付与されなかった確保を戻す（T8）。CLAIMED かつ付与印なし→RELEASED が1行のときだけ使用回数を1戻す。
     * 付与印のある確保（この確保による付与がコミット済み）は戻さず {@link ReleaseOutcome#GRANT_COMMITTED} を返す
     * （呼び出し元は確定する）。付与印の導入前からの旧データ（付与されたか不明）も戻さず
     * {@link ReleaseOutcome#GRANT_UNKNOWN} を返す（自動では戻さない安全側）。2回目以降・GRANTED・RELEASED への呼び出しは何もしない（冪等）。
     * 招待行 → 確保の順にロックする（T6' と同じ順。逆順だとデッドロックしうる）。2本のUPDATEは1TXで、
     * 2本目が失敗すれば全巻き戻しで CLAIMED のまま残る。
     *
     * @throws IllegalStateException claim が存在しない（設計上起こりえない。ERROR を記録する）
     */
    @Transactional
    public ReleaseOutcome releaseClaim(UUID claimId) {
        Long tokenId = inviteTokenRepository.findClaimTokenId(claimId)
                .orElseThrow(() -> invalidClaim("release", claimId, "claim が存在しない"));
        inviteTokenRepository.lockSnapshotById(tokenId);
        return releaseLocked(claimId, tokenId);
    }

    /** 招待行をロックした後の補償本体（{@link #releaseClaim} の javadoc 参照）。 */
    private ReleaseOutcome releaseLocked(UUID claimId, Long tokenId) {
        if (inviteTokenRepository.markClaimReleased(claimId) == 1) {
            if (inviteTokenRepository.decrementUsedCount(tokenId) != 1) {
                // used_count=0 の CLAIMED は不変式違反。巻き戻して CLAIMED のまま監視に出す。
                throw invalidClaim("release", claimId, "使用回数を戻せない（既に0）");
            }
            inviteTokenRepository.refreshIfManaged(tokenId);
            log.info("招待の使用回数を戻した: claimId={}, tokenId={}", claimId, tokenId);
            return ReleaseOutcome.RELEASED;
        }
        InviteClaimLock state = inviteTokenRepository.lockClaim(claimId)
                .orElseThrow(() -> invalidClaim("release", claimId, "claim が存在しない"));
        if (state.claimed() && state.grantCommitted()) {
            log.info("claim の補償は行わない（この claim による付与がコミット済み）: claimId={}", claimId);
            return ReleaseOutcome.GRANT_COMMITTED;
        }
        if (state.claimed() && state.grantUnknown()) {
            log.warn("claim の補償は行わない（付与印の導入前からの旧データで付与されたか不明。CLAIMED のまま残す）: claimId={}",
                    claimId);
            return ReleaseOutcome.GRANT_UNKNOWN;
        }
        log.info("claim の補償は無効（状態 {} のため何もしない）: claimId={}", state.status(), claimId);
        return ReleaseOutcome.SETTLED;
    }

    /**
     * 確保を確定する（T9）。CLAIMED→GRANTED が1行のときだけ招待監査イベントを発行する。
     * 既に GRANTED なら何もしない（重複・並行でも監査は1回）。
     *
     * @throws IllegalStateException claim が存在しない、または RELEASED（設計上起こりえない。ERROR を記録する）
     */
    @Transactional
    public void recordJoined(UUID claimId) {
        confirmLocked(claimId);
    }

    private void confirmLocked(UUID claimId) {
        if (inviteTokenRepository.markClaimGranted(claimId) == 1) {
            InviteClaimAuditContext context = inviteTokenRepository.findClaimAuditContext(claimId)
                    .orElseThrow(() -> invalidClaim("recordJoined", claimId, "claim の招待が引けない"));
            publishInviteAudit(context);
            return;
        }
        String status = inviteTokenRepository.findClaimStatusLocked(claimId)
                .orElseThrow(() -> invalidClaim("recordJoined", claimId, "claim が存在しない"));
        if (!"GRANTED".equals(status)) {
            throw invalidClaim("recordJoined", claimId, "確定できない状態: " + status);
        }
    }

    /**
     * 宛先本人が招待を辞退する（F04.12）。招待行を最新値でロックし、宛先違いは 403、既に失効なら
     * 何もしない（冪等）。新規に辞退したときだけ {@code newlyDeclined=true} を返す。
     */
    @Transactional
    public InviteDeclineResult decline(InviteLocator locator, Long userId) {
        InviteTokenEntity token = (switch (locator) {
            case InviteLocator.ByTokenHash byHash -> inviteTokenRepository.lockFreshByTokenHash(byHash.tokenHash());
            case InviteLocator.ById byId -> inviteTokenRepository.lockFreshById(byId.id());
        }).orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        Long targetUserId = token.getTargetUserId();
        // 宛先本人のみ辞退可（宛先なし共有リンク型は辞退の概念を持たない）。宛先不一致は 403 ROLE_009（IDOR 防止）。
        if (targetUserId == null || !targetUserId.equals(userId)) {
            throw locator.targetMismatch();
        }

        // 既に失効済み（辞退/取消/補償）なら冪等に扱う。
        if (token.getRevokedAt() != null) {
            return new InviteDeclineResult(false, token.getId(), targetUserId, token.getTeamId(),
                    token.getOrganizationId());
        }

        token.revoke(); // revoked_at を立てる。used_count は updatable=false なので書き戻さない。
        log.info("承諾型招待を辞退（DECLINED）: tokenId={}, userId={}", token.getId(), userId);
        return new InviteDeclineResult(true, token.getId(), targetUserId, token.getTeamId(),
                token.getOrganizationId());
    }

    private Optional<InviteTokenSnapshot> lockSnapshot(InviteLocator locator) {
        return switch (locator) {
            case InviteLocator.ByTokenHash byHash -> inviteTokenRepository.lockSnapshotByTokenHash(byHash.tokenHash());
            case InviteLocator.ById byId -> inviteTokenRepository.lockSnapshotById(byId.id());
        };
    }

    /** 招待監査（T9 由来）。membership 側の JOINED は T7 の {@code MembershipService.join} が別途発行する。 */
    private void publishInviteAudit(InviteClaimAuditContext context) {
        if (context.teamId() != null) {
            eventPublisher.publishEvent(new TeamMemberAuditEvent(context.invitedBy(), context.userId(),
                    context.teamId(), TeamMemberAuditEvent.SubType.INVITED));
        } else {
            eventPublisher.publishEvent(new OrganizationMemberAuditEvent(context.invitedBy(), context.userId(),
                    context.organizationId(), OrganizationMemberAuditEvent.SubType.JOINED));
        }
    }

    private IllegalStateException invalidClaim(String operation, UUID claimId, String reason) {
        log.error("招待 claim の不正な遷移: operation={}, claimId={}, reason={}", operation, claimId, reason);
        return new IllegalStateException("招待 claim の不正な遷移 " + operation + ": " + claimId + " (" + reason + ")");
    }
}
