package com.mannschaft.app.filesharing;

import com.mannschaft.app.support.test.scopecontent.ScopeContentFixture.Actor;
import com.mannschaft.app.support.test.scopecontent.ScopeContentMatrixTable.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-D1: ファイル共有の folderId / fileId 指定の読み取り経路と、ダウンロード URL の発行（AC-0159-11）。
 *
 * <p>表: {@code scope_content_matrix/_filesharing_files.yaml}。ダウンロード URL は、権限なしの閲覧者（在籍サポーター・GUEST・
 * 部外者・他チーム・他組織）に対し部外者と同一の拒否になり、応答本文に URL が含まれないことを確かめる。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("V-0159-11-2: ファイル共有（ファイル詳細・DL URL 発行）の閲覧上限 Matrix（AC-0159-11）")
class FilesharingFileScopeContentAccessMatrixIT extends AbstractFilesharingScopeContentMatrixIT {

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "_filesharing_files.yaml");
    }

    @Test
    @DisplayName("V-0159-11-3: 権限なしにはダウンロード URL が返らず、部外者と同一応答（本文に downloadUrl を含まない）")
    void 権限なしにはダウンロードURLが返らない() throws Exception {
        Route route = new Route("R-filesharing-dl", "GET", "/api/v1/files/{fileId}/download-url",
                Map.of("fileId", "@file"), Map.of());
        Response outsider = perform(route, Actor.OUTSIDER);
        assertThat(isRejection(outsider)).as("部外者は拒否: %s", outsider).isTrue();
        for (Actor actor : List.of(Actor.SUPPORTER, Actor.GUEST, Actor.OTHER_TEAM_MEMBER, Actor.OTHER_ORG_MEMBER)) {
            Response actual = perform(route, actor);
            assertThat(sameAsOutsider(actual, outsider)).as("%s は部外者と同一応答: actual=%s outsider=%s",
                    actor, actual, outsider).isTrue();
            assertThat(String.valueOf(actual)).as("%s の応答に URL が含まれない", actor).doesNotContain("downloadUrl");
        }
    }
}
