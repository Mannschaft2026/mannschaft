package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261103-0822 AC7 お知らせ削除・ピン留めの閲覧資格 Guard の構造番人（試練 red）。
 *
 * <p>凍結ストアを使わない普通の検査（{@code FreezingArchRule} 不使用・{@code archunit_store} に触れない）。
 * 出陣で作る Guard は文字列で持つ（コンパイルを壊さない）。</p>
 *
 * <ul>
 *   <li>Guard {@value #GUARD} が social.announcement に実在し、クラスにもメソッドにも {@code @Transactional} を持たない
 *       （閲覧資格の判定・著者/ADMIN 判定を tx の外で行い、D-3T 凍結ストアに新しい違反を作らないため）。</li>
 *   <li>チーム・組織の Controller の削除・ピン留め入口は、Guard を呼んでから
 *       {@code AnnouncementFeedService#deleteAnnouncement} / {@code #togglePin} を呼ぶ（バイトコード上の行番号で前後を判定）。</li>
 * </ul>
 */
@DisplayName("CMP-261103-0822 AC7 お知らせ削除・ピン留め Guard の構造（tx なし・Service より前に呼ぶ）")
@Tag(ArchUnitTestTag.ARCHUNIT)
class AnnouncementScopedFeedVisibilityGuardArchTest {

    static final String PACKAGE = "com.mannschaft.app.social.announcement";
    static final String GUARD = "AnnouncementScopedFeedVisibilityGuard";
    static final String FEED_SERVICE = PACKAGE + ".AnnouncementFeedService";
    static final String TEAM_CONTROLLER = PACKAGE + ".controller.AnnouncementFeedController";
    static final String ORG_CONTROLLER = PACKAGE + ".controller.AnnouncementFeedOrgController";

    private static final Set<String> TX_ANNOTATIONS = Set.of(
            "org.springframework.transaction.annotation.Transactional", "jakarta.transaction.Transactional");

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(PACKAGE);

    @Test
    @DisplayName("AC7 Guard AnnouncementScopedFeedVisibilityGuard が social.announcement に実在し、@Transactional をクラス・メソッドのどちらにも持たない")
    void ac7_Guardが実在しtxを持たない() {
        Optional<JavaClass> guard = guard();
        assertThat(guard).as("出陣で作る Guard %s が %s 配下に実在すること", GUARD, PACKAGE).isPresent();
        JavaClass guardClass = guard.orElseThrow();
        assertThat(TX_ANNOTATIONS.stream().anyMatch(guardClass::isAnnotatedWith))
                .as("Guard はクラスに @Transactional を持たない").isFalse();
        assertThat(guardClass.getMethods())
                .as("Guard はメソッドに @Transactional を持たない")
                .noneMatch(method -> TX_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith));
    }

    @ParameterizedTest(name = "AC7 {0}#{1} は Guard を呼んでから AnnouncementFeedService#{2} を呼ぶ")
    @CsvSource({
            TEAM_CONTROLLER + ", deleteAnnouncement, deleteAnnouncement",
            TEAM_CONTROLLER + ", togglePin, togglePin",
            ORG_CONTROLLER + ", deleteAnnouncement, deleteAnnouncement",
            ORG_CONTROLLER + ", togglePin, togglePin",
    })
    @DisplayName("AC7 Controller の削除・ピン留め入口は Service 呼び出しより前に Guard を呼ぶ")
    void ac7_ControllerはServiceより前にGuardを呼ぶ(String controller, String entry, String serviceMethod) {
        assertThat(CLASSES.contain(controller)).as("Controller %s の取り込み", controller).isTrue();
        JavaMethod method = entryMethod(CLASSES.get(controller), entry);
        List<JavaMethodCall> calls = List.copyOf(method.getMethodCallsFromSelf());

        OptionalInt serviceLine = calls.stream()
                .filter(call -> call.getTarget().getOwner().getFullName().equals(FEED_SERVICE)
                        && call.getTarget().getName().equals(serviceMethod))
                .mapToInt(JavaMethodCall::getLineNumber).min();
        assertThat(serviceLine).as("%s#%s が AnnouncementFeedService#%s を呼ぶこと（規則が空振りしない）",
                controller, entry, serviceMethod).isPresent();

        OptionalInt guardLine = calls.stream()
                .filter(call -> call.getTarget().getOwner().getSimpleName().equals(GUARD))
                .mapToInt(JavaMethodCall::getLineNumber).min();
        assertThat(guardLine).as("%s#%s が Guard %s を呼ぶこと", controller, entry, GUARD).isPresent();
        assertThat(guardLine.getAsInt())
                .as("%s#%s は Guard を Service#%s より前に呼ぶこと", controller, entry, serviceMethod)
                .isLessThan(serviceLine.getAsInt());
    }

    private static Optional<JavaClass> guard() {
        return CLASSES.stream()
                .filter(javaClass -> javaClass.getSimpleName().equals(GUARD))
                .findFirst();
    }

    private static JavaMethod entryMethod(JavaClass controller, String name) {
        List<JavaMethod> candidates = controller.getMethods().stream()
                .filter(method -> method.getName().equals(name))
                .toList();
        assertThat(candidates).as("%s#%s が一意に実在すること", controller.getFullName(), name).hasSize(1);
        return candidates.get(0);
    }
}
