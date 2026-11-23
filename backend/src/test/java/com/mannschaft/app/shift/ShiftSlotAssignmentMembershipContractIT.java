package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * シフト枠への割当追加で、追加先ユーザーが枠のシフト表のチームの現役メンバーであることを要求する契約テスト（試練）。
 *
 * <p>是正前の欠陥: {@code PATCH /api/v1/shifts/slots/{slotId}/assignments} の addUserIds は重複・人数・時間重なりしか
 * 見ず、他チームのユーザーや非所属ユーザーを割り当てられた。拒否は 400 {@code SHIFT_049}。存在しないユーザーと
 * 他チームのユーザーは同一応答にし、ユーザー ID の存在オラクルにしない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("シフト枠割当の追加先所属検証 契約テスト（試練）")
class ShiftSlotAssignmentMembershipContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ShiftScheduleRepository scheduleRepository;

    @Autowired
    private ShiftSlotRepository slotRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long supporterAId;
    private Long memberBId;
    private Long outsiderId;
    private Long slotA;

    @BeforeEach
    void setUp() {
        teamAId = insertTeam("所属検証 チームA");
        teamBId = insertTeam("所属検証 チームB");
        adminAId = insertUser("slot-mem-admin-a@example.com");
        adminBId = insertUser("slot-mem-admin-b@example.com");
        memberAId = insertUser("slot-mem-member-a@example.com");
        supporterAId = insertUser("slot-mem-supporter-a@example.com");
        memberBId = insertUser("slot-mem-member-b@example.com");
        outsiderId = insertUser("slot-mem-outsider@example.com");

        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, supporterAId, ScopeType.TEAM, teamAId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertMembership(em, memberBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        Long scheduleA = insertSchedule(teamAId);
        slotA = insertSlot(scheduleA, LocalTime.of(9, 0), LocalTime.of(12, 0), 3, List.of());
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("チームA ADMIN が自チームのメンバーを追加できる")
    void 自チームのメンバーを追加できる() throws Exception {
        setAuth(adminAId);
        patchAdd(slotA, memberAId).andExpect(status().isOk());
        assertThat(assignedOf(slotA)).containsExactly(memberAId);
    }

    @Test
    @DisplayName("他チームのメンバーは追加できない（400 SHIFT_049・割当は変わらない）")
    void 他チームのメンバーは追加できない() throws Exception {
        setAuth(adminAId);
        patchAdd(slotA, memberBId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SHIFT_049"));
        assertThat(assignedOf(slotA)).isEmpty();
    }

    @Test
    @DisplayName("どのチームにも所属しないユーザーは追加できない")
    void 非所属ユーザーは追加できない() throws Exception {
        setAuth(adminAId);
        patchAdd(slotA, outsiderId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SHIFT_049"));
        assertThat(assignedOf(slotA)).isEmpty();
    }

    @Test
    @DisplayName("サポーターは追加できない")
    void サポーターは追加できない() throws Exception {
        setAuth(adminAId);
        patchAdd(slotA, supporterAId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("SHIFT_049"));
    }

    @Test
    @DisplayName("存在しないユーザーと他チームのユーザーは同一応答（存在オラクルにならない）")
    void 存在しないユーザーと他チームは同一応答() throws Exception {
        setAuth(adminAId);
        MvcResult other = patchAdd(slotA, memberBId).andReturn();
        MvcResult ghost = patchAdd(slotA, 999_999_999L).andReturn();
        assertThat(ghost.getResponse().getStatus()).isEqualTo(other.getResponse().getStatus());
        assertThat(ghost.getResponse().getContentAsString()).isEqualTo(other.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("1人でも不適格なら全体を拒否し、適格な人も保存されない")
    void 一部不適格なら全体拒否() throws Exception {
        setAuth(adminAId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addUserIds", List.of(memberAId, memberBId));
        body.put("removeUserIds", List.of());
        body.put("slotVersion", versionOf(slotA).intValue());
        mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(assignedOf(slotA)).isEmpty();
    }

    @Test
    @DisplayName("チームB ADMIN はチームAの枠を操作できない（404 SHIFT_002）")
    void 他チームADMINは操作できない() throws Exception {
        setAuth(adminBId);
        patchAdd(slotA, memberBId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SHIFT_002"));
        assertThat(assignedOf(slotA)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions patchAdd(Long slotId, Long addUserId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("addUserIds", List.of(addUserId));
        body.put("removeUserIds", List.of());
        body.put("slotVersion", versionOf(slotId).intValue());
        return mockMvc.perform(patch("/api/v1/shifts/slots/{id}/assignments", slotId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Long versionOf(Long slotId) {
        return ((Number) em.createNativeQuery("SELECT version FROM shift_slots WHERE id = :id")
                .setParameter("id", slotId).getSingleResult()).longValue();
    }

    private List<Long> assignedOf(Long slotId) {
        em.flush();
        em.clear();
        ShiftSlotEntity slot = slotRepository.findById(slotId).orElseThrow();
        return ShiftAssignedUserIds.parse(slot.getAssignedUserIds());
    }

    private Long insertSchedule(Long team) {
        ShiftScheduleEntity schedule = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(team)
                .title("割当所属検証")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 11, 1))
                .endDate(LocalDate.of(2026, 11, 7))
                .status(ShiftScheduleStatus.ADJUSTING)
                .requestDeadline(LocalDateTime.of(2099, 1, 1, 0, 0))
                .createdBy(adminAId)
                .build());
        return schedule.getId();
    }

    private Long insertSlot(Long schedule, LocalTime start, LocalTime end, int required, List<Long> assigned) {
        return slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(schedule)
                .slotDate(LocalDate.of(2026, 11, 2))
                .startTime(start)
                .endTime(end)
                .requiredCount(required)
                .assignedUserIds(assigned.isEmpty() ? null : ShiftAssignedUserIds.serialize(assigned))
                .build()).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'CMP', 'テスト', 'CMP テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
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
