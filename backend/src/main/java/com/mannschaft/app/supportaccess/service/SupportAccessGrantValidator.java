package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.context.SupportAccessContext;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.port.SupportAccessOperatorAffiliationPort;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * F10.9 代理ヘッダーの毎リクエスト検証（キャッシュしない。取消直後の次リクエストから拒否）。
 *
 * <p>違反は BusinessException: 001(404 無い/他の運営者)・002(403 無効/未開始/本人の状態)・010(403 運営者が所属)。
 * 呼び出し側（SupportAccessFilter）が「元の認証が ROLE_SYSTEM_ADMIN か」を先に確かめる前提だが、JWT のロールは
 * 発行時点の写しのため、運営者の現在の状態（SYSTEM_ADMIN の保持・ACTIVE・退会手続中でない）も毎回 DB で確かめる。
 * トランザクションは張らない（各読み取りはそれぞれの Service・Repository の境界で完結する）。</p>
 */
@Service
public class SupportAccessGrantValidator {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;
    private final SupportAccessOperatorAffiliationPort affiliationPort;
    private final Clock clock;

    public SupportAccessGrantValidator(SupportAccessGrantRepository grantRepository,
                                       SupportAccessUserDirectory userDirectory,
                                       SupportAccessOperatorAffiliationPort affiliationPort,
                                       Clock clock) {
        this.grantRepository = grantRepository;
        this.userDirectory = userDirectory;
        this.affiliationPort = affiliationPort;
        this.clock = clock;
    }

    public SupportAccessContext validate(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        // 他の運営者の grant は存在を明かさない
        if (!grant.getOperatorUserId().equals(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND);
        }
        // 運営者自身の現在の状態を毎回判定する（SYSTEM_ADMIN の剥奪・凍結・停止・退会手続中は、発行済みの
        // アクセストークンが期限内でも次のリクエストから 002）。JWT のロールは発行時点の写しのため頼らない。
        SupportAccessUserSnapshot operator = userDirectory.findUser(operatorUserId)
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE));
        if (!STATUS_ACTIVE.equals(operator.status()) || operator.withdrawalPending() || !operator.systemAdmin()) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }

        Instant now = Instant.now(clock);
        if (grant.getStatus() != SupportAccessStatus.APPROVED
                || grant.getRevokedAt() != null
                || grant.getExpiresAt() == null
                || !now.isBefore(grant.getExpiresAt())) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }
        // 未成年・後見下は本人と保護者の両方が揃って初めて有効
        if (grant.getDecidedAt() == null
                || (grant.isGuardianApprovalRequired() && grant.getGuardianDecidedAt() == null)) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }
        // 閲覧開始（セッション）が必須。未開始・終了済みは 002
        if (!grant.isSessionActive()) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }
        if (affiliationPort.isAffiliatedWithAnyTenant(operatorUserId)) {
            throw new BusinessException(SupportAccessErrorCode.OPERATOR_AFFILIATED);
        }

        // 本人の状態ゲートは毎回判定する（凍結・保護者同意待ち・退会手続中）
        SupportAccessUserSnapshot target = userDirectory.findUser(grant.getTargetUserId())
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE));
        if (!STATUS_ACTIVE.equals(target.status()) || target.withdrawalPending() || target.systemAdmin()) {
            throw new BusinessException(SupportAccessErrorCode.GRANT_NOT_ACTIVE);
        }
        return new SupportAccessContext(grantId, operatorUserId, grant.getTargetUserId(), grant.getExpiresAt());
    }
}
