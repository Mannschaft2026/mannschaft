package com.mannschaft.app.supportaccess.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.supportaccess.SupportAccessErrorCode;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.dto.MySupportAccessGrantResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantResponse;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory;
import com.mannschaft.app.supportaccess.port.SupportAccessUserSnapshot;
import com.mannschaft.app.supportaccess.repository.SupportAccessGrantRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F10.9 依頼・許可の参照系（運営側・本人側の一覧と詳細）。Entity は返さず、表示名つきの応答 DTO に変換する。
 */
@Service
@Transactional(readOnly = true)
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
                .map(g -> toOperatorResponse(g, names))
                .toList();
    }

    /** 運営者の詳細。他の運営者の依頼は存在を明かさず 001。 */
    public SupportAccessGrantResponse getForOperator(Long operatorUserId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .filter(g -> g.getOperatorUserId().equals(operatorUserId))
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return toOperatorResponse(grant, new HashMap<>());
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
                .map(g -> toMyResponse(g, userId, now, names))
                .toList();
    }

    /** 本人（または保護者）の立場で1件返す。他人には存在を明かさず 001。許可・拒否・取消の直後の応答に使う。 */
    public MySupportAccessGrantResponse getForUser(Long userId, UUID grantId) {
        SupportAccessGrantEntity grant = grantRepository.findById(grantId)
                .filter(g -> g.getTargetUserId().equals(userId)
                        || (g.isGuardianApprovalRequired() && userId.equals(g.getGuardianUserId())))
                .orElseThrow(() -> new BusinessException(SupportAccessErrorCode.GRANT_NOT_FOUND));
        return toMyResponse(grant, userId, LocalDateTime.now(clock), new HashMap<>());
    }

    private SupportAccessGrantResponse toOperatorResponse(SupportAccessGrantEntity g, Map<Long, String> names) {
        return new SupportAccessGrantResponse(
                g.getId(),
                g.getTargetUserId(),
                name(names, g.getTargetUserId()),
                g.getStatus().name(),
                g.getRequestReason(),
                g.getReferenceType(),
                g.getReferenceId(),
                g.getRequestedAt(),
                g.getRequestExpiresAt(),
                g.getDecidedAt(),
                g.getExpiresAt(),
                g.getRevokedAt(),
                g.getEndedReason() == null ? null : g.getEndedReason().name(),
                g.isGuardianApprovalRequired(),
                g.getGuardianDecidedAt(),
                g.isSessionActive());
    }

    private MySupportAccessGrantResponse toMyResponse(SupportAccessGrantEntity g, Long viewerUserId,
                                                      LocalDateTime now, Map<Long, String> names) {
        boolean guardianOnly = !g.getTargetUserId().equals(viewerUserId);
        boolean alreadyApproved = g.hasApprovalBy(guardianOnly);
        boolean canApprove = g.getStatus() == SupportAccessStatus.PENDING
                && now.isBefore(g.getRequestExpiresAt())
                && !alreadyApproved;
        return new MySupportAccessGrantResponse(
                g.getId(),
                guardianOnly ? "GUARDIAN" : "TARGET",
                name(names, g.getOperatorUserId()),
                name(names, g.getTargetUserId()),
                g.getStatus().name(),
                g.getRequestReason(),
                g.getReferenceType(),
                g.getRequestedAt(),
                g.getRequestExpiresAt(),
                g.getDecidedAt(),
                g.getExpiresAt(),
                g.getRevokedAt(),
                g.getEndedReason() == null ? null : g.getEndedReason().name(),
                g.isGuardianApprovalRequired(),
                g.getDecidedAt() != null && g.getStatus() != SupportAccessStatus.DENIED,
                g.getGuardianDecidedAt() != null,
                canApprove);
    }

    private String name(Map<Long, String> cache, Long userId) {
        return cache.computeIfAbsent(userId,
                id -> userDirectory.findUser(id).map(SupportAccessUserSnapshot::displayName).orElse(null));
    }
}
