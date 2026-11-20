package com.mannschaft.app.common.testing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ScopeConcealingAccessGate;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.testing.ContextOverrideCensus.Census;
import com.mannschaft.app.common.testing.ContextOverrideCensus.Entry;
import com.mannschaft.app.common.testing.ContextOverrideCensus.Snapshot;
import com.mannschaft.app.common.testing.ContextOverrideCensus.Status;
import com.mannschaft.app.role.repository.UserRoleRepository;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.support.AnnotationConfigContextLoader;

/**
 * CMP-261111-0508 追撃① 試練: テストコンテキストの BeanOverride 棚卸し（census）の番人（AC-1・AC-3・AC-4）。
 *
 * <p>全テストクラスについて、Spring が実際に適用する BeanOverride（MCC の BeanOverride 系 customizer の handlers）と構成キーを求め、
 * 凍結スナップショット {@value ContextOverrideCensus#SNAPSHOT_RESOURCE} と照合する（{@link ContextOverrideCensus#violations}）。
 * 実測は毎回 {@code backend/build/context-override-census/snapshot.tsv} に書き出す（再生成手順は規約 31）。</p>
 *
 * <p>番人本体（{@code 番人_...}）は現状 green。検体（{@code 検体_...}）は、種付けした違反を番人の判定関数が red と判定することを示す。
 * 検体クラスはテストメソッドを持たないので、JUnit も棚卸しも実行対象として数えない。</p>
 */
@DisplayName("CMP-261111-0508 追撃① 構成キーの棚卸しと認可 override の番人")
class ContextOverrideCensusGuardTest {

    private static Census census;
    private static Snapshot snapshot;

    private static synchronized Census census() throws IOException {
        if (census == null) {
            census = ContextOverrideCensus.take();
        }
        return census;
    }

