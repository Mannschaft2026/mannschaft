package com.mannschaft.app.parking.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.parking.ParkingScopeType;
import com.mannschaft.app.parking.dto.ApplicationResponse;
import com.mannschaft.app.parking.dto.CreateApplicationRequest;
import com.mannschaft.app.parking.dto.RejectApplicationRequest;
import com.mannschaft.app.parking.service.ParkingAccessGuard;
import com.mannschaft.app.parking.service.ParkingApplicationAccessPolicy;
import com.mannschaft.app.parking.service.ParkingApplicationService;
import com.mannschaft.app.parking.service.ParkingSpaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * チーム区画申請コントローラー（6 EP）。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/parking/applications")
@Tag(name = "チーム区画申請", description = "F09.3 チーム区画申請管理")
@RequiredArgsConstructor
public class TeamParkingApplicationController {

    private final ParkingApplicationService applicationService;
    private final ParkingSpaceService spaceService;
    private final ParkingAccessGuard parkingAccessGuard;
    private final ParkingApplicationAccessPolicy accessPolicy;

    private static final String SCOPE_TYPE = ParkingScopeType.TEAM.name();

    @GetMapping
    @Operation(summary = "チーム申請一覧")
    public ResponseEntity<PagedResponse<ApplicationResponse>> list(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, teamId, userId);
        Page<ApplicationResponse> result = applicationService.list(SCOPE_TYPE, teamId, userId,
                accessPolicy.canViewAll(SCOPE_TYPE, teamId, userId), status,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "チーム区画申請")
    public ResponseEntity<ApiResponse<ApplicationResponse>> create(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateApplicationRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        // SUPPORTER は申請不可（AC-15）。代理入力時の申請者は代理対象者（AC-14）
        Long applicantId = accessPolicy.resolveApplicant(SCOPE_TYPE, teamId, actorId);
        parkingAccessGuard.requireOwnedVehicle(applicantId, request.getVehicleId());
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, teamId);
        ApplicationResponse result = applicationService.create(spaceIds, applicantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/approve")
    @Operation(summary = "チーム申請承認")
    public ResponseEntity<ApiResponse<ApplicationResponse>> approve(
            @PathVariable Long teamId, @PathVariable Long id) {
        accessPolicy.requireManage(SCOPE_TYPE, teamId, SecurityUtils.getCurrentUserId());
        ApplicationResponse result = applicationService.approve(SCOPE_TYPE, teamId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/reject")
    @Operation(summary = "チーム申請拒否")
    public ResponseEntity<ApiResponse<ApplicationResponse>> reject(
            @PathVariable Long teamId, @PathVariable Long id,
            @Valid @RequestBody RejectApplicationRequest request) {
        accessPolicy.requireManage(SCOPE_TYPE, teamId, SecurityUtils.getCurrentUserId());
        ApplicationResponse result = applicationService.reject(SCOPE_TYPE, teamId, id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "チーム申請取消")
    public ResponseEntity<Void> cancel(@PathVariable Long teamId, @PathVariable Long id) {
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, teamId, SecurityUtils.getCurrentUserId());
        applicationService.cancel(SCOPE_TYPE, teamId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lottery")
    @Operation(summary = "チーム抽選実行")
    public ResponseEntity<ApiResponse<List<ApplicationResponse>>> lottery(
            @PathVariable Long teamId,
            @RequestParam Long spaceId) {
        accessPolicy.requireManage(SCOPE_TYPE, teamId, SecurityUtils.getCurrentUserId());
        List<ApplicationResponse> result = applicationService.executeLottery(SCOPE_TYPE, teamId, spaceId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
