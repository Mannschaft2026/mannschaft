package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.service.ActivityStatsService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261124-0159 PR-A1b: 活動統計 {@code GET /api/v1/activities/stats}・設定 {@code /stats/settings} の契約を、
 * 実 HTTP 境界と実 MySQL で固定する受け入れテスト（F06.4 §15.2〜§15.4・§15.7）。
 *
 * <p>集計期間は既定値に頼らず明示する（既定値の決め方 AC-0159-75 は固定クロックの単体テスト
 * {@code ActivityStatsServiceTest} で固定する）。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 活動統計の契約（AC-0159-70〜74・76〜79・82・83・101・102・105）")
class ActivityStatsContractIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private ActivityStatsService statsService;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private ScopeContentFixture fixture;
    private final List<Long> extraUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(t -> fixture = ScopeContentFixture.create(em));
    }

    @AfterEach
    void tearDown() {
        if (fixture == null) {
            return;
        }
        tx.executeWithoutResult(t -> {
            for (Object[] scope : new Object[][]{{"TEAM", fixture.teamId}, {"TEAM", fixture.otherTeamId},
                    {"ORGANIZATION", fixture.parentOrgId}}) {
                String inResults = " IN (SELECT id FROM activity_results WHERE scope_type = :type AND scope_id = :id)";
                em.createNativeQuery("DELETE FROM activity_participants WHERE activity_result_id" + inResults)
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_results WHERE scope_type = :type AND scope_id = :id")
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_templates WHERE scope_type = :type AND scope_id = :id")
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
                em.createNativeQuery("DELETE FROM activity_stats_settings WHERE scope_type = :type AND scope_id = :id")
                        .setParameter("type", scope[0]).setParameter("id", scope[1]).executeUpdate();
            }
            if (!extraUsers.isEmpty()) {
                em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)").setParameter("ids", extraUsers)
                        .executeUpdate();
                em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)").setParameter("ids", extraUsers)
                        .executeUpdate();
            }
            em.createNativeQuery("UPDATE users SET deleted_at = NULL WHERE id IN (:ids)")
                    .setParameter("ids", fixture.users.values()).executeUpdate();
            fixture.delete(em);
        });
    }

    // ───────── AC-0159-70・71 閲覧資格と全員共通の数字 ─────────

    @Test
    @DisplayName("AC-0159-70: MEMBER・DEPUTY_ADMIN・ADMIN の本文は上位参加者を除き完全一致し、設定 ON なら全体が完全一致する")
    void 権限ありは同じ数字を見る() throws Exception {
        record(FROM.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false,
                fixture.user(Actor.MEMBER), fixture.user(Actor.ADMIN));
        record(FROM.plusDays(2), ActivityStatus.PUBLISHED, ActivityVisibility.MEMBERS_ONLY, null, false,
                fixture.user(Actor.MEMBER));
        record(FROM.plusDays(3), ActivityStatus.DRAFT, ActivityVisibility.PUBLIC, null, false);

        JsonNode member = data(stats(Actor.MEMBER, FROM, TO, null, null));
        JsonNode deputy = data(stats(Actor.DEPUTY_ADMIN, FROM, TO, null, null));
        JsonNode admin = data(stats(Actor.ADMIN, FROM, TO, null, null));
        assertThat(member.path("topParticipantsVisible").asBoolean()).isFalse();
        assertThat(member.path("topParticipants").isNull()).isTrue();
        assertThat(withoutTop(member)).isEqualTo(withoutTop(deputy)).isEqualTo(withoutTop(admin));
        assertThat(deputy).isEqualTo(admin);

        putSettings(Actor.ADMIN, true, null);
        JsonNode memberOn = data(stats(Actor.MEMBER, FROM, TO, null, null));
        assertThat(memberOn).isEqualTo(data(stats(Actor.ADMIN, FROM, TO, null, null)))
                .isEqualTo(data(stats(Actor.DEPUTY_ADMIN, FROM, TO, null, null)));
    }

    @Test
    @DisplayName("AC-0159-71: サポーター・GUEST・部外者・他チーム・他組織は非公開チームでも公開チームでも部外者と同一の 403 COMMON_002。組織のサポーター・委員会も 403")
    void 権限なしは部外者と同一の403() throws Exception {
        record(FROM.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, fixture.user(Actor.MEMBER));
        for (String visibility : List.of("MEMBERS_AND_ABOVE", "SUPPORTERS_AND_ABOVE")) {
            tx.executeWithoutResult(t -> ScopeContentFixture.setTeamVisibility(em, fixture.teamId, visibility));
            MvcResult outsider = stats(Actor.OUTSIDER, FROM, TO, null, null);
            assertThat(outsider.getResponse().getStatus()).isEqualTo(403);
            assertThat(code(outsider)).isEqualTo("COMMON_002");
            for (Actor actor : List.of(Actor.SUPPORTER, Actor.GUEST, Actor.OTHER_TEAM_MEMBER, Actor.OTHER_ORG_MEMBER,
                    Actor.PARENT_ORG_ADMIN)) {
                MvcResult denied = stats(actor, FROM, TO, null, null);
                assertThat(denied.getResponse().getStatus()).as("%s %s", visibility, actor).isEqualTo(403);
                assertThat(code(denied)).as("%s %s", visibility, actor).isEqualTo(code(outsider));
                assertThat(message(denied)).as("%s %s", visibility, actor).isEqualTo(message(outsider));
            }
        }
        // 組織スコープ: 在籍サポーターは 403、在籍 ADMIN は 200
        Long orgSupporter = extraUser(0);
        tx.executeWithoutResult(t -> MembershipTestHelper.insertMembership(em, orgSupporter, ScopeType.ORGANIZATION,
                fixture.parentOrgId, RoleKind.SUPPORTER));
        MvcResult orgDenied = perform(statsRequest("ORGANIZATION", fixture.parentOrgId, FROM, TO), orgSupporter);
        assertThat(orgDenied.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(orgDenied)).isEqualTo("COMMON_002");
        assertThat(perform(statsRequest("ORGANIZATION", fixture.parentOrgId, FROM, TO),
                fixture.user(Actor.PARENT_ORG_ADMIN)).getResponse().getStatus()).isEqualTo(200);
        // 委員会スコープは委員会のメンバーでも 403（既存挙動）
        MvcResult committee = perform(statsRequest("COMMITTEE", fixture.teamId, FROM, TO), fixture.user(Actor.ADMIN));
        assertThat(committee.getResponse().getStatus()).isEqualTo(403);
        assertThat(code(committee)).isEqualTo("COMMON_002");
    }

    // ───────── AC-0159-72〜74 数える対象・期間・集計単位 ─────────

    @Test
    @DisplayName("AC-0159-72: PUBLISHED の PUBLIC 2・MEMBERS_ONLY 3・自動生成 2 だけを数え（DRAFT 4・削除済み 1・自動生成 DRAFT 1 は数えない）、DRAFT の作成者が見ても 7")
    void 数えるのはPUBLISHEDだけ() throws Exception {
        LocalDate day = FROM.plusDays(4);
        Long member = fixture.user(Actor.MEMBER);
        for (int i = 0; i < 2; i++) record(day, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false);
        for (int i = 0; i < 3; i++) record(day, ActivityStatus.PUBLISHED, ActivityVisibility.MEMBERS_ONLY, null, false);
        recordBy(member, day, ActivityStatus.DRAFT, false);
        recordBy(member, day, ActivityStatus.DRAFT, false);
        recordBy(fixture.user(Actor.ADMIN), day, ActivityStatus.DRAFT, false);
        recordBy(fixture.user(Actor.DEPUTY_ADMIN), day, ActivityStatus.DRAFT, false);
        Long deleted = record(day, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false);
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE activity_results SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", deleted).executeUpdate());
        record(day, ActivityStatus.PUBLISHED, ActivityVisibility.MEMBERS_ONLY, null, true);
        record(day, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, true);
        recordBy(fixture.user(Actor.ADMIN), day, ActivityStatus.DRAFT, true);

        assertThat(data(stats(Actor.MEMBER, FROM, TO, null, null)).path("totalActivities").asLong()).isEqualTo(7);
        assertThat(data(stats(Actor.ADMIN, FROM, TO, null, null)).path("totalActivities").asLong()).isEqualTo(7);
    }

    @Test
    @DisplayName("AC-0159-73: date_from 当日・date_to 当日は含み、前日・翌日は総数・テンプレート別・推移・上位参加者のどれにも入らない")
    void 期間の境界() throws Exception {
        Long template = template(fixture.teamId, "境界の型");
        Long admin = fixture.user(Actor.ADMIN);
        Long member = fixture.user(Actor.MEMBER);
        record(FROM.minusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, template, false, member);
        record(FROM, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, template, false, admin);
        record(TO, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, template, false, admin);
        record(TO.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, template, false, member);

        JsonNode stats = data(stats(Actor.ADMIN, FROM, TO, null, "MONTH"));
        assertThat(stats.path("totalActivities").asLong()).isEqualTo(2);
        assertThat(stats.path("byTemplate").get(0).path("count").asLong()).isEqualTo(2);
        assertThat(stats.path("byPeriod").size()).isEqualTo(1);
        assertThat(stats.path("byPeriod").get(0).path("count").asLong()).isEqualTo(2);
        assertThat(stats.path("topParticipants").size()).isEqualTo(1);
        assertThat(stats.path("topParticipants").get(0).path("userId").asLong()).isEqualTo(admin);
        assertThat(stats.path("topParticipants").get(0).path("participationCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-0159-74: WEEK は月曜起点で日曜と翌月曜が別の週、MONTH・QUARTER・YEAR のラベル形式、記録の無いバケットは 0、省略時は MONTH")
    void 集計単位() throws Exception {
        record(LocalDate.of(2026, 3, 8), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false); // 日曜
        record(LocalDate.of(2026, 3, 9), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false); // 月曜

        JsonNode week = data(stats(Actor.ADMIN, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 22), null, "WEEK"));
        assertThat(periods(week)).containsExactly(
                "2026-02-23=2026-02-23:0", "2026-03-02=2026-03-02:1", "2026-03-09=2026-03-09:1", "2026-03-16=2026-03-16:0");
        JsonNode month = data(stats(Actor.ADMIN, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 30), null, "MONTH"));
        assertThat(periods(month)).containsExactly("2026-02-01=2026-02:0", "2026-03-01=2026-03:2", "2026-04-01=2026-04:0");
        JsonNode quarter = data(stats(Actor.ADMIN, LocalDate.of(2025, 12, 1), LocalDate.of(2026, 4, 1), null, "QUARTER"));
        assertThat(periods(quarter)).containsExactly("2025-10-01=2025-Q4:0", "2026-01-01=2026-Q1:2", "2026-04-01=2026-Q2:0");
        JsonNode year = data(stats(Actor.ADMIN, LocalDate.of(2025, 6, 1), LocalDate.of(2026, 6, 1), null, "YEAR"));
        assertThat(periods(year)).containsExactly("2025-01-01=2025:0", "2026-01-01=2026:2");
        JsonNode omitted = data(stats(Actor.ADMIN, FROM, TO, null, null));
        assertThat(omitted.path("granularity").asText()).isEqualTo("MONTH");
        assertThat(periods(omitted)).containsExactly("2026-03-01=2026-03:2");
    }

    // ───────── AC-0159-76・105 期間の検証 ─────────

    @Test
    @DisplayName("AC-0159-76: date_from > date_to は 400 ACTIVITY_024、3 年超は 400 ACTIVITY_025、granularity=DAY は 400 COMMON_001、date_from = date_to は 200")
    void 期間の検証() throws Exception {
        assertError(stats(Actor.ADMIN, TO, FROM, null, null), 400, "ACTIVITY_024");
        assertError(stats(Actor.ADMIN, FROM, FROM.plusDays(1096), null, null), 400, "ACTIVITY_025");
        assertError(stats(Actor.ADMIN, FROM, TO, null, "DAY"), 400, "COMMON_001");
        assertThat(stats(Actor.ADMIN, FROM, FROM, null, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-0159-105: 日数 = date_to − date_from + 1。1,095 日・1,096 日は 200、1,097 日は 400 ACTIVITY_025（うるう日をまたいでも同じ）")
    void 三年の境界() throws Exception {
        for (LocalDate from : List.of(LocalDate.of(2023, 1, 1), LocalDate.of(2027, 3, 1))) {
            assertThat(stats(Actor.ADMIN, from, from.plusDays(1094), null, "YEAR").getResponse().getStatus())
                    .as("%s から 1,095 日", from).isEqualTo(200);
            assertThat(stats(Actor.ADMIN, from, from.plusDays(1095), null, "YEAR").getResponse().getStatus())
                    .as("%s から 1,096 日", from).isEqualTo(200);
            assertError(stats(Actor.ADMIN, from, from.plusDays(1096), null, "YEAR"), 400, "ACTIVITY_025");
        }
    }

    // ───────── AC-0159-77〜79 上位参加者と設定 ─────────

    @Test
    @DisplayName("AC-0159-77: 設定 OFF で MEMBER は見えず、ADMIN・DEPUTY_ADMIN は受け取る。ON で MEMBER も受け取る。11 人以上でも 10 人、参加回数の降順・同数は userId 昇順")
    void 上位参加者() throws Exception {
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            people.add(extraUser(i + 1));
        }
        // people[0] は 3 回、people[1] は 2 回、残りは 1 回
        record(FROM.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false,
                people.toArray(Long[]::new));
        record(FROM.plusDays(2), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, people.get(0), people.get(1));
        record(FROM.plusDays(3), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, people.get(0));

        JsonNode memberOff = data(stats(Actor.MEMBER, FROM, TO, null, null));
        assertThat(memberOff.path("topParticipantsVisible").asBoolean()).isFalse();
        assertThat(memberOff.path("topParticipants").isNull()).isTrue();
        for (Actor actor : List.of(Actor.ADMIN, Actor.DEPUTY_ADMIN)) {
            JsonNode top = data(stats(actor, FROM, TO, null, null)).path("topParticipants");
            assertThat(top.size()).as("%s", actor).isEqualTo(10);
            List<Long> expected = new ArrayList<>(people.subList(0, 10));
            assertThat(ids(top)).as("%s の並び順", actor).containsExactlyElementsOf(expected);
            assertThat(top.get(0).path("participationCount").asLong()).isEqualTo(3);
            assertThat(top.get(1).path("participationCount").asLong()).isEqualTo(2);
        }
        putSettings(Actor.ADMIN, true, null);
        JsonNode memberOn = data(stats(Actor.MEMBER, FROM, TO, null, null));
        assertThat(memberOn.path("topParticipantsVisible").asBoolean()).isTrue();
        assertThat(memberOn.path("topParticipants").size()).isEqualTo(10);
    }

    @Test
    @DisplayName("AC-0159-78: displayName が埋まる。退会受付済みの参加者は userId・displayName とも null で、数には含まれる")
    void 名前の解決と退会者() throws Exception {
        Long alive = extraUser(1);
        Long withdrawn = extraUser(2);
        record(FROM.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, alive, withdrawn);
        record(FROM.plusDays(2), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, withdrawn);
        tx.executeWithoutResult(t -> em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", withdrawn).executeUpdate());

        JsonNode stats = data(stats(Actor.ADMIN, FROM, TO, null, null));
        assertThat(stats.path("totalParticipations").asLong()).isEqualTo(3);
        assertThat(stats.path("uniqueParticipants").asLong()).isEqualTo(2);
        JsonNode top = stats.path("topParticipants");
        assertThat(top.size()).isEqualTo(2);
        assertThat(top.get(0).path("userId").isNull()).isTrue();
        assertThat(top.get(0).path("displayName").isNull()).isTrue();
        assertThat(top.get(0).path("participationCount").asLong()).isEqualTo(2);
        assertThat(top.get(1).path("userId").asLong()).isEqualTo(alive);
        assertThat(top.get(1).path("displayName").isTextual()).isTrue();
        assertThat(top.get(1).path("displayName").asText()).isNotBlank();
    }

    @Test
    @DisplayName("AC-0159-79: 設定 API は管理者だけ。行なしの GET は false・version=null、初回 PUT は version=null で作成、古い version は 409、権限なしは 403 COMMON_002")
    void 設定API() throws Exception {
        JsonNode initial = data(perform(settingsGet(), fixture.user(Actor.ADMIN)));
        assertThat(initial.path("topParticipantsVisibleToMembers").asBoolean()).isFalse();
        assertThat(initial.path("version").isNull()).isTrue();

        MvcResult created = putSettings(Actor.DEPUTY_ADMIN, true, null);
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        long version = data(created).path("version").asLong();
        assertThat(data(created).path("topParticipantsVisibleToMembers").asBoolean()).isTrue();

        MvcResult recreate = putSettings(Actor.ADMIN, false, null);
        assertThat(recreate.getResponse().getStatus()).as("行があるのに version=null").isEqualTo(409);
        MvcResult stale = putSettings(Actor.ADMIN, false, version + 1);
        assertThat(stale.getResponse().getStatus()).as("古い version").isEqualTo(409);
        MvcResult updated = putSettings(Actor.ADMIN, false, version);
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(updated).path("topParticipantsVisibleToMembers").asBoolean()).isFalse();

        for (Actor actor : List.of(Actor.MEMBER, Actor.SUPPORTER, Actor.OUTSIDER, Actor.OTHER_TEAM_MEMBER)) {
            MvcResult getDenied = perform(settingsGet(), fixture.user(actor));
            assertError(getDenied, 403, "COMMON_002");
            assertError(putSettings(actor, true, null), 403, "COMMON_002");
        }
    }

    // ───────── AC-0159-82 問い合わせ本数 ─────────

    @Test
    @DisplayName("AC-0159-82: 記録 1,000 件・参加者 30 人/件でも /stats の集計 SQL は 5 本以内で、記録 10 件のときと本数が同じ")
    void 問い合わせ本数() {
        List<Long> people = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            people.add(extraUser(100 + i));
        }
        bulk(fixture.teamId, 10, people);
        bulk(fixture.otherTeamId, 1000, people);

        long small = countStatements(fixture.teamId);
        long large = countStatements(fixture.otherTeamId);
        assertThat(large).as("1,000 件の本数").isLessThanOrEqualTo(5);
        assertThat(large).as("10 件と同じ本数").isEqualTo(small);
    }

    // ───────── AC-0159-83 テンプレート絞り込み ─────────

    @Test
    @DisplayName("AC-0159-83: template_id 指定時は総数・推移・上位参加者がそのテンプレートの記録だけで数えられ期間と同時に効く。他スコープのテンプレートは 404 ACTIVITY_002")
    void テンプレート絞り込み() throws Exception {
        Long t1 = template(fixture.teamId, "型1");
        Long t2 = template(fixture.teamId, "型2");
        Long other = template(fixture.otherTeamId, "他チームの型");
        Long member = fixture.user(Actor.MEMBER);
        Long admin = fixture.user(Actor.ADMIN);
        record(FROM.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, t1, false, member);
        record(FROM.plusDays(2), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, t1, false, member);
        record(TO.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, t1, false, member);
        record(FROM.plusDays(3), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, t2, false, admin, admin);

        JsonNode stats = data(stats(Actor.ADMIN, FROM, TO, t1, null));
        assertThat(stats.path("totalActivities").asLong()).isEqualTo(2);
        assertThat(stats.path("byTemplate").size()).isEqualTo(1);
        assertThat(stats.path("byTemplate").get(0).path("templateId").asLong()).isEqualTo(t1);
        assertThat(stats.path("byPeriod").get(0).path("count").asLong()).isEqualTo(2);
        assertThat(ids(stats.path("topParticipants"))).containsExactly(member);
        assertThat(stats.path("topParticipants").get(0).path("participationCount").asLong()).isEqualTo(2);

        MvcResult otherScope = stats(Actor.ADMIN, FROM, TO, other, null);
        MvcResult missing = stats(Actor.ADMIN, FROM, TO, 9_999_999_971L, null);
        assertError(missing, 404, "ACTIVITY_002");
        assertError(otherScope, 404, "ACTIVITY_002");
        assertThat(message(otherScope)).isEqualTo(message(missing));
    }

    // ───────── AC-0159-101・102 具体的な期待値 ─────────

    @Test
    @DisplayName("AC-0159-101: 参加者 {u1,u2,u3}・{u1,u2}・{u1}（1 件はテンプレートなし）で延べ 6・実人数 3・平均 2.0。延べ 7／3 件は 2.3、延べ 5／2 件は 2.5。テンプレートなしの行は最後")
    void 参加集計の期待値() throws Exception {
        Long t = template(fixture.teamId, "参加集計の型");
        Long u1 = extraUser(1);
        Long u2 = extraUser(2);
        Long u3 = extraUser(3);
        record(FROM.plusDays(1), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, t, false, u1, u2, u3);
        record(FROM.plusDays(2), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, t, false, u1, u2);
        record(FROM.plusDays(3), ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, u1);

        JsonNode stats = data(stats(Actor.ADMIN, FROM, TO, null, null));
        assertThat(stats.path("totalParticipations").asLong()).isEqualTo(6);
        assertThat(stats.path("uniqueParticipants").asLong()).isEqualTo(3);
        assertThat(stats.path("averageParticipants").decimalValue()).isEqualByComparingTo("2.0");
        JsonNode byTemplate = stats.path("byTemplate");
        assertThat(byTemplate.size()).isEqualTo(2);
        assertThat(byTemplate.get(0).path("templateId").asLong()).isEqualTo(t);
        assertThat(byTemplate.get(1).path("templateId").isNull()).isTrue();
        assertThat(byTemplate.get(1).path("templateName").isNull()).isTrue();
        assertThat(byTemplate.get(1).path("count").asLong()).isEqualTo(1);

        // 延べ 7 ／ 3 件（4 月）と 延べ 5 ／ 2 件（5 月）
        LocalDate april = LocalDate.of(2026, 4, 1);
        record(april, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, u1, u2, u3);
        record(april, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, u1, u2);
        record(april, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, u1, u2);
        assertThat(data(stats(Actor.ADMIN, april, april.plusDays(29), null, null)).path("averageParticipants")
                .decimalValue()).isEqualByComparingTo("2.3");
        LocalDate may = LocalDate.of(2026, 5, 1);
        record(may, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, u1, u2, u3);
        record(may, ActivityStatus.PUBLISHED, ActivityVisibility.PUBLIC, null, false, u1, u2);
        assertThat(data(stats(Actor.ADMIN, may, may.plusDays(30), null, null)).path("averageParticipants")
                .decimalValue()).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("AC-0159-102: 対象 0 件は 200 で総数 0・平均 null・byTemplate []・byPeriod は全バケット 0 の昇順・上位参加者は見せる閲覧者に []・見せない閲覧者に null")
    void 完全0件() throws Exception {
        JsonNode admin = data(stats(Actor.ADMIN, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), null, null));
        assertThat(admin.path("totalActivities").asLong()).isZero();
        assertThat(admin.path("totalParticipations").asLong()).isZero();
        assertThat(admin.path("uniqueParticipants").asLong()).isZero();
        assertThat(admin.path("averageParticipants").isNull()).isTrue();
        assertThat(admin.path("byTemplate").isArray()).isTrue();
        assertThat(admin.path("byTemplate").size()).isZero();
        assertThat(periods(admin)).containsExactly("2026-01-01=2026-01:0", "2026-02-01=2026-02:0", "2026-03-01=2026-03:0");
        assertThat(admin.path("topParticipants").isArray()).isTrue();
        assertThat(admin.path("topParticipants").size()).isZero();
        JsonNode member = data(stats(Actor.MEMBER, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31), null, null));
        assertThat(member.path("topParticipants").isNull()).isTrue();
    }

    // ───────── helpers ─────────

    private long countStatements(Long teamId) {
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            ActivityStatsResponse response = statsService.getStats(ActivityScopeType.TEAM, teamId, null, FROM, TO, null, true);
            assertThat(response.getTopParticipants()).hasSize(10);
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }

    /** 記録を count 件、参加者 people 全員を各記録に一括で入れる（試験データの準備。集合演算 2 本）。 */
    private void bulk(Long teamId, int count, List<Long> people) {
        tx.executeWithoutResult(t -> {
            em.createNativeQuery("""
                    INSERT INTO activity_results (scope_type, scope_id, title, activity_date, visibility, status,
                        created_by, field_values, version, created_at, updated_at)
                    WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < :count)
                    SELECT 'TEAM', :team, CONCAT('一括', n), DATE_ADD(:from, INTERVAL MOD(n, 28) DAY), 'PUBLIC', 'PUBLISHED',
                        :author, '{}', 0, NOW(), NOW() FROM seq
                    """).setParameter("count", count).setParameter("team", teamId).setParameter("from", FROM)
                    .setParameter("author", fixture.user(Actor.ADMIN)).executeUpdate();
            em.createNativeQuery("""
                    INSERT INTO activity_participants (activity_result_id, user_id, created_at)
                    SELECT r.id, u.id, NOW() FROM activity_results r JOIN users u ON u.id IN (:people)
                    WHERE r.scope_type = 'TEAM' AND r.scope_id = :team
                    """).setParameter("people", people).setParameter("team", teamId).executeUpdate();
        });
    }

    private Long extraUser(int offset) {
        Long id = fixture.user(Actor.ADMIN) + 100_000L + offset;
        tx.executeWithoutResult(t -> {
            MembershipTestHelper.insertActiveUser(em, id);
            MembershipTestHelper.insertMembership(em, id, ScopeType.TEAM, fixture.teamId, RoleKind.MEMBER);
        });
        extraUsers.add(id);
        return id;
    }

    private Long template(Long teamId, String name) {
        return tx.execute(t -> {
            ActivityTemplateEntity template = ActivityTemplateEntity.builder().scopeType(ActivityScopeType.TEAM)
                    .scopeId(teamId).name(name).createdBy(fixture.user(Actor.ADMIN)).build();
            em.persist(template);
            em.flush();
            return template.getId();
        });
    }

    private Long recordBy(Long author, LocalDate date, ActivityStatus status, boolean automatic) {
        return insertRecord(author, date, status, ActivityVisibility.PUBLIC, null, automatic);
    }

    private Long record(LocalDate date, ActivityStatus status, ActivityVisibility visibility, Long templateId,
                        boolean automatic, Long... participants) {
        Long id = insertRecord(fixture.user(Actor.ADMIN), date, status, visibility, templateId, automatic);
        tx.executeWithoutResult(t -> {
            for (Long participant : participants) {
                em.createNativeQuery("INSERT INTO activity_participants (activity_result_id, user_id, created_at) "
                                + "VALUES (:id, :user, NOW())")
                        .setParameter("id", id).setParameter("user", participant).executeUpdate();
            }
        });
        return id;
    }

    private Long insertRecord(Long author, LocalDate date, ActivityStatus status, ActivityVisibility visibility,
                              Long templateId, boolean automatic) {
        return tx.execute(t -> {
            ActivityResultEntity entity = ActivityResultEntity.builder().scopeType(ActivityScopeType.TEAM)
                    .scopeId(fixture.teamId).createdBy(author).title("統計の記録").activityDate(date)
                    .templateId(templateId).visibility(visibility).status(status)
                    .autoGeneratedFromSchedule(automatic).build();
            em.persist(entity);
            em.flush();
            return entity.getId();
        });
    }

    private MockHttpServletRequestBuilder statsRequest(String scopeType, Long scopeId, LocalDate from, LocalDate to) {
        return get("/api/v1/activities/stats").param("scope_type", scopeType).param("scope_id", scopeId.toString())
                .param("date_from", from.toString()).param("date_to", to.toString());
    }

    private MvcResult stats(Actor actor, LocalDate from, LocalDate to, Long templateId, String granularity)
            throws Exception {
        MockHttpServletRequestBuilder request = statsRequest("TEAM", fixture.teamId, from, to);
        if (templateId != null) {
            request = request.param("template_id", templateId.toString());
        }
        if (granularity != null) {
            request = request.param("granularity", granularity);
        }
        return perform(request, fixture.user(actor));
    }

    private MockHttpServletRequestBuilder settingsGet() {
        return get("/api/v1/activities/stats/settings").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString());
    }

    private MvcResult putSettings(Actor actor, boolean visible, Long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topParticipantsVisibleToMembers", visible);
        body.put("version", version);
        return perform(put("/api/v1/activities/stats/settings").param("scope_type", "TEAM")
                .param("scope_id", fixture.teamId.toString()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)), fixture.user(actor));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, Long userId) throws Exception {
        return mvc.perform(request.with(user(String.valueOf(userId)))).andReturn();
    }

    private JsonNode root(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode data(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("2xx: %s", result.getResponse().getContentAsString())
                .isBetween(200, 299);
        return root(result).path("data");
    }

    private String code(MvcResult result) throws Exception {
        return root(result).path("error").path("code").asText(null);
    }

    private String message(MvcResult result) throws Exception {
        return root(result).path("error").path("message").asText(null);
    }

    private void assertError(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus()).as("status: %s", result.getResponse().getContentAsString())
                .isEqualTo(status);
        assertThat(code(result)).isEqualTo(code);
    }

    private static JsonNode withoutTop(JsonNode data) {
        ObjectNode copy = data.deepCopy();
        copy.remove("topParticipantsVisible");
        copy.remove("topParticipants");
        return copy;
    }

    private static List<String> periods(JsonNode data) {
        List<String> result = new ArrayList<>();
        data.path("byPeriod").forEach(p -> result.add(p.path("periodStart").asText() + "=" + p.path("periodLabel").asText()
                + ":" + p.path("count").asLong()));
        return result;
    }

    private static List<Long> ids(JsonNode top) {
        List<Long> result = new ArrayList<>();
        top.forEach(p -> result.add(p.path("userId").asLong()));
        return result;
    }
}
