package com.mannschaft.app.parking.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
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
    private final ProxyInputContext proxyInputContext;

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

    /**
     * 承認・却下・抽選の入口で ADMIN または MANAGE_PARKING 保有者であることを要求する。
     * 申請 ID の存在を問わず 403（COMMON_002）にするため、Service の存在確認より前に呼ぶ（存在オラクル解消）。
     */
    public void requireManage(String scopeType, Long scopeId, Long userId) {
        accessControlService.checkAdminOrHasPermissionInScope(userId, scopeId, scopeType, MANAGE_PARKING);
    }

    /**
     * 申請者（車両照合の対象）を解決する。操作者は正式メンバー以上であること。
     * 代理入力時は同意書に {@code PARKING_APPLICATION} スコープが要り、代理対象者も正式メンバー以上であること。
     * 非該当は 403（COMMON_002）。
     *
     * @return 通常は操作者、代理入力時は代理対象者
     */
    public Long resolveApplicant(String scopeType, Long scopeId, Long actorId) {
        requireRegularMember(scopeType, scopeId, actorId);
        if (!proxyInputContext.isProxy()) {
            return actorId;
        }
        if (!proxyInputContext.hasScope("PARKING_APPLICATION")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        Long subjectId = proxyInputContext.getSubjectUserId();
        requireRegularMember(scopeType, scopeId, subjectId);
        return subjectId;
    }
}
