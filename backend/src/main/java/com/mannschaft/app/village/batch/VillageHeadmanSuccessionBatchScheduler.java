package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.service.UserStatusLookupService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 村長（HEADMAN）自動引き継ぎバッチの定期起動役（F17.1 Phase 1 B11・設計書 §5.5）。
 *
 * <p>本クラスは {@code @Transactional} を持たない（クラスにもメソッドにも付けない）。全村を巡回し、村ごとに
 * {@link VillageHeadmanSuccessionBatchService#processVillage}（村単位の取引）をプロキシ経由で呼ぶ。</p>
 *
 * <p><b>運営アカウントの除外（戦役 Z-A PR2）</b>: 後継候補から運営アカウント（SYSTEM_ADMIN）を外す判定は
 * role ドメインの {@code user_roles} を読む。村の取引の中で読むと取引が role ドメインへ越境する
 * （CLAUDE.md DB 設計の原則 #5・番人 D-3T）。そこで本クラスが村の取引の外で、引き継ぎが要りうる村
 * （HEADMAN 不在、または USER 名義の HEADMAN が有効でない）の現役 ELDER / VILLAGER を
 * {@link PlatformAccountAffiliationPolicy#callJudged} で判定し、その範囲で村の取引を呼ぶ。
 * 取引の中は判定結果だけを見る。取引の外で読んでから取引までの間に加わった候補は判定結果が無く、
 * その村は失敗として記録し翌日の実行で処理する（判定なしに昇格させない）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VillageHeadmanSuccessionBatchScheduler {

    private static final int CHUNK_SIZE = 500;

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageHeadmanSuccessionBatchService batchService;
    private final UserStatusLookupService userStatusLookupService;
    private final PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;

    /**
     * 毎日 UTC 03:00 にバッチ実行する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村長の自動引き継ぎであり、再開後の実行で現在の条件に応じて引き継がれる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "village-headman-succession-daily", description = "村長 HEADMAN の自動引き継ぎを毎日 UTC 03:00 に処理する")
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @SchedulerLock(
            name = "villageHeadmanSuccessionBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        log.info("村長引き継ぎバッチ開始");

        int promoted = 0;
        int archived = 0;
        int skipped = 0;
        int failed = 0;
        int totalVillages = 0;

        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<VillageEntity> page;
        do {
            page = villageRepository.findByDeletedAtIsNullAndArchivedAtIsNull(pageable);
            for (VillageEntity village : page.getContent()) {
                totalVillages++;
                try {
                    VillageHeadmanSuccessionBatchService.SuccessionResult result = processVillage(village.getId());
                    switch (result) {
                        case PROMOTED -> promoted++;
                        case ARCHIVED -> archived++;
                        case NOT_NEEDED -> skipped++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.error("村長引き継ぎ処理失敗: villageId={}", village.getId(), e);
                }
            }
            pageable = pageable.next();
        } while (page.hasNext());

        log.info("村長引き継ぎバッチ完了: 総村数={} 昇格={} 凍結={} スキップ={} 失敗={}",
                totalVillages, promoted, archived, skipped, failed);
    }

    /** 1 村について、後継候補を取引の外で判定してから村単位の取引を呼ぶ。 */
    VillageHeadmanSuccessionBatchService.SuccessionResult processVillage(UUID villageId) {
        return PlatformAccountAffiliationPolicy.callJudged(
                platformAccountAffiliationPolicy.judge(successorCandidateUserIds(villageId)), () -> batchService.processVillage(villageId));
    }

    /**
     * 引き継ぎが要りうる村の後継候補（現役 ELDER / VILLAGER の USER 名義）を返す。要らない村は空。
     *
     * <p>HEADMAN が居て、USER 名義でないか有効な利用者なら引き継ぎは起きない（取引の中の判定と同じ。
     * 「有効でない」は退会済より広いので、候補の判定が余分になることはあっても欠けることはない）。</p>
     */
    List<Long> successorCandidateUserIds(UUID villageId) {
        Optional<VillageMembershipEntity> headman = membershipRepository
                .findFirstByVillageIdAndRoleAndLeftAtIsNullOrderByJoinedAtAsc(villageId, VillageRole.HEADMAN);
        if (headman.isPresent() && (headman.get().getSubjectType() != VillageSubjectType.USER
                || userStatusLookupService.isActive(headman.get().getSubjectId()))) {
            return List.of();
        }
        return Stream.of(VillageRole.ELDER, VillageRole.VILLAGER)
                .flatMap(role -> membershipRepository
                        .findByVillageIdAndRoleAndLeftAtIsNullAndBannedAtIsNullOrderByJoinedAtAsc(villageId, role)
                        .stream())
                .filter(m -> m.getSubjectType() == VillageSubjectType.USER)
                .map(VillageMembershipEntity::getSubjectId)
                .distinct()
                .toList();
    }
}
