package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 駐車場申請の閲覧範囲・申請資格の判定（CMP-260930-0233）。
 *
 * <p>{@code @Transactional} を付けない入口専用コンポーネント。{@link AccessControlService} は内部で
 * 他ドメイン（role / membership）の Repository に到達するため、{@code @Transactional} な Service /
 * Guard の内側から呼ぶと D-3T（推移的クロスドメイン番人）に新規違反を作る。判定は Controller から
 * 本クラス経由で行い、結果（boolean）だけを Service へ渡す。</p>
 */
@Component
@RequiredArgsConstructor
public class ParkingApplicationAccessPolicy {

    /** 申請の全件閲覧・承認・却下・抽選に要る権限名（ADMIN 自動付与・DEPUTY_ADMIN 手動付与）。 */
    public static final String MANAGE_PARKING = "MANAGE_PARKING";

    private final AccessControlService accessControlService;

    /**
     * 申請を全件閲覧できるか（ADMIN、または MANAGE_PARKING を持つ DEPUTY_ADMIN）。
     * それ以外は本人分のみ閲覧できる。
     */
    public boolean canViewAll(String scopeType, Long scopeId, Long userId) {
        return accessControlService.hasAdminOrPermissionInScope(userId, scopeId, scopeType, MANAGE_PARKING);
    }

    /** 正式メンバー以上（SUPPORTER を除く）であることを要求する。非該当は 403（COMMON_002）。 */
    public void requireRegularMember(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkRegularMemberOrAbove(userId, scopeId, scopeType);
    }
}
