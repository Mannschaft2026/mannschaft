package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.event.EffortShareRequestedEvent;
import com.mannschaft.app.effortrecord.event.EffortShareRevokedEvent;
import com.mannschaft.app.effortrecord.event.EffortTeamShareModeratedEvent;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.timeline.service.TimelineEffortSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 共有の非 TX coordinator（データ設計 §3・§7・§8）。
 *
 * <p>owner 行の desired command を正本とし、イベントの版がそれと一致しないものは捨てる（v2 の後の v1・取消後の遅延・
 * 別チームへ移った後の旧チーム宛て）。team Service → timeline Service → owner ack を順に呼び、各 Service が自身の TX を
 * 完了してから次へ進む（@Transactional の自己呼出しを使わない）。途中で失敗したら owner 側に FAILED を別 TX で記録し、
 * 業務成功と見せない。ログには ID・版・結果と例外の種別だけを書き、値・note・projection・token を書かない（AC-61）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortShareCoordinator {

    private final EffortShareTxService ownerTx;
    private final EffortTeamShareTxService teamTx;
    private final TimelineEffortSourceService timeline;
    private final MembershipService membershipService;
    private final TeamService teamService;
    private final EffortShareMetrics metrics;

    /** 共有の同期（PENDING → team VISIBLE → timeline upsert → owner ack）。 */
    public void sync(EffortShareRequestedEvent event) {
        Optional<EffortShareCommand> found = ownerTx.pendingCommand(event.recordId())
                .filter(command -> matches(command, event));
        if (found.isEmpty()) {
            metrics.sync("discarded");
            log.debug("古い共有要求を破棄: recordId={}, shareId={}, shareVersion={}", event.recordId(), event.shareId(),
                    event.shareVersion());
            return;
        }
        EffortShareCommand command = found.get();
        try {
            if (teamService.findTeamSummary(command.teamId()).isEmpty() || !isCurrentGrant(command)) {
                // チーム削除・加入期間の終了後の要求は team へ反映しない（読み取りは資格照会で既に deny）
                fail(command, "not_eligible");
                return;
            }
            EffortTeamShareTxService.UpsertResult teamResult = teamTx.upsert(command);
            if (teamResult == EffortTeamShareTxService.UpsertResult.MODERATED) {
                ownerTx.markModerated(command.recordId(), command.shareId());
                metrics.sync("moderated");
                return;
            }
            if (teamResult == EffortTeamShareTxService.UpsertResult.STALE) {
                metrics.sync("stale");
                return;
            }
            TimelineEffortSourceService.UpsertResult timelineResult = timeline.upsertTeamShare(command.teamId(),
                    command.shareId(), command.shareVersion(), command.contentRevision(), command.grantMembershipId(),
                    command.ownerUserId());
            if (timelineResult == TimelineEffortSourceService.UpsertResult.STALE) {
                metrics.sync("stale");
                return;
            }
            ownerTx.ack(command).ifPresentOrElse(elapsed -> {
                metrics.pending(elapsed);
                metrics.sync("success");
                log.info("頑張り記録の共有を同期: recordId={}, shareId={}, teamId={}, shareVersion={}, sourceVersion={}",
                        command.recordId(), command.shareId(), command.teamId(), command.shareVersion(),
                        command.contentRevision());
            }, () -> metrics.sync("stale"));
        } catch (RuntimeException e) {
            log.error("頑張り記録の共有の同期に失敗: recordId={}, shareId={}, teamId={}, shareVersion={}, cause={}",
                    command.recordId(), command.shareId(), command.teamId(), command.shareVersion(),
                    e.getClass().getSimpleName());
            fail(command, "failed");
        }
    }

    /** 本人取消・原本削除の後処理: team 行を REVOKED にし、timeline の対応投稿を非表示にする。 */
    public void revoked(EffortShareRevokedEvent event) {
        try {
            teamTx.revoke(event.teamId(), event.recordId(), event.shareId()).ifPresent(this::hideOnTimeline);
        } catch (RuntimeException e) {
            // 読み取りの deny は owner 行の REVOKED で確定済み。後処理の失敗は記録し、保守コマンドで収束させる。
            log.error("頑張り記録の共有取消の後処理に失敗: recordId={}, shareId={}, teamId={}, cause={}",
                    event.recordId(), event.shareId(), event.teamId(), e.getClass().getSimpleName());
        }
    }

    /** チーム側の MODERATED を owner 行へ反映し、timeline の対応投稿を非表示にする。 */
    public void moderated(EffortTeamShareModeratedEvent event) {
        try {
            ownerTx.markModerated(event.recordId(), event.shareId());
        } catch (RuntimeException e) {
            // 通知が欠けても F00 は team 行の MODERATED を読んで deny する（復活しない）。
            log.error("頑張り記録の共有の非表示を本人側へ反映できません: recordId={}, shareId={}, cause={}",
                    event.recordId(), event.shareId(), e.getClass().getSimpleName());
        }
        try {
            teamTx.find(event.shareId()).ifPresent(row -> timeline.revokeTeamShare(row.getTeamId(), row.getId(),
                    row.getGrantMembershipId(), row.getShareVersion()));
        } catch (RuntimeException e) {
            log.error("頑張り記録の共有の非表示をタイムラインへ反映できません: shareId={}, cause={}", event.shareId(),
                    e.getClass().getSimpleName());
        }
    }

    /** 通報・モデレーションで努力由来の投稿が非表示にされたら、その共有を MODERATED（終端）にする。 */
    public void hiddenByModeration(Long postId) {
        timeline.findShareUuidByPostId(postId)
                .flatMap(teamTx::moderateByReport)
                .ifPresent(this::moderated);
    }

    /**
     * 加入期間の終了による失効（TeamEffortMembershipRevokedEvent・保守コマンド共通）。endedMembershipId に束縛された
     * 共有だけを team・owner・timeline で冪等に REVOKED にする（再加入後の別の加入期間の共有には触れない）。
     *
     * @return 失効させた team 行の数
     */
    public int revokeForMembership(Long teamId, Long userId, Long endedMembershipId) {
        List<EffortTeamShareTxService.Revoked> revoked = teamTx.revokeForMembership(teamId, userId, endedMembershipId);
        revoked.forEach(this::afterMembershipRevoked);
        return revoked.size();
    }

    /** 保守: 指定の共有を、加入期間 ID がまだ一致していれば失効させる。 */
    public boolean revokeIfBound(UUID shareId, Long grantMembershipId) {
        Optional<EffortTeamShareTxService.Revoked> revoked = teamTx.revokeIfBound(shareId, grantMembershipId);
        revoked.ifPresent(this::afterMembershipRevoked);
        return revoked.isPresent();
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private void afterMembershipRevoked(EffortTeamShareTxService.Revoked row) {
        ownerTx.revokeForEndedMembership(row.recordId(), row.shareId(), row.grantMembershipId());
        hideOnTimeline(row);
    }

    private void hideOnTimeline(EffortTeamShareTxService.Revoked row) {
        timeline.revokeTeamShare(row.teamId(), row.shareId(), row.grantMembershipId(), row.shareVersion());
    }

    private boolean isCurrentGrant(EffortShareCommand command) {
        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(command.teamId(), command.ownerUserId(), command.grantMembershipId())));
        return !result.isEmpty() && result.get(0).isCurrentEligible();
    }

    private void fail(EffortShareCommand command, String result) {
        metrics.sync(result);
        try {
            ownerTx.recordFailed(command);
        } catch (RuntimeException e) {
            // 失敗の記録自体に失敗しても PENDING のまま残り、60 秒後の本人 retry と監視で検知できる。
            log.error("頑張り記録の共有の失敗を記録できません: recordId={}, shareId={}, cause={}",
                    command.recordId(), command.shareId(), e.getClass().getSimpleName());
        }
    }

    private static boolean matches(EffortShareCommand command, EffortShareRequestedEvent event) {
        return command.shareId().equals(event.shareId()) && command.teamId().equals(event.teamId())
                && command.shareVersion() == event.shareVersion() && command.contentRevision() == event.sourceVersion()
                && command.grantMembershipId().equals(event.grantMembershipId());
    }
}
