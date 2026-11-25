package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.team.dto.DeputyShiftViewToggles;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * シフト管理の<b>認可ゲート</b>（トランザクションの外。CMP-261125-2114）。
 *
 * <h2>方針（マスター裁定 2026-11-25）</h2>
 * <ul>
 *   <li><b>管理操作</b>（作成・更新・削除・遷移・割当・自動割当・承認・勤務制約・時給の登録削除・ポジション・設定変更・
 *       リマインド）は ADMIN と {@value #MANAGE_SHIFTS} を持つ DEPUTY_ADMIN だけに許可する
 *       （{@link AccessControlService#hasAdminOrPermissionInScope}）。DEPUTY_ADMIN だというだけでは許可しない。</li>
 *   <li><b>閲覧</b>（未公開シフト表・割当の非マスク・他人の交代/変更依頼・希望一覧/サマリー・PDF の管理者出力・
 *       ダッシュボード要対応のシフト希望）と<b>時給の閲覧</b>は、管理者に加えて、チームのシフト設定の切替
 *       （{@link DeputyShiftViewToggles}、既定 OFF）が ON のときに限り MANAGE_SHIFTS 無しの DEPUTY_ADMIN にも許す。
 *       OFF なら一般メンバーと同じ。切替は閲覧だけで、操作は常に MANAGE_SHIFTS が必要。</li>
 *   <li><b>SYSTEM_ADMIN を特別扱いしない</b>（docs/security/03_role_authority_model.md §3.6）。チームの ADMIN でも
 *       MANAGE_SHIFTS 付き DEPUTY_ADMIN でもない SYSTEM_ADMIN は、操作も閲覧も他の部外者と同じに拒否する。</li>
 * </ul>
 *
 * <h2>拒否の作り分け（存在オラクルの封鎖。{@code ScopeConcealingAccessGate} と同じ規約）</h2>
 * <p>所属していない（越境）は呼び出し側の不在コード、所属者の権限不足は 403 {@code COMMON_002}。
 * 所属は memberships の在籍、または user_roles の ADMIN/DEPUTY_ADMIN のいずれか（両者が食い違う副管理者を
 * 越境と誤判定して 404 にしない）。</p>
 *
 * <h2>R4（トランザクション本体は認可クラスに依存しない）</h2>
 * <p>本クラスは Facade（tx の外）から呼ぶ。閲覧切替の値や privileged の結果は Facade で解決し、
 * tx 本体へは引数として渡すこと。tx 本体から本クラスや設定を引いてはならない。
 * 例外として、認可を tx 本体の中で行う既存の Service（勤務制約・時給・可用性）は従来どおり本クラスを呼ぶ。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftManageAccessGate {

    /** シフト管理の権限名。 */
    public static final String MANAGE_SHIFTS = "MANAGE_SHIFTS";

    private static final String TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final TeamShiftSettingsService teamShiftSettingsService;

    // ═════════════════════════════════════════════════════════════════════
    // 管理操作
    // ═════════════════════════════════════════════════════════════════════

    /**
     * ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN か（例外を投げない）。SYSTEM_ADMIN は特別扱いしない。
     */
    public boolean canManage(Long userId, Long teamId) {
        return accessControlService.hasAdminOrPermissionInScope(userId, teamId, TEAM, MANAGE_SHIFTS);
    }

    /**
     * 管理操作の認可。越境は {@code notFoundCode}、所属者の権限不足は 403 {@code COMMON_002}。
     *
     * @param notFoundCode 不在時に投げるコードと同一のコード
     */
    public void requireManageOrConceal(Long userId, Long teamId, ErrorCode notFoundCode) {
        if (canManage(userId, teamId)) {
            return;
        }
        throw denial(userId, teamId, notFoundCode);
    }

    /**
     * 本人、または管理操作の認可。本人判定はメモリ上の比較でクエリを撃たないため先に評価する。
     */
    public void requireOwnerOrManageOrConceal(Long userId, Long teamId, Long ownerId, ErrorCode notFoundCode) {
        if (ownerId != null && Objects.equals(ownerId, userId)) {
            return;
        }
        requireManageOrConceal(userId, teamId, notFoundCode);
    }

    /**
     * 管理操作の認可（{@code ?teamId=} 直接指定型）。越境・権限不足とも 403 {@code COMMON_002}
     * （隠蔽対象のリソース ID を持たず、応答がチームの実在で割れないため）。
     */
    public void requireManage(Long userId, Long teamId) {
        if (!canManage(userId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 管理操作の認可で、権限不足のコードを呼び出し側が決める版（勤務制約など独自の 403 コードを持つもの用）。
     */
    public void requireManage(Long userId, Long teamId, ErrorCode forbiddenCode) {
        if (!canManage(userId, teamId)) {
            throw new BusinessException(forbiddenCode);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 閲覧
    // ═════════════════════════════════════════════════════════════════════

    /**
     * シフトの閲覧を管理者の形で許すか（例外を投げない）。管理操作ができる人、または閲覧切替が ON のとき
     * 当該チームの DEPUTY_ADMIN。
     */
    public boolean canView(Long userId, Long teamId) {
        return isManagerOrToggledDeputy(userId, teamId, false);
    }

    /**
     * 他メンバーの時給の閲覧を許すか（例外を投げない）。管理操作ができる人、または時給閲覧切替が ON のとき
     * 当該チームの DEPUTY_ADMIN。時給の登録・削除は対象外（{@link #canManage}）。
     */
    public boolean canViewHourlyRates(Long userId, Long teamId) {
        return isManagerOrToggledDeputy(userId, teamId, true);
    }

    /**
     * 閲覧の認可。許可されなければ {@link #requireManageOrConceal} と同じ作り分けで拒否する。
     */
    public void requireViewOrConceal(Long userId, Long teamId, ErrorCode notFoundCode) {
        if (canView(userId, teamId)) {
            return;
        }
        throw denial(userId, teamId, notFoundCode);
    }

    private boolean isManagerOrToggledDeputy(Long userId, Long teamId, boolean hourlyRate) {
        // MANAGE_SHIFTS 付き DEPUTY・ADMIN は必ず isAdminOrAbove を満たすので、先に非管理系ロールを弾いて
        // 一般メンバーの閲覧経路のクエリを抑える。
        if (!accessControlService.isAdminOrAbove(userId, teamId, TEAM)) {
            return false;
        }
        if (canManage(userId, teamId)) {
            return true;
        }
        // ここへ来るのは MANAGE_SHIFTS を持たない DEPUTY_ADMIN。切替が ON のときだけ許す。
        DeputyShiftViewToggles toggles = teamShiftSettingsService.findDeputyViewToggles(teamId);
        return hourlyRate ? toggles.hourlyRateView() : toggles.shiftView();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 所属
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 当該チームの所属者として扱うか。memberships の在籍、または user_roles の ADMIN/DEPUTY_ADMIN。
     * 両系統が食い違う副管理者も所属者とみなし、越境（404）に化けさせない。
     */
    public boolean belongsToTeam(Long userId, Long teamId) {
        return accessControlService.isMember(userId, teamId, TEAM)
                || accessControlService.isAdminOrAbove(userId, teamId, TEAM);
    }

    /**
     * 一般メンバーの参照の認可（在籍中であること。SYSTEM_ADMIN を特別扱いしない）。
     * 在籍していなければ、user_roles のみの管理者は 403、それ以外は越境として {@code notFoundCode}。
     */
    public void requireMemberOrConceal(Long userId, Long teamId, ErrorCode notFoundCode) {
        if (accessControlService.isMember(userId, teamId, TEAM)) {
            return;
        }
        throw denial(userId, teamId, notFoundCode);
    }

    private BusinessException denial(Long userId, Long teamId, ErrorCode notFoundCode) {
        if (!belongsToTeam(userId, teamId)) {
            // 越境（他スコープ／無所属）: 存在自体を隠す側。不在時と完全同一のコード。
            return new BusinessException(notFoundCode);
        }
        return new BusinessException(CommonErrorCode.COMMON_002);
    }
}
