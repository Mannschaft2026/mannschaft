package com.mannschaft.app.supportaccess.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.dto.MySupportAccessGrantResponse;
import com.mannschaft.app.supportaccess.dto.SupportAccessApproveRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessDenyRequest;
import com.mannschaft.app.supportaccess.dto.SupportAccessLogView;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantQueryService;
import com.mannschaft.app.supportaccess.service.SupportAccessGrantService;
import com.mannschaft.app.supportaccess.service.SupportAccessPersonalDataService;
import com.mannschaft.app.supportaccess.web.SupportAccessDenied;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * F10.9 本人（未成年・後見下は保護者を含む）側のサポート閲覧 API（許可・拒否・取消・閲覧記録）。
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 認証主体のユーザー ID だけを使い、
 * {@code SupportAccessGrantService} が「grant の対象本人（または保護者の承認が要る grant の保護者）」
 * であることを毎回検証する。それ以外の利用者には存在を明かさず 404（SUPPORT_ACCESS_001）。
 * 許可には再認証（パスワードかパスキー）が必須で、メールのワンクリックでは許可できない。
 * 代理閲覧中（ヘッダー付き）は本 API 全体を {@link SupportAccessDenied} で拒否する
 * （運営者が本人になりすまして自己許可する抜け道を塞ぐ）。</p>
 */
@AuthorizedInService
@SupportAccessDenied
@RestController
@RequestMapping("/api/v1/me/support-access")
@Tag(name = "マイページ - サポート閲覧", description = "F10.9 本人承認制のサポート閲覧（代理ログイン）の本人側 API")
@RequiredArgsConstructor
public class MySupportAccessController {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final SupportAccessGrantService grantService;
    private final SupportAccessGrantQueryService queryService;
    private final SupportAccessPersonalDataService logService;

    @GetMapping("/grants")
    @Operation(summary = "自分宛のサポート閲覧の依頼・許可の一覧", description = "運営者は表示名のみ。依頼理由はプレーンテキストとして表示すること。")
    public ResponseEntity<CursorPagedResponse<MySupportAccessGrantResponse>> listGrants(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int size = clamp(limit);
        SupportAccessStatus parsed = status == null || status.isBlank()
                ? null : EnumInputParser.parse(SupportAccessStatus.class, status, "status");
        List<MySupportAccessGrantResponse> rows = queryService.listForUser(
                SecurityUtils.getCurrentUserId(), parsed, parseCursor(cursor), size + 1);
        boolean hasNext = rows.size() > size;
        List<MySupportAccessGrantResponse> page = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext ? page.get(page.size() - 1).requestedAt().toString() : null;
        return ResponseEntity.ok(CursorPagedResponse.of(page, new CursorPagedResponse.CursorMeta(next, hasNext, size)));
    }

    @PostMapping("/grants/{id}/approve")
    @Operation(summary = "サポート閲覧の許可", description = "再認証（currentPassword か passkeyAssertionJson）が必須。期限は許可から24時間で固定・延長不可。未成年・後見下は本人と保護者の両方の許可で有効になる。")
    public ResponseEntity<ApiResponse<MySupportAccessGrantResponse>> approve(
            @PathVariable UUID id, @RequestBody(required = false) SupportAccessApproveRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        grantService.approve(userId, id, request);
        return ResponseEntity.ok(ApiResponse.of(queryService.getForUser(userId, id)));
    }

    @PostMapping("/grants/{id}/deny")
    @Operation(summary = "サポート閲覧の拒否")
    public ResponseEntity<ApiResponse<MySupportAccessGrantResponse>> deny(
            @PathVariable UUID id, @Valid @RequestBody(required = false) SupportAccessDenyRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        grantService.deny(userId, id, request == null ? null : request.comment());
        return ResponseEntity.ok(ApiResponse.of(queryService.getForUser(userId, id)));
    }

    @PostMapping("/grants/{id}/revoke")
    @Operation(summary = "サポート閲覧の許可の取消", description = "いつでも取り消せる。取消の次のリクエストから代理閲覧は拒否される。")
    public ResponseEntity<ApiResponse<MySupportAccessGrantResponse>> revoke(@PathVariable UUID id) {
        Long userId = SecurityUtils.getCurrentUserId();
        grantService.revoke(userId, id);
        return ResponseEntity.ok(ApiResponse.of(queryService.getForUser(userId, id)));
    }

    @GetMapping("/logs")
    @Operation(summary = "自分の画面が閲覧された記録", description = "日時・運営者の表示名・画面（パス）・スコープを新しい順に返す。メールは含まない。")
    public ResponseEntity<CursorPagedResponse<SupportAccessLogView>> listLogs(
            @RequestParam(required = false) UUID grantId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        int size = clamp(limit);
        List<SupportAccessLogView> rows = logService.listLogsForTarget(
                SecurityUtils.getCurrentUserId(), grantId, parseCursor(cursor), size + 1);
        boolean hasNext = rows.size() > size;
        List<SupportAccessLogView> page = hasNext ? rows.subList(0, size) : rows;
        String next = hasNext ? page.get(page.size() - 1).occurredAt().toString() : null;
        return ResponseEntity.ok(CursorPagedResponse.of(page, new CursorPagedResponse.CursorMeta(next, hasNext, size)));
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
