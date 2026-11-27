package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ファイル共有リンクリポジトリ。
 */
public interface SharedFileLinkRepository extends JpaRepository<SharedFileLinkEntity, Long> {

    /**
     * トークンのハッシュ（{@code SecretTokenVault#hash}）で共有リンクを取得する。
     */
    Optional<SharedFileLinkEntity> findByTokenHash(String tokenHash);

    /**
     * ファイルの共有リンク一覧を取得する。
     */
    List<SharedFileLinkEntity> findByFileIdOrderByCreatedAtDesc(Long fileId);

    /**
     * ファイルの共有リンク数を取得する。
     */
    long countByFileId(Long fileId);
}
