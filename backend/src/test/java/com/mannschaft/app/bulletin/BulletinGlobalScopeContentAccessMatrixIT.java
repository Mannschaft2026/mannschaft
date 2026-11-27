package com.mannschaft.app.bulletin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * CMP-261124-0159 PR-D1: V-0159-10-2: 掲示板グローバル方式（threadId・scope_id 指定）の閲覧上限 Matrix（AC-0159-10）
 *
 * <p>表: {@code scope_content_matrix/_bulletin_global.yaml}。非公開チーム（MEMBERS_AND_ABOVE）で、権限あり（ADMIN / DEPUTY_ADMIN / MEMBER）は
 * 本戦役前どおり 2xx、在籍サポーター・GUEST・部外者・他チーム・他組織は部外者と同一応答（status・error.code・error.message）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("V-0159-10-2: 掲示板グローバル方式（threadId・scope_id 指定）の閲覧上限 Matrix（AC-0159-10）")
class BulletinGlobalScopeContentAccessMatrixIT extends AbstractBulletinScopeContentMatrixIT {

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "_bulletin_global.yaml");
    }
}
