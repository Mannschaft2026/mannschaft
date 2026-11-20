package com.mannschaft.app.filesharing.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.filesharing.dto.CreateFileRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.dto.SharedFilePresignRequest;
import com.mannschaft.app.filesharing.dto.SharedFilePresignResponse;
import com.mannschaft.app.filesharing.dto.UpdateFileRequest;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 共有ファイルコントローラー。ファイルのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "ファイル共有 - ファイル", description = "F05.5 ファイルCRUD")
@RequiredArgsConstructor
public class SharedFileController {

    private final SharedFileService fileService;
    /** 大会スコープの存在秘匿つき投稿認可（Controller 前段・tx なし。CMP-261116-0726）。 */
    private final FolderScopeAccessGuard folderScopeAccessGuard;


    /**
     * フォルダ内のファイル一覧をページングで取得する。
     */
    @GetMapping
    @Operation(summary = "ファイル一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FileResponse>> listFiles(
            @RequestParam Long folderId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FileResponse> result = fileService.listFilesPaged(
                folderId, SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * ファイル詳細を取得する。
     */
    @GetMapping("/{fileId}")
    @Operation(summary = "ファイル詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<FileResponse>> getFile(
            @PathVariable Long fileId) {
        FileResponse response = fileService.getFile(fileId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ファイルを作成する。
     */
    @PostMapping
    @Operation(summary = "ファイル作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FileResponse>> createFile(
            @Valid @RequestBody CreateFileRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        folderScopeAccessGuard.requirePostConcealedByFolderId(request.getFolderId(), actorId);
        FileResponse response = fileService.createFile(actorId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ファイルを更新する。
     */
    @PatchMapping("/{fileId}")
    @Operation(summary = "ファイル更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FileResponse>> updateFile(
            @PathVariable Long fileId,
            @Valid @RequestBody UpdateFileRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        folderScopeAccessGuard.requirePostConcealedByFileId(fileId, actorId);
        // 移動先フォルダが指定された場合は、移動先の存在も同じ秘匿規則で判定する。
        if (request.getFolderId() != null) {
            folderScopeAccessGuard.requirePostConcealedByFolderId(request.getFolderId(), actorId);
        }
        FileResponse response = fileService.updateFile(fileId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ファイルを削除する。
     */
    @DeleteMapping("/{fileId}")
    @Operation(summary = "ファイル削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteFile(
            @PathVariable Long fileId) {
        Long actorId = SecurityUtils.getCurrentUserId();
        folderScopeAccessGuard.requirePostConcealedByFileId(fileId, actorId);
        fileService.deleteFile(fileId, actorId);
        return ResponseEntity.noContent().build();
    }

    /**
     * ファイルダウンロード用の Presigned GET URL を発行する。
     *
     * <p>FE {@code useFileSharingApi.getDownloadUrl(fileId)} が叩く EP。
     * これまで未実装で NoResourceFound → 500 になっていた箇所の根治。
     * 認可は {@link SharedFileService#presignDownload} 内でフォルダスコープ別に当てる
     * （PERSONAL=本人以外404 / TEAM・ORG=非メンバー403 / 大会=連絡スペース認可）。</p>
     *
     * @param fileId ファイル ID
     * @return {@code { data: { downloadUrl, expiresInSeconds } }}
     */
    @GetMapping("/{fileId}/download-url")
    @Operation(summary = "ファイルダウンロード用 presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<SharedFileDownloadUrlResponse>> getDownloadUrl(
            @PathVariable Long fileId) {
        Long actorId = SecurityUtils.getCurrentUserId();
        SharedFileDownloadUrlResponse response = fileService.presignDownload(fileId, actorId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * F13 Phase 5-a: ファイルアップロード用の Presigned URL を発行する。
     *
     * <p>クライアントはこのエンドポイントで {@code uploadUrl} と {@code fileKey} を取得し、
     * {@code uploadUrl} を使って R2 に直接 PUT する。完了後、{@code fileKey} を
     * {@code POST /api/v1/files} に渡してメタデータを登録する。</p>
     */
    @PostMapping("/presign-upload")
    @Operation(summary = "ファイルアップロード用 presigned URL 発行")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "発行成功")
    public ResponseEntity<ApiResponse<SharedFilePresignResponse>> presignUpload(
            @Valid @RequestBody SharedFilePresignRequest request) {
        Long actorId = SecurityUtils.getCurrentUserId();
        folderScopeAccessGuard.requirePostConcealedByFolderId(request.folderId(), actorId);
        SharedFilePresignResponse response =
                fileService.presignUpload(request.folderId(), actorId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
