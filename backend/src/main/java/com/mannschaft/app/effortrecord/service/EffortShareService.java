package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionSnapshot;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortSharePreviewRequest;
import com.mannschaft.app.effortrecord.dto.EffortSharePreviewResponse;
import com.mannschaft.app.effortrecord.dto.EffortShareRequest;
import com.mannschaft.app.effortrecord.dto.EffortShareRetryRequest;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortSourceType;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRepository;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F06.4.1 共有 API の非 TX Facade（API 契約 §4・§4.1・§8）。
 *
 * <p>他ドメインの照会（チーム所属・加入期間 ID・資格・チームの有効性・activity の現定義）は TX の外で行い、owner 側の
 * 状態更新は {@link EffortShareTxService} の owner TX に渡す（owner TX から他ドメインの Repository へ到達させない）。
 * preview は DB へ共有を作らず、共有 PUT までに原本の版・内容・共有先定義・加入期間 ID が変われば再 preview を求める。</p>
 *
 * <p>エラーの割当は殿裁定どおり: 定義 token の差替・不正は 400 COMMON_001（definitionToken）、preview 後の定義の変更・廃止は
 * 409 EFFORT_002、preview の失効・版の競合・key 不一致・別チームの有効共有は 409 COMMON_003、非所属は 403 COMMON_002、
 * 他人の原本は 404 EFFORT_001、共有要求を保存できないインフラ障害は 503 EFFORT_005（原本は残る）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffortShareService {

    private static final String BASE_PATH = "/api/v1/effort-records/me";

    private final EffortShareTxService tx;
    private final EffortTeamAccessGuard teamAccess;
    private final EffortOwnerAccessGuard ownerAccess;
    private final EffortDefinitionTokenCodec definitionTokens;
    private final EffortPreviewTokenCodec previewTokens;
    private final EffortTeamDefinitionResolver teamDefinitions;
    private final EffortDefinitionRepository definitions;
    private final EffortShareProjector projector;
    private final EffortSnapshotCodec codec;
    private final EffortSharedRecordAssembler assembler;
    private final EffortReceiptService receipts;
    private final EffortShareMetrics metrics;
    private final MembershipService membershipService;
    private final TeamService teamService;
    private final ObjectMapper objectMapper;
    @Qualifier("utcClock")
    private final Clock clock;

    // ───────────────────────────── preview ─────────────────────────────

    /**
     * 共有 preview（DB を変えない）。他人の原本は 404、非所属は 403、定義 token の不正・別チームの token は 400、
     * preview 後に変わった定義・廃止は 409 EFFORT_002、別チームの有効な共有・MODERATED は 409。
     */
    public EffortSharePreviewResponse preview(Long userId, UUID recordId, EffortSharePreviewRequest request) {
        // 他人の原本は所属の有無より先に 404（存在を区別しない）、次に共有先チームへの現在所属（403）
        ownerAccess.requireOwner(userId, recordId);
        teamAccess.requireMember(userId, request.teamId());
        return metrics.measure("preview", () -> {
            EffortRecordEntity record = tx.owned(userId, recordId);
            if (record.getVersion() != request.version().longValue()) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            EffortDefinitionTokenCodec.Claims definitionClaims =
                    definitionTokens.verifyForUser(request.definitionToken(), userId);
            if (!definitionClaims.teamId().equals(request.teamId())) {
                throw EffortDefinitionTokenCodec.invalid();
            }
            EffortTeamDefinitionTxService.Current current = currentDefinition(request.teamId(), definitionClaims.definitionUuid(),
                    definitionClaims.revision(), definitionClaims.definitionHash());
            Long grant = grantOf(userId, request.teamId());
            Optional<EffortTeamShareEntity> teamRow = tx.teamShare(request.teamId(), recordId);
            tx.checkTarget(record, request.teamId(), teamRow);
            UUID shareId = tx.reserveShareId(record, request.teamId(), teamRow);

            EffortShareProjector.Built built = projector.build(codec.parseSnapshot(record.getDefinitionSnapshot()),
                    codec.parseValues(record.getValuesJson()), current.snapshot(), request.mappings(),
                    request.selectedFieldKeys(), record.getRecordTimezone());
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            EffortSharedRecord projection = assembler.build(shareId, 0L, request.teamId(), userId, record.getRecordedOn(),
                    current.definitionUuid(), built.projection(), now, now, assembler.authors(List.of(userId)), false);
            EffortPreviewTokenCodec.Issued issued = previewTokens.issue(new EffortPreviewTokenCodec.Claims(null, userId,
                    recordId, record.getVersion(), record.getContentRevision(), record.getShareVersion(), request.teamId(),
                    grant, current.definitionUuid(), current.revision(), current.definitionHash(),
                    projector.commandMappingsJson(built.command()), projector.selectedJson(built.command()),
                    built.projectionHash(), shareId, 0L));
            return new EffortSharePreviewResponse(issued.token(), issued.expiresAt(), record.getVersion(),
                    record.getContentRevision(), current.revision(), projection);
        });
    }

    // ───────────────────────────── 共有 PUT・retry・取消 ─────────────────────────────

    /** 共有 PUT（202 PENDING）。 */
    public EffortOwnerRecordResponse share(Long userId, UUID recordId, String idempotencyKey, EffortShareRequest request) {
        ownerAccess.requireOwner(userId, recordId);
        return metrics.measure("share", () -> {
            UUID key = parseKey(idempotencyKey);
            String path = "PUT " + BASE_PATH + "/" + recordId + "/team-share";
            String hash = receipts.requestHash(path, toJson(request));
            Optional<EffortReceiptService.StoredResponse> completed = tx.findCompleted(userId, path, key, hash);
            if (completed.isPresent()) {
                return fromJson(completed.get().json());
            }
            EffortPreviewTokenCodec.Claims claims = previewTokens.verify(request.previewToken(), userId, recordId);
            teamAccess.requireMember(userId, claims.teamId());
            if (!claims.grantMembershipId().equals(grantOf(userId, claims.teamId()))) {
                // 脱退→再加入で加入期間が変わった（旧 preview は使えない）
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            EffortTeamDefinitionTxService.Current current = currentDefinition(claims.teamId(), claims.definitionUuid(),
                    claims.definitionRevision(), claims.definitionHash());
            EffortDefinitionSnapshot target = current.snapshot();
            try {
                return tx.share(userId, recordId, key, path, hash, request.version(), claims, target);
            } catch (DataAccessException e) {
                throw saveFailed(e);
            }
        });
    }

    /**
     * 同一 desired command の明示 retry（202）。チームが削除済み・共有資格の加入期間が終わっている（再加入済みを含む）なら
     * 409、本人が非所属なら 403。
     */
    public EffortOwnerRecordResponse retry(Long userId, UUID recordId, String idempotencyKey, EffortShareRetryRequest request) {
        ownerAccess.requireOwner(userId, recordId);
        return metrics.measure("retry", () -> {
            UUID key = parseKey(idempotencyKey);
            String path = "POST " + BASE_PATH + "/" + recordId + "/team-share/retry";
            String hash = receipts.requestHash(path, toJson(request));
            Optional<EffortReceiptService.StoredResponse> completed = tx.findCompleted(userId, path, key, hash);
            if (completed.isPresent()) {
                return fromJson(completed.get().json());
            }
            EffortRecordEntity record = tx.owned(userId, recordId);
            if (record.getShareId() == null || record.getShareTeamId() == null) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            if (teamService.findTeamSummary(record.getShareTeamId()).isEmpty()) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            teamAccess.requireMember(userId, record.getShareTeamId());
            if (!isCurrentGrant(record)) {
                throw new BusinessException(CommonErrorCode.COMMON_003);
            }
            try {
                return tx.retry(userId, recordId, key, path, hash, request.version(), request.shareVersion());
            } catch (DataAccessException e) {
                throw saveFailed(e);
            }
        });
    }

    /** 本人取消（200 REVOKED）。owner 照合だけで、現在の所属を要求しない。 */
    public EffortOwnerRecordResponse revoke(Long userId, UUID recordId, Long version, Long shareVersion,
                                           String idempotencyKey) {
        ownerAccess.requireOwner(userId, recordId);
        return metrics.measure("revoke", () -> {
            UUID key = parseKey(idempotencyKey);
            if (version == null || version < 0 || shareVersion == null || shareVersion < 0) {
                throw invalid("version", "version と share_version は 0 以上で指定してください");
            }
            String path = "DELETE " + BASE_PATH + "/" + recordId + "/team-share";
            String hash = receipts.requestHash(path, "version=" + version + "&share_version=" + shareVersion);
            Optional<EffortReceiptService.StoredResponse> completed = tx.findCompleted(userId, path, key, hash);
            if (completed.isPresent()) {
                return fromJson(completed.get().json());
            }
            try {
                return tx.revoke(userId, recordId, key, path, hash, version, shareVersion);
            } catch (DataAccessException e) {
                throw saveFailed(e);
            }
        });
    }

    // ───────────────────────────── owner GET の実効状態 ─────────────────────────────

    /**
     * 共有に束縛された加入期間 ID が今も有効な直接所属か（PRIMARY の独立 TX。データ設計 §8）。
     * 加入期間 ID が無い共有は false。
     */
    public boolean isCurrentGrant(EffortRecordEntity record) {
        if (record.getGrantMembershipId() == null || record.getShareTeamId() == null) {
            return false;
        }
        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(record.getShareTeamId(), record.getOwnerUserId(),
                        record.getGrantMembershipId())));
        return !result.isEmpty() && result.get(0).isCurrentEligible();
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /**
     * 共有先 TEAM 定義の現行 revision を activity の現定義と同期して確かめる。token の定義がそのチームの TEAM 定義でなければ
     * 400（definitionToken）、テンプレートの廃止・preview 後の revision/hash の変化は 409 EFFORT_002。
     */
    private EffortTeamDefinitionTxService.Current currentDefinition(Long teamId, UUID definitionUuid, int revision,
                                                                    String definitionHash) {
        EffortDefinitionEntity definition = definitions.findById(definitionUuid)
                .filter(d -> d.getSourceType() == EffortSourceType.TEAM && teamId.equals(d.getTeamId()))
                .orElseThrow(EffortDefinitionTokenCodec::invalid);
        EffortTeamDefinitionTxService.Current current = teamDefinitions.sync(teamId, definition.getTeamTemplateId());
        if (current.retired() || current.revision() != revision || !current.definitionHash().equals(definitionHash)) {
            throw new BusinessException(EffortErrorCode.EFFORT_002);
        }
        return current;
    }

    /** 現在の加入期間 ID（非所属は 403）。 */
    private Long grantOf(Long userId, Long teamId) {
        return membershipService.findActiveEffortGrantMembershipId(userId, teamId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_002));
    }

    private BusinessException saveFailed(DataAccessException e) {
        // 値・SQL を含めず例外種別だけを記録する（AC-61）。原本は別 TX で保存済みのまま残る。
        log.error("頑張り記録の共有要求を保存できません: cause={}", e.getClass().getSimpleName());
        return new BusinessException(EffortErrorCode.EFFORT_005);
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.length() != 36) {
            throw invalid("Idempotency-Key", "Idempotency-Key は UUID で指定してください");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw invalid("Idempotency-Key", "Idempotency-Key は UUID で指定してください");
        }
    }

    static BusinessException invalid(String field, String message) {
        return new BusinessException(CommonErrorCode.COMMON_001, List.of(new ErrorResponse.FieldError(field, message)));
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
