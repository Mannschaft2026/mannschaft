package com.mannschaft.app.effortrecord.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.effortrecord.dto.EffortModerateResponse;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecord;
import com.mannschaft.app.effortrecord.dto.EffortSharedRecordPage;
import com.mannschaft.app.effortrecord.dto.EffortTeamDefinitionCandidate;
import com.mannschaft.app.effortrecord.dto.EffortTeamRecordListCriteria;
import com.mannschaft.app.effortrecord.service.EffortTeamShareQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 チームに共有された頑張り記録の API（API 契約 §4: 一覧・定義候補・詳細・管理者非表示）。
 *
 * <p>Controller は Service を呼ぶだけ。認可は {@link EffortTeamShareQueryService} が {@code EffortTeamAccessGuard} で行う
 * （一覧・詳細・定義候補はチームへの現在所属、非表示は既存のテンプレート管理権限＝ADMIN・委任 DEPUTY_ADMIN）。
 * 親組織は判定に使わず、SYSTEM_ADMIN もチームの所属をバイパスしない。{@code /definitions} は {@code /{shareId}} より先に解決される
 * （Spring は具体的な path を優先する）。</p>
 */
@RestController
@RequestMapping(value = "/api/v1/teams/{teamId}/effort-records", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "個人の頑張り記録（チーム履歴）", description = "F06.4.1 チームに共有された頑張り記録")
@RequiredArgsConstructor
public class EffortTeamRecordController {

    /** 一覧が受け付ける query。これ以外（camelCase の別名等）は 400（API 契約 §1）。 */
    private static final Set<String> LIST_QUERY =
            Set.of("date_from", "date_to", "definition_uuid", "cursor", "limit", "user_id");
    private static final Set<String> NO_QUERY = Set.of();
    private static final Set<String> MODERATE_QUERY = Set.of("version");

    private final EffortTeamShareQueryService queryService;

    @GetMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームに共有された頑張り記録の履歴。チーム所属と F00 の判定だけで提供し機能フラグに依存させない")
    @Operation(summary = "チームに共有された記録の一覧（カーソルページング）")
    public ResponseEntity<CursorPagedResponse<EffortSharedRecord>> list(
            @PathVariable Long teamId,
            @RequestParam(name = "date_from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "definition_uuid", required = false) UUID definitionUuid,
            @RequestParam(name = "user_id", required = false) Long userId,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            HttpServletRequest request) {
        EffortShareController.rejectUnknownQuery(request, LIST_QUERY);
        Long viewerUserId = SecurityUtils.getCurrentUserId();
        EffortSharedRecordPage page = queryService.list(viewerUserId, teamId,
                new EffortTeamRecordListCriteria(dateFrom, dateTo, definitionUuid, userId, cursor, limit));
        return ResponseEntity.ok(CursorPagedResponse.of(page.items(),
                new CursorPagedResponse.CursorMeta(page.nextCursor(), page.hasNext(), page.limit())));
    }

    @GetMapping("/definitions")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チーム履歴の絞り込み候補。チーム所属と F00 の判定だけで提供し機能フラグに依存させない")
    @Operation(summary = "チーム履歴の定義候補（VISIBLE の履歴がある定義だけ）")
    public ResponseEntity<ApiResponse<List<EffortTeamDefinitionCandidate>>> definitions(
            @PathVariable Long teamId, HttpServletRequest request) {
        EffortShareController.rejectUnknownQuery(request, NO_QUERY);
        Long viewerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(queryService.definitions(viewerUserId, teamId)));
    }

    @GetMapping("/{shareId}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームに共有された頑張り記録の詳細。チーム所属と F00 の判定だけで提供し機能フラグに依存させない")
    @Operation(summary = "チームに共有された記録の詳細")
    public ResponseEntity<ApiResponse<EffortSharedRecord>> detail(
            @PathVariable Long teamId, @PathVariable String shareId, HttpServletRequest request) {
        EffortShareController.rejectUnknownQuery(request, NO_QUERY);
        Long viewerUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(queryService.detail(viewerUserId, teamId, shareId)));
    }

    @DeleteMapping("/{shareId}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チーム管理者による共有の非表示。既存のテンプレート管理権限だけで提供し機能フラグに依存させない")
    @Operation(summary = "チームに共有された記録の管理者非表示（200 MODERATED）")
    public ResponseEntity<ApiResponse<EffortModerateResponse>> moderate(
            @PathVariable Long teamId,
            @PathVariable String shareId,
            @RequestParam(name = "version") Long version,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        EffortShareController.rejectUnknownQuery(request, MODERATE_QUERY);
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(queryService.moderate(actorUserId, teamId, shareId, version,
                idempotencyKey)));
    }
}
