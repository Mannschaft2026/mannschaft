package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.dto.ContactSpaceResponse;
import com.mannschaft.app.tournament.dto.ContactSpaceVisibilityRequest;
import com.mannschaft.app.tournament.service.TournamentContactSpaceAccessGate;
import com.mannschaft.app.tournament.service.TournamentContactSpaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 大会・ディビジョン連絡スペースの公開トグル管理コントローラ（F08.7.1 §5.1）。
 *
 * <ul>
 *   <li>{@code PATCH /api/v1/tournaments/{tournamentId}/contact-spaces/{spaceId}/visibility}</li>
 *   <li>{@code PATCH /api/v1/tournaments/{tournamentId}/divisions/{divisionId}/contact-spaces/{spaceId}/visibility}</li>
 * </ul>
 *
 * <p>認可は主催組織 ADMIN / SYSTEM_ADMIN のみ（チーム代表は不可）。各メソッド先頭で
 * {@link TournamentContactSpaceAccessGate#requireVisibilityManage} を通し、パスの大会 ID とディビジョンの帰属を
 * 検証したうえで、存在を知り得ない者には不在と同一の 404 TOUR_029、知り得るが管理権のない者には 403 TOUR_032 を返す
 * （CMP-261116-0726・AC-11/AC-18）。サービス層の {@code checkVisibilityManage} は tx 内の多層防御として残る。</p>
 */
@RestController
@RequestMapping("/api/v1/tournaments/{tournamentId}")
@Tag(name = "大会連絡スペース", description = "F08.7.1 連絡スペース公開トグル")
@RequiredArgsConstructor
public class TournamentContactSpaceController {

    private final TournamentContactSpaceService contactSpaceService;
    private final TournamentContactSpaceAccessGate accessGate;

    @GetMapping("/contact-spaces")
    @Operation(summary = "大会全体の連絡スペース一覧（主催者向け）")
    public ResponseEntity<ApiResponse<List<ContactSpaceResponse>>> listTournamentSpaces(
            @PathVariable Long tournamentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGate.requireVisibilityManage(tournamentId, null, userId);
        List<ContactSpaceResponse> spaces = contactSpaceService.listSpaces(
                ContactSpaceScopeType.TOURNAMENT, tournamentId, userId);
        return ResponseEntity.ok(ApiResponse.of(spaces));
    }

    @PatchMapping("/contact-spaces/{spaceId}/visibility")
    @Operation(summary = "大会全体の連絡スペース公開設定変更")
    public ResponseEntity<ApiResponse<ContactSpaceResponse>> updateTournamentSpaceVisibility(
            @PathVariable Long tournamentId,
            @PathVariable UUID spaceId,
            @Valid @RequestBody ContactSpaceVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGate.requireVisibilityManage(tournamentId, null, userId);
        ContactSpaceResponse result = contactSpaceService.updateVisibility(
                ContactSpaceScopeType.TOURNAMENT, tournamentId, spaceId,
                request.getIsPublic(), userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/divisions/{divisionId}/contact-spaces")
    @Operation(summary = "ディビジョンの連絡スペース一覧（主催者向け）")
    public ResponseEntity<ApiResponse<List<ContactSpaceResponse>>> listDivisionSpaces(
            @PathVariable Long tournamentId,
            @PathVariable Long divisionId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGate.requireVisibilityManage(tournamentId, divisionId, userId);
        List<ContactSpaceResponse> spaces = contactSpaceService.listSpaces(
                ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, userId);
        return ResponseEntity.ok(ApiResponse.of(spaces));
    }

    @PatchMapping("/divisions/{divisionId}/contact-spaces/{spaceId}/visibility")
    @Operation(summary = "ディビジョンの連絡スペース公開設定変更")
    public ResponseEntity<ApiResponse<ContactSpaceResponse>> updateDivisionSpaceVisibility(
            @PathVariable Long tournamentId,
            @PathVariable Long divisionId,
            @PathVariable UUID spaceId,
            @Valid @RequestBody ContactSpaceVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessGate.requireVisibilityManage(tournamentId, divisionId, userId);
        ContactSpaceResponse result = contactSpaceService.updateVisibility(
                ContactSpaceScopeType.TOURNAMENT_DIVISION, divisionId, spaceId,
                request.getIsPublic(), userId);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
