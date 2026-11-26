package com.mannschaft.app.committee;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 委員会の存在オラクル封じの契約テスト（CMP-261125-2349・試練）。
 *
 * <p>正本は docs/security/01_authorization_baseline.md §3.3.2「委員会（F04.10）への適用」の
 * EP 別許可主体表（AC X1）。本テストはその表を {@link #EXPECTATIONS} に写し、全 EP × 全主体の
 * 応答を実 MySQL で固定する。</p>
 *
 * <ul>
 *   <li>X2: 不可視（他テナント・HIDDEN の組織非委員・残存所属）への応答は、不在 ID への応答と
 *       status・Content-Type・本文（code・message を含む）がバイト等価。絶対値（404・コード・文言）も併せて固定する
 *       （一致だけだと両方壊れたとき偽 green になる）。</li>
 *   <li>X3: 委員会の非メンバーによる書込は、委員会が組織に可視（NAME_ONLY）でも 404。委員会メンバーの役職不足は 403。</li>
 *   <li>X4: 委員会メンバー・組織 ADMIN（user_roles のみを含む）・SYSTEM_ADMIN の許可・403 は表どおり。</li>
 *   <li>X6: 伝達ログ ID・招集状 ID を指定する EP も、子の実在によらず不在と等価。</li>
 *   <li>X7: 宛先でない利用者の有効トークンは、無効トークンと等価。</li>
 *   <li>X9: 委員会メンバーでも親組織に在籍しない者（残存所属、PR #4424）は不可視。</li>
 * </ul>
 *
 * <p>匿名（401）は Security filter の責務で、{@code CommitteeMinutesScopeContractIT} が実 filter で固定する。
 * 本テストは {@code addFilters = false} で認可判定のみを見る。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("委員会の存在オラクル封じ 契約テスト（CMP-261125-2349）")
class CommitteeExistenceOracleContractIT extends AbstractMySqlIntegrationTest {

    /** 実在しない ID（委員会・伝達ログ・活動記録・招集状・ユーザーに共通）。 */
    private static final long MISSING = Long.MAX_VALUE;
    private static final long FOREIGN_USER_ID = 900_000_001L;

    private static final String COMMITTEE_NOT_FOUND = "COMMITTEE_NOT_FOUND";
    private static final String COMMITTEE_NOT_FOUND_MESSAGE = "委員会が見つかりません";
    private static final String INVITATION_NOT_FOUND = "COMMITTEE_INVITATION_NOT_FOUND";
    private static final String INVITATION_NOT_FOUND_MESSAGE = "招集状が見つかりません";
    private static final String TOKEN_INVALID = "COMMITTEE_INVITATION_TOKEN_INVALID";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private CommitteeRepository committeeRepository;
    @Autowired private CommitteeMemberRepository committeeMemberRepository;
    @Autowired private CommitteeInvitationRepository committeeInvitationRepository;
    @PersistenceContext private EntityManager em;

    /** 主体（EP 別許可主体表の列）。 */
    enum Subject {
        /** 委員会 A の CHAIR（組織 A 在籍）。 */
        CHAIR,
        /** 委員会 A の VICE_CHAIR（組織 A 在籍、招集状の招集者）。 */
        VICE_CHAIR,
        /** 委員会 A の SECRETARY（組織 A 在籍）。 */
        SECRETARY,
        /** 委員会 A の MEMBER（組織 A 在籍、ロール変更・解任の対象者）。 */
        MEMBER,
        /** 組織 A の ADMIN（memberships＋user_roles）。委員会 A には非所属。 */
        ORG_ADMIN,
        /** 組織 A の ADMIN だが user_roles のみ（memberships 行なし）。委員会 A には非所属。 */
        ORG_ADMIN_ROLE_ONLY,
        /** SYSTEM_ADMIN（どこにも所属しない）。 */
        SYSTEM_ADMIN,
        /** 組織 A の一般メンバーで委員会 A の非メンバー（招集状の被招集者）。 */
        ORG_NON_MEMBER,
        /** 組織 A の一般メンバーで別委員会 B の CHAIR。委員会 A には非所属。 */
        OTHER_COMMITTEE_CHAIR,
        /** 他テナント（組織 B の在籍者で、組織 B の委員会 C の CHAIR）。 */
        OTHER_TENANT,
        /** 委員会 A の現役 MEMBER 行は残っているが、組織 A に在籍しない者（残存所属、PR #4424）。 */
        RESIDUAL
    }

    /** エンドポイント（EP 別許可主体表の行）。 */
    enum Ep {
        GET_COMMITTEE("GET /committees/{id}", false),
        UPDATE_COMMITTEE("PATCH /committees/{id}", false),
        TRANSITION_STATUS("POST /committees/{id}/status", false),
        LIST_MEMBERS("GET /committees/{id}/members", false),
        UPDATE_MEMBER_ROLE("PATCH /committees/{id}/members/{userId}", true),
        REMOVE_MEMBER("DELETE /committees/{id}/members/{userId}", true),
        LEAVE("POST /committees/{id}/members/me/leave", false),
        DISTRIBUTE("POST /committees/{id}/distributions", false),
        LIST_DISTRIBUTIONS("GET /committees/{id}/distributions", false),
        GET_DISTRIBUTION("GET /committees/{id}/distributions/{distributionId}", true),
        SEND_INVITATIONS("POST /committees/{id}/invitations", false),
        LIST_INVITATIONS("GET /committees/{id}/invitations", false),
        CONFIRM_MINUTES("PATCH /committees/{id}/activity-records/{recordId}/confirm", true),
        CANCEL_INVITATION("DELETE /committee-invitations/{invitationId}", false),
        ACCEPT_BY_TOKEN("POST /committee-invitations/accept-by-token", false),
        DECLINE_BY_TOKEN("POST /committee-invitations/decline-by-token", false);

        final String label;
        /** 委員会 ID に加えて子 ID（対象ユーザー・伝達ログ・活動記録）を持つか。 */
        final boolean hasChild;

        Ep(String label, boolean hasChild) {
            this.label = label;
            this.hasChild = hasChild;
        }

        boolean isTokenEp() {
            return this == ACCEPT_BY_TOKEN || this == DECLINE_BY_TOKEN;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * EP 別許可主体表（docs/security/01_authorization_baseline.md §3.3.2 委員会の表と一致させる）。
     *
     * <p>列順は {@link Subject} の宣言順。値: 数字は HTTP status（403 は {@code COMMON_002}）、
     * {@code 404} は不在と等価な秘匿応答、{@code TOKEN} は無効トークンと等価な応答、
     * {@code 400:CODE} は認可を通過した後の業務エラー。</p>
     */
    private static final Map<Ep, String[]> EXPECTATIONS = new EnumMap<>(Ep.class);

    static {
        //                                    CHAIR  VICE   SEC    MEMBER ADMIN  ADM_RO SYS    ORGNON OTHCHR TENANT RESID
        EXPECTATIONS.put(Ep.GET_COMMITTEE,     row("200", "200", "200", "200", "200", "200", "200", "200", "200", "404", "404"));
        EXPECTATIONS.put(Ep.UPDATE_COMMITTEE,  row("200", "200", "403", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.TRANSITION_STATUS, row("200", "403", "403", "403", "200", "200", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.LIST_MEMBERS,      row("200", "200", "200", "200", "403", "403", "403", "403", "403", "404", "404"));
        EXPECTATIONS.put(Ep.UPDATE_MEMBER_ROLE, row("200", "403", "403", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.REMOVE_MEMBER,     row("204", "403", "403", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.LEAVE,             row("400:COMMITTEE_LAST_CHAIR_CANNOT_LEAVE",
                                                          "204", "204", "204", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.DISTRIBUTE,        row("201", "201", "201", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.LIST_DISTRIBUTIONS, row("200", "200", "200", "200", "403", "403", "403", "403", "403", "404", "404"));
        EXPECTATIONS.put(Ep.GET_DISTRIBUTION,  row("200", "200", "200", "200", "403", "403", "403", "403", "403", "404", "404"));
        EXPECTATIONS.put(Ep.SEND_INVITATIONS,  row("201", "201", "403", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.LIST_INVITATIONS,  row("200", "200", "403", "403", "403", "403", "403", "403", "403", "404", "404"));
        EXPECTATIONS.put(Ep.CONFIRM_MINUTES,   row("200", "200", "403", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.CANCEL_INVITATION, row("204", "204", "403", "403", "403", "403", "403", "404", "404", "404", "404"));
        EXPECTATIONS.put(Ep.ACCEPT_BY_TOKEN,   row("TOKEN", "TOKEN", "TOKEN", "TOKEN", "TOKEN", "TOKEN", "TOKEN", "200", "TOKEN", "TOKEN", "TOKEN"));
        EXPECTATIONS.put(Ep.DECLINE_BY_TOKEN,  row("TOKEN", "TOKEN", "TOKEN", "TOKEN", "TOKEN", "TOKEN", "TOKEN", "200", "TOKEN", "TOKEN", "TOKEN"));
    }

    private static String[] row(String... values) {
        if (values.length != Subject.values().length) {
            throw new IllegalStateException("EP 別許可主体表の列数が主体数と一致しない: " + Arrays.toString(values));
        }
        return values;
    }

    /** 委員会ごとの子リソース。 */
    private record Target(Long committeeId, Long memberTargetUserId, Long distributionId, Long recordId,
                          Long invitationId, String inviteToken) {
    }

    private final Map<Subject, Long> users = new EnumMap<>(Subject.class);
    private Long orgAId;
    private Target committeeA;
    private Target committeeHidden;

    @BeforeEach
    void setUp() {
        orgAId = insertOrganization("存在オラクル 組織A " + UUID.randomUUID());
        Long orgBId = insertOrganization("存在オラクル 組織B " + UUID.randomUUID());

        for (Subject subject : Subject.values()) {
            users.put(subject, insertUser("cmt-oracle-" + subject.name().toLowerCase() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + "@example.com"));
        }
        Long chairHiddenId = insertUser("cmt-oracle-chair-hidden-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com");

        // 組織 A の在籍者（RESIDUAL・ORG_ADMIN_ROLE_ONLY・SYSTEM_ADMIN・OTHER_TENANT は在籍させない）
        for (Subject subject : List.of(Subject.CHAIR, Subject.VICE_CHAIR, Subject.SECRETARY, Subject.MEMBER,
                Subject.ORG_ADMIN, Subject.ORG_NON_MEMBER, Subject.OTHER_COMMITTEE_CHAIR)) {
            MembershipTestHelper.insertMembership(em, users.get(subject), ScopeType.ORGANIZATION, orgAId,
                    RoleKind.MEMBER);
        }
        MembershipTestHelper.insertMembership(em, chairHiddenId, ScopeType.ORGANIZATION, orgAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, users.get(Subject.ORG_ADMIN), "ADMIN", null, orgAId);
        MembershipTestHelper.insertUserRole(em, users.get(Subject.ORG_ADMIN_ROLE_ONLY), "ADMIN", null, orgAId);
        MembershipTestHelper.insertUserRole(em, users.get(Subject.SYSTEM_ADMIN), "SYSTEM_ADMIN", null, null);
        MembershipTestHelper.insertMembership(em, users.get(Subject.OTHER_TENANT), ScopeType.ORGANIZATION, orgBId,
                RoleKind.MEMBER);
        em.flush();

        Long aId = insertCommittee(orgAId, "存在オラクル 委員会A", CommitteeVisibility.NAME_ONLY);
        Long bId = insertCommittee(orgAId, "存在オラクル 委員会B", CommitteeVisibility.NAME_ONLY);
        Long hId = insertCommittee(orgAId, "存在オラクル 委員会H", CommitteeVisibility.HIDDEN);
        Long cId = insertCommittee(orgBId, "存在オラクル 委員会C（他テナント）", CommitteeVisibility.NAME_ONLY);

        insertMember(aId, users.get(Subject.CHAIR), CommitteeRole.CHAIR);
        insertMember(aId, users.get(Subject.VICE_CHAIR), CommitteeRole.VICE_CHAIR);
        insertMember(aId, users.get(Subject.SECRETARY), CommitteeRole.SECRETARY);
        insertMember(aId, users.get(Subject.MEMBER), CommitteeRole.MEMBER);
        insertMember(aId, users.get(Subject.RESIDUAL), CommitteeRole.MEMBER);
        insertMember(bId, users.get(Subject.OTHER_COMMITTEE_CHAIR), CommitteeRole.CHAIR);
        insertMember(cId, users.get(Subject.OTHER_TENANT), CommitteeRole.CHAIR);
        insertMember(hId, chairHiddenId, CommitteeRole.CHAIR);
        em.flush();

        CommitteeInvitationEntity invitationA = insertInvitation(aId, users.get(Subject.ORG_NON_MEMBER),
                users.get(Subject.VICE_CHAIR));
        committeeA = new Target(aId, users.get(Subject.MEMBER),
                insertDistributionLog(aId, users.get(Subject.CHAIR)),
                insertCommitteeActivityRecord(aId, users.get(Subject.CHAIR)),
                invitationA.getId(), invitationA.getInviteToken());

        CommitteeInvitationEntity invitationH = insertInvitation(hId, users.get(Subject.MEMBER), chairHiddenId);
        committeeHidden = new Target(hId, chairHiddenId,
                insertDistributionLog(hId, chairHiddenId),
                insertCommitteeActivityRecord(hId, chairHiddenId),
                invitationH.getId(), invitationH.getInviteToken());

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 全 EP × 全主体（NAME_ONLY の委員会 A）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> matrix() {
        List<Arguments> cases = new ArrayList<>();
        for (Ep ep : Ep.values()) {
            String[] expected = EXPECTATIONS.get(ep);
            for (Subject subject : Subject.values()) {
                cases.add(Arguments.of(ep, subject, expected[subject.ordinal()]));
            }
        }
        return cases.stream();
    }

    @Nested
    @DisplayName("X1〜X4・X6・X7・X9: EP 別許可主体表どおりに応答する（NAME_ONLY の委員会、残存所属を含む）")
    class Matrix {

        @ParameterizedTest(name = "[X1-X4/X6/X7/X9] {0} × {1} → {2}")
        @MethodSource("com.mannschaft.app.committee.CommitteeExistenceOracleContractIT#matrix")
        void EP別許可主体表どおりに応答する(Ep ep, Subject subject, String expected) throws Exception {
            verify(ep, subject, committeeA, expected);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // HIDDEN の委員会（組織メンバーの非委員には読取を含め全 EP で不在と等価）
    // ═════════════════════════════════════════════════════════════════════

    static Stream<Arguments> hiddenMatrix() {
        List<Arguments> cases = new ArrayList<>();
        for (Ep ep : Ep.values()) {
            if (ep.isTokenEp()) {
                continue;
            }
            for (Subject subject : List.of(Subject.ORG_NON_MEMBER, Subject.OTHER_COMMITTEE_CHAIR,
                    Subject.OTHER_TENANT, Subject.RESIDUAL)) {
                cases.add(Arguments.of(ep, subject));
            }
        }
        return cases.stream();
    }

    @Nested
    @DisplayName("X2: HIDDEN の委員会は組織メンバーの非委員に対し読取・書込とも不在とバイト等価")
    class Hidden {

        @ParameterizedTest(name = "[X2] HIDDEN {0} × {1} → 不在と等価の404")
        @MethodSource("com.mannschaft.app.committee.CommitteeExistenceOracleContractIT#hiddenMatrix")
        void HIDDEN委員会は非委員に不在と等価(Ep ep, Subject subject) throws Exception {
            verify(ep, subject, committeeHidden, "404");
        }

        @ParameterizedTest(name = "[X4] HIDDEN 詳細 × {0} → 200（権限者は従来どおり）")
        @MethodSource("com.mannschaft.app.committee.CommitteeExistenceOracleContractIT#hiddenPrivileged")
        void HIDDEN委員会の詳細は権限者に200(Subject subject) throws Exception {
            verify(Ep.GET_COMMITTEE, subject, committeeHidden, "200");
        }
    }

    static Stream<Arguments> hiddenPrivileged() {
        return Stream.of(Arguments.of(Subject.ORG_ADMIN), Arguments.of(Subject.ORG_ADMIN_ROLE_ONLY),
                Arguments.of(Subject.SYSTEM_ADMIN));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 判定本体
    // ═════════════════════════════════════════════════════════════════════

    private void verify(Ep ep, Subject subject, Target target, String expected) throws Exception {
        Long userId = users.get(subject);
        switch (expected) {
            case "404" -> verifyConcealed(ep, userId, target);
            case "TOKEN" -> verifyTokenConcealed(ep, userId, target);
            case "403" -> {
                MvcResult result = perform(ep, userId, target.committeeId(), childOf(ep, target), target);
                assertThat(result.getResponse().getStatus())
                        .as("%s × %s は可視の非許可者として 403", ep, subject).isEqualTo(403);
                assertThat(json(result).path("error").path("code").asText()).isEqualTo("COMMON_002");
            }
            default -> {
                MvcResult result = perform(ep, userId, target.committeeId(), childOf(ep, target), target);
                if (expected.startsWith("400:")) {
                    assertThat(result.getResponse().getStatus()).as("%s × %s", ep, subject).isEqualTo(400);
                    assertThat(json(result).path("error").path("code").asText())
                            .isEqualTo(expected.substring("400:".length()));
                } else {
                    assertThat(result.getResponse().getStatus())
                            .as("%s × %s は許可主体として %s（本文: %s）", ep, subject, expected,
                                    result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                            .isEqualTo(Integer.parseInt(expected));
                }
            }
        }
    }

    /**
     * 不可視（または非委員の書込）への応答が、不在 ID への応答とバイト等価であり、DB を変えないこと。
     *
     * <p>比較の基準は同じ利用者による (a) 委員会 ID・子 ID とも不在、(b) 子 ID を持つ EP は実在の委員会＋不在の子 ID。
     * 招集状の取り下げは招集状 ID の不在が基準。</p>
     */
    private void verifyConcealed(Ep ep, Long userId, Target target) throws Exception {
        String before = snapshot();
        MvcResult actual = perform(ep, userId, target.committeeId(), childOf(ep, target), target);
        em.flush();
        em.clear();
        assertThat(snapshot()).as("%s の秘匿応答で DB が変わってはならない", ep).isEqualTo(before);

        String code = ep == Ep.CANCEL_INVITATION ? INVITATION_NOT_FOUND : COMMITTEE_NOT_FOUND;
        String message = ep == Ep.CANCEL_INVITATION ? INVITATION_NOT_FOUND_MESSAGE : COMMITTEE_NOT_FOUND_MESSAGE;

        // 絶対値の固定（両方壊れたときの偽 green を防ぐ）
        assertThat(actual.getResponse().getStatus())
                .as("%s は不可視の対象に 404（本文: %s）", ep, body(actual)).isEqualTo(404);
        assertThat(json(actual)).isEqualTo(objectMapper.valueToTree(Map.of("error", Map.of(
                "code", code, "message", message, "fieldErrors", List.of()))));

        List<MvcResult> baselines = new ArrayList<>();
        baselines.add(perform(ep, userId, MISSING, MISSING, absentTarget()));
        if (ep.hasChild) {
            baselines.add(perform(ep, userId, target.committeeId(), MISSING, target));
        }
        for (MvcResult absent : baselines) {
            assertThat(actual.getResponse().getStatus()).isEqualTo(absent.getResponse().getStatus());
            assertThat(actual.getResponse().getContentType()).isEqualTo(absent.getResponse().getContentType());
            assertThat(body(actual))
                    .as("%s: 不可視と不在で本文が割れると実在が判別できる", ep)
                    .isEqualTo(body(absent));
        }
    }

    /** 宛先でない利用者の有効トークンへの応答が、無効トークンへの応答とバイト等価であること（X7）。 */
    private void verifyTokenConcealed(Ep ep, Long userId, Target target) throws Exception {
        String before = snapshot();
        MvcResult actual = perform(ep, userId, target.committeeId(), null, target);
        em.flush();
        em.clear();
        assertThat(snapshot()).as("%s の秘匿応答で DB が変わってはならない", ep).isEqualTo(before);

        MvcResult invalid = perform(ep, userId, target.committeeId(), null,
                new Target(MISSING, MISSING, MISSING, MISSING, MISSING, "invalid-" + UUID.randomUUID()));
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(invalid).path("error").path("code").asText()).isEqualTo(TOKEN_INVALID);
        assertThat(actual.getResponse().getStatus())
                .as("%s: 宛先でない利用者の有効トークンは無効トークンと同じ status（本文: %s）", ep, body(actual))
                .isEqualTo(invalid.getResponse().getStatus());
        assertThat(actual.getResponse().getContentType()).isEqualTo(invalid.getResponse().getContentType());
        assertThat(body(actual)).isEqualTo(body(invalid));
    }

    private Long childOf(Ep ep, Target target) {
        return switch (ep) {
            case UPDATE_MEMBER_ROLE, REMOVE_MEMBER -> target.memberTargetUserId();
            case GET_DISTRIBUTION -> target.distributionId();
            case CONFIRM_MINUTES -> target.recordId();
            default -> null;
        };
    }

    private Target absentTarget() {
        return new Target(MISSING, MISSING, MISSING, MISSING, MISSING, "absent-" + UUID.randomUUID());
    }

    private MvcResult perform(Ep ep, Long userId, Long committeeId, Long childId, Target target) throws Exception {
        MockHttpServletRequestBuilder request = switch (ep) {
            case GET_COMMITTEE -> get("/api/v1/committees/{id}", committeeId);
            case UPDATE_COMMITTEE -> patch("/api/v1/committees/{id}", committeeId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("name", "改名 " + UUID.randomUUID())));
            case TRANSITION_STATUS -> post("/api/v1/committees/{id}/status", committeeId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("action", "CLOSE")));
            case LIST_MEMBERS -> get("/api/v1/committees/{id}/members", committeeId);
            case UPDATE_MEMBER_ROLE -> patch("/api/v1/committees/{id}/members/{userId}", committeeId, childId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("role", "SECRETARY")));
            case REMOVE_MEMBER -> delete("/api/v1/committees/{id}/members/{userId}", committeeId, childId);
            case LEAVE -> post("/api/v1/committees/{id}/members/me/leave", committeeId);
            case DISTRIBUTE -> post("/api/v1/committees/{id}/distributions", committeeId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(distributeBody()));
            case LIST_DISTRIBUTIONS -> get("/api/v1/committees/{id}/distributions", committeeId);
            case GET_DISTRIBUTION -> get("/api/v1/committees/{id}/distributions/{distributionId}",
                    committeeId, childId);
            case SEND_INVITATIONS -> post("/api/v1/committees/{id}/invitations", committeeId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("inviteeUserIds", List.of(FOREIGN_USER_ID))));
            case LIST_INVITATIONS -> get("/api/v1/committees/{id}/invitations", committeeId);
            case CONFIRM_MINUTES -> patch("/api/v1/committees/{id}/activity-records/{recordId}/confirm",
                    committeeId, childId);
            case CANCEL_INVITATION -> delete("/api/v1/committee-invitations/{invitationId}", target.invitationId());
            case ACCEPT_BY_TOKEN -> post("/api/v1/committee-invitations/accept-by-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("inviteToken", target.inviteToken())));
            case DECLINE_BY_TOKEN -> post("/api/v1/committee-invitations/decline-by-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("inviteToken", target.inviteToken())));
        };
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return mockMvc.perform(request.header("Accept-Language", "ja")).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(body(result));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 委員会ドメインの全行（委員会・メンバー・招集状・伝達ログ・委員会スコープの活動記録）。 */
    private String snapshot() {
        StringBuilder sb = new StringBuilder();
        for (String sql : List.of(
                "SELECT * FROM committees ORDER BY id",
                "SELECT * FROM committee_members ORDER BY id",
                "SELECT * FROM committee_invitations ORDER BY id",
                "SELECT * FROM committee_distribution_logs ORDER BY id",
                "SELECT * FROM activity_results WHERE scope_type = 'COMMITTEE' ORDER BY id")) {
            sb.append(Arrays.deepToString(em.createNativeQuery(sql).getResultList().toArray())).append('\n');
        }
        return sb.toString();
    }

    // ═════════════════════════════════════════════════════════════════════
    // フィクスチャ
    // ═════════════════════════════════════════════════════════════════════

    private Map<String, Object> distributeBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contentType", "CUSTOM_MESSAGE");
        body.put("customTitle", "伝達タイトル");
        body.put("customBody", "伝達本文");
        body.put("targetScope", "COMMITTEE_ONLY");
        body.put("announcementEnabled", false);
        body.put("confirmationMode", "NONE");
        return body;
    }

    private Long insertCommittee(Long organizationId, String name, CommitteeVisibility visibility) {
        return committeeRepository.save(CommitteeEntity.builder()
                .organizationId(organizationId)
                .name(name)
                .status(CommitteeStatus.ACTIVE)
                .visibilityToOrg(visibility)
                .createdBy(FOREIGN_USER_ID)
                .build()).getId();
    }

    private void insertMember(Long committeeId, Long userId, CommitteeRole role) {
        committeeMemberRepository.save(CommitteeMemberEntity.builder()
                .committeeId(committeeId)
                .userId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private CommitteeInvitationEntity insertInvitation(Long committeeId, Long inviteeUserId, Long invitedBy) {
        return committeeInvitationRepository.save(CommitteeInvitationEntity.builder()
                .committeeId(committeeId)
                .inviteeUserId(inviteeUserId)
                .proposedRole(CommitteeRole.MEMBER)
                .inviteToken(UUID.randomUUID().toString())
                .invitedBy(invitedBy)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build());
    }

    private Long insertDistributionLog(Long committeeId, Long createdBy) {
        String title = "伝達ログ " + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO committee_distribution_logs "
                                + "(committee_id, content_type, custom_title, custom_body, target_scope, "
                                + "announcement_enabled, confirmation_mode, created_by, created_at, updated_at) "
                                + "VALUES (:committeeId, 'CUSTOM_MESSAGE', :title, '本文', 'COMMITTEE_ONLY', "
                                + "0, 'NONE', :createdBy, NOW(), NOW())")
                .setParameter("committeeId", committeeId)
                .setParameter("title", title)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM committee_distribution_logs WHERE custom_title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }

    private Long insertCommitteeActivityRecord(Long committeeId, Long createdBy) {
        String title = "議事録 " + UUID.randomUUID();
        em.createNativeQuery(
                        "INSERT INTO activity_results "
                                + "(scope_type, scope_id, title, activity_date, field_values, "
                                + "visibility, status, created_by, created_at, updated_at) "
                                + "VALUES ('COMMITTEE', :scopeId, :title, CURDATE(), :fieldValues, "
                                + "'MEMBERS_ONLY', 'PUBLISHED', :createdBy, NOW(), NOW())")
                .setParameter("scopeId", committeeId)
                .setParameter("title", title)
                .setParameter("fieldValues", "{\"_meta\":{\"status\":\"DRAFT\"}}")
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM activity_results WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
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
                                + "VALUES (:email, '委員会秘匿', 'テスト', '委員会秘匿テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('cmt-x-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
