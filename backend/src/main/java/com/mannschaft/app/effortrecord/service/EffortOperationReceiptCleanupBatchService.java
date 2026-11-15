package com.mannschaft.app.effortrecord.service;

import org.springframework.stereotype.Service;

/**
 * F06.4.1 冪等 receipt の期限削除バッチ（骨格）。データ設計 §2「保持24時間で期限削除バッチ」・AC-62。
 *
 * <p>{@code expires_at} が現在時刻（{@code utcClock} Bean）より前の {@code effort_operation_receipts} を物理削除する。
 * 期限内の receipt は残す。骨格段階のため未実装（試練 {@code EffortRecordTemporalIT} が red で待つ）。
 * スケジュール起動の設定は出陣で決める。</p>
 */
@Service
public class EffortOperationReceiptCleanupBatchService {

    /** 期限切れ receipt を削除し、削除件数を返す。 */
    public int purgeExpired() {
        throw new UnsupportedOperationException("F06.4.1 未実装");
    }
}
