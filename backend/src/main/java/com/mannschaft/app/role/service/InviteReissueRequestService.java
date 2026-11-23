package com.mannschaft.app.role.service;

import org.springframework.stereotype.Service;

/**
 * 再招待依頼の受け手側（招待権限者の一覧・再招待・見送り）の司令塔（CMP-261107-1833 PR3 設計 b・d・e）。
 *
 * <p>{@code @Transactional} を持たない。保留の状態は auth の短TX（{@code PendingInviteStore}）、トークンの仮発行と
 * 有効化は role の短TX（{@link MembershipInviteTokenIssuer}）で行う。</p>
 *
 * <p><b>試練スタブ</b>: 付与ロールの判定だけ形を置いた。一覧・再招待・見送りは出陣で実装する。</p>
 */
@Service
public class InviteReissueRequestService {

    /**
     * 再招待で付与するロール（設計 d）。非特権（MEMBER/SUPPORTER/GUEST）は引き継ぎ、特権（ADMIN/DEPUTY_ADMIN）や
     * 元のトークン・ロールが引けない（null）場合は MEMBER。一覧の roleName と発行で同じ判定を使う。
     */
    public static String resolveGrantRole(String originalRoleName) {
        throw new UnsupportedOperationException("未実装: InviteReissueRequestService#resolveGrantRole（CMP-261107-1833 PR3）");
    }
}
