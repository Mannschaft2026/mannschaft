package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.UserSocialProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ソーシャルプロフィールリポジトリ。
 */
public interface UserSocialProfileRepository extends JpaRepository<UserSocialProfileEntity, Long> {

    /**
     * ユーザーIDでプロフィールを取得する。
     */
    Optional<UserSocialProfileEntity> findByUserId(Long userId);

    /**
     * ハンドルでプロフィールを取得する。
     */
    Optional<UserSocialProfileEntity> findByHandle(String handle);

    /**
     * 複数ユーザーの有効なプロフィールを一括取得する（グローバル検索の結果 URL 解決。CMP-261116-0722）。
     */
    List<UserSocialProfileEntity> findByUserIdInAndIsActiveTrue(Collection<Long> userIds);

    /**
     * ハンドルの存在チェックを行う。
     */
    boolean existsByHandle(String handle);

    /**
     * ユーザーIDの存在チェックを行う。
     */
    boolean existsByUserId(Long userId);
}
