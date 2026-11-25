package com.mannschaft.app.common;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261124-0159 PR-01: 親チーム上限の実 MySQL 結合テスト（AC-0159-03 / 05 / 06 / 07）。
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 親チーム上限の結合テスト（AC-0159-03/05/06/07）")
class MembersOnlyTeamCapIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final String[] LEVELS = {"PUBLIC", "GUESTS_AND_ABOVE", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE"};

    @Autowired private AccessControlService accessControlService;
    @Autowired private ContentVisibilityChecker contentVisibilityChecker;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private ScopeContentFixture fixture;
    private final List<Long> extraTeams = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        fixture = tx.execute(t -> ScopeContentFixture.create(em));
    }

    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(t -> {
            if (!extraTeams.isEmpty()) {
                em.createNativeQuery("DELETE FROM photo_albums WHERE team_id IN (:ids)").setParameter("ids", extraTeams)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE scope_type = 'TEAM' AND scope_id IN (:ids)")
                        .setParameter("ids", extraTeams).executeUpdate();
                em.createNativeQuery("DELETE FROM teams WHERE id IN (:ids)").setParameter("ids", extraTeams)
                        .executeUpdate();
            }
            fixture.delete(em);
        });
    }

    private void setVisibility(String visibility) {
        tx.executeWithoutResult(t -> ScopeContentFixture.setTeamVisibility(em, fixture.teamId, visibility));
    }

    @Test
    @DisplayName("V-0159-06-1 / V-0159-06-2: 公開→非公開・非公開→公開の直後の次の呼び出しから上限が効く・外れる（キャッシュで旧判定が残らない）")
    void 公開範囲の変更が即時に効く() {
        Long supporter = fixture.user(Actor.SUPPORTER);
        setVisibility("SUPPORTERS_AND_ABOVE");
        assertThat(accessControlService.canAccessScopeContent(supporter, fixture.teamId, "TEAM")).isTrue();
        assertThat(contentVisibilityChecker.canView(ReferenceType.TEAM, fixture.teamId, supporter)).isTrue();

        setVisibility("MEMBERS_AND_ABOVE");
        assertThat(accessControlService.canAccessScopeContent(supporter, fixture.teamId, "TEAM")).isFalse();
        assertThat(accessControlService.filterAccessibleTeamIds(supporter, List.of(fixture.teamId))).isEmpty();
        assertThat(contentVisibilityChecker.canView(ReferenceType.TEAM, fixture.teamId, supporter)).isFalse();

        setVisibility("SUPPORTERS_AND_ABOVE");
        assertThat(accessControlService.canAccessScopeContent(supporter, fixture.teamId, "TEAM")).isTrue();
        assertThat(accessControlService.filterAccessibleTeamIds(supporter, List.of(fixture.teamId)))
                .containsExactly(fixture.teamId);
    }

    @Test
    @DisplayName("AC-0159-05/07: checkMembership は非公開チームでも在籍だけで決まる（サポーター在籍は通り、SYSTEM_ADMIN は在籍なしで拒否のまま）")
    void checkMembershipは変わらない() {
        setVisibility("MEMBERS_AND_ABOVE");
        assertThatCode(() -> accessControlService.checkMembership(fixture.user(Actor.SUPPORTER), fixture.teamId, "TEAM"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> accessControlService.checkMembership(fixture.user(Actor.SYSTEM_ADMIN), fixture.teamId,
                "TEAM")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> accessControlService.checkScopeContentAccess(fixture.user(Actor.SUPPORTER),
                fixture.teamId, "TEAM"))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("AC-0159-03: filterAccessible に 50 チーム（4 段混在）のコンテンツ 500 件を渡しても問い合わせ本数は 1 件のときと同じ（非公開チームの判定は 1 本）")
    void 問い合わせ本数() {
        Long viewer = fixture.user(Actor.MEMBER);
        List<Long> albums = tx.execute(t -> {
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
                Long team = TeamOrgFixtureHelper.insertTeam(em, "本数試練" + suffix, "c59n-" + suffix);
                extraTeams.add(team);
                ScopeContentFixture.setTeamVisibility(em, team, LEVELS[i % 4]);
                if (i % 3 == 0) {
                    MembershipTestHelper.insertMembership(em, viewer, ScopeType.TEAM, team,
                            i % 2 == 0 ? RoleKind.MEMBER : RoleKind.SUPPORTER);
                }
                for (int j = 0; j < 10; j++) {
                    em.createNativeQuery("INSERT INTO photo_albums (team_id, title, visibility, allow_member_upload, "
                                    + "allow_download, photo_count, created_at, updated_at) "
                                    + "VALUES (:t, :title, 'ALL_MEMBERS', 0, 1, 0, NOW(), NOW())")
                            .setParameter("t", team).setParameter("title", "本数" + j).executeUpdate();
                }
            }
            em.flush();
            for (Object id : em.createNativeQuery("SELECT id FROM photo_albums WHERE team_id IN (:ids)")
                    .setParameter("ids", extraTeams).getResultList()) {
                ids.add(((Number) id).longValue());
            }
            return ids;
        });
        assertThat(albums).hasSize(500);

        long many = countStatements(() -> contentVisibilityChecker.filterAccessible(
                ReferenceType.PHOTO_ALBUM, albums, viewer));
        long one = countStatements(() -> contentVisibilityChecker.filterAccessible(
                ReferenceType.PHOTO_ALBUM, albums.subList(0, 1), viewer));
        assertThat(many).as("500 件・50 チームでも 1 件のときと同じ本数（チーム数・件数に比例しない）").isEqualTo(one);

        // 未ログイン: 本戦役前は射影 1 本だけ。非公開チームの判定 1 本が足されて 2 本になる（+1 本）。
        long anonymous = countStatements(() -> contentVisibilityChecker.filterAccessible(
                ReferenceType.PHOTO_ALBUM, albums, null));
        assertThat(anonymous).as("未ログイン: 射影 1 本＋非公開チーム判定 1 本").isEqualTo(2);
    }

    private long countStatements(Runnable action) {
        SessionFactory sf = em.getEntityManagerFactory().unwrap(SessionFactory.class);
        Statistics stats = sf.getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        action.run();
        return stats.getPrepareStatementCount();
    }
}
