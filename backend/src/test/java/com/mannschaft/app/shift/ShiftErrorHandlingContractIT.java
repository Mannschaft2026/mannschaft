package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.common.EnumInputParser;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-260910-1607 試練: シフト系 BE のエラー処理（不正 enum・未知フィールド・空の差分割当）を
 * 実 JWT・実 Security フィルタ・実 MySQL（commit 観測）で固定する。
 *
 * <p>マスター裁定（確定）:</p>
 * <ul>
 *   <li>① 不正 status は 400 {@code COMMON_001}（fieldErrors.field=status、訳キー {@code validation.field.invalid_enum}）。
 *       認可を先に判定し、他チーム・非管理者は従来どおり 404/403。</li>
 *   <li>② シフト更新系 DTO（10 件）は未知フィールドを 400 で拒否し、何も変更しない。
 *       PATCH assignments で addUserIds・removeUserIds が両方 null か空なら 400（項目エラー付き）・無変更。</li>
 *   <li>④ resolve の action 不正値・小文字は 400 {@code COMMON_001} field=action。</li>
 * </ul>
 *
 * <p>fixture は {@link ShiftSwapApprovalTestSupport}（所有行の明示 commit・所有 ID での cleanup・実 JWT）を再利用し、
 * 本クラスで足した行（希望・ポジション・勤務制約・勤務可能時間・変更依頼）は先に所有範囲で消す。
 * 「不変」は対象行の全列（version・updated_at を含む）を再読込して比較する。</p>
 *
 * <p>攻め口: 性能＝該当なし（いずれも単一行・単一枠の更新で、件数に比例する経路を持たない）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-260910-1607 シフト系 BE のエラー処理（不正 enum・未知フィールド）")
class ShiftErrorHandlingContractIT extends ShiftSwapApprovalTestSupport {

    private static final String TRANSITION = "/api/v1/shifts/schedules/{id}/transition";
    private static final String SWAPS = "/api/v1/shifts/swap-requests";
    private static final String ASSIGNMENTS = "/api/v1/shifts/slots/{id}/assignments";

    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ShiftRequestRepository shiftRequests;
    @Autowired private ShiftPositionRepository positions;
    @Autowired private ShiftChangeRequestRepository changeRequests;

