package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.MemberWorkConstraintRequest;
import com.mannschaft.app.shift.dto.MemberWorkConstraintResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * メンバー勤務制約の <b>認可ファサード</b>（トランザクションの外。CMP-261125-2114）。
 *
 * <p>本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。流れは
 * 「認可（{@link ShiftManageAccessGate}）＋対象の在籍検査（{@code AccessControlService#isMember}）→
 * {@link MemberWorkConstraintService} の tx 本体（認可しない）」。tx 内から membership・role の Repository へ
 * 到達させない（D-3T）。Controller は本クラスだけを呼ぶ。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（個別制約の upsert）</h3>
 * <pre>
 * 主体＼対象                  対象が当該チームの在籍者      対象が在籍者でない（他チーム・脱退・不在 ID）
 * ADMIN／MANAGE_SHIFTS 付き DEPUTY_ADMIN  200  403 WORK_CONSTRAINT_FORBIDDEN（権限不足と同一応答）
 * 上記以外（一般メンバー・MANAGE_SHIFTS 無しの DEPUTY_ADMIN・他チームでだけ権限を持つ副管理者・
 *   チームロール無しの SYSTEM_ADMIN）  403 WORK_CONSTRAINT_FORBIDDEN  403 WORK_CONSTRAINT_FORBIDDEN
 * DB 変更: 拒否はすべて変更なし。成功のみ member_work_constraints の insert/update。
 * </pre>
 * <p>権限判定が先（権限の無い人に対象の在籍を観測させない）。続けて、対象がそのチームの在籍者であることを検査する
 * （他チームでだけ MANAGE_SHIFTS を持つ副管理者が、パスに自チームを書いて他チームのメンバーの制約を作る越境を塞ぐ。
 * ユーザー ID の存在オラクルにしないため権限不足と同じ応答）。</p>
 */
@Service
@RequiredArgsConstructor
public class MemberWorkConstraintFacade {

    private static final String TEAM = "TEAM";

    private final MemberWorkConstraintService workConstraintService;
    private final ShiftManageAccessGate manageGate;
    private final AccessControlService accessControlService;

    /**
     * メンバー個別の勤務制約を upsert する（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN。対象は当該チームの在籍者）。
     *
     * @param teamId        チーム ID
     * @param userId        対象ユーザー ID
     * @param request       制約リクエスト
     * @param currentUserId 操作者ユーザー ID
     * @return 更新後の勤務制約
     * @throws BusinessException 権限不足、または対象が在籍者でない場合（{@link ShiftErrorCode#WORK_CONSTRAINT_FORBIDDEN}）
     */
    public MemberWorkConstraintResponse upsertConstraint(
            Long teamId, Long userId, MemberWorkConstraintRequest request, Long currentUserId) {
        manageGate.requireManage(currentUserId, teamId, ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        if (!accessControlService.isMember(userId, teamId, TEAM)) {
            throw new BusinessException(ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        }
        return workConstraintService.upsertConstraint(teamId, userId, request, currentUserId);
    }

    /** チーム内の全勤務制約を取得する（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN。権限不足は WORK_CONSTRAINT_FORBIDDEN）。 */
    public List<MemberWorkConstraintResponse> listConstraintsByTeam(Long teamId, Long currentUserId) {
        manageGate.requireManage(currentUserId, teamId, ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        return workConstraintService.listConstraintsByTeam(teamId, currentUserId);
    }

    /** チームデフォルトを upsert する（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。 */
    public MemberWorkConstraintResponse upsertTeamDefault(
            Long teamId, MemberWorkConstraintRequest request, Long currentUserId) {
        manageGate.requireManage(currentUserId, teamId, ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        return workConstraintService.upsertTeamDefault(teamId, request, currentUserId);
    }

    /** 個別勤務制約を削除する（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。 */
    public void deleteConstraint(Long teamId, Long userId, Long currentUserId) {
        manageGate.requireManage(currentUserId, teamId, ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        workConstraintService.deleteConstraint(teamId, userId, currentUserId);
    }

    /** チームデフォルトを削除する（ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。 */
    public void deleteTeamDefault(Long teamId, Long currentUserId) {
        manageGate.requireManage(currentUserId, teamId, ShiftErrorCode.WORK_CONSTRAINT_FORBIDDEN);
        workConstraintService.deleteTeamDefault(teamId, currentUserId);
    }
}
