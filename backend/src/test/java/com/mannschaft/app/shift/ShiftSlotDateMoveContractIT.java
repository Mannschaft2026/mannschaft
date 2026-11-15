package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftRequestService;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * CMP-260910-0127: 確認後の枠・希望日付移動と、並行する希望編集の実MySQL契約。
 *
 * <p>共通MOCK起動で実Security・実Bean・実DBを使う。fixtureは明示commitし、
 * 親ロックを待つ2TXをlatchと実行スタックで固定する。Docker不在のskipをredと数えない。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ShiftSlotDateMoveContractIT extends AbstractMySqlIntegrationTest {

    private static final LocalDate OLD_DATE = LocalDate.of(2026, 12, 31);
    private static final LocalDate NEW_DATE = LocalDate.of(2027, 1, 1);
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ShiftSlotService slotService;
    @Autowired private ShiftRequestService requestService;
    @Autowired private ShiftRequestRepository requestRepository;
    @Autowired private ShiftSlotRepository slotRepository;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate tx;
    private ExecutorService executor;
    private final List<Long> users = new ArrayList<>();
    private final List<Long> teams = new ArrayList<>();
    private final List<Long> schedules = new ArrayList<>();
    private Long adminId;
    private Long memberId;
    private Long outsiderId;
    private Long scheduleId;
    private Long slotId;
    private String suffix;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newCachedThreadPool();
        suffix = Long.toHexString(System.nanoTime());
        tx.executeWithoutResult(s -> {
            Long teamId = newTeam();
            // 初回リクエストの locale cache 参照より前に、管理者の保存 locale を en とする。
            adminId = newUser("en");
            memberId = newUser();
            outsiderId = newUser();
            MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
            MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            Long otherTeam = newTeam();
            MembershipTestHelper.insertMembership(em, outsiderId, ScopeType.TEAM, otherTeam, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, outsiderId, "ADMIN", otherTeam, null);
            scheduleId = newSchedule(teamId);
            slotId = newSlot(scheduleId, OLD_DATE);
        });
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM shift_requests WHERE schedule_id IN (:ids)")
                    .setParameter("ids", schedules).executeUpdate();
            em.createNativeQuery("DELETE FROM shift_assignments WHERE slot_id IN "
                            + "(SELECT id FROM shift_slots WHERE schedule_id IN (:ids))")
                    .setParameter("ids", schedules).executeUpdate();
            em.createNativeQuery("DELETE FROM shift_slots WHERE schedule_id IN (:ids)")
                    .setParameter("ids", schedules).executeUpdate();
            em.createNativeQuery("DELETE FROM shift_schedules WHERE id IN (:ids)")
                    .setParameter("ids", schedules).executeUpdate();
            em.createNativeQuery("DELETE FROM user_roles WHERE user_id IN (:ids)")
                    .setParameter("ids", users).executeUpdate();
            em.createNativeQuery("DELETE FROM memberships WHERE user_id IN (:ids)")
                    .setParameter("ids", users).executeUpdate();
            em.createNativeQuery("DELETE FROM teams WHERE id IN (:ids)")
                    .setParameter("ids", teams).executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE id IN (:ids)")
                    .setParameter("ids", users).executeUpdate();
        });
    }

    @Test
    void 希望ありの日付変更は確認省略とfalseを409で拒否し全列を保持する() throws Exception {
        newRequest(slotId, OLD_DATE, memberId);
        String before = snapshot();
        mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotId).with(authentication(auth(adminId)))
                        .header("Accept-Language", "ja").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slotDate", NEW_DATE.toString()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHIFT_047"))
                .andExpect(jsonPath("$.error.message").value(
                        "Requests have been submitted for this slot. Please confirm moving them to the new date."));
        assertThat(snapshot()).isEqualTo(before);
        patchSlot(adminId, Map.of("slotDate", NEW_DATE.toString(), "moveRequestsConfirmed", false))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("SHIFT_047"));
        assertThat(snapshot()).isEqualTo(before);
        Map<String, Object> explicitNull = new java.util.HashMap<>();
        explicitNull.put("slotDate", NEW_DATE.toString());
        explicitNull.put("moveRequestsConfirmed", null);
        patchSlot(adminId, explicitNull).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("SHIFT_047"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 確認後は生存する同枠希望だけ移動し識別子と内容を保持する() throws Exception {
        Long requestId = newRequest(slotId, OLD_DATE, memberId);
        ShiftRequestEntity original = tx.execute(s -> requestRepository.findById(requestId).orElseThrow());
        Long dailyId = newRequest(null, NEW_DATE, memberId);
        Long otherSlot = tx.execute(s -> newSlot(scheduleId, OLD_DATE));
        Long otherRequest = newRequest(otherSlot, OLD_DATE, memberId);
        Long deletedId = newRequest(slotId, OLD_DATE, adminId);
        tx.executeWithoutResult(s -> requestRepository.softDeleteById(deletedId));
        Long otherSchedule = tx.execute(s -> newSchedule(teams.get(1)));
        Long otherTeamSlot = tx.execute(s -> newSlot(otherSchedule, OLD_DATE));
        Long otherTeamRequest = tx.execute(s -> saveRequest(otherSchedule, otherTeamSlot, OLD_DATE, outsiderId));

        patchSlot(adminId, confirmedBody(NEW_DATE)).andExpect(status().isOk());
        tx.executeWithoutResult(s -> {
            ShiftRequestEntity moved = requestRepository.findById(requestId).orElseThrow();
            assertThat(moved.getSlotDate()).isEqualTo(NEW_DATE);
            assertThat(moved.getSlotId()).isEqualTo(slotId);
            assertThat(moved.getUserId()).isEqualTo(memberId);
            assertThat(moved.getPreference()).isEqualTo(ShiftPreference.PREFERRED);
            assertThat(moved.getNote()).isEqualTo("移動前の希望");
            assertThat(moved.getSubmittedAt()).isEqualTo(original.getSubmittedAt());
            assertThat(moved.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
            assertThat(slotRepository.findById(slotId).orElseThrow().getSlotDate()).isEqualTo(NEW_DATE);
            assertThat(requestRepository.findById(dailyId).orElseThrow().getSlotDate()).isEqualTo(NEW_DATE);
            assertThat(requestRepository.findById(otherRequest).orElseThrow().getSlotDate()).isEqualTo(OLD_DATE);
            assertThat(requestRepository.findById(otherTeamRequest).orElseThrow().getSlotDate()).isEqualTo(OLD_DATE);
            assertThat(em.createNativeQuery("SELECT slot_date FROM shift_requests WHERE id = :id")
                    .setParameter("id", deletedId).getSingleResult().toString()).isEqualTo(OLD_DATE.toString());
        });
        // 再送は同日更新となり、希望の行を増やさない。
        patchSlot(adminId, confirmedBody(NEW_DATE)).andExpect(status().isOk());
        Integer count = tx.execute(s -> requestRepository.findByScheduleIdAndUserId(scheduleId, memberId).size());
        assertThat(count).isEqualTo(3);
    }

    @Test
    void 同日と日付省略と希望なしは確認不要() throws Exception {
        newRequest(slotId, OLD_DATE, memberId);
        patchSlot(adminId, Map.of("slotDate", OLD_DATE.toString())).andExpect(status().isOk());
        patchSlot(adminId, Map.of("note", "日付を変えない編集")).andExpect(status().isOk());
        Long emptySlot = tx.execute(s -> newSlot(scheduleId, OLD_DATE));
        mockMvc.perform(patch("/api/v1/shifts/slots/{id}", emptySlot)
                        .with(authentication(auth(adminId))).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slotDate", NEW_DATE.toString()))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"2026-01-31,2026-02-01", "2026-12-31,2027-01-01"})
    void 月年境界でも枠と複数希望が同日になる(LocalDate from, LocalDate to) throws Exception {
        tx.executeWithoutResult(s -> slotRepository.findById(slotId).orElseThrow()
                .applyUpdate(from, null, null, null, null, null, null, null));
        Long first = newRequest(slotId, from, memberId);
        Long second = newRequest(slotId, from, adminId);
        patchSlot(adminId, confirmedBody(to)).andExpect(status().isOk());
        assertDate(first, to);
        assertDate(second, to);
    }

    @Test
    void 希望移動後の枠保存エラーでは両方rollbackする() {
        newRequest(slotId, OLD_DATE, memberId);
        String before = snapshot();
        UpdateShiftSlotRequest invalid = objectMapper.convertValue(Map.of(
                "slotDate", NEW_DATE.toString(), "moveRequestsConfirmed", true, "note", "x".repeat(201)),
                UpdateShiftSlotRequest.class);
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            assertThatThrownBy(() -> slotService.updateSlot(slotId, invalid, adminId)).isInstanceOf(RuntimeException.class);
            List<String> sqls = capture.capturedSqls();
            assertThat(sqls).as("実 SQL 捕捉が有効である").isNotEmpty();
            assertThat(sqls).filteredOn(sql -> updatesTable(sql, "shift_requests")).hasSize(1);
            int requestUpdate = java.util.stream.IntStream.range(0, sqls.size())
                    .filter(i -> updatesTable(sqls.get(i), "shift_requests")).findFirst().orElseThrow();
            int slotUpdate = java.util.stream.IntStream.range(0, sqls.size())
                    .filter(i -> updatesTable(sqls.get(i), "shift_slots")).findFirst().orElseThrow();
            assertThat(slotUpdate).as("希望 UPDATE が返った後の枠保存で失敗したこと").isGreaterThan(requestUpdate);
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 確認trueでも一般メンバーと別チーム管理者と匿名は更新できない() throws Exception {
        newRequest(slotId, OLD_DATE, memberId);
        String before = snapshot();
        patchSlot(memberId, confirmedBody(NEW_DATE)).andExpect(status().isForbidden());
        patchSlot(outsiderId, confirmedBody(NEW_DATE)).andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotId).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmedBody(NEW_DATE))))
                .andExpect(status().isUnauthorized());
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void 内容編集の先読み後に移動がcommitしても新日付と編集内容を両方保持する() throws Exception {
        Long requestId = newRequest(slotId, OLD_DATE, memberId);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch edit = new CountDownLatch(1);
        AtomicReference<Thread> thread = new AtomicReference<>();
        CompletableFuture<Void> editing = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            thread.set(Thread.currentThread());
            // サービスが親ロック前に読んだ場合と同じ、旧日付のmanaged entityとRR snapshotを固定する。
            assertThat(requestRepository.findById(requestId).orElseThrow().getSlotDate()).isEqualTo(OLD_DATE);
            read.countDown();
            awaitLatch(edit);
            requestService.updateRequest(requestId, new UpdateShiftRequestRequest("AVAILABLE", "並行編集の内容"));
        }), executor);
        try {
            assertThat(read.await(20, TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(s -> {
                scheduleRepository.findByIdForUpdate(scheduleId).orElseThrow();
                slotService.updateSlot(slotId, confirmedRequest(NEW_DATE), adminId);
                edit.countDown();
                awaitParentLock(thread);
            });
            editing.get(20, TimeUnit.SECONDS);
            tx.executeWithoutResult(s -> {
                ShiftRequestEntity result = requestRepository.findById(requestId).orElseThrow();
                assertThat(result.getSlotDate()).isEqualTo(NEW_DATE);
                assertThat(result.getPreference()).isEqualTo(ShiftPreference.AVAILABLE);
                assertThat(result.getNote()).isEqualTo("並行編集の内容");
            });
        } finally {
            edit.countDown();
        }
    }

    @Test
    void 移動中の希望提出は親ロック後の新日付へ提出する() throws Exception {
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<CompletableFuture<Long>> submitted = new AtomicReference<>();
        tx.executeWithoutResult(s -> {
            scheduleRepository.findByIdForUpdate(scheduleId).orElseThrow();
            slotService.updateSlot(slotId, confirmedRequest(NEW_DATE), adminId);
            submitted.set(CompletableFuture.supplyAsync(() -> {
                thread.set(Thread.currentThread());
                ServletRequestAttributes attributes = new ServletRequestAttributes(new MockHttpServletRequest());
                RequestContextHolder.setRequestAttributes(attributes);
                try {
                    return requestService.submitRequest(new CreateShiftRequestRequest(
                            scheduleId, slotId, NEW_DATE, "PREFERRED", "並行提出"), memberId).getId();
                } finally {
                    attributes.requestCompleted();
                    RequestContextHolder.resetRequestAttributes();
                }
            }, executor));
            awaitParentLock(thread);
        });
        assertDate(submitted.get().get(20, TimeUnit.SECONDS), NEW_DATE);
    }

    @Test
    void 枠先読み後に希望提出がcommitしても確認なしの移動を拒否する() throws Exception {
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch move = new CountDownLatch(1);
        CompletableFuture<Void> moving = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            // 親ロック前の枠読取でRR snapshotを確立し、その後に増えた希望を見落とさないことを検査する。
            slotRepository.findById(slotId).orElseThrow();
            read.countDown();
            awaitLatch(move);
            UpdateShiftSlotRequest unconfirmed = objectMapper.convertValue(
                    Map.of("slotDate", NEW_DATE.toString()), UpdateShiftSlotRequest.class);
            slotService.updateSlot(slotId, unconfirmed, adminId);
        }), executor);
        try {
            assertThat(read.await(20, TimeUnit.SECONDS)).isTrue();
            // HTTP要求が完了した時点で、提出サービスの実TXもcommitしている。
            mockMvc.perform(post("/api/v1/shifts/requests").with(authentication(auth(memberId)))
                            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                                    "scheduleId", scheduleId, "slotId", slotId,
                                    "slotDate", OLD_DATE.toString(), "preference", "PREFERRED"))))
                    .andExpect(status().isCreated());
            String afterSubmission = snapshot();
            move.countDown();
            assertThatThrownBy(() -> moving.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(snapshot()).isEqualTo(afterSubmission);
        } finally {
            move.countDown();
        }
    }

    @Test
    void 先読み後に撤回された希望を内容編集で書き換えない() throws Exception {
        Long requestId = newRequest(slotId, OLD_DATE, memberId);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch edit = new CountDownLatch(1);
        CompletableFuture<Void> editing = CompletableFuture.runAsync(() -> tx.executeWithoutResult(s -> {
            requestRepository.findById(requestId).orElseThrow();
            read.countDown();
            awaitLatch(edit);
            requestService.updateRequest(requestId, new UpdateShiftRequestRequest("AVAILABLE", "撤回後の編集"));
        }), executor);
        try {
            assertThat(read.await(20, TimeUnit.SECONDS)).isTrue();
            requestService.deleteRequest(requestId);
            String afterWithdrawal = snapshot();
            edit.countDown();
            assertThatThrownBy(() -> editing.get(20, TimeUnit.SECONDS)).hasCauseInstanceOf(BusinessException.class);
            assertThat(snapshot()).isEqualTo(afterWithdrawal);
        } finally {
            edit.countDown();
        }
    }

    @Test
    void 移動中の撤回は親ロック後に削除済みとなり生存希望を残さない() throws Exception {
        Long requestId = newRequest(slotId, OLD_DATE, memberId);
        AtomicReference<Thread> thread = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> withdrawn = new AtomicReference<>();
        tx.executeWithoutResult(s -> {
            scheduleRepository.findByIdForUpdate(scheduleId).orElseThrow();
            slotService.updateSlot(slotId, confirmedRequest(NEW_DATE), adminId);
            withdrawn.set(CompletableFuture.runAsync(() -> {
                thread.set(Thread.currentThread());
                requestService.deleteRequest(requestId);
            }, executor));
            awaitParentLock(thread);
        });
        withdrawn.get().get(20, TimeUnit.SECONDS);
        Boolean absent = tx.execute(s -> requestRepository.findById(requestId).isEmpty());
        assertThat(absent).isTrue();
    }

    @Test
    void 千件の希望は一括更新されSQL要求数が人数に比例しない() throws Exception {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 1000; i++) {
                saveRequest(scheduleId, slotId, OLD_DATE, newUser());
            }
        });
        try (var capture = SqlIntentCounter.captureCurrentThread()) {
            slotService.updateSlot(slotId, confirmedRequest(NEW_DATE), adminId);
            assertThat(capture.totalCount()).as("実 SQL 捕捉が有効で、N+1 がない").isBetween(1, 14);
            assertThat(capture.capturedSqls()).filteredOn(sql -> updatesTable(sql, "shift_requests")).hasSize(1);
            Long movedCount = tx.execute(s -> ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM shift_requests WHERE slot_id=:id AND slot_date=:day AND deleted_at IS NULL")
                    .setParameter("id", slotId).setParameter("day", NEW_DATE).getSingleResult()).longValue());
            assertThat(movedCount).isEqualTo(1000L);
        }
    }

    private boolean updatesTable(String sql, String table) {
        return sql.toLowerCase(Locale.ROOT).matches("(?s).*\\bupdate\\s+" + table + "\\b.*");
    }

    private org.springframework.test.web.servlet.ResultActions patchSlot(Long userId, Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/shifts/slots/{id}", slotId).with(authentication(auth(userId)))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private UsernamePasswordAuthenticationToken auth(Long userId) {
        return new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of());
    }

    private Map<String, Object> confirmedBody(LocalDate date) {
        return Map.of("slotDate", date.toString(), "moveRequestsConfirmed", true);
    }

    private UpdateShiftSlotRequest confirmedRequest(LocalDate date) {
        return objectMapper.convertValue(confirmedBody(date), UpdateShiftSlotRequest.class);
    }

    private Long newRequest(Long targetSlot, LocalDate date, Long userId) {
        return tx.execute(s -> saveRequest(scheduleId, targetSlot, date, userId));
    }

    private Long saveRequest(Long parentId, Long targetSlot, LocalDate date, Long userId) {
        return requestRepository.saveAndFlush(ShiftRequestEntity.builder().scheduleId(parentId).slotId(targetSlot)
                .slotDate(date).userId(userId).preference(ShiftPreference.PREFERRED).note("移動前の希望").build()).getId();
    }

    private Long newUser() {
        return newUser("ja");
    }

    private Long newUser(String locale) {
        UserEntity user = UserEntity.builder().email("cmp0127-" + suffix + "-" + users.size() + "@example.com")
                .lastName("日付移動").firstName("試験").displayName("日付移動試験")
                .status(UserEntity.UserStatus.ACTIVE).locale(locale).timezone("Asia/Tokyo").isSearchable(true).build();
        em.persist(user);
        users.add(user.getId());
        return user.getId();
    }

    private Long newTeam() {
        TeamEntity team = TeamEntity.builder().name("日付移動 " + suffix)
                .slug("c0127-" + suffix + "-" + teams.size()).visibility(TeamEntity.Visibility.PUBLIC)
                .supporterEnabled(true).build();
        em.persist(team);
        teams.add(team.getId());
        return team.getId();
    }

    private Long newSchedule(Long teamId) {
        Long id = scheduleRepository.saveAndFlush(ShiftScheduleEntity.builder().teamId(teamId).createdBy(adminId)
                .title("日付移動").periodType(ShiftPeriodType.MONTHLY).startDate(OLD_DATE.minusYears(1))
                .endDate(NEW_DATE.plusYears(1)).status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusYears(2)).build()).getId();
        schedules.add(id);
        return id;
    }

    private Long newSlot(Long parentId, LocalDate date) {
        return slotRepository.saveAndFlush(ShiftSlotEntity.builder().scheduleId(parentId).slotDate(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1).build()).getId();
    }

    private void assertDate(Long requestId, LocalDate date) {
        LocalDate actualDate = tx.execute(s -> requestRepository.findById(requestId).orElseThrow().getSlotDate());
        assertThat(actualDate).isEqualTo(date);
    }

    private String snapshot() {
        return tx.execute(s -> {
            List<?> slots = em.createNativeQuery("SELECT * FROM shift_slots WHERE schedule_id IN (:ids) ORDER BY id")
                    .setParameter("ids", schedules).getResultList();
            List<?> requests = em.createNativeQuery("SELECT * FROM shift_requests WHERE schedule_id IN (:ids) ORDER BY id")
                    .setParameter("ids", schedules).getResultList();
            return slots.stream().map(r -> Arrays.deepToString((Object[]) r)).toList().toString()
                    + requests.stream().map(r -> Arrays.deepToString((Object[]) r)).toList();
        });
    }

    private void awaitParentLock(AtomicReference<Thread> thread) {
        await().atMost(TIMEOUT).until(() -> thread.get() != null && Arrays.stream(thread.get().getStackTrace())
                .anyMatch(frame -> frame.getMethodName().equals("findByIdForUpdate")));
    }

    private void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("希望編集の開始待ちが期限を超えました");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("希望編集の待機が中断されました", e);
        }
    }
}
