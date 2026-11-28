package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.AuthErrorCode;
import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.PendingInviteSnapshot;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteValidity;
import com.mannschaft.app.role.service.InviteAcceptanceFacade;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 招待付き新規登録の保留承認（ログイン後に本人が承認して参加）の入口（CMP-261107-1833 設計 §2・§3・§9）。
 *
 * <p><b>{@code @Transactional} を持たない</b>。保留の状態は {@link PendingInviteStore}（auth の短TX）で、
 * 参加は {@link InviteAcceptanceFacade#joinByInviteTokenHash}（各ドメインの短TXの連鎖）で行う。
 * 外側TXを張ると参加の各段が1本の越境TXに合流してしまうため、ここでは張らない。</p>
 *
 * <h2>承認の手順</h2>
 * <ol>
 *   <li>PENDING（または 5 分を超えて止まった ACCEPTING）を新しい attempt で ACCEPTING にする。取れなければ
 *       現状態で応える（JOINED なら 200 JOINED、処理中なら AUTH_086、終了・辞退済みなら AUTH_085、無ければ AUTH_084）</li>
 *   <li>既に在籍済みなら招待を使わず、残った確保（CLAIMED）を片付けて JOINED（付与印があれば確定、
 *       無ければ別経路の参加なので戻す）</li>
 *   <li>招待のハッシュで参加する</li>
 *   <li>結果を写像して自分の attempt で確定する。成功・在籍済み（TEAM_003）・参加後のフォルダ配置失敗は JOINED、
 *       失効・期限切れ（ROLE_002）・上限到達（ROLE_003）は EXPIRED、ブロック（TEAM_004）・特権ロール等（ROLE_009）・
 *       非公開チームへのサポーター招待（TEAM_073。CMP-261124-0159）は UNAVAILABLE。それ以外（原因不明）は PENDING に戻して例外をそのまま返す</li>
 * </ol>
 *
 * <p>確定が0行（自分の attempt が古い）なら何も書かず、他の attempt が確定した現状態を返す。
 * 招待のハッシュは応答・ログに出さない。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PendingInviteService {

    /** ACCEPTING のまま止まった保留を取り直せるまでの時間（{@code RegistrationPendingInviteRepository#startAccepting} と同値）。 */
    static final long ACCEPTING_RETRY_MINUTES = 5;

    /** 再招待を依頼できる状態（CMP-261107-1833 PR3 設計 a）。見送られた依頼（REISSUE_DECLINED）からは依頼できない。 */
    static final Set<PendingInviteStatus> REISSUABLE =
            EnumSet.of(PendingInviteStatus.EXPIRED, PendingInviteStatus.REISSUE_REQUESTED);

    /**
     * 失効・見送りの案内を閉じられる状態（CMP-261107-1833 PR3 設計 a）。依頼済み（REISSUE_REQUESTED）は含めない:
     * 閉じても依頼の取り下げにはしない（実機 D-1・殿の裁定。依頼の取り下げは機能として用意しない）。依頼済みへの dismiss は
     * 他の状態不一致と同じ 409 AUTH_085。
     */
    static final Set<PendingInviteStatus> DISMISSIBLE =
            EnumSet.of(PendingInviteStatus.EXPIRED, PendingInviteStatus.REISSUE_DECLINED);

    private final PendingInviteStore store;
    private final InviteAcceptanceFacade facade;
    private final AccessControlService accessControlService;
    private final TeamService teamService;
    private final OrganizationService organizationService;

    /**
     * 本人の保留を返す。無ければ null。
     *
     * <p><b>参照時の確定（A-1 根治）</b>: 保留が PENDING のまま、本人が既にその招待先に在籍していれば（保留の承認を
     * 経ず {@code /invite/<token>} など別経路で参加した場合）、PENDING→JOINED へ確定してから返す。
     * 確定しないと次のログインで「参加しますか？」が再び出てしまう。在籍の確認は承認処理の②と同じ
     * {@link AccessControlService#isMember}、確定は保留ストアの条件付き UPDATE（PENDING のときだけ効く）で行う。
     * ACCEPTING（承認処理中）や終了済みの状態には触れない。</p>
     *
     * <p>確定は保留の状態だけを書く。{@code invite_token_claims}・{@code used_count} には<b>触れない</b>:
     * 別経路での参加なので、保留が持つ招待を使ったことにはしない（承認処理の既在籍分岐の「付与印が無ければ別経路の
     * 参加」と同じ判断）。招待を使ったことにすると、実際には使っていない招待の使用回数を消費してしまう。
     * 監査・通知も承認時の既在籍経路と同じく新たには出さない（参加自体の監査は別経路が記録済み）。</p>
     */
    public PendingInviteResponse findMine(Long userId) {
        Optional<PendingInviteSnapshot> found = store.find(userId);
        if (found.isEmpty()) {
            return null;
        }
        PendingInviteSnapshot row = found.get();
        if (row.status() == PendingInviteStatus.PENDING
                && accessControlService.isMember(userId, row.scopeId(), row.scopeType())
                && store.transition(userId, EnumSet.of(PendingInviteStatus.PENDING), PendingInviteStatus.JOINED)) {
            log.info("別経路で在籍済みの保留を JOINED に確定: userId={}, scopeType={}, scopeId={}",
                    userId, row.scopeType(), row.scopeId());
            row = store.find(userId).orElse(row);
        } else if (row.status() == PendingInviteStatus.PENDING) {
            row = expireIfUnusable(row);
        }
        ScopeView scope = scopeView(row.scopeType(), row.scopeId());
        Instant retryAvailableAt = row.status() == PendingInviteStatus.ACCEPTING && row.acceptingStartedAt() != null
                ? row.acceptingStartedAt().plus(Duration.ofMinutes(ACCEPTING_RETRY_MINUTES))
                : null;
        return new PendingInviteResponse(
                row.id(),
                row.status().name(),
                row.scopeType(),
                scope.name(),
                facade.findInviteRoleName(row.tokenHash()).orElse(null),
                scope.joinable(),
                scope.publicPath(),
                row.reissueRequestedAt(),
                retryAvailableAt);
    }

    /** 本人が承認して参加する。応答は承認後の状態。 */
    public PendingInviteAcceptResponse accept(Long userId) {
        Optional<UUID> acquired = store.tryStartAccepting(userId);
        if (acquired.isEmpty()) {
            return respondWithoutAttempt(userId);
        }
        UUID attemptId = acquired.get();
        PendingInviteSnapshot row = store.find(userId)
                .orElseThrow(() -> new IllegalStateException("取得した保留が引けない: userId=" + userId));
        if (facade.findInviteValidity(row.tokenHash(), userId).orElse(null) == InviteValidity.STAGING) {
            // 再招待の仮発行から有効化までの間（CMP-261107-1833 PR3 設計 c の窓）。招待は使わず処理中として応える。
            BusinessException processing = new BusinessException(AuthErrorCode.AUTH_086);
            revert(userId, attemptId, processing);
            throw processing;
        }

        PendingInviteStatus result;
        String failureCode = null;
        try {
            if (accessControlService.isMember(userId, row.scopeId(), row.scopeType())) {
                // 別経路で既に参加済み、または前回の承認で付与だけコミットされていた。招待は使わず、残った確保を
                // 付与印で片付ける（印があれば確定、無ければ別経路の参加なので戻す）。
                facade.settleOrphanClaim(row.tokenHash(), userId);
            } else {
                facade.joinByInviteTokenHash(row.tokenHash(), userId);
            }
            result = PendingInviteStatus.JOINED;
        } catch (BusinessException e) {
            PendingInviteStatus mapped = classify(e.getErrorCode());
            if (mapped == null) {
                revert(userId, attemptId, e);
                throw e;
            }
            result = mapped;
            failureCode = mapped == PendingInviteStatus.JOINED ? null : e.getErrorCode().getCode();
        } catch (RuntimeException | Error e) {
            revert(userId, attemptId, e);
            throw e;
        }

        if (store.completeAttempt(userId, attemptId, result, failureCode)) {
            log.info("招待付き登録の保留を承認: userId={}, result={}, failureCode={}", userId, result, failureCode);
            return new PendingInviteAcceptResponse(result.name());
        }
        // 自分の attempt は既に取り直されていた（古い）。何も書かず、他の attempt が確定した現状態を返す。
        PendingInviteStatus current = store.find(userId).map(PendingInviteSnapshot::status).orElse(result);
        log.info("古い承認 attempt の結果は書かない: userId={}, result={}, current={}", userId, result, current);
        return new PendingInviteAcceptResponse(current.name());
    }

    /** 本人が辞退する（PENDING→DECLINED）。共有招待そのものは失効させない。 */
    public void decline(Long userId) {
        if (!store.transition(userId, EnumSet.of(PendingInviteStatus.PENDING), PendingInviteStatus.DECLINED)) {
            throw stateError(userId);
        }
    }

    /**
     * 失効した招待の再発行を、招待先の招待権限者へ要請する（非公開の招待先のみ）。
     * 公開・参加可能な招待先は参加申し込みへ誘導するため 409 AUTH_085。7 日以内の再要請は通知せず
     * {@code alreadyRequested=true}。
     */
    public PendingInviteReissueResponse requestReissue(Long userId) {
        PendingInviteSnapshot row = store.find(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_084));
        if (!REISSUABLE.contains(row.status())) {
            throw new BusinessException(AuthErrorCode.AUTH_085);
        }
        if (scopeView(row.scopeType(), row.scopeId()).joinable()) {
            throw new BusinessException(AuthErrorCode.AUTH_085);
        }
        boolean requested = store.markReissueRequested(userId);
        return new PendingInviteReissueResponse(!requested);
    }

    /** 失効・見送りの案内を閉じる（EXPIRED/REISSUE_DECLINED→DISMISSED）。依頼済み（REISSUE_REQUESTED）は 409 AUTH_085。 */
    public void dismiss(Long userId) {
        if (!store.transition(userId, DISMISSIBLE, PendingInviteStatus.DISMISSED)) {
            throw stateError(userId);
        }
    }

    /**
     * 参照時の期限判定（CMP-261107-1833 PR3 設計 g）。PENDING の保留が指す招待が失効・期限切れ・上限到達なら、
     * 条件付き UPDATE で EXPIRED（失敗コード ROLE_002/ROLE_003）へ進めてから返す。判定は DB の現在時刻で行い、
     * {@code expires_at} ちょうどは有効。再招待の仮発行中（作成から 60 秒以内）は処理中として PENDING のまま返す。
     * 本人の確保（CLAIMED）が残っていれば上限到達とはみなさない（承認で再開できる）。
     * 招待が引けない場合は何もしない（承認時の参加処理が判定する）。
     */
    private PendingInviteSnapshot expireIfUnusable(PendingInviteSnapshot row) {
        Optional<InviteValidity> validity = facade.findInviteValidity(row.tokenHash(), row.userId());
        String failureCode = validity.map(InviteValidity::failureCode).orElse(null);
        // 判定に使ったハッシュを条件に含める（止まっていた参照が、再招待で替わった新しい PENDING を上書きしない。PR3 検分 P2-3）
        if (failureCode != null && store.expirePending(row.userId(), row.tokenHash(), failureCode)) {
            log.info("参照時に使えない招待の保留を EXPIRED に確定: userId={}, failureCode={}", row.userId(), failureCode);
            return store.find(row.userId()).orElse(row);
        }
        return row;
    }

    private PendingInviteAcceptResponse respondWithoutAttempt(Long userId) {
        PendingInviteSnapshot row = store.find(userId)
                .orElseThrow(() -> new BusinessException(AuthErrorCode.AUTH_084));
        return switch (row.status()) {
            case JOINED -> {
                // 二重押しの負け側・前回の確定（T9）失敗の後。残った確保があれば付与印で片付けておく。
                facade.settleOrphanClaim(row.tokenHash(), userId);
                yield new PendingInviteAcceptResponse(PendingInviteStatus.JOINED.name());
            }
            case ACCEPTING, PENDING -> throw new BusinessException(AuthErrorCode.AUTH_086);
            default -> throw new BusinessException(AuthErrorCode.AUTH_085);
        };
    }

    private BusinessException stateError(Long userId) {
        return store.find(userId).isPresent()
                ? new BusinessException(AuthErrorCode.AUTH_085)
                : new BusinessException(AuthErrorCode.AUTH_084);
    }

    private void revert(Long userId, UUID attemptId, Throwable primary) {
        try {
            if (!store.revertAttempt(userId, attemptId)) {
                log.info("古い承認 attempt は PENDING に戻さない: userId={}", userId);
            }
        } catch (RuntimeException revertFailure) {
            log.error("承認失敗後に保留を PENDING へ戻せなかった（5 分後に再承認できる）: userId={}", userId, revertFailure);
            primary.addSuppressed(revertFailure);
        }
    }

    /** 参加処理の業務例外を保留の結果状態へ写像する。写像しない（原因不明として PENDING に戻す）ものは null。 */
    private static PendingInviteStatus classify(ErrorCode code) {
        if (code == TeamErrorCode.TEAM_003 || code instanceof ScopeFolderErrorCode) {
            // 在籍済み（競合で先に付与された）／参加確定後のフォルダ配置失敗（部分成功）。参加は成立している。
            return PendingInviteStatus.JOINED;
        }
        if (code == RoleErrorCode.ROLE_002 || code == RoleErrorCode.ROLE_003) {
            return PendingInviteStatus.EXPIRED;
        }
        if (code == TeamErrorCode.TEAM_004 || code == RoleErrorCode.ROLE_009 || code == TeamErrorCode.TEAM_073) {
            // TEAM_073: 非公開チームへのサポーター招待（CMP-261124-0159・AC-0159-41）。在籍は作られていない。
            return PendingInviteStatus.UNAVAILABLE;
        }
        return null;
    }

    private ScopeView scopeView(String scopeType, Long scopeId) {
        if ("TEAM".equals(scopeType)) {
            return teamService.findJoinabilitySummary(scopeId)
                    .map(s -> new ScopeView(s.name(), s.joinable(), publicPath("/teams/", s.joinable(), s.slug())))
                    .orElse(ScopeView.NONE);
        }
        return organizationService.findJoinabilitySummary(scopeId)
                .map(s -> new ScopeView(s.name(), s.joinable(), publicPath("/organizations/", s.joinable(), s.slug())))
                .orElse(ScopeView.NONE);
    }

    private static String publicPath(String prefix, boolean joinable, String slug) {
        return joinable && slug != null ? prefix + slug : null;
    }

    private record ScopeView(String name, boolean joinable, String publicPath) {
        static final ScopeView NONE = new ScopeView(null, false, null);
    }
}
