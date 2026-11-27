package com.mannschaft.app.support.test.scopecontent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CMP-261124-0159 PR-D2: ブログのタグ・連載一覧の親チーム上限 Matrix IT（F00 §20.9 AC-0159-15）。
 *
 * <p>非公開チームでは ADMIN / DEPUTY_ADMIN / MEMBER だけがタグ・連載の構成を読める。在籍サポーター（是正前データ）・
 * ゲスト・部外者・他チーム・他組織は部外者と同一の 403（COMMON_002）。記事本体は Resolver（既存の
 * {@code assertCanView}）が上限を課すため本表の対象外。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 PR-D2 cms の親チーム上限 Matrix IT（AC-0159-15）")
class CmsScopeContentAccessMatrixIT extends AbstractScopeContentAccessMatrixIT {

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "_cms.yaml");
    }
}
