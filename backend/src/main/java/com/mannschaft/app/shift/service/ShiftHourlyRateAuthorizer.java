package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 時給（金銭情報）に対する per-scope 認可の<b>唯一の判定箇所</b>（トランザクションの外）。
 *
 * <p>時給の参照・登録（{@link ShiftHourlyRateService}）と削除（{@link ShiftHourlyRateDeletionFacade}）は
 * すべてここを通る。判定が複数箇所に分かれて片方だけ直される事故を防ぐため、判定を複製してはならない。
 * 本クラスは {@code @Transactional} を付けない（クラスにもメソッドにも）。</p>
 *
 * <p>参照・削除の判定順:</p>
 * <ol>
 *   <li>対象が本人 — 当該チームのメンバーであることを要求</li>
 *   <li>対象が他メンバー — 呼び出し元が ADMIN／MANAGE_SHIFTS を持つ DEPUTY_ADMIN、または時給閲覧切替 ON の
 *       DEPUTY_ADMIN であること、かつ対象ユーザーも当該チームのメンバーであることを要求（対象側 BOLA 封鎖）</li>
 * </ol>
 * <p>SYSTEM_ADMIN を特別扱いしない（チームロールを持たない SYSTEM_ADMIN は拒否。CMP-261125-2114、
 * docs/security/03_role_authority_model.md §3.6）。登録・訂正・削除は {@link #checkHourlyRateWriteAccess} で
 * ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN に限る（切替では広がらない）。</p>
 */
@Component
@RequiredArgsConstructor
public class ShiftHourlyRateAuthorizer {

    private static final String TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final ShiftManageAccessGate manageGate;

    /**
     * @param currentUserId 操作ユーザーID
     * @param targetUserId  時給の対象ユーザーID
     * @param teamId        チームID
     * @throws com.mannschaft.app.common.BusinessException 権限がない場合（COMMON_002 / 403）
     */
    public void checkHourlyRateAccess(Long currentUserId, Long targetUserId, Long teamId) {
        if (currentUserId.equals(targetUserId)) {
            accessControlService.checkMembership(currentUserId, teamId, TEAM);
            return;
        }
        // 他メンバーの時給: 管理者に加えて、時給閲覧切替 ON の DEPUTY_ADMIN にも閲覧だけ許す。
        if (!manageGate.canViewHourlyRates(currentUserId, teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        accessControlService.checkMembership(targetUserId, teamId, TEAM);
    }

    /**
     * 時給の削除の認可。本人は自分の分を従来どおり削除できる。他メンバーの分は ADMIN と MANAGE_SHIFTS を持つ
     * DEPUTY_ADMIN だけ（時給閲覧切替 ON の DEPUTY_ADMIN にも許さない）。
     *
     * @throws com.mannschaft.app.common.BusinessException 権限がない場合（COMMON_002 / 403）
     */
    public void checkHourlyRateDeleteAccess(Long currentUserId, Long targetUserId, Long teamId) {
        if (currentUserId.equals(targetUserId)) {
            accessControlService.checkMembership(currentUserId, teamId, TEAM);
            return;
        }
        checkHourlyRateWriteAccess(currentUserId, targetUserId, teamId);
    }

    /**
     * 時給の登録・訂正を、当該チームの ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN に限定する
     * （時給閲覧切替 ON の DEPUTY_ADMIN にも許さない）。対象ユーザーの所属確認を含む。
     *
     * @param currentUserId 操作ユーザーID
     * @param targetUserId  時給の対象ユーザーID
     * @param teamId        チームID
     * @throws com.mannschaft.app.common.BusinessException 権限がない場合（COMMON_002 / 403）
     */
    public void checkHourlyRateWriteAccess(Long currentUserId, Long targetUserId, Long teamId) {
        manageGate.requireManage(currentUserId, teamId);
        accessControlService.checkMembership(targetUserId, teamId, TEAM);
    }
}
