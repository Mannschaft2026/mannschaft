package com.mannschaft.app.supportaccess.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.dto.CreateSupportAccessGrantRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessGrantResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessLogView;
import com.mannschaft.app.supportaccess.dto.SupportAccessSessionResponse;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantQueryService;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessPersonalDataService;
import com.mannschaft.app.supportaccess.web.SupportAccessDenied;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F10.9 運営側のサポート閲覧 API（依頼・撤回・閲覧開始/終了・閲覧記録）。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig}）</b>: {@code /api/v1/system-admin/**} は SecurityConfig の
 * パス規則で ROLE_SYSTEM_ADMIN に限定済み。さらに Service が「自分の依頼だけ」を扱い、他の運営者の依頼は
 * 存在を明かさず 404（SUPPORT_ACCESS_001）にする。代理閲覧中（ヘッダー付き）は本 API 全体を
 * {@link SupportAccessDenied} で拒否する（自己許可の抜け道を塞ぐ）。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@SupportAccessDenied
@RestController
@RequestMapping("/api/v1/system-admin/support-access")
@Tag(name = "システム管理 - サポート閲覧", description = "F10.9 本人承認制のサポート閲覧（代理ログイン）の運営側 API")
@RequiredArgsConstructor
public class SystemAdminSupportAccessController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final SupportAccessGrantService grantService;
    private final SupportAccessGrantQueryService queryService;
    private final SupportAccessPersonalDataService logService;

    @PostMapping("/grants")
    @Operation(summary = "サポート閲覧の依頼", description = "対象・理由（10〜1000字）を指定して本人へ許可を依頼する。依頼は72時間で失効する。")
    public ResponseEntity<ApiResponse<SupportAccessGrantResponse>> createGrant(
            @Valid @RequestBody CreateSupportAccessGrantRequest request) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(queryService.toOperatorResponse(grantService.request(operatorId, request))));
    }

    @GetMapping("/grants")
    @Operation(summary = "自分の依頼一覧", description = "自分が出した依頼だけを新しい順に返す。cursor は直前ページ末尾の requestedAt。")
    public ResponseEntity<CursorPagedResponse<SupportAccessGrantResponse>> listGrants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int size = clamp(limit);
        List<SupportAccessGrantResponse> rows = queryService.listForOperator(
                SecurityUtils.getCurrentUserId(), parseStatus(status), targetUserId, parseCursor(cursor), size + 1);
        boolean hasNext = rows.size() > size;
        List<SupportAccessGrantResponse> page = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext ? page.get(page.size() - 1).requestedAt().toString() : null;
        return ResponseEntity.ok(CursorPagedResponse.of(page, new CursorPagedResponse.CursorMeta(next, hasNext, size)));
    }

    @GetMapping("/grants/{id}")
    @Operation(summary = "依頼の詳細")
    public ResponseEntity<ApiResponse<SupportAccessGrantResponse>> getGrant(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(queryService.getForOperator(SecurityUtils.getCurrentUserId(), id)));
    }

    @PostMapping("/grants/{id}/cancel")
    @Operation(summary = "依頼・許可の撤回", description = "PENDING/APPROVED を CANCELLED にする。次のリクエストから代理閲覧は拒否される。")
    public ResponseEntity<ApiResponse<SupportAccessGrantResponse>> cancel(@PathVariable UUID id) {
        Long operatorId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(queryService.toOperatorResponse(grantService.cancel(operatorId, id))));
    }

    @PostMapping("/grants/{id}/sessions")
    @Operation(summary = "代理閲覧の開始", description = "承認済みで期限内の許可で閲覧を開始する。開始記録を残し、本人へ通知する。以後 X-Support-Access-Grant-Id ヘッダーで本人の視点を閲覧のみできる。")
    public ResponseEntity<ApiResponse<SupportAccessSessionResponse>> startSession(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(grantService.startSession(SecurityUtils.getCurrentUserId(), id)));
    }

    @PostMapping("/grants/{id}/sessions/end")
    @Operation(summary = "代理閲覧の終了")
    public ResponseEntity<Void> endSession(@PathVariable UUID id) {
        grantService.endSession(SecurityUtils.getCurrentUserId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/grants/{id}/logs")
    @Operation(summary = "閲覧記録", description = "この依頼の代理閲覧の記録を新しい順に返す。cursor は直前ページ末尾の occurredAt。")
    public ResponseEntity<CursorPagedResponse<SupportAccessLogView>> listLogs(
            @PathVariable UUID id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        queryService.requireOwnedByOperator(SecurityUtils.getCurrentUserId(), id);
        int size = clamp(limit);
        List<SupportAccessLogView> rows = logService.listLogsForGrant(id, parseCursor(cursor), size + 1);
        boolean hasNext = rows.size() > size;
        List<SupportAccessLogView> page = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext ? page.get(page.size() - 1).occurredAt().toString() : null;
        return ResponseEntity.ok(CursorPagedResponse.of(page, new CursorPagedResponse.CursorMeta(next, hasNext, size)));
    }

    private static SupportAccessStatus parseStatus(String status) {
        return status == null || status.isBlank() ? null : EnumInputParser.parse(SupportAccessStatus.class, status, "status");
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    private static LocalDateTime parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(cursor);
        } catch (java.time.format.DateTimeParseException e) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }
}
