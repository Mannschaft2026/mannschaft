package com.mannschaft.app.effortrecord.service;

import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortPersonalDataEntry;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F06.4.1 GDPR 個人データエクスポート用の収集（PersonalDataCollector から呼ばれる）。
 *
 * <p>本人（owner）の原本のみを返す。削除済み・purge 済みは Entity の {@code @SQLRestriction} で除外される（Repository を経由せず EntityManager で引く）。
 * 定義は snapshot の名称・版だけを出し、hash・receipt・共有状態は出さない。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortPersonalDataService {

    private final EffortSnapshotCodec codec;

    @PersistenceContext
    private EntityManager entityManager;

    // エクスポート本体の TX から切り離す（Ranch と同じ。他ドメインの Repository を TX 内で辿らない: D-3T）
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<EffortPersonalDataEntry> collectForGdpr(Long userId) {
        return entityManager.createQuery(
                        "SELECT r FROM EffortRecordEntity r WHERE r.ownerUserId = :userId ORDER BY r.recordedOn, r.id",
                        EffortRecordEntity.class)
                .setParameter("userId", userId).getResultList().stream()
                .map(r -> {
                    EffortDefinitionSnapshot snapshot = codec.parseSnapshot(r.getDefinitionSnapshot());
                    return new EffortPersonalDataEntry(r.getId(), r.getDefinitionId(), r.getDefinitionRevision(),
                            snapshot.templateName(), r.getRecordedOn(), r.getRecordTimezone(),
                            codec.parseValues(r.getValuesJson()), r.getPrivateNote(),
                            r.getCreatedAt(), r.getUpdatedAt());
                })
                .toList();
    }
}
