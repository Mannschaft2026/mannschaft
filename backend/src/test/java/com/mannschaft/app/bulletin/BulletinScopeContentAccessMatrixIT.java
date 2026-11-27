package com.mannschaft.app.bulletin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;

import com.mannschaft.app.support.test.scopecontent.ScopeContentMatrixTable;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Paths;

/**
 * CMP-261124-0159 PR-D1: V-0159-10-1: 掲示板（スレッド一覧・詳細・返信・既読状況・カテゴリ・保管庫）の閲覧上限 Matrix（AC-0159-10）
 *
 * <p>表: {@code scope_content_matrix/bulletin.yaml}。非公開チーム（MEMBERS_AND_ABOVE）で、権限あり（ADMIN / DEPUTY_ADMIN / MEMBER）は
 * 本戦役前どおり 2xx、在籍サポーター・GUEST・部外者・他チーム・他組織は部外者と同一応答（status・error.code・error.message）。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("V-0159-10-1: 掲示板（スレッド一覧・詳細・返信・既読状況・カテゴリ・保管庫）の閲覧上限 Matrix（AC-0159-10）")
class BulletinScopeContentAccessMatrixIT extends AbstractBulletinScopeContentMatrixIT {

    @Override
    protected Path matrixFile() {
        return Paths.get("src", "test", "resources", "scope_content_matrix", "bulletin.yaml");
    }
    @Test
    @DisplayName("V-0159-10-1: 掲示板の全読み取り経路（経路台帳 R-bulletin-1〜9）が Matrix 表に載っている")
    void 表に全読み取り経路が載っている() {
        assertThat(ScopeContentMatrixTable.load(matrixFile()).ids())
                .containsExactlyInAnyOrderElementsOf(Set.of("R-bulletin-1", "R-bulletin-2", "R-bulletin-3", "R-bulletin-4", "R-bulletin-5", "R-bulletin-6", "R-bulletin-7", "R-bulletin-8", "R-bulletin-9"));
    }
}
