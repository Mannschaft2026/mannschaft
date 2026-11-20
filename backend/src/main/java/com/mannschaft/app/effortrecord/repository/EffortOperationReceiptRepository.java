package com.mannschaft.app.effortrecord.repository;

import com.mannschaft.app.effortrecord.entity.EffortOperationReceiptEntity;
import com.mannschaft.app.effortrecord.enums.EffortReceiptRouteKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** F06.4.1 冪等 receipt リポジトリ。 */
public interface EffortOperationReceiptRepository extends JpaRepository<EffortOperationReceiptEntity, UUID> {

    Optional<EffortOperationReceiptEntity> findByRouteKindAndRouteIdAndActorUserIdAndOperationPathAndOperationKey(
            EffortReceiptRouteKind routeKind, Long routeId, Long actorUserId, String operationPath, String operationKey);

    /** 期限切れ receipt を削除する（保持 24 時間の期限削除バッチ用）。削除件数を返す。 */
    long deleteByExpiresAtBefore(Instant threshold);
}
