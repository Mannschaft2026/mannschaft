package com.mannschaft.app.common.architecture;

import com.mannschaft.app.advertising.campaign.service.AdCampaignDeliveryWorker;
import com.mannschaft.app.common.architecture.fixtures.txselfinvocation.TxSelfInvocationAllowedFixture;
import com.mannschaft.app.common.architecture.fixtures.txselfinvocation.TxSelfInvocationClassLevelFixture;
import com.mannschaft.app.common.architecture.fixtures.txselfinvocation.TxSelfInvocationCollaborator;
import com.mannschaft.app.common.architecture.fixtures.txselfinvocation.TxSelfInvocationSelfProxyFixture;
import com.mannschaft.app.common.architecture.fixtures.txselfinvocation.TxSelfInvocationViolationFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-261009-0010 D5 番人: 同一 bean 内で {@code this} 経由に {@code @Transactional} メソッドを呼ぶ
 * <b>自己呼出し</b>を検出する。
 *
 * <h2>なぜ必要か</h2>
 * <p>Spring の {@code @Transactional} はプロキシ経由の呼び出しにしか効かない。非トランザクションのメソッドから
 * 同一クラスの {@code @Transactional} メソッドを呼ぶと、注釈は<b>黙って無視</b>される。
 * {@code AdCampaignDeliveryWorker#runDelivery} → {@code loadActiveCampaigns()}（{@code SELECT ... FOR UPDATE}）は
 * この形で {@code TransactionRequiredException} になり、毎分の広告配信が止まっていた。repository をモックする
 * 単体テストでは原理的に検出できないため、呼び出しの形そのものを機械的に検査する。</p>
 *
 * <h2>検出条件（{@link #findSelfInvocations} が判定の単一正準）</h2>
 * <ol>
 *   <li>呼び出し元と呼び出し先の所有クラスが同一（{@code this.m()} / 無修飾の {@code m()}。
 *       同じ型の別インスタンスへの呼び出しもバイトコード上は区別できないため含む）</li>
 *   <li>呼び出し先が {@code @Transactional}（Spring / jakarta、メタ注釈を含む）</li>
 *   <li>呼び出し元が {@code @Transactional} でなく、所有クラスにもクラスレベル {@code @Transactional} が無い
 *       （既にトランザクション内にいる呼び出しは「TX が付かない」欠陥に当たらないため対象外。
 *       ラムダ本体内の呼び出しは ArchUnit が外側メソッドに帰属させるため、外側メソッドの注釈で判定される）</li>
 * </ol>
 * <p>自型のフィールド（{@code @Lazy} 自己注入のプロキシ）を持つクラスは、バイトコード上で {@code self.m()} と
 * {@code this.m()} を区別できないため、自己注入で根治済みとみなして除外する。
 * 匿名クラス・内部クラスから外側クラスのメソッドを呼ぶ形は所有クラスが異なるため対象外（いずれも既知の取りこぼし）。</p>
 *
 * <h2>既存違反の凍結（CMP-261009-0010 で一覧化した借金の台帳）</h2>
 * <p>全本番コードの既存違反は {@value #FREEZE_RESOURCE} に<b>クラス単位の件数</b>で凍結する
 * （{@code DateTimeAndZoneGuardTest} と同じ流儀。メソッド名を鍵に含めないので無関係な改名で落ちない）。
 * 新規違反・件数増加は fail、根治で件数が減ったのに台帳が古いまま（陳腐化）も fail（chip-away）。
 * ArchUnit の凍結ストア（{@code archunit_store}）は使わない。<b>新規の自己呼出しを台帳へ追記して通すことは禁止</b>。
 * {@code TransactionTemplate} か別 bean への切り出しで根治すること。</p>
 *
 * <p>試練時点（是正前）で red になるのは {@code AdCampaignDeliveryWorker} を対象にした 2 件
 * （{@link #D5_広告配信workerに自己呼出しが無い} と、凍結台帳に載せていない同クラスが実測に現れる
 * {@link #D5_全本番コードの自己呼出しが凍結台帳と一致する}）。</p>
 */
@DisplayName("CMP-261009-0010 D5 番人: @Transactional の自己呼出し検出")
@Tag(ArchUnitTestTag.ARCHUNIT)
class TransactionalSelfInvocationGuardTest {

    static final String FREEZE_RESOURCE = "tx_self_invocation_guard/self_invocation_freeze.txt";

    private static final String SPRING_TX = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TX = "jakarta.transaction.Transactional";

    /** 検出した自己呼出し 1 件。 */
    record SelfInvocation(String ownerClass, String callerMethod, String targetMethod, int line) {
        @Override
        public String toString() {
            return ownerClass + "#" + callerMethod + " -> " + targetMethod + " (line " + line + ")";
        }
    }

    // ================================================================== 判定の単一正準

    /**
     * 与えられたクラス群から、{@code @Transactional} が効かない自己呼出しを列挙する。
     *
     * @param classes 走査対象
     * @return 検出結果（所有クラス・行番号順）
     */
    static List<SelfInvocation> findSelfInvocations(JavaClasses classes) {
        List<SelfInvocation> result = new ArrayList<>();
        for (JavaClass owner : classes) {
            if (isTransactional(owner) || hasSelfTypedField(owner)) {
                continue;
            }
            for (JavaMethodCall call : owner.getMethodCallsFromSelf()) {
                if (!call.getTargetOwner().equals(owner)) {
                    continue;
                }
                Optional<JavaMethod> resolved = call.getTarget().resolveMember();
                if (resolved.isEmpty() || !isTransactional(resolved.get())) {
                    continue;
                }
                JavaCodeUnit origin = call.getOrigin();
                if (origin.equals(resolved.get()) || isCallerTransactional(origin)) {
                    continue;
                }
                result.add(new SelfInvocation(owner.getName(), origin.getName(),
                        resolved.get().getName(), call.getLineNumber()));
            }
        }
        result.sort(Comparator.comparing(SelfInvocation::ownerClass)
                .thenComparingInt(SelfInvocation::line)
                .thenComparing(SelfInvocation::callerMethod));
        return result;
    }

    /** 自型のフィールド（自己注入したプロキシ）を持つクラスは、そのフィールド経由の呼び出しと区別できないため除外する。 */
    private static boolean hasSelfTypedField(JavaClass owner) {
        return owner.getFields().stream().anyMatch(f -> f.getRawType().equals(owner));
    }

    private static boolean isCallerTransactional(JavaCodeUnit origin) {
        // ArchUnit はラムダ本体内の呼び出しを外側メソッドの呼び出しとして帰属させるため、
        // 外側メソッドの注釈だけで判定できる（検体 TxSelfInvocationViolationFixture#runLater で実証）。
        return isTransactional(origin);
    }

    private static boolean isTransactional(CanBeAnnotated element) {
        return element.isAnnotatedWith(SPRING_TX) || element.isMetaAnnotatedWith(SPRING_TX)
                || element.isAnnotatedWith(JAKARTA_TX) || element.isMetaAnnotatedWith(JAKARTA_TX);
    }

    /** 検出結果をクラス単位の件数へ畳む。 */
    static Map<String, Integer> countByClass(List<SelfInvocation> found) {
        return found.stream().collect(Collectors.toMap(
                SelfInvocation::ownerClass, s -> 1, Integer::sum, TreeMap::new));
    }

    /** 実測と凍結台帳の差分（空なら一致）。 */
    static List<String> freezeMismatches(Map<String, Integer> actual, Map<String, Integer> frozen) {
        List<String> mismatches = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(actual.keySet());
        keys.addAll(frozen.keySet());
        for (String key : keys) {
            int a = actual.getOrDefault(key, 0);
            int f = frozen.getOrDefault(key, 0);
            if (a > f) {
                mismatches.add("新規違反: %s の自己呼出しが台帳%d件 → 実測%d件（TransactionTemplate か別 bean で根治する。台帳への追記は禁止）"
                        .formatted(key, f, a));
            } else if (a < f) {
                mismatches.add("陳腐化: %s は台帳%d件だが実測%d件（根治済みなら台帳を減らす。chip-away）"
                        .formatted(key, f, a));
            }
        }
        return mismatches;
    }

    private static Map<String, Integer> readFreeze() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (InputStream in = TransactionalSelfInvocationGuardTest.class.getClassLoader()
                .getResourceAsStream(FREEZE_RESOURCE)) {
            assertThat(in).as("凍結台帳 %s が無い", FREEZE_RESOURCE).isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\|");
                if (parts.length != 2) {
                    throw new IllegalStateException("凍結台帳の行形式は <FQCN>|<件数>: " + trimmed);
                }
                counts.merge(parts[0], Integer.parseInt(parts[1]), Integer::sum);
            }
        }
        return counts;
    }

    // ================================================================== 番人本体

    @Test
    @DisplayName("D5: AdCampaignDeliveryWorker に @Transactional の自己呼出しが無い（是正前は runDelivery -> loadActiveCampaigns で red）")
    void D5_広告配信workerに自己呼出しが無い() {
        JavaClasses classes = new ClassFileImporter().importClasses(AdCampaignDeliveryWorker.class);

        assertThat(findSelfInvocations(classes))
                .as("同一 bean 内の this 経由の @Transactional 呼び出しはプロキシを通らず TX が付かない")
                .isEmpty();
    }

    @Test
    @DisplayName("D5: 全本番コードの @Transactional 自己呼出しがクラス単位で凍結台帳と一致する（新規流入禁止・chip-away）")
    void D5_全本番コードの自己呼出しが凍結台帳と一致する() throws IOException {
        List<SelfInvocation> found = findSelfInvocations(ProductionClasses.get());
        System.out.println("[tx-self-invocation] 実測 " + found.size() + " 件 / "
                + countByClass(found).size() + " クラス");
        found.forEach(s -> System.out.println("[tx-self-invocation] " + s));

        assertThat(freezeMismatches(countByClass(found), readFreeze()))
                .as("凍結台帳 %s との差分", FREEZE_RESOURCE)
                .isEmpty();
    }

    // ================================================================== 判定ロジックの自己証明（検体）

    @Test
    @DisplayName("D5 検体: 非TXメソッド・非TXラムダからの自己呼出しを検出する（偽陰性ゼロ）")
    void D5_検体_負例を検出する() {
        JavaClasses classes = new ClassFileImporter().importClasses(TxSelfInvocationViolationFixture.class);

        List<SelfInvocation> found = findSelfInvocations(classes);

        assertThat(found).extracting(SelfInvocation::targetMethod).containsOnly("loadForUpdate");
        // ラムダ本体内の呼び出しは外側メソッド runLater に帰属する
        assertThat(found).extracting(SelfInvocation::callerMethod).containsExactlyInAnyOrder("run", "runLater");
    }

    @Test
    @DisplayName("D5 検体: TX内呼出し・TXメソッド内ラムダ・別bean呼出し・クラスレベルTX・自己注入プロキシ経由は検出しない（偽陽性ゼロ）")
    void D5_検体_正例は検出しない() {
        JavaClasses classes = new ClassFileImporter().importClasses(
                TxSelfInvocationAllowedFixture.class,
                TxSelfInvocationCollaborator.class,
                TxSelfInvocationClassLevelFixture.class,
                TxSelfInvocationSelfProxyFixture.class);

        assertThat(findSelfInvocations(classes)).isEmpty();
    }

    @Test
    @DisplayName("D5 検体: 台帳照合は新規違反と陳腐化の両方を差分として返す")
    void D5_検体_台帳照合の差分() {
        List<String> mismatches = freezeMismatches(
                Map.of("a.New", 1, "a.Same", 2, "a.Shrunk", 1),
                Map.of("a.Same", 2, "a.Shrunk", 3, "a.Gone", 1));

        assertThat(mismatches).hasSize(3);
        assertThat(mismatches).anyMatch(m -> m.startsWith("新規違反: a.New"));
        assertThat(mismatches).anyMatch(m -> m.startsWith("陳腐化: a.Shrunk"));
        assertThat(mismatches).anyMatch(m -> m.startsWith("陳腐化: a.Gone"));
    }
}
