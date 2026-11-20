package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * フォルダ／ファイルのスコープ（スコープ種別とスコープ参照 ID）だけを解決する、tx を持たない参照部品
 * （CMP-261116-0726）。
 *
 * <p>他ドメイン（tournament）の認可ポート実装が、filesharing の Repository・Entity に直接触れずに
 * 「このフォルダ／ファイルはどのスコープに属するか」を知るための入口である（ArchUnit D-1 / D-5）。
 * 戻り値は ID と enum のみで Entity を公開しない。{@code @Transactional} を持たないため、
 * 他ドメインの tx 入口から呼ばれても越境違反（D-3T）の新規キーを生まない。</p>
 *
 * <p>不在（削除済みを含む）は空の {@link Optional}。ファイル ID 経路は、ファイルが存在しても所属フォルダが
 * 不在なら空を返す（呼び出し側はファイル不在として扱う）。</p>
 */
@Component
@RequiredArgsConstructor
public class SharedFolderScopeLookup {

    private final SharedFolderRepository folderRepository;
    private final SharedFileRepository fileRepository;

    /**
     * フォルダのスコープ参照。
     *
     * @param scopeType  スコープ種別
     * @param scopeRefId 大会／ディビジョンスコープでは大会 ID／ディビジョン ID。それ以外は null の場合がある
     */
    public record FolderScopeRef(FileScopeType scopeType, Long scopeRefId) {
    }

    /**
     * フォルダ ID からスコープを解決する。
     *
     * @param folderId フォルダ ID
     * @return スコープ参照。フォルダ不在なら空
     */
    public Optional<FolderScopeRef> findByFolderId(Long folderId) {
        return folderRepository.findById(folderId)
                .map(f -> new FolderScopeRef(f.getScopeType(), f.getScopeRefId()));
    }

    /**
     * ファイル ID から所属フォルダのスコープを解決する。
     *
     * @param fileId ファイル ID
     * @return スコープ参照。ファイル不在、または所属フォルダ不在なら空
     */
    public Optional<FolderScopeRef> findByFileId(Long fileId) {
        return fileRepository.findById(fileId)
                .flatMap(file -> folderRepository.findById(file.getFolderId()))
                .map(f -> new FolderScopeRef(f.getScopeType(), f.getScopeRefId()));
    }
}
