package com.mannschaft.app.effortrecord.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.effortrecord.service.EffortShareCoordinator;
import com.mannschaft.app.effortrecord.service.EffortTeamShareTxService;
import com.mannschaft.app.membership.event.TeamEffortMembershipRevokedEvent;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import com.mannschaft.app.timeline.event.TimelinePostHiddenByModerationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F06.4.1 共有の後処理の購読（データ設計 §3・§5.1・§7.2・§8）。すべて業務 TX の commit 後（AFTER_COMMIT）に非同期で動き、
 * 処理本体は非 TX の {@link EffortShareCoordinator} が各ドメインの TX Service を順に呼ぶ。
 *
 * <p>読み取りの deny は書込の commit 時点（owner 行の REVOKED・team 行の MODERATED・加入期間の終了・チームの削除）で
 * 確定しているため、ここでの後処理が遅れても止まっても共有は読めない。後処理の欠落は保守コマンド
 * （{@code EffortShareMaintenanceService}）と本人の retry で収束させる。AFTER_COMMIT は通知規約に合わせて即時の自動リトライをしない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EffortShareEventListener {

    private final EffortShareCoordinator coordinator;
    private final EffortTeamShareTxService teamTx;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると本人が共有した頑張り記録がチームへ反映されず PENDING のまま残る（本人の retry と保守コマンドでは補えるが利用者に見える遅延になる）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShareRequested(EffortShareRequestedEvent event) {
        coordinator.sync(event);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると本人取消・原本削除の後にチーム側の行とタイムライン投稿が残る（読み取りは deny 済みだが後始末が欠落する）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShareRevoked(EffortShareRevokedEvent event) {
        coordinator.revoked(event);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると管理者非表示が本人の記録画面へ反映されず、タイムライン投稿の後始末が欠落する（読み取りは deny 済み）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamShareModerated(EffortTeamShareModeratedEvent event) {
        coordinator.moderated(event);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると脱退・除名で終わった加入期間の共有がチーム側・本人側で REVOKED にならない（読み取りは加入期間の照会で deny 済み。保守コマンドで補う）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMembershipRevoked(TeamEffortMembershipRevokedEvent event) {
        try {
            coordinator.revokeForMembership(event.teamId(), event.userId(), event.endedMembershipId());
        } catch (RuntimeException e) {
            log.error("加入期間の終了による共有の失効に失敗: teamId={}, membershipId={}, cause={}", event.teamId(),
                    event.endedMembershipId(), e.getClass().getSimpleName());
        }
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると削除したチームの共有行が論理削除されず残る（読み取りはチームの有効性の照会で deny 済み）")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTeamDeleted(TeamDeletedEvent event) {
        int deleted = teamTx.softDeleteTeam(event.getTeamId());
        log.info("削除したチームの頑張り記録の共有を論理削除: teamId={}, count={}", event.getTeamId(), deleted);
    }

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると通報で非表示にした努力由来の投稿の共有が MODERATED にならず、本人の retry・編集で再表示され得る")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostHiddenByModeration(TimelinePostHiddenByModerationEvent event) {
        try {
            coordinator.hiddenByModeration(event.postId());
        } catch (RuntimeException e) {
            log.error("通報による非表示を頑張り記録の共有へ反映できません: postId={}, cause={}", event.postId(),
                    e.getClass().getSimpleName());
        }
    }
}
