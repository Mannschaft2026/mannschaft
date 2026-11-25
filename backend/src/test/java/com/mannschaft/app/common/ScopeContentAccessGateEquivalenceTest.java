package com.mannschaft.app.common;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-01: 門と Resolver の等価性の番人（F00 §20.6 {@code ScopeContentAccessGateEquivalenceTest}・
 * AC-0159-04 / 07 / 09・AC-0159-25 の骨組み）。
 *
 * <p>非公開チームで、閲覧者ロールごとに門（{@link ScopeContentAccessGate#canAccessScopeContent}）と
 * Resolver（{@code canView(TEAM, teamId)}＝チーム詳細の正準判定）の結果が一致することを、実 MySQL で確かめる。
 * SYSTEM_ADMIN は既存挙動どおりの差（門は在籍を見るため false・Resolver は高速パスで true）を許可リストで明示する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 門と Resolver の等価性（非公開チーム）")
class ScopeContentAccessGateEquivalenceTest extends AbstractMySqlIntegrationTest {

    /** 既存挙動どおりの差を許す区分（理由: §20.2 規則 6・SYSTEM_ADMIN は在籍を持たず門は checkMembership と同じく拒否）。 */
    private static final Set<Actor> KNOWN_DIFFERENCE = EnumSet.of(Actor.SYSTEM_ADMIN);

    @Autowired private AccessControlService accessControlService;
    @Autowired private ScopeContentAccessGate scopeContentAccessGate;
    @Autowired private ContentVisibilityChecker contentVisibilityChecker;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private ScopeContentFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TransactionTemplate(transactionManager).execute(t -> ScopeContentFixture.create(em));
    }

    @AfterEach
    void tearDown() {
        if (fixture != null) {
            new TransactionTemplate(transactionManager).executeWithoutResult(t -> fixture.delete(em));
        }
    }

    @Test
    @DisplayName("V-0159-04-1 / V-0159-04-2: 非公開チームで門と Resolver が全区分一致し、MEMBER 以上だけが真（SYSTEM_ADMIN の差だけ許可リスト）")
    void 門とResolverが一致する() {
        for (Actor actor : Actor.values()) {
            Long userId = fixture.user(actor);
            boolean gate = scopeContentAccessGate.canAccessScopeContent(userId, fixture.teamId, "TEAM");
            boolean resolver = contentVisibilityChecker.canView(ReferenceType.TEAM, fixture.teamId, userId);
            if (KNOWN_DIFFERENCE.contains(actor)) {
                assertThat(gate).as("SYSTEM_ADMIN の門（在籍なし）").isFalse();
                assertThat(resolver).as("SYSTEM_ADMIN の Resolver（高速パス）").isTrue();
                continue;
            }
            boolean expected = EnumSet.of(Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.MEMBER).contains(actor);
            assertThat(gate).as("門 %s", actor).isEqualTo(expected);
            assertThat(resolver).as("Resolver %s", actor).isEqualTo(gate);
        }
        assertThat(scopeContentAccessGate.canAccessScopeContent(null, fixture.teamId, "TEAM")).isFalse();
        assertThat(contentVisibilityChecker.canView(ReferenceType.TEAM, fixture.teamId, null)).isFalse();
    }

    @Test
    @DisplayName("AC-0159-09: 在籍しない親組織 ADMIN は部外者と同一（門は COMMON_002・Resolver は不可）")
    void 親組織ADMINは部外者と同一() {
        Long parentAdmin = fixture.user(Actor.PARENT_ORG_ADMIN);
        Long outsider = fixture.user(Actor.OUTSIDER);
        assertThat(scopeContentAccessGate.canAccessScopeContent(parentAdmin, fixture.teamId, "TEAM"))
                .isEqualTo(scopeContentAccessGate.canAccessScopeContent(outsider, fixture.teamId, "TEAM")).isFalse();
        assertThat(scopeContentAccessGate.filterAccessibleTeamIds(parentAdmin, Set.of(fixture.teamId))).isEmpty();
        assertThat(scopeContentAccessGate.filterAccessibleTeamIds(fixture.user(Actor.MEMBER), Set.of(fixture.teamId)))
                .containsExactly(fixture.teamId);
        assertThat(scopeContentAccessGate.filterAccessibleTeamIds(fixture.user(Actor.SUPPORTER), Set.of(fixture.teamId)))
                .isEmpty();
    }
}
