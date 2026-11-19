package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFolderScopeLookup;
import com.mannschaft.app.filesharing.service.SharedFolderScopeLookup.FolderScopeRef;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link FolderScopeAccessGuard} の tournament ドメイン側実装（F08.7.1 / 04 §3 / §5、CMP-261116-0726）。
 *
 * <p>filesharing ドメインが宣言した認可ポートを、連絡スペース（BULLETIN）の認可で満たす。
 * 依存方向は tournament → filesharing（依存性逆転）であり、循環依存を生まない。</p>
 *
 * <p><b>循環依存回避・ドメイン境界</b>: 本ガードは filesharing の <em>Service</em> /
 * <em>Repository</em> / <em>Entity</em> に直接依存せず、tx を持たない参照部品
 * {@link SharedFolderScopeLookup}（ID と enum のみを返す）経由でフォルダ／ファイルのスコープを解決する
 * （ArchUnit D-1 / D-5）。</p>
 *
 * <p>フォルダのスコープが大会／ディビジョン<b>以外</b>（TEAM / ORGANIZATION / PERSONAL）の場合は
 * 何もしない（既存 F05.5 の挙動を保つ）。大会／ディビジョンの場合のみ、フォルダの {@code scope_ref_id}
 * （= 大会 ID / ディビジョン ID）を解決し、連絡スペース認可を通す。</p>
 *
 * <h2>存在秘匿と tx 境界（CMP-261116-0726）</h2>
 * <p>閲覧権のない者には、不在フォルダ／ファイルと同一の不在応答（フォルダ ID 経路は
 * {@code FILE_SHARING_001}、ファイル ID 経路は {@code FILE_SHARING_002}）を返す。判定は tx を持たない
 * {@link TournamentContactSpaceAccessGate} に寄せ、本クラスはクラス単位の {@code @Transactional} を持たない
 * （ArchUnit D-3T: 入口にならない）。投稿系は {@code requirePostConcealedBy*} を Controller 前段から呼び、
 * tx 内の {@code checkFolderPostBy*} は多層防御として {@link TournamentContactAccessService#checkPost} を残す。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md §3 / §5</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TournamentFolderScopeAccessGuard implements FolderScopeAccessGuard {

    private final SharedFolderScopeLookup scopeLookup;
    private final TournamentContactAccessService accessService;
    private final TournamentContactSpaceAccessGate accessGate;

    @Override
    public void checkFolderViewByFolderId(Long folderId, Long userId) {
        checkView(resolveByFolderId(folderId), userId, FileSharingErrorCode.FOLDER_NOT_FOUND);
    }

    @Override
    public void checkFolderPostByFolderId(Long folderId, Long userId) {
        checkPost(resolveByFolderId(folderId), userId);
    }

    @Override
    public void checkFolderViewByFileId(Long fileId, Long userId) {
        checkView(resolveByFileId(fileId), userId, FileSharingErrorCode.FILE_NOT_FOUND);
    }

    @Override
    public void checkFolderPostByFileId(Long fileId, Long userId) {
        checkPost(resolveByFileId(fileId), userId);
    }

    @Override
    public void requirePostConcealedByFolderId(Long folderId, Long userId) {
        requirePostConcealed(resolveByFolderId(folderId), userId, FileSharingErrorCode.FOLDER_NOT_FOUND);
    }

    @Override
    public void requirePostConcealedByFileId(Long fileId, Long userId) {
        requirePostConcealed(resolveByFileId(fileId), userId, FileSharingErrorCode.FILE_NOT_FOUND);
    }

    // ========================================================================
    // 内部ヘルパー
    // ========================================================================

    private FolderScopeRef resolveByFolderId(Long folderId) {
        return scopeLookup.findByFolderId(folderId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND));
    }

    /** ファイル不在もフォルダ不在も FILE_NOT_FOUND（ファイル ID 経路は常に 002。存在を漏らさない）。 */
    private FolderScopeRef resolveByFileId(Long fileId) {
        return scopeLookup.findByFileId(fileId)
                .orElseThrow(() -> new BusinessException(FileSharingErrorCode.FILE_NOT_FOUND));
    }

    /**
     * フォルダが大会／ディビジョンスコープなら存在秘匿つきの閲覧認可を通す。それ以外は何もしない。
     */
    private void checkView(FolderScopeRef ref, Long userId, ErrorCode notFoundCode) {
        Optional<ContactSpaceScopeType> scopeType = toContactScopeType(ref.scopeType());
        if (scopeType.isEmpty()) {
            return; // TEAM / ORGANIZATION / PERSONAL は既存挙動のまま（本ガード対象外）
        }
        accessGate.requireView(scopeType.get(), ref.scopeRefId(), ContactSpaceKind.BULLETIN, userId, notFoundCode);
    }

    /**
     * tx 内の多層防御。大会／ディビジョンスコープならアップロード／編集認可を通す。それ以外は何もしない。
     */
    private void checkPost(FolderScopeRef ref, Long userId) {
        Optional<ContactSpaceScopeType> scopeType = toContactScopeType(ref.scopeType());
        if (scopeType.isEmpty()) {
            return;
        }
        accessService.checkPost(scopeType.get(), ref.scopeRefId(), userId);
    }

    /**
     * Controller 前段の存在秘匿つき投稿認可。閲覧権なしは不在応答、閲覧可で投稿権なしは 403 TOUR_031。
     * 大会／ディビジョン以外は何もしない。
     */
    private void requirePostConcealed(FolderScopeRef ref, Long userId, ErrorCode notFoundCode) {
        Optional<ContactSpaceScopeType> scopeType = toContactScopeType(ref.scopeType());
        if (scopeType.isEmpty()) {
            return;
        }
        accessGate.requirePost(scopeType.get(), ref.scopeRefId(), ContactSpaceKind.BULLETIN, userId, notFoundCode);
    }

    /**
     * filesharing の {@link FileScopeType} を連絡スペースの {@link ContactSpaceScopeType} に対応付ける。
     * 大会／ディビジョン以外（本ガード対象外）は空を返す。
     */
    private Optional<ContactSpaceScopeType> toContactScopeType(FileScopeType scopeType) {
        return switch (scopeType) {
            case TOURNAMENT -> Optional.of(ContactSpaceScopeType.TOURNAMENT);
            case TOURNAMENT_DIVISION -> Optional.of(ContactSpaceScopeType.TOURNAMENT_DIVISION);
            case TEAM, ORGANIZATION, PERSONAL -> Optional.empty();
        };
    }
}
