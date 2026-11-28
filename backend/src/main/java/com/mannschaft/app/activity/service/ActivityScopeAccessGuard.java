package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ScopeContentAccessGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 活動記録（F06.4）のスコープ認可ガード。
 *
 * <p>activity ドメインの各 Service / Controller が共通で使う「スコープ所属・スコープ管理者」判定を
 * 本クラスへ集約する。{@code bulletin} ドメインの {@code BulletinAccessGuard} を金型とする。</p>
 *
 * <h2>スコープ別の方針（網羅的ディスパッチ）</h2>
 * <p>{@link ActivityScopeType} の全値を明示的に扱い、<strong>未対応の値は既定で拒否する
 * （fail-closed）</strong>。分岐の追加漏れが「素通し」ではなく「拒否」に倒れるようにするための規律であり、
 * 将来 {@code ActivityScopeType} に値が追加された場合も既定で拒否される。</p>
 * <ul>
 *   <li>{@code TEAM} / {@code ORGANIZATION}: {@link AccessControlService} 経由で所属・ロールを実効認可する。</li>
 *   <li>{@code COMMITTEE}: メンバーシップ／ロール基盤（{@link AccessControlService}）の解決対象外のため、
 *       本ガードでは認可を与えず拒否する。これは同ドメインの
 *       {@code ActivityResultVisibilityProjection}（F00 共通可視性基盤）が COMMITTEE を
 *       fail-closed として扱う方針と一致させたものである。
 *       委員会スコープを機能として開くときは、委員会メンバーシップ
 *       （{@code committee} ドメイン）を解決する分岐をここに実装すること。</li>
 * </ul>
 *
 * <p>認可違反は共通 403（{@link CommonErrorCode#COMMON_002}）を用いる（activity 専用の認可エラーは作らない）。</p>
 *
 * <h2>読み取りと書き込みの書き分け（CMP-261124-0159・F00 §20.3 / §20.5.1）</h2>
 * <p>中身を返す読み取り（一覧・詳細・コメント・統計など）と中身を返す書き込み（複製・参加者の追加/削除）は
 * 共通の門 {@link ScopeContentAccessGate} の {@link #checkScopeContentAccess} / {@link #canAccessScopeContent} を、
 * 中身を返さない書き込み（作成・コメント投稿など）は {@link #checkMembershipForWrite} を使う。
 * 非公開チーム（{@code MEMBERS_AND_ABOVE}）の中身はそのチームの ADMIN / DEPUTY_ADMIN / MEMBER にだけ見せ、
 * サポーター・ゲスト・部外者には部外者と同一の 403（COMMON_002）を返す。</p>
 */
@Component
@RequiredArgsConstructor
public class ActivityScopeAccessGuard {

    private final AccessControlService accessControlService;
    private final ScopeContentAccessGate scopeContentAccessGate;

    /**
     * 当該スコープが {@link AccessControlService} のロール／メンバーシップ基盤で
     * 認可判定可能か（= TEAM / ORGANIZATION か）を返す。
     *
     * <p>網羅的ディスパッチ。{@code null} および解決対象外のスコープでは {@code false} を返し、
     * 呼び出し側が拒否へ倒す。</p>
     */
    private boolean isRoleManagedScope(ActivityScopeType scopeType) {
        if (scopeType == null) {
            return false;
        }
        return switch (scopeType) {
            case TEAM, ORGANIZATION -> true;
            case COMMITTEE -> false;
        };
    }

    /**
     * ロール基盤で解決できないスコープを拒否する。
     *
     * @throws BusinessException 解決対象外スコープ（COMMON_002）
     */
    private void requireRoleManagedScope(ActivityScopeType scopeType) {
        if (!isRoleManagedScope(scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スコープの中身を読めることを検証する（参照系・中身を返す書き込み）。共通の門
     * {@link ScopeContentAccessGate#checkScopeContentAccess} に委譲する（非公開チームでは MEMBER 以上）。
     *
     * @throws BusinessException 非メンバー・非公開チームの MEMBER 未満、または解決対象外スコープ（COMMON_002）
     */
    public void checkScopeContentAccess(Long userId, ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        scopeContentAccessGate.checkScopeContentAccess(userId, scopeId, scopeType.name());
    }

    /**
     * {@link #checkScopeContentAccess} の真偽版（例外を投げない）。同期対象・auto 記録を秘匿して除外する判定に使う。
     * 解決対象外スコープでは {@code false}（fail-closed）。
     */
    public boolean canAccessScopeContent(Long userId, ActivityScopeType scopeType, Long scopeId) {
        return isRoleManagedScope(scopeType)
                && scopeContentAccessGate.canAccessScopeContent(userId, scopeId, scopeType.name());
    }

    /**
     * 中身を返さない書き込み（作成・コメント投稿など）の在籍確認。判定は {@code checkMembership} と同一
     * （{@link ScopeContentAccessGate#checkMembershipForWrite}。非公開チームにサポーター・ゲストが在籍しない不変条件に依拠する）。
     *
     * @throws BusinessException 非メンバー、または解決対象外スコープ（COMMON_002）
     */
    public void checkMembershipForWrite(Long userId, ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        scopeContentAccessGate.checkMembershipForWrite(userId, scopeId, scopeType.name());
    }

    /**
     * {@link #canAccessScopeContent} の別名。common の {@code AutomaticActivityActualPolicy}（auto 実績の読み取り段取り。
     * 担当 PR 未割当のため PR-Z で棚卸し）だけが呼ぶ。activity パッケージ内では使わない（判定は親チーム上限込み）。
     */
    public boolean isMember(Long userId, ActivityScopeType scopeType, Long scopeId) {
        return canAccessScopeContent(userId, scopeType, scopeId);
    }

    /** auto actualの所属要件をF00のSYSTEM_ADMIN短絡より前で適用するために使う。 */
    public boolean isSystemAdmin(Long userId) {
        return userId != null && accessControlService.isSystemAdmin(userId);
    }

    /**
     * 活動統計の閲覧資格（F06.4 §15.2）を検証する。共通の門を通したうえで、スコープでのロールが MEMBER 以上
     * （在籍が {@code role_kind=MEMBER}、または在籍したうえで ADMIN / DEPUTY_ADMIN）。公開チーム・組織のサポーターも
     * 部外者と同一の 403（COMMON_002）。COMMITTEE は常に 403。
     *
     * @throws BusinessException 資格なし、または解決対象外スコープ（COMMON_002）
     */
    public void checkRegularMemberOrAbove(Long userId, ActivityScopeType scopeType, Long scopeId) {
        checkScopeContentAccess(userId, scopeType, scopeId);
        if (!accessControlService.isRegularMemberOrAbove(userId, scopeId, scopeType.name())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * スコープ管理者（ADMIN / DEPUTY_ADMIN）であることを検証する（更新・削除系）。
     *
     * @throws BusinessException 非管理者、または解決対象外スコープ（COMMON_002）
     */
    public void checkAdminOrAbove(Long userId, ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * 作成者本人、またはスコープ管理者であることを検証する（活動記録の公開・更新・削除）。
     *
     * <p>解決対象外スコープでは作成者本人であっても拒否する（fail-closed）。</p>
     *
     * @throws BusinessException 本人でも管理者でもない、または解決対象外スコープ（COMMON_002）
     */
    public void checkAuthorOrAdmin(Long userId, Long authorUserId,
                                   ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        if (userId != null && userId.equals(authorUserId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
    }

    /**
     * リソース所有者本人、またはスコープ管理者であることを検証する（コメント削除）。
     *
     * @throws BusinessException 本人でも管理者でもない、または解決対象外スコープ（COMMON_002）
     */
    public void checkOwnerOrAdmin(Long currentUserId, Long resourceOwnerId,
                                  ActivityScopeType scopeType, Long scopeId) {
        requireRoleManagedScope(scopeType);
        accessControlService.checkOwnerOrAdmin(
                currentUserId, resourceOwnerId, scopeId, scopeType.name());
    }

    /**
     * スコープ管理者（ADMIN / DEPUTY_ADMIN）かどうかを返す。
     *
     * <p>権限の有無を問う述語であるため、解決対象外スコープでは
     * <strong>権限なし（{@code false}）</strong>を返す（fail-closed）。</p>
     */
    public boolean isAdminOrAbove(Long userId, ActivityScopeType scopeType, Long scopeId) {
        return isRoleManagedScope(scopeType)
                && accessControlService.isAdminOrAbove(userId, scopeId, scopeType.name());
    }
}
