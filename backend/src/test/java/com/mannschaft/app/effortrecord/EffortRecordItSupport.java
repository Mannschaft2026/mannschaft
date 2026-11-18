package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.effortrecord.entity.EffortRecordEntity;
import com.mannschaft.app.effortrecord.repository.EffortRecordRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * F06.4.1 個人の頑張り記録 PR-1 試練（CMP-261115-2145）の共通土台。
 *
 * <p><b>方針</b>（TEST_CONVENTION.md §3.1.2）: 共通 {@link AbstractMySqlIntegrationTest} の MOCK 構成に
 * {@link AutoConfigureMockMvc} を重ね、<b>実 JWT・実 Security フィルタ・実 Controller・実 Service・実 MySQL</b> を通す
 * （{@code addFilters=false} は使わない）。fixture と HTTP の書込は commit し、テスト全体の rollback による
 * 偽の緑を避ける。後始末は本土台が作成したユーザーに属する effort 行・所属・チームだけに限定する。</p>
 *
 * <p><b>標準プリセット</b>: test profile は Flyway 無効（ddl-auto=create）のため、V260 migration の
 * プリセット seed が入らない。そこで<b>migration ファイル自身の seed 節</b>（{@code -- 標準プリセット定義 seed} 以降）を
 * 読み出して 1 度だけ実行する。プリセットの内容は migration を単一の正本とし、試験側に複製しない。</p>
 */
@AutoConfigureMockMvc
abstract class EffortRecordItSupport extends AbstractMySqlIntegrationTest {

    static final UUID STUDY = UUID.fromString("01938a00-0000-7000-8000-000000000001");
    static final UUID EXERCISE = UUID.fromString("01938a00-0000-7000-8000-000000000002");
    static final UUID WEIGHT = UUID.fromString("01938a00-0000-7000-8000-000000000003");

    static final String ME = "/api/v1/effort-records/me";
    static final String TOKYO = "Asia/Tokyo";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected AuthTokenService tokens;
    @Autowired protected PlatformTransactionManager txManager;
    @Autowired protected UserRepository users;
    @Autowired protected TeamRepository teams;
    @Autowired protected EffortRecordRepository records;
    @Autowired protected DataSource dataSource;
    @PersistenceContext protected EntityManager em;

    /** 本土台で作成したユーザー（後始末対象）。 */
    protected final List<Long> ownedUsers = new ArrayList<>();
    /** 本土台で作成したチーム（後始末対象）。 */
    protected final List<Long> ownedTeams = new ArrayList<>();
    /** SYSTEM_ADMIN ロールでトークンを発行するユーザー。 */
    protected final List<Long> systemAdmins = new ArrayList<>();

    @BeforeEach
    void effortプリセットseedをmigrationから投入する() throws Exception {
        seedPresetsFromMigration(dataSource);
    }

