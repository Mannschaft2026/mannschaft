package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortModerateResponse;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortReceiptRouteKind;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import com.mannschaft.app.effortrecord.event.EffortTeamShareModeratedEvent;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareQueryRepository;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 チーム側共有行の TX（データ設計 §3・§6）。経路キーは team_id だけ。
 *
 * <p>upsert は版の単調性（shareVersion → contentRevision の順）を守り、古い版・取消済みの旧版では復活させない。
 * team 行の MODERATED は終端で、本人の retry・訂正・遅れて届いたイベントでも解除しない。upsert は team 行を
 * VISIBLE で commit するが、F00 は owner 行の VISIBLE と版・hash の一致も要求するため、owner ack までは表示されない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortTeamShareTxService {

    private final EffortTeamShareRepository teamShares;
    private final EffortTeamShareQueryRepository queries;
    private final EffortReceiptService receipts;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    @Qualifier("utcClock")
    private final Clock clock;

    /** upsert の結果。 */
    public enum UpsertResult {
        /** 新しい projection を VISIBLE で確定した（同じ版の再送を含む）。 */
        APPLIED,
        /** 古い版・取消済みの旧版のため何もしなかった。 */
        STALE,
        /** team 行が MODERATED（終端）のため何もしなかった。 */
        MODERATED
    }

    /**
     * 取消・失効させた team 行の、timeline へ渡す版（変更前の値）。
     *
     * @param shareId           共有 UUID
     * @param recordId          原本 ID
     * @param teamId            チーム
     * @param grantMembershipId 行に束縛されていた加入期間 ID
     * @param shareVersion      行の shareVersion
     * @param sourceVersion     行の contentRevision
     */
    public record Revoked(UUID shareId, UUID recordId, Long teamId, Long grantMembershipId, long shareVersion,
                          long sourceVersion) {
    }

    /** チームの projection を upsert する（coordinator が owner の desired command と照合済みで呼ぶ）。 */
    @Transactional
    public UpsertResult upsert(EffortShareCommand command) {
        Optional<EffortTeamShareEntity> current = teamShares.findForUpdate(command.teamId(), command.recordId());
        if (current.isEmpty()) {
            EffortTeamShareEntity created = EffortTeamShareEntity.builder()
                    .teamId(command.teamId())
                    .ownerUserId(command.ownerUserId())
                    .sourceRecordId(command.recordId())
                    .sourceVersion(command.contentRevision())
                    .grantMembershipId(command.grantMembershipId())
                    .shareVersion(command.shareVersion())
                    .definitionId(command.definitionUuid())
                    .recordedOn(command.recordedOn())
                    .projectionJson(command.projectionJson())
                    .projectionHash(command.projectionHash())
                    .state(EffortTeamShareState.VISIBLE)
                    .build();
            created.setId(command.shareId());
            teamShares.saveAndFlush(created);
            return UpsertResult.APPLIED;
        }
        EffortTeamShareEntity row = current.get();
        if (row.getState() == EffortTeamShareState.MODERATED) {
            return UpsertResult.MODERATED;
        }
        if (!row.getId().equals(command.shareId()) || isOlder(command, row)
                || (row.getState() == EffortTeamShareState.REVOKED && command.shareVersion() <= row.getShareVersion())) {
            return UpsertResult.STALE;
        }
        row.setOwnerUserId(command.ownerUserId());
        row.setSourceVersion(command.contentRevision());
        row.setGrantMembershipId(command.grantMembershipId());
        row.setShareVersion(command.shareVersion());
        row.setDefinitionId(command.definitionUuid());
        row.setRecordedOn(command.recordedOn());
        row.setProjectionJson(command.projectionJson());
        row.setProjectionHash(command.projectionHash());
        row.setState(EffortTeamShareState.VISIBLE);
        teamShares.saveAndFlush(row);
        return UpsertResult.APPLIED;
    }

    /** 本人取消・原本削除の後処理: 同じ share の team 行を REVOKED にする（MODERATED・取消済みは変えない）。 */
    @Transactional
    public Optional<Revoked> revoke(Long teamId, UUID recordId, UUID shareId) {
        return teamShares.findForUpdate(teamId, recordId)
                .filter(row -> row.getId().equals(shareId))
                .filter(row -> row.getState() == EffortTeamShareState.VISIBLE || row.getState() == EffortTeamShareState.PENDING)
                .map(this::markRevoked);
    }

    /**
     * 加入期間の終了による失効: そのチームで endedMembershipId に束縛された未失効の行だけを REVOKED にする
     * （同じ user/team の別の加入期間＝再加入後の共有には触れない）。
     */
    @Transactional
    public List<Revoked> revokeForMembership(Long teamId, Long userId, Long endedMembershipId) {
        List<Revoked> revoked = new ArrayList<>();
        for (EffortTeamShareEntity row : queries.findLiveForMembership(teamId, userId, endedMembershipId)) {
            revoked.add(markRevoked(row));
        }
        return revoked;
    }

    /** 保守: 指定の行を、加入期間 ID がまだ一致し未失効なら REVOKED にする。 */
    @Transactional
    public Optional<Revoked> revokeIfBound(UUID shareId, Long grantMembershipId) {
        return teamShares.findByIdForUpdate(shareId)
                .filter(row -> grantMembershipId.equals(row.getGrantMembershipId()))
                .filter(row -> row.getState() == EffortTeamShareState.VISIBLE || row.getState() == EffortTeamShareState.PENDING)
                .map(this::markRevoked);
    }

    /** share UUID のチーム側共有行（読み取りのみ）。 */
    @Transactional(readOnly = true)
    public Optional<EffortTeamShareEntity> find(UUID shareId) {
        return teamShares.findById(shareId);
    }

    /** 同じ key・同じ本文の完了済み管理者非表示（TEAM 経路の receipt）。 */
    @Transactional(readOnly = true)
    public Optional<EffortReceiptService.StoredResponse> findCompletedModeration(Long teamId, Long actorUserId, String path,
                                                                               UUID key, String hash) {
        return receipts.findCompleted(EffortReceiptRouteKind.TEAM, teamId, actorUserId, path, key, hash);
    }

    /**
     * 管理者非表示（API 契約 §4）。team TX を先に commit して即 deny にし、owner への反映は commit 後に行う。
     * 別チームの share UUID・不在は 404、version 不一致・非表示にできない状態は 409。
     */
    @Transactional
    public EffortModerateResponse moderate(Long actorUserId, Long teamId, UUID shareId, long version, UUID key, String path,
                                           String hash) {
        EffortReceiptService.Handle handle = receipts.begin(EffortReceiptRouteKind.TEAM, teamId, actorUserId, path, key,
                hash);
        if (handle.replay() != null) {
            return fromJson(handle.replay().json());
        }
        EffortTeamShareEntity row = teamShares.findByIdAndTeamIdForUpdate(shareId, teamId)
                .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        if (row.getVersion() != version
                || (row.getState() != EffortTeamShareState.VISIBLE && row.getState() != EffortTeamShareState.PENDING)) {
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
        row.setState(EffortTeamShareState.MODERATED);
        EffortTeamShareEntity saved = teamShares.saveAndFlush(row);
        EffortModerateResponse response = new EffortModerateResponse(saved.getId(), saved.getVersion(),
                EffortTeamShareState.MODERATED.name());
        receipts.complete(handle.receiptId(), 200, toJson(response));
        events.publishEvent(new EffortTeamShareModeratedEvent(teamId, saved.getId(), saved.getSourceRecordId(),
                actorUserId, saved.getVersion()));
        log.info("頑張り記録の共有を管理者が非表示: actorUserId={}, teamId={}, shareId={}, version={}",
                actorUserId, teamId, saved.getId(), saved.getVersion());
        return response;
    }

    /** 通報・モデレーションによる非表示（データ設計 §7.2）。VISIBLE・PENDING の行を MODERATED にする。 */
    @Transactional
    public Optional<EffortTeamShareModeratedEvent> moderateByReport(UUID shareId) {
        return teamShares.findByIdForUpdate(shareId)
                .filter(row -> row.getState() == EffortTeamShareState.VISIBLE || row.getState() == EffortTeamShareState.PENDING)
                .map(row -> {
                    row.setState(EffortTeamShareState.MODERATED);
                    EffortTeamShareEntity saved = teamShares.saveAndFlush(row);
                    log.info("頑張り記録の共有を通報により非表示: teamId={}, shareId={}, version={}",
                            saved.getTeamId(), saved.getId(), saved.getVersion());
                    return new EffortTeamShareModeratedEvent(saved.getTeamId(), saved.getId(), saved.getSourceRecordId(),
                            null, saved.getVersion());
                });
    }

    /** チーム削除の後処理: 共有行を論理削除する（物理削除しない。データ設計 §5.1）。 */
    @Transactional
    public int softDeleteTeam(Long teamId) {
        return queries.softDeleteByTeam(teamId, clock.instant().truncatedTo(ChronoUnit.MICROS));
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    private Revoked markRevoked(EffortTeamShareEntity row) {
        Revoked before = new Revoked(row.getId(), row.getSourceRecordId(), row.getTeamId(), row.getGrantMembershipId(),
                row.getShareVersion(), row.getSourceVersion());
        row.setState(EffortTeamShareState.REVOKED);
        teamShares.saveAndFlush(row);
        return before;
    }

    private static boolean isOlder(EffortShareCommand command, EffortTeamShareEntity row) {
        return command.shareVersion() < row.getShareVersion()
                || (command.shareVersion() == row.getShareVersion() && command.contentRevision() < row.getSourceVersion());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("直列化に失敗しました", e);
        }
    }

    private EffortModerateResponse fromJson(String json) {
        try {
            return objectMapper.readValue(json, EffortModerateResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("保存済みの応答を読み込めません", e);
        }
    }
}
