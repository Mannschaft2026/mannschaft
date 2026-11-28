package com.mannschaft.app.filesharing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;

import com.mannschaft.app.support.test.scopecontent.ScopeContentMatrixTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Paths;

/**
 * CMP-261124-0159 PR-D1: V-0159-11-1: ファイル共有（チームフォルダの一覧・子一覧・詳細）の閲覧上限 Matrix（AC-0159-11）
 *
 * <p>表: {@code scope_content_matrix/filesharing.yaml}。非公開チーム（MEMBERS_AND_ABOVE）で、権限あり（ADMIN / DEPUTY_ADMIN / MEMBER）は
 * 本戦役前どおり 2xx、在籍サポーター・GUEST・部外者・他チーム・他組織は部外者と同一応答（status・error.code・error.message）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("V-0159-11-1: ファイル共有（チームフォルダの一覧・子一覧・詳細）の閲覧上限 Matrix（AC-0159-11）")
class FilesharingScopeContentAccessMatrixIT extends AbstractFilesharingScopeContentMatrixIT {

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "filesharing.yaml");
    }
    @Test
    @DisplayName("V-0159-11-1: ファイル共有の全読み取り経路（経路台帳 R-filesharing-1〜3）が Matrix 表に載っている")
    void 表に全読み取り経路が載っている() {
        assertThat(ScopeContentMatrixTable.load(matrixFile()).ids())
                .containsExactlyInAnyOrderElementsOf(Set.of("R-filesharing-1", "R-filesharing-2", "R-filesharing-3"));
    }
}
