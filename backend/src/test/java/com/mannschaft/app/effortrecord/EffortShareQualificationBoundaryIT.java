package com.mannschaft.app.effortrecord;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.effortrecord.entity.EffortTeamShareEntity;
import com.mannschaft.app.effortrecord.enums.EffortTeamShareState;
import com.mannschaft.app.effortrecord.repository.EffortTeamShareRepository;
import com.mannschaft.app.membership.domain.EffortShareDisposition;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.dto.EffortShareQualification;
import com.mannschaft.app.membership.dto.EffortShareQualificationQuery;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.event.RolePurgeScopeExecutor;
import com.mannschaft.app.timeline.entity.TimelineEffortSourceEntity;
import com.mannschaft.app.timeline.repository.TimelineEffortSourceRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * F06.4.1 PR-3a 試練: チーム共有資格の境界（AC-44・45・48・49 の BE 側、E14・E13、Codex P1 のロール境界、性能。CMP-261115-2145）。
 *
 * <p>資格の正本は TEAM 側の memberships（加入期間 ID = grantMembershipId）。
 * {@code MembershipService#getEffortShareQualificationsBatch} は PRIMARY・REQUIRES_NEW・READ_COMMITTED の独立 TX で
 * committed の最新状態を読み、呼出元の REPLICA hint・先に開いた REPEATABLE READ snapshot に引きずられない（データ設計 §8）。
 * 自己脱退・除名は endCause=EXPLICIT_MEMBERSHIP_END で REVOKED、退会の整理だけなら RETAINED_ACCOUNT_WITHDRAWAL、
 * 両方なら順序によらず REVOKED。明示終了のコミット後に {@code TeamEffortMembershipRevokedEvent} を AFTER_COMMIT で届ける。</p>
 *
 * <p>共有・TL ガード・検索・purge・Controller は PR-3b の範囲なので扱わない。実 JWT・実 Security・実 MySQL。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3a チーム共有資格の境界（AC-44/45/48/49・ロール境界・性能）")
class EffortShareQualificationBoundaryIT extends EffortShareItSupport {

    @Autowired private RolePurgeScopeExecutor rolePurgeScopeExecutor;
    @Autowired private EffortTeamShareRepository teamShares;
    @Autowired private TimelineEffortSourceRepository timelineSources;

    private EffortShareQualification qualify(Long teamId, Long userId, Long grantMembershipId) {
        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(
                List.of(new EffortShareQualificationQuery(teamId, userId, grantMembershipId)));
        assertThat(result).hasSize(1);
        EffortShareQualification q = result.get(0);
        assertThat(q.teamId()).isEqualTo(teamId);
        assertThat(q.userId()).isEqualTo(userId);
        assertThat(q.grantMembershipId()).isEqualTo(grantMembershipId);
        return q;
    }

    private void assertSingleRevokedAfterCommit(Long teamId, Long userId, Long endedMembershipId) {
        assertThat(revokedOutsideTx).as("失効イベントは終了 TX の中で発行し、コミット後に届ける").isEmpty();
        assertThat(revokedEvents).as("明示終了のコミット後に失効イベントがちょうど1件").hasSize(1);
        CapturedRevoke captured = revokedEvents.get(0);
        assertThat(captured.event().teamId()).isEqualTo(teamId);
        assertThat(captured.event().userId()).isEqualTo(userId);
        assertThat(captured.event().endedMembershipId()).isEqualTo(endedMembershipId);
        assertThat(captured.committedLeftAtSet()).as("届いた時点で在籍終了がコミット済み").isTrue();
        assertThat(captured.committedDisposition()).as("届いた時点で REVOKED がコミット済み").isEqualTo("REVOKED");
    }

    // ───────────────────────────── AC-44 / AC-45: 自己脱退・除名 ─────────────────────────────

    @Test
    @DisplayName("AC-44: 自己脱退のコミット直後から資格なし（disposition=REVOKED）、コミット後に失効イベントが1件届く")
    void AC44_自己脱退の直後から資格なし() throws Exception {
        Long team = team("資格-自己脱退");
        Long admin = user("資格管理者");
        Long owner = user("資格本人");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);
        assertThat(qualify(team, owner, m1).isCurrentEligible()).as("脱退前は資格あり").isTrue();

        expect(leaveTeamApi(owner, team), 204);

        EffortShareQualification after = qualify(team, owner, m1);
        assertThat(after.isCurrentEligible()).isFalse();
        assertThat(after.disposition()).isEqualTo(EffortShareDisposition.REVOKED);
        assertSingleRevokedAfterCommit(team, owner, m1);
    }

    @Test
    @DisplayName("AC-45: 管理者による除名（RoleService.removeMember）でも資格なし・disposition=REVOKED・失効イベント1件")
    void AC45_除名の直後から資格なし() throws Exception {
        Long team = team("資格-除名");
        Long admin = user("資格管理者");
        Long owner = user("資格除名対象");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);

        expect(removeMemberApi(admin, team, owner), 204);

        EffortShareQualification after = qualify(team, owner, m1);
        assertThat(after.isCurrentEligible()).isFalse();
        assertThat(after.disposition()).isEqualTo(EffortShareDisposition.REVOKED);
        assertSingleRevokedAfterCommit(team, owner, m1);
    }

    @Test
    @DisplayName("AC-46(BE): m1 で脱退して m2 で再加入しても m1 は資格なしのまま、m2 だけが資格あり（加入期間 ID は別許可）")
    void AC46_再加入しても旧加入期間は資格なし() throws Exception {
        Long team = team("資格-再加入");
        Long admin = user("資格管理者");
        Long owner = user("資格再加入者");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);
        expect(leaveTeamApi(owner, team), 204);
        Long m2 = rejoin(owner, team);
        assertThat(m2).as("再加入は新しい行").isNotEqualTo(m1);

        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(team, owner, m1),
                new EffortShareQualificationQuery(team, owner, m2)));

        assertThat(result).hasSize(2);
        EffortShareQualification old = result.stream().filter(q -> q.grantMembershipId().equals(m1)).findFirst().orElseThrow();
        EffortShareQualification now = result.stream().filter(q -> q.grantMembershipId().equals(m2)).findFirst().orElseThrow();
        assertThat(old.isCurrentEligible()).isFalse();
        assertThat(old.disposition()).isEqualTo(EffortShareDisposition.REVOKED);
        assertThat(now.isCurrentEligible()).isTrue();
        assertThat(now.disposition()).isNull();
    }

    // ───────────────────────────── AC-48: PRIMARY・REQUIRES_NEW・READ_COMMITTED ─────────────────────────────

    @Test
    @DisplayName("AC-48: 先に開いた readOnly の REPEATABLE READ snapshot では在籍に見えても、別スレッドの脱退コミット後の判定は資格なし（REPLICA 実体は試験環境に無いので、PRIMARY への接続は次の TX 宣言試験で固定する）")
    void AC48_RRsnapshotに引きずられない() throws Exception {
        Long team = team("資格-RR");
        Long admin = user("資格管理者");
        Long owner = user("資格RR本人");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);

        TransactionTemplate outer = new TransactionTemplate(txManager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.setReadOnly(true);
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            EffortShareQualification judged = outer.execute(status -> {
                // snapshot を確立する（この TX からは在籍中に見える）。
                assertThat(snapshotLeft(m1)).isFalse();
                try {
                    int leaveStatus = other.submit(() -> leaveTeamApi(owner, team).getStatus()).get(30, TimeUnit.SECONDS);
                    assertThat(leaveStatus).isEqualTo(204);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(snapshotLeft(m1)).as("外側の RR snapshot はまだ在籍に見える（試験の前提）").isFalse();
                return qualify(team, owner, m1);
            });
            assertThat(judged.isCurrentEligible()).as("PRIMARY の新しい TX（READ_COMMITTED）で判定して deny").isFalse();
            assertThat(judged.disposition()).isEqualTo(EffortShareDisposition.REVOKED);
        } finally {
            other.shutdownNow();
        }
    }

    private boolean snapshotLeft(Long membershipId) {
        Object left = em.createNativeQuery("SELECT left_at FROM memberships WHERE id = :id")
                .setParameter("id", membershipId).getSingleResult();
        return left != null;
    }

    @Test
    @DisplayName("AC-48: 資格 batch は proxy 経由の独立 TX（REQUIRES_NEW・readOnly=false で PRIMARY・READ_COMMITTED）で宣言されている")
    void AC48_資格batchのTX境界() throws Exception {
        Method method = MembershipService.class.getMethod("getEffortShareQualificationsBatch", Collection.class);
        Transactional tx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        assertThat(tx).as("メソッド自身に @Transactional を宣言する（クラス既定の readOnly を継がない）").isNotNull();
        assertThat(method.isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(tx.isolation()).isEqualTo(Isolation.READ_COMMITTED);
        assertThat(tx.readOnly()).as("readOnly=true は ReplicaRoutingAspect で REPLICA へ行く").isFalse();
    }

    // ───────────────────────────── AC-49: 退会の整理と明示脱退 ─────────────────────────────

    @Test
    @DisplayName("AC-49: 退会の整理だけなら disposition=RETAINED_ACCOUNT_WITHDRAWAL、現在の資格はなく、失効イベントは出ない")
    void AC49_退会だけならRETAINED() {
        Long team = team("資格-退会のみ");
        Long admin = user("資格管理者");
        Long owner = user("資格退会者");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);

        rolePurgeScopeExecutor.processScope(owner, team, "TEAM", false, UUID.randomUUID());

        EffortShareQualification after = qualify(team, owner, m1);
        assertThat(after.isCurrentEligible()).isFalse();
        assertThat(after.disposition()).isEqualTo(EffortShareDisposition.RETAINED_ACCOUNT_WITHDRAWAL);
        assertThat(revokedEvents).isEmpty();
        assertThat(revokedOutsideTx).isEmpty();
    }

    @Test
    @DisplayName("AC-49: 明示脱退 → 退会の整理の順では REVOKED のまま（退会の整理が覆さない）")
    void AC49_明示脱退のあと退会整理でもREVOKED() throws Exception {
        Long team = team("資格-脱退後退会");
        Long admin = user("資格管理者");
        Long owner = user("資格脱退後退会者");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);

        expect(leaveTeamApi(owner, team), 204);
        // 既に在籍が無いので既存挙動どおり ROLE_001 で終わりうる。どちらでも disposition は REVOKED のまま。
        Throwable purge = catchThrowable(() ->
                rolePurgeScopeExecutor.processScope(owner, team, "TEAM", false, UUID.randomUUID()));
        if (purge != null) {
            assertThat(purge).isInstanceOf(BusinessException.class);
        }

        assertThat(disposition(m1)).isEqualTo(EffortShareDisposition.REVOKED);
        assertThat(qualify(team, owner, m1).disposition()).isEqualTo(EffortShareDisposition.REVOKED);
    }

    @Test
    @DisplayName("AC-49: 退会の整理 → 同じ加入期間の明示終了の順では REVOKED へ上書きし、left_at は動かさない")
    void AC49_退会整理のあと明示終了でREVOKEDへ上書き() {
        Long team = team("資格-退会後脱退");
        Long admin = user("資格管理者");
        Long owner = user("資格退会後脱退者");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);
        rolePurgeScopeExecutor.processScope(owner, team, "TEAM", false, UUID.randomUUID());
        assertThat(disposition(m1)).isEqualTo(EffortShareDisposition.RETAINED_ACCOUNT_WITHDRAWAL);
        Object leftAtBefore = committedMembership(m1).get("left_at");

        MembershipLeaveRequest explicit = new MembershipLeaveRequest();
        explicit.setLeaveReason(LeaveReason.SELF);
        catchThrowable(() -> membershipService.leave(m1, explicit));

        assertThat(disposition(m1)).as("明示終了が REVOKED を優先する").isEqualTo(EffortShareDisposition.REVOKED);
        assertThat(committedMembership(m1).get("left_at")).as("終了時刻は退会整理のまま").isEqualTo(leftAtBefore);
    }

    // ───────────────────────────── Codex P1: ロールの境界 ─────────────────────────────

    @Test
    @DisplayName("Codex P1（ロール境界）: SUPPORTER でも直接所属していれば資格あり")
    void ロール境界_SUPPORTERは直接所属なら資格あり() {
        Long team = team("資格-応援者");
        Long admin = user("資格管理者");
        Long fan = user("資格応援者");
        teamAdmin(admin, team);
        supporter(fan, team);
        Long m1 = activeMembershipId(fan, team);

        EffortShareQualification q = qualify(team, fan, m1);
        assertThat(q.isCurrentEligible()).isTrue();
        assertThat(q.disposition()).isNull();
    }

    @Test
    @DisplayName("Codex P1（ロール境界）: 所属していない SYSTEM_ADMIN は資格なし（別チームの加入期間 ID や架空 ID を渡しても回り込めない）")
    void ロール境界_非所属SYSTEM_ADMINは資格なし() {
        Long team = team("資格-SYSADMIN対象");
        Long otherTeam = team("資格-SYSADMIN所属先");
        Long admin = user("資格管理者");
        Long sysadmin = platformSystemAdmin("資格シスアド");
        teamAdmin(admin, team);
        member(sysadmin, otherTeam);
        Long otherMembership = activeMembershipId(sysadmin, otherTeam);
        Long adminMembership = activeMembershipId(admin, team);

        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(team, sysadmin, otherMembership),
                new EffortShareQualificationQuery(team, sysadmin, adminMembership),
                new EffortShareQualificationQuery(team, sysadmin, Long.MAX_VALUE)));

        assertThat(result).hasSize(3).allSatisfy(q -> assertThat(q.isCurrentEligible()).isFalse());
    }

    @Test
    @DisplayName("Codex P1（ロール境界）: 直接所属を保ったままのロール変更（MEMBER→ADMIN→MEMBER）では加入期間 ID・資格・disposition が変わらず失効イベントも出ない")
    void ロール境界_ロール変更では資格を失わない() throws Exception {
        Long team = team("資格-ロール変更");
        Long admin = user("資格管理者");
        Long owner = user("資格ロール変更者");
        teamAdmin(admin, team);
        invitedMember(admin, owner, team);
        Long m1 = activeMembershipId(owner, team);

        expect(changeRoleApi(admin, team, owner, "ADMIN"), 200);
        assertThat(activeMembershipId(owner, team)).isEqualTo(m1);
        EffortShareQualification promoted = qualify(team, owner, m1);
        assertThat(promoted.isCurrentEligible()).isTrue();
        assertThat(promoted.disposition()).isNull();

        expect(changeRoleApi(admin, team, owner, "MEMBER"), 200);
        assertThat(activeMembershipId(owner, team)).isEqualTo(m1);
        EffortShareQualification demoted = qualify(team, owner, m1);
        assertThat(demoted.isCurrentEligible()).isTrue();
        assertThat(demoted.disposition()).isNull();
        assertThat(revokedEvents).isEmpty();
        assertThat(revokedOutsideTx).isEmpty();
    }

    @Test
    @DisplayName("資格境界: 加入期間 ID が別人・別チームのものなら資格なし（ID だけでは許可しない）")
    void 加入期間IDが別人別チームなら資格なし() {
        Long team = team("資格-ID不一致");
        Long otherTeam = team("資格-ID不一致の別チーム");
        Long admin = user("資格管理者");
        Long owner = user("資格本人");
        Long neighbor = user("資格隣人");
        teamAdmin(admin, team);
        member(owner, team);
        member(neighbor, team);
        member(owner, otherTeam);

        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(team, owner, activeMembershipId(neighbor, team)),
                new EffortShareQualificationQuery(team, owner, activeMembershipId(owner, otherTeam)),
                new EffortShareQualificationQuery(otherTeam, owner, activeMembershipId(owner, team))));

        assertThat(result).hasSize(3).allSatisfy(q -> assertThat(q.isCurrentEligible()).isFalse());
    }

    @Test
    @DisplayName("資格境界: 加入期間 ID が全件 null でもバッチは落ちず、全件を資格なし（disposition なし）で返す")
    void 加入期間IDが全件nullなら全件資格なし() {
        Long team = team("資格-ID全件null");
        Long admin = user("資格管理者");
        Long owner = user("資格本人");
        teamAdmin(admin, team);
        member(owner, team);

        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(team, owner, null),
                new EffortShareQualificationQuery(team, admin, null)));

        assertThat(result).hasSize(2).allSatisfy(q -> {
            assertThat(q.grantMembershipId()).isNull();
            assertThat(q.isCurrentEligible()).isFalse();
            assertThat(q.disposition()).isNull();
        });
    }

    @Test
    @DisplayName("資格境界: 加入期間 ID が一部 null なら null の組だけ資格なしで、他の組は通常どおり判定する")
    void 加入期間IDが一部nullならその組だけ資格なし() {
        Long team = team("資格-ID一部null");
        Long admin = user("資格管理者");
        Long owner = user("資格本人");
        teamAdmin(admin, team);
        member(owner, team);
        Long m1 = activeMembershipId(owner, team);

        List<EffortShareQualification> result = membershipService.getEffortShareQualificationsBatch(List.of(
                new EffortShareQualificationQuery(team, owner, null),
                new EffortShareQualificationQuery(team, owner, m1)));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).grantMembershipId()).isNull();
        assertThat(result.get(0).isCurrentEligible()).isFalse();
        assertThat(result.get(0).disposition()).isNull();
        assertThat(result.get(1).grantMembershipId()).isEqualTo(m1);
        assertThat(result.get(1).isCurrentEligible()).isTrue();
        assertThat(result.get(1).disposition()).isNull();
    }

    // ───────────────────────────── 性能 ─────────────────────────────

    @Test
    @DisplayName("性能: 1 回の batch で N 人を判定してもクエリ数は N に比例しない（1 人と 20 人で同じ）。空の入力は空")
    void 性能_クエリ数はNに比例しない() {
        Long team = team("資格-性能");
        Long admin = user("資格管理者");
        teamAdmin(admin, team);
        List<EffortShareQualificationQuery> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Long u = user("資格性能" + i);
            member(u, team);
            many.add(new EffortShareQualificationQuery(team, u, activeMembershipId(u, team)));
        }

        assertThat(membershipService.getEffortShareQualificationsBatch(List.of())).isEmpty();

        Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true);
        stats.clear();
        List<EffortShareQualification> one = membershipService.getEffortShareQualificationsBatch(many.subList(0, 1));
        long oneCount = stats.getPrepareStatementCount();
        stats.clear();
        List<EffortShareQualification> twenty = membershipService.getEffortShareQualificationsBatch(many);
        long twentyCount = stats.getPrepareStatementCount();

        assertThat(one).hasSize(1);
        assertThat(twenty).hasSize(20).allSatisfy(q -> assertThat(q.isCurrentEligible()).isTrue());
        assertThat(oneCount).as("1 人でも照会する").isPositive();
        assertThat(twentyCount).as("20 人でも 1 人と同じクエリ数（N+1 にしない）").isEqualTo(oneCount);
    }

    // ───────────────────────────── AC-57（Spring 側）: 新規 Entity の主キー ─────────────────────────────

    @Test
    @DisplayName("AC-57: effort_team_shares・timeline_effort_sources の主キーは UUIDv7（version nibble=7・variant=2）で、チーム側の行は team_id だけで束ねる")
    void AC57_新規Entityの主キーはUUIDv7() {
        Long team = team("資格-UUIDv7");
        EffortTeamShareEntity share = tx(() -> teamShares.saveAndFlush(EffortTeamShareEntity.builder()
                .teamId(team).ownerUserId(1L).sourceRecordId(UUID.randomUUID()).sourceVersion(0L)
                .grantMembershipId(1L).shareVersion(0L).definitionId(STUDY).recordedOn(LocalDate.of(2026, 11, 1))
                .projectionJson("{}").projectionHash("a".repeat(64)).state(EffortTeamShareState.PENDING).build()));
        TimelineEffortSourceEntity source = tx(() -> timelineSources.saveAndFlush(TimelineEffortSourceEntity.builder()
                .teamId(team).timelinePostId(Long.MAX_VALUE - team).shareUuid(share.getId()).grantMembershipId(1L)
                .shareVersion(0L).contentRevision(1L).build()));
        try {
            for (UUID id : List.of(share.getId(), source.getId())) {
                assertThat(id.version()).isEqualTo(7);
                assertThat(id.variant()).isEqualTo(2);
            }
        } finally {
            tx(() -> {
                timelineSources.deleteById(source.getId());
                teamShares.deleteById(share.getId());
                return null;
            });
        }
    }
}
