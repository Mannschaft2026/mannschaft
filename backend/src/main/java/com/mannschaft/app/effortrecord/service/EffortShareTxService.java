package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortShareState;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import com.mannschaft.app.effortrecord.event.EffortShareRequestedEvent;
import com.mannschaft.app.effortrecord.event.EffortShareRevokedEvent;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 共有の owner 側 TX（データ設計 §3・§6。原本行が共有の desired 状態の正本）。
 *
 * <p>共有 PUT・retry・本人取消は receipt の確保・原本行の行ロック・版の検証・desired 状態の更新・応答保存を
 * 同じ owner TX で確定し、commit 後（AFTER_COMMIT）に coordinator へ同期を要求する。他ドメインの照会
 * （所属・資格・定義の live 確認）は呼出元の非 TX Facade（{@link EffortShareService}）が TX の外で済ませる。
 * ログには操作・ID・版・結果だけを書き、値・note・token・projection を書かない（AC-61）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortShareTxService {

    /** 共有が有効な（team へ同期中・同期済み・同期失敗の）状態。 */
    public static final Set<EffortShareState> ACTIVE =
            EnumSet.of(EffortShareState.PENDING, EffortShareState.VISIBLE, EffortShareState.FAILED);
    /** 本人の明示 retry を受け付ける PENDING の経過時間（API 契約 §4.1）。 */
    public static final Duration RETRY_AFTER_PENDING = Duration.ofSeconds(60);
    /** 同期失敗の記録に使う一般化したコード（内部の例外内容を本人へ返さない）。 */
    public static final String SYNC_FAILED = "EFFORT_SHARE_SYNC_FAILED";

    private final EffortRecordRepository records;
    private final EffortTeamShareRepository teamShares;
    private final EffortShareProjector projector;
    private final EffortSnapshotCodec codec;
    private final EffortRecordMapper mapper;
    private final EffortReceiptService receipts;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    @Qualifier("utcClock")
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    // ───────────────────────────── 読み取り ─────────────────────────────

    /** 本人の原本（不在・他人・削除済み・purged は区別せず EFFORT_001）。 */
    @Transactional(readOnly = true)
    public EffortRecordEntity owned(Long ownerUserId, UUID recordId) {
        return records.findByIdAndOwnerUserId(recordId, ownerUserId)
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
    }

    /** 同じ key・同じ本文の完了済み応答（同じ key・別の本文は 409 COMMON_003）。 */
    @Transactional(readOnly = true)
    public Optional<EffortReceiptService.StoredResponse> findCompleted(Long ownerUserId, String path, UUID key, String hash) {
        return receipts.findCompleted(ownerUserId, path, key, hash);
    }

    /** (team, 原本) のチーム側共有行。 */
    @Transactional(readOnly = true)
    public Optional<EffortTeamShareEntity> teamShare(Long teamId, UUID recordId) {
        return teamShares.findByTeamIdAndSourceRecordId(teamId, recordId);
    }

    /**
     * 共有先を検証する（preview・共有 PUT 共通）。別チームに有効な共有がある・同じチームで MODERATED（終端）なら 409。
     */
    public void checkTarget(EffortRecordEntity record, Long teamId, Optional<EffortTeamShareEntity> teamRow) {
        if (record.getShareState() != null && ACTIVE.contains(record.getShareState())
                && !teamId.equals(record.getShareTeamId())) {
            throw conflict();
        }
        if (teamId.equals(record.getShareTeamId()) && record.getShareState() == EffortShareState.MODERATED) {
            throw conflict();
        }
        if (teamRow.isPresent() && teamRow.get().getState() == EffortTeamShareState.MODERATED) {
            throw conflict();
        }
    }

    /** 予約 share UUID: 同じチームに過去の共有行があればその UUID を再利用し、なければ新規に採番する（データ設計 §2）。 */
    public UUID reserveShareId(EffortRecordEntity record, Long teamId, Optional<EffortTeamShareEntity> teamRow) {
        if (teamRow.isPresent()) {
            return teamRow.get().getId();
        }
        if (teamId.equals(record.getShareTeamId()) && record.getShareId() != null) {
            return record.getShareId();
        }
        return com.mannschaft.app.common.UuidV7.generate();
    }

    // ───────────────────────────── 共有 PUT・retry・取消 ─────────────────────────────

    /**
     * 共有 PUT（API 契約 §4）。previewToken の command で共有内容を作り直し、projection hash が preview と一致することを
     * 確かめてから PENDING へ進める（shareVersion+1）。版・内容・共有先の変化は 409 COMMON_003（再 preview）。
     */
    @Transactional
    public EffortOwnerRecordResponse share(Long ownerUserId, UUID recordId, UUID key, String path, String hash, long version,
                                           EffortPreviewTokenCodec.Claims claims, EffortDefinitionSnapshot target) {
        EffortReceiptService.Handle handle = receipts.begin(ownerUserId, path, key, hash);
        if (handle.replay() != null) {
            return fromJson(handle.replay().json());
        }
        EffortRecordEntity record = records.findOwnedForUpdate(recordId, ownerUserId)
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        if (record.getVersion() != version || record.getVersion() != claims.recordVersion()
                || record.getContentRevision() != claims.contentRevision()
                || record.getShareVersion() != claims.shareVersion()) {
            throw conflict();
        }
        checkTarget(record, claims.teamId(), teamShares.findByTeamIdAndSourceRecordId(claims.teamId(), recordId));
        EffortShareProjector.Command command = projector.readCommand(claims.mappingsJson(), claims.selectedJson());
        EffortShareProjector.Built built = projector.project(codec.parseSnapshot(record.getDefinitionSnapshot()),
                codec.parseValues(record.getValuesJson()), target, command, record.getRecordTimezone());
        if (!built.projectionHash().equals(claims.projectionHash())) {
            throw conflict();
        }
        Instant now = now();
        record.setShareId(claims.shareId());
        record.setShareTeamId(claims.teamId());
        record.setShareState(EffortShareState.PENDING);
        record.setShareVersion(record.getShareVersion() + 1);
        record.setGrantMembershipId(claims.grantMembershipId());
        record.setShareDefinitionSnapshot(codec.canonicalJson(target));
        record.setShareMappingJson(projector.commandMappingsJson(command));
        record.setSelectedFieldKeys(projector.selectedJson(command));
        record.setDesiredProjection(built.projectionJson());
        record.setDesiredHash(built.projectionHash());
        record.setLastErrorCode(null);
        record.setShareUpdatedAt(now);
        EffortRecordEntity saved = records.saveAndFlush(record);
        EffortOwnerRecordResponse response = mapper.toResponse(saved);
        receipts.complete(handle.receiptId(), 202, toJson(response));
        events.publishEvent(requested(saved));
        log.info("頑張り記録の共有を要求: ownerUserId={}, recordId={}, shareId={}, teamId={}, shareVersion={}",
                ownerUserId, saved.getId(), saved.getShareId(), saved.getShareTeamId(), saved.getShareVersion());
        return response;
    }

    /**
     * 同一 desired command の明示 retry（API 契約 §4）。FAILED、または 60 秒を超えて PENDING のままの共有だけを受け付け、
     * 内容・許可は変えない。MODERATED・REVOKED・チーム側の MODERATED は 409。
     */
    @Transactional
    public EffortOwnerRecordResponse retry(Long ownerUserId, UUID recordId, UUID key, String path, String hash, long version,
                                           long shareVersion) {
        EffortReceiptService.Handle handle = receipts.begin(ownerUserId, path, key, hash);
        if (handle.replay() != null) {
            return fromJson(handle.replay().json());
        }
        EffortRecordEntity record = records.findOwnedForUpdate(recordId, ownerUserId)
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        if (record.getVersion() != version || record.getShareVersion() != shareVersion || record.getShareId() == null) {
            throw conflict();
        }
        Instant now = now();
        boolean stalled = record.getShareState() == EffortShareState.PENDING && record.getShareUpdatedAt() != null
                && !record.getShareUpdatedAt().isAfter(now.minus(RETRY_AFTER_PENDING));
        if (record.getShareState() != EffortShareState.FAILED && !stalled) {
            throw conflict();
        }
        Optional<EffortTeamShareEntity> teamRow = teamShares.findByTeamIdAndSourceRecordId(record.getShareTeamId(), recordId);
        if (teamRow.isPresent() && teamRow.get().getState() == EffortTeamShareState.MODERATED) {
            throw conflict();
        }
        record.setShareState(EffortShareState.PENDING);
        record.setLastErrorCode(null);
        record.setShareUpdatedAt(now);
        EffortRecordEntity saved = records.saveAndFlush(record);
        EffortOwnerRecordResponse response = mapper.toResponse(saved);
        receipts.complete(handle.receiptId(), 202, toJson(response));
        events.publishEvent(requested(saved));
        log.info("頑張り記録の共有を再試行: ownerUserId={}, recordId={}, shareId={}, shareVersion={}",
                ownerUserId, saved.getId(), saved.getShareId(), saved.getShareVersion());
        return response;
    }

    /**
     * 本人取消（API 契約 §4）。owner 照合だけで受け付け、現在の所属を要求しない（脱退後でも取消できる）。
     * 許可の失効（REVOKED・shareVersion+1）を owner TX で確定してから 200 を返し、team・timeline は commit 後に隠す。
     */
    @Transactional
    public EffortOwnerRecordResponse revoke(Long ownerUserId, UUID recordId, UUID key, String path, String hash, long version,
                                            long shareVersion) {
        EffortReceiptService.Handle handle = receipts.begin(ownerUserId, path, key, hash);
        if (handle.replay() != null) {
            return fromJson(handle.replay().json());
        }
        EffortRecordEntity record = records.findOwnedForUpdate(recordId, ownerUserId)
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        if (record.getVersion() != version || record.getShareVersion() != shareVersion || record.getShareId() == null) {
            throw conflict();
        }
        // 脱退による失効（REVOKED）は本人の取消でも確定させる。MODERATED は終端で本人操作の対象外。
        if (!ACTIVE.contains(record.getShareState()) && record.getShareState() != EffortShareState.REVOKED) {
            throw conflict();
        }
        record.setShareState(EffortShareState.REVOKED);
        record.setShareVersion(record.getShareVersion() + 1);
        record.setLastErrorCode(null);
        record.setShareUpdatedAt(now());
        EffortRecordEntity saved = records.saveAndFlush(record);
        EffortOwnerRecordResponse response = mapper.toResponse(saved);
        receipts.complete(handle.receiptId(), 200, toJson(response));
        events.publishEvent(new EffortShareRevokedEvent(saved.getId(), ownerUserId, saved.getShareId(),
                saved.getShareTeamId(), saved.getShareVersion(), true));
        log.info("頑張り記録の共有を取消: ownerUserId={}, recordId={}, shareId={}, shareVersion={}",
                ownerUserId, saved.getId(), saved.getShareId(), saved.getShareVersion());
        return response;
    }

    // ───────────────────────────── 原本の訂正・削除（呼出元の owner TX 内） ─────────────────────────────

    /**
     * 原本訂正に伴う同期要求（呼出元 {@link EffortRecordTxService#update} の TX 内で呼ぶ）。凍結した共有定義・mapping・
     * allowlist から新しい contentRevision の desired projection を作り、PENDING にする（shareVersion は不変）。
     * 同期を要求したときだけ true を返す（呼出元が保存後に {@link #requested} のイベントを発行する）。
     */
    public boolean prepareCorrection(EffortRecordEntity record) {
        if (record.getShareId() == null || !ACTIVE.contains(record.getShareState())
                || record.getShareDefinitionSnapshot() == null || record.getShareMappingJson() == null
                || record.getSelectedFieldKeys() == null) {
            return false;
        }
        EffortShareProjector.Built built = projector.project(codec.parseSnapshot(record.getDefinitionSnapshot()),
                codec.parseValues(record.getValuesJson()), codec.parseSnapshot(record.getShareDefinitionSnapshot()),
                projector.readCommand(record.getShareMappingJson(), record.getSelectedFieldKeys()),
                record.getRecordTimezone());
        record.setDesiredProjection(built.projectionJson());
        record.setDesiredHash(built.projectionHash());
        record.setShareState(EffortShareState.PENDING);
        record.setLastErrorCode(null);
        record.setShareUpdatedAt(now());
        return true;
    }

    /** 原本削除と同じ owner TX で共有許可を失効させる（REVOKED・shareVersion+1）。失効させたら true。 */
    public boolean revokeOnDelete(EffortRecordEntity record) {
        if (record.getShareId() == null || !ACTIVE.contains(record.getShareState())) {
            return false;
        }
        record.setShareState(EffortShareState.REVOKED);
        record.setShareVersion(record.getShareVersion() + 1);
        record.setShareUpdatedAt(now());
        return true;
    }

    /** 保存済みの原本から同期要求イベントを作る。 */
    public EffortShareRequestedEvent requested(EffortRecordEntity saved) {
        return new EffortShareRequestedEvent(saved.getId(), saved.getOwnerUserId(), saved.getShareId(),
                saved.getShareTeamId(), saved.getContentRevision(), saved.getShareVersion(), saved.getGrantMembershipId());
    }

    // ───────────────────────────── coordinator（非 TX）から呼ぶ owner TX ─────────────────────────────

    /** 現在の desired command（PENDING のときだけ）。原本が無い・purged・PENDING でないなら empty。 */
    @Transactional(readOnly = true)
    public Optional<EffortShareCommand> pendingCommand(UUID recordId) {
        return records.findById(recordId)
                .filter(r -> r.getShareState() == EffortShareState.PENDING && r.getShareId() != null
                        && r.getDesiredProjection() != null && r.getShareDefinitionSnapshot() != null)
                .map(r -> new EffortShareCommand(r.getId(), r.getOwnerUserId(), r.getShareId(), r.getShareTeamId(),
                        r.getShareVersion(), r.getContentRevision(), r.getGrantMembershipId(),
                        codec.parseSnapshot(r.getShareDefinitionSnapshot()).definitionUuid(), r.getRecordedOn(),
                        r.getDesiredProjection(), r.getDesiredHash()));
    }

    /**
     * owner ack: 同じ shareId・shareVersion・contentRevision・hash で PENDING のままなら VISIBLE を確定する
     * （不一致は古い ack として捨てる）。確定したら要求からの経過時間を返す。
     */
    @Transactional
    public Optional<Duration> ack(EffortShareCommand command) {
        return records.findByIdForUpdate(command.recordId())
                .filter(r -> matches(r, command) && r.getShareState() == EffortShareState.PENDING)
                .map(r -> {
                    Instant requestedAt = r.getShareUpdatedAt();
                    r.setShareState(EffortShareState.VISIBLE);
                    r.setSyncedSourceVersion(r.getContentRevision());
                    r.setLastErrorCode(null);
                    r.setShareUpdatedAt(now());
                    records.saveAndFlush(r);
                    return requestedAt == null ? Duration.ZERO : Duration.between(requestedAt, now());
                });
    }

    /** 同期失敗を記録する（同じ command で PENDING のときだけ FAILED。別 TX で確定する）。 */
    @Transactional
    public boolean recordFailed(EffortShareCommand command) {
        return records.findByIdForUpdate(command.recordId())
                .filter(r -> matches(r, command) && r.getShareState() == EffortShareState.PENDING)
                .map(r -> {
                    r.setShareState(EffortShareState.FAILED);
                    r.setLastErrorCode(SYNC_FAILED);
                    r.setShareUpdatedAt(now());
                    records.saveAndFlush(r);
                    return true;
                })
                .orElse(false);
    }

    /** チーム側の MODERATED（終端）を owner 行へ反映する（同じ share のときだけ）。 */
    @Transactional
    public boolean markModerated(UUID recordId, UUID shareId) {
        return records.findByIdForUpdate(recordId)
                .filter(r -> shareId.equals(r.getShareId()) && r.getShareState() != EffortShareState.MODERATED)
                .map(r -> {
                    r.setShareState(EffortShareState.MODERATED);
                    r.setShareUpdatedAt(now());
                    records.saveAndFlush(r);
                    return true;
                })
                .orElse(false);
    }

    /**
     * 加入期間の終了による失効を owner 行へ反映する（grant_membership_id が一致する有効な共有だけ。冪等）。
     * 読み取りの deny は加入期間の終了状態で既に確定しているため、本人の版（@Version・shareVersion）は進めない
     * （本人が直前の GET の版で取消を送っても競合させない）。
     */
    @Transactional
    public boolean revokeForEndedMembership(UUID recordId, UUID shareId, Long grantMembershipId) {
        return entityManager.createNativeQuery("UPDATE effort_records SET share_state = 'REVOKED', "
                        + "share_updated_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6) "
                        + "WHERE id = UNHEX(REPLACE(?1, '-', '')) AND share_id = UNHEX(REPLACE(?2, '-', '')) "
                        + "AND grant_membership_id = ?3 AND share_state IN ('PENDING', 'VISIBLE', 'FAILED')")
                .setParameter(1, recordId.toString())
                .setParameter(2, shareId.toString())
                .setParameter(3, grantMembershipId)
                .executeUpdate() > 0;
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private static boolean matches(EffortRecordEntity r, EffortShareCommand command) {
        return command.shareId().equals(r.getShareId()) && command.teamId().equals(r.getShareTeamId())
                && r.getShareVersion() == command.shareVersion() && r.getContentRevision() == command.contentRevision()
                && command.grantMembershipId().equals(r.getGrantMembershipId())
                && command.projectionHash().equals(r.getDesiredHash());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static BusinessException conflict() {
        return new BusinessException(CommonErrorCode.COMMON_003);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("直列化に失敗しました", e);
        }
    }

    private EffortOwnerRecordResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, EffortOwnerRecordResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの応答を読み込めません", e);
        }
    }
}
