package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.PendingInviteStatus;
import com.mannschaft.app.auth.dto.PendingReissueRequestRow;
import com.mannschaft.app.auth.dto.PendingReissueTarget;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.PendingInviteStore;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.organization.service.OrganizationInviteQueryService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.provisioning.service.ProvisioningGate;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteReissueRequestResponse;
import com.mannschaft.app.role.dto.InviteReissueResultResponse;
import com.mannschaft.app.role.dto.InviteScopeState;
import com.mannschaft.app.role.dto.MembershipInviteTokenCommand;
import com.mannschaft.app.role.dto.StagedInviteToken;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.team.service.TeamInviteQueryService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 再招待依頼の受け手側（招待権限者の一覧・再招待・見送り）の司令塔（CMP-261107-1833 PR3 設計 b・d・e）。
 *
 * <p>{@code @Transactional} を持たない。保留の状態は auth の短TX（{@link PendingInviteStore}）、トークンの仮発行と
 * 有効化は role の短TX（{@link MembershipInviteTokenIssuer}）で行う。表示名は Store の TX の外で
 * {@link NameResolverService} によりページごとにまとめて合成する。監査も TX の外で記録し、失敗しても主処理を止めない。</p>
 *
 * <h2>認可</h2>
 * <p>招待権限者（{@link InviteIssuerPolicy#canIssue}: ADMIN、または INVITE_MEMBERS を持つ DEPUTY_ADMIN）だけが
 * 一覧・再招待・見送りを行える。それ以外（権限を外された DEPUTY・MEMBER・SUPPORTER・非メンバー・他スコープの管理者）は
 * 403 COMMON_002。自スコープのパスで他スコープの依頼 ID を指すと、不在と同じ 404 ROLE_016（存在秘匿）。</p>
 *
 * <h2>再招待の手順（設計 e）</h2>
 * <ol>
 *   <li>依頼の確認（不在・他スコープ 404 ROLE_016／REISSUE_REQUESTED 以外 409 ROLE_017／依頼者が非 ACTIVE・ブロック中 409 ROLE_018）</li>
 *   <li>依頼者が在籍済みなら REISSUE_REQUESTED→JOINED、200 ALREADY_MEMBER（招待は作らない）</li>
 *   <li>PROVISIONED は既存の PROV_008、閉鎖中は ROLE_002</li>
 *   <li>仮発行（{@code revoked_at = created_at}）→ 閉鎖世代の再確認（偽なら仮発行は放置して ROLE_002）</li>
 *   <li>auth の確定（条件付き UPDATE。0 行なら競合で負けたので 409 ROLE_017、仮発行は無効のまま）</li>
 *   <li>有効化（失敗は 500 ROLE_019。保留は PENDING で使えない招待を指し、窓を過ぎた次の参照で EXPIRED になる）→ 監査</li>
 * </ol>
 * <p>補償による失効は行わない（仮発行の招待は印のまま無効で、誰も使えない）。勝敗は auth の条件付き UPDATE で決まる。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteReissueRequestService {

    /** 一覧の 1 ページの上限。 */
    public static final int MAX_PAGE_SIZE = 50;
    /** 再招待で発行する招待の有効期間。 */
    static final Duration REISSUE_VALIDITY = Duration.ofDays(7);

    private static final String TEAM = "TEAM";
    private static final String DEFAULT_GRANT_ROLE = "MEMBER";
    /** 再招待で引き継ぐ非特権ロール。特権（ADMIN/DEPUTY_ADMIN）は宛先付き招待では ROLE_009 になるため MEMBER へ寄せる。 */
    private static final Set<String> INHERITABLE_ROLES = Set.of("MEMBER", "SUPPORTER", "GUEST");

    private final PendingInviteStore pendingStore;
    private final InviteIssuerPolicy issuerPolicy;
    private final AccessControlService accessControlService;
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final TeamInviteQueryService teamInviteQueryService;
    private final OrganizationInviteQueryService organizationInviteQueryService;
    private final ProvisioningGate provisioningGate;
    private final InviteScopeStateService scopeStates;
    private final MembershipInviteTokenIssuer issuer;
    private final InviteTokenRepository inviteTokenRepository;
    private final RoleRepository roleRepository;
    private final NameResolverService nameResolverService;
    private final AuditLogService auditLogService;
    private final Clock clock;

    /**
     * 再招待で付与するロール（設計 d）。非特権（MEMBER/SUPPORTER/GUEST）は引き継ぎ、特権（ADMIN/DEPUTY_ADMIN）や
     * 元のトークン・ロールが引けない（null）場合は MEMBER。一覧の roleName と発行で同じ判定を使う。
     */
    public static String resolveGrantRole(String originalRoleName) {
        return originalRoleName != null && INHERITABLE_ROLES.contains(originalRoleName)
                ? originalRoleName : DEFAULT_GRANT_ROLE;
    }

    /** 再招待依頼の一覧（古い順）。閉鎖中のスコープは 0 件。 */
    public Page<InviteReissueRequestResponse> list(String scopeType, String slug, Long actorUserId, int page, int size) {
        Long scopeId = resolveScopeId(scopeType, slug);
        requireInviter(actorUserId, scopeType, scopeId);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        boolean available = scopeStates.read(scopeType, scopeId).map(InviteScopeState::available).orElse(false);
        if (!available) {
            return new PageImpl<>(List.of(), pageable, 0);
        }
        long total = pendingStore.countReissueRequests(scopeType, scopeId);
        List<PendingReissueRequestRow> rows = total == 0 ? List.of()
                : pendingStore.pageReissueRequests(scopeType, scopeId, safePage, safeSize);
        if (rows.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        List<Long> userIds = rows.stream().map(PendingReissueRequestRow::userId).distinct().toList();
        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(userIds);
        Map<Long, String> avatars = nameResolverService.resolveUserAvatarUrls(userIds);
        Map<String, String> originalRoles = inviteTokenRepository.findRoleNamesByTokenHashes(
                rows.stream().map(PendingReissueRequestRow::tokenHash).distinct().toList());
        List<InviteReissueRequestResponse> content = rows.stream()
                .map(row -> new InviteReissueRequestResponse(row.id(), names.get(row.userId()),
                        avatars.get(row.userId()), row.requestedAt(),
                        resolveGrantRole(originalRoles.get(row.tokenHash()))))
                .toList();
        return new PageImpl<>(content, pageable, total);
    }

    /** 再招待する（設計 e）。 */
    public InviteReissueResultResponse reissue(String scopeType, String slug, UUID pendingId, Long actorUserId) {
        Long scopeId = resolveScopeId(scopeType, slug);
        requireInviter(actorUserId, scopeType, scopeId);
        PendingReissueTarget target = requireRequested(pendingId, scopeType, scopeId);
        Long requester = target.userId();
        if (isBlocked(scopeType, scopeId, requester)) {
            throw new BusinessException(RoleErrorCode.ROLE_018);
        }
        if (accessControlService.isMember(requester, scopeId, scopeType)) {
            if (!pendingStore.joinReissueRequest(pendingId, scopeType, scopeId)) {
                throw new BusinessException(RoleErrorCode.ROLE_017);
            }
            log.info("再招待の依頼者は在籍済みのため参加確定: pendingId={}, scopeType={}, scopeId={}",
                    pendingId, scopeType, scopeId);
            return new InviteReissueResultResponse(InviteReissueResultResponse.ALREADY_MEMBER, null);
        }

        provisioningGate.requireActive(scopeId, scopeType);
        InviteScopeState state = scopeStates.read(scopeType, scopeId)
                .filter(InviteScopeState::available)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        Long roleId = grantRoleId(target.tokenHash());
        Instant expiresAt = Instant.now(clock).plus(REISSUE_VALIDITY);
        StagedInviteToken staged = issuer.stageForReissue(new MembershipInviteTokenCommand(scopeType, scopeId, roleId,
                requester, actorUserId, expiresAt, state.closureGeneration()));
        if (!scopeStates.recheckIssued(scopeType, scopeId, state.closureGeneration())) {
            // 仮発行の間に閉鎖された。仮発行の招待は印のまま無効なので失効させずに放置する。
            log.info("再招待の仮発行中に招待先が閉鎖された: pendingId={}, tokenId={}", pendingId, staged.tokenId());
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }
        if (!pendingStore.confirmReissue(pendingId, scopeType, scopeId, staged.tokenHash())) {
            // 見送り・別の再招待が先に確定した（競合の負け側）。仮発行の招待は無効のまま残る。
            log.info("再招待の確定は競合で負けた: pendingId={}, tokenId={}", pendingId, staged.tokenId());
            throw new BusinessException(RoleErrorCode.ROLE_017);
        }
        activateOrFail(pendingId, staged.tokenId(), requester);
        audit(scopeType, scopeId, actorUserId, requester, reissuedEvent(scopeType),
                "{\"pending_id\":\"" + pendingId + "\",\"token_id\":" + staged.tokenId() + "}");
        log.info("再招待を発行: pendingId={}, tokenId={}, scopeType={}, scopeId={}",
                pendingId, staged.tokenId(), scopeType, scopeId);
        return new InviteReissueResultResponse(InviteReissueResultResponse.REISSUED, expiresAt);
    }

    /** 再招待を見送る（設計 e）。ブロック中の依頼者でも見送れる。 */
    public InviteReissueResultResponse decline(String scopeType, String slug, UUID pendingId, Long actorUserId) {
        Long scopeId = resolveScopeId(scopeType, slug);
        requireInviter(actorUserId, scopeType, scopeId);
        PendingReissueTarget target = requireRequested(pendingId, scopeType, scopeId);
        if (!pendingStore.declineReissue(pendingId, scopeType, scopeId)) {
            throw new BusinessException(RoleErrorCode.ROLE_017);
        }
        audit(scopeType, scopeId, actorUserId, target.userId(), declinedEvent(scopeType),
                "{\"pending_id\":\"" + pendingId + "\"}");
        return new InviteReissueResultResponse(InviteReissueResultResponse.REISSUE_DECLINED, null);
    }

    private Long resolveScopeId(String scopeType, String slug) {
        return TEAM.equals(scopeType) ? teamService.resolveTeamId(slug) : organizationService.resolveOrgId(slug);
    }

    private void requireInviter(Long actorUserId, String scopeType, Long scopeId) {
        if (!issuerPolicy.canIssue(actorUserId, scopeId, scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    private PendingReissueTarget requireRequested(UUID pendingId, String scopeType, Long scopeId) {
        PendingReissueTarget target = pendingStore.findForScope(pendingId, scopeType, scopeId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_016));
        if (target.status() != PendingInviteStatus.REISSUE_REQUESTED) {
            throw new BusinessException(RoleErrorCode.ROLE_017);
        }
        if (!target.requesterActive()) {
            throw new BusinessException(RoleErrorCode.ROLE_018);
        }
        return target;
    }

    private boolean isBlocked(String scopeType, Long scopeId, Long userId) {
        return TEAM.equals(scopeType)
                ? teamInviteQueryService.isBlocked(scopeId, userId)
                : organizationInviteQueryService.isBlocked(scopeId, userId);
    }

    private Long grantRoleId(String originalTokenHash) {
        String grant = resolveGrantRole(inviteTokenRepository.findRoleNameByTokenHash(originalTokenHash).orElse(null));
        return roleRepository.findByName(grant)
                .map(RoleEntity::getId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
    }

    private void activateOrFail(UUID pendingId, Long tokenId, Long requester) {
        boolean activated;
        try {
            activated = issuer.activateStaged(tokenId, requester);
        } catch (RuntimeException e) {
            log.error("再招待の招待を有効化できなかった（保留は PENDING。窓を過ぎた次の参照で EXPIRED になり再依頼できる）: "
                    + "pendingId={}, tokenId={}", pendingId, tokenId, e);
            throw new BusinessException(RoleErrorCode.ROLE_019);
        }
        if (!activated) {
            log.error("再招待の招待を有効化できなかった（0 行。保留は PENDING。窓を過ぎた次の参照で EXPIRED になり再依頼できる）: "
                    + "pendingId={}, tokenId={}", pendingId, tokenId);
            throw new BusinessException(RoleErrorCode.ROLE_019);
        }
    }

    private void audit(String scopeType, Long scopeId, Long actorUserId, Long requester, AuditEventType event,
                       String metadata) {
        try {
            auditLogService.record(event.name(), actorUserId, requester,
                    TEAM.equals(scopeType) ? scopeId : null, TEAM.equals(scopeType) ? null : scopeId,
                    null, null, null, metadata);
        } catch (RuntimeException e) {
            // 監査の失敗は確定済みの主処理を止めない（N5）。
            log.error("再招待の監査記録に失敗（主処理は確定済み）: event={}, scopeType={}, scopeId={}",
                    event, scopeType, scopeId, e);
        }
    }

    private static AuditEventType reissuedEvent(String scopeType) {
        return TEAM.equals(scopeType) ? AuditEventType.TEAM_INVITE_REISSUED : AuditEventType.ORGANIZATION_INVITE_REISSUED;
    }

    private static AuditEventType declinedEvent(String scopeType) {
        return TEAM.equals(scopeType)
                ? AuditEventType.TEAM_INVITE_REISSUE_DECLINED : AuditEventType.ORGANIZATION_INVITE_REISSUE_DECLINED;
    }
}
