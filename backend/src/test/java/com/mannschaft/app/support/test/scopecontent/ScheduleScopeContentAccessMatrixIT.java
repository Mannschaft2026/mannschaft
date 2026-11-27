package com.mannschaft.app.support.test.scopecontent;

import com.mannschaft.app.schedule.CrossRefStatus;
import com.mannschaft.app.schedule.CrossRefTargetType;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.entity.ScheduleCrossRefEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleCrossRefRepository;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261124-0159 PR-D2: スケジュール（予定一覧・詳細・出欠・年間計画・イベントカテゴリ・受信招待・キープ）の
 * 親チーム上限 Matrix IT（F00 §20.9 AC-0159-14）。
 *
 * <p>非公開チーム（{@code MEMBERS_AND_ABOVE}）で、権限あり（ADMIN / DEPUTY_ADMIN / MEMBER）は本戦役前どおり、
 * 権限なし（SUPPORTER・GUEST・部外者・他チーム・他組織）は部外者と同一応答であること。予定は
 * 「上限が無ければサポーターにも見える」設定（{@code SUPPORTER_PLUS}）で作り、拒否の原因が個別の公開範囲では
 * なく親チーム上限であることを示す。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 PR-D2 schedule の親チーム上限 Matrix IT（AC-0159-14）")
class ScheduleScopeContentAccessMatrixIT extends AbstractScopeContentAccessMatrixIT {

    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private ScheduleKeepRepository scheduleKeepRepository;
    @Autowired private ScheduleCrossRefRepository crossRefRepository;
    @Autowired private PlatformTransactionManager tm;

    private static final String SCHEDULE_TITLE = "上限試練の予定";

    private Long scheduleId;
    private java.util.UUID keepId;

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "schedule.yaml");
    }

    @Override
    protected String resolveParam(String name, String value) {
        return switch (value) {
            case "@schedule" -> String.valueOf(scheduleId);
            case "@keep" -> keepId.toString();
            default -> super.resolveParam(name, value);
        };
    }

    @Override
    protected void seed(ScopeContentFixture fx) {
        Long admin = fx.user(Actor.ADMIN);
        // 上限が無ければサポーターにも見える予定（拒否の原因を親チーム上限に限定する）
        scheduleId = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(fx.teamId).title(SCHEDULE_TITLE)
                .startAt(LocalDateTime.of(2026, 4, 10, 10, 0)).endAt(LocalDateTime.of(2026, 4, 10, 12, 0))
                .eventType(EventType.MEETING).visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.SUPPORTER_PLUS).status(ScheduleStatus.SCHEDULED)
                .createdBy(admin).build()).getId();
        Long otherTeamSchedule = scheduleRepository.save(ScheduleEntity.builder()
                .teamId(fx.otherTeamId).title("上限試練の招待元")
                .startAt(LocalDateTime.of(2026, 4, 11, 10, 0)).endAt(LocalDateTime.of(2026, 4, 11, 12, 0))
                .eventType(EventType.MATCH).visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS).status(ScheduleStatus.SCHEDULED)
                .createdBy(fx.user(Actor.OTHER_TEAM_MEMBER)).build()).getId();
        crossRefRepository.save(ScheduleCrossRefEntity.builder()
                .sourceScheduleId(otherTeamSchedule).targetType(CrossRefTargetType.TEAM).targetId(fx.teamId)
                .invitedBy(fx.user(Actor.OTHER_TEAM_MEMBER)).status(CrossRefStatus.PENDING)
                .message("上限試練の招待").build());
        keepId = scheduleKeepRepository.save(ScheduleKeepEntity.builder()
                .teamId(fx.teamId).title("上限試練のキープ").candidateDates("[\"2026-05-01\"]")
                .status(ScheduleKeepStatus.KEPT).convertedScheduleId(scheduleId).sortOrder(0)
                .createdBy(admin).build()).getId();
    }

    /** 作成したテストデータだけを消す（フィクスチャ本体の後始末は基底クラス）。 */
    @AfterEach
    void deleteSeededRows() {
        if (fixture == null) {
            return;
        }
        new TransactionTemplate(tm).executeWithoutResult(t -> {
            em.createNativeQuery("DELETE FROM schedule_cross_refs WHERE target_type = 'TEAM' AND target_id = :id")
                    .setParameter("id", fixture.teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM schedule_keeps WHERE team_id = :id")
                    .setParameter("id", fixture.teamId).executeUpdate();
            em.createNativeQuery("DELETE FROM schedules WHERE team_id IN (:ids)")
                    .setParameter("ids", List.of(fixture.teamId, fixture.otherTeamId)).executeUpdate();
        });
    }

    @Test
    @DisplayName("AC-0159-14: 予定一覧は上限を越える閲覧者に 1 件も返さず、権限あり（MEMBER 以上）には返す")
    void 予定一覧は上限を越える閲覧者に1件も返さない() throws Exception {
        for (Actor actor : List.of(Actor.ADMIN, Actor.DEPUTY_ADMIN, Actor.MEMBER)) {
            assertThat(listedBody(actor)).as("権限あり %s には予定が見える（本戦役前どおり）", actor).contains(SCHEDULE_TITLE);
        }
        for (Actor actor : List.of(Actor.SUPPORTER, Actor.GUEST, Actor.OUTSIDER, Actor.OTHER_TEAM_MEMBER,
                Actor.OTHER_ORG_MEMBER)) {
            assertThat(listedBody(actor)).as("権限なし %s には予定を 1 件も返さない", actor).doesNotContain(SCHEDULE_TITLE);
        }
    }

    @Test
    @DisplayName("AC-0159-14: 受信招待一覧は上限を越える在籍者（サポーター）に本文を返さない")
    void 受信招待一覧は上限を越える在籍者に返さない() throws Exception {
        assertThat(perform(route("/api/v1/teams/{teamId}/schedule-invitations"), Actor.SUPPORTER).status())
                .as("在籍サポーター（是正前データ）は 403").isEqualTo(403);
        assertThat(perform(route("/api/v1/teams/{teamId}/schedule-invitations"), Actor.MEMBER).status())
                .as("MEMBER は本戦役前どおり 200").isEqualTo(200);
    }

    private ScopeContentMatrixTable.Route route(String path) {
        return new ScopeContentMatrixTable.Route("adhoc", "GET", path, java.util.Map.of(), java.util.Map.of());
    }

    /** 予定一覧の本文（レスポンス形状に依存しないよう、本文に予定のタイトルが含まれるかで判定する）。 */
    private String listedBody(Actor actor) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/teams/{slug}/schedules", fixture.teamSlug)
                        .param("from", "2026-01-01T00:00:00").param("to", "2026-12-31T23:59:59")
                        .with(user(String.valueOf(fixture.user(actor)))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("%s の予定一覧は 200（一覧は絞り込み）", actor).isEqualTo(200);
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
