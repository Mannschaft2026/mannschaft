package com.mannschaft.app.effortrecord.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortSharePreviewRequest;
import com.mannschaft.app.effortrecord.dto.EffortSharePreviewResponse;
import com.mannschaft.app.effortrecord.dto.EffortShareRequest;
import com.mannschaft.app.effortrecord.dto.EffortShareRetryRequest;
import com.mannschaft.app.effortrecord.service.EffortShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F06.4.1 本人の原本をチームへ共有する API（API 契約 §4: preview / 共有 PUT / retry / 本人取消）。
 *
 * <p>Controller は Service を呼ぶだけ。teamId を受け取る共有系なので {@code @SelfScopedEndpoint} は付けない
 * （API 契約 §3 末尾）。認可は {@link EffortShareService} が原本の所有者（{@code EffortOwnerAccessGuard}）と
 * 共有先チームへの現在所属（{@code EffortTeamAccessGuard}）で行う。本人取消は所有者の照合だけで、現在の所属を要求しない。
 * Idempotency-Key は共有 PUT・retry・取消で必須（欠落は 400）。</p>
 */
@RestController
@RequestMapping(value = "/api/v1/effort-records/me/{id}/team-share", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "個人の頑張り記録（チーム共有）", description = "F06.4.1 本人の記録をチームへ共有する")
@RequiredArgsConstructor
public class EffortShareController {

    private static final Set<String> REVOKE_QUERY = Set.of("version", "share_version");

    private final EffortShareService shareService;

    @PostMapping("/preview")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の記録をチームへ共有する前の確認。所有者とチーム所属の認可だけで提供し機能フラグに依存させない")
    @Operation(summary = "チーム共有の preview（DB を変えない・10 分有効 token）")
    public ResponseEntity<ApiResponse<EffortSharePreviewResponse>> preview(
            @PathVariable UUID id, @Valid @RequestBody EffortSharePreviewRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(shareService.preview(userId, id, request)));
    }

    @PutMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人の記録をチームへ共有する。所有者とチーム所属の認可だけで提供し機能フラグに依存させない")
    @Operation(summary = "チーム共有（202 PENDING）")
    public ResponseEntity<ApiResponse<EffortOwnerRecordResponse>> share(
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EffortShareRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(shareService.share(userId, id, idempotencyKey, request)));
    }

    @PostMapping("/retry")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "共有の同期失敗から本人が明示的に再試行する。所有者とチーム所属の認可だけで提供し機能フラグに依存させない")
    @Operation(summary = "チーム共有の再試行（202。FAILED または 60 秒超の PENDING だけ）")
    public ResponseEntity<ApiResponse<EffortOwnerRecordResponse>> retry(
            @PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody EffortShareRetryRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of(shareService.retry(userId, id, idempotencyKey, request)));
    }

    @DeleteMapping
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "本人によるチーム共有の取消。脱退後でも所有者の照合だけで提供し機能フラグに依存させない")
    @Operation(summary = "チーム共有の本人取消（200 REVOKED）")
    public ResponseEntity<ApiResponse<EffortOwnerRecordResponse>> revoke(
            @PathVariable UUID id,
            @RequestParam(name = "version") Long version,
            @RequestParam(name = "share_version") Long shareVersion,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        rejectUnknownQuery(request, REVOKE_QUERY);
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(shareService.revoke(userId, id, version, shareVersion, idempotencyKey)));
    }

    /** 未知の query は 400（値は応答・ログへ出さない）。 */
    static void rejectUnknownQuery(HttpServletRequest request, Set<String> allowed) {
        for (String name : request.getParameterMap().keySet()) {
            if (!allowed.contains(name)) {
                throw new BusinessException(CommonErrorCode.COMMON_001,
                        List.of(new ErrorResponse.FieldError("query", "許可されていない query です")));
            }
        }
    }
}
