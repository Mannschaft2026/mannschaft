package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.dto.MySupportAccessGrantResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantSnapshot;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F10.9 依頼・許可の参照系（運営側・本人側の一覧と詳細）。Entity は返さず、表示名つきの応答 DTO に変換する。
 *
 * <p>{@code @Transactional} を持たない。grant の読取は Repository 自身の読取トランザクションで済ませ、
 * 表示名の解決（他ドメインへの port 問い合わせ）はトランザクションの外で行う
 * （CLAUDE.md「{@code @Transactional} はドメイン内に閉じる」）。</p>
 */
@Service
public class SupportAccessGrantQueryService {

    /** 運営者の一覧で取得する上限（1日20件の上限があるため十分に小さい）。状態・対象での絞り込みはこの範囲で行う。 */
    private static final int OPERATOR_SCAN_LIMIT = 200;
    private static final LocalDateTime FAR_FUTURE = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final SupportAccessGrantRepository grantRepository;
    private final SupportAccessUserDirectory userDirectory;
    private final Clock clock;

    public SupportAccessGrantQueryService(SupportAccessGrantRepository grantRepository,
                                          SupportAccessUserDirectory userDirectory,
                                          @Qualifier("wallClock") Clock clock) {
        this.grantRepository = grantRepository;
        this.userDirectory = userDirectory;
        this.clock = clock;
    }

    /** 運営者自身の依頼だけを新しい順に返す（他の運営者の依頼は含めない）。 */
    public List<SupportAccessGrantResponse> listForOperator(Long operatorUserId, SupportAccessStatus status,
                                                            Long targetUserId, LocalDateTime before, int limit) {
        List<SupportAccessGrantEntity> rows = grantRepository.findByOperatorUserIdAndRequestedAtBeforeOrderByRequestedAtDesc(
                operatorUserId, before != null ? before : FAR_FUTURE, PageRequest.of(0, OPERATOR_SCAN_LIMIT));
        Map<Long, String> names = new HashMap<>();
        return rows.stream()
                .filter(g -> status == null || g.getStatus() == status)
                .filter(g -> targetUserId == null || g.getTargetUserId().equals(targetUserId))
                .limit(limit)
                .map(g -> toOperatorResponse(SupportAccessGrantSnapshot.from(g), names))
                .toList();
    }

    /** 運営者の詳細。他の運営者の依頼は存在を明かさず 001。 */
    public SupportAccessGrantResponse getForOperator(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .filter(g -> g.getOperatorUserId().equals(operatorUserId))
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return toOperatorResponse(SupportAccessGrantSnapshot.from(grant), new HashMap<>());
    }

    /** 運営者の所有確認のみ（閲覧記録の取得の前に使う）。 */
    public void requireOwnedByOperator(Long operatorUserId, UUID grantId) {
        getForOperator(operatorUserId, grantId);
    }

    /** 本人宛・保護者宛の依頼を新しい順に返す。 */
    public List<MySupportAccessGrantResponse> listForUser(Long userId, SupportAccessStatus status,
                                                          LocalDateTime before, int limit) {
        List<SupportAccessGrantEntity> rows = grantRepository.findVisibleToUser(
                userId, before != null ? before : FAR_FUTURE, PageRequest.of(0, OPERATOR_SCAN_LIMIT));
        Map<Long, String> names = new HashMap<>();
        LocalDateTime now = LocalDateTime.now(clock);
        return rows.stream()
                .filter(g -> status == null || g.getStatus() == status)
                .limit(limit)
                .map(g -> toMyResponse(SupportAccessGrantSnapshot.from(g), userId, now, names))
                .toList();
    }

    /** 本人（または保護者）の立場で1件返す。他人には存在を明かさず 001。許可・拒否・取消の直後の応答に使う。 */
    public MySupportAccessGrantResponse getForUser(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .filter(g -> g.getTargetUserId().equals(userId)
                        || (g.isGuardianApprovalRequired() && userId.equals(g.getGuardianUserId())))
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return toMyResponse(SupportAccessGrantSnapshot.from(grant), userId, LocalDateTime.now(clock), new HashMap<>());
    }

    /** 遷移直後の grant（運営者の視点）を応答に変換する。 */
    public SupportAccessGrantResponse toOperatorResponse(SupportAccessGrantSnapshot g) {
        return toOperatorResponse(g, new HashMap<>());
    }

    /** 遷移直後の grant（本人または保護者の視点）を応答に変換する。 */
    public MySupportAccessGrantResponse toMyResponse(SupportAccessGrantSnapshot g, Long viewerUserId) {
        return toMyResponse(g, viewerUserId, LocalDateTime.now(clock), new HashMap<>());
    }

    private SupportAccessGrantResponse toOperatorResponse(SupportAccessGrantSnapshot g, Map<Long, String> names) {
        return new SupportAccessGrantResponse(
                g.id(),
                g.targetUserId(),
                name(names, g.targetUserId()),
                g.status().name(),
                g.requestReason(),
                g.referenceType(),
                g.referenceId(),
                g.requestedAt(),
                g.requestExpiresAt(),
                g.decidedAt(),
                g.expiresAt(),
                g.revokedAt(),
                g.endedReason() == null ? null : g.endedReason().name(),
                g.guardianApprovalRequired(),
                g.guardianDecidedAt(),
                g.sessionActive());
    }

    private MySupportAccessGrantResponse toMyResponse(SupportAccessGrantSnapshot g, Long viewerUserId,
                                                      LocalDateTime now, Map<Long, String> names) {
        boolean guardianOnly = !g.targetUserId().equals(viewerUserId);
        boolean alreadyApproved = g.hasApprovalBy(guardianOnly);
        boolean canApprove = g.status() == SupportAccessStatus.PENDING
                && now.isBefore(g.requestExpiresAt())
                && !alreadyApproved;
        return new MySupportAccessGrantResponse(
                g.id(),
                guardianOnly ? "GUARDIAN" : "TARGET",
                name(names, g.operatorUserId()),
                name(names, g.targetUserId()),
                g.status().name(),
                g.requestReason(),
                g.referenceType(),
                g.requestedAt(),
                g.requestExpiresAt(),
                g.decidedAt(),
                g.expiresAt(),
                g.revokedAt(),
                g.endedReason() == null ? null : g.endedReason().name(),
                g.guardianApprovalRequired(),
                g.decidedAt() != null && g.status() != SupportAccessStatus.DENIED,
                g.guardianDecidedAt() != null,
                canApprove);
    }

    private String name(Map<Long, String> cache, Long userId) {
        return cache.computeIfAbsent(userId,
                id -> userDirectory.findUser(id).map(SupportAccessUserSnapshot::displayName).orElse(null));
    }
}
