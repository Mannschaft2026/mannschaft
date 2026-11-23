package com.mannschaft.app.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.entity.ActivityTemplateEntity;
import com.mannschaft.app.activity.entity.ActivityTemplateFieldEntity;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 活動テンプレート更新 API の fieldKey 契約（F06.4.1 §7.1 / 先行修正 PR・試練）。
 *
 * <p>実 JWT・実 Security フィルタ・実 MySQL を通す。テスト全体は TX で包まず（HTTP の書込を commit させ、
 * 別 TX の再読込で「失敗時に DB が不変」「途中まで変えた name・項目が rollback される」を確認する）、
 * 自分が作った fixture だけを後始末で消す。TEAM と ORGANIZATION は同じ結果になることをパラメータ化で固定する。</p>
 *
 * <p>request は Map（JSON）で組む。DTO に removedFieldKeys が無い現状でもコンパイルでき、
 * 未知プロパティは無視されるため、実装前は「照合」系が red になる。</p>
 *
 * <p>AC-71 / AC-72 の一部（fields 省略で不変・removedFieldKeys 指定の削除）と AC-77 は、現行実装で通る契約の
 * 固定（characterization）であり、先行修正で壊さないための番人。AC-72 の「fields 省略 + removedFieldKeys は 400」と
 * AC-73 の各入力は red。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("活動テンプレート更新 fieldKey 契約（AC-71〜73・77）")
class ActivityTemplateUpdateContractIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/activity-templates";
    private static final String ORIGINAL_NAME = "元の名前";
    private static final String CHANGED_NAME = "変更後の名前";

    /** 旧書式の既存 key（大文字・ハイフン）。BE は既存 key の書式を検査しない（§7.1）。 */
    private static final String KEY_WEIGHT = "weight_kg";
    private static final String KEY_LEGACY = "Memo-Legacy";
    private static final String NEW_KEY = "f_3fa85f6457a1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private AuthTokenService tokens;
    @Autowired private PlatformTransactionManager txManager;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OrganizationRepository organizations;
    @Autowired private ActivityTemplateRepository templates;
    @Autowired private ActivityTemplateFieldRepository fields;
    @PersistenceContext private EntityManager em;

    private Long admin;
    private Long member;
    private TeamEntity team;
    private OrganizationEntity organization;
    private Long teamTemplateId;
    private Long organizationTemplateId;

    @BeforeEach
    void fixtureをコミットする() {
        inTx(() -> {
            admin = user("admin");
            member = user("member");
            team = team();
            organization = organization();
            membership(admin, "TEAM", team.getId(), true);
            membership(member, "TEAM", team.getId(), false);
            membership(admin, "ORGANIZATION", organization.getId(), true);
            membership(member, "ORGANIZATION", organization.getId(), false);
            teamTemplateId = seedTemplate(ActivityScopeType.TEAM, team.getId());
            organizationTemplateId = seedTemplate(ActivityScopeType.ORGANIZATION, organization.getId());
            return null;
        });
    }

    @AfterEach
    void 自分が作成したfixtureだけ削除する() {
        if (team == null || organization == null) {
            return;
        }
        inTx(() -> {
            // 数値 ID は scope_type ごとに別採番のため、(scope_type, scope_id) の組で限定する
            String scopeMatch = "((scope_type = 'TEAM' AND scope_id = :teamId) "
                    + "OR (scope_type = 'ORGANIZATION' AND scope_id = :orgId))";
            em.createNativeQuery("DELETE FROM activity_template_fields WHERE template_id IN "
                            + "(SELECT id FROM activity_templates WHERE " + scopeMatch + ")")
                    .setParameter("teamId", team.getId()).setParameter("orgId", organization.getId())
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM activity_templates WHERE " + scopeMatch)
                    .setParameter("teamId", team.getId()).setParameter("orgId", organization.getId())
                    .executeUpdate();
            for (Long userId : List.of(admin, member)) {
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id")
                        .setParameter("id", userId).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :id")
                        .setParameter("id", userId).executeUpdate();
            }
            teams.deleteAll(List.of(team));
            organizations.deleteAll(List.of(organization));
            users.deleteAllById(List.of(admin, member));
            return null;
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-71
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-71: 既存の全項目に fieldKey を付け新規を1件足して PUT すると200。既存 field の id・unit・placeholder と系列 key は不変")
    void ac71_既存全項目にfieldKeyを付け新規を足すと200で既存項目は不変(String scope) throws Exception {
        Long id = templateId(scope);
        String before = snapshot(id);
        List<ActivityTemplateFieldEntity> original = inTx(() -> fields.findByTemplateIdOrderBySortOrderAsc(id));

        Map<String, Object> body = body(CHANGED_NAME,
                List.of(weight(), legacy(), field(NEW_KEY, "新規項目", "TEXT", null, null)), null);
        JsonNode data = mapper.readTree(update(id, body, admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");

        assertThat(data.path("name").asText()).isEqualTo(CHANGED_NAME);
        Map<String, JsonNode> byKey = new LinkedHashMap<>();
        data.path("fields").forEach(f -> byKey.put(f.path("fieldKey").asText(), f));
        assertThat(byKey.keySet()).containsExactlyInAnyOrder(KEY_WEIGHT, KEY_LEGACY, NEW_KEY);
        for (ActivityTemplateFieldEntity o : original) {
            JsonNode now = byKey.get(o.getFieldKey());
            assertThat(now.path("id").asLong()).as("%s の field ID は不変", o.getFieldKey()).isEqualTo(o.getId());
            assertThat(textOrNull(now.path("unit"))).isEqualTo(o.getUnit());
            assertThat(textOrNull(now.path("placeholder"))).isEqualTo(o.getPlaceholder());
        }
        // 指標設定の系列 key は activity の fieldKey そのもの。GET で同じ key 集合・同じ ID が返る
        JsonNode got = mapper.readTree(http(get(BASE + "/{id}", id), admin).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("data");
        Set<String> gotKeys = new java.util.HashSet<>();
        got.path("fields").forEach(f -> gotKeys.add(f.path("fieldKey").asText()));
        assertThat(gotKeys).containsExactlyInAnyOrder(KEY_WEIGHT, KEY_LEGACY, NEW_KEY);
        assertThat(snapshot(id)).isNotEqualTo(before);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-72
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-72: removedFieldKeys に入れた項目だけが消える（旧書式の既存 key は書式検査されず維持される）")
    void ac72_removedFieldKeysに入れた項目だけが消える(String scope) throws Exception {
        Long id = templateId(scope);
        Long legacyFieldId = fieldIdByKey(id, KEY_LEGACY);

        update(id, body(CHANGED_NAME, List.of(legacy()), List.of(KEY_WEIGHT)), admin).andExpect(status().isOk());

        List<ActivityTemplateFieldEntity> now = inTx(() -> fields.findByTemplateIdOrderBySortOrderAsc(id));
        assertThat(now).extracting(ActivityTemplateFieldEntity::getFieldKey).containsExactly(KEY_LEGACY);
        assertThat(now.get(0).getId()).isEqualTo(legacyFieldId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-72: fields を省略すると項目は不変（name など他の項目だけ更新される）")
    void ac72_fieldsを省略すると項目は不変(String scope) throws Exception {
        Long id = templateId(scope);
        List<String> before = fieldRows(id);

        update(id, body(CHANGED_NAME, null, null), admin).andExpect(status().isOk());

        assertThat(fieldRows(id)).isEqualTo(before);
        assertThat(templateName(id)).isEqualTo(CHANGED_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-72: fields を省略して removedFieldKeys だけを送ると400 COMMON_001（DB は不変）")
    void ac72_fields省略でremovedFieldKeysだけを送ると400(String scope) throws Exception {
        Long id = templateId(scope);
        String before = snapshot(id);

        ResultActions res = update(id, body(CHANGED_NAME, null, List.of(KEY_WEIGHT)), admin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_001"));

        assertFieldErrors(res, List.of("removedFieldKeys*"));
        assertThat(snapshot(id)).isEqualTo(before);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-73
    // ═══════════════════════════════════════════════════════════════════════

    /** 入力ごとの「400 COMMON_001 + fieldErrors の field 名」。末尾 * は前方一致。 */
    static Stream<Arguments> invalidBodies() {
        List<Arguments> cases = new ArrayList<>();
        Map<String, Supplier<Map<String, Object>>> c = new LinkedHashMap<>();
        Map<String, List<String>> expected = new LinkedHashMap<>();

        add(c, expected, "fieldKey が null", () -> body(CHANGED_NAME,
                List.of(weight(), legacy(), field(null, "新規", "TEXT", null, null)), null), "fields[2].fieldKey");
        add(c, expected, "fieldKey が空白", () -> body(CHANGED_NAME,
                List.of(weight(), legacy(), field("   ", "新規", "TEXT", null, null)), null), "fields[2].fieldKey");
        add(c, expected, "fieldKey が省略", () -> {
            Map<String, Object> f = field(NEW_KEY, "新規", "TEXT", null, null);
            f.remove("fieldKey");
            return body(CHANGED_NAME, List.of(weight(), legacy(), f), null);
        }, "fields[2].fieldKey");
        add(c, expected, "fields に null 要素", () -> body(CHANGED_NAME, java.util.Collections.singletonList(null), null),
                "fields[0]*");
        add(c, expected, "未知の fieldType（新規 key）", () -> body(CHANGED_NAME,
                List.of(weight(), legacy(), field(NEW_KEY, "新規", "NO_SUCH_TYPE", null, null)), null),
                "fields[2].fieldType");
        add(c, expected, "項目数16（上限15超え）", () -> {
            List<Object> l = new ArrayList<>(List.of(weight(), legacy()));
            for (int i = 1; i <= 14; i++) {
                l.add(field(String.format("f_n%02d", i), "新規" + i, "TEXT", null, null));
            }
            return body(CHANGED_NAME, l, null);
        }, "fields");
        add(c, expected, "新規 key が51字", () -> body(CHANGED_NAME,
                List.of(weight(), legacy(), field("a" + "b".repeat(50), "新規", "TEXT", null, null)), null),
                "fields[2].fieldKey");
        for (String bad : List.of("Bad-Key", "1abc", "has space", "日本語key", "UPPER")) {
            add(c, expected, "新規 key の書式違反 " + bad, () -> body(CHANGED_NAME,
                    List.of(weight(), legacy(), field(bad, "新規", "TEXT", null, null)), null), "fields[2].fieldKey");
        }
        add(c, expected, "新規 key の重複", () -> body(CHANGED_NAME,
                List.of(weight(), legacy(), field("f_dup", "新規A", "TEXT", null, null),
                        field("f_dup", "新規B", "TEXT", null, null)), null),
                "fields[2].fieldKey", "fields[3].fieldKey");
        add(c, expected, "既存 key の重複", () -> body(CHANGED_NAME, List.of(weight(), weight(), legacy()), null),
                "fields[0].fieldKey", "fields[1].fieldKey");
        add(c, expected, "照合漏れ（既存 key が fields にも removed にも無い）", () -> body(CHANGED_NAME,
                List.of(weight()), null), "fields");
        add(c, expected, "removed に存在しない key", () -> body(CHANGED_NAME,
                List.of(weight(), legacy()), List.of("ghost_key")), "removedFieldKeys[0]");
        add(c, expected, "removed と fields の両方に同じ key", () -> body(CHANGED_NAME,
                List.of(weight(), legacy()), List.of(KEY_WEIGHT)), "removedFieldKeys[0]");
        add(c, expected, "途中まで変えて失敗（先頭の項目ラベル変更＋後続に未知の型）", () -> {
            Map<String, Object> w = weight();
            w.put("fieldLabel", "途中で変えたラベル");
            return body("途中で変えた名前", List.of(w, legacy(), field(NEW_KEY, "新規", "NO_SUCH_TYPE", null, null)), null);
        }, "fields[2].fieldType");

        for (String scope : List.of("TEAM", "ORGANIZATION")) {
            c.forEach((label, body) -> cases.add(Arguments.of(scope, label, body, expected.get(label))));
        }
        return cases.stream();
    }

    private static void add(Map<String, Supplier<Map<String, Object>>> c, Map<String, List<String>> expected,
                            String label, Supplier<Map<String, Object>> body, String... fieldNames) {
        c.put(label, body);
        expected.put(label, List.of(fieldNames));
    }

    @ParameterizedTest(name = "[{0}] {1}")
    @MethodSource("invalidBodies")
    @DisplayName("AC-73: 不正入力は400 COMMON_001・fieldErrors の field 名が契約どおり・DB は不変（500 にしない）")
    void ac73_不正入力は400で拒否されDBは不変(String scope, String label, Supplier<Map<String, Object>> body,
                                          List<String> expectedFields) throws Exception {
        Long id = templateId(scope);
        String before = snapshot(id);

        ResultActions res = update(id, body.get(), admin);

        // 項目数の上限超過だけは既存の ACTIVITY_006 を維持する（殿裁定。それ以外は COMMON_001）
        String expectedCode = label.startsWith("項目数16") ? "ACTIVITY_006" : "COMMON_001";
        res.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(expectedCode));
        assertFieldErrors(res, expectedFields);
        assertThat(snapshot(id)).as("失敗時は name・項目とも rollback され不変").isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-73: 項目数15・新規 key 50字は通る（境界の正常側）")
    void ac73_項目数15と50字keyは通る(String scope) throws Exception {
        Long id = templateId(scope);
        String key50 = "a" + "b".repeat(49);
        List<Object> l = new ArrayList<>(List.of(weight(), legacy(), field(key50, "50字", "TEXT", null, null)));
        for (int i = 1; i <= 12; i++) {
            l.add(field(String.format("f_n%02d", i), "新規" + i, "TEXT", null, null));
        }
        assertThat(l).hasSize(15);

        update(id, body(CHANGED_NAME, l, null), admin).andExpect(status().isOk());

        List<ActivityTemplateFieldEntity> now = inTx(() -> fields.findByTemplateIdOrderBySortOrderAsc(id));
        assertThat(now).hasSize(15);
        assertThat(now).extracting(ActivityTemplateFieldEntity::getFieldKey).contains(key50, KEY_LEGACY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-73: 既存項目の型を変えると ACTIVITY_018（先に変えた name・項目も rollback）")
    void ac73_型変更はACTIVITY_018でrollbackされる(String scope) throws Exception {
        Long id = templateId(scope);
        String before = snapshot(id);
        Map<String, Object> changedType = weight();
        changedType.put("fieldType", "TEXT");

        update(id, body(CHANGED_NAME, List.of(changedType, legacy()), null), admin)
                .andExpect(jsonPath("$.error.code").value("ACTIVITY_018"));

        assertThat(snapshot(id)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-73: 管理者でなければ403（DB は不変）")
    void ac73_管理者でなければ403(String scope) throws Exception {
        Long id = templateId(scope);
        String before = snapshot(id);

        update(id, body(CHANGED_NAME, List.of(weight(), legacy(), field(NEW_KEY, "新規", "TEXT", null, null)), null),
                member).andExpect(status().isForbidden());

        assertThat(snapshot(id)).isEqualTo(before);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // AC-77（BE 側でいま通る契約の固定）
    // ═══════════════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-77: 一覧は数値 scope_id で200")
    void ac77_一覧は数値scopeIdで200(String scope) throws Exception {
        http(get(BASE).param("scope_type", scope).param("scope_id", String.valueOf(scopeId(scope))), admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(templateId(scope)))
                .andExpect(jsonPath("$.data[0].fields.length()").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-77: 作成は scope_type と数値 scope_id で201")
    void ac77_作成は数値scopeIdで201(String scope) throws Exception {
        Map<String, Object> body = body("新規テンプレート", List.of(field(NEW_KEY, "項目", "TEXT", null, null)), null);

        http(post(BASE).param("scope_type", scope).param("scope_id", String.valueOf(scopeId(scope)))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scopeType").value(scope))
                .andExpect(jsonPath("$.data.scopeId").value(scopeId(scope)))
                .andExpect(jsonPath("$.data.fields[0].fieldKey").value(NEW_KEY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TEAM", "ORGANIZATION"})
    @DisplayName("AC-77: 複製は {targetScopeType, targetScopeId} 付きで201")
    void ac77_複製はtargetScope付きで201(String scope) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetScopeType", scope);
        body.put("targetScopeId", scopeId(scope));

        http(post(BASE + "/{id}/duplicate", templateId(scope)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)), admin)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.scopeType").value(scope))
                .andExpect(jsonPath("$.data.scopeId").value(scopeId(scope)))
                .andExpect(jsonPath("$.data.fields.length()").value(2));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // helper
    // ═══════════════════════════════════════════════════════════════════════

    /** expected の各 field 名が fieldErrors に含まれること（末尾 * は前方一致）。 */
    private void assertFieldErrors(ResultActions res, List<String> expected) throws Exception {
        JsonNode errors = mapper.readTree(res.andReturn().getResponse().getContentAsString())
                .path("error").path("fieldErrors");
        List<String> actual = new ArrayList<>();
        errors.forEach(e -> actual.add(e.path("field").asText()));
        for (String e : expected) {
            boolean ok = e.endsWith("*")
                    ? actual.stream().anyMatch(a -> a.startsWith(e.substring(0, e.length() - 1)))
                    : actual.contains(e);
            assertThat(ok).as("fieldErrors に %s が含まれること（実際: %s）", e, actual).isTrue();
        }
    }

    private static String textOrNull(JsonNode n) {
        return n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    private ResultActions update(Long id, Map<String, Object> body, Long actor) throws Exception {
        return http(put(BASE + "/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)), actor);
    }

    private ResultActions http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, List.of("MEMBER")));
        }
        return mockMvc.perform(request);
    }

    private static Map<String, Object> body(String name, List<?> fieldList, List<String> removed) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        if (fieldList != null) {
            body.put("fields", fieldList);
        }
        if (removed != null) {
            body.put("removedFieldKeys", removed);
        }
        return body;
    }

    private static Map<String, Object> field(String key, String label, String type, String unit, String placeholder) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("fieldKey", key);
        f.put("fieldLabel", label);
        f.put("fieldType", type);
        f.put("isRequired", false);
        f.put("isAggregatable", false);
        f.put("unit", unit);
        f.put("placeholder", placeholder);
        return f;
    }

    private static Map<String, Object> weight() {
        return field(KEY_WEIGHT, "体重", "NUMBER", "kg", "例 60");
    }

    private static Map<String, Object> legacy() {
        return field(KEY_LEGACY, "メモ", "TEXT", null, "自由記述");
    }

    private Long templateId(String scope) {
        return "TEAM".equals(scope) ? teamTemplateId : organizationTemplateId;
    }

    private Long scopeId(String scope) {
        return "TEAM".equals(scope) ? team.getId() : organization.getId();
    }

    private Long fieldIdByKey(Long templateId, String key) {
        return inTx(() -> fields.findByTemplateIdOrderBySortOrderAsc(templateId).stream()
                .filter(f -> f.getFieldKey().equals(key)).findFirst().orElseThrow().getId());
    }

    private String templateName(Long id) {
        return inTx(() -> templates.findById(id).orElseThrow().getName());
    }

    private List<String> fieldRows(Long templateId) {
        return inTx(() -> fields.findByTemplateIdOrderBySortOrderAsc(templateId).stream()
                .map(f -> f.getId() + "|" + f.getFieldKey() + "|" + f.getFieldLabel() + "|" + f.getFieldType()
                        + "|" + f.getUnit() + "|" + f.getPlaceholder() + "|" + f.getSortOrder())
                .collect(Collectors.toList()));
    }

    private String snapshot(Long templateId) {
        return templateName(templateId) + "\n" + String.join("\n", fieldRows(templateId));
    }

    private Long seedTemplate(ActivityScopeType scope, Long scopeId) {
        Long id = templates.save(ActivityTemplateEntity.builder().scopeType(scope).scopeId(scopeId)
                .name(ORIGINAL_NAME).createdBy(admin).build()).getId();
        fields.save(ActivityTemplateFieldEntity.builder().templateId(id).fieldKey(KEY_WEIGHT).fieldLabel("体重")
                .fieldType(FieldType.NUMBER).unit("kg").placeholder("例 60").sortOrder(0).build());
        fields.save(ActivityTemplateFieldEntity.builder().templateId(id).fieldKey(KEY_LEGACY).fieldLabel("メモ")
                .fieldType(FieldType.TEXT).placeholder("自由記述").sortOrder(1).build());
        return id;
    }

    private <T> T inTx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    private Long user(String label) {
        return users.save(UserEntity.builder().email("act-tpl-contract-" + UUID.randomUUID() + "@example.com")
                .lastName("試練").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo").build()).getId();
    }

    private TeamEntity team() {
        return teams.save(TeamEntity.builder().slug("acttpl-" + UUID.randomUUID().toString().substring(0, 8))
                .name("活動テンプレ契約チーム").visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build());
    }

    private OrganizationEntity organization() {
        return organizations.save(OrganizationEntity.builder()
                .slug("acttpl-" + UUID.randomUUID().toString().substring(0, 8))
                .name("活動テンプレ契約組織").orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build());
    }

    private void membership(Long userId, String scope, Long id, boolean isAdmin) {
        MembershipTestHelper.insertMembership(em, userId, ScopeType.valueOf(scope), id, RoleKind.MEMBER);
        if (isAdmin) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", "TEAM".equals(scope) ? id : null,
                    "ORGANIZATION".equals(scope) ? id : null);
        }
    }
}
