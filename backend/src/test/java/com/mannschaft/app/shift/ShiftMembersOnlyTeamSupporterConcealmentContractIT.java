package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * CMP-261125-2114 回帰（AC-0159-94）: 非公開チーム（{@code MEMBERS_AND_ABOVE}）に在籍する MEMBER 未満の利用者
 * （是正前データのサポーター）は、シフトの一般メンバー向け操作・本人操作でも不在と同一の 404 になる。
 *
 * <p>main では変更依頼（作成・一覧）・シフト希望（提出・本人の更新/削除）・交代（作成・承諾・他人のキャンセル）が
 * {@code ScopeConcealingAccessGate} を通り、親チーム上限（{@code ScopeContentAccessGate#isBeyondTeamContentCap}）で
 * 404 に倒れていた。シフト管理ゲート {@code ShiftManageAccessGate} へ置き換えた後も同じ応答であることを固定する
 * （作成が 403、一覧・本人操作が 2xx に後退しない）。公開側のチームでは本戦役前と同一（対照）。</p>
 *
 * <p>F00 §20.1 の不変条件（非公開チームにサポーターは在籍しない）に反するデータを、memberships へ直接入れて作る。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261125-2114 回帰 AC-0159-94: 非公開チーム在籍のサポーターはシフトの一般操作でも 404")
class ShiftMembersOnlyTeamSupporterConcealmentContractIT extends AbstractMySqlIntegrationTest {

    private static final String SHIFTS = "/api/v1/shifts";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private RoleClaimResolver roleClaimResolver;
    @Autowired private StatusClaimResolver statusClaimResolver;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftSlotRepository slotRepository;
    @Autowired private ShiftRequestRepository requestRepository;
    @Autowired private ShiftSwapRequestRepository swapRepository;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    /** 非公開チームの検査対象。 */
    private Fixture privateTeam;
    /** 公開側チームの対照。 */
    private Fixture publicTeam;

    /** チームごとのフィクスチャ。 */
    private record Fixture(Long teamId, Long adminId, Long memberId, Long supporterId, Long scheduleId,
                           Long slotId, Long supporterRequestId, Long memberPendingSwapId) {
    }

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        privateTeam = fixture(TeamEntity.Visibility.MEMBERS_AND_ABOVE);
        publicTeam = fixture(TeamEntity.Visibility.PUBLIC);
        em.flush();
        em.clear();
    }

    @AfterTransaction
    void clearFlagCaches() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    // ───────── ShiftChangeRequestFacade ─────────

    @Test
    @DisplayName("変更依頼の作成: 非公開チーム在籍のサポーターは 403 ではなく不在と同一の 404（SHIFT_001）")
    void 変更依頼の作成は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(), changeRequestCreate(privateTeam)),
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, "変更依頼の作成");
    }

    @Test
    @DisplayName("変更依頼の一覧: 非公開チーム在籍のサポーターは 200 ではなく不在と同一の 404（SHIFT_001）")
    void 変更依頼の一覧は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(), changeRequestList(privateTeam)),
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, "変更依頼の一覧");
    }

    // ───────── ShiftRequestFacade ─────────

    @Test
    @DisplayName("シフト希望の提出: 非公開チーム在籍のサポーターは不在と同一の 404（SHIFT_001）")
    void シフト希望の提出は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(), requestSubmit(privateTeam)),
                ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND, "シフト希望の提出");
    }

    @Test
    @DisplayName("シフト希望の本人更新: 非公開チーム在籍のサポーターは所有者でも不在と同一の 404（SHIFT_003）")
    void シフト希望の本人更新は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(), requestUpdate(privateTeam)),
                ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND, "シフト希望の本人更新");
    }

    @Test
    @DisplayName("シフト希望の本人削除: 非公開チーム在籍のサポーターは所有者でも不在と同一の 404（SHIFT_003）")
    void シフト希望の本人削除は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(),
                        delete(SHIFTS + "/requests/{id}", privateTeam.supporterRequestId())),
                ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND, "シフト希望の本人削除");
        assertThat(requestRepository.findById(privateTeam.supporterRequestId())).as("副作用なし").isPresent();
    }

    // ───────── ShiftSwapFacade ─────────

    @Test
    @DisplayName("交代の作成: 非公開チーム在籍のサポーターは 403 ではなく不在と同一の 404（SHIFT_002）")
    void 交代の作成は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(), swapCreate(privateTeam)),
                ShiftErrorCode.SHIFT_SLOT_NOT_FOUND, "交代の作成");
    }

    @Test
    @DisplayName("交代の承諾: 非公開チーム在籍のサポーターは 403 ではなく不在と同一の 404（SHIFT_005）")
    void 交代の承諾は404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(),
                        post(SHIFTS + "/swap-requests/{id}/accept", privateTeam.memberPendingSwapId())),
                ShiftErrorCode.SWAP_REQUEST_NOT_FOUND, "交代の承諾");
    }

    @Test
    @DisplayName("交代の他人キャンセル: 非公開チーム在籍のサポーターは 403 ではなく不在と同一の 404（SHIFT_005）")
    void 交代の他人キャンセルは404() throws Exception {
        assertNotFound(perform(privateTeam.supporterId(),
                        delete(SHIFTS + "/swap-requests/{id}", privateTeam.memberPendingSwapId())),
                ShiftErrorCode.SWAP_REQUEST_NOT_FOUND, "交代の他人キャンセル");
    }

    // ───────── 対照 ─────────

    @Test
    @DisplayName("対照: 非公開チームの一般メンバーは変更依頼の一覧・作成に成功する（上限は MEMBER 以上に掛からない）")
    void 非公開チームの一般メンバーは通る() throws Exception {
        assertStatus(perform(privateTeam.memberId(), changeRequestList(privateTeam)), 200, "一覧");
        assertSuccess(perform(privateTeam.memberId(), changeRequestCreate(privateTeam)), "作成");
    }

    @Test
    @DisplayName("対照: 公開チームのサポーターは従来どおり（一覧・本人更新は通り、作成・提出・交代作成は所属者の 403）")
    void 公開チームのサポーターは従来どおり() throws Exception {
        assertStatus(perform(publicTeam.supporterId(), changeRequestList(publicTeam)), 200, "一覧");
        assertSuccess(perform(publicTeam.supporterId(), requestUpdate(publicTeam)), "本人更新");
        assertError(perform(publicTeam.supporterId(), changeRequestCreate(publicTeam)), 403,
                CommonErrorCode.COMMON_002, "変更依頼の作成");
        assertError(perform(publicTeam.supporterId(), requestSubmit(publicTeam)), 403,
                CommonErrorCode.COMMON_002, "シフト希望の提出");
        assertError(perform(publicTeam.supporterId(), swapCreate(publicTeam)), 403,
                CommonErrorCode.COMMON_002, "交代の作成");
    }

    // ───────── リクエスト ─────────

    private MockHttpServletRequestBuilder changeRequestCreate(Fixture f) throws Exception {
        return post(SHIFTS + "/change-requests").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("scheduleId", f.scheduleId(), "requestType", "PRE_CONFIRM_EDIT",
                        "reason", "回帰")));
    }

    private MockHttpServletRequestBuilder changeRequestList(Fixture f) {
        return get(SHIFTS + "/change-requests").param("scheduleId", f.scheduleId().toString());
    }

    private MockHttpServletRequestBuilder requestSubmit(Fixture f) throws Exception {
        return post(SHIFTS + "/requests").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("scheduleId", f.scheduleId(), "slotDate", "2030-01-05",
                        "preference", "PREFERRED")));
    }

    private MockHttpServletRequestBuilder requestUpdate(Fixture f) throws Exception {
        return patch(SHIFTS + "/requests/{id}", f.supporterRequestId()).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("preference", "AVAILABLE", "note", "回帰")));
    }

    private MockHttpServletRequestBuilder swapCreate(Fixture f) throws Exception {
        return post(SHIFTS + "/swap-requests").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("slotId", f.slotId(), "reason", "回帰", "openCall", true)));
    }

    // ───────── 検査ヘルパー ─────────

    private MvcResult perform(Long userId, MockHttpServletRequestBuilder builder) throws Exception {
        String bearer = "Bearer " + authTokenService.issueAccessToken(userId, roleClaimResolver.resolveRoles(userId),
                statusClaimResolver.isPendingParentalConsent(userId));
        return mockMvc.perform(builder.header("Authorization", bearer)).andReturn();
    }

    private static void assertStatus(MvcResult result, int expected, String label) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("%s: %s", label, result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private static void assertSuccess(MvcResult result, String label) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("%s は成功する: %s", label, result.getResponse().getContentAsString())
                .isBetween(200, 299);
    }

    private static void assertError(MvcResult result, int expected, ErrorCode code, String label) throws Exception {
        assertStatus(result, expected, label);
        assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.error.code"))
                .as(label).isEqualTo(code.getCode());
    }

    private static void assertNotFound(MvcResult result, ErrorCode code, String label) throws Exception {
        assertError(result, 404, code, label);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    // ───────── フィクスチャ ─────────

    private Fixture fixture(TeamEntity.Visibility visibility) {
        Long teamId = teamRepository.saveAndFlush(TeamEntity.builder()
                .name("AC-0159-94 回帰チーム").slug("mocap-" + UUID.randomUUID().toString().substring(0, 20))
                .template("sports").visibility(visibility).supporterEnabled(true).build()).getId();
        Long adminId = newUser();
        Long memberId = newUser();
        Long member2Id = newUser();
        Long supporterId = newUser();
        MembershipTestHelper.insertMembership(em, adminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, member2Id, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        // 是正前データ: 非公開チームにも在籍するサポーター（F00 §20.1 の不変条件の違反）。
        MembershipTestHelper.insertMembership(em, supporterId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER);

        Long scheduleId = scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId).title("回帰シフト表").periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2030, 1, 1)).endDate(LocalDate.of(2030, 1, 7))
                .status(ShiftScheduleStatus.COLLECTING).createdBy(adminId).build()).getId();
        Long slotId = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId).slotDate(LocalDate.of(2030, 1, 2))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).requiredCount(2)
                .assignedUserIds("[" + supporterId + "," + memberId + "]").build()).getId();
        Long supporterRequestId = requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(scheduleId).userId(supporterId).slotDate(LocalDate.of(2030, 1, 2))
                .preference(ShiftPreference.PREFERRED).note("回帰").build()).getId();
        Long memberPendingSwapId = swapRepository.save(ShiftSwapRequestEntity.builder()
                .slotId(slotId).requesterId(memberId).status(SwapRequestStatus.PENDING)
                .reason("回帰").isOpenCall(true).recipientMode("OPEN_CALL").build()).getId();
        return new Fixture(teamId, adminId, memberId, supporterId, scheduleId, slotId, supporterRequestId,
                memberPendingSwapId);
    }

    private Long newUser() {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        MembershipTestHelper.insertActiveUser(em, id);
        return id;
    }
}
