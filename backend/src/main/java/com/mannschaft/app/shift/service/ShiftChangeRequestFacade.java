package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * シフト変更依頼の <b>認可ファサード</b>（トランザクションの外）。
 *
 * <p>CMP-260923-0954 W2: 認可を {@code @Transactional} の外へ出す。本クラスは {@code @Transactional} を
 * <b>付けない</b>（クラスにもメソッドにも）。流れは「{@link ShiftChangeRequestService} の readOnly な scope 解決
 * （自ドメインのみ）→ 認可 → {@link ShiftChangeRequestService} の tx（中で読み直す）」。
 * Controller は本クラスだけを呼ぶ。名前を {@code *AccessService} / {@code *AccessGate} にしないのは、
 * 呼んだだけで認可シグナル扱いになり本クラス内の認可漏れを AuthzControllerGuard が見逃すのを避けるため。</p>
 *
 * <h3>認可表（是正前＝#3528 の契約から変えていない）</h3>
 * <pre>
 * 主体＼EP                    作成(schedule)    一覧(schedule)   詳細(id)          審査(id)         取下げ(id)
 * 部外者(非メンバー)          404 SHIFT_001     404 SHIFT_001    404 SHIFT_030     404 SHIFT_030    404 SHIFT_030
 * 同チーム一般メンバー        201               200(自分の分)    200(本人)/404 SHIFT_030(他人の依頼) 403 COMMON_002 403 SHIFT_019(他人の依頼)
 * チーム ADMIN/DEPUTY_ADMIN   201               200(全件)        200              200(状態次第)    403 SHIFT_019
 * 依頼者本人                  201               200(自分の分)    200              (一般なら 403)   204(OPEN のみ)
 *                                                                                (管理者でも 403 SHIFT_045。CMP-261010-1432)
 * SYSTEM_ADMIN(チームロール無し) 部外者と同じ（CMP-261125-2114: SYSTEM_ADMIN を特別扱いしない。取下げは 404 SHIFT_030）
 * user_roles のみの ADMIN     403 COMMON_002    200(全件)        200              200(状態次第)    403 SHIFT_019
 * 対象不在(依頼/スケジュール) 404 SHIFT_001     404 SHIFT_001    404 SHIFT_030     404 SHIFT_030    404 SHIFT_030
 * 親だけ不在(スケジュール)    404 SHIFT_001     404 SHIFT_001    404 SHIFT_001(本人以外) 404 SHIFT_001 404 SHIFT_001(本人以外)
 * DB 変更: 拒否・不在はすべて変更なし。成功のみ変更依頼行の insert/update。
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ShiftChangeRequestFacade {

    private final ShiftChangeRequestService changeRequestService;
    private final ShiftManageAccessGate manageGate;
    /**
     * 依頼者の表示名解決（CMP-261010-1432 M）。users は auth ドメインのため、common の越境窓口を
     * <b>トランザクションの外（本 Facade）</b>で呼ぶ（tx 入口から他ドメインの Repository へ到達させない＝D-3T）。
     */
    private final NameResolverService nameResolverService;

    /**
     * 変更依頼を作成する（スケジュール所属チームのメンバー。SUPPORTER は不可 403 COMMON_002）。
     *
     * <p>在籍（memberships）が必須。SYSTEM_ADMIN を特別扱いしない（CMP-261125-2114）。チームロールの無い
     * SYSTEM_ADMIN は部外者と同じく不在と同一応答（404 SHIFT_001）。</p>
     *
     * <p>SUPPORTER は仕様（F03.5 README: 変更依頼不可）どおり、シフト表のステータスを見る前に 403 で弾く
     * （tx 本体のステータス判定の 201/409 の差で公開状態を読ませない。CMP-260826-2127 P2）。</p>
     *
     * @param request 作成リクエスト
     * @param userId  依頼者ユーザー ID
     * @return 作成された変更依頼
     */
    public ChangeRequestResponse create(CreateChangeRequestRequest request, Long userId) {
        Long teamId = changeRequestService.resolveScheduleTeamId(request.scheduleId());
        manageGate.requireMemberOrConceal(userId, teamId, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, true);
        return withRequesterName(changeRequestService.create(request, userId));
    }

    /**
     * 変更依頼一覧を取得する。管理者の形（ADMIN・MANAGE_SHIFTS 付き DEPUTY_ADMIN、閲覧切替 ON の DEPUTY_ADMIN）は
     * 全件、一般メンバーは自分の依頼のみ。
     * 越境（非メンバー）は不在と同一応答（{@code SHIFT_SCHEDULE_NOT_FOUND}）に畳む。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @return 変更依頼一覧
     */
    public List<ChangeRequestResponse> list(Long scheduleId, Long userId) {
        Long teamId = changeRequestService.resolveScheduleTeamId(scheduleId);
        // 管理者の形（閲覧扱い）か、そうでなければ同一チームの在籍者。拒否経路でのみ越境判定を行う。
        boolean privileged = manageGate.canView(userId, teamId);
        if (!privileged) {
            manageGate.requireMemberOrConceal(userId, teamId, ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
        return withRequesterNames(changeRequestService.list(scheduleId, userId, privileged));
    }

    /**
     * 変更依頼詳細を取得する。依頼者本人または管理者の形（閲覧扱い）のみ。それ以外は存在秘匿で 404
     * （{@code CHANGE_REQUEST_NOT_FOUND}）。
     *
     * @param id     変更依頼 ID
     * @param userId 操作者ユーザー ID
     * @return 変更依頼
     */
    public ChangeRequestResponse get(Long id, Long userId) {
        ShiftChangeRequestService.ChangeRequestHead head = changeRequestService.resolveHead(id);
        if (!head.requestedBy().equals(userId)
                && !manageGate.canView(userId, resolveTeamIdOrConceal(head))) {
            // 越境は存在秘匿（未存在と同一応答）
            throw new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
        }
        return withRequesterName(changeRequestService.get(id, userId));
    }

    /**
     * 変更依頼を審査する（当該チームの ADMIN、または MANAGE_SHIFTS を持つ DEPUTY_ADMIN）。
     * 管理者宛の通知の受信者（{@code RoleService#getAdminAndPermittedDeputyUserIdsByTeamId}）と同じ述語。
     *
     * @param id      変更依頼 ID
     * @param request 審査リクエスト
     * @param userId  審査者ユーザー ID
     * @return 更新された変更依頼
     */
    public ChangeRequestResponse review(Long id, ReviewChangeRequestRequest request, Long userId) {
        ShiftChangeRequestService.ChangeRequestHead head = changeRequestService.resolveHead(id);
        Long teamId = userId.equals(head.requestedBy())
                ? changeRequestService.resolveScheduleTeamId(head.scheduleId())
                : resolveTeamIdOrConceal(head);
        manageGate.requireManageOrConceal(userId, teamId, ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
        // CMP-261010-1432 B（マスター決裁 2026-10-12）: 依頼者本人による審査は禁止。SYSTEM_ADMIN も例外にしない。
        // 認可（越境の存在秘匿）の後に置き、部外者には依頼者が誰かを観測させない。
        // tx 本体の status・version 照合より先に判定する（version が古い自己審査も 403 SHIFT_045＝AC-B3）。
        if (userId.equals(head.requestedBy())) {
            throw new BusinessException(ShiftErrorCode.SELF_REVIEW_FORBIDDEN);
        }
        return withRequesterName(changeRequestService.review(id, request, userId));
    }

    /**
     * 変更依頼を取り下げる（依頼者本人のみ。管理者による代理取下げは対象外）。
     *
     * <p>本人でない場合、当該チームの所属者（user_roles のみの管理者を含む）には
     * {@code ACCESS_DENIED}（403 / SHIFT_019）、所属しない越境（チームロールの無い SYSTEM_ADMIN を含む。
     * SYSTEM_ADMIN を特別扱いしない）の場合のみ不在と同一応答（{@code CHANGE_REQUEST_NOT_FOUND}）。
     * Gate は forbidden コードを {@code COMMON_002} 固定で持つため、SHIFT_019 を維持する本メソッドは
     * 同ゲートの所属判定（{@code belongsToTeam}）だけを踏襲する。</p>
     *
     * @param id     変更依頼 ID
     * @param userId 操作者ユーザー ID
     */
    public void withdraw(Long id, Long userId) {
        ShiftChangeRequestService.ChangeRequestHead head = changeRequestService.resolveHead(id);
        if (!head.requestedBy().equals(userId)) {
            // 本人でなければ取下げ不可。所属者（SYSTEM_ADMIN は特別扱いしない）には ACCESS_DENIED、越境は不在と同一応答。
            Long teamId = resolveTeamIdOrConceal(head);
            if (manageGate.belongsToTeam(userId, teamId)) {
                throw new BusinessException(ShiftErrorCode.ACCESS_DENIED);
            }
            throw new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
        }
        changeRequestService.withdraw(id, userId);
    }

    /**
     * 依頼の親スケジュールのチーム ID を解決する。親が削除済み・不在なら、依頼者本人以外には
     * 存在しない依頼と同じ {@code CHANGE_REQUEST_NOT_FOUND}（404 SHIFT_030）へ畳む。
     * 親スケジュール不在の {@code SHIFT_SCHEDULE_NOT_FOUND}（SHIFT_001）をそのまま返すと、
     * 「依頼は実在するが親が消えている」ことが不在依頼との差で読めるため（CMP-260826-2127）。
     * 依頼者本人にはこれを使わず、従来の診断を残す。
     */
    private Long resolveTeamIdOrConceal(ShiftChangeRequestService.ChangeRequestHead head) {
        try {
            return changeRequestService.resolveScheduleTeamId(head.scheduleId());
        } catch (BusinessException e) {
            if (e.getErrorCode() == ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND) {
                throw new BusinessException(ShiftErrorCode.CHANGE_REQUEST_NOT_FOUND);
            }
            throw e;
        }
    }

    /**
     * 応答一覧に依頼者の表示名（{@code requesterName}）を付ける（CMP-261010-1432 M）。
     *
     * <p>名前解決は一覧 1 回のバッチ（件数に依らずクエリ数一定）。解決できない userId（退会者等）は
     * {@code null}。返す範囲は認可済みの一覧そのものなので、露出する名前は増えない（一般メンバーは自分の依頼だけ）。</p>
     */
    private List<ChangeRequestResponse> withRequesterNames(List<ChangeRequestResponse> responses) {
        if (responses.isEmpty()) {
            return responses;
        }
        Set<Long> requesterIds = responses.stream()
                .map(r -> r.getRequestInfo().requestedBy())
                .collect(Collectors.toSet());
        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(requesterIds);
        return responses.stream()
                .map(r -> r.toBuilder().requesterName(names.get(r.getRequestInfo().requestedBy())).build())
                .toList();
    }

    /** 単票版の {@link #withRequesterNames}。 */
    private ChangeRequestResponse withRequesterName(ChangeRequestResponse response) {
        return withRequesterNames(List.of(response)).get(0);
    }
}
