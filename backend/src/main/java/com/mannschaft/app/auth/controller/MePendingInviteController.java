package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.PendingInviteAcceptResponse;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.dto.PendingInviteReissueResponse;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 招待付き新規登録の保留承認 API（CMP-261107-1833 設計 §2）。
 *
 * <p>招待リンクから新規登録した利用者は登録時点では参加せず、ログイン後に本人がこの API で承認・辞退する。
 * 利用者は {@link SecurityUtils#getCurrentUserId()}（SecurityContext）だけから取り、パス・クエリ・本文で
 * 利用者や行の ID を受け取らない（他人の保留に触れられない）。応答は招待のハッシュを含まない。
 * 保護者同意待ちのトークンは {@code ParentalConsentGateFilter} が 403 AUTH_070 で遮断する（許可リストに載せない）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/pending-invite")
@Tag(name = "招待付き新規登録の保留承認")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class MePendingInviteController {

    private final PendingInviteService pendingInviteService;

    @GetMapping
    @Operation(summary = "自分の参加待ち招待を取得",
            description = "招待付きで新規登録した参加待ちの招待を返す。無ければ data は null")
    public ApiResponse<PendingInviteResponse> get() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(pendingInviteService.findMine(userId));
    }

    @PostMapping("/accept")
    @Operation(summary = "参加待ちの招待を承認して参加",
            description = "承認後の状態（JOINED/EXPIRED/UNAVAILABLE）を返す。処理中は 409 AUTH_086、辞退済み等は 409 AUTH_085")
    public ApiResponse<PendingInviteAcceptResponse> accept() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(pendingInviteService.accept(userId));
    }

    @PostMapping("/decline")
    @Operation(summary = "参加待ちの招待を辞退")
    public ApiResponse<Void> decline() {
        Long userId = SecurityUtils.getCurrentUserId();
        pendingInviteService.decline(userId);
        return ApiResponse.of(null);
    }

    @PostMapping("/reissue-request")
    @Operation(summary = "失効した招待の再発行を要請",
            description = "非公開の招待先の招待権限者へ通知する。7 日以内の再要請は通知せず alreadyRequested=true。公開の招待先は 409 AUTH_085")
    public ApiResponse<PendingInviteReissueResponse> requestReissue() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(pendingInviteService.requestReissue(userId));
    }

    @PostMapping("/dismiss")
    @Operation(summary = "失効した招待の案内を閉じる")
    public ApiResponse<Void> dismiss() {
        Long userId = SecurityUtils.getCurrentUserId();
        pendingInviteService.dismiss(userId);
        return ApiResponse.of(null);
    }
}
