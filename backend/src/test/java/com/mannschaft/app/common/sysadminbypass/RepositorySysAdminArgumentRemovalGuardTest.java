package com.mannschaft.app.common.sysadminbypass;

import com.mannschaft.app.common.architecture.JavaSourceScanningUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役 Z-A PR4（試練）番人: Repository に「閲覧者が SYSTEM_ADMIN か」を渡して SQL で素通しさせる経路を撤去する。
 *
 * <p>z-a-design.md §1「ほか」: ActivityResultService・JobPostingService の isSystemAdmin 引数と JPQL の
 * {@code :viewerIsSystemAdmin OR} 句、同型の TournamentService・AutomaticActivityListService
 * （とそこから CSV 書き出しへ流れる ActivityStatsController / ActivityStatsService）を撤去する。
 * false 固定ではなく引数ごと撤去するので、Repository のメソッド引数・JPQL・呼び出し側のいずれにも残ってはならない。</p>
 *
 * <p>撤去後、運営アカウント（所属なし）は {@code resolveVisibleLevels} が PUBLIC だけを返すため、
 * 他テナントの DRAFT・CANCELLED・ARCHIVED・メンバー限定の行を SQL で取得できない
 * （{@code UserScopeRoleSnapshotSysAdminBypassRemovalTest#resolveVisibleLevels_publicOnly} と対）。</p>
 */
@DisplayName("Z-A PR4 番人: Repository への isSystemAdmin 引数と JPQL の素通し句の撤去")
class RepositorySysAdminArgumentRemovalGuardTest {

    private static final String MAIN = "src/main/java/com/mannschaft/app/";

    @ParameterizedTest(name = "{0} に viewerIsSystemAdmin（引数・JPQL のバインド）が無い")
    @ValueSource(strings = {
            "activity/repository/ActivityResultRepository.java",
            "jobmatching/repository/JobPostingRepository.java",
            "tournament/repository/TournamentRepository.java"})
    void repositoryHasNoSystemAdminBinding(String file) throws IOException {
        // JPQL は文字列リテラル内なのでコメントだけを伏せて走査する
        String src = JavaSourceScanningUtils.maskCommentsOnly(read(file));
        assertThat(src).as(file)
                .doesNotContain(":viewerIsSystemAdmin")
                .doesNotContain("\"viewerIsSystemAdmin\"")
                .doesNotContain("viewerIsSystemAdmin");
    }

    @ParameterizedTest(name = "{0} は snapshot 由来の SYSTEM_ADMIN を Repository／書き出しへ渡さない")
    @ValueSource(strings = {
            "activity/service/ActivityResultService.java",
            "activity/service/AutomaticActivityListService.java",
            "activity/service/ActivityStatsService.java",
            "activity/controller/ActivityStatsController.java",
            "jobmatching/service/JobPostingService.java",
            "tournament/service/TournamentService.java"})
    void callersDoNotPassSystemAdmin(String file) throws IOException {
        String src = JavaSourceScanningUtils.maskCommentsAndLiterals(read(file));
        assertThat(src).as(file)
                .doesNotContain("snapshot.isSystemAdmin()")
                .doesNotContain("roles.isSystemAdmin()")
                .doesNotContain("levels.systemAdmin()")
                .doesNotContain("viewerIsSystemAdmin");
    }

    @Test
    @DisplayName("Repository の検索メソッドに boolean の SYSTEM_ADMIN 引数が無い（リフレクション）")
    void repositoryMethodsHaveNoSystemAdminParameter() {
        List<String> hits = new ArrayList<>();
        for (Class<?> repo : List.of(
                com.mannschaft.app.activity.repository.ActivityResultRepository.class,
                com.mannschaft.app.jobmatching.repository.JobPostingRepository.class,
                com.mannschaft.app.tournament.repository.TournamentRepository.class)) {
            for (Method m : repo.getDeclaredMethods()) {
                for (Parameter p : m.getParameters()) {
                    org.springframework.data.repository.query.Param param =
                            p.getAnnotation(org.springframework.data.repository.query.Param.class);
                    if (param != null && param.value().toLowerCase().contains("systemadmin")) {
                        hits.add(repo.getSimpleName() + "#" + m.getName() + "(" + param.value() + ")");
                    }
                }
            }
        }
        assertThat(hits).as("SYSTEM_ADMIN を受け取る Repository 引数が残っている").isEmpty();
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(MAIN + file), StandardCharsets.UTF_8);
    }
}