    @AfterEach
    void 所有fixtureだけ片付ける() {
        SecurityContextHolder.clearContext();
        if (ownedUsers.isEmpty() && ownedTeams.isEmpty()) {
            return;
        }
        tx(() -> {
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM effort_records WHERE owner_user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_operation_receipts WHERE actor_user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM user_roles WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM memberships WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM audit_logs WHERE user_id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedTeams) {
                em.createNativeQuery("DELETE FROM teams WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM users WHERE id = :id").setParameter("id", id).executeUpdate();
            }
            return null;
        });
        ownedUsers.clear();
        ownedTeams.clear();
        systemAdmins.clear();
    }

    // ───────────────────────────── fixture ─────────────────────────────

    protected Long user(String label) {
        return user(label, TOKYO);
    }

    /** 本人 TZ（users.timezone）を指定してユーザーを作る。未来日判定は本人 TZ を使う（親設計 §3.1）。 */
    protected Long user(String label, String timezone) {
        Long id = tx(() -> users.save(UserEntity.builder()
                .email("effort-" + UUID.randomUUID() + "@example.com")
                .lastName("頑張り").firstName(label).displayName(label).isSearchable(false)
                .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone(timezone).build()).getId());
        ownedUsers.add(id);
        return id;
    }

    protected Long systemAdmin(String label) {
        Long id = user(label);
        systemAdmins.add(id);
        return id;
    }

    protected Long team(String name) {
        Long id = tx(() -> teams.save(TeamEntity.builder()
                .slug("effort-" + UUID.randomUUID().toString().substring(0, 8)).name(name)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build()).getId());
        ownedTeams.add(id);
        return id;
    }

    protected void member(Long userId, Long teamId) {
        tx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            return null;
        });
    }

    protected void teamAdmin(Long userId, Long teamId) {
        tx(() -> {
            MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", teamId, null);
            return null;
        });
    }

    /** プリセット revision 1 の定義 JSON（migration seed の正本）を返す。 */
    protected String presetSnapshot(UUID definitionId) {
        return tx(() -> String.valueOf(em.createNativeQuery(
                        "SELECT CAST(definition_json AS CHAR) FROM effort_definition_revisions "
                                + "WHERE definition_id = UNHEX(REPLACE(:id, '-', '')) AND revision = 1")
                .setParameter("id", definitionId.toString()).getSingleResult()));
    }

    /**
     * 集計・一覧の fixture を Service を通さず実 Repository で直接永続化する（集計部隊と個人 Service 部隊が並行で
     * green 化できるよう、作成 API の実装に依存させない）。createdAt は明示指定する。
     */
    protected UUID seedRecord(Long owner, UUID definition, LocalDate recordedOn, String timezone,
                              Map<String, String> values, Instant createdAt) {
        return seedRecord(owner, definition, recordedOn, timezone, values, createdAt, null);
    }

    protected UUID seedRecord(Long owner, UUID definition, LocalDate recordedOn, String timezone,
                              Map<String, String> values, Instant createdAt, UUID fixedId) {
        String snapshot = presetSnapshot(definition);
        return tx(() -> {
            EffortRecordEntity.EffortRecordEntityBuilder<?, ?> builder = EffortRecordEntity.builder()
                    .ownerUserId(owner).definitionId(definition).definitionRevision(1)
                    .definitionSnapshot(snapshot).recordedOn(recordedOn).recordTimezone(timezone)
                    .valuesJson(write(values)).createdAt(createdAt).updatedAt(createdAt);
            if (fixedId != null) {
                builder.id(fixedId);
            }
            return records.saveAndFlush(builder.build()).getId();
        });
    }

    // ───────────────────────────── HTTP ─────────────────────────────

    protected MockHttpServletResponse http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            List<String> roles = systemAdmins.contains(actor) ? List.of("MEMBER", "SYSTEM_ADMIN") : List.of("MEMBER");
            request.header("Authorization", "Bearer " + tokens.issueAccessToken(actor, roles));
        }
        return mvc.perform(request).andReturn().getResponse();
    }

    protected JsonNode json(MockHttpServletResponse response) throws Exception {
        return mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** ステータスを本文付きで検証する（失敗時に原因が読めるよう本文を as に載せる）。 */
    protected JsonNode expect(MockHttpServletResponse response, int status) throws Exception {
        assertThat(response.getStatus()).as(response.getContentAsString(StandardCharsets.UTF_8)).isEqualTo(status);
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
    }

    protected JsonNode expectError(MockHttpServletResponse response, int status, String code) throws Exception {
        JsonNode body = expect(response, status);
        assertThat(body.at("/error/code").asText()).as(body.toString()).isEqualTo(code);
        return body;
    }

    /** 作成リクエスト本文。値 null は JSON null として送る。 */
    protected Map<String, Object> createBody(UUID definition, LocalDate recordedOn, String timezone,
                                             Map<String, String> values, String privateNote) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recordedOn", recordedOn.toString());
        body.put("recordTimezone", timezone);
        body.put("definitionUuid", definition.toString());
        body.put("definitionRevision", 1);
        body.put("definitionToken", null);
        body.put("values", valueList(values));
        body.put("privateNote", privateNote);
        return body;
    }

    protected List<Map<String, Object>> valueList(Map<String, String> values) {
        List<Map<String, Object>> list = new ArrayList<>();
        values.forEach((key, value) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("fieldKey", key);
            entry.put("value", value);
            list.add(entry);
        });
        return list;
    }

    protected MockHttpServletResponse postCreate(Long actor, Object body, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder request = post(ME).contentType(MediaType.APPLICATION_JSON).content(write(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http(request, actor);
    }

    /** 作成 API で原本を作り、201 の data を返す。 */
    protected JsonNode create(Long owner, UUID definition, LocalDate recordedOn, Map<String, String> values, String note)
            throws Exception {
        return expect(postCreate(owner, createBody(definition, recordedOn, TOKYO, values, note),
                UUID.randomUUID().toString()), 201).path("data");
    }

    /** 値の JSON 表現。null を保持するため LinkedHashMap で組む。 */
    protected static Map<String, String> values(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /** owner 応答の values 配列から fieldKey の値を取り出す（null は JSON null）。 */
    protected static JsonNode valueOf(JsonNode record, String fieldKey) {
        for (JsonNode entry : record.path("values")) {
            if (fieldKey.equals(entry.path("fieldKey").asText())) {
                return entry.path("value");
            }
        }
        throw new AssertionError("values に fieldKey=" + fieldKey + " が無い: " + record);
    }

    protected String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected <T> T tx(Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> work.get());
    }

    protected long count(String sql, Object... params) {
        return tx(() -> {
            var query = em.createNativeQuery(sql);
            for (int i = 0; i < params.length; i++) {
                query.setParameter(i + 1, params[i]);
            }
            return ((Number) query.getSingleResult()).longValue();
        });
    }

    // ─────────────────────── プリセット seed（migration 正本） ───────────────────────

    private static final Object SEED_LOCK = new Object();

    static void seedPresetsFromMigration(DataSource dataSource) throws Exception {
        synchronized (SEED_LOCK) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (var rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM effort_definitions WHERE source_type = 'PRESET'")) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        return;
                    }
                }
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try {
                    for (String sql : presetSeedStatements()) {
                        statement.execute(sql);
                    }
                    connection.commit();
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            }
        }
    }

    /** V*__create_effort_records_tables.sql の seed 節を文単位に切り出す（版番号の付け替えに追従するため名前で探す）。 */
    static List<String> presetSeedStatements() throws Exception {
        Resource[] found = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/V*__create_effort_records_tables.sql");
        assertThat(found).as("effort 表の migration がちょうど1本ある").hasSize(1);
        String sql = new String(found[0].getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int start = sql.indexOf("-- 標準プリセット定義 seed");
        assertThat(start).as("migration に標準プリセット seed 節がある").isNotNegative();
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : sql.substring(start).split("\\R")) {
            if (line.stripLeading().startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String statement = current.toString().strip();
                statements.add(statement.substring(0, statement.length() - 1));
                current.setLength(0);
            }
        }
        assertThat(statements).as("seed 節の文").isNotEmpty();
        return statements;
    }
}
