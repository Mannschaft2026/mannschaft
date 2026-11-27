package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * デジタルサイネージ アクセストークンリポジトリ。
 */
public interface SignageAccessTokenRepository extends JpaRepository<SignageAccessTokenEntity, Long> {

    /**
     * トークンのハッシュでアクティブなトークンを取得する。
     */
    Optional<SignageAccessTokenEntity> findByTokenHashAndIsActiveTrue(String tokenHash);

    /**
     * 画面IDに紐づくトークン一覧を取得する。
     */
    List<SignageAccessTokenEntity> findByScreenId(Long screenId);

    /**
     * チームの全画面（論理削除済みの画面を含む）の有効なトークンを一括で無効化する（CMP-261124-0159・非公開化フロー）。
     * 期限なしのトークンも対象。UPDATE 1 本で、画面数・トークン数に比例しない。
     *
     * @param teamId チーム ID
     * @return 無効化した件数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    // 画面の Entity は論理削除済みを JPQL から隠す（@SQLRestriction）ため、削除済み画面のトークンも確実に止めるよう
    // ネイティブ SQL で書く。
    @Query(value = """
            UPDATE signage_access_tokens SET is_active = FALSE, updated_at = CURRENT_TIMESTAMP
            WHERE is_active = TRUE
              AND screen_id IN (
                  SELECT s.id FROM signage_screens s
                  WHERE s.scope_type = 'TEAM' AND s.scope_id = :teamId)
            """, nativeQuery = true)
    int deactivateActiveTokensByTeam(@Param("teamId") Long teamId);
}
