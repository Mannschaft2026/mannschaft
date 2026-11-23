package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.organization.service.OrganizationInviteQueryService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteClaimTicket;
import com.mannschaft.app.role.dto.InviteDeclineResult;
import com.mannschaft.app.role.dto.InviteLocator;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.InviteValidity;
import com.mannschaft.app.role.service.MembershipGrantService.GrantOutcome;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.InviteFolderCheck;
import com.mannschaft.app.scopefolder.service.InviteFolderPlacementService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamInviteQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 招待の承諾・辞退の入口（CMP-260826-1457 W1 前段・陣立て書 §3/§4）。
 *
 * <p>以前は {@code InviteService#joinByInvite} が1本の {@code @Transactional} の中で user ロック、ブロック判定、
 * ロール付与、membership、フォルダ配置、監査を行い、トランザクションがドメインを越境していた。本クラスは
 * <b>{@code @Transactional} を持たず</b>、各ドメインの短いトランザクションを順に呼ぶ同期 Saga ＋補償として
 * 手順・補償・例外の対応付けだけを担う。Repository にも他ドメイン Entity にも依存しない。</p>
 *
 * <h2>承諾の手順</h2>
 * <pre>
 *  T1  [auth]        user 行ロック → ACTIVE でなければ ROLE_002
 *  T2  [role RO]     所属先の射影（行ロックなし）
 *  T3  [team|org RO] ブロック判定（boolean）
 *  T5  [scopefolder] 配置先フォルダの事前確認（folderId 指定時のみ。結果を返し例外にしない）
 *  T6  [role]        claim: 招待行を最新値でロックして判定 → 使用回数+1 → 確保記録（CLAIMED）
 *  T7  [role+membership] ロール付与 + 入会（GRANTED / ALREADY_JOINED）。付与したら同じTXで確保に付与印を立てる
 *  T8  [role]        付与されなかった確保を戻す（ちょうど1回。付与印のある確保は戻さない）
 *  T9  [role]        確保を確定（GRANTED）し招待監査を発行
 *  T10/T11 [scopefolder] 既定フォルダ取得/作成 → フォルダ配置
 * </pre>
 *
 * <h2>補償（T8 の条件）</h2>
 * <p>T7 で付与されなかったと確定できる場合だけ確保を戻す: {@code ALREADY_JOINED} が正常に返った場合、または
 * T7 本体で投げられた {@link BusinessException} がトランザクション境界をそのまま越えてきた場合
 * （ロールバックが正常に完了した証拠）。それ以外の例外（ロック待ちタイムアウト、コミット成否不明、
 * rollback 失敗など）では戻さない。確保は CLAIMED のまま残り、監視（gauge）で可視化される。
 * ただし確保に付与印（この確保による付与がコミット済み。T7 が付与と同じTXで立てる）があれば、どの条件でも戻さない
 * （戻しの条件付き UPDATE 自体が印の無いことを条件にしている）。印の無い確保は付与に使われていない
 * （CMP-261107-1833・Codex 検分 P1/P2）。</p>
 *
 * <h2>部分成功（案1・陣立て書 §10）</h2>
 * <p>外側トランザクションが無い通常の HTTP 経路では、<b>参加（ロール付与・入会・使用回数・監査）の確定後に
 * フォルダ配置が失敗しても、参加は取り消さずエラーを返す</b>。応答は配置のエラーコードで、参加は成立している
 * （再送すると 409 TEAM_003）。配置はフォルダ画面から本人が直せる。外側トランザクションがある経路
 * （登録経路など）では配置の例外が外側ごと巻き戻り、何も残らない。</p>
 *
 * <h2>外側トランザクションへの参加</h2>
 * <p>各段は REQUIRED なので外側があれば参加して物理1TXになる。例外は閉鎖世代の判定
 * （{@code InviteScopeStateService#isCurrent} の REQUIRES_NEW・ロックなし読み）だけである。
 * 呼び出し元が握る行（user 行など）を別接続でロックしに行ってはならない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteAcceptanceFacade {

    private static final String SOURCE_INVITE_TOKEN = "INVITE_TOKEN";

    private final UserRowLockService userRowLockService;
    private final InviteAcceptanceWriter writer;
    private final TeamInviteQueryService teamInviteQueryService;
    private final OrganizationInviteQueryService organizationInviteQueryService;
    private final InviteFolderPlacementService placementService;
    private final MembershipGrantService membershipGrantService;
    private final AuditLogService auditLogService;
    private final SecretTokenVault vault;

    /** 招待トークンで参加する（後方互換版。folderId=null と等価）。 */
    public void joinByInvite(String token, Long userId) {
        joinByInvite(token, userId, null);
    }

    /**
     * 招待トークンで参加する（F15.3 §5.1.1: folderId 受領版）。
     *
     * <p>参加成功後、{@code folderId} 指定時はそのフォルダへ配置 (assignedVia=INVITE)、未指定時は「未分類」
     * フォルダへ自動配置 (DEFAULT) する。フォルダの不正（存在しない・種類違い）は、書き込み前の事前確認で
     * 検出して現行どおり参加せずエラーを返す。事前確認の後に起きた配置の失敗は部分成功（クラス javadoc 参照）。</p>
     */
    public void joinByInvite(String token, Long userId, Long folderId) {
        accept(new InviteLocator.ByTokenHash(vault.hash(token)), userId, folderId);
    }

    /**
     * 承諾型招待を招待 ID で承諾して参加する（平文トークンを持ち回らないための入口）。
     *
     * <p>宛先本人でない・共有リンク型・存在しない ID は、区別せずいずれも 404 ROLE_002（ID は推測できるため
     * 存在オラクルを作らない）。以降の判定と手順はトークン入口と同じ {@link #accept} である。</p>
     */
    public void acceptInvitationById(Long tokenId, Long userId, Long folderId) {
        accept(new InviteLocator.ById(tokenId), userId, folderId);
    }

    /**
     * 招待のハッシュで参加する（新規登録の保留承認専用の入口。CMP-261107-1833 設計 §2・§9-3）。
     * 呼び出し元は {@code PendingInviteService} のみ。平文トークンは保存していないため、登録時に保存したハッシュで指す。
     *
     * <p>手順は {@link #joinByInvite(String, Long)} と同じ（フォルダ指定なし＝既定フォルダへ配置）。ただし T6 は
     * {@link InviteAcceptanceWriter#claimOrResume} で、前回の承認が途中で失敗して同じ利用者の確保（CLAIMED）が
     * 残っていれば、使用回数を進めずにその確保を再開する。保留の ACCEPTING が 5 分を超えて取り直されると、
     * 1つの確保を新旧の attempt が共有しうるため、確保を戻すか確定するかは確保の付与印（T7 と同じトランザクションで
     * 立つ）で決める（Codex 検分 P1/P2）。</p>
     * <ul>
     *   <li>再開した確保に付与印がある: 付与はコミット済み。T7 を行わず確定（T9）と配置だけ行う
     *       （招待がその後に失効していても戻さない）。</li>
     *   <li>T7 が {@code ALREADY_JOINED}: 確保に付与印が無ければ別経路の参加なので戻して 409 TEAM_003（通常の承諾と同じ）。
     *       付与印があれば（別 attempt がこの確保で先に付与した）戻さず確定して 409 TEAM_003（配置はその attempt に任せる）。</li>
     * </ul>
     */
    public void joinByInviteTokenHash(String tokenHash, Long userId) {
        InviteLocator locator = new InviteLocator.ByTokenHash(tokenHash);

        // T1
        if (userRowLockService.lock(userId) != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        // T2〜T3
        Optional<InviteScopeRef> scope = writer.inspectScope(locator);
        boolean blocked = scope.isPresent() && isBlocked(scope.get(), userId);

        // T6'
        InviteClaimTicket ticket = writer.claimOrResume(locator, userId, blocked, null);

        if (ticket.grantCommitted()) {
            log.info("再開した確保による付与はコミット済み（付与は行わず確定する）: claimId={}, userId={}",
                    ticket.claimId(), userId);
        } else {
            // T7〜T8（通常の承諾と同じ。ALREADY_JOINED は付与印で戻すか確定するかを決めて 409）
            grantOrRelease(ticket, userId);
        }

        // T9
        recordJoinedOrLog(ticket);

        log.info("招待付き登録の保留承認による参加完了: userId={}, scopeType={}, scopeId={}, resumed={}",
                userId, ticket.scopeType(), ticket.scopeId(), ticket.resumed());

        // T10/T11
        placementService.place(userId, null, ticket.scopeType(), ticket.scopeId());
    }

    /**
     * 在籍済みのとき、同じ招待・同じ利用者に残った CLAIMED を片付ける（CMP-261107-1833 設計 §9-3）。
     * 呼び出し元は {@code PendingInviteService} のみ。残った確保が無ければ何もしない。
     *
     * <p>確保に付与印があれば（前回の attempt がこの確保で付与をコミットした）GRANTED に確定し、無ければ
     * 在籍は別経路の参加によるもので確保は使われていないので戻す（{@link InviteAcceptanceWriter#settleOrphanClaim}）。
     * 失敗しても参加は成立しているので ERROR を記録して先へ進む（CLAIMED のまま監視対象）。</p>
     */
    public void settleOrphanClaim(String tokenHash, Long userId) {
        try {
            InviteAcceptanceWriter.OrphanSettlement settled =
                    writer.settleOrphanClaim(new InviteLocator.ByTokenHash(tokenHash), userId);
            if (settled != InviteAcceptanceWriter.OrphanSettlement.NONE) {
                log.info("残っていた招待 claim を片付けた: result={}, userId={}", settled, userId);
            }
        } catch (RuntimeException e) {
            log.error("残った招待 claim の片付けに失敗（参加は成立済み。CLAIMED のまま監視対象）: userId={}", userId, e);
        }
    }

    /** 招待のハッシュで、付与するロールの名前を引く（保留承認の表示用。CMP-261107-1833 設計 §2）。 */
    public Optional<String> findInviteRoleName(String tokenHash) {
        return writer.findRoleName(tokenHash);
    }

    /**
     * 招待のハッシュで、有効性（失効・再招待の仮発行中・期限切れ・上限到達）を DB の現在時刻で引く
     * （保留承認の参照時の期限判定と承認時の処理中判定用。CMP-261107-1833 PR3 設計 c・g）。招待が無ければ空。
     */
    public Optional<InviteValidity> findInviteValidity(String tokenHash) {
        return writer.findValidity(tokenHash);
    }

    private void accept(InviteLocator locator, Long userId, Long folderId) {

        // T1: user 行を先にロックする（inactive/absent user の存在を token 検証前に oracle 化させない）。
        if (userRowLockService.lock(userId) != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        // T2〜T5: 他ドメインの読み取りは結果だけを受け取る。招待が無ければ飛ばす（claim が ROLE_002 を投げる）。
        Optional<InviteScopeRef> scope = writer.inspectScope(locator);
        boolean blocked = false;
        ErrorCode folderError = null;
        if (scope.isPresent()) {
            blocked = isBlocked(scope.get(), userId);
            if (folderId != null) {
                folderError = toFolderError(placementService.checkInviteFolder(userId, folderId,
                        scope.get().scopeType()));
            }
        }

        // T6: 判定と使用回数の確保。エラーの優先順位は claim が決める。
        InviteClaimTicket ticket = writer.claim(locator, userId, blocked, folderError);

        // T7〜T8: ロール付与と補償。
        grantOrRelease(ticket, userId);

        // T9: 確保の確定と招待監査。失敗しても参加は成立しているので ERROR を記録して先へ進む。
        recordJoinedOrLog(ticket);

        log.info("招待トークンによる参加完了: userId={}, scopeType={}, scopeId={}",
                userId, ticket.scopeType(), ticket.scopeId());

        // T10/T11: マイスコープフォルダ配置。失敗は部分成功としてそのまま呼び出し元へ返す。
        placementService.place(userId, folderId, ticket.scopeType(), ticket.scopeId());
    }

    /**
     * 承諾型招待を辞退する（F04.12）。宛先本人のみ。宛先不一致は 403 ROLE_009。
     * 新規に辞退したときだけ、コミット後に audit_logs へ理由 {@code DECLINED} を記録する。
     */
    public void declineInvite(String token, Long userId) {
        decline(new InviteLocator.ByTokenHash(vault.hash(token)), userId);
    }

    /**
     * 承諾型招待を招待 ID で辞退する。宛先本人でない・共有リンク型・存在しない ID は、区別せず 404 ROLE_002。
     */
    public void declineInvitationById(Long tokenId, Long userId) {
        decline(new InviteLocator.ById(tokenId), userId);
    }

    private void decline(InviteLocator locator, Long userId) {
        InviteDeclineResult result = writer.decline(locator, userId);
        if (!result.newlyDeclined()) {
            return;
        }
        AuditEventType event = result.teamId() != null
                ? AuditEventType.TEAM_MEMBERSHIP_INVITE_DECLINED
                : AuditEventType.ORGANIZATION_MEMBERSHIP_INVITE_DECLINED;
        auditLogService.record(
                event.name(),
                userId,
                result.targetUserId(),
                result.teamId(),
                result.organizationId(),
                null,
                null,
                null,
                "{\"token_id\":" + result.tokenId() + ",\"reason\":\"DECLINED\"}");
    }

    private void grantOrRelease(InviteClaimTicket ticket, Long userId) {
        GrantOutcome outcome;
        try {
            // 付与の元の claim を渡す。grantRole は同じトランザクションで claim をロックし、付与したら付与印を立てる。
            outcome = InviteClaimGrantScope.call(ticket.claimId(), () -> membershipGrantService.grantRole(
                    ticket.scopeType(), ticket.scopeId(), userId, ticket.roleId(), ticket.invitedBy(),
                    SOURCE_INVITE_TOKEN));
        } catch (BusinessException e) {
            // T7 本体の業務例外がそのまま境界を越えた = ロールバック完了。未付与が確定したので戻す。
            release(ticket, e);
            throw e;
        } catch (RuntimeException | Error e) {
            // コミット成否不明・rollback 失敗・ロック待ちタイムアウト等。付与されたか断定できないので戻さない。
            log.error("ロール付与の結果が不明（確保は CLAIMED のまま残し監視に任せる）: claimId={}, tokenId={}, userId={}",
                    ticket.claimId(), ticket.tokenId(), userId, e);
            throw e;
        }
        if (outcome == GrantOutcome.ALREADY_JOINED) {
            // 在籍済み（user ロック下の現在読みで確定）。既メンバーの二重参加は 409。
            // 確保に付与印が無ければ別経路の参加なので戻す。付与印があれば別 attempt がこの確保で先に付与したので、
            // 戻さず確定する（共有中の確保を解放して「参加済みなのに使用回数0」にしない。Codex 検分 P1/P2）。
            BusinessException conflict = new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
            InviteAcceptanceWriter.ReleaseOutcome released = release(ticket, conflict);
            if (released == InviteAcceptanceWriter.ReleaseOutcome.GRANT_COMMITTED
                    || released == InviteAcceptanceWriter.ReleaseOutcome.GRANT_UNKNOWN) {
                // 付与印あり、または付与されたか不明な旧データ（在籍済みなのでこの確保で参加した側に倒す）。
                recordJoinedOrLog(ticket);
            }
            throw conflict;
        }
    }

    private void recordJoinedOrLog(InviteClaimTicket ticket) {
        try {
            writer.recordJoined(ticket.claimId());
        } catch (RuntimeException e) {
            log.error("招待 claim の確定に失敗（参加は成立済み。CLAIMED のまま監視対象）: claimId={}, tokenId={}",
                    ticket.claimId(), ticket.tokenId(), e);
        }
    }

    /** 確保を戻す。失敗は元の例外の抑制例外に残し null を返す。 */
    private InviteAcceptanceWriter.ReleaseOutcome release(InviteClaimTicket ticket, RuntimeException primary) {
        try {
            return writer.releaseClaim(ticket.claimId());
        } catch (RuntimeException releaseFailure) {
            log.error("招待 claim の補償に失敗（CLAIMED のまま残り監視対象）: claimId={}, tokenId={}",
                    ticket.claimId(), ticket.tokenId(), releaseFailure);
            primary.addSuppressed(releaseFailure);
            return null;
        }
    }

    private boolean isBlocked(InviteScopeRef scope, Long userId) {
        return "TEAM".equals(scope.scopeType())
                ? teamInviteQueryService.isBlocked(scope.scopeId(), userId)
                : organizationInviteQueryService.isBlocked(scope.scopeId(), userId);
    }

    private static ErrorCode toFolderError(InviteFolderCheck check) {
        return switch (check) {
            case OK -> null;
            case NOT_FOUND -> ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND;
            case TYPE_MISMATCH -> ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH;
        };
    }
}
