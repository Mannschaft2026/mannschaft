package com.mannschaft.app.supporter;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.VisibilityErrorCode;
import com.mannschaft.app.supporter.dto.BulkApproveRequest;
import com.mannschaft.app.supporter.entity.SupporterApplicationEntity;
import com.mannschaft.app.supporter.repository.SupporterApplicationRepository;
import com.mannschaft.app.supporter.service.SupporterService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import com.mannschaft.app.team.TeamErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CMP-261124-0159 PR-E: 非公開チームへのサポーター申請の承認・一括承認・フォローの関所（実 MySQL。AC-0159-42 / 43 / 44 / 46）。
 *
 * <p>F01.2 03「サポーター入口の拒否」。承認・一括承認はチーム行を FOR SHARE で読み、非公開なら 403 {@code TEAM_073}
 * （一括は全体を拒否）。申請は PENDING のまま・在籍は作られない。公開チームの期待値は本戦役前（origin/main）の挙動を固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 非公開チームへのサポーター申請の関所（AC-0159-42/43/44/46）")
class MembersOnlyTeamSupporterEntryIT extends AbstractMySqlIntegrationTest {

    @Autowired private SupporterService supporterService;
    @Autowired private SupporterApplicationRepository applicationRepository;
    @Autowired private ContentVisibilityChecker contentVisibilityChecker;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private ScopeContentFixture fixture;
    private final List<Long> applicants = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        fixture = tx.execute(t -> ScopeContentFixture.create(em));
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("DELETE FROM supporter_applications WHERE scope_type = 'TEAM' AND scope_id = :t")
                    .setParameter("t", fixture.teamId).executeUpdate();
            if (!applicants.isEmpty()) {
                em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", applicants)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)").setParameter("ids", applicants)
                        .executeUpdate();
            }
            fixture.delete(em);
        });
    }

    private void setVisibility(String visibility) {
        tx.executeWithoutResult(t -> ScopeContentFixture.setTeamVisibility(em, fixture.teamId, visibility));
    }

    /** 申請者（部外者）を作り、当該チームへの PENDING 申請を 1 件置く（是正前データを模したフィクスチャ）。 */
    private Long pendingApplication() {
        Long applicant = 9_597_000_000L + ThreadLocalRandom.current().nextLong(0, 900_000_000L);
        applicants.add(applicant);
        return tx.execute(t -> {
            MembershipTestHelper.insertActiveUser(em, applicant);
            return applicationRepository.saveAndFlush(SupporterApplicationEntity.builder()
                    .scopeType("TEAM").scopeId(fixture.teamId).userId(applicant)
                    .status(SupporterApplicationStatus.PENDING).build()).getId();
        });
    }

    private String statusOf(Long applicationId) {
        return tx.execute(t -> applicationRepository.findById(applicationId).orElseThrow().getStatus().name());
    }

    private Long applicantOf(Long applicationId) {
        return tx.execute(t -> applicationRepository.findById(applicationId).orElseThrow().getUserId());
    }

    private long activeSupporterCount(Long userId) {
        return tx.execute(t -> ((Number) em.createNativeQuery("SELECT COUNT(*) FROM memberships WHERE user_id = :u "
                        + "AND scope_type = 'TEAM' AND scope_id = :t AND role_kind = 'SUPPORTER' AND left_at IS NULL")
                .setParameter("u", userId).setParameter("t", fixture.teamId).getSingleResult()).longValue());
    }

    private static BulkApproveRequest bulkRequest(List<Long> ids) {
        BulkApproveRequest request = new BulkApproveRequest();
        ReflectionTestUtils.setField(request, "applicationIds", ids);
        return request;
    }

    private static BusinessException business(Runnable call) {
        try {
            call.run();
        } catch (BusinessException e) {
            return e;
        }
        return fail("BusinessException が投げられなかった");
    }

    @Test
    @DisplayName("AC-0159-42: 非公開チームの PENDING 申請を ADMIN が承認すると 403 TEAM_073・申請は PENDING のまま・在籍は作られない")
    void 非公開チームの申請承認は拒否() {
        Long application = pendingApplication();

        BusinessException e = business(() -> supporterService.approve(application, "TEAM", fixture.teamId));

        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        assertThat(e.getHttpStatusOverride()).as("既定の 403（ERROR_CODE_STATUS_MAP）").isNull();
        assertThat(statusOf(application)).isEqualTo("PENDING");
        assertThat(activeSupporterCount(applicantOf(application))).isZero();
    }

    @Test
    @DisplayName("AC-0159-43: 非公開チームで一括承認に 3 件を渡すと 403 TEAM_073 で 1 件も承認されない")
    void 非公開チームの一括承認は全体を拒否() {
        List<Long> ids = List.of(pendingApplication(), pendingApplication(), pendingApplication());
        BulkApproveRequest request = bulkRequest(ids);

        BusinessException e = business(() -> supporterService.bulkApprove(request, "TEAM", fixture.teamId));

        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        for (Long id : ids) {
            assertThat(statusOf(id)).as("application=%s", id).isEqualTo("PENDING");
            assertThat(activeSupporterCount(applicantOf(id))).isZero();
        }
    }

    @Test
    @DisplayName("AC-0159-46: 公開チームでは承認・一括承認で SUPPORTER として在籍する（本戦役前と同一）")
    void 公開チームの承認と一括承認は従来どおり() {
        setVisibility("SUPPORTERS_AND_ABOVE");
        Long single = pendingApplication();
        List<Long> bulk = List.of(pendingApplication(), pendingApplication(), pendingApplication());
        BulkApproveRequest request = bulkRequest(bulk);

        supporterService.approve(single, "TEAM", fixture.teamId);
        supporterService.bulkApprove(request, "TEAM", fixture.teamId);

        for (Long id : List.of(single, bulk.get(0), bulk.get(1), bulk.get(2))) {
            assertThat(statusOf(id)).as("application=%s", id).isEqualTo("APPROVED");
            assertThat(activeSupporterCount(applicantOf(id))).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("AC-0159-44: 非公開チームへのフォローは従来どおり 403 VISIBILITY_001（入口の可視性ゲートで止まる）")
    void 非公開チームへのフォローは従来どおり() {
        for (Actor outsider : List.of(Actor.OUTSIDER, Actor.OTHER_TEAM_MEMBER, Actor.OTHER_ORG_MEMBER)) {
            BusinessException e = business(() -> contentVisibilityChecker.assertCanView(
                    ReferenceType.TEAM, fixture.teamId, fixture.user(outsider)));
            assertThat(e.getErrorCode()).as("actor=%s", outsider).isEqualTo(VisibilityErrorCode.VISIBILITY_001);
        }
    }

    @Test
    @DisplayName("AC-0159-44: 可視性ゲートを越えて呼ばれても、非公開チームへの自己フォローは SUPPORTER 在籍・PENDING 申請を作らない（403 TEAM_073）")
    void 非公開チームへの自己フォローはサービスでも拒否() {
        Long outsider = fixture.user(Actor.OUTSIDER);

        BusinessException e = business(() -> supporterService.follow(outsider, "TEAM", fixture.teamId));

        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_073);
        assertThat(activeSupporterCount(outsider)).isZero();
        Long applications = tx.execute(t -> ((Number) em.createNativeQuery("SELECT COUNT(*) FROM supporter_applications "
                        + "WHERE scope_type = 'TEAM' AND scope_id = :t AND user_id = :u")
                .setParameter("t", fixture.teamId).setParameter("u", outsider).getSingleResult()).longValue());
        assertThat(applications).as("PENDING 申請も作らない").isZero();
    }

    @Test
    @DisplayName("AC-0159-46: 公開チームへの自己フォローは従来どおり即時承認される（自動承認が既定）")
    void 公開チームへのフォローは従来どおり() {
        setVisibility("PUBLIC");
        Long outsider = fixture.user(Actor.OUTSIDER);

        var response = supporterService.follow(outsider, "TEAM", fixture.teamId);

        assertThat(response.getData().status()).isEqualTo("APPROVED");
        assertThat(activeSupporterCount(outsider)).isEqualTo(1);
    }
}