    private static synchronized Snapshot snapshot() throws IOException {
        if (snapshot == null) {
            try (InputStream in = ContextOverrideCensusGuardTest.class.getClassLoader()
                    .getResourceAsStream(ContextOverrideCensus.SNAPSHOT_RESOURCE)) {
                assertThat(in).as("凍結スナップショット %s が無い（規約 31 の手順で生成する）",
                        ContextOverrideCensus.SNAPSHOT_RESOURCE).isNotNull();
                snapshot = Snapshot.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return snapshot;
    }

    // ================================================================ 番人本体（現状 green）

    @Test
    @DisplayName("AC-1/AC-3/AC-4 番人: 実測が凍結スナップショットと一致し、認可型 override の追加・解析失敗・脱落・key 数の増加が無い")
    void 番人_実測がスナップショットと一致する() throws IOException {
        Census actual = census();
        Path written = ContextOverrideCensus.writeActual(actual.toSnapshot(actual.distinctKeys()));
        System.out.println("[ctx-census] " + actual.summary() + " actual=" + written);

        List<String> violations = ContextOverrideCensus.violations(snapshot(), actual);

        assertThat(violations)
                .as("棚卸しの違反（実測は %s に書き出した。意図した変更なら規約 31 の手順で再生成する）", written)
                .isEmpty();
    }

    @Test
    @DisplayName("AC-3 番人: 非 Spring・override 0 件・override あり・解析失敗を別々に集計し、解析失敗は名前と理由を持つ")
    void 番人_分類を別々に集計する() throws IOException {
        Census actual = census();

        assertThat(actual.count(Status.NON_SPRING)).as("非 Spring のクラス").isPositive();
        assertThat(actual.count(Status.ZERO)).as("override 0 件のクラス（正常な結果として保持）").isPositive();
        assertThat(actual.count(Status.OVERRIDES)).as("override を持つクラス").isPositive();
        assertThat(actual.count(Status.NON_SPRING) + actual.count(Status.ZERO) + actual.count(Status.OVERRIDES)
                + actual.count(Status.FAILED)).isEqualTo(actual.entries().size());
        assertThat(actual.entries()).filteredOn(entry -> entry.status() == Status.FAILED)
                .allSatisfy(entry -> assertThat(entry.failure()).isNotBlank());
        assertThat(actual.entries()).filteredOn(entry -> entry.status() == Status.ZERO)
                .allSatisfy(entry -> assertThat(entry.key()).isNotNull());
    }

    @Test
    @DisplayName("AC-4 番人: distinct key 数が基準値以下（ラチェット）で、スナップショットの基準値が実測と食い違わない")
    void 番人_distinctKeyのラチェット() throws IOException {
        Census actual = census();
        Snapshot frozen = snapshot();

        assertThat(actual.distinctKeys()).as("distinct key 数（基準値はスナップショットの !distinctKeysBaseline）")
                .isLessThanOrEqualTo(frozen.distinctKeysBaseline());
        assertThat(actual.authzOverrideCount()).as("認可型 override の総件数（基準値 !authzOverrideBaseline）")
                .isLessThanOrEqualTo(frozen.authzOverrideBaseline());
    }

    @Test
    @DisplayName("AC-1 認可型の判定: denylist（完全一致・接尾辞）と明示リストの両方を認可型とする")
    void 認可型の判定() {
        for (Class<?> type : List.of(AccessGuard.class, AccessControlService.class, AuthTokenService.class,
                com.mannschaft.app.common.i18n.UserLocaleCache.class, ScopeConcealingAccessGate.class,
                UserRoleRepository.class, com.mannschaft.app.schedule.service.GoogleCalendarWebhookService.class)) {
            assertThat(ContextOverrideCensus.isAuthzType(type.getName())).as(type.getName()).isTrue();
        }
        assertThat(ContextOverrideCensus.isAuthzType("com.example.FooGuard")).isTrue();
        assertThat(ContextOverrideCensus.isAuthzType("com.example.FooAuthorizer")).isTrue();
        assertThat(ContextOverrideCensus.isAuthzType("com.example.FooPermissionEvaluator")).isTrue();
        assertThat(ContextOverrideCensus.isAuthzOverride("mock:com.example.Outer$InnerGuard#bean")).isTrue();
        assertThat(ContextOverrideCensus.isAuthzType(R2StorageService.class.getName())).isFalse();
        assertThat(ContextOverrideCensus.isAuthzType("org.springframework.data.redis.core.StringRedisTemplate")).isFalse();
    }

    // ================================================================ 検体（種付けで red）

    @Test
    @DisplayName("AC-1 検体: フィールド・クラス上の @MockitoBean(types)・合成注釈・継承・@Nested の外側の5形式の認可型 override を番人が red にする")
    void 検体_認可型overrideの5形式はredになる() throws IOException {
        for (Class<?> specimen : List.of(FieldForm.class, TypeLevelForm.class, ComposedForm.class,
                InheritedForm.class, OuterForm.Inner.class)) {
            Entry entry = ContextOverrideCensus.analyze(specimen);
            assertThat(entry.status()).as(specimen.getName() + " " + entry.failure()).isEqualTo(Status.OVERRIDES);
            assertThat(entry.authzOverrides()).as(specimen.getName() + " の実効 override に認可型が現れる").isNotEmpty();

            List<String> violations = ContextOverrideCensus.violations(snapshot(), census().with(entry));

            assertThat(violations).as(specimen.getName())
                    .anyMatch(line -> line.startsWith("[認可型 override の追加] " + specimen.getName() + " "));
        }
    }

    @Test
    @DisplayName("AC-1 検体: 既知クラスに認可型 override を1件足すと red、非認可型の追加は認可違反にならない（差分としては red）")
    void 検体_既知クラスへの追加() throws IOException {
        Entry known = census().entries().stream().filter(entry -> entry.status() == Status.OVERRIDES).findFirst()
                .orElseThrow();
        List<String> authz = new java.util.ArrayList<>(known.overrides());
        authz.add("mock:" + AccessControlService.class.getName());
        List<String> plain = new java.util.ArrayList<>(known.overrides());
        plain.add("mock:" + R2StorageService.class.getName() + "#specimenOnly");

        List<String> authzViolations = ContextOverrideCensus.violations(snapshot(),
                census().with(new Entry(known.className(), Status.OVERRIDES, known.key(), sorted(authz), null)));
        List<String> plainViolations = ContextOverrideCensus.violations(snapshot(),
                census().with(new Entry(known.className(), Status.OVERRIDES, known.key(), sorted(plain), null)));

        assertThat(authzViolations).anyMatch(line -> line.startsWith("[認可型 override の追加] " + known.className()));
        assertThat(plainViolations).noneMatch(line -> line.startsWith("[認可型 override の追加]"))
                .anyMatch(line -> line.startsWith("[override の差分] " + known.className()));
    }

    @Test
    @DisplayName("AC-1 検体: 非認可型（外部境界）の override は認可違反にならない")
    void 検体_非認可型は認可違反にならない() {
        Entry entry = ContextOverrideCensus.analyze(PlainForm.class);
        assertThat(entry.status()).isEqualTo(Status.OVERRIDES);
        assertThat(entry.overrides()).containsExactly("mock:" + R2StorageService.class.getName());
        assertThat(entry.authzOverrides()).isEmpty();
    }

    @Test
    @DisplayName("AC-3 検体: 新規の未分類（解析失敗）・既知の対象の解析失敗・対象クラスの脱落を red にし、0 件と非 Spring は違反にしない")
    void 検体_解析失敗と脱落はredになり0件と非Springは違反にならない() throws IOException {
        Entry broken = ContextOverrideCensus.analyze(BrokenForm.class);
        assertThat(broken.status()).isEqualTo(Status.FAILED);
        assertThat(broken.failure()).isNotBlank();
        assertThat(ContextOverrideCensus.violations(snapshot(), census().with(broken)))
                .anyMatch(line -> line.startsWith("[新規の未分類] " + BrokenForm.class.getName()));

        String target = snapshot().rows().values().stream().filter(entry -> entry.status() != Status.FAILED)
                .findFirst().orElseThrow().className();
        Entry targetFailed = new Entry(target, Status.FAILED, null, List.of(), "IllegalStateException: 検体");
        assertThat(ContextOverrideCensus.violations(snapshot(), census().with(targetFailed)))
                .anyMatch(line -> line.startsWith("[既知の対象の解析失敗] " + target));
        assertThat(ContextOverrideCensus.violations(snapshot(), census().without(target)))
                .anyMatch(line -> line.startsWith("[対象クラスの脱落] " + target));
        Entry nonSpringNow = new Entry(target, Status.NON_SPRING, null, List.of(), null);
        assertThat(ContextOverrideCensus.violations(snapshot(), census().with(nonSpringNow)))
                .anyMatch(line -> line.startsWith("[対象クラスの脱落] " + target));

        Entry zero = ContextOverrideCensus.analyze(ZeroForm.class);
        assertThat(zero.status()).isEqualTo(Status.ZERO);
        assertThat(zero.key()).isNotNull();
        Entry nonSpring = ContextOverrideCensus.analyze(NonSpringForm.class);
        assertThat(nonSpring.status()).isEqualTo(Status.NON_SPRING);
        // ZeroForm は既存の構成と別キーになり得るので、key を既存のものに揃えて「0 件そのもの」は違反でないことを見る。
        String existingKey = census().entries().stream().filter(entry -> entry.key() != null).findFirst()
                .orElseThrow().key();
        Census withZero = census().with(new Entry(ZeroForm.class.getName(), Status.ZERO, existingKey, List.of(), null))
                .with(nonSpring);
        assertThat(ContextOverrideCensus.violations(snapshot(), withZero))
                .noneMatch(line -> line.contains(ZeroForm.class.getName()) || line.contains(NonSpringForm.class.getName()));
    }

    @Test
    @DisplayName("AC-4 検体: 新しい構成キーを1つ足すと distinct key 数のラチェットで red になる")
    void 検体_新しい構成キーはラチェットでredになる() throws IOException {
        Census actual = census();
        assertThat(actual.distinctKeys()).isEqualTo(snapshot().distinctKeysBaseline())
                .as("試練時点では基準値＝現在値（A の後は基準値−1 以下を想定）");
        Entry extra = new Entry("com.mannschaft.app.SpecimenNewKeyIT", Status.ZERO, "fffffffffff0", List.of(), null);

        assertThat(ContextOverrideCensus.violations(snapshot(), actual.with(extra)))
                .anyMatch(line -> line.startsWith("[distinct key 数のラチェット]"));
    }

    @Test
    @DisplayName("AC-1 検体: スナップショットの書き出しと読み戻しで行が一致する")
    void 検体_スナップショットの往復() throws IOException {
        Census actual = census();
        Snapshot reparsed = Snapshot.parse(actual.toSnapshot(actual.distinctKeys()));

        assertThat(reparsed.distinctKeysBaseline()).isEqualTo(actual.distinctKeys());
        assertThat(reparsed.authzOverrideBaseline()).isEqualTo(actual.authzOverrideCount());
        assertThat(ContextOverrideCensus.violations(reparsed, actual)).isEmpty();
        assertThat(reparsed.rows().keySet()).isEqualTo(actual.entries().stream()
                .filter(entry -> entry.status() != Status.NON_SPRING).map(Entry::className)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));
    }

    private static List<String> sorted(List<String> values) {
        return values.stream().sorted().collect(Collectors.toList());
    }

    // ---------------------------------------------------------------- 検体クラス（テストメソッドを持たない）

    /** コンテキストを起動しない metadata 専用の空構成。 */
    @Configuration(proxyBeanMethods = false)
    static class EmptyConfiguration {
    }

    /** 形式1: フィールド。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    static class FieldForm {
        @MockitoBean
        AccessControlService accessControlService;
    }

    /** 形式2: クラス上の @MockitoBean(types)。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    @MockitoBean(types = AuthTokenService.class)
    static class TypeLevelForm {
    }

    /** 形式3 の合成注釈。 */
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @MockitoBean(types = AccessGuard.class)
    @interface MockAccessGuard {
    }

    /** 形式3: 合成注釈。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    @MockAccessGuard
    static class ComposedForm {
    }

    /** 形式4 の基底。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    static class InheritedBase {
        @MockitoBean
        ScopeConcealingAccessGate scopeConcealingAccessGate;
    }

    /** 形式4: 継承。 */
    static class InheritedForm extends InheritedBase {
    }

    /** 形式5: @Nested の外側で宣言した override。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    @MockitoBean(types = UserRoleRepository.class)
    static class OuterForm {
        @Nested
        class Inner {
        }
    }

    /** 対照: 非認可型（外部境界）の override。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    static class PlainForm {
        @MockitoBean
        R2StorageService r2StorageService;
    }

    /** override 0 件の Spring クラス。 */
    @SpringJUnitConfig(EmptyConfiguration.class)
    static class ZeroForm {
    }

    /** Spring 非使用。 */
    static class NonSpringForm {
    }

    /** MCC の構築が必ず失敗するローダー。 */
    static class BrokenLoader extends AnnotationConfigContextLoader {
        BrokenLoader() {
            throw new IllegalStateException("検体: ローダー構築失敗");
        }
    }

    /** 解析失敗の検体。 */
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.test.context.junit.jupiter.SpringExtension.class)
    @ContextConfiguration(classes = EmptyConfiguration.class, loader = BrokenLoader.class)
    static class BrokenForm {
    }
}
