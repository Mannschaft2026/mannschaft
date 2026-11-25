package com.mannschaft.app.role.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.role.dto.InviteReissueRequestResponse;
import com.mannschaft.app.role.dto.InviteReissueResultResponse;
import com.mannschaft.app.role.service.InviteReissueRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 再招待依頼の受け手側 API（CMP-261107-1833 PR3 設計 i）。招待権限者が、失効した招待から登録した利用者の
 * 再招待依頼を一覧し、再招待または見送りを行う。
 *
 * <p>TEAM / ORGANIZATION それぞれに専用メソッドを持つ（金型: {@code JoinRequestController}）。いずれも
 * {@link InviteReissueRequestService} へ scopeType 文字列を渡して委譲するだけで、認可（招待権限者のみ・
 * それ以外は 403 COMMON_002）はサービス層で完結する（{@link AuthorizedInService}）。応答は依頼者のメールアドレス・
 * 招待のハッシュを含まない。</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "再招待依頼", description = "失効した招待から登録した利用者の再招待依頼を、招待権限者が一覧・再招待・見送りする API")
public class InviteReissueRequestController {

    private static final String TEAM = "TEAM";
    private static final String ORGANIZATION = "ORGANIZATION";

    private final InviteReissueRequestService service;

    // ------------------------------------------------------------------
    // TEAM
    // ------------------------------------------------------------------

    @AuthorizedInService
    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "招待の再発行依頼への対応は中核の所属管理機能として常時提供する")
    @GetMapping("/api/v1/teams/{slug}/invite-reissue-requests")
    @Operation(summary = "チームの再招待依頼一覧（招待権限者）",
            description = "古い順。size は最大 50。閉鎖中のチームは 0 件")
    public ApiResponse<Page<InviteReissueRequestResponse>> listForTeam(
            @PathVariable("slug") String slug,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.of(service.list(TEAM, slug, SecurityUtils.getCurrentUserId(), page, size));
    }

    @AuthorizedInService
    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "招待の再発行依頼への対応は中核の所属管理機能として常時提供する")
    @PostMapping("/api/v1/teams/{slug}/invite-reissue-requests/{id}/reissue")
    @Operation(summary = "チームの再招待依頼に再招待する（招待権限者）",
            description = "依頼者宛ての 1 回限り・7 日の招待を発行する。在籍済みなら ALREADY_MEMBER")
    public ApiResponse<InviteReissueResultResponse> reissueForTeam(
            @PathVariable("slug") String slug, @PathVariable("id") UUID id) {
        return ApiResponse.of(service.reissue(TEAM, slug, id, SecurityUtils.getCurrentUserId()));
    }

    @AuthorizedInService
    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "招待の再発行依頼への対応は中核の所属管理機能として常時提供する")
    @PostMapping("/api/v1/teams/{slug}/invite-reissue-requests/{id}/decline")
    @Operation(summary = "チームの再招待依頼を見送る（招待権限者）")
    public ApiResponse<InviteReissueResultResponse> declineForTeam(
            @PathVariable("slug") String slug, @PathVariable("id") UUID id) {
        return ApiResponse.of(service.decline(TEAM, slug, id, SecurityUtils.getCurrentUserId()));
    }

    // ------------------------------------------------------------------
    // ORGANIZATION
    // ------------------------------------------------------------------

    @AuthorizedInService
    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "招待の再発行依頼への対応は中核の所属管理機能として常時提供する")
    @GetMapping("/api/v1/organizations/{slug}/invite-reissue-requests")
    @Operation(summary = "組織の再招待依頼一覧（招待権限者）",
            description = "古い順。size は最大 50。閉鎖中の組織は 0 件")
    public ApiResponse<Page<InviteReissueRequestResponse>> listForOrganization(
            @PathVariable("slug") String slug,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.of(service.list(ORGANIZATION, slug, SecurityUtils.getCurrentUserId(), page, size));
    }

    @AuthorizedInService
    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "招待の再発行依頼への対応は中核の所属管理機能として常時提供する")
    @PostMapping("/api/v1/organizations/{slug}/invite-reissue-requests/{id}/reissue")
    @Operation(summary = "組織の再招待依頼に再招待する（招待権限者）",
            description = "依頼者宛ての 1 回限り・7 日の招待を発行する。在籍済みなら ALREADY_MEMBER")
    public ApiResponse<InviteReissueResultResponse> reissueForOrganization(
            @PathVariable("slug") String slug, @PathVariable("id") UUID id) {
        return ApiResponse.of(service.reissue(ORGANIZATION, slug, id, SecurityUtils.getCurrentUserId()));
    }

    @AuthorizedInService
    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "招待の再発行依頼への対応は中核の所属管理機能として常時提供する")
    @PostMapping("/api/v1/organizations/{slug}/invite-reissue-requests/{id}/decline")
    @Operation(summary = "組織の再招待依頼を見送る（招待権限者）")
    public ApiResponse<InviteReissueResultResponse> declineForOrganization(
            @PathVariable("slug") String slug, @PathVariable("id") UUID id) {
        return ApiResponse.of(service.decline(ORGANIZATION, slug, id, SecurityUtils.getCurrentUserId()));
    }
}
