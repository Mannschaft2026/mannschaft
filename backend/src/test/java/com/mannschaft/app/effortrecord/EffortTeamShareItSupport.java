package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelineEffortSourceEntity;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelineEffortSourceRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F06.4.1 PR-3b（チーム共有のコア・タイムラインガード・共有 API）試練の共通土台（CMP-261115-2145）。
 *
 * <p>{@link EffortShareItSupport}（PR-3a）の実 JWT・実 Security・実 MySQL 構成をそのまま継承し、構成キーは増やさない
 * （{@code @MockitoBean} / {@code @MockitoSpyBean} / {@code @DynamicPropertySource} / {@code @Import} を宣言しない。
 * ContextOverrideCensusGuardTest）。共有は preview → 共有 PUT → 非同期の後処理（team → timeline → owner ack）を
 * 実 API と実 Bean で通し、owner の GET で VISIBLE に到達するまで待つ。部分失敗は所有 fixture の行だけに効く
 * 一時 TRIGGER で実 MySQL に注入する（内部 Bean・DB はモックしない。EffortRecordIdempotencyIT と同じ作法）。</p>
 *
 * <p>タイムラインのガード・読み取り経路の試験は、共有の後処理の実装に依存させないため、努力由来の投稿
 * （timeline_posts と timeline_effort_sources の対応行）を実 Repository で直接作る {@link #seedEffortPost} も用意する。</p>
 */
abstract class EffortTeamShareItSupport extends EffortShareItSupport {

    static final String TEAM_RECORDS = "/api/v1/teams/{teamId}/effort-records";
    static final String SHARE = ME + "/{id}/team-share";
    static final String TIMELINE = "/api/v1/timeline";
    static final LocalDate DAY = LocalDate.of(2026, 11, 5);
    /** 共有の後処理（AFTER_COMMIT → coordinator）の到達待ちの上限。 */
    static final Duration SYNC_WAIT = Duration.ofSeconds(20);

    @Autowired protected ApplicationEventPublisher eventPublisher;
    @Autowired protected TimelinePostRepository timelinePosts;
    @Autowired protected TimelineEffortSourceRepository effortSources;

    /** 本土台で作った一時 TRIGGER（後始末対象）。 */
    protected final List<String> triggers = new ArrayList<>();

    /** 試験の登場人物（チーム・管理者・本人 A・同じチームの B・チームテンプレート）。 */
    protected record World(Long team, Long admin, Long owner, Long viewer, Long template) {
    }

    @AfterEach
    void 共有fixtureを片付ける() throws SQLException {
        for (String trigger : triggers) {
            triggerDdl("DROP TRIGGER IF EXISTS " + trigger);
        }
        triggers.clear();
        if (ownedUsers.isEmpty() && ownedTeams.isEmpty()) {
            return;
        }
        tx(() -> {
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM timeline_post_reactions WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM timeline_bookmarks WHERE user_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM content_reports WHERE reported_by = :id").setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedTeams) {
                for (String child : List.of("timeline_post_edits", "timeline_post_reactions", "timeline_bookmarks")) {
                    em.createNativeQuery("DELETE FROM " + child + " WHERE timeline_post_id IN (SELECT id FROM "
                            + "(SELECT id FROM timeline_posts WHERE scope_type = 'TEAM' AND scope_id = :id) p)")
                            .setParameter("id", id).executeUpdate();
                }
                em.createNativeQuery("DELETE FROM team_friends WHERE team_a_id = :id OR team_b_id = :id")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM timeline_effort_sources WHERE team_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_team_shares WHERE team_id = :id").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM effort_operation_receipts WHERE route_kind = 'TEAM' AND route_id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM timeline_effort_sources WHERE timeline_post_id IN "
                        + "(SELECT id FROM (SELECT id FROM timeline_posts WHERE user_id = :id) p)").setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM timeline_posts WHERE user_id = :id AND parent_id IS NOT NULL")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM timeline_posts WHERE user_id = :id AND repost_of_id IS NOT NULL")
                        .setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedTeams) {
                em.createNativeQuery("DELETE FROM timeline_posts WHERE scope_type = 'TEAM' AND scope_id = :id AND parent_id IS NOT NULL")
                        .setParameter("id", id).executeUpdate();
                em.createNativeQuery("DELETE FROM timeline_posts WHERE scope_type = 'TEAM' AND scope_id = :id")
                        .setParameter("id", id).executeUpdate();
            }
            for (Long id : ownedUsers) {
                em.createNativeQuery("DELETE FROM timeline_posts WHERE user_id = :id").setParameter("id", id).executeUpdate();
            }
            return null;
        });
    }

    // ───────────────────────────── fixture ─────────────────────────────

    /** 共有に使うチームテンプレートの項目（科目=SELECT、時間=NUMBER、メモ=TEXT、距離=NUMBER）。 */
    protected static List<FieldSpec> shareFields() {
        return List.of(
                FieldSpec.select("subject", "科目", "[\"数学\",\"英語\"]", 0),
                FieldSpec.number("minutes", "時間", 1),
                FieldSpec.text("memo", "メモ", 2),
                FieldSpec.number("distance", "距離", 3));
    }

    protected World world(String name) {
        return world(name, TeamShape.STANDALONE);
    }

    /** チーム（指定の親組織の形）・ADMIN・本人 A（MEMBER）・同じチームの B（MEMBER）・テンプレートを作る。 */
    protected World world(String name, TeamShape shape) {
        Long team = team(shape, name);
        Long admin = user(name + "管理者");
        teamAdmin(admin, team);
        Long owner = user(name + "本人");
        member(owner, team);
        Long viewer = user(name + "同僚");
        member(viewer, team);
        Long template = teamTemplate(admin, team, name + "練習", shareFields());
        return new World(team, admin, owner, viewer, template);
    }

    /** 既存の World のチームへ、別の本人を MEMBER で足す。 */
    protected Long anotherMember(World w, String label) {
        Long id = user(label);
        member(id, w.team());
        return id;
    }

    /** 本人の TEAM 原本を作る（科目=1つ目の選択肢・時間・メモ・距離。note は私的メモ）。 */
    protected JsonNode teamRecord(Long owner, World w, String minutes, String memo, String note) throws Exception {
        return teamRecord(owner, w.team(), w.template(), DAY, minutes, memo, note);
    }

    protected JsonNode teamRecord(Long owner, Long teamId, Long templateId, LocalDate day, String minutes, String memo,
                                  String note) throws Exception {
        JsonNode snap = snapshot(owner, teamId, templateId);
        JsonNode definition = snap.path("definition");
        Map<String, String> byLabel = new LinkedHashMap<>();
        byLabel.put("科目", fieldOf(definition, "科目").path("options").get(0).path("key").asText());
        byLabel.put("時間", minutes);
        byLabel.put("メモ", memo);
        byLabel.put("距離", "5");
        return expect(postCreate(owner, teamCreateBody(snap, day, byLabel, note), UUID.randomUUID().toString()), 201)
                .path("data");
    }

    protected static String id(JsonNode record) {
        return record.path("id").asText();
    }

    protected static long version(JsonNode record) {
        return record.path("version").asLong();
    }

    protected static long shareVersion(JsonNode record) {
        return record.path("share").path("version").asLong();
    }

    protected static String shareId(JsonNode record) {
        return record.path("share").path("id").asText();
    }

    // ───────────────────────────── 共有 API（本人） ─────────────────────────────

    /**
     * preview 本文。mappings は共有先定義の各 target に、原本定義で同じ表示名の source を対応させる
     * （無ければ source=null）。selectedFieldKeys は表示名 selectedLabels の target key。
     */
    protected Map<String, Object> previewBody(JsonNode record, Long teamId, JsonNode snapshotData, String... selectedLabels) {
        JsonNode target = snapshotData.path("definition");
        JsonNode source = record.path("definition");
        List<Map<String, Object>> mappings = new ArrayList<>();
        for (JsonNode field : target.path("fields")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("targetFieldKey", field.path("fieldKey").asText());
            String sourceKey = null;
            for (JsonNode s : source.path("fields")) {
                if (s.path("label").asText().equals(field.path("label").asText())) {
                    sourceKey = s.path("fieldKey").asText();
                }
            }
            m.put("sourceFieldKey", sourceKey);
            m.put("valueOverride", null);
            mappings.add(m);
        }
        List<String> selected = new ArrayList<>();
        for (String label : selectedLabels) {
            selected.add(keyOf(target, label));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version(record));
        body.put("teamId", teamId);
        body.put("definitionToken", snapshotData.path("definitionToken").asText());
        body.put("mappings", mappings);
        body.put("selectedFieldKeys", selected);
        return body;
    }

    /**
     * preview で送った command（mappings・selectedFieldKeys）を、発行された previewToken ごとに覚える。共有 PUT は
     * preview と同じ command を送り直す契約（token は command を hash で束縛する）なので、{@link #putShare} が引いて送る。
     */
    private final Map<String, Map<String, Object>> previewCommands = new java.util.concurrent.ConcurrentHashMap<>();

    protected MockHttpServletResponse postPreview(Long actor, String recordId, Object body) throws Exception {
        MockHttpServletResponse response = http(post(SHARE + "/preview", recordId).contentType(MediaType.APPLICATION_JSON)
                .content(write(body)), actor);
        if (response.getStatus() == 200 && body instanceof Map<?, ?> map) {
            String token = json(response).at("/data/previewToken").asText(null);
            if (token != null) {
                Map<String, Object> command = new LinkedHashMap<>();
                command.put("mappings", map.get("mappings"));
                command.put("selectedFieldKeys", map.get("selectedFieldKeys"));
                previewCommands.put(token, command);
            }
        }
        return response;
    }

    /** 最新の定義 token を取り直して preview し、200 の data を返す。 */
    protected JsonNode preview(Long actor, World w, JsonNode record, String... selectedLabels) throws Exception {
        return preview(actor, w.team(), w.template(), record, selectedLabels);
    }

    protected JsonNode preview(Long actor, Long teamId, Long templateId, JsonNode record, String... selectedLabels)
            throws Exception {
        JsonNode snap = snapshot(actor, teamId, templateId);
        return expect(postPreview(actor, id(record), previewBody(record, teamId, snap, selectedLabels)), 200).path("data");
    }

    protected MockHttpServletResponse putShare(Long actor, String recordId, long version, String previewToken, String key)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("previewToken", previewToken);
        // preview で送った command を送り直す（覚えていない token＝改竄・偽造の試験では空の command を送る）
        Map<String, Object> command = previewToken == null ? null : previewCommands.get(previewToken);
        if (command == null) {
            command = Map.of("mappings", List.of(), "selectedFieldKeys", List.of());
        }
        body.put("mappings", command.get("mappings"));
        body.put("selectedFieldKeys", command.get("selectedFieldKeys"));
        MockHttpServletRequestBuilder request = put(SHARE, recordId).contentType(MediaType.APPLICATION_JSON).content(write(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http(request, actor);
    }

    /** 共有 PUT を 202 で通し、data（OwnerRecord・PENDING）を返す。 */
    protected JsonNode share(Long actor, String recordId, long version, String previewToken) throws Exception {
        return expect(putShare(actor, recordId, version, previewToken, UUID.randomUUID().toString()), 202).path("data");
    }

    protected MockHttpServletResponse postRetry(Long actor, String recordId, long version, long shareVersion, String key)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version);
        body.put("shareVersion", shareVersion);
        MockHttpServletRequestBuilder request = post(SHARE + "/retry", recordId).contentType(MediaType.APPLICATION_JSON)
                .content(write(body));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http(request, actor);
    }

    protected MockHttpServletResponse deleteShare(Long actor, String recordId, long version, long shareVersion, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = delete(SHARE, recordId).param("version", String.valueOf(version))
                .param("share_version", String.valueOf(shareVersion));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http(request, actor);
    }

    /** 本人取消を 200 で通し、data（OwnerRecord・REVOKED）を返す。 */
    protected JsonNode revoke(Long actor, JsonNode record) throws Exception {
        return expect(deleteShare(actor, id(record), version(record), shareVersion(record), UUID.randomUUID().toString()), 200)
                .path("data");
    }

    /** 原本の全置換 PUT（時間とメモと note を差し替える）。 */
    protected MockHttpServletResponse putRecord(Long owner, JsonNode record, String minutes, String memo, String note)
            throws Exception {
        JsonNode definition = record.path("definition");
        Map<String, String> byKey = new LinkedHashMap<>();
        for (JsonNode field : definition.path("fields")) {
            String label = field.path("label").asText();
            String current = null;
            for (JsonNode v : record.path("values")) {
                if (v.path("fieldKey").asText().equals(field.path("fieldKey").asText())) {
                    current = v.path("value").isNull() ? null : v.path("value").asText();
                }
            }
            byKey.put(field.path("fieldKey").asText(), switch (label) {
                case "時間" -> minutes;
                case "メモ" -> memo;
                default -> current;
            });
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", version(record));
        body.put("recordedOn", record.path("recordedOn").asText());
        body.put("values", valueList(byKey));
        body.put("privateNote", note);
        return http(put(ME + "/{id}", id(record)).contentType(MediaType.APPLICATION_JSON).content(write(body)), owner);
    }

    protected JsonNode ownerRecord(Long owner, String recordId) throws Exception {
        return expect(http(get(ME + "/{id}", recordId), owner), 200).path("data");
    }

    protected String ownerShareState(Long owner, String recordId) throws Exception {
        JsonNode share = ownerRecord(owner, recordId).path("share");
        return share.isMissingNode() || share.isNull() ? null : share.path("state").asText();
    }

    /** owner の GET で共有状態が state になるまで待ち、その時点の owner 応答を返す。 */
    protected JsonNode awaitShareState(Long owner, String recordId, String state) throws Exception {
        await().atMost(SYNC_WAIT).pollInterval(Duration.ofMillis(100))
                .until(() -> state.equals(ownerShareState(owner, recordId)));
        return ownerRecord(owner, recordId);
    }

    /** preview → 共有 PUT → VISIBLE 到達までを実 API で通し、VISIBLE の owner 応答を返す。 */
    protected JsonNode shareVisible(World w, JsonNode record, String... selectedLabels) throws Exception {
        return shareVisible(w.owner(), w.team(), w.template(), record, selectedLabels);
    }

    protected JsonNode shareVisible(Long owner, Long teamId, Long templateId, JsonNode record, String... selectedLabels)
            throws Exception {
        JsonNode preview = preview(owner, teamId, templateId, record, selectedLabels);
        JsonNode pending = share(owner, id(record), version(record), preview.path("previewToken").asText());
        assertThat(pending.path("share").path("state").asText()).as("共有 PUT 直後は PENDING").isEqualTo("PENDING");
        return awaitShareState(owner, id(record), "VISIBLE");
    }

    // ───────────────────────────── チーム API ─────────────────────────────

    protected MockHttpServletResponse teamList(Long actor, Long teamId, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(TEAM_RECORDS, teamId);
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return http(request, actor);
    }

    protected MockHttpServletResponse teamDetail(Long actor, Long teamId, String shareId) throws Exception {
        return http(get(TEAM_RECORDS + "/{shareId}", teamId, shareId), actor);
    }

    protected MockHttpServletResponse teamDefinitions(Long actor, Long teamId) throws Exception {
        return http(get(TEAM_RECORDS + "/definitions", teamId), actor);
    }

    protected MockHttpServletResponse moderateApi(Long actor, Long teamId, String shareId, long version, String key)
            throws Exception {
        MockHttpServletRequestBuilder request = delete(TEAM_RECORDS + "/{shareId}", teamId, shareId)
                .param("version", String.valueOf(version));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return http(request, actor);
    }

    /** チーム一覧（200）の data[].id。 */
    protected List<String> listedShareIds(Long actor, Long teamId, String... params) throws Exception {
        JsonNode body = expect(teamList(actor, teamId, params), 200);
        List<String> ids = new ArrayList<>();
        body.path("data").forEach(r -> ids.add(r.path("id").asText()));
        return ids;
    }

    /** チーム詳細（200）の data。 */
    protected JsonNode sharedRecord(Long actor, Long teamId, String shareId) throws Exception {
        return expect(teamDetail(actor, teamId, shareId), 200).path("data");
    }

    // ───────────────────────────── 観測（別 TX で再読込） ─────────────────────────────

    /** effort_team_shares の行（@SQLRestriction を通さず論理削除行も読む）。無ければ空 Map。 */
    protected Map<String, Object> teamShareRow(String shareId) {
        List<?> rows = tx(() -> em.createNativeQuery("SELECT state, share_version, source_version, grant_membership_id, "
                        + "version, deleted_at IS NOT NULL, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), team_id, "
                        + "CAST(projection_json AS CHAR) FROM effort_team_shares WHERE id = UNHEX(REPLACE(:id, '-', ''))")
                .setParameter("id", shareId).getResultList());
        Map<String, Object> map = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return map;
        }
        Object[] row = (Object[]) rows.get(0);
        map.put("state", String.valueOf(row[0]));
        map.put("shareVersion", ((Number) row[1]).longValue());
        map.put("sourceVersion", ((Number) row[2]).longValue());
        map.put("grantMembershipId", ((Number) row[3]).longValue());
        map.put("version", ((Number) row[4]).longValue());
        map.put("deleted", ((Number) row[5]).intValue() == 1);
        map.put("createdAt", String.valueOf(row[6]));
        map.put("teamId", ((Number) row[7]).longValue());
        map.put("projection", String.valueOf(row[8]));
        return map;
    }

    /** effort_records の共有列（原本の再読込）。 */
    protected Map<String, Object> ownerRow(String recordId) {
        Object[] row = tx(() -> (Object[]) em.createNativeQuery("SELECT share_state, share_version, content_revision, version, "
                        + "LOWER(HEX(share_id)), share_team_id, grant_membership_id, CAST(selected_field_keys AS CHAR), last_error_code, "
                        + "CAST(values_json AS CHAR), private_note, purged_at IS NOT NULL, deleted_at IS NOT NULL, "
                        + "CAST(desired_projection AS CHAR), CAST(share_mapping_json AS CHAR), CAST(share_definition_snapshot AS CHAR), "
                        + "CAST(definition_snapshot AS CHAR), desired_hash, synced_source_version "
                        + "FROM effort_records WHERE id = UNHEX(REPLACE(:id, '-', ''))")
                .setParameter("id", recordId).getSingleResult());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("shareState", row[0] == null ? null : String.valueOf(row[0]));
        map.put("shareVersion", ((Number) row[1]).longValue());
        map.put("contentRevision", ((Number) row[2]).longValue());
        map.put("version", ((Number) row[3]).longValue());
        map.put("shareIdHex", row[4] == null ? null : String.valueOf(row[4]));
        map.put("shareTeamId", row[5] == null ? null : ((Number) row[5]).longValue());
        map.put("grantMembershipId", row[6] == null ? null : ((Number) row[6]).longValue());
        map.put("selectedFieldKeys", row[7]);
        map.put("lastErrorCode", row[8]);
        map.put("values", row[9]);
        map.put("privateNote", row[10]);
        map.put("purged", ((Number) row[11]).intValue() == 1);
        map.put("deleted", ((Number) row[12]).intValue() == 1);
        map.put("desiredProjection", row[13]);
        map.put("shareMapping", row[14]);
        map.put("shareDefinition", row[15]);
        map.put("definitionSnapshot", row[16] == null ? null : String.valueOf(row[16]));
        map.put("desiredHash", row[17]);
        map.put("syncedSourceVersion", row[18] == null ? null : ((Number) row[18]).longValue());
        return map;
    }

    /** share UUID に対応する TEAM タイムライン投稿 ID（対応表から。無ければ null）。 */
    protected Long effortPostId(String shareId) {
        List<?> rows = tx(() -> em.createNativeQuery("SELECT timeline_post_id FROM timeline_effort_sources "
                        + "WHERE share_uuid = UNHEX(REPLACE(:id, '-', ''))").setParameter("id", shareId).getResultList());
        return rows.isEmpty() ? null : ((Number) rows.get(0)).longValue();
    }

    /** チームの努力由来投稿（対応表）の行数。 */
    protected long effortSourceCount(Long teamId) {
        return count("SELECT COUNT(*) FROM timeline_effort_sources WHERE team_id = ?1", teamId);
    }

    /** チームの未削除・公開の TEAM 投稿の件数（努力由来を含む）。 */
    protected long livePostCount(Long teamId) {
        return count("SELECT COUNT(*) FROM timeline_posts WHERE scope_type = 'TEAM' AND scope_id = ?1 AND deleted_at IS NULL",
                teamId);
    }

    /** timeline_posts の行（論理削除行も読む）。 */
    protected Map<String, Object> postRow(Long postId) {
        Object[] row = tx(() -> (Object[]) em.createNativeQuery("SELECT scope_type, scope_id, share_with_friends, "
                        + "forward_source_post_id, status, deleted_at IS NOT NULL, DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s.%f'), "
                        + "content, is_pinned, reaction_count, reply_count FROM timeline_posts WHERE id = :id")
                .setParameter("id", postId).getSingleResult());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scopeType", String.valueOf(row[0]));
        map.put("scopeId", ((Number) row[1]).longValue());
        map.put("shareWithFriends", row[2] instanceof Boolean b ? b : ((Number) row[2]).intValue() == 1);
        map.put("forwardSourcePostId", row[3]);
        map.put("status", String.valueOf(row[4]));
        map.put("deleted", ((Number) row[5]).intValue() == 1);
        map.put("createdAt", String.valueOf(row[6]));
        map.put("content", row[7] == null ? null : String.valueOf(row[7]));
        map.put("pinned", row[8] instanceof Boolean b ? b : ((Number) row[8]).intValue() == 1);
        map.put("reactionCount", ((Number) row[9]).intValue());
        map.put("replyCount", ((Number) row[10]).intValue());
        return map;
    }

    // ───────────────────────────── 努力由来投稿の直接 fixture ─────────────────────────────

    /**
     * 努力由来の TEAM 投稿を実 Repository で直接作る（共有の後処理を通さない）。対応表の share_uuid は
     * どの共有にも一致しない新しい UUID とし、F00 を通らない投稿として扱う。本文 content には検索用の語を入れる。
     */
    protected Long seedEffortPost(Long teamId, Long authorId, String content) {
        Long postId = tx(() -> timelinePosts.saveAndFlush(TimelinePostEntity.builder().scopeType(PostScopeType.TEAM)
                .scopeId(teamId).userId(authorId).content(content).status(PostStatus.PUBLISHED).build()).getId());
        tx(() -> effortSources.saveAndFlush(TimelineEffortSourceEntity.builder().teamId(teamId).timelinePostId(postId)
                .shareUuid(UUID.randomUUID()).grantMembershipId(1L).shareVersion(1L).contentRevision(1L).isRevoked(false)
                .build()));
        return postId;
    }

    /** 通常の TEAM 投稿を実 Repository で作る（ガードの対照）。 */
    protected Long seedNormalPost(PostScopeType scope, Long scopeId, Long authorId, String content) {
        return tx(() -> timelinePosts.saveAndFlush(TimelinePostEntity.builder().scopeType(scope).scopeId(scopeId)
                .userId(authorId).content(content).status(PostStatus.PUBLISHED).build()).getId());
    }

    // ───────────────────────────── 障害注入（一時 TRIGGER） ─────────────────────────────

    /**
     * 所有 fixture の行だけに効く一時 TRIGGER を作る。condition は NEW/OLD を参照する SQL 条件。
     * 例: {@code failOn("timeline_effort_sources", "BEFORE INSERT", "NEW.team_id = 12")}。
     */
    protected String failOn(String table, String timing, String condition) throws SQLException {
        String name = "trg_effort3b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        triggers.add(name);
        triggerDdl("CREATE TRIGGER " + name + " " + timing + " ON " + table + " FOR EACH ROW BEGIN IF " + condition
                + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'effort pr3b injected failure'; END IF; END");
        return name;
    }

    /** 条件に合う行の書込を seconds 秒止める一時 TRIGGER（「処理中」の再現）。 */
    protected String slowOn(String table, String timing, String condition, int seconds) throws SQLException {
        String name = "trg_effort3b_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        triggers.add(name);
        triggerDdl("CREATE TRIGGER " + name + " " + timing + " ON " + table + " FOR EACH ROW BEGIN IF " + condition
                + " THEN SET @effort3b_sleep = SLEEP(" + seconds + "); END IF; END");
        return name;
    }

    protected void dropTrigger(String name) throws SQLException {
        triggerDdl("DROP TRIGGER IF EXISTS " + name);
        triggers.remove(name);
    }

    /** binary log 有効時の TRIGGER 作成は共有 Testcontainer の root 接続を使う（EffortRecordIdempotencyIT と同じ）。 */
    protected void triggerDdl(String sql) throws SQLException {
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** owner の share_updated_at を seconds 秒だけ過去へずらす（60 秒超 PENDING の再現）。 */
    protected void ageShare(String recordId, int seconds) {
        tx(() -> em.createNativeQuery("UPDATE effort_records SET share_updated_at = DATE_SUB(share_updated_at, INTERVAL :s SECOND) "
                        + "WHERE id = UNHEX(REPLACE(:id, '-', ''))").setParameter("s", seconds).setParameter("id", recordId)
                .executeUpdate());
    }

    /** 応答本文に canary が出ないこと（JSON 文字列全体で判定）。 */
    protected static void assertNoCanary(String where, String body, List<String> canaries) {
        for (String canary : canaries) {
            assertThat(body).as(where + " に " + canary + " を出さない").doesNotContain(canary);
        }
    }
}
