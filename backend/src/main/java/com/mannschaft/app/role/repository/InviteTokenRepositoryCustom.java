package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.dto.InviteClaimAuditContext;
import com.mannschaft.app.role.dto.InviteClaimLock;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.InviteTokenSnapshot;
import com.mannschaft.app.role.dto.InviteValidity;
import com.mannschaft.app.role.entity.InviteTokenEntity;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 招待トークンと確保記録（{@code invite_token_claims}）の、派生クエリでは書けない操作（カスタム fragment）。
 *
 * <p>使用回数（{@code used_count}）を変えてよいのは {@link #incrementUsedCountIfAvailable(Long)}（確保）と
 * {@link #decrementUsedCount(Long)}（補償）だけである。native 文を実行した後は管理下の
 * {@link InviteTokenEntity} を {@link #refreshIfManaged(Long)} で最新化する
 * （{@code clearAutomatically} は登録中のユーザー Entity を巻き込むため使わない）。</p>
 */
public interface InviteTokenRepositoryCustom {

    /** 招待の所属先を、トークンのハッシュで行ロックなしの通常読みで引く。 */
    Optional<InviteScopeRef> findScopeByTokenHash(String tokenHash);

    /** 招待の所属先を、招待 ID で行ロックなしの通常読みで引く。 */
    Optional<InviteScopeRef> findScopeById(Long id);

    /** 招待行をトークンのハッシュで {@code FOR UPDATE} で読み、最新値を Entity を介さず射影で返す。 */
    Optional<InviteTokenSnapshot> lockSnapshotByTokenHash(String tokenHash);

    /** {@link #lockSnapshotByTokenHash(String)} の招待 ID 版。 */
    Optional<InviteTokenSnapshot> lockSnapshotById(Long id);

    /**
     * 未失効かつ上限未満のときだけ使用回数を1進める条件付き UPDATE。
     *
     * @return 更新行数（1: 成功 / 0: 失効または上限到達）
     */
    int incrementUsedCountIfAvailable(Long tokenId);

    /** 使用回数を1戻す（0 を下回らない）。補償専用。 @return 更新行数 */
    int decrementUsedCount(Long tokenId);

    /** 同じトークンの Entity を最新状態へ再読込する（native 文の後に呼ぶ）。 */
    void refreshIfManaged(Long tokenId);

    /** 外側トランザクションのスナップショットや一次キャッシュに左右されず、最新値で行をロックして返す。 */
    Optional<InviteTokenEntity> lockFreshById(Long id);

    /** {@link #lockFreshById(Long)} のトークンハッシュ版。 */
    Optional<InviteTokenEntity> lockFreshByTokenHash(String tokenHash);

    /** CLAIMED の確保記録を1行挿入して主キー（UUIDv7）を返す。 */
    UUID insertClaim(Long tokenId, Long userId);

    /** CLAIMED→GRANTED の条件付き UPDATE。 @return 更新行数 */
    int markClaimGranted(UUID claimId);

    /**
     * CLAIMED→RELEASED の条件付き UPDATE。この claim による付与がコミット済み（{@code grant_committed_at} あり）、
     * または付与されたか不明な旧データ（{@code legacy_grant_unknown_at} あり）なら戻さない（0 行）。 @return 更新行数
     */
    int markClaimReleased(UUID claimId);

    /**
     * CLAIMED かつ未付与の claim に「この claim による付与がコミット済み」の印を立てる条件付き UPDATE
     * （ロール付与と同じトランザクションで呼ぶ。CMP-261107-1833）。 @return 更新行数
     */
    int markClaimGrantCommitted(UUID claimId);

    /** claim の状態と付与印を、ロック付きの最新値で読む（CMP-261107-1833）。 */
    Optional<InviteClaimLock> lockClaim(UUID claimId);

    /**
     * 同じ招待・同じ利用者に残った CLAIMED の確保を、ロック付きの最新値で1件引く（CMP-261107-1833 §9-3）。
     * 招待行をロックした後に呼ぶこと（ロック順: 招待行 → claim）。
     */
    Optional<InviteClaimLock> findClaimedForUpdate(Long tokenId, Long userId);

    /** 招待のハッシュで、付与するロールの名前を引く（ロックなし。CMP-261107-1833 §2）。 */
    Optional<String> findRoleNameByTokenHash(String tokenHash);

    /**
     * 招待のハッシュで、有効性（失効・仮発行中・期限切れ・上限到達）を DB の現在時刻で判定する（ロックなし。
     * CMP-261107-1833 PR3 設計 c・g）。招待が無ければ空。
     */
    Optional<InviteValidity> findValidityByTokenHash(String tokenHash);

    /** 招待のハッシュ群から、付与するロールの名前をまとめて引く（1 本の SQL。CMP-261107-1833 PR3 L4）。 */
    Map<String, String> findRoleNamesByTokenHashes(Collection<String> tokenHashes);

    /** 作成直後の招待に仮発行の印（{@code revoked_at = created_at}）を付ける（CMP-261107-1833 PR3 設計 c）。 @return 更新行数 */
    int markStaged(Long tokenId);

    /**
     * 仮発行の印がある未使用の宛先付き招待を有効にする（{@code revoked_at = NULL}）。印の窓（作成から
     * {@link InviteValidity#STAGING_WINDOW}）を過ぎたものは有効にしない（CMP-261107-1833 PR3 設計 c）。 @return 更新行数
     */
    int activateStaged(Long tokenId, Long targetUserId);

    /** claim が指す招待の主キー。 */
    Optional<Long> findClaimTokenId(UUID claimId);

    /** claim の現在状態を、ロック付きの最新値で読む。 */
    Optional<String> findClaimStatusLocked(UUID claimId);

    /** claim と招待行を突き合わせ、招待監査イベントの材料を返す。 */
    Optional<InviteClaimAuditContext> findClaimAuditContext(UUID claimId);

    /** 作成から閾値秒を超えて CLAIMED のままの件数（監視用）。 */
    long countStaleClaimed(long thresholdSeconds);

    /** 最古の CLAIMED の滞留秒。CLAIMED が無ければ 0（監視用）。 */
    long oldestClaimedAgeSeconds();
}
