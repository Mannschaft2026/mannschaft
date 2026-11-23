package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.RegistrationPendingInviteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
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

    /**
     * PENDING のまま招待が使えなくなった（失効・期限切れ・上限到達）ときだけ EXPIRED にする（CMP-261107-1833 PR3 設計 g）。
     */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = 'EXPIRED', failure_code = :failureCode, "
            + "version = version + 1, updated_at = UTC_TIMESTAMP(6) WHERE user_id = :userId AND status = 'PENDING'",
            nativeQuery = true)
    int expirePending(@Param("userId") Long userId, @Param("failureCode") String failureCode);

    // ------------------------------------------------------------------
    // 再招待依頼の受け手側（CMP-261107-1833 PR3 設計 b・f）。一覧と件数は同じ述語
    // （status=REISSUE_REQUESTED・スコープ一致・依頼者 ACTIVE・論理削除なし）を使う。
    // ------------------------------------------------------------------

    /** 指定スコープの保留 1 件（他スコープの ID は引けない）。 */
    Optional<RegistrationPendingInviteEntity> findByIdAndScopeTypeAndScopeId(UUID id, String scopeType, Long scopeId);

    /** 利用者が ACTIVE かつ論理削除されていなければ 1。 */
    @Query(value = "SELECT COUNT(*) FROM users WHERE id = :userId AND status = 'ACTIVE' AND deleted_at IS NULL",
            nativeQuery = true)
    long countActiveUser(@Param("userId") Long userId);

    /** 再招待依頼の件数（一覧の totalElements・集約の pending_count の正本）。 */
    @Query(value = "SELECT COUNT(*) FROM registration_pending_invites p JOIN users u ON u.id = p.user_id "
            + "WHERE p.scope_type = :scopeType AND p.scope_id = :scopeId AND p.status = 'REISSUE_REQUESTED' "
            + "AND u.status = 'ACTIVE' AND u.deleted_at IS NULL", nativeQuery = true)
    long countReissueRequests(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /** 再招待依頼の 1 ページ（古い順。件数と同じ述語）。 */
    @Query(value = "SELECT p.* FROM registration_pending_invites p JOIN users u ON u.id = p.user_id "
            + "WHERE p.scope_type = :scopeType AND p.scope_id = :scopeId AND p.status = 'REISSUE_REQUESTED' "
            + "AND u.status = 'ACTIVE' AND u.deleted_at IS NULL "
            + "ORDER BY p.reissue_requested_at ASC, p.id ASC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<RegistrationPendingInviteEntity> findReissueRequestPage(@Param("scopeType") String scopeType,
                                                                  @Param("scopeId") Long scopeId,
                                                                  @Param("limit") int limit,
                                                                  @Param("offset") long offset);

    /**
     * 再招待の確定（REISSUE_REQUESTED→PENDING）。依頼者が ACTIVE のときだけ効き、新しいハッシュへ替えて
     * 承認・失敗・依頼の列を NULL に戻す。
     */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites p JOIN users u ON u.id = p.user_id "
            + "SET p.status = 'PENDING', p.token_hash = :tokenHash, p.failure_code = NULL, p.accept_attempt_id = NULL, "
            + "p.accepting_started_at = NULL, p.reissue_requested_at = NULL, p.version = p.version + 1, "
            + "p.updated_at = UTC_TIMESTAMP(6) WHERE p.id = UNHEX(:idHex) AND p.scope_type = :scopeType "
            + "AND p.scope_id = :scopeId AND p.status = 'REISSUE_REQUESTED' AND u.status = 'ACTIVE' "
            + "AND u.deleted_at IS NULL", nativeQuery = true)
    int confirmReissue(@Param("idHex") String idHex, @Param("scopeType") String scopeType,
                       @Param("scopeId") Long scopeId, @Param("tokenHash") String tokenHash);

    /** REISSUE_REQUESTED のときだけ to（REISSUE_DECLINED / JOINED）へ進める（id・スコープ一致）。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = :to, version = version + 1, "
            + "updated_at = UTC_TIMESTAMP(6) WHERE id = UNHEX(:idHex) AND scope_type = :scopeType "
            + "AND scope_id = :scopeId AND status = 'REISSUE_REQUESTED'", nativeQuery = true)
    int resolveReissueRequest(@Param("idHex") String idHex, @Param("scopeType") String scopeType,
                              @Param("scopeId") Long scopeId, @Param("to") String to);

    /** 未終了（PENDING/ACCEPTING/EXPIRED/REISSUE_REQUESTED）の行を DISCARDED にする。 */
    @Modifying
    @Query(value = "UPDATE registration_pending_invites SET status = 'DISCARDED', accept_attempt_id = NULL, "
            + "version = version + 1, updated_at = UTC_TIMESTAMP(6) WHERE user_id IN (:userIds) "
            + "AND status IN ('PENDING', 'ACCEPTING', 'EXPIRED', 'REISSUE_REQUESTED')", nativeQuery = true)
    int discardOpen(@Param("userIds") Collection<Long> userIds);
}
