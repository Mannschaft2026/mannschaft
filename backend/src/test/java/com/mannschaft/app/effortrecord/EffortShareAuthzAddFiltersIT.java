package com.mannschaft.app.effortrecord;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F06.4.1 PR-3b 共有 API・チーム API の認可が Security フィルタに依存しないことの契約（CMP-261115-2145。殿裁定 2026-11-24）。
 *
 * <p>新規の認可エンドポイントは ArchUnit の番人と addFilters=false の IT の両方に対応させる掟に従い、Security フィルタを外した
 * 状態（{@code addFilters=false}。認証主体は SecurityContext へ直接置く。金型: {@code TeamCoreAuthzContractIT}）でも、
 * Service 層の認可（所有者照合・チームへの現在所属・テンプレート管理権限）で他人の原本 404・非所属 403・MEMBER の非表示 403・
 * 不在チーム 404 が返ることを、各エンドポイントについて確かめる。fixture と後始末は {@link EffortTeamShareItSupport} を使う。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 PR-3b 共有 API・チーム API の認可（addFilters=false）")
class EffortShareAuthzAddFiltersIT extends EffortTeamShareItSupport {

    /** フィルタを通さないので、認証主体を SecurityContext へ直接置いて実 Controller を呼ぶ。 */
    @Override
    protected MockHttpServletResponse http(MockHttpServletRequestBuilder request, Long actor) throws Exception {
        if (actor != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(actor.toString(), null, List.of()));
        }
        try {
            return mvc.perform(request).andReturn().getResponse();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    @DisplayName("共有 API（preview・共有 PUT・retry・取消）: 他人の原本は 404 EFFORT_001、共有先チームの非所属は 403 COMMON_002")
    void 共有APIの認可() throws Exception {
        World w = world("認可AF共有");
        JsonNode record = teamRecord(w.owner(), w, "30", "腕立て", null);
        String key = UUID.randomUUID().toString();

        // 同じチームの B が A の原本を操作する（所属はあるが所有者ではない）
        JsonNode snapB = snapshot(w.viewer(), w.team(), w.template());
        expectError(postPreview(w.viewer(), id(record), previewBody(record, w.team(), snapB, "時間")), 404, "EFFORT_001");
        expectError(putShare(w.viewer(), id(record), version(record), "token", key), 404, "EFFORT_001");
        expectError(postRetry(w.viewer(), id(record), version(record), 0, key), 404, "EFFORT_001");
        expectError(deleteShare(w.viewer(), id(record), version(record), 0, key), 404, "EFFORT_001");

        // 共有先チームに所属しない本人は自分の原本でも preview できない
        Long outsider = user("認可AF非所属");
        JsonNode own = create(outsider, STUDY, DAY, values("subject", "数学", "duration", "30"), null);
        JsonNode snapA = snapshot(w.owner(), w.team(), w.template());
        expectError(postPreview(outsider, id(own), previewBody(own, w.team(), snapA, "時間")), 403, "COMMON_002");

        // 対照: 所有者かつ所属者は共有できる（認証主体が Service へ届いている）
        JsonNode visible = shareVisible(w, record, "時間");
        assertThat(shareId(visible)).isNotBlank();
    }

    @Test
    @DisplayName("チーム API（一覧・定義候補・詳細・非表示）: 非所属は 403、MEMBER の非表示は 403、不在チームは 404")
    void チームAPIの認可() throws Exception {
        World w = world("認可AFチーム");
        JsonNode visible = shareVisible(w, teamRecord(w.owner(), w, "30", "腕立て", null), "時間");
        String sid = shareId(visible);
        Long outsider = user("認可AFチーム非所属");

        expectError(teamList(outsider, w.team()), 403, "COMMON_002");
        expectError(teamDefinitions(outsider, w.team()), 403, "COMMON_002");
        expectError(teamDetail(outsider, w.team(), sid), 403, "COMMON_002");
        long version = (Long) teamShareRow(sid).get("version");
        expectError(moderateApi(outsider, w.team(), sid, version, UUID.randomUUID().toString()), 403, "COMMON_002");
        expectError(moderateApi(w.viewer(), w.team(), sid, version, UUID.randomUUID().toString()), 403, "COMMON_002");
        assertThat(teamList(w.viewer(), Long.MAX_VALUE - 11).getStatus()).as("不在チームは 404").isEqualTo(404);

        // 対照: 所属者は読め、ADMIN は非表示にできる
        assertThat(listedShareIds(w.viewer(), w.team())).containsExactly(sid);
        assertThat(sharedRecord(w.viewer(), w.team(), sid).path("id").asText()).isEqualTo(sid);
        expect(teamDefinitions(w.viewer(), w.team()), 200);
        expect(moderateApi(w.admin(), w.team(), sid, version, UUID.randomUUID().toString()), 200);
        assertThat(teamShareRow(sid).get("state")).isEqualTo("MODERATED");
    }
}
