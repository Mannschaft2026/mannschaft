package com.mannschaft.app.support.test.sparse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** sparse 除外の判定ヘルパーを、一時ディレクトリの小さな git リポジトリで検証する（AC-10 / AC-11 の BE 側）。 */
class SparseExcludedAssetsTest {
    private static final String DIR = "backend/src/main/resources/ranch/approved";
    private static final String OTHER = "backend/src/main/resources/other";
    private static final String A = DIR + "/a.png";
    private static final String B = DIR + "/sub/b.png";
    private static final Map<String, String> NO_CI = Map.of();

    @TempDir
    Path repo;

    @BeforeEach
    void リポジトリを作る() throws Exception {
        git("init", "-q");
        git("config", "user.email", "t@example.com");
        git("config", "user.name", "t");
        git("config", "commit.gpgsign", "false");
        write("scripts/worktree-sparse-exclusions.json",
                "{\"exclusions\":[{\"path\":\"" + DIR + "\",\"reason\":\"r\"}]}");
        write(A, "a");
        write(B, "b");
        write(OTHER + "/keep.txt", "k");
        git("add", "-A");
        git("commit", "-q", "-m", "init");
    }

    // ---- 正常系（本物の cone sparse） ----

    @Test
    void 本物のcone_sparseで対象が範囲外ならskipし理由に対象ディレクトリと展開コマンドを含む() throws Exception {
        coneSparse("scripts", OTHER);
        assertThat(repo.resolve(A)).doesNotExist();
        var d = SparseExcludedAssets.decide(DIR, NO_CI, repo);
        assertThat(d.skip()).isTrue();
        assertThat(d.reason()).contains(DIR).contains("node scripts/worktree-assets.mjs expand");
    }

    @Test
    void サブディレクトリ起点でもリポジトリルートを解決して判定する() throws Exception {
        coneSparse("scripts", OTHER);
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo.resolve(OTHER)).skip()).isTrue();
    }

    // ---- skip しない ----

    @Test
    void 全展開でsparse無効ならskipしない() {
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void sparse無効でskipWorktreeを手で立てて削除してもskipしない() throws Exception {
        for (String p : List.of(A, B)) {
            git("update-index", "--skip-worktree", p);
            Files.delete(repo.resolve(p));
        }
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void coneではないsparseはskipしない() throws Exception {
        coneSparse("scripts", OTHER);
        git("config", "core.sparseCheckoutCone", "false");
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void 対象がcone範囲内で手で削除した欠落はskipしない() throws Exception {
        coneSparse("scripts", DIR);
        Files.delete(repo.resolve(A));
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void 対象のサブディレクトリだけ展開した部分展開はskipしない() throws Exception {
        coneSparse("scripts", DIR + "/sub");
        assertThat(repo.resolve(B)).exists();
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void 追跡パスが0件ならskipしない() throws Exception {
        git("rm", "-q", "-r", DIR);
        git("commit", "-q", "-m", "rm");
        coneSparse("scripts", OTHER);
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void 除外リストに無いディレクトリはskipしない() throws Exception {
        write("scripts/worktree-sparse-exclusions.json", "{\"exclusions\":[{\"path\":\"other\",\"reason\":\"r\"}]}");
        git("commit", "-q", "-am", "list");
        coneSparse("scripts", OTHER);
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    @Test
    void 除外リストが読めなければskipしない() throws Exception {
        coneSparse(OTHER);
        assertThat(repo.resolve("scripts/worktree-sparse-exclusions.json")).doesNotExist();
        assertThat(SparseExcludedAssets.decide(DIR, NO_CI, repo).skip()).isFalse();
    }

    // ---- git リポジトリの外 / git 失敗（FE 検分での確認事項） ----

    @Test
    void gitリポジトリの外はskipせず通常実行する(@TempDir Path notRepo) {
        var d = SparseExcludedAssets.decide(DIR, NO_CI, notRepo);
        assertThat(d.skip()).isFalse();
        assertThat(d.reason()).contains("リポジトリの外");
    }

    @Test
    void dotGitがあるのにgitが失敗する場合もskipしない(@TempDir Path broken) throws Exception {
        Files.createDirectories(broken.resolve(".git"));
        var d = SparseExcludedAssets.decide(DIR, NO_CI, broken);
        assertThat(d.skip()).isFalse();
        assertThat(d.reason()).doesNotContain("リポジトリの外");
    }

    // ---- CI 判定（FE の isCiEnvironment と同じ規則。環境変数のマップで判定） ----

    @Test
    void CIの環境変数が立っていればsparse除外でもskipしない() throws Exception {
        coneSparse("scripts", OTHER);
        for (Map<String, String> env : List.of(
                Map.of("CI", "true"), Map.of("CI", "TRUE"), Map.of("CI", "1"),
                Map.of("GITHUB_ACTIONS", "true"), Map.of("CI", "false", "GITHUB_ACTIONS", "true"),
                Map.of("GITLAB_CI", "true"), Map.of("CIRCLECI", "true"), Map.of("TRAVIS", "true"),
                Map.of("BUILDKITE", "true"), Map.of("TF_BUILD", "True"), Map.of("APPVEYOR", "True"),
                Map.of("JENKINS_URL", "http://j/"), Map.of("TEAMCITY_VERSION", "2024.1"))) {
            assertThat(SparseExcludedAssets.isCi(env)).as(env.toString()).isTrue();
            assertThat(SparseExcludedAssets.decide(DIR, env, repo).skip()).as(env.toString()).isFalse();
        }
    }

    @Test
    void CIの環境変数が無いか偽値ならCIではなくskipする() throws Exception {
        coneSparse("scripts", OTHER);
        for (Map<String, String> env : List.of(
                Map.<String, String>of(), Map.of("CI", "false"), Map.of("CI", "0"), Map.of("CI", ""),
                Map.of("GITHUB_ACTIONS", ""), Map.of("GITHUB_ACTIONS", "0"), Map.of("GITHUB_ACTIONS", "false"))) {
            assertThat(SparseExcludedAssets.isCi(env)).as(env.toString()).isFalse();
            assertThat(SparseExcludedAssets.decide(DIR, env, repo).skip()).as(env.toString()).isTrue();
        }
    }

    /** 本物の cone sparse checkout にする（git が対象外の実体を消し、skip-worktree を立てる）。 */
    private void coneSparse(String... dirs) throws Exception {
        List<String> args = new ArrayList<>(List.of("sparse-checkout", "set", "--cone"));
        args.addAll(List.of(dirs));
        git(args.toArray(new String[0]));
    }

    private void write(String rel, String content) throws IOException {
        Path p = repo.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private void git(String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(String.join(" ", cmd)).isZero();
    }
}
