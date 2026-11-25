package com.mannschaft.app.support.test.scopecontent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261124-0159 PR-01: 表駆動 IT ハーネス（{@link AbstractScopeContentAccessMatrixIT}）の自己検査。
 *
 * <p>本戦役前から上限が効いている経路（非公開チームの詳細 {@code GET /api/v1/teams/{slug}}）で、
 * フィクスチャ・表の読み込み・部外者との比較器が噛み合うことを確かめる。比較器が差を見逃さないことは
 * {@link #比較器は1項目の差も見逃さない} で固定する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261124-0159 Matrix IT ハーネスの自己検査")
class ScopeContentAccessMatrixHarnessSelfCheckIT extends AbstractScopeContentAccessMatrixIT {

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "_selfcheck_team.yaml");
    }

    @Test
    @DisplayName("比較器は status・error.code・error.message のどれか 1 項目の差も見逃さない")
    void 比較器は1項目の差も見逃さない() {
        Response outsider = new Response(403, "VISIBILITY_001", "m");
        assertThat(sameAsOutsider(new Response(403, "VISIBILITY_001", "m"), outsider)).isTrue();
        assertThat(sameAsOutsider(new Response(404, "VISIBILITY_001", "m"), outsider)).isFalse();
        assertThat(sameAsOutsider(new Response(403, "COMMON_002", "m"), outsider)).isFalse();
        assertThat(sameAsOutsider(new Response(403, "VISIBILITY_001", "x"), outsider)).isFalse();
    }
}
