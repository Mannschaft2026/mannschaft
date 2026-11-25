package com.mannschaft.app.membership.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.MembershipEndCause;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * メンバーシップ退会リクエスト DTO。
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §6.2 / §7.2</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MembershipLeaveRequest {

    /** 退会理由。SELF / REMOVED / TRANSFER / OTHER。GDPR は専用ルートのため受け付けない。 */
    @NotNull
    private LeaveReason leaveReason;

    /** 除名（REMOVED）時に必須。除名を実行した管理者の userId。 */
    private Long removedBy;

    /**
     * F06.4.1（データ設計 §8）: 加入期間を終える理由の区分。既定は明示終了（EXPLICIT_MEMBERSHIP_END）。
     *
     * <p>ACCOUNT_WITHDRAWAL は退会処理の内部経路（RoleService#removeMemberWithoutAdminCheck）だけが設定する。
     * 外部リクエストから指定させないため JSON では受け付けない。</p>
     */
    @JsonIgnore
    private MembershipEndCause endCause = MembershipEndCause.EXPLICIT_MEMBERSHIP_END;
}
