package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 時給の参照の <b>認可ファサード</b>（トランザクションの外。CMP-261125-2114）。
 *
 * <p>時給の閲覧は、本人、ADMIN／{@code MANAGE_SHIFTS} を持つ DEPUTY_ADMIN、およびチームの時給閲覧切替
 * （{@code deputyHourlyRateViewEnabled}）が ON のときの DEPUTY_ADMIN に許す。切替は team ドメインの設定なので、
 * 認可を {@code @Transactional} の {@link ShiftHourlyRateService} の中で行うと tx 入口から他ドメインの Repository へ
 * 到達してしまう（D-3T）。そのため認可を本クラス（tx なし）で済ませ、{@link ShiftHourlyRateService} は
 * 認可なしの tx 本体に徹する。本クラスは {@code @Transactional} を<b>付けない</b>（クラスにもメソッドにも）。
 * Controller は参照系では本クラスだけを呼ぶ（登録・訂正は従来どおり {@link ShiftHourlyRateService#createHourlyRate}、
 * 削除は {@link ShiftHourlyRateDeletionFacade}）。</p>
 *
 * <p>SYSTEM_ADMIN は特別扱いしない（チームロールを持たない SYSTEM_ADMIN は拒否。
 * docs/security/03_role_authority_model.md §3.6）。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 */
@Service
@RequiredArgsConstructor
public class ShiftHourlyRateFacade {

    private static final String TEAM = "TEAM";

    private final ShiftHourlyRateService hourlyRateService;
    private final ShiftHourlyRateAuthorizer hourlyRateAuthorizer;
    private final ShiftManageAccessGate manageGate;
    private final AccessControlService accessControlService;

    /**
     * ユーザーの時給履歴を取得する（本人 + 時給の閲覧を許された管理者）。
     *
     * @param userId        対象ユーザーID
     * @param teamId        チームID
     * @param currentUserId 操作ユーザーID
     * @return 時給履歴一覧
     */
    public List<HourlyRateResponse> listHourlyRates(Long userId, Long teamId, Long currentUserId) {
        hourlyRateAuthorizer.checkHourlyRateAccess(currentUserId, userId, teamId);
        return hourlyRateService.listHourlyRates(userId, teamId);
    }

    /**
     * 特定日時点の有効時給を取得する（本人 + 時給の閲覧を許された管理者）。
     *
     * @param userId        対象ユーザーID
     * @param teamId        チームID
     * @param date          基準日
     * @param currentUserId 操作ユーザーID
     * @return 有効な時給（存在しない場合はnull）
     */
    public HourlyRateResponse getEffectiveRate(Long userId, Long teamId, LocalDate date, Long currentUserId) {
        hourlyRateAuthorizer.checkHourlyRateAccess(currentUserId, userId, teamId);
        return hourlyRateService.getEffectiveRate(userId, teamId, date);
    }

    /**
     * チーム全員ぶんの有効時給を取得する（他メンバーの時給を必ず含むので、時給の閲覧を許された管理者だけ）。
     *
     * <p>認可（{@link ShiftManageAccessGate#requireHourlyRateViewOrDeny}）の後に、在籍中メンバーの ID を 1 クエリで解決して
     * tx 本体へ渡す（脱退した元メンバーの時給を返さない）。</p>
     *
     * @param teamId        チームID
     * @param date          基準日
     * @param currentUserId 操作ユーザーID
     * @return ユーザーごとの有効時給
     */
    public List<HourlyRateResponse> listEffectiveRatesForTeam(Long teamId, LocalDate date, Long currentUserId) {
        manageGate.requireHourlyRateViewOrDeny(currentUserId, teamId);
        List<Long> activeMemberIds = accessControlService.listActiveMemberIds(teamId, TEAM);
        return hourlyRateService.listEffectiveRatesForTeam(teamId, date, activeMemberIds);
    }
}
