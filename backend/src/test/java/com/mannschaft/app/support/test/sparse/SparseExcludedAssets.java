package com.mannschaft.app.support.test.sparse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 足軽 worktree の sparse checkout で意図的に除外された素材ディレクトリの欠落を判定する。
 *
 * <p>実素材に依存するテストが「欠落が全て意図した sparse 除外か」を知るためのもの。
 * skip してよいのは次を全て満たすときだけで、それ以外（追跡0件・git 失敗・部分展開・
 * 手で削除した欠落・CI・除外リスト外）は skip せず、テスト本体を実行して失敗させる。
 * <ul>
 *   <li>対象ディレクトリが {@code scripts/worktree-sparse-exclusions.json} の除外リストにある</li>
 *   <li>{@code git ls-files -t -z} で対象配下の追跡パスが1件以上ある</li>
 *   <li>追跡パスが全て skip-worktree（タグ S）であり、かつ作業木に1件も実在しない</li>
 * </ul>
 */
public final class SparseExcludedAssets {

    /** 除外リストの正本（リポジトリルートからの相対パス）。 */
    public static final String EXCLUSIONS_FILE = "scripts/worktree-sparse-exclusions.json";

    /** BE 同梱の牧場承認済み素材ディレクトリ。 */
    public static final String RANCH_APPROVED_DIR = "backend/src/main/resources/ranch/approved";

    private static final long GIT_TIMEOUT_SECONDS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();

    private SparseExcludedAssets() {
    }

    /** 判定結果。{@code skip} が true のときだけ {@code reason} に skip 理由が入る。 */
    public record Decision(boolean skip, String reason) {
        static Decision run(String why) {
            return new Decision(false, why);
        }

        static Decision skipped(String reason) {
            return new Decision(true, reason);
        }
    }

    /** 環境変数 {@code CI} と現在の作業ディレクトリのリポジトリから判定する。 */
    public static Decision decide(String dir) {
        return decide(dir, "true".equalsIgnoreCase(System.getenv("CI")), Path.of("").toAbsolutePath());
    }

    /**
     * @param dir     リポジトリルートからの相対ディレクトリ
     * @param ci      CI 実行か（true なら絶対に skip しない）
     * @param workDir リポジトリ内の任意のディレクトリ（ルートは {@code git rev-parse --show-toplevel} で得る）
     */
    public static Decision decide(String dir, boolean ci, Path workDir) {
        if (ci) {
            return Decision.run("CI=true のため skip しない");
        }
        Path root;
        try {
            root = Path.of(git(workDir, "rev-parse", "--show-toplevel").trim());
        } catch (IOException | RuntimeException e) {
            return Decision.run("リポジトリルートを取得できないため skip しない: " + e.getMessage());
        }
        try {
            if (!listExclusions(root).contains(dir)) {
                return Decision.run(dir + " は " + EXCLUSIONS_FILE + " の除外リストに無いため skip しない");
            }
            String out = git(root, "ls-files", "-t", "-z", "--", dir);
            int tracked = 0;
            int skipWorktree = 0;
            int present = 0;
            for (String entry : out.split("\0")) {
                if (entry.isEmpty()) {
                    continue;
                }
                // 形式: "<タグ1文字> <パス>"
                if (entry.length() < 3 || entry.charAt(1) != ' ') {
                    return Decision.run("git ls-files の出力を解釈できないため skip しない: " + entry);
                }
                tracked++;
                if (entry.charAt(0) == 'S') {
                    skipWorktree++;
                }
                if (Files.exists(root.resolve(entry.substring(2)))) {
                    present++;
                }
            }
            if (tracked == 0) {
                return Decision.run(dir + " に追跡ファイルが無いため skip しない");
            }
            if (present > 0 || skipWorktree != tracked) {
                return Decision.run(dir + " は sparse 除外ではない欠落または部分展開のため skip しない"
                        + "（追跡" + tracked + "件、skip-worktree " + skipWorktree + "件、実在" + present + "件）");
            }
            return Decision.skipped(dir + " は足軽 worktree の sparse checkout で除外されており未展開（追跡" + tracked
                    + "件）。`node scripts/worktree-assets.mjs expand` で展開すれば実行される");
        } catch (IOException | RuntimeException e) {
            return Decision.run("判定に失敗したため skip しない: " + e.getMessage());
        }
    }

    private static List<String> listExclusions(Path root) throws IOException {
        JsonNode node = JSON.readTree(Files.readString(root.resolve(EXCLUSIONS_FILE), StandardCharsets.UTF_8));
        List<String> paths = new ArrayList<>();
        for (JsonNode e : node.path("exclusions")) {
            paths.add(e.path("path").asText());
        }
        return paths;
    }

    private static String git(Path cwd, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(false).start();
        try {
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("git がタイムアウトした: " + String.join(" ", args));
            }
            if (p.exitValue() != 0) {
                throw new IOException("git が失敗した(exit " + p.exitValue() + "): " + String.join(" ", args));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("git の待機が中断された", e);
        }
    }
}
