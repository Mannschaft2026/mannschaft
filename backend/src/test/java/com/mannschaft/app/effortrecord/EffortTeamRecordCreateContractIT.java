package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * F06.4.1 PR-2 TEAM 定義での原本作成（試練 / red 先行。CMP-261115-2145）。AC-20（E02）。
 *
 * <p>TEAM 原本の create は definition-snapshots の署名 token が必須。改竄・他人・別チーム・版不一致の token は 400
 * （fieldErrors に definitionToken）。snapshot 本体はクライアントから受け取らず、サーバ保存の revision を複写する。
 * 拒否系は必ず同じ fixture での成功対照（201）と組にし、「TEAM 定義を一律拒否する」PR-1 の暫定挙動で偽の緑にならないようにする。
 * token の 10 分失効は時計を差し替える {@code EffortRecordTemporalIT} で検査する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 TEAM 定義の原本作成と token（AC-20）")
class EffortTeamRecordCreateContractIT extends EffortRecordItSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 2);

    private List<FieldSpec> fields() {
        return List.of(
                FieldSpec.select("subject", "科目", "[\"数学\",\"英語\"]", 0),
                FieldSpec.number("minutes", "時間", 1),
                FieldSpec.text("memo", "メモ", 2));
    }

    private Long templateFor(Long team) {
        Long admin = user("管理者");
        teamAdmin(admin, team);
        return teamTemplate(admin, team, "練習", fields());
    }

    private static String firstOptionKey(JsonNode definition) {
        return fieldOf(definition, "科目").path("options").get(0).path("key").asText();
    }

    private long records(Long owner) {
        return count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", owner);
    }

    private JsonNode rejectedToken(Long actor, Map<String, Object> body) throws Exception {
        JsonNode error = expectError(postCreate(actor, body, UUID.randomUUID().toString()), 400, "COMMON_001");
        assertThat(error.at("/error/fieldErrors").toString()).as("拒否理由は definitionToken").contains("definitionToken");
        return error;
    }

    @Test
    @DisplayName("AC-20: 正 token で TEAM 原本を作成でき、snapshot はサーバ保存の revision と同一（再読込・GET でも同じ）")
    void AC20_正tokenでTEAM原本を作成しsnapshotはサーバ保存() throws Exception {
        Long org = organization("作成組織");
        Long team = teamIn(org, "作成チーム");
        Long template = templateFor(team);
        Long owner = user("本人");
        member(owner, team);

        JsonNode snap = snapshot(owner, team, template);
        JsonNode definition = snap.path("definition");
        String uuid = definition.path("definitionUuid").asText();
        String subjectKey = firstOptionKey(definition);

        JsonNode created = createTeamRecord(owner, snap, DAY, Map.of("科目", subjectKey, "時間", "30", "メモ", "腕立て"));

        assertThat(created.path("definition")).as("応答の定義はサーバ保存の snapshot").isEqualTo(definition);
        assertThat(valueOf(created, keyOf(definition, "時間")).asText()).isEqualTo("30");
        assertThat(created.path("version").asLong()).isZero();
        assertThat(created.path("contentRevision").asLong()).isEqualTo(1);

        // 再読込: 原本の definition_snapshot は revision の definition_json と同一、definition_id / revision を束縛。
        String id = created.path("id").asText();
        String stored = tx(() -> String.valueOf(em.createNativeQuery(
                        "SELECT CAST(definition_snapshot AS CHAR) FROM effort_records WHERE id = UNHEX(REPLACE(:id, '-', ''))")
                .setParameter("id", id).getSingleResult()));
        assertThat(mapper.readTree(stored)).isEqualTo(storedRevision(uuid, definition.path("revision").asInt()));
        assertThat(count("SELECT COUNT(*) FROM effort_records WHERE id = UNHEX(REPLACE(?1, '-', '')) "
                + "AND definition_id = UNHEX(REPLACE(?2, '-', '')) AND definition_revision = ?3", id, uuid,
                definition.path("revision").asInt())).isEqualTo(1);
        assertThat(expect(http(get(ME + "/" + id), owner), 200).at("/data/definition")).isEqualTo(definition);
    }

    @Test
    @DisplayName("AC-20: クライアントが snapshot 本体・ownerUserId を送っても受け取らず 400（サーバ保存のみ）")
    void AC20_snapshot本体はクライアントから受け取らない() throws Exception {
        Long org = organization("本体組織");
        Long team = teamIn(org, "本体チーム");
        Long template = templateFor(team);
        Long owner = user("本人");
        member(owner, team);
        JsonNode snap = snapshot(owner, team, template);

        Map<String, Object> withDefinition = teamCreateBody(snap, DAY, Map.of("時間", "30"), null);
        withDefinition.put("definition", snap.path("definition"));
        expectError(postCreate(owner, withDefinition, UUID.randomUUID().toString()), 400, "COMMON_001");

        Map<String, Object> withOwner = teamCreateBody(snap, DAY, Map.of("時間", "30"), null);
        withOwner.put("ownerUserId", owner + 1);
        expectError(postCreate(owner, withOwner, UUID.randomUUID().toString()), 400, "COMMON_001");
        assertThat(records(owner)).isZero();

        // 対照: 同じ token・本文から余計な項目を除けば作成できる。
        createTeamRecord(owner, snap, DAY, Map.of("時間", "30"));
        assertThat(records(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-20: TEAM 定義は token 必須。欠落・改竄・他人の token・別チームの token・版不一致は 400 で原本を作らない")
    void AC20_token欠落改竄他人別チームは拒否() throws Exception {
        Long org = organization("拒否組織");
        Long team1 = teamIn(org, "拒否チーム1");
        Long team2 = teamIn(org, "拒否チーム2");
        Long template1 = templateFor(team1);
        Long template2 = templateFor(team2);
        Long owner = user("本人");
        member(owner, team1);
        member(owner, team2);
        Long colleague = user("同僚");
        member(colleague, team1);

        JsonNode own1 = snapshot(owner, team1, template1);
        JsonNode colleague1 = snapshot(colleague, team1, template1);
        JsonNode own2 = snapshot(owner, team2, template2);
        Map<String, String> values = Map.of("時間", "30");

        // 欠落（null）
        Map<String, Object> missing = teamCreateBody(own1, DAY, values, null);
        missing.put("definitionToken", null);
        rejectedToken(owner, missing);

        // 改竄（1 文字書換え）
        Map<String, Object> tampered = teamCreateBody(own1, DAY, values, null);
        String token = own1.path("definitionToken").asText();
        int middle = token.length() / 2;
        tampered.put("definitionToken", token.substring(0, middle) + (token.charAt(middle) == 'A' ? 'B' : 'A')
                + token.substring(middle + 1));
        rejectedToken(owner, tampered);

        // でたらめな文字列
        Map<String, Object> garbage = teamCreateBody(own1, DAY, values, null);
        garbage.put("definitionToken", "not-a-token");
        rejectedToken(owner, garbage);

        // 他人（同じ定義・同じ revision でも本人 ID に束縛）
        rejectedToken(owner, teamCreateBody(colleague1, DAY, values, null));

        // 別チームの token を team1 の定義へ流用
        Map<String, Object> crossTeam = teamCreateBody(own1, DAY, values, null);
        crossTeam.put("definitionToken", own2.path("definitionToken").asText());
        rejectedToken(owner, crossTeam);

        // 版不一致（token の revision と definitionRevision が違う）
        Map<String, Object> wrongRevision = teamCreateBody(own1, DAY, values, null);
        wrongRevision.put("definitionRevision", own1.at("/definition/revision").asInt() + 1);
        expectError(postCreate(owner, wrongRevision, UUID.randomUUID().toString()), 400, "COMMON_001");

        assertThat(records(owner)).as("拒否された要求は原本を作らない").isZero();

        // 対照: 本人の正 token なら同じ値で作成できる（拒否が TEAM 一律拒否ではないこと）。
        createTeamRecord(owner, own1, DAY, values);
        createTeamRecord(owner, own2, DAY, values);
        assertThat(records(owner)).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-20: token 発行後に脱退した本人の create は 403（現在所属を再検証）。所属を続ける同僚は作成できる")
    void AC20_token発行後に脱退した本人は403() throws Exception {
        Long org = organization("脱退組織");
        Long team = teamIn(org, "脱退チーム");
        Long template = templateFor(team);
        Long leaver = user("脱退者");
        member(leaver, team);
        Long stayer = user("残留者");
        member(stayer, team);

        JsonNode leaverSnap = snapshot(leaver, team, template);
        JsonNode stayerSnap = snapshot(stayer, team, template);
        leave(leaver, team);

        expectError(postCreate(leaver, teamCreateBody(leaverSnap, DAY, Map.of("時間", "10"), null),
                UUID.randomUUID().toString()), 403, "COMMON_002");
        assertThat(records(leaver)).isZero();

        createTeamRecord(stayer, stayerSnap, DAY, Map.of("時間", "10"));
        assertThat(records(stayer)).isEqualTo(1);
    }
}
