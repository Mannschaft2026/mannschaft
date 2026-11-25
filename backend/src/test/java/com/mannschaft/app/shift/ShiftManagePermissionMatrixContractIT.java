package com.mannschaft.app.shift;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.RoleClaimResolver;
import com.mannschaft.app.auth.service.StatusClaimResolver;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.MemberWorkConstraintRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import com.mannschaft.app.shift.service.ShiftHourlyRateDeletionFacade;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * CMP-261125-2114（試練）: シフト管理の操作を ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけに許可する。
 *
 * <p>マスター裁定（2026-11-25）:</p>
 * <ol>
 *   <li>シフト管理の全操作は ADMIN と MANAGE_SHIFTS を持つ DEPUTY_ADMIN だけ。</li>
 *   <li>閲覧（未公開シフト表・割当の非マスク・他人の交代/変更依頼の全件・希望一覧/サマリー・PDF の管理者出力・
 *       ダッシュボード「要対応」のシフト希望）と時給の閲覧は、チームのシフト設定の 2 つの切替で
 *       MANAGE_SHIFTS 無しの DEPUTY_ADMIN にも許すかを決める（既定 OFF）。OFF なら一般メンバーと同じ。</li>
 *   <li>チームロールを持たない SYSTEM_ADMIN は操作も閲覧も拒否（docs/security/03 §3.6）。</li>
 *   <li>本人の操作（希望の更新・削除、交代のキャンセル、自分の時給の閲覧）は維持。</li>
 *   <li>秘匿は現行どおり（越境 404、同チームの権限不足 403、run 系は同チーム非管理者も 404）。</li>
 * </ol>
 *
 * <p><b>切替の設定 API（仮置き。出陣で確定する）</b>: {@code PATCH /api/v1/teams/{slug}/shift-settings} の本文に
 * {@value #TOGGLE_SHIFT_VIEW}（閲覧切替）と {@value #TOGGLE_HOURLY_RATE_VIEW}（時給閲覧切替）を足す想定。
 * 列は {@code team_shift_settings.deputy_shift_view_enabled} / {@code deputy_hourly_rate_view_enabled}（既定 0）を想定。
 * 前提作成は ADMIN が同 API を叩くだけにして（現状は未知の項目として無視される）、受け入れ条件の
 * アサーションそのもので落ちるようにしてある。</p>
 *
 * <p>起動方式: 基底 {@link AbstractMySqlIntegrationTest} の MOCK 設定をそのまま継承し（構成キーを増やさない）、
 * 実 JWT・実 Security フィルタ・実 Facade/Service・Testcontainers MySQL を通す。fixture はテスト TX 内で
 * 作り終了時に rollback する（AFTER_COMMIT の通知は本クラスでは観測しない。通知の宛先は
 * {@link RoleService#getAdminAndPermittedDeputyUserIdsByTeamId} を通知リスナーと同じ窓口として照合する）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261125-2114: シフト管理の MANAGE_SHIFTS 判定（操作カテゴリ×ロールのマトリクス・試練）")
class ShiftManagePermissionMatrixContractIT extends AbstractMySqlIntegrationTest {

    /** 閲覧切替の仮の項目名（出陣で確定）。 */
    static final String TOGGLE_SHIFT_VIEW = "deputyShiftViewEnabled";
    /** 時給閲覧切替の仮の項目名（出陣で確定）。 */
    static final String TOGGLE_HOURLY_RATE_VIEW = "deputyHourlyRateViewEnabled";

    private static final String SHIFTS = "/api/v1/shifts";
    private static final String MANAGE_SHIFTS = "MANAGE_SHIFTS";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AuthTokenService authTokenService;
    @Autowired private RoleClaimResolver roleClaimResolver;
    @Autowired private StatusClaimResolver statusClaimResolver;
    @Autowired private TeamRepository teamRepository;
    @Autowired private ShiftScheduleRepository scheduleRepository;
    @Autowired private ShiftSlotRepository slotRepository;
    @Autowired private ShiftRequestRepository requestRepository;
    @Autowired private ShiftChangeRequestRepository changeRequestRepository;
    @Autowired private ShiftSwapRequestRepository swapRepository;
    @Autowired private ShiftAssignmentRunRepository runRepository;
    @Autowired private ShiftAssignmentRepository assignmentRepository;
    @Autowired private ShiftPositionRepository positionRepository;
    @Autowired private ShiftHourlyRateRepository hourlyRateRepository;
    @Autowired private ShiftHourlyRateDeletionFacade hourlyRateDeletionFacade;
    @Autowired private MemberWorkConstraintRepository workConstraintRepository;
    @Autowired private RoleService roleService;
    @Autowired private FeatureFlagRepository featureFlagRepository;
    @Autowired private CacheManager cacheManager;
    @PersistenceContext private EntityManager em;

    /** 主体。チームT（検査対象）と O（他チーム）。 */
    enum Actor {
        /** T の ADMIN（memberships + user_roles）。 */
        ADMIN,
        /** T の 2 人目の ADMIN（降格の検査用）。 */
        ADMIN2,
        /** T の DEPUTY_ADMIN ＋ 権限グループ経由の MANAGE_SHIFTS。 */
        DEPUTY_WITH,
        /** T の DEPUTY_ADMIN、MANAGE_SHIFTS 無し（権限グループ無し）。 */
        DEPUTY_WITHOUT,
        /** T の DEPUTY_ADMIN、権限が空の権限グループだけを持つ（攻め口: 空）。 */
        DEPUTY_EMPTY_GROUP,
        /** T では MANAGE_SHIFTS 無しの DEPUTY、O では MANAGE_SHIFTS 付きの DEPUTY（攻め口: 境界）。 */
        CROSS_DEPUTY,
        /** user_roles にだけ T の ADMIN がある（memberships 無し）。 */
        USER_ROLES_ONLY_ADMIN,
        /** user_roles にだけ T の DEPUTY_ADMIN がある（memberships 無し、MANAGE_SHIFTS 無し）。 */
        USER_ROLES_ONLY_DEPUTY,
        /** T の一般メンバー（希望・交代・変更依頼の持ち主）。 */
        MEMBER,
        /** T の一般メンバー（交代の受諾者）。 */
        MEMBER2,
        /** T の SUPPORTER。 */
        SUPPORTER,
        /** O の ADMIN（T には無所属）。 */
        OTHER_ADMIN,
        /** チームロールを持たない SYSTEM_ADMIN。 */
        SYSTEM_ADMIN
    }

    /**
     * 操作系 EP（全 EP）。memberDeny = 同チーム非管理者（現行の一般メンバー）への応答、
     * outsiderDeny = 非所属者（他チーム ADMIN）への応答。
     */
    enum Op {
        A_SCHEDULE_CREATE('a', 403, 403),
        A_SCHEDULE_UPDATE('a', 403, 404),
        A_SCHEDULE_TRANSITION('a', 403, 404),
        A_SCHEDULE_DELETE('a', 403, 404),
        A_SCHEDULE_DUPLICATE('a', 403, 404),
        B_SLOT_CREATE('b', 403, 404),
        B_SLOT_BULK_CREATE('b', 403, 404),
        B_SLOT_UPDATE('b', 403, 404),
        B_SLOT_DELETE('b', 403, 404),
        B_SLOT_ASSIGNMENT_PATCH('b', 403, 404),
        B_SLOT_ASSIGNMENT_MOVE('b', 403, 404),
        C_AUTO_ASSIGN_RUN('c', 403, 404),
        C_AUTO_ASSIGN_CONFIRM('c', 404, 404),
        C_AUTO_ASSIGN_REVOKE('c', 404, 404),
        C_ASSIGNMENT_RUN_LIST('c', 403, 404),
        C_ASSIGNMENT_RUN_DETAIL('c', 404, 404),
        C_ASSIGNMENT_RUN_VISUAL_REVIEW('c', 404, 404),
        D_REQUEST_UPDATE_OTHERS('d', 403, 404),
        D_REQUEST_DELETE_OTHERS('d', 403, 404),
        E_SWAP_RESOLVE('e', 403, 404),
        E_SWAP_CANCEL_OTHERS('e', 403, 404),
        F_CHANGE_REQUEST_REVIEW('f', 403, 404),
        G_WORK_CONSTRAINT_LIST('g', 403, 403),
        G_WORK_CONSTRAINT_GET_OTHERS('g', 403, 403),
        G_WORK_CONSTRAINT_UPSERT('g', 403, 403),
        G_WORK_CONSTRAINT_DELETE('g', 403, 403),
        G_WORK_CONSTRAINT_DEFAULT_UPSERT('g', 403, 403),
        G_WORK_CONSTRAINT_DEFAULT_DELETE('g', 403, 403),
        G_HOURLY_RATE_CREATE('g', 403, 403),
        H_POSITION_CREATE('h', 403, 403),
        H_POSITION_UPDATE('h', 403, 404),
        H_POSITION_DELETE('h', 403, 404),
        I_SHIFT_SETTINGS_UPDATE('i', 403, 403),
        K_SCHEDULE_REMIND('k', 403, 404);

        final char category;
        final int memberDeny;
        final int outsiderDeny;

        Op(char category, int memberDeny, int outsiderDeny) {
            this.category = category;
            this.memberDeny = memberDeny;
            this.outsiderDeny = outsiderDeny;
        }
    }

    /** 閲覧系（切替の対象）。outsiderDeny = 非所属者への応答。 */
    enum View {
        SCHEDULE_GET_UNPUBLISHED(404),
        SCHEDULE_LIST_INCLUDES_UNPUBLISHED(403),
        SLOT_ASSIGNMENTS_UNMASKED(404),
        D_REQUEST_LIST(404),
        D_REQUEST_SUMMARY(404),
        SCHEDULE_SUMMARY(404),
        SWAP_LIST_ALL(403),
        CHANGE_REQUEST_LIST_ALL(404),
        CHANGE_REQUEST_GET_OTHERS(404),
        J_PDF_ADMIN_OUTPUT(404),
        L_DASHBOARD_SHIFT_REQUEST(403);

        final int outsiderDeny;

        View(int outsiderDeny) {
            this.outsiderDeny = outsiderDeny;
        }
    }

    /** AC-1 の代表 EP（カテゴリごとに 1 つ。閲覧系の d/j/l は View 側で検査する）。 */
    static final List<Op> REPRESENTATIVES = List.of(
            Op.A_SCHEDULE_UPDATE, Op.B_SLOT_CREATE, Op.C_AUTO_ASSIGN_RUN, Op.E_SWAP_RESOLVE,
            Op.F_CHANGE_REQUEST_REVIEW, Op.G_WORK_CONSTRAINT_UPSERT, Op.G_HOURLY_RATE_CREATE,
            Op.H_POSITION_CREATE, Op.I_SHIFT_SETTINGS_UPDATE, Op.K_SCHEDULE_REMIND);

    private final Map<Actor, Long> actors = new EnumMap<>(Actor.class);
    private Long teamId;
    private String teamSlug;
    private Long otherTeamId;
    private Long deputyWithGroupId;

    private Long scheduleId;          // COLLECTING（主たる操作対象）
    private Long draftScheduleId;     // DRAFT（未公開）
    private Long slotId;              // MEMBER 割当済み
    private Long slot2Id;             // 移動先（空き）
    private Long memberRequestId;     // MEMBER の希望
    private Long othersRequestId;     // MEMBER2 の希望（他人の希望を操作する検査の対象）
    private Long deputyOwnRequestId;  // DEPUTY_WITHOUT 本人の希望
    private Long memberOwnRequest2Id; // MEMBER 本人の 2 件目（本人操作の検査用）
    private Long changeRequestId;     // MEMBER の変更依頼（OPEN）
    private Long deputyOwnChangeRequestId;
    private Long acceptedSwapId;      // MEMBER→MEMBER2 受諾済み（承認・却下の対象）
    private Long othersPendingSwapId; // MEMBER2 の PENDING（他人キャンセルの対象）
    private Long deputyOwnSwapId;     // DEPUTY_WITHOUT 本人の PENDING
    private Long memberOwnSwapId;     // MEMBER 本人の PENDING（本人キャンセル用）
    private Long succeededRunId;
    private Long confirmedRunId;
    private Long proposedAssignmentId;
    private Long positionId;
    private Long memberHourlyRateId;

    @BeforeEach
    void setUp() {
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_ENABLED");
        FeatureFlagTestSupport.enable(featureFlagRepository, cacheManager, "FEATURE_SHIFT_AUTO_ASSIGN_ENABLED");
        teamId = saveTeam();
        teamSlug = teamRepository.findById(teamId).orElseThrow().getSlug();
        otherTeamId = saveTeam();
        for (Actor actor : Actor.values()) {
            long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            MembershipTestHelper.insertActiveUser(em, id);
            actors.put(actor, id);
        }
        for (Actor a : List.of(Actor.ADMIN, Actor.ADMIN2, Actor.DEPUTY_WITH, Actor.DEPUTY_WITHOUT,
                Actor.DEPUTY_EMPTY_GROUP, Actor.CROSS_DEPUTY, Actor.MEMBER, Actor.MEMBER2)) {
            MembershipTestHelper.insertMembership(em, id(a), ScopeType.TEAM, teamId, RoleKind.MEMBER);
        }
        MembershipTestHelper.insertMembership(em, id(Actor.SUPPORTER), ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        MembershipTestHelper.insertUserRole(em, id(Actor.ADMIN), "ADMIN", teamId, null);
        MembershipTestHelper.insertUserRole(em, id(Actor.ADMIN2), "ADMIN", teamId, null);
        for (Actor a : List.of(Actor.DEPUTY_WITH, Actor.DEPUTY_WITHOUT, Actor.DEPUTY_EMPTY_GROUP,
                Actor.CROSS_DEPUTY, Actor.USER_ROLES_ONLY_DEPUTY)) {
            MembershipTestHelper.insertUserRole(em, id(a), "DEPUTY_ADMIN", teamId, null);
        }
        MembershipTestHelper.insertUserRole(em, id(Actor.USER_ROLES_ONLY_ADMIN), "ADMIN", teamId, null);
        MembershipTestHelper.insertUserRole(em, id(Actor.SYSTEM_ADMIN), "SYSTEM_ADMIN", null, null);
        // 他チーム O: OTHER_ADMIN は ADMIN、CROSS_DEPUTY は MANAGE_SHIFTS 付き DEPUTY。
        MembershipTestHelper.insertMembership(em, id(Actor.OTHER_ADMIN), ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, id(Actor.OTHER_ADMIN), "ADMIN", otherTeamId, null);
        // CROSS_DEPUTY は T にも O にも所属する（user_roles は (user, team) ごとに 1 行）。
        MembershipTestHelper.insertMembership(em, id(Actor.CROSS_DEPUTY), ScopeType.TEAM, otherTeamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, id(Actor.CROSS_DEPUTY), "DEPUTY_ADMIN", otherTeamId, null);

        deputyWithGroupId = createPermissionGroup(teamId, true);
        assignGroup(id(Actor.DEPUTY_WITH), deputyWithGroupId);
        assignGroup(id(Actor.DEPUTY_EMPTY_GROUP), createPermissionGroup(teamId, false));
        assignGroup(id(Actor.CROSS_DEPUTY), createPermissionGroup(otherTeamId, true));

        scheduleId = saveSchedule(ShiftScheduleStatus.COLLECTING, "主たるシフト表");
        draftScheduleId = saveSchedule(ShiftScheduleStatus.DRAFT, "未公開シフト表");
        slotId = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId).slotDate(LocalDate.of(2030, 1, 2))
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(12, 0)).requiredCount(2)
                .assignedUserIds("[" + id(Actor.MEMBER) + "]").build()).getId();
        slot2Id = slotRepository.save(ShiftSlotEntity.builder()
                .scheduleId(scheduleId).slotDate(LocalDate.of(2030, 1, 3))
                .startTime(LocalTime.of(13, 0)).endTime(LocalTime.of(17, 0)).requiredCount(2).build()).getId();

        memberRequestId = saveRequest(id(Actor.MEMBER), LocalDate.of(2030, 1, 2));
        othersRequestId = saveRequest(id(Actor.MEMBER2), LocalDate.of(2030, 1, 2));
        memberOwnRequest2Id = saveRequest(id(Actor.MEMBER), LocalDate.of(2030, 1, 4));
        deputyOwnRequestId = saveRequest(id(Actor.DEPUTY_WITHOUT), LocalDate.of(2030, 1, 2));

        changeRequestId = saveChangeRequest(id(Actor.MEMBER));
        deputyOwnChangeRequestId = saveChangeRequest(id(Actor.DEPUTY_WITHOUT));

        ShiftSwapRequestEntity accepted = buildSwap(id(Actor.MEMBER));
        accepted.accept(id(Actor.MEMBER2));
        acceptedSwapId = swapRepository.save(accepted).getId();
        othersPendingSwapId = swapRepository.save(buildSwap(id(Actor.MEMBER2))).getId();
        memberOwnSwapId = swapRepository.save(buildSwap(id(Actor.MEMBER))).getId();
        deputyOwnSwapId = swapRepository.save(buildSwap(id(Actor.DEPUTY_WITHOUT))).getId();

        succeededRunId = saveRun(ShiftAssignmentRunStatus.SUCCEEDED);
        confirmedRunId = saveRun(ShiftAssignmentRunStatus.CONFIRMED);
        proposedAssignmentId = assignmentRepository.save(ShiftAssignmentEntity.builder()
                .slotId(slot2Id).userId(id(Actor.MEMBER2)).runId(confirmedRunId)
                .status(ShiftAssignmentStatus.PROPOSED).assignedBy(id(Actor.ADMIN)).build()).getId();

        positionId = positionRepository.save(ShiftPositionEntity.builder()
                .teamId(teamId).name("ホール").displayOrder(1).isActive(true).build()).getId();
        memberHourlyRateId = saveHourlyRate(id(Actor.MEMBER), "1200.00");
        saveHourlyRate(id(Actor.DEPUTY_WITHOUT), "1300.00");
        // 勤務制約: MEMBER2 の個別とチーム既定（削除・取得が認可後に 404 へ落ちず、許可者は成功できるように）。
        workConstraintRepository.save(MemberWorkConstraintEntity.builder()
                .teamId(teamId).userId(id(Actor.MEMBER2)).maxMonthlyDays(18).build());
        workConstraintRepository.save(MemberWorkConstraintEntity.builder()
                .teamId(teamId).userId(null).maxMonthlyDays(22).build());

        em.flush();
        em.clear();
    }

    @AfterTransaction
    void clearFlagCaches() {
        FeatureFlagTestSupport.clearFlagCaches(cacheManager);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-1: ADMIN と DEPUTY+（と user_roles のみの ADMIN）は全カテゴリ成功（回帰）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> ac1OpCases() {
        return Stream.of(Actor.ADMIN, Actor.DEPUTY_WITH, Actor.USER_ROLES_ONLY_ADMIN)
                .flatMap(a -> REPRESENTATIVES.stream().map(op -> Arguments.of(a, op)));
    }

    @ParameterizedTest(name = "AC-1 {0} × {1}")
    @MethodSource("ac1OpCases")
    @DisplayName("AC-1/AC-10: ADMIN・DEPUTY+MANAGE_SHIFTS・user_roles のみの ADMIN は各カテゴリの代表操作に成功する")
    void AC1_管理者と権限付き副管理者は代表操作に成功する(Actor actor, Op op) throws Exception {
        MvcResult result = perform(actor, request(op));
        assertAllowed(result, op == Op.K_SCHEDULE_REMIND, actor + " × " + op);
    }

    static Stream<Arguments> ac1ViewCases() {
        return Stream.of(Actor.ADMIN, Actor.DEPUTY_WITH, Actor.USER_ROLES_ONLY_ADMIN)
                .flatMap(a -> Stream.of(View.values())
                        // 一覧（?teamId=）と PDF は是正前から在籍を要求する（user_roles のみの ADMIN は 403）ので対象外。
                        .filter(v -> !(a == Actor.USER_ROLES_ONLY_ADMIN
                                && (v == View.J_PDF_ADMIN_OUTPUT || v == View.SCHEDULE_LIST_INCLUDES_UNPUBLISHED)))
                        .map(v -> Arguments.of(a, v)));
    }

    @ParameterizedTest(name = "AC-1 {0} × {1}")
    @MethodSource("ac1ViewCases")
    @DisplayName("AC-1: ADMIN・DEPUTY+MANAGE_SHIFTS は閲覧系（d/j/l を含む）を管理者の形で見られる")
    void AC1_管理者と権限付き副管理者は閲覧系を管理者の形で見る(Actor actor, View view) throws Exception {
        assertPrivilegedView(view, actor);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-2: DEPUTY−（切替 OFF）は操作系すべて拒否、副作用なし（攻め口: 空・境界を含む）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> ac2Cases() {
        return Stream.of(Actor.DEPUTY_WITHOUT, Actor.DEPUTY_EMPTY_GROUP, Actor.CROSS_DEPUTY)
                .flatMap(a -> Stream.of(Op.values()).map(op -> Arguments.of(a, op)));
    }

    @ParameterizedTest(name = "AC-2 {0} × {1}")
    @MethodSource("ac2Cases")
    @DisplayName("AC-2: MANAGE_SHIFTS 無しの DEPUTY（空の権限グループ・他チームでだけ保有を含む）は全操作が一般メンバーと同じ拒否で、DB に副作用が無い")
    void AC2_権限なし副管理者は全操作を拒否され副作用なし(Actor actor, Op op) throws Exception {
        String before = fingerprint();
        MvcResult result = perform(actor, request(op));
        assertStatus(result, op.memberDeny, actor + " × " + op);
        assertThat(fingerprint()).as("拒否された %s × %s で DB が変わった", actor, op).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-2（途中失敗）: 権限なし DEPUTY の一括枠作成は全体不成立（1 枠も作られない）")
    void AC2_権限なし副管理者の一括作成は全体不成立() throws Exception {
        long before = countSlots();
        MvcResult result = perform(Actor.DEPUTY_WITHOUT, request(Op.B_SLOT_BULK_CREATE));
        assertStatus(result, 403, "bulk");
        assertThat(countSlots()).isEqualTo(before);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-3: DEPUTY−（切替 OFF）の閲覧は一般メンバーと同じ
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-3 {0}")
    @EnumSource(View.class)
    @DisplayName("AC-3: 閲覧切替 OFF（既定）の権限なし DEPUTY は一般メンバーと同じ形でしか見られない")
    void AC3_切替OFFの権限なし副管理者の閲覧は一般メンバーと同じ(View view) throws Exception {
        assertMemberView(view, Actor.DEPUTY_WITHOUT);
    }

    @ParameterizedTest(name = "AC-3 対照 MEMBER {0}")
    @EnumSource(View.class)
    @DisplayName("AC-3 対照: 一般メンバーの閲覧の形（現行の固定）")
    void AC3対照_一般メンバーの閲覧の形(View view) throws Exception {
        assertMemberView(view, Actor.MEMBER);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-4: DEPUTY−（閲覧切替 ON）は閲覧だけ許可、操作は拒否
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-4 閲覧 {0}")
    @EnumSource(View.class)
    @DisplayName("AC-4: 閲覧切替 ON の権限なし DEPUTY は閲覧系を管理者の形で見られる")
    void AC4_閲覧切替ONなら権限なし副管理者も閲覧できる(View view) throws Exception {
        setToggles(true, false);
        assertPrivilegedView(view, Actor.DEPUTY_WITHOUT);
    }

    @ParameterizedTest(name = "AC-4 操作 {0}")
    @EnumSource(Op.class)
    @DisplayName("AC-4: 閲覧切替・時給切替が ON でも、権限なし DEPUTY の操作は AC-2 どおり拒否され副作用なし")
    void AC4_切替ONでも権限なし副管理者の操作は拒否(Op op) throws Exception {
        setToggles(true, true);
        String before = fingerprint();
        MvcResult result = perform(Actor.DEPUTY_WITHOUT, request(op));
        assertStatus(result, op.memberDeny, "toggle-on × " + op);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    @DisplayName("AC-4: 閲覧切替 ON でも、MEMBER の閲覧は広がらない（切替は DEPUTY_ADMIN だけが対象）")
    void AC4_切替ONでも一般メンバーの閲覧は広がらない() throws Exception {
        setToggles(true, true);
        for (View view : View.values()) {
            assertMemberView(view, Actor.MEMBER);
        }
        assertStatus(perform(Actor.MEMBER, hourlyRateOf(id(Actor.MEMBER2))), 403, "member hourly");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 切替の設定 API（仮置き）
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("切替の既定値は OFF（GET shift-settings が 2 つの切替を false で返す）")
    void 切替の既定値はOFF() throws Exception {
        MvcResult result = perform(Actor.ADMIN, get("/api/v1/teams/{slug}/shift-settings", teamSlug));
        assertStatus(result, 200, "get settings");
        String body = result.getResponse().getContentAsString();
        assertThat(readBoolean(body, TOGGLE_SHIFT_VIEW)).as(TOGGLE_SHIFT_VIEW).isFalse();
        assertThat(readBoolean(body, TOGGLE_HOURLY_RATE_VIEW)).as(TOGGLE_HOURLY_RATE_VIEW).isFalse();
    }

    @Test
    @DisplayName("ADMIN は切替を ON にでき、保存されて GET で読み戻せる")
    void ADMINは切替をONにでき保存される() throws Exception {
        setToggles(true, true);
        MvcResult result = perform(Actor.ADMIN, get("/api/v1/teams/{slug}/shift-settings", teamSlug));
        String body = result.getResponse().getContentAsString();
        assertThat(readBoolean(body, TOGGLE_SHIFT_VIEW)).as(TOGGLE_SHIFT_VIEW).isTrue();
        assertThat(readBoolean(body, TOGGLE_HOURLY_RATE_VIEW)).as(TOGGLE_HOURLY_RATE_VIEW).isTrue();
    }

    @Test
    @DisplayName("切替だけの PATCH は 200 で、リマインド 3 項目は現在値のまま維持される（省略は変更なし）")
    void 切替だけのPATCHはリマインドを維持する() throws Exception {
        // 既定: 48h=ON, 24h=ON, 12h=OFF
        assertStatus(perform(Actor.ADMIN, patch("/api/v1/teams/{slug}/shift-settings", teamSlug)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(TOGGLE_SHIFT_VIEW, true)))),
                200, "切替だけ");
        String body = perform(Actor.ADMIN, get("/api/v1/teams/{slug}/shift-settings", teamSlug))
                .getResponse().getContentAsString();
        assertThat(readBoolean(body, "reminder48hEnabled")).isTrue();
        assertThat(readBoolean(body, "reminder24hEnabled")).isTrue();
        assertThat(readBoolean(body, "reminder12hEnabled")).isFalse();
        assertThat(readBoolean(body, TOGGLE_SHIFT_VIEW)).isTrue();
        assertThat(readBoolean(body, TOGGLE_HOURLY_RATE_VIEW)).isFalse();
    }

    @Test
    @DisplayName("リマインドの一部だけを送った PATCH は、送った項目だけ反映し、他は維持する")
    void リマインドの一部だけのPATCHは部分更新() throws Exception {
        assertStatus(perform(Actor.ADMIN, patch("/api/v1/teams/{slug}/shift-settings", teamSlug)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reminder12hEnabled", true)))),
                200, "12h だけ");
        String body = perform(Actor.ADMIN, get("/api/v1/teams/{slug}/shift-settings", teamSlug))
                .getResponse().getContentAsString();
        assertThat(readBoolean(body, "reminder48hEnabled")).isTrue();
        assertThat(readBoolean(body, "reminder24hEnabled")).isTrue();
        assertThat(readBoolean(body, "reminder12hEnabled")).isTrue();
    }

    @Test
    @DisplayName("保存後に有効なリマインドが 1 つも無くなる PATCH は 400 で、保存されない")
    void 全リマインドOFFになるPATCHは400() throws Exception {
        MvcResult result = perform(Actor.ADMIN, patch("/api/v1/teams/{slug}/shift-settings", teamSlug)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                        "reminder48hEnabled", false, "reminder24hEnabled", false))));
        assertError(result, 400, "COMMON_001", "全 OFF");
        String body = perform(Actor.ADMIN, get("/api/v1/teams/{slug}/shift-settings", teamSlug))
                .getResponse().getContentAsString();
        assertThat(readBoolean(body, "reminder48hEnabled")).isTrue();
        assertThat(readBoolean(body, "reminder24hEnabled")).isTrue();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-5: 時給の閲覧
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-5: 時給切替 OFF（閲覧切替だけ ON を含む）の権限なし DEPUTY は他人の時給を見られない")
    void AC5_時給切替OFFなら他人の時給を見られない() throws Exception {
        assertStatus(perform(Actor.DEPUTY_WITHOUT, hourlyRateOf(id(Actor.MEMBER))), 403, "OFF/単体");
        assertStatus(perform(Actor.DEPUTY_WITHOUT, teamHourlyRates()), 403, "OFF/一括");
        setToggles(true, false);
        assertStatus(perform(Actor.DEPUTY_WITHOUT, hourlyRateOf(id(Actor.MEMBER))), 403, "閲覧切替のみON/単体");
        assertStatus(perform(Actor.DEPUTY_WITHOUT, teamHourlyRates()), 403, "閲覧切替のみON/一括");
    }

    @Test
    @DisplayName("AC-5: 時給切替 ON なら権限なし DEPUTY も他人の時給（単体・チーム一括）を見られる")
    void AC5_時給切替ONなら他人の時給を見られる() throws Exception {
        setToggles(false, true);
        MvcResult single = perform(Actor.DEPUTY_WITHOUT, hourlyRateOf(id(Actor.MEMBER)));
        assertStatus(single, 200, "ON/単体");
        assertThat(longs(single, "$.data[*].id")).contains(memberHourlyRateId);
        MvcResult team = perform(Actor.DEPUTY_WITHOUT, teamHourlyRates());
        assertStatus(team, 200, "ON/一括");
        assertThat(longs(team, "$.data[*].userId")).contains(id(Actor.MEMBER));
    }

    @Test
    @DisplayName("AC-5: 時給の削除は常に MANAGE_SHIFTS 必須（時給切替 ON の権限なし DEPUTY も 403）、権限付きは削除できる")
    void AC5_時給の削除はMANAGE_SHIFTS必須() throws Exception {
        setToggles(true, true);
        Throwable denied = catchThrowable(() ->
                hourlyRateDeletionFacade.deleteHourlyRate(memberHourlyRateId, id(Actor.DEPUTY_WITHOUT)));
        assertThat(denied).as("権限なし DEPUTY の削除").isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) denied).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002);
        assertThat(hourlyRateRepository.findById(memberHourlyRateId)).isPresent();

        hourlyRateDeletionFacade.deleteHourlyRate(memberHourlyRateId, id(Actor.DEPUTY_WITH));
        em.flush();
        em.clear();
        assertThat(hourlyRateRepository.findById(memberHourlyRateId)).isEmpty();
    }

    @Test
    @DisplayName("AC-5: 本人は自分の時給を従来どおり見られる（権限なし DEPUTY・MEMBER、切替 OFF）")
    void AC5_本人は自分の時給を見られる() throws Exception {
        assertStatus(perform(Actor.DEPUTY_WITHOUT, hourlyRateOf(id(Actor.DEPUTY_WITHOUT))), 200, "deputy self");
        assertStatus(perform(Actor.MEMBER, hourlyRateOf(id(Actor.MEMBER))), 200, "member self");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-6: MEMBER・SUPPORTER は操作拒否（回帰）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> ac6Cases() {
        return Stream.of(Actor.MEMBER, Actor.SUPPORTER)
                .flatMap(a -> Stream.of(Op.values()).map(op -> Arguments.of(a, op)));
    }

    @ParameterizedTest(name = "AC-6 {0} × {1}")
    @MethodSource("ac6Cases")
    @DisplayName("AC-6: MEMBER・SUPPORTER は全操作を拒否され副作用なし")
    void AC6_一般メンバーとサポーターは操作を拒否される(Actor actor, Op op) throws Exception {
        String before = fingerprint();
        assertStatus(perform(actor, request(op)), op.memberDeny, actor + " × " + op);
        assertThat(fingerprint()).isEqualTo(before);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7: 他チーム ADMIN は 404 秘匿（作成・?teamId= は 403）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-7 {0}")
    @EnumSource(Op.class)
    @DisplayName("AC-7: 他チーム ADMIN の操作は越境として秘匿（ID 起点 404、作成・パス/クエリの teamId 起点 403）")
    void AC7_他チームADMINの操作は越境秘匿(Op op) throws Exception {
        String before = fingerprint();
        assertStatus(perform(Actor.OTHER_ADMIN, request(op)), op.outsiderDeny, "other-admin × " + op);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-7 閲覧 {0}")
    @EnumSource(View.class)
    @DisplayName("AC-7: 他チーム ADMIN の閲覧も越境として秘匿")
    void AC7_他チームADMINの閲覧は越境秘匿(View view) throws Exception {
        assertStatus(perform(Actor.OTHER_ADMIN, viewRequest(view)), view.outsiderDeny, "other-admin × " + view);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8: チームロールなし SYSTEM_ADMIN は操作・閲覧とも拒否（応答は他の非所属者と同じ）
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-8 {0}")
    @EnumSource(Op.class)
    @DisplayName("AC-8: チームロールなし SYSTEM_ADMIN の操作は他チーム ADMIN と同じ応答で拒否され副作用なし")
    void AC8_チームロールなしSYSTEM_ADMINの操作は拒否(Op op) throws Exception {
        String before = fingerprint();
        assertStatus(perform(Actor.SYSTEM_ADMIN, request(op)), op.outsiderDeny, "system-admin × " + op);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-8 閲覧 {0}")
    @EnumSource(View.class)
    @DisplayName("AC-8: チームロールなし SYSTEM_ADMIN の閲覧は他チーム ADMIN と同じ応答で拒否")
    void AC8_チームロールなしSYSTEM_ADMINの閲覧は拒否(View view) throws Exception {
        assertStatus(perform(Actor.SYSTEM_ADMIN, viewRequest(view)), view.outsiderDeny, "system-admin × " + view);
    }

    @Test
    @DisplayName("AC-8: チームロールなし SYSTEM_ADMIN は他人の時給も見られず、削除もできない")
    void AC8_チームロールなしSYSTEM_ADMINは時給を扱えない() throws Exception {
        assertStatus(perform(Actor.SYSTEM_ADMIN, hourlyRateOf(id(Actor.MEMBER))), 403, "single");
        assertStatus(perform(Actor.SYSTEM_ADMIN, teamHourlyRates()), 403, "team");
        Throwable denied = catchThrowable(() ->
                hourlyRateDeletionFacade.deleteHourlyRate(memberHourlyRateId, id(Actor.SYSTEM_ADMIN)));
        assertThat(denied).isInstanceOf(BusinessException.class);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-9: 権限グループの付け外しが直後のリクエストに効く
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-9: 権限グループから MANAGE_SHIFTS を外した直後のリクエストで拒否される")
    void AC9_付与を外した直後に拒否される() throws Exception {
        assertAllowed(perform(Actor.DEPUTY_WITH, request(Op.A_SCHEDULE_UPDATE)), false, "付与中");
        assertStatus(perform(Actor.ADMIN, assignGroupsRequest(id(Actor.DEPUTY_WITH), List.of())), 200, "外す");
        assertStatus(perform(Actor.DEPUTY_WITH, request(Op.A_SCHEDULE_UPDATE)), 403, "外した直後");
    }

    @Test
    @DisplayName("AC-9: 権限なし DEPUTY に MANAGE_SHIFTS のグループを付与した直後に許可される（付与前は拒否）")
    void AC9_付与した直後に許可される() throws Exception {
        assertStatus(perform(Actor.DEPUTY_WITHOUT, request(Op.A_SCHEDULE_UPDATE)), 403, "付与前");
        assertStatus(perform(Actor.ADMIN,
                assignGroupsRequest(id(Actor.DEPUTY_WITHOUT), List.of(deputyWithGroupId))), 200, "付与");
        assertAllowed(perform(Actor.DEPUTY_WITHOUT, request(Op.A_SCHEDULE_UPDATE)), false, "付与直後");
    }

    @Test
    @DisplayName("攻め口（境界）: ADMIN から DEPUTY へ降格された直後は MANAGE_SHIFTS が無ければ拒否される")
    void 降格直後は拒否される() throws Exception {
        assertAllowed(perform(Actor.ADMIN2, request(Op.A_SCHEDULE_UPDATE)), false, "降格前");
        Long deputyRoleId = ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = 'DEPUTY_ADMIN'")
                .getSingleResult()).longValue();
        MvcResult demote = perform(Actor.ADMIN, patch("/api/v1/teams/{slug}/members/{userId}/role",
                teamSlug, id(Actor.ADMIN2)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("roleId", deputyRoleId))));
        assertThat(demote.getResponse().getStatus()).as("降格の前提").isBetween(200, 299);
        assertStatus(perform(Actor.ADMIN2, request(Op.A_SCHEDULE_UPDATE)), 403, "降格直後");
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-10: user_roles と memberships の食い違い
    // ═════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "AC-10 {0}")
    @EnumSource(Op.class)
    @DisplayName("AC-10: user_roles にだけ DEPUTY（MANAGE_SHIFTS 無し）がある所属者は拒否され、応答は 404 でなく所属者向け（run 系は 404）")
    void AC10_userRolesのみの権限なし副管理者は所属者として拒否(Op op) throws Exception {
        String before = fingerprint();
        assertStatus(perform(Actor.USER_ROLES_ONLY_DEPUTY, request(op)), op.memberDeny, "ur-only-deputy × " + op);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-10 閲覧 {0}")
    @EnumSource(View.class)
    @DisplayName("AC-10: user_roles にだけ DEPUTY（MANAGE_SHIFTS 無し・切替 OFF）がある所属者の閲覧は、越境の 404 ではなく所属者向けの 403")
    void AC10_userRolesのみの権限なし副管理者の閲覧は所属者として拒否(View view) throws Exception {
        MvcResult r = perform(Actor.USER_ROLES_ONLY_DEPUTY, viewRequest(view));
        String label = "ur-only-deputy × " + view;
        switch (view) {
            // 他人の変更依頼の詳細は、所属者（一般メンバー）にも存在を隠す仕様（越境と同じ 404 SHIFT_030）。
            case CHANGE_REQUEST_GET_OTHERS -> assertError(r, 404, "SHIFT_030", label);
            // 在籍していないので一般メンバーとしても見られない。越境（404）とは区別して 403。
            case L_DASHBOARD_SHIFT_REQUEST -> {
                assertStatus(r, 200, label);
                assertThat(domains(r)).as(label).doesNotContain("SHIFT_REQUEST");
            }
            default -> assertError(r, 403, "COMMON_002", label);
        }
        // 対照: 越境（他チーム ADMIN）は ID 起点の閲覧で 404（所属者の 403 と応答が割れること＝所属判定が効いている）。
        if (view == View.SCHEDULE_GET_UNPUBLISHED || view == View.SLOT_ASSIGNMENTS_UNMASKED
                || view == View.J_PDF_ADMIN_OUTPUT) {
            assertError(perform(Actor.OTHER_ADMIN, viewRequest(view)), 404, "SHIFT_001", "other-admin × " + view);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-8b: チームロールなし SYSTEM_ADMIN は一般メンバーの操作（提出・交代・変更依頼・ポジション一覧・可用性）でも部外者
    // ═════════════════════════════════════════════════════════════════════

    /** 一般メンバー向けの EP。expectStatus/expectCode は部外者（他チーム ADMIN）への応答。 */
    enum MemberOp {
        REQUEST_SUBMIT(404, "SHIFT_001"),
        SWAP_CREATE(404, "SHIFT_002"),
        SWAP_ACCEPT(404, "SHIFT_005"),
        CHANGE_REQUEST_CREATE(404, "SHIFT_001"),
        CHANGE_REQUEST_WITHDRAW(404, "SHIFT_030"),
        POSITION_LIST(403, "COMMON_002"),
        AVAILABILITY_GET(403, "COMMON_002");

        final int expectStatus;
        final String expectCode;

        MemberOp(int expectStatus, String expectCode) {
            this.expectStatus = expectStatus;
            this.expectCode = expectCode;
        }
    }

    private MockHttpServletRequestBuilder memberRequest(MemberOp op) throws Exception {
        return switch (op) {
            case REQUEST_SUBMIT -> post(SHIFTS + "/requests").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("scheduleId", scheduleId, "slotDate", "2030-01-05", "preference", "PREFERRED")));
            case SWAP_CREATE -> post(SHIFTS + "/swap-requests").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("slotId", slotId, "reason", "試練", "openCall", true)));
            case SWAP_ACCEPT -> post(SHIFTS + "/swap-requests/{id}/accept", othersPendingSwapId);
            case CHANGE_REQUEST_CREATE -> post(SHIFTS + "/change-requests").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("scheduleId", scheduleId, "requestType", "PRE_CONFIRM_EDIT", "reason", "試練")));
            case CHANGE_REQUEST_WITHDRAW -> delete(SHIFTS + "/change-requests/{id}", changeRequestId);
            case POSITION_LIST -> get(SHIFTS + "/positions").param("teamId", teamId.toString());
            case AVAILABILITY_GET -> get(SHIFTS + "/availability").param("teamId", teamId.toString());
        };
    }

    @ParameterizedTest(name = "AC-8b {0}")
    @EnumSource(MemberOp.class)
    @DisplayName("AC-8b: チームロールなし SYSTEM_ADMIN の一般メンバー向け操作は、他チーム ADMIN と同じ応答で拒否され副作用なし")
    void AC8b_チームロールなしSYSTEM_ADMINは一般メンバー向け操作でも部外者(MemberOp op) throws Exception {
        String before = fingerprint();
        MvcResult systemAdmin = perform(Actor.SYSTEM_ADMIN, memberRequest(op));
        assertError(systemAdmin, op.expectStatus, op.expectCode, "system-admin × " + op);
        assertError(perform(Actor.OTHER_ADMIN, memberRequest(op)), op.expectStatus, op.expectCode, "other-admin × " + op);
        assertThat(fingerprint()).as("拒否された system-admin × %s で DB が変わった", op).isEqualTo(before);
    }

    @ParameterizedTest(name = "AC-8b 対照 MEMBER {0}")
    @EnumSource(MemberOp.class)
    @DisplayName("AC-8b 対照: 同じリクエストは在籍メンバーには部外者向けの応答にならない（リクエストが有効であることの確認）")
    void AC8b対照_在籍メンバーは部外者向けの応答にならない(MemberOp op) throws Exception {
        MvcResult result = perform(Actor.MEMBER2, memberRequest(op));
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        boolean outsiderShaped = status == op.expectStatus && body.contains("\"" + op.expectCode + "\"");
        assertThat(outsiderShaped).as("MEMBER2 × %s が部外者向けの応答になった: %s %s", op, status, body).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11: 変更依頼の通知受信者 ＝ 審査の許可者
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-11: 変更依頼の通知受信者（ADMIN ＋ MANAGE_SHIFTS 付き DEPUTY。user_roles のみの ADMIN を含む）と審査できる人が一致する")
    void AC11_通知受信者と審査の許可者が一致する() throws Exception {
        Set<Long> recipients = new HashSet<>(
                roleService.getAdminAndPermittedDeputyUserIdsByTeamId(teamId, MANAGE_SHIFTS));
        List<Actor> candidates = List.of(Actor.ADMIN, Actor.ADMIN2, Actor.DEPUTY_WITH, Actor.DEPUTY_WITHOUT,
                Actor.DEPUTY_EMPTY_GROUP, Actor.CROSS_DEPUTY, Actor.MEMBER2, Actor.SUPPORTER,
                // user_roles にだけロールがある者（memberships 無し）。ADMIN は審査できる（isAdmin は在籍を問わない）ので
                // 通知も届かなければならない。MANAGE_SHIFTS 無しの DEPUTY は審査も通知も無い。
                Actor.USER_ROLES_ONLY_ADMIN, Actor.USER_ROLES_ONLY_DEPUTY);
        Set<Long> reviewers = new HashSet<>();
        for (Actor actor : candidates) {
            Long crId = saveChangeRequest(id(Actor.MEMBER));
            em.flush();
            MvcResult result = perform(actor, patch(SHIFTS + "/change-requests/{id}/review", crId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(reviewBody("REJECTED"))));
            if (result.getResponse().getStatus() == 200) {
                reviewers.add(id(actor));
            }
        }
        Set<Long> candidateIds = candidates.stream().map(this::id).collect(Collectors.toSet());
        Set<Long> recipientsAmongCandidates = recipients.stream().filter(candidateIds::contains)
                .collect(Collectors.toSet());
        assertThat(recipientsAmongCandidates).as("通知受信者")
                .containsExactlyInAnyOrder(id(Actor.ADMIN), id(Actor.ADMIN2), id(Actor.DEPUTY_WITH),
                        id(Actor.USER_ROLES_ONLY_ADMIN));
        assertThat(reviewers).as("審査できた人 = 通知受信者").isEqualTo(recipientsAmongCandidates);
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12: 本人の操作は維持
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("AC-12: 権限なし DEPUTY は本人の希望の更新・削除と本人の交代のキャンセルができる")
    void AC12_権限なし副管理者の本人操作は成功() throws Exception {
        assertStatus(perform(Actor.DEPUTY_WITHOUT, patch(SHIFTS + "/requests/{id}", deputyOwnRequestId)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("preference", "AVAILABLE")))),
                200, "本人の希望更新");
        assertStatus(perform(Actor.DEPUTY_WITHOUT, delete(SHIFTS + "/swap-requests/{id}", deputyOwnSwapId)),
                204, "本人の交代キャンセル");
        assertStatus(perform(Actor.DEPUTY_WITHOUT, delete(SHIFTS + "/requests/{id}", deputyOwnRequestId)),
                204, "本人の希望削除");
    }

    @Test
    @DisplayName("AC-12: MEMBER は本人の希望の更新・削除と本人の交代のキャンセルができる")
    void AC12_一般メンバーの本人操作は成功() throws Exception {
        assertStatus(perform(Actor.MEMBER, patch(SHIFTS + "/requests/{id}", memberOwnRequest2Id)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("preference", "AVAILABLE")))),
                200, "本人の希望更新");
        assertStatus(perform(Actor.MEMBER, delete(SHIFTS + "/swap-requests/{id}", memberOwnSwapId)),
                204, "本人の交代キャンセル");
        assertStatus(perform(Actor.MEMBER, delete(SHIFTS + "/requests/{id}", memberOwnRequest2Id)),
                204, "本人の希望削除");
    }

    @Test
    @DisplayName("AC-3/AC-12: 権限なし DEPUTY でも自分の交代・変更依頼は一覧・詳細で見られる")
    void AC12_権限なし副管理者は自分の交代と変更依頼を見られる() throws Exception {
        MvcResult swaps = perform(Actor.DEPUTY_WITHOUT, get(SHIFTS + "/swap-requests").param("teamId", teamId.toString()));
        assertStatus(swaps, 200, "swap list");
        assertThat(longs(swaps, "$.data[*].id")).contains(deputyOwnSwapId);
        MvcResult crs = perform(Actor.DEPUTY_WITHOUT,
                get(SHIFTS + "/change-requests").param("scheduleId", scheduleId.toString()));
        assertStatus(crs, 200, "cr list");
        assertThat(longs(crs, "$.data[*].id")).contains(deputyOwnChangeRequestId);
        assertStatus(perform(Actor.DEPUTY_WITHOUT, get(SHIFTS + "/change-requests/{id}", deputyOwnChangeRequestId)),
                200, "own cr");
    }

    // ═════════════════════════════════════════════════════════════════════
    // 攻め口: 認可（削除済み親・パスと実体の teamId 不一致）・性能
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("攻め口（削除済み親）: 論理削除済みシフト表への枠作成は DEPUTY+ でも不在と同じ 404")
    void 削除済み親は権限付きでも404() throws Exception {
        scheduleRepository.findById(scheduleId).orElseThrow().softDelete();
        em.flush();
        em.clear();
        assertStatus(perform(Actor.DEPUTY_WITH, request(Op.B_SLOT_CREATE)), 404, "deleted parent");
    }

    @Test
    @DisplayName("攻め口（teamId 不一致）: 他チームでだけ MANAGE_SHIFTS を持つ DEPUTY が、パス・クエリに他チームを書いて T のメンバーの時給・勤務制約を作れない")
    void パスのteamIdと実体のteamIdの不一致() throws Exception {
        String before = fingerprint();
        MvcResult rate = perform(Actor.CROSS_DEPUTY, post(SHIFTS + "/hourly-rate")
                .param("teamId", otherTeamId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(hourlyRateBody(id(Actor.MEMBER)))));
        assertThat(rate.getResponse().getStatus()).as("他チームの teamId で T のメンバーの時給").isIn(403, 404);
        MvcResult constraint = perform(Actor.CROSS_DEPUTY,
                put(SHIFTS + "/teams/{teamId}/work-constraints/members/{userId}", otherTeamId, id(Actor.MEMBER))
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("maxMonthlyDays", 20))));
        assertThat(constraint.getResponse().getStatus())
                .as("他チームのパスで T のメンバーの勤務制約: %s", constraint.getResponse().getContentAsString())
                .isIn(403, 404);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    @DisplayName("攻め口（性能）: DEPUTY+ の操作 1 回あたりの SQL 本数は ADMIN の同じ操作 +4 本以内（認可クエリを増やしすぎない）")
    void 認可クエリの本数の上限() throws Exception {
        long admin = countStatements(Actor.ADMIN, Op.A_SCHEDULE_UPDATE);
        long deputy = countStatements(Actor.DEPUTY_WITH, Op.A_SCHEDULE_UPDATE);
        System.out.printf("[CMP-261125-2114 perf] admin=%d deputyWith=%d%n", admin, deputy);
        assertThat(deputy).as("DEPUTY+ の SQL 本数（ADMIN=%d）", admin).isLessThanOrEqualTo(admin + 4);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 閲覧の観測
    // ═════════════════════════════════════════════════════════════════════

    private MockHttpServletRequestBuilder viewRequest(View view) {
        return switch (view) {
            case SCHEDULE_GET_UNPUBLISHED -> get(SHIFTS + "/schedules/{id}", draftScheduleId);
            case SCHEDULE_LIST_INCLUDES_UNPUBLISHED -> get(SHIFTS + "/schedules").param("teamId", teamId.toString());
            case SLOT_ASSIGNMENTS_UNMASKED -> get(SHIFTS + "/schedules/{id}/slots", scheduleId);
            case D_REQUEST_LIST -> get(SHIFTS + "/requests").param("scheduleId", scheduleId.toString());
            case D_REQUEST_SUMMARY -> get(SHIFTS + "/requests/summary").param("scheduleId", scheduleId.toString());
            case SCHEDULE_SUMMARY -> get(SHIFTS + "/schedules/{id}/summary", scheduleId);
            case SWAP_LIST_ALL -> get(SHIFTS + "/swap-requests").param("teamId", teamId.toString());
            case CHANGE_REQUEST_LIST_ALL -> get(SHIFTS + "/change-requests").param("scheduleId", scheduleId.toString());
            case CHANGE_REQUEST_GET_OTHERS -> get(SHIFTS + "/change-requests/{id}", changeRequestId);
            case J_PDF_ADMIN_OUTPUT -> get(SHIFTS + "/schedules/{id}/pdf", scheduleId).param("layout", "team");
            case L_DASHBOARD_SHIFT_REQUEST -> get("/api/v1/dashboard/team/{slug}/admin-action-required", teamSlug)
                    .param("preview_size", "0");
        };
    }

    /** 管理者の形（未公開も見える・割当を伏せない・全件・管理者出力）で見られることを検査する。 */
    private void assertPrivilegedView(View view, Actor actor) throws Exception {
        MvcResult r = perform(actor, viewRequest(view));
        String label = actor + " × " + view;
        switch (view) {
            case SCHEDULE_LIST_INCLUDES_UNPUBLISHED -> {
                assertStatus(r, 200, label);
                assertThat(longs(r, "$.data[*].id")).as(label).contains(draftScheduleId);
            }
            case SLOT_ASSIGNMENTS_UNMASKED -> {
                assertStatus(r, 200, label);
                assertThat(maskedFlags(r)).as(label + " assignmentMasked").containsOnly(false);
                assertThat(longs(r, "$.data[*].assignedUserIds[*]")).as(label).contains(id(Actor.MEMBER));
            }
            case SWAP_LIST_ALL -> {
                assertStatus(r, 200, label);
                assertThat(longs(r, "$.data[*].id")).as(label).contains(acceptedSwapId, othersPendingSwapId);
            }
            case CHANGE_REQUEST_LIST_ALL -> {
                assertStatus(r, 200, label);
                assertThat(longs(r, "$.data[*].id")).as(label).contains(changeRequestId);
            }
            case J_PDF_ADMIN_OUTPUT -> assertAllowed(r, true, label);
            case L_DASHBOARD_SHIFT_REQUEST -> {
                assertStatus(r, 200, label);
                assertThat(domains(r)).as(label).contains("SHIFT_REQUEST");
            }
            default -> assertStatus(r, 200, label);
        }
    }

    /** 一般メンバーと同じ形（未公開は 404・割当 MASKED・自分の分のみ・管理者向けは拒否）でしか見られないことを検査する。 */
    private void assertMemberView(View view, Actor actor) throws Exception {
        MvcResult r = perform(actor, viewRequest(view));
        String label = actor + " × " + view;
        switch (view) {
            case SCHEDULE_GET_UNPUBLISHED -> assertError(r, 404, "SHIFT_001", label);
            case SCHEDULE_LIST_INCLUDES_UNPUBLISHED -> {
                assertStatus(r, 200, label);
                assertThat(longs(r, "$.data[*].id")).as(label).contains(scheduleId).doesNotContain(draftScheduleId);
            }
            case SLOT_ASSIGNMENTS_UNMASKED -> {
                assertStatus(r, 200, label);
                assertThat(maskedFlags(r)).as(label + " assignmentMasked").containsOnly(true);
                assertThat(longs(r, "$.data[*].assignedUserIds[*]")).as(label).isEmpty();
            }
            case D_REQUEST_LIST, D_REQUEST_SUMMARY, SCHEDULE_SUMMARY -> assertError(r, 403, "COMMON_002", label);
            case SWAP_LIST_ALL -> {
                assertStatus(r, 200, label);
                List<Long> ids = longs(r, "$.data[*].id");
                if (actor == Actor.MEMBER) {
                    assertThat(ids).as(label).contains(acceptedSwapId)
                            .doesNotContain(deputyOwnSwapId, othersPendingSwapId);
                } else {
                    assertThat(ids).as(label).doesNotContain(acceptedSwapId, othersPendingSwapId);
                }
            }
            case CHANGE_REQUEST_LIST_ALL -> {
                assertStatus(r, 200, label);
                List<Long> ids = longs(r, "$.data[*].id");
                if (actor == Actor.MEMBER) {
                    assertThat(ids).as(label).contains(changeRequestId).doesNotContain(deputyOwnChangeRequestId);
                } else {
                    assertThat(ids).as(label).doesNotContain(changeRequestId);
                }
            }
            case CHANGE_REQUEST_GET_OTHERS -> {
                if (actor == Actor.MEMBER) {
                    assertStatus(r, 200, label); // MEMBER にとっては自分の依頼
                } else {
                    assertStatus(r, 404, label);
                }
            }
            case J_PDF_ADMIN_OUTPUT -> assertError(r, 404, "SHIFT_001", label);
            case L_DASHBOARD_SHIFT_REQUEST -> {
                if (actor == Actor.MEMBER) {
                    assertStatus(r, 403, label);
                } else {
                    // DEPUTY のダッシュボードそのものは見られるが、シフト希望の欄・件数は出さない。
                    assertStatus(r, 200, label);
                    assertThat(domains(r)).as(label).doesNotContain("SHIFT_REQUEST");
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 操作のリクエスト
    // ═════════════════════════════════════════════════════════════════════

    private MockHttpServletRequestBuilder request(Op op) throws Exception {
        return switch (op) {
            case A_SCHEDULE_CREATE -> post(SHIFTS + "/schedules").param("teamId", teamId.toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                            "title", "新規", "startDate", "2030-02-01", "endDate", "2030-02-07")));
            case A_SCHEDULE_UPDATE -> patch(SHIFTS + "/schedules/{id}", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("title", "更新後")));
            case A_SCHEDULE_TRANSITION -> post(SHIFTS + "/schedules/{id}/transition", scheduleId)
                    .param("status", "ADJUSTING");
            case A_SCHEDULE_DELETE -> delete(SHIFTS + "/schedules/{id}", scheduleId);
            case A_SCHEDULE_DUPLICATE -> post(SHIFTS + "/schedules/{id}/duplicate", scheduleId);
            case B_SLOT_CREATE -> post(SHIFTS + "/schedules/{id}/slots", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(slotBody("2030-01-05")));
            case B_SLOT_BULK_CREATE -> post(SHIFTS + "/schedules/{id}/slots/bulk", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("slots", List.of(slotBody("2030-01-05"), slotBody("2030-01-06")))));
            case B_SLOT_UPDATE -> patch(SHIFTS + "/slots/{id}", slotId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("note", "更新後")));
            case B_SLOT_DELETE -> delete(SHIFTS + "/slots/{id}", slotId);
            case B_SLOT_ASSIGNMENT_PATCH -> patch(SHIFTS + "/slots/{id}/assignments", slotId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                            "addUserIds", List.of(id(Actor.MEMBER2)), "removeUserIds", List.of(), "slotVersion", 0)));
            case B_SLOT_ASSIGNMENT_MOVE -> post(SHIFTS + "/slots/{id}/assignments/move", slotId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                            "targetSlotId", slot2Id, "userId", id(Actor.MEMBER),
                            "sourceSlotVersion", 0, "targetSlotVersion", 0)));
            case C_AUTO_ASSIGN_RUN -> post(SHIFTS + "/schedules/{id}/auto-assign", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("strategy", "GREEDY_V1")));
            case C_AUTO_ASSIGN_CONFIRM -> post(SHIFTS + "/schedules/{id}/auto-assign/confirm", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                            "runId", confirmedRunId, "assignmentIds", List.of(proposedAssignmentId),
                            "scheduleVersion", 0)));
            case C_AUTO_ASSIGN_REVOKE -> delete(SHIFTS + "/schedules/{id}/auto-assign", scheduleId)
                    .contentType(MediaType.APPLICATION_JSON).content(String.valueOf(succeededRunId));
            case C_ASSIGNMENT_RUN_LIST -> get(SHIFTS + "/schedules/{id}/assignment-runs", scheduleId);
            case C_ASSIGNMENT_RUN_DETAIL -> get(SHIFTS + "/assignment-runs/{id}", succeededRunId);
            case C_ASSIGNMENT_RUN_VISUAL_REVIEW -> post(SHIFTS + "/assignment-runs/{id}/confirm-visual-review",
                    succeededRunId).contentType(MediaType.APPLICATION_JSON).content(json(Map.of("note", "確認")));
            case D_REQUEST_UPDATE_OTHERS -> patch(SHIFTS + "/requests/{id}", othersRequestId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("preference", "AVAILABLE")));
            case D_REQUEST_DELETE_OTHERS -> delete(SHIFTS + "/requests/{id}", othersRequestId);
            case E_SWAP_RESOLVE -> post(SHIFTS + "/swap-requests/{id}/resolve", acceptedSwapId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("action", "REJECT")));
            case E_SWAP_CANCEL_OTHERS -> delete(SHIFTS + "/swap-requests/{id}", othersPendingSwapId);
            case F_CHANGE_REQUEST_REVIEW -> patch(SHIFTS + "/change-requests/{id}/review", changeRequestId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(reviewBody("REJECTED")));
            case G_WORK_CONSTRAINT_LIST -> get(SHIFTS + "/teams/{teamId}/work-constraints", teamId);
            case G_WORK_CONSTRAINT_GET_OTHERS -> get(SHIFTS + "/teams/{teamId}/work-constraints/members/{userId}",
                    teamId, id(Actor.MEMBER2));
            case G_WORK_CONSTRAINT_UPSERT -> put(SHIFTS + "/teams/{teamId}/work-constraints/members/{userId}",
                    teamId, id(Actor.MEMBER2)).contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("maxMonthlyDays", 20)));
            case G_WORK_CONSTRAINT_DELETE -> delete(SHIFTS + "/teams/{teamId}/work-constraints/members/{userId}",
                    teamId, id(Actor.MEMBER2));
            case G_WORK_CONSTRAINT_DEFAULT_UPSERT -> put(SHIFTS + "/teams/{teamId}/work-constraints/default", teamId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("maxMonthlyDays", 22)));
            case G_WORK_CONSTRAINT_DEFAULT_DELETE -> delete(SHIFTS + "/teams/{teamId}/work-constraints/default", teamId);
            case G_HOURLY_RATE_CREATE -> post(SHIFTS + "/hourly-rate").param("teamId", teamId.toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json(hourlyRateBody(id(Actor.MEMBER))));
            case H_POSITION_CREATE -> post(SHIFTS + "/positions").param("teamId", teamId.toString())
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "キッチン")));
            case H_POSITION_UPDATE -> patch(SHIFTS + "/positions/{id}", positionId)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("name", "フロア")));
            case H_POSITION_DELETE -> delete(SHIFTS + "/positions/{id}", positionId);
            case I_SHIFT_SETTINGS_UPDATE -> patch("/api/v1/teams/{slug}/shift-settings", teamSlug)
                    .contentType(MediaType.APPLICATION_JSON).content(json(Map.of(
                            "reminder48hEnabled", false, "reminder24hEnabled", true, "reminder12hEnabled", false)));
            case K_SCHEDULE_REMIND -> post(SHIFTS + "/schedules/{id}/remind", scheduleId);
        };
    }

    private MockHttpServletRequestBuilder hourlyRateOf(Long userId) {
        return get(SHIFTS + "/hourly-rate").param("teamId", teamId.toString()).param("userId", userId.toString());
    }

    private MockHttpServletRequestBuilder teamHourlyRates() {
        return get(SHIFTS + "/hourly-rates").param("teamId", teamId.toString()).param("date", "2030-01-02");
    }

    private MockHttpServletRequestBuilder assignGroupsRequest(Long userId, List<Long> groupIds) throws Exception {
        return put("/api/v1/teams/{slug}/members/{userId}/permission-groups", teamSlug, userId)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("groupIds", groupIds)));
    }

    /** ADMIN が設定 API で切替を設定する（前提作成。現状は未知の項目として無視される）。 */
    private void setToggles(boolean shiftView, boolean hourlyRateView) throws Exception {
        // 切替だけを送る（リマインド 3 項目は省略＝変更しない。省略で 400 になる・false で上書きされる不具合の再発防止）。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TOGGLE_SHIFT_VIEW, shiftView);
        body.put(TOGGLE_HOURLY_RATE_VIEW, hourlyRateView);
        MvcResult result = perform(Actor.ADMIN, patch("/api/v1/teams/{slug}/shift-settings", teamSlug)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
        assertStatus(result, 200, "切替の設定（前提）");
    }

    private static Map<String, Object> slotBody(String date) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slotDate", date);
        body.put("startTime", "09:00:00");
        body.put("endTime", "12:00:00");
        body.put("requiredCount", 1);
        return body;
    }

    private static Map<String, Object> reviewBody(String decision) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("decision", decision);
        body.put("reviewComment", "試練");
        body.put("version", 0);
        return body;
    }

    private static Map<String, Object> hourlyRateBody(Long userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("hourlyRate", 1500);
        body.put("effectiveFrom", "2030-01-10");
        return body;
    }

    // ═════════════════════════════════════════════════════════════════════
    // 検査ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private MvcResult perform(Actor actor, MockHttpServletRequestBuilder builder) throws Exception {
        return mockMvc.perform(builder.header("Authorization", bearer(actor))).andReturn();
    }

    private String bearer(Actor actor) {
        Long id = id(actor);
        return "Bearer " + authTokenService.issueAccessToken(id, roleClaimResolver.resolveRoles(id),
                statusClaimResolver.isPendingParentalConsent(id));
    }

    private Long id(Actor actor) {
        return actors.get(actor);
    }

    private static void assertStatus(MvcResult result, int expected, String label) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as("%s: %s", label, result.getResponse().getContentAsString())
                .isEqualTo(expected);
    }

    private static void assertError(MvcResult result, int expected, String code, String label) throws Exception {
        assertStatus(result, expected, label);
        String body = result.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.error.code")).as(label).isEqualTo(code);
    }

    /**
     * 認可を通過したこと。通常は 2xx。PDF・手動リマインドは後段（PDF 生成・Valkey ロック＝試験では mock）の
     * 都合で 2xx を固定できないので、認可・可視性・入力の拒否（400/401/403/404）でないことだけを検査する。
     */
    private static void assertAllowed(MvcResult result, boolean authzOnly, String label) throws Exception {
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        if (authzOnly) {
            assertThat(status).as("%s は認可で弾かれない: %s", label, body).isNotIn(400, 401, 403, 404);
        } else {
            assertThat(status).as("%s は成功する: %s", label, body).isBetween(200, 299);
        }
    }

    private static List<Long> longs(MvcResult result, String path) throws Exception {
        List<Object> raw = JsonPath.read(result.getResponse().getContentAsString(), path);
        List<Long> out = new ArrayList<>();
        for (Object o : raw) {
            if (o != null) {
                out.add(((Number) o).longValue());
            }
        }
        return out;
    }

    private static List<Boolean> maskedFlags(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data[*].assignmentMasked");
    }

    private static List<String> domains(MvcResult result) throws Exception {
        return JsonPath.read(result.getResponse().getContentAsString(), "$.data.domains[*].domain");
    }

    private static Boolean readBoolean(String body, String field) {
        List<Object> found = JsonPath.read(body, "$.." + field);
        assertThat(found).as("設定の応答に %s が無い: %s", field, body).isNotEmpty();
        return (Boolean) found.get(0);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    /** 当該チームのシフト関連の全行（論理削除を含む）と通知件数の指紋。拒否時に何も変わっていないことの検査に使う。 */
    private String fingerprint() {
        em.flush();
        String schedules = "SELECT id FROM shift_schedules WHERE team_id = " + teamId;
        String slots = "SELECT id FROM shift_slots WHERE schedule_id IN (" + schedules + ")";
        List<String> queries = List.of(
                "SELECT * FROM shift_schedules WHERE team_id = " + teamId + " ORDER BY id",
                "SELECT * FROM shift_slots WHERE schedule_id IN (" + schedules + ") ORDER BY id",
                "SELECT * FROM shift_requests WHERE schedule_id IN (" + schedules + ") ORDER BY id",
                "SELECT * FROM shift_change_requests WHERE schedule_id IN (" + schedules + ") ORDER BY id",
                "SELECT * FROM shift_swap_requests WHERE slot_id IN (" + slots + ") ORDER BY id",
                "SELECT * FROM shift_assignment_runs WHERE schedule_id IN (" + schedules + ") ORDER BY id",
                "SELECT * FROM shift_assignments WHERE slot_id IN (" + slots + ") ORDER BY id",
                "SELECT * FROM shift_positions WHERE team_id = " + teamId + " ORDER BY id",
                "SELECT * FROM shift_hourly_rates WHERE team_id = " + teamId + " ORDER BY id",
                "SELECT * FROM member_work_constraints WHERE team_id = " + teamId + " ORDER BY id",
                "SELECT * FROM team_shift_settings WHERE team_id = " + teamId,
                "SELECT COUNT(*) FROM notifications WHERE user_id IN ("
                        + actors.values().stream().map(String::valueOf).collect(Collectors.joining(",")) + ")");
        StringBuilder sb = new StringBuilder();
        for (String q : queries) {
            List<?> rows = em.createNativeQuery(q).getResultList();
            sb.append(q, 0, Math.min(q.length(), 40)).append("=");
            for (Object row : rows) {
                sb.append(row instanceof Object[] arr ? Arrays.deepToString(arr) : String.valueOf(row)).append(';');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private long countSlots() {
        em.flush();
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM shift_slots WHERE schedule_id = :id")
                .setParameter("id", scheduleId).getSingleResult()).longValue();
    }

    private long countStatements(Actor actor, Op op) throws Exception {
        MockHttpServletRequestBuilder builder = request(op);
        String token = bearer(actor);
        em.flush();
        em.clear();
        Statistics statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        mockMvc.perform(builder.header("Authorization", token)).andReturn();
        long count = statistics.getPrepareStatementCount();
        statistics.setStatisticsEnabled(false);
        return count;
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private Long saveTeam() {
        return teamRepository.saveAndFlush(TeamEntity.builder()
                .name("MANAGE_SHIFTS 試練チーム").slug("msp-" + UUID.randomUUID().toString().substring(0, 20))
                .template("sports").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(true).build()).getId();
    }

    private Long saveSchedule(ShiftScheduleStatus status, String title) {
        return scheduleRepository.save(ShiftScheduleEntity.builder()
                .teamId(teamId).title(title).periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2030, 1, 1)).endDate(LocalDate.of(2030, 1, 7))
                .status(status).createdBy(id(Actor.ADMIN)).build()).getId();
    }

    private Long saveRequest(Long userId, LocalDate date) {
        return requestRepository.save(ShiftRequestEntity.builder()
                .scheduleId(scheduleId).userId(userId).slotDate(date)
                .preference(ShiftPreference.PREFERRED).note("試練").build()).getId();
    }

    private Long saveChangeRequest(Long requester) {
        return changeRequestRepository.save(ShiftChangeRequestEntity.builder()
                .scheduleId(scheduleId).requestType(ChangeRequestType.PRE_CONFIRM_EDIT)
                .requestedBy(requester).reason("試練").build()).getId();
    }

    private ShiftSwapRequestEntity buildSwap(Long requesterId) {
        return ShiftSwapRequestEntity.builder()
                .slotId(slotId).requesterId(requesterId).status(SwapRequestStatus.PENDING)
                .reason("試練").isOpenCall(false).recipientMode("SPECIFIC").build();
    }

    private Long saveRun(ShiftAssignmentRunStatus status) {
        return runRepository.save(ShiftAssignmentRunEntity.builder()
                .scheduleId(scheduleId).strategy(AssignmentStrategyType.GREEDY_V1).status(status)
                .triggeredBy(id(Actor.ADMIN)).slotsTotal(2).slotsFilled(0).build()).getId();
    }

    private Long saveHourlyRate(Long userId, String rate) {
        return hourlyRateRepository.save(ShiftHourlyRateEntity.builder()
                .userId(userId).teamId(teamId).hourlyRate(new BigDecimal(rate))
                .effectiveFrom(LocalDate.of(2029, 12, 1)).build()).getId();
    }

    /** DEPUTY_ADMIN 向けの権限グループを作る（withManageShifts=false なら権限が空のグループ）。 */
    private Long createPermissionGroup(Long team, boolean withManageShifts) {
        em.createNativeQuery("INSERT INTO permissions (name, display_name, scope, created_at, updated_at) "
                        + "SELECT 'MANAGE_SHIFTS', 'MANAGE_SHIFTS', 'TEAM', NOW(), NOW() FROM DUAL "
                        + "WHERE NOT EXISTS (SELECT 1 FROM permissions WHERE name = 'MANAGE_SHIFTS')")
                .executeUpdate();
        String name = "msp-pg-" + UUID.randomUUID();
        em.createNativeQuery("INSERT INTO permission_groups (team_id, organization_id, target_role, name, "
                        + "created_at, updated_at) VALUES (:tid, NULL, 'DEPUTY_ADMIN', :name, NOW(), NOW())")
                .setParameter("tid", team).setParameter("name", name).executeUpdate();
        Long groupId = ((Number) em.createNativeQuery("SELECT id FROM permission_groups WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
        if (withManageShifts) {
            em.createNativeQuery("INSERT INTO permission_group_permissions (group_id, permission_id, created_at) "
                            + "SELECT :gid, p.id, NOW() FROM permissions p WHERE p.name = 'MANAGE_SHIFTS'")
                    .setParameter("gid", groupId).executeUpdate();
        }
        return groupId;
    }

    private void assignGroup(Long userId, Long groupId) {
        em.createNativeQuery("INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                        + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId).setParameter("gid", groupId).executeUpdate();
    }
}
