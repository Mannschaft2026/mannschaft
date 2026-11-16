package com.mannschaft.app.support.test.sparse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** sparse 除外の判定ヘルパーを、一時ディレクトリの小さな git リポジトリで検証する（AC-10 / AC-11 の BE 側）。 */
class SparseExcludedAssetsTest {
    private static final String DIR = "backend/src/main/resources/ranch/approved";
    private static final String A = DIR + "/a.png";
    private static final String B = DIR + "/sub/b.png";

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
        write("backend/src/main/resources/other/keep.txt", "k");
        git("add", "-A");
        git("commit", "-q", "-m", "init");
    }

    @Test
    void 全追跡パスがskipWorktreeで全て未展開ならskipし理由に対象ディレクトリと展開コマンドを含む() throws Exception {
        sparseExclude(A, B);
        var d = SparseExcludedAssets.decide(DIR, false, repo);
        assertThat(d.skip()).isTrue();
        assertThat(d.reason()).contains(DIR).contains("node scripts/worktree-assets.mjs expand");
    }

    @Test
    void サブディレクトリ起点でもリポジトリルートを解決して判定する() throws Exception {
        sparseExclude(A, B);
        assertThat(SparseExcludedAssets.decide(DIR, false, repo.resolve("backend/src/main/resources/other")).skip())
                .isTrue();
    }

    @Test
    void 全展開されていればskipしない() {
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void CIではsparse除外でもskipしない() throws Exception {
        sparseExclude(A, B);
        assertThat(SparseExcludedAssets.decide(DIR, true, repo).skip()).isFalse();
    }

    @Test
    void 追跡パスが0件ならskipしない() throws Exception {
        git("rm", "-q", "-r", DIR);
        git("commit", "-q", "-m", "rm");
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void 手で削除した欠落はskipしない() throws Exception {
        Files.delete(repo.resolve(A));
        Files.delete(repo.resolve(B));
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void 一部だけsparse除外で一部は手で削除ならskipしない() throws Exception {
        sparseExclude(A);
        Files.delete(repo.resolve(B));
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void 部分展開ならskipしない() throws Exception {
        sparseExclude(A);
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void 除外リストに無いディレクトリはskipしない() throws Exception {
        write("scripts/worktree-sparse-exclusions.json", "{\"exclusions\":[{\"path\":\"other\",\"reason\":\"r\"}]}");
        sparseExclude(A, B);
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void 除外リストが読めなければskipしない() throws Exception {
        Files.delete(repo.resolve("scripts/worktree-sparse-exclusions.json"));
        sparseExclude(A, B);
        assertThat(SparseExcludedAssets.decide(DIR, false, repo).skip()).isFalse();
    }

    @Test
    void gitが失敗する場所ではskipしない(@TempDir Path notRepo) {
        // git リポジトリでない一時ディレクトリ（親にもリポジトリが無い前提の TempDir）
        assertThat(SparseExcludedAssets.decide(DIR, false, notRepo.resolve("missing")).skip()).isFalse();
    }

    /** skip-worktree を立て、作業木から実体を消す（sparse checkout 後の状態と同じ）。 */
    private void sparseExclude(String... paths) throws Exception {
        for (String p : paths) {
            git("update-index", "--skip-worktree", p);
            Files.delete(repo.resolve(p));
        }
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
