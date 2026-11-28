package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/**
 * F06.4.1 原本の所有者認可（親設計 §5 EffortRecordAccessPolicy）。
 *
 * <p>本人取消など、現在のチーム所属を要求しない本人操作の入口で使う。他人・不在・削除済み・purge 済みは区別せず
 * {@code EFFORT_001}（404）で存在を秘匿する。</p>
 */
@Component
@RequiredArgsConstructor
public class EffortOwnerAccessGuard {

    private final EffortRecordRepository records;

    /**
     * 認証主体が原本の所有者であることを要求する。preview・共有 PUT・retry・取消を 404 で拒むかをこの結果で決めるため、
     * 作成・訂正の直後の要求を複製の遅れで拒まないよう PRIMARY の独立 TX で読む（readOnly=false は
     * ReplicaRoutingAspect の PRIMARY 経路選択用。DML は行わない）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void requireOwner(Long userId, UUID recordId) {
        if (records.findByIdAndOwnerUserId(recordId, userId).isEmpty()) {
            throw new BusinessException(EffortErrorCode.EFFORT_001);
        }
    }
}
