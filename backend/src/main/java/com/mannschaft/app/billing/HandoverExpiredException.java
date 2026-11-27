package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;

/**
 * 引継要求の期限切れを検出し、{@code EXPIRED} への確定を済ませたうえで呼び出し元へ伝える例外（CMP-260909-0304）。
 *
 * <p>{@link BusinessException} は {@link RuntimeException} のため、{@code @Transactional} の既定規則では
 * 投げた時点でトランザクションごとロールバックされ、直前の {@code EXPIRED} 更新が巻き戻る。
 * この例外だけを {@code noRollbackFor} に指定することで、<b>EXPIRED の確定をコミットしつつ</b>
 * 呼び出し元には従来どおり {@link EntitlementErrorCode#HANDOVER_EXPIRED} を返す。
 * 認可失敗など「書き込み前に投げる」例外まで noRollbackFor にすると、内側の {@code MANDATORY}
 * トランザクションが立てた rollback-only と衝突して {@code UnexpectedRollbackException} になるため、
 * 専用サブクラスに限定している。</p>
 */
public class HandoverExpiredException extends BusinessException {

    public HandoverExpiredException() {
        super(EntitlementErrorCode.HANDOVER_EXPIRED);
    }
}
