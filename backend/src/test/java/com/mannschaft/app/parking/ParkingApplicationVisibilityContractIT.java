package com.mannschaft.app.parking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.transaction.annotation.Transactional;



/**
 * CMP-260930-0233 試練（TEAM スコープ）: 駐車場申請の閲覧範囲・車両所有・取消の API 契約。
 *
 * <p>共通の受け入れ条件（AC-1, 2, 4, 7, 11, 12, 16）は {@link AbstractParkingApplicationVisibilityContractIT}。
 * 本クラスはチーム固有のスコープ作成・URL のみを与える（AC-3, 5, 6, 13, 14, 15, 17, 20 も基底で TEAM / ORGANIZATION 共通に走る）。
 * 実 Security フィルタ鎖（addFilters=true・基底既定）＋実 MySQL で検証する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260930-0233 駐車場申請の閲覧範囲・車両所有・取消（TEAM・試練）")
class ParkingApplicationVisibilityContractIT extends AbstractParkingApplicationVisibilityContractIT {

    @Override
    protected String scopeType() {
        return "TEAM";
    }

    @Override
    protected String urlSegment() {
        return "teams";
    }

    @Override
    protected String emailPrefix() {
        return "pkvis-team";
    }

    @Override
    protected Long insertScope(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
