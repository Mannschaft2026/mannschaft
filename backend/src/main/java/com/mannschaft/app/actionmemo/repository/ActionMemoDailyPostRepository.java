package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoDailyPostEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * F02.5 行動メモの日ごとの公開記録リポジトリ（CMP-261123-1201）。
 */
public interface ActionMemoDailyPostRepository extends JpaRepository<ActionMemoDailyPostEntity, UUID> {

    /**
     * (user, date) の記録を悲観ロック（FOR UPDATE）つきで取得する。
     * publish-daily が旧投稿 ID を読む際、他の公開の完了を待って最新の記録を読むために使う。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM ActionMemoDailyPostEntity d WHERE d.userId = :userId AND d.memoDate = :memoDate")
    Optional<ActionMemoDailyPostEntity> findByUserIdAndMemoDateForUpdate(
            @Param("userId") Long userId,
            @Param("memoDate") LocalDate memoDate);

    /**
     * 指定ユーザーの記録を全件物理削除する（退会即時・30日後消去・手動再試行で使う）。
     *
     * @return 削除件数
     */
    @Modifying
    @Query(value = "DELETE FROM action_memo_daily_posts WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUserId(@Param("userId") Long userId);
}
