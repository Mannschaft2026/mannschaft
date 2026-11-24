package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.MembershipInviteIssuedToken;
import com.mannschaft.app.role.dto.MembershipInviteTokenCommand;
import com.mannschaft.app.role.dto.StagedInviteToken;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.event.InviteReissuedEvent;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 承諾型招待の宛先付きトークン発行・補償失効を担う role ドメイン専属サービス（F04.12）。
 *
 * <p>越境トランザクション方針（原則5・設計書 §5）の要。発行フローの主体である
 * {@link MembershipInviteService} は複数ドメインの Repository を単一 {@code @Transactional} で
 * またがない。トークン発行（role ドメイン）を本サービスの独立した {@code @Transactional} メソッドに
 * 閉じ、カード投稿（chat ドメイン）は別サービスの別 {@code @Transactional} に閉じる。</p>
 *
 * <p>本番では発行 → カード投稿がそれぞれ独立コミットされる Saga となり、カード投稿失敗時は
 * {@link #revokeForCompensation(Long)} でトークンを失効して宙に浮いた PENDING を残さない。
     * 発行facadeはNOT_SUPPORTEDで、親再確認の前にrole発行TXを完了する。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipInviteTokenIssuer {

    private final InviteTokenRepository inviteTokenRepository;
    private final AuditLogService auditLogService;
    private final SecretTokenVault vault;
    private final DomainEventPublisher eventPublisher;

    /**
     * 宛先付き承諾型トークンを発行する（{@code target_user_id} 非 NULL・{@code max_uses = 1}）。
     *
     * @param command 呼出し側の検証済み値と親の発行時世代
     * @return 発行済みトークン（宛先本人は ID で承諾・辞退するため、平文は生成しても誰にも渡さない）
     */
    @Transactional
    public MembershipInviteIssuedToken issue(MembershipInviteTokenCommand command) {
        var builder = InviteTokenEntity.builder()
                .tokenHash(vault.issueBase64Url().hash())
                .roleId(command.roleId())
                .createdBy(command.createdBy())
                .expiresAt(LocalDateTime.ofInstant(command.expiresAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .maxUses(1)
                .usedCount(0)
                .targetUserId(command.targetUserId())
                .scopeClosureGeneration(command.scopeClosureGeneration());
        if ("TEAM".equals(command.scopeType())) {
            builder.teamId(command.scopeId());
        } else {
            builder.organizationId(command.scopeId());
        }
        InviteTokenEntity token = inviteTokenRepository.save(builder.build());
        log.info("承諾型招待トークン発行: tokenId={}, scopeType={}, scopeId={}, targetUserId={}",
                token.getId(), command.scopeType(), command.scopeId(), command.targetUserId());
        return new MembershipInviteIssuedToken(token.getId());
    }

    /**
     * 再招待の仮発行（CMP-261107-1833 PR3 設計 c）。宛先付き・max_uses=1・期限（呼び出し側が 7 日で渡す）・現在の世代・
     * 作成者は管理者で、{@code revoked_at = created_at}（仮発行の印）にして作る。平文は破棄し、ハッシュだけを返す。
     *
     * <p>印のある招待は失効扱いなので、承諾（tokenId 指定を含む）・DM 招待の重複判定・管理者の招待一覧のいずれにも
     * 現れない。保留の確定（auth）の後に {@link #activateStaged} で有効にする。</p>
     */
    @Transactional
    public StagedInviteToken stageForReissue(MembershipInviteTokenCommand command) {
        String tokenHash = vault.issueBase64Url().hash();
        var builder = InviteTokenEntity.builder()
                .tokenHash(tokenHash)
                .roleId(command.roleId())
                .createdBy(command.createdBy())
                .expiresAt(LocalDateTime.ofInstant(command.expiresAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .maxUses(1)
                .usedCount(0)
                .targetUserId(command.targetUserId())
                .scopeClosureGeneration(command.scopeClosureGeneration());
        if ("TEAM".equals(command.scopeType())) {
            builder.teamId(command.scopeId());
        } else {
            builder.organizationId(command.scopeId());
        }
        InviteTokenEntity token = inviteTokenRepository.saveAndFlush(builder.build());
        if (inviteTokenRepository.markStaged(token.getId()) != 1) {
            throw new IllegalStateException("仮発行した招待に印を付けられない: tokenId=" + token.getId());
        }
        log.info("再招待の招待を仮発行: tokenId={}, scopeType={}, scopeId={}, targetUserId={}",
                token.getId(), command.scopeType(), command.scopeId(), command.targetUserId());
        return new StagedInviteToken(token.getId(), tokenHash);
    }

    /**
     * 仮発行したトークンの有効化（CMP-261107-1833 PR3 設計 c）。
     * {@code UPDATE invite_tokens SET revoked_at = NULL WHERE id = ? AND target_user_id = ? AND used_count = 0
     * AND revoked_at = created_at}（作成から 60 秒の窓を過ぎたものは有効にしない。窓は
     * {@link com.mannschaft.app.role.dto.InviteValidity#STAGING_OPEN_SQL} で参照時の判定と同じ定義）。0 行なら false。
     * 通知のイベントは、司令塔が保留を再確認した後に {@link #announceReissued} で発行する（PR3 検分 P2-2）。
     */
    @Transactional
    public boolean activateStaged(Long tokenId, Long targetUserId) {
        if (inviteTokenRepository.activateStaged(tokenId, targetUserId) != 1) {
            return false;
        }
        inviteTokenRepository.refreshIfManaged(tokenId);
        log.info("再招待の招待を有効化: tokenId={}", tokenId);
        return true;
    }

    /**
     * 再招待の確定を告げる（{@link InviteReissuedEvent}。コミット後に依頼者へ通知が配送される）。
     * 有効化の後、保留がまだ当該招待を指す PENDING であることを司令塔が確かめてから呼ぶ（CMP-261107-1833 PR3 検分 P2-2）。
     */
    @Transactional
    public void announceReissued(Long tokenId, Long targetUserId) {
        InviteScopeRef scope = inviteTokenRepository.findScopeById(tokenId)
                .orElseThrow(() -> new IllegalStateException("有効化した招待が引けない: tokenId=" + tokenId));
        eventPublisher.publish(new InviteReissuedEvent(targetUserId, scope.scopeType(), scope.scopeId()));
    }

    /**
     * カード投稿失敗時の補償: 発行済みトークンを失効させる（理由 COMPENSATED・宙に浮く PENDING を残さない）。
     *
     * @param tokenId 失効対象トークン ID
     */
    @Transactional
    public void revokeForCompensation(Long tokenId) {
        inviteTokenRepository.lockFreshById(tokenId).ifPresent(token -> {
            if (token.getRevokedAt() != null) {
                return;
            }
            token.revoke();
            inviteTokenRepository.save(token);
            boolean team = token.getTeamId() != null;
            auditLogService.record(
                    (team
                            ? AuditEventType.TEAM_MEMBERSHIP_INVITE_COMPENSATED
                            : AuditEventType.ORGANIZATION_MEMBERSHIP_INVITE_COMPENSATED).name(),
                    token.getCreatedBy(),
                    token.getTargetUserId(),
                    token.getTeamId(),
                    token.getOrganizationId(),
                    null,
                    null,
                    null,
                    "{\"token_id\":" + tokenId + ",\"reason\":\"COMPENSATED\"}");
            log.warn("承諾型招待トークンを補償失効（カード投稿失敗）: tokenId={}", tokenId);
        });
    }
}
