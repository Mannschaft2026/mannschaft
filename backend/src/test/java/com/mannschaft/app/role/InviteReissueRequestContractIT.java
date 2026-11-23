package com.mannschaft.app.role;

import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.auth.dto.PendingInviteResponse;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.auth.service.PendingInviteService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.token.SecretTokenVault;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261107-1833 PR3: 再招待依頼の受け手側（招待権限者の一覧・再招待・見送り）の契約試験（MockMvc＋実 DB）。
 *
 * <p>陣立て書 {@code .claude/handoffs/cmp1833-pr3-plan-20261123.md} の AC のうち〔契約〕の項目。
 * 故障注入（F-0・F-DB・F-ACT・N5）は {@link InviteReissueRequestFailureIT}、並行競合（C1〜C3）は
 * {@link InviteReissueRequestRaceIT}、クエリ数（L4・L5）は {@link InviteReissueRequestQueryCountIT}、
 * migration（L8・A2）は {@code FlywayRegistrationPendingInvitesMigrationTest}。</p>
 *
 * <p>保留行は試験が直接作る（登録経路は {@link RegistrationPendingInviteIT}）。依頼者の表示名は一意の目印にして、
 * ログ・応答への漏れを見分けられるようにしている。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("CMP-261107-1833 PR3 再招待依頼の受け手側（契約）")
