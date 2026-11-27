package com.mannschaft.app.supportaccess.repository;

import com.mannschaft.app.supportaccess.SupportAccessStatus;
import com.mannschaft.app.supportaccess.entity.SupportAccessGrantEntity;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F10.9 support_access_grants のリポジトリ。
 *
 * <p><b>状態遷移の排他</b>: 遷移する書込（許可・拒否・取消・撤回・開始・終了・失効・退会）は必ず
 * {@code *ForUpdate}（{@code PESSIMISTIC_WRITE} = {@code SELECT ... FOR UPDATE}）で grant 行を読む。
 * ロックなしで読んで書くと、同時の取消と開始のような競合で後勝ちの上書きが起き、取消が消える。</p>
 *
 * <p><b>ロックの取得順（第2回検分対応）</b>: grant 行のロックは<b>必ず主キー（{@link #findByIdForUpdate}）で</b>取り、
 * 二次索引（status・target_status・open_flag の uq）を走査してロックする問い合わせを持たない。以前は失効バッチ
 * （status 索引）と退会（target_status 索引）が二次索引の行を先にロックしてから主キーへ進み、本人の許可・取消・開始は
 * 主キー → 二次索引（status の更新）の順だったため、逆順の取得でデッドロックした。いまは複数行を扱う失効・退会・依頼も
 * 対象の id を<b>ロックなし</b>（{@code findOverdueIds} 等・一貫性読み取り）で集め、id 昇順に1件ずつ主キーでロックしてから
 * 状態を再検証して遷移する。順序は「運営者の依頼ロック（{@link #lockOperatorForRequest}）→ grant 行の主キー（id 昇順）
 * → 新しい行の INSERT（uq 索引）」に統一する。</p>
 *
 * <p>ロック待ちの上限は {@code SupportAccessLockWaitScope}（{@code innodb_lock_wait_timeout} を5秒）で絞る。
 * {@code jakarta.persistence.lock.timeout} のミリ秒指定は Hibernate の MySQL 方言が解釈せず黙って無視するため
 * （{@code ConfirmableNotificationRepository} の経緯を参照）、接続のセッション変数で実効化する。</p>
 */
public interface SupportAccessGrantRepository extends JpaRepository<SupportAccessGrantEntity, UUID> {

    /** 遷移用: grant 行を排他ロックして読む。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from SupportAccessGrantEntity g where g.id = :id")
    Optional<SupportAccessGrantEntity> findByIdForUpdate(@Param("id") UUID id);

    /** 依頼用: 同じ運営者×本人の開いている grant の id（ロックしない。呼び出し側が id 昇順に主キーでロックする）。 */
    @Query("select g.id from SupportAccessGrantEntity g where g.operatorUserId = :operatorUserId "
            + "and g.targetUserId = :targetUserId and g.status in :statuses order by g.id")
    List<UUID> findOpenIds(@Param("operatorUserId") Long operatorUserId,
                           @Param("targetUserId") Long targetUserId,
                           @Param("statuses") Collection<SupportAccessStatus> statuses);

    /** 退会用: 本人の開いている grant の id（ロックしない。呼び出し側が1件ずつ主キーでロックして再検証する）。 */
    @Query("select g.id from SupportAccessGrantEntity g where g.targetUserId = :targetUserId "
            + "and g.status in :statuses order by g.id")
    List<UUID> findOpenIdsByTargetUserId(@Param("targetUserId") Long targetUserId,
                                         @Param("statuses") Collection<SupportAccessStatus> statuses);

    /**
     * 失効バッチ用: 期限に達した PENDING（依頼期限）・APPROVED（許可期限）の id（ロックしない。呼び出し側が1件ずつ
     * 主キーでロックして期限を再検証する）。境界は {@code SupportAccessGrantValidator}（{@code now < expiresAt} の
     * ときだけ有効）に揃え、期限ちょうど（{@code expiresAt == now}）も失効とする（{@code <=}）。
     */
    @Query("select g.id from SupportAccessGrantEntity g where "
            + "(g.status = com.mannschaft.app.supportaccess.SupportAccessStatus.PENDING and g.requestExpiresAt <= :now) "
            + "or (g.status = com.mannschaft.app.supportaccess.SupportAccessStatus.APPROVED "
            + "and g.expiresAt is not null and g.expiresAt <= :now) order by g.id")
    List<UUID> findOverdueIds(@Param("now") Instant now);

    /**
     * 運営者単位の依頼の排他（1日20件の上限の count→insert を直列化する）。
     * {@code ON DUPLICATE KEY UPDATE} は既存行にも共有ロックでなく排他ロックを取るため、同じ運営者の同時依頼は
     * ここで1件ずつになる（{@code INSERT IGNORE}→{@code SELECT FOR UPDATE} は共有→排他の昇格でデッドロックする）。
     */
    @Modifying
    @Query(value = "INSERT INTO support_access_request_locks (operator_user_id) VALUES (:operatorUserId) "
            + "ON DUPLICATE KEY UPDATE operator_user_id = operator_user_id", nativeQuery = true)
    int lockOperatorForRequest(@Param("operatorUserId") Long operatorUserId);

    /** 運営アカウントの物理削除時に、その運営者の依頼ロック行を消す。 */
    @Modifying
    @Query(value = "DELETE FROM support_access_request_locks WHERE operator_user_id = :operatorUserId", nativeQuery = true)
    int deleteRequestLock(@Param("operatorUserId") Long operatorUserId);

    /** 保護者の物理削除時: 保護者としてだけ記録されている行から保護者の userId を外す（本人の記録は残す）。 */
    @Modifying
    @Query("update SupportAccessGrantEntity g set g.guardianUserId = null where g.guardianUserId = :guardianUserId")
    int clearGuardianUserId(@Param("guardianUserId") Long guardianUserId);

    /** 保護者の GDPR エクスポート用: 保護者として関わった grant。 */
    List<SupportAccessGrantEntity> findByGuardianUserIdOrderByRequestedAtDesc(Long guardianUserId);

    /** 本人の GDPR エクスポート用: 本人宛の grant（新しい順）。 */
    List<SupportAccessGrantEntity> findByTargetUserIdOrderByRequestedAtDesc(Long targetUserId);

    /** 同じ運営者×本人の開いている（PENDING/APPROVED）grant。 */
    Optional<SupportAccessGrantEntity> findFirstByOperatorUserIdAndTargetUserIdAndStatusIn(
            Long operatorUserId, Long targetUserId, Collection<SupportAccessStatus> statuses);

    List<SupportAccessGrantEntity> findByTargetUserIdAndStatusIn(Long targetUserId, Collection<SupportAccessStatus> statuses);

    /** 開いている grant（退会の取りこぼしの照合用。ロックしない）。 */
    List<SupportAccessGrantEntity> findByStatusIn(Collection<SupportAccessStatus> statuses);

    List<SupportAccessGrantEntity> findByStatusAndRequestExpiresAtBefore(SupportAccessStatus status, Instant threshold);

    List<SupportAccessGrantEntity> findByStatusAndExpiresAtBefore(SupportAccessStatus status, Instant threshold);

    /** 運営者ごとの1日あたり依頼数（011 の判定）。 */
    long countByOperatorUserIdAndRequestedAtAfter(Long operatorUserId, Instant since);

    /** 運営者の自分の依頼（新しい順）。カーソルは requestedAt（これより前）。 */
    List<SupportAccessGrantEntity> findByOperatorUserIdAndRequestedAtBeforeOrderByRequestedAtDesc(
            Long operatorUserId, Instant before, Pageable pageable);

    /** 本人宛・保護者宛の依頼（新しい順）。 */
    @Query("select g from SupportAccessGrantEntity g "
            + "where (g.targetUserId = :userId or (g.guardianApprovalRequired = true and g.guardianUserId = :userId)) "
            + "and g.requestedAt < :before order by g.requestedAt desc")
    List<SupportAccessGrantEntity> findVisibleToUser(@Param("userId") Long userId,
                                                     @Param("before") Instant before, Pageable pageable);

    /** 退会30日後の強消去: 本人宛の許可を物理削除する。 */
    @Modifying
    @Query("delete from SupportAccessGrantEntity g where g.targetUserId = :targetUserId")
    int deleteByTargetUserId(@Param("targetUserId") Long targetUserId);

    /** 保存期間（終了から3年）を過ぎた終了済み grant の物理削除。 */
    @Modifying
    @Query("delete from SupportAccessGrantEntity g where g.status in :statuses and g.updatedAt < :threshold")
    int deleteByStatusInAndUpdatedAtBefore(@Param("statuses") Collection<SupportAccessStatus> statuses,
                                           @Param("threshold") Instant threshold);
}
