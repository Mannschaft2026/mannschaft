package com.mannschaft.app.filesharing.repository;

import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * ファイルが TEAM スコープのフォルダに属するとき、そのチーム ID を返す（CMP-261124-0159・親チーム上限の判定用）。
     *
     * <p>ファイル・フォルダの Entity は論理削除済みを JPQL から隠す（@SQLRestriction）ため、削除済みでも所属チームを
     * 判定できるようネイティブ SQL で引く（閲覧不可側へ倒す）。</p>
     */
    @Query(value = """
            SELECT d.team_id FROM shared_files f
            JOIN shared_folders d ON d.id = f.folder_id
            WHERE f.id = :fileId AND d.scope_type = 'TEAM'
            """, nativeQuery = true)
    Optional<Long> findTeamIdByFileId(@Param("fileId") Long fileId);

    /**
     * チームのフォルダ配下（論理削除済みを含む）のファイルの有効なリンクを一括で失効させる
     * （CMP-261124-0159・非公開化フロー / 是正 migration と同じ述語）。UPDATE 1 本で件数に比例しない。
     *
     * @return 失効させた件数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE shared_file_links SET is_active = FALSE
            WHERE is_active = TRUE
              AND file_id IN (
                  SELECT f.id FROM shared_files f
                  JOIN shared_folders d ON d.id = f.folder_id
                  WHERE d.scope_type = 'TEAM' AND d.team_id = :teamId)
            """, nativeQuery = true)
    int deactivateActiveLinksByTeam(@Param("teamId") Long teamId);
}
