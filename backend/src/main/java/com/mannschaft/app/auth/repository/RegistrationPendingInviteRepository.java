package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.RegistrationPendingInviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 招待付き新規登録の保留（{@code registration_pending_invites}）のリポジトリ（CMP-261107-1833）。
 *
 * <p>状態遷移は条件付き native UPDATE だけで行い、時刻は {@code UTC_TIMESTAMP(6)} で書く
 * （DB 格納基準は UTC 壁時計。Java の時刻を束縛しない）。attempt の世代は {@code UNHEX} した hex32 で照合する。</p>
 */
public interface RegistrationPendingInviteRepository extends JpaRepository<RegistrationPendingInviteEntity, UUID> {

    Optional<RegistrationPendingInviteEntity> findByUserId(Long userId);

    /** PENDING、または 5 分を超えて止まった ACCEPTING を、新しい attempt で ACCEPTING にする。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = 'ACCEPTING', accept_attempt_id = UNHEX(:attemptHex), "
            + "accepting_started_at = UTC_TIMESTAMP(6), failure_code = NULL, version = version + 1, "
            + "updated_at = UTC_TIMESTAMP(6) WHERE user_id = :userId AND (status = 'PENDING' OR (status = 'ACCEPTING' "
            + "AND accepting_started_at < UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE))", nativeQuery = true)
    int startAccepting(@Param("userId") Long userId, @Param("attemptHex") String attemptHex);

    /** 自分の attempt が ACCEPTING のときだけ結果状態へ進め、attempt を NULL に戻す。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = :result, failure_code = :failureCode, "
            + "accept_attempt_id = NULL, version = version + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE user_id = :userId AND status = 'ACCEPTING' AND accept_attempt_id = UNHEX(:attemptHex)",
            nativeQuery = true)
    int completeAttempt(@Param("userId") Long userId, @Param("attemptHex") String attemptHex,
                        @Param("result") String result, @Param("failureCode") String failureCode);

    /** 現状態が from のいずれかのときだけ to へ遷移する。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = :to, version = version + 1, "
            + "updated_at = UTC_TIMESTAMP(6) WHERE user_id = :userId AND status IN (:from)", nativeQuery = true)
    int transition(@Param("userId") Long userId, @Param("from") Collection<String> from, @Param("to") String to);

    /** EXPIRED/REISSUE_REQUESTED で、前回要請から 7 日を超えた（または未要請の）ときだけ要請を記録する。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = 'REISSUE_REQUESTED', "
            + "reissue_requested_at = UTC_TIMESTAMP(6), version = version + 1, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE user_id = :userId AND status IN ('EXPIRED', 'REISSUE_REQUESTED') "
            + "AND (reissue_requested_at IS NULL OR reissue_requested_at < UTC_TIMESTAMP(6) - INTERVAL 7 DAY)",
            nativeQuery = true)
    int markReissueRequested(@Param("userId") Long userId);

    /** 未終了（PENDING/ACCEPTING/EXPIRED/REISSUE_REQUESTED）の行を DISCARDED にする。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = 'DISCARDED', accept_attempt_id = NULL, "
            + "version = version + 1, updated_at = UTC_TIMESTAMP(6) WHERE user_id IN (:userIds) "
            + "AND status IN ('PENDING', 'ACCEPTING', 'EXPIRED', 'REISSUE_REQUESTED')", nativeQuery = true)
    int discardOpen(@Param("userIds") Collection<Long> userIds);
}
