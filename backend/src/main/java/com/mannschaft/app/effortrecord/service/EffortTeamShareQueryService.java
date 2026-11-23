package com.mannschaft.app.effortrecord.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.EffortErrorCode;
import com.mannschaft.app.effortrecord.dto.EffortModerateResponse;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecordPage;
import com.mannschaft.app.effortrecord.dto.EffortTeamDefinitionCandidate;
import com.mannschaft.app.effortrecord.dto.EffortTeamRecordListCriteria;
import com.mannschaft.app.effortrecord.entity.EffortDefinitionEntity;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.repository.EffortDefinitionRepository;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareQueryRepository;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareRepository;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 チーム API（一覧・詳細・定義候補・管理者非表示）の非 TX Facade（API 契約 §4・§6）。
 *
 * <p>チームの存在（削除済み・不在は 404）→ 現在の所属（非所属・脱退直後は 403）→ 対象の判定、の順に確かめる。
 * 親組織は判定に使わない。読み取りは team 行を SQL で引いたあと、表示の直前に F00（{@link EffortTeamShareVisibility}）で
 * 1 ページ単位に一括判定し、許可 snapshot（projection）だけを返す。1 回の走査は最大 {@value #MAX_SCAN} 行で、全件が
 * 非表示でも暗号化した走査継続位置で「空・hasNext=true」を返す。F00 の照会障害は 503 EFFORT_004（空一覧にしない）。</p>
 */
@Service
@RequiredArgsConstructor
public class EffortTeamShareQueryService {

    /** 1 回の走査の上限（API 契約 §6）。 */
    public static final int MAX_SCAN = 1000;
    private static final int MAX_LIMIT = 100;
    /** 1 回の team 行の読み出し件数の下限（F00 の一括判定をページ内で増やさないため）。 */
    private static final int MIN_CHUNK = 100;
    private static final String TEAM_PATH = "/api/v1/teams/";

    private final EffortTeamShareQueryRepository queries;
    private final EffortTeamShareRepository teamShares;
    private final EffortTeamShareVisibility visibility;
    private final EffortSharedRecordAssembler assembler;
    private final EffortCursorCodec cursors;
    private final EffortTeamAccessGuard teamAccess;
    private final EffortTeamShareTxService teamTx;
    private final EffortReceiptService receipts;
    private final EffortDefinitionRepository definitions;
    private final EffortShareProjector projector;
    private final EffortShareMetrics metrics;
    private final TeamService teamService;
    private final ActivityTemplateService activityTemplates;
    private final ObjectMapper objectMapper;

    /** チーム一覧（recordedOn DESC, createdAt DESC, id DESC。user_id・期間・definition_uuid で絞る）。 */
    public EffortSharedRecordPage list(Long viewerUserId, Long teamId, EffortTeamRecordListCriteria criteria) {
        requireReadableTeam(viewerUserId, teamId);
        return metrics.measure("list", () -> {
            if (criteria.limit() < 1 || criteria.limit() > MAX_LIMIT) {
                throw EffortShareService.invalid("limit", "limit は 1〜" + MAX_LIMIT + " で指定してください");
            }
            if ((criteria.dateFrom() == null) != (criteria.dateTo() == null)) {
                throw EffortShareService.invalid("date_from", "date_from と date_to は対で指定してください");
            }
            if (criteria.dateFrom() != null && criteria.dateFrom().isAfter(criteria.dateTo())) {
                throw EffortShareService.invalid("date_from", "date_from は date_to 以前で指定してください");
            }
            String scope = EffortCursorCodec.teamScope(teamId, viewerUserId);
            String filterHash = cursors.filterHash(criteria.dateFrom(), criteria.dateTo(), criteria.definitionUuid(),
                    criteria.userId());
            EffortTeamShareQueryRepository.After after = null;
            if (criteria.cursor() != null) {
                EffortCursorCodec.Position position = cursors.decode(scope, filterHash, criteria.cursor());
                after = new EffortTeamShareQueryRepository.After(position.recordedOn(), position.createdAt(), position.id());
            }
            int limit = criteria.limit();
            int chunk = Math.max(limit + 1, MIN_CHUNK);
            List<EffortTeamShareEntity> items = new ArrayList<>();
            Set<UUID> anonymous = new HashSet<>();
            EffortTeamShareEntity lastScanned = null;
            int scanned = 0;
            boolean moreRows = true;
            while (items.size() < limit && scanned < MAX_SCAN) {
                int size = Math.min(chunk, MAX_SCAN - scanned);
                List<EffortTeamShareEntity> rows = fetch(teamId, criteria, after, size);
                if (rows.isEmpty()) {
                    moreRows = false;
                    break;
                }
                EffortTeamShareVisibility.Result allowed = visibility.filter(rows, viewerUserId, teamId);
                anonymous.addAll(allowed.purgedOwner());
                int consumed = 0;
                for (EffortTeamShareEntity row : rows) {
                    consumed++;
                    scanned++;
                    lastScanned = row;
                    if (allowed.allowed().contains(row.getId())) {
                        items.add(row);
                        if (items.size() == limit) {
                            break;
                        }
                    }
                }
                after = position(lastScanned);
                if (consumed < rows.size()) {
                    moreRows = true;
                    break;
                }
                moreRows = rows.size() == size;
                if (!moreRows) {
                    break;
                }
            }
            String nextCursor = moreRows && lastScanned != null ? cursors.encode(scope, filterHash,
                    new EffortCursorCodec.Position(lastScanned.getRecordedOn(), lastScanned.getCreatedAt(),
                            lastScanned.getId())) : null;
            List<EffortSharedRecord> records = assembler.fromTeamRows(items, anonymous);
            return new EffortSharedRecordPage(records, nextCursor, nextCursor != null, limit);
        });
    }

    /** チーム詳細（別チームの share UUID・不在・F00 を通らない共有は 404 EFFORT_001）。 */
    public EffortSharedRecord detail(Long viewerUserId, Long teamId, String shareIdText) {
        requireReadableTeam(viewerUserId, teamId);
        return metrics.measure("detail", () -> {
            UUID shareId = parseShareId(shareIdText);
            EffortTeamShareEntity row = findRow(shareId, teamId);
            EffortTeamShareVisibility.Result allowed = visibility.filter(List.of(row), viewerUserId, teamId);
            if (!allowed.allowed().contains(row.getId())) {
                throw new BusinessException(EffortErrorCode.EFFORT_001);
            }
            return assembler.fromTeamRows(List.of(row), allowed.purgedOwner()).get(0);
        });
    }

    /**
     * 定義候補（当該チームで F00 を通る VISIBLE の履歴が 1 件以上ある定義だけ。有効・廃止は isRetired で区別）。
     * 非表示・同期中の履歴しかない定義、履歴の無い有効テンプレートは返さない。
     */
    public List<EffortTeamDefinitionCandidate> definitions(Long viewerUserId, Long teamId) {
        requireReadableTeam(viewerUserId, teamId);
        return metrics.measure("definitions", () -> {
            List<EffortTeamShareEntity> rows = fetchCandidates(teamId);
            EffortTeamShareVisibility.Result allowed = visibility.filter(rows, viewerUserId, teamId);
            Map<UUID, String> nameByDefinition = new LinkedHashMap<>();
            for (EffortTeamShareEntity row : rows) {
                if (allowed.allowed().contains(row.getId()) && !nameByDefinition.containsKey(row.getDefinitionId())) {
                    nameByDefinition.put(row.getDefinitionId(),
                            projector.readProjection(row.getProjectionJson()).templateName());
                }
            }
            if (nameByDefinition.isEmpty()) {
                return List.of();
            }
            Map<UUID, EffortDefinitionEntity> byId = new HashMap<>();
            definitions.findAllById(nameByDefinition.keySet()).forEach(d -> byId.put(d.getId(), d));
            Set<Long> activeTemplates = new HashSet<>();
            activityTemplates.listTemplates(viewerUserId, ActivityScopeType.TEAM, teamId)
                    .forEach(t -> activeTemplates.add(t.getId()));
            List<EffortTeamDefinitionCandidate> result = new ArrayList<>();
            nameByDefinition.forEach((definitionUuid, name) -> {
                EffortDefinitionEntity definition = byId.get(definitionUuid);
                boolean retired = definition == null || definition.getRetiredAt() != null
                        || !activeTemplates.contains(definition.getTeamTemplateId());
                result.add(new EffortTeamDefinitionCandidate(definitionUuid, name, retired));
            });
            return result;
        });
    }

    /** 管理者非表示（ADMIN・委任 DEPUTY_ADMIN。MEMBER・非所属は 403）。 */
    public EffortModerateResponse moderate(Long actorUserId, Long teamId, String shareIdText, Long version,
                                           String idempotencyKey) {
        UUID key = parseKey(idempotencyKey);
        requireExistingTeam(teamId);
        teamAccess.requireTemplateManager(actorUserId, teamId);
        return metrics.measure("moderate", () -> {
            if (version == null || version < 0) {
                throw EffortShareService.invalid("version", "version は 0 以上で指定してください");
            }
            UUID shareId = parseShareId(shareIdText);
            String path = "DELETE " + TEAM_PATH + teamId + "/effort-records/" + shareId;
            String hash = receipts.requestHash(path, "version=" + version);
            Optional<EffortReceiptService.StoredResponse> completed =
                    teamTx.findCompletedModeration(teamId, actorUserId, path, key, hash);
            if (completed.isPresent()) {
                return fromJson(completed.get().json());
            }
            return teamTx.moderate(actorUserId, teamId, shareId, version, key, path, hash);
        });
    }

    // ───────────────────────────── 内部 ─────────────────────────────

    /** チームが存在し（削除済みは不在と同じ 404）、閲覧者が現在所属している（403）。 */
    private void requireReadableTeam(Long viewerUserId, Long teamId) {
        requireExistingTeam(teamId);
        teamAccess.requireMember(viewerUserId, teamId);
    }

    private void requireExistingTeam(Long teamId) {
        if (teamId == null || teamService.findTeamSummary(teamId).isEmpty()) {
            throw new BusinessException(EffortErrorCode.EFFORT_001);
        }
    }

    private List<EffortTeamShareEntity> fetch(Long teamId, EffortTeamRecordListCriteria criteria,
                                              EffortTeamShareQueryRepository.After after, int size) {
        try {
            return queries.findVisiblePage(teamId, criteria.dateFrom(), criteria.dateTo(), criteria.definitionUuid(),
                    criteria.userId(), after, size);
        } catch (DataAccessException e) {
            throw new BusinessException(EffortErrorCode.EFFORT_004);
        }
    }

    private List<EffortTeamShareEntity> fetchCandidates(Long teamId) {
        try {
            return queries.findVisibleForCandidates(teamId, MAX_SCAN);
        } catch (DataAccessException e) {
            throw new BusinessException(EffortErrorCode.EFFORT_004);
        }
    }

    private EffortTeamShareEntity findRow(UUID shareId, Long teamId) {
        try {
            return teamShares.findByIdAndTeamId(shareId, teamId)
                    .orElseThrow(() -> new BusinessException(EffortErrorCode.EFFORT_001));
        } catch (DataAccessException e) {
            throw new BusinessException(EffortErrorCode.EFFORT_004);
        }
    }

    private static EffortTeamShareQueryRepository.After position(EffortTeamShareEntity row) {
        return new EffortTeamShareQueryRepository.After(row.getRecordedOn(), row.getCreatedAt(), row.getId());
    }

    private static UUID parseShareId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(EffortErrorCode.EFFORT_001);
        }
    }

    private static UUID parseKey(String raw) {
        if (raw == null || raw.length() != 36) {
            throw EffortShareService.invalid("Idempotency-Key", "Idempotency-Key は UUID で指定してください");
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw EffortShareService.invalid("Idempotency-Key", "Idempotency-Key は UUID で指定してください");
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
