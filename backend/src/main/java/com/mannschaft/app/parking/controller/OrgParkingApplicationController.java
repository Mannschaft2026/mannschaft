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
import com.mannschaft.app.proxy.ProxyInputContext;
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
 * 組織区画申請コントローラー（6 EP）。
 */
@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/parking/applications")
@Tag(name = "組織区画申請", description = "F09.3 組織区画申請管理")
@RequiredArgsConstructor
public class OrgParkingApplicationController {

    private final ParkingApplicationService applicationService;
    private final ParkingSpaceService spaceService;
    private final ParkingAccessGuard parkingAccessGuard;
    private final ProxyInputContext proxyInputContext;
    private final ParkingApplicationAccessPolicy accessPolicy;

    private static final String SCOPE_TYPE = ParkingScopeType.ORGANIZATION.name();

    @GetMapping
    @Operation(summary = "組織申請一覧")
    public ResponseEntity<PagedResponse<ApplicationResponse>> list(
            @PathVariable Long organizationId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, organizationId, userId);
        Page<ApplicationResponse> result = applicationService.list(SCOPE_TYPE, organizationId, userId,
                accessPolicy.canViewAll(SCOPE_TYPE, organizationId, userId), status,
                PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    @PostMapping
    @Operation(summary = "組織区画申請")
    public ResponseEntity<ApiResponse<ApplicationResponse>> create(
            @PathVariable Long organizationId,
            @Valid @RequestBody CreateApplicationRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        // SUPPORTER は申請不可（AC-15）。代理入力時の申請者は代理対象者（AC-14）
        accessPolicy.requireRegularMember(SCOPE_TYPE, organizationId, actorId);
        Long applicantId = proxyInputContext.isProxy() ? proxyInputContext.getSubjectUserId() : actorId;
        parkingAccessGuard.requireOwnedVehicle(applicantId, request.getVehicleId());
        List<Long> spaceIds = spaceService.getSpaceIds(SCOPE_TYPE, organizationId);
        ApplicationResponse result = applicationService.create(spaceIds, applicantId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/approve")
    @Operation(summary = "組織申請承認")
    public ResponseEntity<ApiResponse<ApplicationResponse>> approve(
            @PathVariable Long organizationId, @PathVariable Long id) {
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, organizationId, SecurityUtils.getCurrentUserId());
        ApplicationResponse result = applicationService.approve(SCOPE_TYPE, organizationId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @PatchMapping("/{id}/reject")
    @Operation(summary = "組織申請拒否")
    public ResponseEntity<ApiResponse<ApplicationResponse>> reject(
            @PathVariable Long organizationId, @PathVariable Long id,
            @Valid @RequestBody RejectApplicationRequest request) {
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, organizationId, SecurityUtils.getCurrentUserId());
        ApplicationResponse result = applicationService.reject(SCOPE_TYPE, organizationId, id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "組織申請取消")
    public ResponseEntity<Void> cancel(@PathVariable Long organizationId, @PathVariable Long id) {
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, organizationId, SecurityUtils.getCurrentUserId());
        applicationService.cancel(SCOPE_TYPE, organizationId, id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/lottery")
    @Operation(summary = "組織抽選実行")
    public ResponseEntity<ApiResponse<List<ApplicationResponse>>> lottery(
            @PathVariable Long organizationId,
            @RequestParam Long spaceId) {
        parkingAccessGuard.requireScopeMember(SCOPE_TYPE, organizationId, SecurityUtils.getCurrentUserId());
        List<ApplicationResponse> result = applicationService.executeLottery(SCOPE_TYPE, organizationId, spaceId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