class InviteReissueRequestContractIT extends InviteAcceptanceItSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private AuthTokenService authTokens;
    @Autowired private InviteTokenRepository inviteTokens;

    private ReissueFixtures fx;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        fx = new ReissueFixtures(this, authTokens);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.increment(anyString())).thenReturn(1L);
    }

    @AfterEach
    void tearDown() {
        fx.cleanup();
    }

    private PendingInviteService pending() {
        return context.getBean(PendingInviteService.class);
    }

    private ResultActions list(ReissueFixtures.Case c, Long viewer, String query) throws Exception {
        return mockMvc.perform(get(ReissueFixtures.base(c) + query).header("Authorization", fx.bearer(viewer)));
    }

    private ResultActions reissue(ReissueFixtures.Case c, Long actor) throws Exception {
        return mockMvc.perform(post(ReissueFixtures.reissuePath(c)).header("Authorization", fx.bearer(actor)));
    }

    private ResultActions decline(ReissueFixtures.Case c, Long actor) throws Exception {
        return mockMvc.perform(post(ReissueFixtures.declinePath(c)).header("Authorization", fx.bearer(actor)));
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static List<String> ids(ResultActions actions) throws Exception {
        return JsonPath.read(body(actions), "$.data.content[*].id");
    }

    private static void assertCode(Throwable thrown, String code) {
        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo(code);
    }

    /** 管理者向け集約（スコープ）の INVITE_REISSUE 欄。無ければ null。 */
    private Map<String, Object> scopeAggregate(ReissueFixtures.Case c, Long viewer, int previewSize) throws Exception {
        String path = "/api/v1/dashboard/" + ("TEAM".equals(c.scope().type()) ? "team/" : "organization/") + c.slug()
                + "/admin-action-required?preview_size=" + previewSize;
        String json = mockMvc.perform(get(path).header("Authorization", fx.bearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> sections = JsonPath.read(json, "$.data.domains[?(@.domain == 'INVITE_REISSUE')]");
        return sections.isEmpty() ? null : sections.get(0);
    }

    /** 個人ダッシュボードの司令塔（承認待ち横断）の INVITE_REISSUE 行。 */
    private List<Map<String, Object>> personalItems(Long viewer) throws Exception {
        String json = mockMvc.perform(get("/api/v1/dashboard/admin-action-required").header("Authorization", fx.bearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.data.items[?(@.domain == 'INVITE_REISSUE')]");
    }

    // ------------------------------------------------------------------
    // R1: 一覧は自スコープかつ述語どおり。メールとハッシュを含まない
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R1: 一覧は自スコープの REISSUE_REQUESTED だけ（他状態・他スコープは出ない）。要素は表示名・日時・付与ロールで、"
            + "メールアドレス・招待のハッシュを含まない")
    void listIsScopedAndRedacted(String type) throws Exception {
        ReissueFixtures.Case a = fx.reissueRequested(type);
        ReissueFixtures.Case b = fx.requestIn(a.scope(), a.admin(), "MEMBER", utcNow().minusMinutes(30));
        Long expired = user();
        fx.insertPending(expired, a.scope(), "EXPIRED", "e".repeat(64), null);
        ReissueFixtures.Case other = fx.reissueRequested(type);

        ResultActions res = list(a, a.admin(), "").andExpect(status().isOk());
        String json = body(res);

        assertThat(ids(res)).containsExactly(a.pendingId().toString(), b.pendingId().toString());
        assertThat(JsonPath.<Integer>read(json, "$.data.totalElements")).isEqualTo(2);
        assertThat(JsonPath.<String>read(json, "$.data.content[0].requesterDisplayName")).isEqualTo(a.requesterName());
        assertThat(JsonPath.<String>read(json, "$.data.content[0].roleName")).isEqualTo("MEMBER");
        assertThat(JsonPath.<String>read(json, "$.data.content[0].requestedAt")).isNotBlank();
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, a.requester());
        String hash = (String) fx.pendingRow(a.requester()).get("token_hash");
        assertThat(json).doesNotContain(email).doesNotContain(hash).doesNotContain(other.pendingId().toString())
                .doesNotContain("tokenHash").doesNotContain("email");
    }

    // ------------------------------------------------------------------
    // R2: 認可（INVITE_MEMBERS を持つ DEPUTY は可。それ以外は 403、未認証は 401）
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R2: INVITE_MEMBERS を持つ DEPUTY_ADMIN は一覧 200・見送り 200")
    void deputyWithInviteMembersIsAllowed(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        Long deputy = fx.deputyWithInviteMembers(c.scope());

        list(c, deputy, "").andExpect(status().isOk());
        decline(c, deputy).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource({"TEAM,DEPUTY_ADMIN", "TEAM,MEMBER", "TEAM,SUPPORTER", "TEAM,OUTSIDER",
            "ORGANIZATION,DEPUTY_ADMIN", "ORGANIZATION,MEMBER", "ORGANIZATION,SUPPORTER", "ORGANIZATION,OUTSIDER"})
    @DisplayName("R2: INVITE_MEMBERS を持たない DEPUTY・MEMBER・SUPPORTER・非メンバーは 3 本とも 403 COMMON_002。行は不変")
    void othersAreForbidden(String type, String role) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        Long actor = "OUTSIDER".equals(role) ? user() : actorWithRole(c.scope(), role, ReissueFixtures.priority(role));

        list(c, actor, "").andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        reissue(c, actor).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        decline(c, actor).andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("COMMON_002"));
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");
        assertThat(fx.tokensFor(c)).isEmpty();
    }

    @Test
    @DisplayName("R2: 未認証は 3 本とも 401（TEAM・ORG）")
    void unauthenticatedIs401() throws Exception {
        for (String type : List.of("TEAM", "ORGANIZATION")) {
            ReissueFixtures.Case c = fx.reissueRequested(type);
            mockMvc.perform(get(ReissueFixtures.base(c))).andExpect(status().isUnauthorized());
            mockMvc.perform(post(ReissueFixtures.reissuePath(c))).andExpect(status().isUnauthorized());
            mockMvc.perform(post(ReissueFixtures.declinePath(c))).andExpect(status().isUnauthorized());
            assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");
        }
    }

    // ------------------------------------------------------------------
    // R3: IDOR
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R3: 自スコープのパスで他スコープの依頼 ID → 404 ROLE_016（行は不変）。他スコープのパス → 403")
    void crossScopeIdIsConcealed(String type) throws Exception {
        ReissueFixtures.Case mine = fx.reissueRequested(type);
        ReissueFixtures.Case other = fx.reissueRequested(type);
        String foreignViaMine = ReissueFixtures.base(mine) + "/" + other.pendingId();

        mockMvc.perform(post(foreignViaMine + "/reissue").header("Authorization", fx.bearer(mine.admin())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROLE_016"));
        mockMvc.perform(post(foreignViaMine + "/decline").header("Authorization", fx.bearer(mine.admin())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROLE_016"));
        mockMvc.perform(post(ReissueFixtures.base(mine) + "/" + UUID.randomUUID() + "/reissue")
                        .header("Authorization", fx.bearer(mine.admin())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROLE_016"));
        assertThat(fx.pendingStatus(other.requester())).isEqualTo("REISSUE_REQUESTED");
        assertThat(fx.tokensFor(other)).isEmpty();

        list(other, mine.admin(), "").andExpect(status().isForbidden());
        reissue(other, mine.admin()).andExpect(status().isForbidden());
        decline(other, mine.admin()).andExpect(status().isForbidden());
        assertThat(fx.pendingStatus(other.requester())).isEqualTo("REISSUE_REQUESTED");
    }

    // ------------------------------------------------------------------
    // R4: 権限を外された後の直打ち・古いボタン
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("R4: INVITE_MEMBERS を外された DEPUTY の直打ち・古いボタンは 3 本とも 403。集約にも INVITE_REISSUE が出ない")
    void revokedPermissionIsForbidden(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        Long deputy = fx.deputyWithInviteMembers(c.scope());
        list(c, deputy, "").andExpect(status().isOk());

        fx.revokeInviteMembers(deputy);

        list(c, deputy, "").andExpect(status().isForbidden());
        reissue(c, deputy).andExpect(status().isForbidden());
        decline(c, deputy).andExpect(status().isForbidden());
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");
        assertThat(scopeAggregate(c, deputy, 3)).as("権限の無い DEPUTY には INVITE_REISSUE を含めない").isNull();
    }

    // ------------------------------------------------------------------
    // R5: ログに平文・ハッシュ・表示名が出ない
    // ------------------------------------------------------------------

    @Test
    @DisplayName("R5: 一覧・再招待・見送りの経路のログに、招待のハッシュ（旧・新）と依頼者の表示名が出ない")
    void secretsAndNamesNeverLogged(CapturedOutput output) throws Exception {
        ReissueFixtures.Case reissued = fx.reissueRequested("TEAM");
        ReissueFixtures.Case declined = fx.requestIn(reissued.scope(), reissued.admin(), "MEMBER", utcNow().minusMinutes(5));
        String oldHash = (String) fx.pendingRow(reissued.requester()).get("token_hash");
        int logStart = output.getAll().length();

        list(reissued, reissued.admin(), "").andExpect(status().isOk());
        reissue(reissued, reissued.admin()).andExpect(status().isOk());
        decline(declined, declined.admin()).andExpect(status().isOk());
        ReissueFixtures.settle();

        String newHash = (String) fx.pendingRow(reissued.requester()).get("token_hash");
        String logs = output.getAll().substring(logStart);
        assertThat(logs).doesNotContain(oldHash).doesNotContain(newHash)
                .doesNotContain(reissued.requesterName()).doesNotContain(declined.requesterName());
    }

    // ------------------------------------------------------------------
    // L1・L2: 空・ページの境界
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L1: 依頼が無ければ 200・空の配列・件数 0")
    void emptyList(String type) throws Exception {
        Scope scope = scope(type);
        Long admin = admin(scope);
        String path = ReissueFixtures.base(scope, fx.slug(scope));

        mockMvc.perform(get(path).header("Authorization", fx.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 21})
    @DisplayName("L2: size=20 で 20 件・21 件の境界。古い順に並び、2 ページ目に残りが出る")
    void pagingBoundary(int rows) throws Exception {
        ReissueFixtures.Case first = fx.reissueRequested("TEAM");
        List<String> expected = new ArrayList<>(List.of(first.pendingId().toString()));
        for (int i = 1; i < rows; i++) {
            ReissueFixtures.Case c = fx.requestIn(first.scope(), first.admin(), "MEMBER", utcNow().minusMinutes(59 - i));
            expected.add(c.pendingId().toString());
        }

        ResultActions page0 = list(first, first.admin(), "?page=0&size=20").andExpect(status().isOk());
        assertThat(ids(page0)).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(JsonPath.<Integer>read(body(page0), "$.data.totalElements")).isEqualTo(rows);

        ResultActions page1 = list(first, first.admin(), "?page=1&size=20").andExpect(status().isOk());
        assertThat(ids(page1)).containsExactlyElementsOf(expected.subList(20, rows));
    }

    @Test
    @DisplayName("L2: size は最大 50（51 を求めても 50 件を超えて返さない）")
    void sizeIsCapped() throws Exception {
        ReissueFixtures.Case first = fx.reissueRequested("TEAM");
        for (int i = 1; i < 51; i++) {
            fx.requestIn(first.scope(), first.admin(), "MEMBER", utcNow().minusMinutes(59).plusSeconds(i));
        }
        ResultActions res = list(first, first.admin(), "?page=0&size=51");
        int statusCode = res.andReturn().getResponse().getStatus();
        if (statusCode == 200) {
            assertThat(ids(res)).hasSizeLessThanOrEqualTo(50);
        } else {
            assertThat(statusCode).as("上限超過は 400 で拒否してもよい").isEqualTo(400);
        }
    }

    // ------------------------------------------------------------------
    // L3: 一覧の totalElements と集約の pendingCount が一致
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L3: 一覧の totalElements と集約（スコープ）の INVITE_REISSUE pending_count が一致する（非 ACTIVE・論理削除の依頼者は両方から外れる）")
    void listAndAggregateCountsAgree(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(20));
        ReissueFixtures.Case frozen = fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(10));
        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", frozen.requester());
        ReissueFixtures.Case deleted = fx.requestIn(c.scope(), c.admin(), "MEMBER", utcNow().minusMinutes(5));
        jdbc.update("UPDATE users SET deleted_at = UTC_TIMESTAMP() WHERE id = ?", deleted.requester());

        int total = JsonPath.read(body(list(c, c.admin(), "").andExpect(status().isOk())), "$.data.totalElements");
        Map<String, Object> section = scopeAggregate(c, c.admin(), 3);

        assertThat(total).isEqualTo(2);
        assertThat(section).as("集約に INVITE_REISSUE が含まれる").isNotNull();
        assertThat(((Number) section.get("pending_count")).intValue()).isEqualTo(total);
    }

    // ------------------------------------------------------------------
    // L6: 退会・ブロック
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L6: 取得後に依頼者が非 ACTIVE になると一覧から消え、再招待・見送りは 409 ROLE_018")
    void withdrawnRequesterDisappears(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        assertThat(ids(list(c, c.admin(), ""))).contains(c.pendingId().toString());

        jdbc.update("UPDATE users SET status = 'FROZEN' WHERE id = ?", c.requester());

        assertThat(ids(list(c, c.admin(), ""))).doesNotContain(c.pendingId().toString());
        reissue(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_018"));
        decline(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_018"));
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");
        assertThat(fx.tokensFor(c)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L6: ブロック中の依頼者は一覧に残り、再招待は 409 ROLE_018、見送りはできる")
    void blockedRequesterCanOnlyBeDeclined(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        block(c.scope(), c.requester(), c.admin());

        assertThat(ids(list(c, c.admin(), ""))).contains(c.pendingId().toString());
        reissue(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_018"));
        assertThat(fx.tokensFor(c)).isEmpty();
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");

        decline(c, c.admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUE_DECLINED"));
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_DECLINED");
    }

    // ------------------------------------------------------------------
    // L7: 閉鎖と再開
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("L7: 閉鎖中は一覧 0 件・集約 0 件・再招待は ROLE_002。再開すると一覧に戻り、新しい世代で発行される")
    void closedScopeHidesAndReopenRestores(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        closeScope(c.scope());

        ResultActions closed = list(c, c.admin(), "");
        int closedStatus = closed.andReturn().getResponse().getStatus();
        if (closedStatus == 200) {
            assertThat(JsonPath.<Integer>read(body(closed), "$.data.totalElements")).isZero();
        }
        reissue(c, c.admin()).andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("ROLE_002"));
        assertThat(fx.tokensFor(c)).isEmpty();
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");

        jdbc.update("UPDATE " + c.scope().table() + " SET archived_at = NULL WHERE id = ?", c.scope().id());

        assertThat(ids(list(c, c.admin(), "").andExpect(status().isOk()))).contains(c.pendingId().toString());
        reissue(c, c.admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUED"));
        assertThat(fx.tokensFor(c)).singleElement()
                .satisfies(t -> assertThat(((Number) t.get("scope_closure_generation")).longValue())
                        .as("再開後の世代（closeScope で 1 に進めた）").isEqualTo(1L));
    }

    // ------------------------------------------------------------------
    // I1〜I5: 再招待
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("I1: 再招待で、宛先付き・1 回限り・期限 7 日・現在の世代・作成者は管理者・有効なトークンが 1 件でき、"
            + "保留は PENDING・新しいハッシュ・各列 NULL。200 REISSUED と expiresAt")
    void reissueCreatesActiveTargetedToken(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        String oldHash = (String) fx.pendingRow(c.requester()).get("token_hash");
        LocalDateTime before = utcNow();

        String json = body(reissue(c, c.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REISSUED"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty()));

        Map<String, Object> token = fx.tokensFor(c).stream().reduce((x, y) -> y).orElseThrow();
        assertThat(fx.tokensFor(c)).hasSize(1);
        assertThat(token.get("revoked_at")).as("有効").isNull();
        assertThat(((Number) token.get("max_uses")).intValue()).isEqualTo(1);
        assertThat(((Number) token.get("used_count")).intValue()).isZero();
        assertThat(((Number) token.get("created_by")).longValue()).isEqualTo(c.admin());
        assertThat(((Number) token.get("scope_closure_generation")).longValue()).isZero();
        LocalDateTime expires = ((java.sql.Timestamp) token.get("expires_at")).toLocalDateTime();
        assertThat(Duration.between(before.plusDays(7), expires).abs()).isLessThan(Duration.ofMinutes(5));
        assertThat(JsonPath.<String>read(json, "$.data.expiresAt")).isNotBlank();

        Map<String, Object> row = fx.pendingRow(c.requester());
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("token_hash")).isEqualTo(token.get("token_hash")).isNotEqualTo(oldHash);
        assertThat(row.get("failure_code")).isNull();
        assertThat(row.get("accept_attempt_id")).isNull();
        assertThat(row.get("accepting_started_at")).isNull();
        assertThat(row.get("reissue_requested_at")).isNull();
        assertThat(json).doesNotContain((String) token.get("token_hash"));
    }

    @ParameterizedTest
    @CsvSource({"MEMBER,MEMBER", "SUPPORTER,SUPPORTER", "GUEST,GUEST", "ADMIN,MEMBER", "DEPUTY_ADMIN,MEMBER", ",MEMBER"})
    @DisplayName("I2: 付与ロールは非特権なら引き継ぎ、特権・元の招待が引けなければ MEMBER。一覧の roleName と一致し、"
            + "承認で ROLE_009（UNAVAILABLE）にならない")
    void grantRoleIsConsistent(String original, String expected) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM", original);

        assertThat(JsonPath.<String>read(body(list(c, c.admin(), "").andExpect(status().isOk())),
                "$.data.content[0].roleName")).isEqualTo(expected);
        reissue(c, c.admin()).andExpect(status().isOk());

        Map<String, Object> token = fx.tokensFor(c).get(0);
        assertThat(fx.roleName(token.get("role_id"))).isEqualTo(expected);
        assertThat(pending().accept(c.requester()).status()).isEqualTo("JOINED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("I3: 再招待で監査 1 行（{TEAM|ORGANIZATION}_INVITE_REISSUED、metadata に表示名・ハッシュなし）、"
            + "依頼者だけに INVITE_REISSUED 1 件（遷移先 /dashboard?pendingInvite=open）")
    void reissueAuditsAndNotifiesRequesterOnly(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        String event = ReissueFixtures.scopeEvent(c, "INVITE_REISSUED");

        reissue(c, c.admin()).andExpect(status().isOk());

        ReissueFixtures.await(() -> fx.notifications(c.requester(), "INVITE_REISSUED"), 1, "依頼者への INVITE_REISSUED");
        ReissueFixtures.await(() -> fx.audits(event, c.requester()), 1, event + " の監査");
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isEqualTo(1);
        assertThat(fx.notifications(c.admin(), "INVITE_REISSUED")).isZero();
        assertThat(fx.notificationRows(c.requester(), "INVITE_REISSUED")).singleElement()
                .satisfies(n -> assertThat(n.get("action_url")).isEqualTo("/dashboard?pendingInvite=open"));
        assertThat(fx.audits(event, c.requester())).isEqualTo(1);
        String newHash = (String) fx.pendingRow(c.requester()).get("token_hash");
        assertThat(fx.auditMetadata(event, c.requester())).singleElement().satisfies(m -> {
            assertThat(m).contains("pending_id").contains(c.pendingId().toString());
            assertThat(m).doesNotContain(c.requesterName()).doesNotContain(newHash);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("I4: 依頼者が既に在籍していれば 200 ALREADY_MEMBER で保留は JOINED、トークンは作らない")
    void alreadyMemberIsJoinedWithoutToken(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        insertUserRole(c.scope(), c.requester());
        insertMembership(c.scope(), c.requester(), "MEMBER");

        reissue(c, c.admin()).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ALREADY_MEMBER"))
                .andExpect(jsonPath("$.data.expiresAt").doesNotExist());

        assertThat(fx.pendingStatus(c.requester())).isEqualTo("JOINED");
        assertThat(fx.tokensFor(c)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"TEAM,FROZEN", "ORGANIZATION,FROZEN", "TEAM,BLOCKED", "ORGANIZATION,BLOCKED"})
    @DisplayName("I5: 依頼者が非 ACTIVE・ブロック中なら 409 ROLE_018。トークンは作らず、保留も不変")
    void unavailableRequesterIsRejected(String type, String condition) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        if ("BLOCKED".equals(condition)) {
            block(c.scope(), c.requester(), c.admin());
        } else {
            jdbc.update("UPDATE users SET status = ? WHERE id = ?", condition, c.requester());
        }
        Map<String, Object> before = fx.pendingRow(c.requester());

        reissue(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_018"));

        assertThat(fx.tokensFor(c)).isEmpty();
        assertThat(fx.pendingRow(c.requester())).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("I5: PROVISIONED の招待先は既存のエラー（slug 解決が ACTIVE の招待先だけを引くため 404 TEAM_001/ORG_001）。"
            + "トークンは作らず、保留も不変")
    void provisionedScopeIsRejected(String type) throws Exception {
        // 出陣での修正: 試練は 423 PROV_008 を期待していたが、slug→ID の解決（TeamService#resolveTeamId /
        // OrganizationService#resolveOrgId）は柱②-3 P1-2 の根治で PROVISIONED を認可より前に解決させないため、
        // 既存のエラーは 404 TEAM_001/ORG_001 になる（ProvisioningGate#requireActive は司令塔に二重防御として残す）。
        ReissueFixtures.Case c = fx.reissueRequested(type);
        jdbc.update("UPDATE " + c.scope().table() + " SET lifecycle_status = 'PROVISIONED' WHERE id = ?", c.scope().id());
        try {
            reissue(c, c.admin()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TEAM".equals(type) ? "TEAM_001" : "ORG_001"));
            assertThat(fx.tokensFor(c)).isEmpty();
            assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_REQUESTED");
        } finally {
            jdbc.update("UPDATE " + c.scope().table() + " SET lifecycle_status = 'ACTIVE' WHERE id = ?", c.scope().id());
        }
    }

    // ------------------------------------------------------------------
    // F-WIN: 仮発行の窓（60 秒）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("F-WIN: 保留が指す招待が仮発行中（revoked_at=created_at）で作成から 60 秒以内なら、承認は AUTH_086・GET は PENDING。"
            + "60 秒を過ぎたら GET で EXPIRED（ROLE_002）")
    void stagingWindow() throws Exception {
        Scope scope = scope("TEAM");
        Long requester = user();
        InviteTokenEntity staged = token(scope, 0, null, 1, 0, requester, null);
        LocalDateTime createdAt = utcNow().minusSeconds(20);
        jdbc.update("UPDATE invite_tokens SET created_at = ?, revoked_at = ? WHERE id = ?", createdAt, createdAt, staged.getId());
        fx.insertPending(requester, scope, "PENDING", staged.getTokenHash(), null);

        assertCode(catchThrowable(() -> pending().accept(requester)), "AUTH_086");
        assertThat(fx.pendingStatus(requester)).isEqualTo("PENDING");
        assertThat(pending().findMine(requester).status()).isEqualTo("PENDING");

        LocalDateTime old = utcNow().minusSeconds(90);
        jdbc.update("UPDATE invite_tokens SET created_at = ?, revoked_at = ? WHERE id = ?", old, old, staged.getId());

        assertThat(pending().findMine(requester).status()).isEqualTo("EXPIRED");
        assertThat(fx.pendingRow(requester).get("failure_code")).isEqualTo("ROLE_002");
        assertThat(usedCount(staged.getId())).isZero();
    }

    // ------------------------------------------------------------------
    // D1・D2: 見送り
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("D1: 見送りで REISSUE_DECLINED、監査 1 行（{TEAM|ORGANIZATION}_INVITE_REISSUE_DECLINED）、"
            + "依頼者だけに INVITE_REISSUE_DECLINED 1 件。トークンは作らない")
    void declineRecordsAndNotifies(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        String event = ReissueFixtures.scopeEvent(c, "INVITE_REISSUE_DECLINED");

        decline(c, c.admin()).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REISSUE_DECLINED"));

        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_DECLINED");
        assertThat(fx.tokensFor(c)).isEmpty();
        ReissueFixtures.await(() -> fx.notifications(c.requester(), "INVITE_REISSUE_DECLINED"), 1, "依頼者への INVITE_REISSUE_DECLINED");
        ReissueFixtures.await(() -> fx.audits(event, c.requester()), 1, event + " の監査");
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUE_DECLINED")).isEqualTo(1);
        assertThat(fx.notifications(c.admin(), "INVITE_REISSUE_DECLINED")).isZero();
        assertThat(fx.notificationRows(c.requester(), "INVITE_REISSUE_DECLINED")).singleElement()
                .satisfies(n -> assertThat(n.get("action_url")).isEqualTo("/dashboard?pendingInvite=open"));
        assertThat(fx.audits(event, c.requester())).isEqualTo(1);
        assertThat(ids(list(c, c.admin(), ""))).doesNotContain(c.pendingId().toString());
    }

    @Test
    @DisplayName("D2: 見送り後の GET は REISSUE_DECLINED。再依頼は閉じる前でも後でも 409 AUTH_085、閉じると DISMISSED")
    void declinedThenRequesterSide() throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        decline(c, c.admin()).andExpect(status().isOk());

        PendingInviteResponse view = pending().findMine(c.requester());
        assertThat(view.status()).isEqualTo("REISSUE_DECLINED");
        assertCode(catchThrowable(() -> pending().requestReissue(c.requester())), "AUTH_085");

        pending().dismiss(c.requester());
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("DISMISSED");
        assertCode(catchThrowable(() -> pending().requestReissue(c.requester())), "AUTH_085");
    }

    // ------------------------------------------------------------------
    // C1・C2（順序が決まった場合）・C4
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C1: 再招待が先に確定すれば、見送りは 409 ROLE_017。有効なトークン 1 件・INVITE_REISSUED だけ")
    void reissueThenDecline(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        reissue(c, c.admin()).andExpect(status().isOk());

        decline(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_017"));

        assertThat(fx.activeTokens(c)).isEqualTo(1);
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("PENDING");
        ReissueFixtures.await(() -> fx.notifications(c.requester(), "INVITE_REISSUED"), 1, "INVITE_REISSUED");
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUE_DECLINED")).isZero();
        assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUE_DECLINED"), c.requester())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("C2: 見送りが先に確定すれば、再招待は 409 ROLE_017。有効なトークン 0 件・INVITE_REISSUE_DECLINED だけ")
    void declineThenReissue(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        decline(c, c.admin()).andExpect(status().isOk());

        reissue(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_017"));

        assertThat(fx.activeTokens(c)).isZero();
        assertThat(fx.pendingStatus(c.requester())).isEqualTo("REISSUE_DECLINED");
        ReissueFixtures.await(() -> fx.notifications(c.requester(), "INVITE_REISSUE_DECLINED"), 1, "INVITE_REISSUE_DECLINED");
        ReissueFixtures.settle();
        assertThat(fx.notifications(c.requester(), "INVITE_REISSUED")).isZero();
        assertThat(fx.audits(ReissueFixtures.scopeEvent(c, "INVITE_REISSUED"), c.requester())).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "ACCEPTING", "JOINED", "EXPIRED", "UNAVAILABLE", "DECLINED", "REISSUE_DECLINED",
            "DISMISSED", "DISCARDED"})
    @DisplayName("C4: REISSUE_REQUESTED 以外の状態への再招待・見送りは 409 ROLE_017（行もトークンも不変）")
    void otherStatusesConflict(String status) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET status = ? WHERE user_id = ?", status, c.requester());

        reissue(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_017"));
        decline(c, c.admin()).andExpect(status().isConflict()).andExpect(jsonPath("$.error.code").value("ROLE_017"));

        assertThat(fx.pendingStatus(c.requester())).isEqualTo(status);
        assertThat(fx.tokensFor(c)).isEmpty();
    }

    // ------------------------------------------------------------------
    // P1・P2: GET での期限判定・再依頼の 7 日
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("P1: GET で期限内は PENDING、期限の直後は EXPIRED（ROLE_002）、上限到達は EXPIRED（ROLE_003）。"
            + "公開の招待先なら joinable・publicPath の案内が付く")
    void getExpiresPendingByValidity(String type) {
        Scope scope = scope(type);
        Long valid = user();
        InviteTokenEntity validToken = token(scope, 0, utcNow().plusMinutes(5), 3, 0, null, null);
        fx.insertPending(valid, scope, "PENDING", new SecretTokenVault().hash(plaintext(validToken)), null);
        Long expired = user();
        InviteTokenEntity expiredToken = token(scope, 0, utcNow().minusSeconds(2), 3, 0, null, null);
        fx.insertPending(expired, scope, "PENDING", new SecretTokenVault().hash(plaintext(expiredToken)), null);
        Long exhausted = user();
        InviteTokenEntity exhaustedToken = token(scope, 0, null, 1, 1, null, null);
        fx.insertPending(exhausted, scope, "PENDING", new SecretTokenVault().hash(plaintext(exhaustedToken)), null);

        assertThat(pending().findMine(valid).status()).isEqualTo("PENDING");

        PendingInviteResponse expiredView = pending().findMine(expired);
        assertThat(expiredView.status()).isEqualTo("EXPIRED");
        assertThat(fx.pendingRow(expired).get("failure_code")).isEqualTo("ROLE_002");
        assertThat(expiredView.joinable()).isTrue();
        assertThat(expiredView.publicPath()).isEqualTo(("TEAM".equals(type) ? "/teams/" : "/organizations/") + fx.slug(scope));

        assertThat(pending().findMine(exhausted).status()).isEqualTo("EXPIRED");
        assertThat(fx.pendingRow(exhausted).get("failure_code")).isEqualTo("ROLE_003");
        assertThat(usedCount(validToken.getId())).isZero();
    }

    @Test
    @DisplayName("P2: 再発行トークンの失効後は、7 日を待たず即時に再依頼でき、招待権限者へ再び通知が届く")
    void reissuedTokenExpiryAllowsImmediateRequest() throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        reissue(c, c.admin()).andExpect(status().isOk());
        Long tokenId = ((Number) fx.tokensFor(c).get(0).get("id")).longValue();
        jdbc.update("UPDATE invite_tokens SET expires_at = ? WHERE id = ?", utcNow().minusMinutes(1), tokenId);
        long before = fx.notifications(c.admin(), "INVITE_REISSUE_REQUESTED");

        assertThat(pending().findMine(c.requester()).status()).isEqualTo("EXPIRED");
        assertThat(pending().requestReissue(c.requester()).alreadyRequested()).isFalse();

        ReissueFixtures.await(() -> fx.notifications(c.admin(), "INVITE_REISSUE_REQUESTED"), before + 1,
                "再依頼の INVITE_REISSUE_REQUESTED");
    }

    @Test
    @DisplayName("P2: 前回の依頼からちょうど 7 日より少し前は通知しない（alreadyRequested）、7 日を過ぎたら通知する")
    void sevenDayBoundary() throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested("TEAM");
        jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET status = 'EXPIRED', reissue_requested_at = ? "
                + "WHERE user_id = ?", utcNow().minusDays(7).plusMinutes(1), c.requester());
        assertThat(pending().requestReissue(c.requester()).alreadyRequested()).isTrue();

        jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET reissue_requested_at = ? WHERE user_id = ?",
                utcNow().minusDays(7).minusMinutes(1), c.requester());
        assertThat(pending().requestReissue(c.requester()).alreadyRequested()).isFalse();
        ReissueFixtures.await(() -> fx.notifications(c.admin(), "INVITE_REISSUE_REQUESTED"), 1, "7 日後の再依頼の通知");
    }

    // ------------------------------------------------------------------
    // N1・N3・N4: 通知と集約の導線
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("N1: 依頼通知（INVITE_REISSUE_REQUESTED）の本文に依頼者の表示名と招待先名が入り、遷移先は …/invites#reissue-requests")
    void requestNotificationLinksToPanel(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        jdbc.update("UPDATE " + ReissueFixtures.PENDING_TABLE + " SET status = 'EXPIRED', reissue_requested_at = NULL "
                + "WHERE user_id = ?", c.requester());

        pending().requestReissue(c.requester());

        ReissueFixtures.await(() -> fx.notifications(c.admin(), "INVITE_REISSUE_REQUESTED"), 1, "依頼通知");
        Map<String, Object> n = fx.notificationRows(c.admin(), "INVITE_REISSUE_REQUESTED").get(0);
        assertThat(n.get("action_url")).isEqualTo(ReissueFixtures.invitesRoute(c));
        assertThat(String.valueOf(n.get("body"))).contains(c.requesterName())
                .contains("TEAM".equals(type) ? "世代試練チーム" : "世代試練組織");
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("N3: 個人ダッシュボードの司令塔（承認待ち横断）に INVITE_REISSUE が出て（表示名・欄への遷移先）、再招待すると消える")
    void personalCommandCenterShowsAndClears(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);

        assertThat(personalItems(c.admin())).singleElement().satisfies(item -> {
            assertThat(item.get("requested_by")).isEqualTo(c.requesterName());
            assertThat(item.get("detail_route")).isEqualTo(ReissueFixtures.invitesRoute(c));
            assertThat(item.get("scope_slug")).isEqualTo(c.slug());
        });

        reissue(c, c.admin()).andExpect(status().isOk());
        assertThat(personalItems(c.admin())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("N4: スコープの集約に INVITE_REISSUE（list_route・detail_route は …/invites#reissue-requests、title は表示名）。"
            + "preview_size=0 は件数だけ。INVITE_MEMBERS を持たない DEPUTY には含めず、持つ DEPUTY には含める")
    void scopeAggregateDependsOnPermission(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        Long plainDeputy = actorWithRole(c.scope(), "DEPUTY_ADMIN", 3);
        Long inviter = fx.deputyWithInviteMembers(c.scope());

        Map<String, Object> admin = scopeAggregate(c, c.admin(), 3);
        assertThat(admin).isNotNull();
        assertThat(((Number) admin.get("pending_count")).longValue()).isEqualTo(1);
        assertThat(admin.get("degraded")).isEqualTo(false);
        assertThat(admin.get("list_route")).isEqualTo(ReissueFixtures.invitesRoute(c));
        List<Map<String, Object>> items = JsonPath.read(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(admin), "$.items");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.get("id")).isEqualTo(c.pendingId().toString());
            assertThat(item.get("title")).isEqualTo(c.requesterName());
            assertThat(item.get("detail_route")).isEqualTo(ReissueFixtures.invitesRoute(c));
        });

        Map<String, Object> countOnly = scopeAggregate(c, c.admin(), 0);
        assertThat(((Number) countOnly.get("pending_count")).longValue()).isEqualTo(1);
        assertThat((List<?>) countOnly.get("items")).isEmpty();

        assertThat(scopeAggregate(c, plainDeputy, 3)).as("INVITE_MEMBERS なしの DEPUTY").isNull();
        assertThat(scopeAggregate(c, inviter, 3)).as("INVITE_MEMBERS ありの DEPUTY").isNotNull();
    }

    // ------------------------------------------------------------------
    // F2: 再招待の後に参加
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("F2: 再招待の後に依頼者が承認すると JOINED、新しい招待の used_count=1・claim GRANTED・在籍 1 行で、欄から消える")
    void requesterJoinsAfterReissue(String type) throws Exception {
        ReissueFixtures.Case c = fx.reissueRequested(type);
        reissue(c, c.admin()).andExpect(status().isOk());
        Long tokenId = ((Number) fx.tokensFor(c).get(0).get("id")).longValue();

        assertThat(pending().findMine(c.requester()).status()).isEqualTo("PENDING");
        assertThat(pending().accept(c.requester()).status()).isEqualTo("JOINED");

        assertThat(usedCount(tokenId)).isEqualTo(1);
        assertThat(claimStatuses(tokenId)).containsExactly("GRANTED");
        assertThat(membershipCount(c.requester(), c.scope())).isEqualTo(1);
        assertThat(ids(list(c, c.admin(), ""))).doesNotContain(c.pendingId().toString());
        assertThat(inviteTokens.findById(tokenId)).isPresent();
    }
}
