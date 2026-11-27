package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftDetailResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSaveRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftSummaryResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationDraftUpdateRequest;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * CMP-261126-1558 F04.9 組織確認通知の下書きコントローラー。
 *
 * <p>作成フォームの入力途中を DB に複数件（スコープ×作成者ごとに上限10件）保存する。下書きは作成者本人だけが
 * 読み書きでき、他人・別スコープ・存在しない ID は同じ 404 で返す。権限は確認通知の送信と同じ
 * 「ADMIN、または SEND_NOTIFICATION を持つ DEPUTY_ADMIN」。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/confirmable-notifications/drafts")
@Tag(name = "組織確認通知の下書き", description = "F04.9 組織確認通知の下書き（DB 保存・複数件）")
@RequiredArgsConstructor
public class OrgConfirmableNotificationDraftController {

    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private final ConfirmableNotificationDraftService draftService;
    private final AccessControlService accessControlService;

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "確認通知の作成フォームの入力途中を失わないための基盤機能として常時提供するため")
    @GetMapping
    @Operation(summary = "下書き一覧（updated_at 降順・本文は冒頭100文字）")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationDraftSummaryResponse>>> list(
            @PathVariable Long orgId) {
        Long userId = authorize(orgId);
        return ResponseEntity.ok(ApiResponse.of(draftService.list(ScopeType.ORGANIZATION, orgId, userId)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "確認通知の作成フォームの入力途中を失わないための基盤機能として常時提供するため")
    @PostMapping
    @Operation(summary = "下書き作成（スコープ×作成者ごとに10件まで）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationDraftDetailResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody ConfirmableNotificationDraftSaveRequest request) {
        Long userId = authorize(orgId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(draftService.create(ScopeType.ORGANIZATION, orgId, userId, request)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "確認通知の作成フォームの入力途中を失わないための基盤機能として常時提供するため")
    @GetMapping("/{draftId}")
    @Operation(summary = "下書き取得")
    public ResponseEntity<ApiResponse<ConfirmableNotificationDraftDetailResponse>> get(
            @PathVariable Long orgId, @PathVariable UUID draftId) {
        Long userId = authorize(orgId);
        return ResponseEntity.ok(ApiResponse.of(draftService.get(ScopeType.ORGANIZATION, orgId, userId, draftId)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "確認通知の作成フォームの入力途中を失わないための基盤機能として常時提供するため")
    @PutMapping("/{draftId}")
    @Operation(summary = "下書き更新（baseVersion 必須。不一致は 409）")
    public ResponseEntity<ApiResponse<ConfirmableNotificationDraftDetailResponse>> update(
            @PathVariable Long orgId, @PathVariable UUID draftId,
            @Valid @RequestBody ConfirmableNotificationDraftUpdateRequest request) {
        Long userId = authorize(orgId);
        return ResponseEntity.ok(ApiResponse.of(draftService.update(ScopeType.ORGANIZATION, orgId, userId, draftId, request)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "確認通知の作成フォームの入力途中を失わないための基盤機能として常時提供するため")
    @DeleteMapping("/{draftId}")
    @Operation(summary = "下書き削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> delete(@PathVariable Long orgId, @PathVariable UUID draftId) {
        Long userId = authorize(orgId);
        draftService.delete(ScopeType.ORGANIZATION, orgId, userId, draftId);
        return ResponseEntity.noContent().build();
    }

    /** 確認通知の送信と同じ権限で認可し、本人の userId を返す。 */
    private Long authorize(Long orgId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrHasPermissionInScope(
                currentUserId, orgId, ScopeType.ORGANIZATION.name(), SEND_NOTIFICATION);
        return currentUserId;
    }
}
