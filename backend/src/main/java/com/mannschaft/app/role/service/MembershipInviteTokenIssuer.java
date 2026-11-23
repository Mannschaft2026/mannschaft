package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.dto.MembershipInviteIssuedToken;
import com.mannschaft.app.role.dto.MembershipInviteTokenCommand;
import com.mannschaft.app.role.dto.StagedInviteToken;
import com.mannschaft.app.role.entity.InviteTokenEntity;
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
     * 再招待の仮発行（CMP-261107-1833 PR3 設計 c）。宛先付き・max_uses=1・期限 7 日・現在の世代・作成者は管理者で、
     * {@code revoked_at = created_at}（仮発行の印）にして作る。平文は破棄し、ハッシュだけを返す。
     *
     * <p><b>試練スタブ</b>: 出陣で実装する。</p>
     */
    public StagedInviteToken stageForReissue(MembershipInviteTokenCommand command) {
        throw new UnsupportedOperationException("未実装: MembershipInviteTokenIssuer#stageForReissue（CMP-261107-1833 PR3）");
    }

    /**
     * 仮発行したトークンの有効化（CMP-261107-1833 PR3 設計 c）。
     * {@code UPDATE invite_tokens SET revoked_at = NULL WHERE id = ? AND target_user_id = ? AND used_count = 0
     * AND revoked_at = created_at}。同じトランザクションで再招待のイベントを発行する。0 行なら false。
     *
     * <p><b>試練スタブ</b>: 出陣で実装する。</p>
     */
    public boolean activateStaged(Long tokenId, Long targetUserId) {
        throw new UnsupportedOperationException("未実装: MembershipInviteTokenIssuer#activateStaged（CMP-261107-1833 PR3）");
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
