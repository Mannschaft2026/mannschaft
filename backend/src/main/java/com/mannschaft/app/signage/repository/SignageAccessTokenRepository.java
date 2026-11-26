package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
