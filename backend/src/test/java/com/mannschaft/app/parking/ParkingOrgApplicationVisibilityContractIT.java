package com.mannschaft.app.parking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.transaction.annotation.Transactional;

/**
 * CMP-260930-0233 試練（ORGANIZATION スコープ・AC-18）: 組織スコープでも
 * AC-1, 2, 4, 7, 11, 12, 16 が同じく成立することを検証する。
 *
 * <p>テスト本体は {@link AbstractParkingApplicationVisibilityContractIT} に共通化し、
 * 本クラスは組織の作成・URL・user_roles/権限グループの組織列だけを与える。
 * ORGANIZATION 経路の DEPUTY_ADMIN 権限判定は
 * {@code UserRoleRepository.existsDeputyAdminWithPermissionInOrganization}（TEAM とは別クエリ）を通るため、
 * TEAM 版だけでは検証にならない。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260930-0233 駐車場申請の閲覧範囲・車両所有・取消（ORGANIZATION・AC-18・試練）")
class ParkingOrgApplicationVisibilityContractIT extends AbstractParkingApplicationVisibilityContractIT {

    @Override
    protected String scopeType() {
        return "ORGANIZATION";
    }

    @Override
    protected String urlSegment() {
        return "organizations";
    }

    @Override
    protected String emailPrefix() {
        return "pkvis-org";
    }

    @Override
    protected Long insertScope(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