    @BeforeEach
    void シフト機能を有効化する() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
    }

    /** 本クラスで足した行を、親（schedule・team）を消す基底の cleanup より先に所有範囲で消す。 */
    @AfterEach
    void 本クラスの所有行をcleanupする() {
        inTx(() -> {
            if (!scheduleIds.isEmpty()) {
                for (String table : List.of("shift_change_requests", "shift_requests")) {
                    em.createNativeQuery("DELETE FROM " + table + " WHERE schedule_id IN (:ids)")
                            .setParameter("ids", scheduleIds).executeUpdate();
                }
            }
            for (String table : List.of("shift_positions", "member_work_constraints", "member_availability_defaults")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE team_id = :team")
                        .setParameter("team", teamId).executeUpdate();
            }
            return null;
        });
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    // ═══════════════════════════════════════════════════════════════
    // ① POST /schedules/{id}/transition の不正 status
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-1: transition?status=INVALID は 400 COMMON_001 field=status で、status・publishedAt・updated_at 不変")
    void AC1_不正statusは400_COMMON_001で不変() throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.ADJUSTING));
        Map<String, String> before = snapshot();

        expectInvalidEnum(transition(admin, schedule, "INVALID"), "status");

        assertThat(snapshot()).as("schedule の status・published_at・version・updated_at を変えない").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-1b: 他チーム ADMIN が status=INVALID なら従来どおり 404 SHIFT_001（400 にしない）")
    void AC1b_他チームADMINは不正statusでも404() throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.DRAFT));
        Map<String, String> before = snapshot();

        expect(transition(actors.get("OTHER_TEAM_ADMIN"), schedule, "INVALID"), 404, "SHIFT_001");

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-1b: 同チームの非管理者が status=INVALID なら従来どおり 403（400 にしない）")
    void AC1b_同チーム非管理者は不正statusでも403() throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.DRAFT));
        Map<String, String> before = snapshot();

        expect(transition(a, schedule, "INVALID"), 403, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-2: status=\"{0}\"")
    @ValueSource(strings = {"", " ", "published"})
    @DisplayName("AC-2: 空・空白・小文字の status は 400 COMMON_001 field=status で不変")
    void AC2_空と空白と小文字は400_COMMON_001(String status) throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.ADJUSTING));
        Map<String, String> before = snapshot();

        expectInvalidEnum(transition(admin, schedule, status), "status");

        assertThat(snapshot()).isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-2: DRAFT → {0}")
    @EnumSource(ShiftScheduleStatus.class)
    @DisplayName("AC-2: 全有効値は 400 COMMON_001 にならない（不許可遷移は既存 409 SHIFT_012）")
    void AC2_全有効値はCOMMON_001にならない(ShiftScheduleStatus target) throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.DRAFT));

        MockHttpServletResponse response = transition(admin, schedule, target.name());

        if (target == ShiftScheduleStatus.COLLECTING) {
            expect(response, 200, null);
        } else {
            expect(response, 409, "SHIFT_012");
        }
    }

    @Test
    @DisplayName("AC-3: status 欠落は既存どおり 400 で不変（回帰）")
    void AC3_status欠落は400で不変() throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.DRAFT));
        Map<String, String> before = snapshot();

        expect(call(admin, post(TRANSITION, schedule)), 400, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    // ═══════════════════════════════════════════════════════════════
    // ① GET /swap-requests?status= の不正 status
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: swap-requests?status=BOGUS は 400 COMMON_001 field=status")
    void AC5_交代一覧の不正statusは400_COMMON_001() throws Exception {
        expectInvalidEnum(call(admin, get(SWAPS).param("teamId", teamId.toString()).param("status", "BOGUS")),
                "status");
    }

    @Test
    @DisplayName("AC-5: swap-requests の status 省略は 200（回帰）")
    void AC5_交代一覧のstatus省略は200() throws Exception {
        expect(call(admin, get(SWAPS).param("teamId", teamId.toString())), 200, null);
    }

    @Test
    @DisplayName("AC-5: 権限なし（他チーム ADMIN・SUPPORTER）＋BOGUS は既存の 403 COMMON_002（400 にしない）")
    void AC5_権限なしと不正statusは既存の拒否() throws Exception {
        for (Long actor : List.of(actors.get("OTHER_TEAM_ADMIN"), actors.get("SUPPORTER"))) {
            expect(call(actor, get(SWAPS).param("teamId", teamId.toString()).param("status", "BOGUS")),
                    403, "COMMON_002");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // ② PATCH /slots/{id}/assignments
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-6: 未知フィールド assignedUserIds は 400。assigned_user_ids・version・割当履歴（shift_assignments）不変")
    void AC6_未知フィールドassignedUserIdsは400で不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        expect(patchAssignments(slot, body("assignedUserIds", List.of(a, b), "slotVersion", slotVersion(slot))),
                400, null);

        assertThat(snapshot()).as("枠・割当履歴・交代・親を変えない").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-6b: 未知フィールド＋正しい addUserIds でも 400 で一切不変")
    void AC6b_未知フィールドと正しいaddUserIdsは400で不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        expect(patchAssignments(slot, body("addUserIds", List.of(b), "unknownField", 1,
                "slotVersion", slotVersion(slot))), 400, null);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(assigned(slot)).containsExactly(a);
    }

    @Test
    @DisplayName("AC-6c: addUserIds・removeUserIds が両方 null は 400（fieldErrors あり）で不変")
    void AC6c_両方nullは400で不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        MockHttpServletResponse response = patchAssignments(slot, body("slotVersion", slotVersion(slot)));

        expect(response, 400, null);
        assertThat(json(response).at("/error/fieldErrors").size()).as("項目エラーを返す").isPositive();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-6c: addUserIds・removeUserIds が両方空配列は 400（fieldErrors あり）で不変")
    void AC6c_両方空配列は400で不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        MockHttpServletResponse response = patchAssignments(slot,
                body("addUserIds", List.of(), "removeUserIds", List.of(), "slotVersion", slotVersion(slot)));

        expect(response, 400, null);
        assertThat(json(response).at("/error/fieldErrors").size()).as("項目エラーを返す").isPositive();
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-7: 正常な PATCH は 200・version+1・割当反映（回帰）")
    void AC7_正常PATCHは200でversionが1増える() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        long version = slotVersion(slot);

        expect(patchAssignments(slot, body("addUserIds", List.of(b), "slotVersion", version)), 200, null);

        assertThat(slotVersion(slot)).isEqualTo(version + 1);
        assertThat(assigned(slot)).containsExactly(a, b);
    }

    @Test
    @DisplayName("AC-7: 大小違いの AddUserIds は未知フィールドとして 400 で不変")
    void AC7_大小違いのAddUserIdsは400で不変() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        expect(patchAssignments(slot, body("AddUserIds", List.of(b), "slotVersion", slotVersion(slot))), 400, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-8: 未知フィールド＋slotVersion 不一致は 400（未知フィールドの拒否が version 照合より先）")
    void AC8_未知フィールドとversion不一致は400が先() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        expect(patchAssignments(slot, body("addUserIds", List.of(b), "bogus", true,
                "slotVersion", slotVersion(slot) + 5)), 400, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-8: 正しい body で version 不一致は既存 409 SHIFT_018（回帰）")
    void AC8_正しいbodyのversion不一致は409() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        expect(patchAssignments(slot, body("addUserIds", List.of(b), "slotVersion", slotVersion(slot) + 5)),
                409, "SHIFT_018");

        assertThat(snapshot()).isEqualTo(before);
    }

    // ═══════════════════════════════════════════════════════════════
    // ② 残りの更新系 DTO（エンドポイントごとに 1 本）
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-13 SlotAssignmentMoveRequest: POST /slots/{id}/assignments/move に未知フィールドは 400 で両枠・履歴不変")
    void AC13_割当移動の未知フィールドは400() throws Exception {
        Long source = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Long target = extraSlot(scheduleOf(source), List.of(), LocalTime.of(19, 0), LocalTime.of(21, 0));
        Map<String, String> before = snapshot();

        expect(call(admin, withBody(post("/api/v1/shifts/slots/{id}/assignments/move", source),
                body("targetSlotId", target, "userId", a, "sourceSlotVersion", slotVersion(source),
                        "targetSlotVersion", slotVersion(target), "bogus", 1))), 400, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 UpdateShiftSlotRequest: PATCH /slots/{id} に未知フィールドは 400 で枠不変")
    void AC13_枠更新の未知フィールドは400() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.ADJUSTING);
        Map<String, String> before = snapshot();

        expect(call(admin, withBody(patch("/api/v1/shifts/slots/{id}", slot),
                body("note", "変更後のメモ", "bogus", 1))), 400, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 UpdateShiftScheduleRequest: PATCH /schedules/{id} に status を送ると 400 でスケジュール不変")
    void AC13_スケジュール更新でstatusを送ると400() throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.DRAFT));
        Map<String, String> before = snapshot();

        expect(call(admin, withBody(patch("/api/v1/shifts/schedules/{id}", schedule),
                body("title", "改題", "status", "PUBLISHED"))), 400, null);

        assertThat(snapshot()).as("status を黙殺して title だけ更新してはならない").isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 UpdateShiftScheduleRequest: PATCH /schedules/{id} に未知フィールドは 400 でスケジュール不変")
    void AC13_スケジュール更新の未知フィールドは400() throws Exception {
        Long schedule = scheduleOf(slot(List.of(a), ShiftScheduleStatus.DRAFT));
        Map<String, String> before = snapshot();

        expect(call(admin, withBody(patch("/api/v1/shifts/schedules/{id}", schedule),
                body("title", "改題", "bogus", 1))), 400, null);

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 UpdateShiftRequestRequest: PATCH /requests/{id} に未知フィールドは 400 で希望不変")
    void AC13_希望更新の未知フィールドは400() throws Exception {
        Long schedule = collectingSchedule();
        Long request = inTx(() -> shiftRequests.save(ShiftRequestEntity.builder().scheduleId(schedule).userId(a)
                .slotDate(LocalDate.now().plusDays(1)).preference(ShiftPreference.PREFERRED).note("元の希望")
                .build()).getId());
        String before = row("shift_requests", request);

        expect(call(a, withBody(patch("/api/v1/shifts/requests/{id}", request),
                body("preference", "AVAILABLE", "note", "変更後", "bogus", 1))), 400, null);

        assertThat(row("shift_requests", request)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 UpdatePositionRequest: PATCH /positions/{id} に未知フィールドは 400 でポジション不変")
    void AC13_ポジション更新の未知フィールドは400() throws Exception {
        Long position = inTx(() -> positions.save(ShiftPositionEntity.builder().teamId(teamId).name("ホール")
                .displayOrder(1).isActive(true).build()).getId());
        String before = row("shift_positions", position);

        expect(call(admin, withBody(patch("/api/v1/shifts/positions/{id}", position),
                body("name", "改名", "bogus", 1))), 400, null);

        assertThat(row("shift_positions", position)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 MemberWorkConstraintRequest: PUT /work-constraints/members/{userId} に未知フィールドは 400 で作成しない")
    void AC13_個別勤務制約の未知フィールドは400() throws Exception {
        String before = teamRows("member_work_constraints");

        expect(call(admin, withBody(put("/api/v1/shifts/teams/{teamId}/work-constraints/members/{userId}", teamId, a),
                body("maxMonthlyDays", 10, "bogus", 1))), 400, null);

        assertThat(teamRows("member_work_constraints")).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 MemberWorkConstraintRequest: PUT /work-constraints/default に未知フィールドは 400 で作成しない")
    void AC13_チーム既定勤務制約の未知フィールドは400() throws Exception {
        String before = teamRows("member_work_constraints");

        expect(call(admin, withBody(put("/api/v1/shifts/teams/{teamId}/work-constraints/default", teamId),
                body("maxMonthlyDays", 10, "bogus", 1))), 400, null);

        assertThat(teamRows("member_work_constraints")).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 BulkAvailabilityDefaultRequest: PUT /availability に未知フィールドは 400 で勤務可能時間不変")
    void AC13_勤務可能時間一括更新の未知フィールドは400() throws Exception {
        String before = teamRows("member_availability_defaults");

        expect(call(a, withBody(put("/api/v1/shifts/availability").param("teamId", teamId.toString()),
                body("availabilities", List.of(availability()), "bogus", 1))), 400, null);

        assertThat(teamRows("member_availability_defaults")).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 AvailabilityDefaultRequest（Bulk の要素）: 要素内の未知フィールドも 400 で勤務可能時間不変")
    void AC13_勤務可能時間の要素内の未知フィールドは400() throws Exception {
        String before = teamRows("member_availability_defaults");
        Map<String, Object> element = availability();
        element.put("bogus", 1);

        expect(call(a, withBody(put("/api/v1/shifts/availability").param("teamId", teamId.toString()),
                body("availabilities", List.of(element)))), 400, null);

        assertThat(teamRows("member_availability_defaults")).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 ReviewChangeRequestRequest: PATCH /change-requests/{id}/review に未知フィールドは 400 で依頼不変")
    void AC13_変更依頼審査の未知フィールドは400() throws Exception {
        Long schedule = collectingSchedule();
        Long request = inTx(() -> changeRequests.save(ShiftChangeRequestEntity.builder().scheduleId(schedule)
                .requestType(ChangeRequestType.PRE_CONFIRM_EDIT).requestedBy(a).reason("CMP1607 試練").build()).getId());
        String before = row("shift_change_requests", request);

        expect(call(admin, withBody(patch("/api/v1/shifts/change-requests/{id}/review", request),
                body("decision", "ACCEPTED", "reviewComment", "審査", "version", 0, "bogus", 1))), 400, null);

        assertThat(row("shift_change_requests", request)).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-13 ResolveSwapRequestRequest: POST /swap-requests/{id}/resolve に未知フィールドは 400 で交代・枠不変")
    void AC13_交代処理の未知フィールドは400() throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        Map<String, String> before = snapshot();

        expect(call(admin, withBody(post(SWAPS + "/{id}/resolve", swap),
                body("action", "REJECT", "adminNote", "却下", "bogus", 1))), 400, null);

        assertThat(snapshot()).isEqualTo(before);
        assertThat(swapState(swap)).isEqualTo("ACCEPTED");
    }

    // ═══════════════════════════════════════════════════════════════
    // ④ resolve の action 不正値
    // ═══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-14: action=\"{0}\"")
    @ValueSource(strings = {"BOGUS", "approve", "reject"})
    @DisplayName("AC-14: resolve の action 不正値・小文字は 400 COMMON_001 field=action で交代申請不変")
    void AC14_resolveのaction不正値は400_COMMON_001(String action) throws Exception {
        Long slot = slot(List.of(a), ShiftScheduleStatus.PUBLISHED);
        Long swap = accepted(slot, a, b);
        Map<String, String> before = snapshot();

        expectInvalidEnum(resolve(admin, swap, action), "action");

        assertThat(snapshot()).isEqualTo(before);
        assertThat(swapState(swap)).isEqualTo("ACCEPTED");
    }

    // ═══════════════════════════════════════════════════════════════
    // helpers
    // ═══════════════════════════════════════════════════════════════

    private void expectInvalidEnum(MockHttpServletResponse response, String field) throws Exception {
        expect(response, 400, "COMMON_001");
        JsonNode errors = json(response).at("/error/fieldErrors");
        assertThat(errors.isArray() && errors.size() == 1).as("項目エラーを 1 件返す: " + response.getContentAsString())
                .isTrue();
        assertThat(errors.get(0).path("field").asText()).isEqualTo(field);
        assertThat(errors.get(0).path("message").asText())
                .as("訳キー validation.field.invalid_enum の ja 文言").isEqualTo(EnumInputParser.INVALID_ENUM_MESSAGE);
    }

    private MockHttpServletResponse transition(Long actor, Long schedule, String status) throws Exception {
        return call(actor, post(TRANSITION, schedule).param("status", status));
    }

    private MockHttpServletResponse patchAssignments(Long slot, Map<String, Object> body) throws Exception {
        return call(admin, withBody(patch(ASSIGNMENTS, slot), body));
    }

    private MockHttpServletResponse call(Long actor, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("Authorization", bearer(actor)).header("Accept-Language", "ja"))
                .andReturn().getResponse();
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, Map<String, Object> body)
            throws Exception {
        return request.contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body));
    }

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    private static Map<String, Object> availability() {
        return body("dayOfWeek", 1, "startTime", "09:00", "endTime", "18:00", "preference", "AVAILABLE");
    }

    private long slotVersion(Long slot) {
        return inTx(() -> ((Number) em.createNativeQuery("SELECT version FROM shift_slots WHERE id = :id")
                .setParameter("id", slot).getSingleResult()).longValue());
    }

    /** 希望募集中・締切前のスケジュール（希望・変更依頼の親）。 */
    private Long collectingSchedule() {
        return inTx(() -> {
            Long id = schedules.save(ShiftScheduleEntity.builder().teamId(teamId).title("CMP1607 募集中")
                    .periodType(ShiftPeriodType.WEEKLY).startDate(LocalDate.now()).endDate(LocalDate.now().plusDays(7))
                    .status(ShiftScheduleStatus.COLLECTING).requestDeadline(LocalDateTime.now().plusDays(30))
                    .createdBy(admin).build()).getId();
            scheduleIds.add(id);
            return id;
        });
    }

    private String row(String table, Long id) {
        return inTx(() -> Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table + " WHERE id = :id")
                .setParameter("id", id).getResultList().toArray()));
    }

    private String teamRows(String table) {
        return inTx(() -> Arrays.deepToString(em.createNativeQuery("SELECT * FROM " + table
                        + " WHERE team_id = :team ORDER BY id")
                .setParameter("team", teamId).getResultList().toArray()));
    }
}
