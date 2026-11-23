package com.mannschaft.app.effortrecord.service;

import org.springframework.stereotype.Service;

/**
 * F06.4.1 共有の保守コマンド（データ設計 §8。PR-3b 試練が置いた骨格。中身は出陣で実装する）。
 *
 * <p>イベント（{@code TeamEffortMembershipRevokedEvent}）が欠落しても、終了済み加入期間の REVOKED disposition と
 * 未失効の共有を grant_membership_id で突き合わせ、team・owner・timeline を冪等に失効させる。
 * 再送は保存加入期間 ID で対象を限定し、同じ user/team の別の加入期間（再加入）には触れない。</p>
 */
@Service
public class EffortShareMaintenanceService {

    /**
     * REVOKED で終わった加入期間に束縛されたまま未失効の共有を失効させる。
     *
     * @return 失効させた共有の件数
     */
    public int reconcileRevokedMemberships() {
        throw new UnsupportedOperationException("F06.4.1 PR-3b: 保守コマンドは出陣で実装する");
    }
}
