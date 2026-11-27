package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.MembersOnlyTeamEntryGuard;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.MembershipInviteIssuedToken;
import com.mannschaft.app.role.dto.MembershipInviteTokenCommand;
import com.mannschaft.app.role.dto.StagedInviteToken;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.event.InviteReissuedEvent;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
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
 *
 * <p>非公開チーム（CMP-261124-0159・F01.2 03「サポーター入口の拒否」）: TEAM の SUPPORTER 招待は、発行・再招待の仮発行・
 * 仮発行の有効化のいずれでも、トークン行に触れる前に同じTXでチーム行を {@code FOR SHARE} で読み、非公開なら 403 TEAM_073
 * （行を作らない・有効にしない）。ロック順は非公開化のTXと同じ（invite_tokens より前にチーム行）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipInviteTokenIssuer {

    private final InviteTokenRepository inviteTokenRepository;
    private final AuditLogService auditLogService;
    private final SecretTokenVault vault;
    private final DomainEventPublisher eventPublisher;
    private final RoleRepository roleRepository;
    private final MembersOnlyTeamEntryGuard membersOnlyTeamEntryGuard;

    /**
     * 宛先付き承諾型トークンを発行する（{@code target_user_id} 非 NULL・{@code max_uses = 1}）。
     *
     * @param command 呼出し側の検証済み値と親の発行時世代
     * @return 発行済みトークン（宛先本人は ID で承諾・辞退するため、平文は生成しても誰にも渡さない）
     */
    @Transactional
    public MembershipInviteIssuedToken issue(MembershipInviteTokenCommand command) {
        requireSupporterInviteAllowed(command.scopeType(), command.scopeId(), command.roleId());
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
        requireSupporterInviteAllowed(command.scopeType(), command.scopeId(), command.roleId());
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
     * 有効化できたら同じトランザクションで {@link InviteReissuedEvent} を発行する（通知はコミット後に依頼者へ配送される）。
     *
     * <p>有効化の後に保留を再確認・補償失効しない（殿の裁定・PR3 再検分）。参照時（GET・承認）が仮発行のまま窓を過ぎたと読んだ招待は、
     * 参照側が恒久失効の CAS（{@code InviteTokenRepositoryCustom#REVOKE_EXPIRED_STAGED_SQL}）を打ち、この有効化の CAS と同じ行を取り合う。
     * 先にコミットした方だけが勝つ（行ロックで直列化。時刻の述語だけでは保証しない）。失効が勝てば有効化は 0 行で ROLE_019、
     * 保留は EXPIRED で再依頼できる。有効化が勝てば参照側は読み直して VALID とし、EXPIRED にしない。auth の確定の後の保留は
     * 新しいハッシュの PENDING で、ハッシュを書き換える経路は無い。本人の辞退が割り込んだ場合に本人宛ての1回限りの招待が
     * 有効なまま残るのは許容する（tokenId を明示して承諾しない限り使われない）。</p>
     */
    @Transactional
    public boolean activateStaged(Long tokenId, Long targetUserId) {
        // 仮発行から有効化までの間に非公開化されていれば有効にしない（仮発行の印のまま残り、誰も使えない）。
        inviteTokenRepository.findById(tokenId).ifPresent(staged -> requireSupporterInviteAllowed(
                staged.getTeamId() != null ? "TEAM" : "ORGANIZATION",
                staged.getTeamId() != null ? staged.getTeamId() : staged.getOrganizationId(), staged.getRoleId()));
        if (inviteTokenRepository.activateStaged(tokenId, targetUserId) != 1) {
            return false;
        }
        inviteTokenRepository.refreshIfManaged(tokenId);
        InviteScopeRef scope = inviteTokenRepository.findScopeById(tokenId)
                .orElseThrow(() -> new IllegalStateException("有効化した招待が引けない: tokenId=" + tokenId));
        eventPublisher.publish(new InviteReissuedEvent(targetUserId, scope.scopeType(), scope.scopeId()));
        log.info("再招待の招待を有効化: tokenId={}, scopeType={}, scopeId={}", tokenId, scope.scopeType(), scope.scopeId());
        return true;
    }

    /** TEAM の SUPPORTER 招待なら、チーム行を FOR SHARE で読み、非公開チームなら 403 TEAM_073。 */
    private void requireSupporterInviteAllowed(String scopeType, Long scopeId, Long roleId) {
        if (!"TEAM".equals(scopeType) || roleId == null) {
            return;
        }
        boolean supporter = roleRepository.findById(roleId).map(role -> "SUPPORTER".equals(role.getName())).orElse(false);
        if (supporter) {
            membersOnlyTeamEntryGuard.assertSupporterEntryAllowed(scopeId);
        }
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
