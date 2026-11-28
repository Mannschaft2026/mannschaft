package com.mannschaft.app.effortrecord.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * F06.4.1 退会 30 日後の強消去（AccountPurgedEvent。データ設計 §7）。effort ドメインの所有行だけを 1 つの TX で消す。
 *
 * <ul>
 *   <li>冪等 receipt（応答 JSON に私的メモを含む）は本人分をすべて物理削除する。</li>
 *   <li>共有していない原本（{@code share_id IS NULL}）は物理削除する。</li>
 *   <li>共有許可を持つ原本は、共有の判定に必要な非本文の列（版・共有状態・加入期間 ID・hash 等）だけを残す tombstone
 *       にする（private values / note / snapshot / 共有 mapping / desired projection / 共有項目 / 同期エラーを消去し
 *       {@code purged_at} を立てる。データ設計 §7.1 の表）。共有の読み取りは F00 が tombstone の版・hash で続ける
 *       （著者は匿名で表示する）。</li>
 * </ul>
 * 再実行しても同じ結果になる（冪等）。
 */
@Service
public class EffortRecordPurgeService {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void purgeUser(Long userId) {
        Objects.requireNonNull(userId);
        if (userId <= 0) {
            throw new IllegalArgumentException("本人IDが不正です");
        }
        entityManager.createNativeQuery("DELETE FROM effort_operation_receipts WHERE actor_user_id = ?1")
                .setParameter(1, userId).executeUpdate();
        entityManager.createNativeQuery("DELETE FROM effort_records WHERE owner_user_id = ?1 AND share_id IS NULL")
                .setParameter(1, userId).executeUpdate();
        entityManager.createNativeQuery("UPDATE effort_records SET values_json = JSON_OBJECT(), definition_snapshot = JSON_OBJECT(), "
                        + "share_definition_snapshot = NULL, share_mapping_json = NULL, desired_projection = NULL, "
                        + "selected_field_keys = NULL, last_error_code = NULL, "
                        + "private_note = NULL, purged_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) "
                        + "WHERE owner_user_id = ?1 AND share_id IS NOT NULL AND purged_at IS NULL")
                .setParameter(1, userId)
                .executeUpdate();
    }
}
