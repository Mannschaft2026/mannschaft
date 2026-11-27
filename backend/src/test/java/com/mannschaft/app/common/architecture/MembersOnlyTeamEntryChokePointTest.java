package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.architecture.fixtures.entrygate.EntryGateRogueGuestWriter;
import com.mannschaft.app.common.architecture.fixtures.entrygate.EntryGateRogueSupporterWriter;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CMP-261124-0159 PR-E: 入口の関所の番人（F00 §20.6 {@code MembersOnlyTeamEntryChokePointTest}・F01.2 03「入口の関所」・
 * AC-0159-47 / AC-0159-48）。
 *
 * <h2>何を固定するか</h2>
 * <ol>
 *   <li>{@code memberships} の在籍行（SUPPORTER 在籍を含む）を新しく作れるのは関所 {@code MembershipService#join} だけ。
 *       {@code MembershipEntity} の builder / toBuilder / コンストラクタを他から呼ぶと赤。在籍の {@code role_kind} を
 *       書き換える mutator を {@code MembershipEntity} に足しても赤（変更も関所を通す）。</li>
 *   <li>{@code user_roles} の行（GUEST 付与を含む）を作れるのは、関所 {@code RoleService#assignRole} / {@code #changeRole} と、
 *       付与するロールが固定（ADMIN / MEMBER）または招待ポリシーで GUEST・SUPPORTER を作れないことが決まっている
 *       理由付きの許可リストだけ。リスト外から {@code UserRoleEntity} を組み立てると赤。</li>
 *   <li>関所と各入口が、非公開チームの判定（{@code MembersOnlyTeamEntryGuard}）を実際に呼んでいる。</li>
 *   <li>{@code TeamService#followTeam}（死蔵のフォロー経路）がコードから消えている（AC-0159-47）。</li>
 * </ol>
 *
 * <p>凍結ストア（{@code archunit_store}）は使わない。違反は許可リストの追加（理由付き・検分対象）でしか通らない。
 * 自己検査（{@link SelfCheck}）で、関所を迂回する呼び出しを足すと実際に赤になることを確かめる。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("CMP-261124-0159 入口の関所の番人（AC-0159-47/48）")
class MembersOnlyTeamEntryChokePointTest {

    static final String MEMBERSHIP_ENTITY = "com.mannschaft.app.membership.entity.MembershipEntity";
    static final String USER_ROLE_ENTITY = "com.mannschaft.app.role.entity.UserRoleEntity";
    static final String GUARD = "com.mannschaft.app.common.MembersOnlyTeamEntryGuard";
    static final String TEAM_SERVICE = "com.mannschaft.app.team.service.TeamService";

    /** {@code memberships} の在籍行を作れる関所（{@code FQCN#method} → 理由）。 */
    static final Map<String, String> MEMBERSHIP_CREATORS = Map.of(
            "com.mannschaft.app.membership.service.MembershipService#join",
            "入口の関所。TEAM の SUPPORTER 在籍はチーム行を FOR SHARE で読み、非公開なら 403 TEAM_073");

    /** {@code user_roles} の行を作れるメソッド（{@code FQCN#method} → 理由）。 */
    static final Map<String, String> USER_ROLE_CREATORS = orderedMap(
            "com.mannschaft.app.role.service.RoleService#assignRole",
            "入口の関所。GUEST / SUPPORTER の付与は非公開チームで 403 TEAM_073",
            "com.mannschaft.app.role.service.RoleService#changeRole",
            "入口の関所。GUEST / SUPPORTER への変更は非公開チームで 403 TEAM_073",
            "com.mannschaft.app.role.service.RoleService#transferOwnership",
            "オーナー委譲。作るのは ADMIN（譲受人）と MEMBER（旧オーナー）の固定ロールだけ",
            "com.mannschaft.app.role.service.MembershipGrantService#grant",
            "招待承諾・参加申請の付与。書き先は InviteRolePolicy で ADMIN / DEPUTY_ADMIN / MEMBER だけ（GUEST は 422 ROLE_009、"
                    + "SUPPORTER は user_roles を作らず join の関所を通る）",
            "com.mannschaft.app.role.service.AdminRoleMutationLockService#grantAdminRole",
            "販促プロビジョニングの承諾。ADMIN 固定",
            "com.mannschaft.app.role.service.RoleSuccessionService#promoteToAdmin",
            "ADMIN 後継の選任。ADMIN 固定",
            "com.mannschaft.app.team.service.TeamService#createTeam",
            "チーム作成者への ADMIN 固定の付与",
            "com.mannschaft.app.organization.service.OrganizationService#createOrganization",
            "組織作成者への ADMIN 固定の付与（組織スコープ）");

    /** 非公開チームの判定（関所のロック付き判定）を呼んでいなければならない入口。 */
    static final List<String> GUARDED_ENTRIES = List.of(
            "com.mannschaft.app.membership.service.MembershipService#join",
            "com.mannschaft.app.role.service.RoleService#assignRole",
            "com.mannschaft.app.role.service.RoleService#changeRole",
            "com.mannschaft.app.role.service.MembershipGrantService#grantRole",
            "com.mannschaft.app.supporter.service.SupporterService#approve",
            "com.mannschaft.app.supporter.service.SupporterService#bulkApprove",
            "com.mannschaft.app.supporter.service.SupporterService#follow",
            "com.mannschaft.app.role.service.SharedInviteTokenIssuer#issue",
            "com.mannschaft.app.role.service.MembershipInviteTokenIssuer#issue",
            "com.mannschaft.app.role.service.MembershipInviteTokenIssuer#stageForReissue",
            "com.mannschaft.app.role.service.MembershipInviteTokenIssuer#activateStaged");

    // ───────── 本番 ─────────

    @Test
    @DisplayName("AC-0159-48: memberships の在籍行を作れるのは関所 MembershipService#join だけ")
    void 在籍行は関所だけが作る() {
        Set<String> creators = creatorsOf(ProductionClasses.get(), MEMBERSHIP_ENTITY);
        assertThat(creators).as("在籍行（MembershipEntity）を組み立てるメソッド。関所以外から作らないこと")
                .containsExactlyInAnyOrderElementsOf(MEMBERSHIP_CREATORS.keySet());
    }

    @Test
    @DisplayName("AC-0159-48: 在籍の role_kind を書き換える mutator が MembershipEntity に無い（変更も関所を通す）")
    void roleKindのmutatorが無い() {
        JavaClass entity = imported(MEMBERSHIP_ENTITY);
        List<String> mutators = entity.getMethods().stream()
                // Lombok の @Builder.Default が生成する private static な既定値の供給元（$default$roleKind）は、
                // 新規行の初期値 MEMBER を返すだけで既存の在籍の role_kind を書き換えられないため mutator に数えない。
                .filter(m -> !(m.getModifiers().contains(JavaModifier.STATIC) && m.getName().startsWith("$default$")))
                .map(m -> m.getName())
                .filter(name -> name.toLowerCase().contains("rolekind"))
                .filter(name -> !name.startsWith("get") && !name.startsWith("is"))
                .sorted()
                .toList();
        assertThat(mutators).as("MembershipEntity の role_kind を書き換えるメソッド").isEmpty();
    }

    @Test
    @DisplayName("AC-0159-48: user_roles の行を作れるのは関所と理由付き許可リストだけ（GUEST を迂回で作れない）")
    void ロール行は関所と許可リストだけが作る() {
        Set<String> creators = creatorsOf(ProductionClasses.get(), USER_ROLE_ENTITY);
        assertThat(creators).as("user_roles（UserRoleEntity）を組み立てるメソッド。新しい経路は関所を通すか、理由付きで許可リストへ")
                .containsExactlyInAnyOrderElementsOf(USER_ROLE_CREATORS.keySet());
        assertThat(USER_ROLE_CREATORS.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("AC-0159-48: 関所と各入口が非公開チームの判定（MembersOnlyTeamEntryGuard）を呼ぶ")
    void 関所と入口は判定を呼ぶ() {
        Set<String> callers = callersOf(ProductionClasses.get(), GUARD);
        assertThat(callers).as("MembersOnlyTeamEntryGuard を呼ぶメソッド（入口表の全経路）")
                .containsAll(GUARDED_ENTRIES);
    }

    @Test
    @DisplayName("AC-0159-47: TeamService#followTeam がコードから消えている（呼び出し元も無い）")
    void followTeamが無い() throws ClassNotFoundException {
        Class<?> teamService = Class.forName(TEAM_SERVICE);
        assertThat(Arrays.stream(teamService.getDeclaredMethods()).map(Method::getName))
                .as("TeamService の宣言メソッド").doesNotContain("followTeam");
        List<String> calls = ProductionClasses.get().stream()
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().getName().equals(TEAM_SERVICE))
                .filter(call -> call.getName().equals("followTeam"))
                .map(call -> keyOf(call.getOrigin()))
                .toList();
        assertThat(calls).as("TeamService#followTeam の呼び出し元").isEmpty();
    }

    // ───────── 自己検査（番人が本当に赤くなること） ─────────

    @Nested
    @DisplayName("自己検査")
    class SelfCheck {

        private final JavaClasses fixtures = new ClassFileImporter()
                .importClasses(EntryGateRogueSupporterWriter.class, EntryGateRogueGuestWriter.class);

        @Test
        @DisplayName("関所を迂回して SUPPORTER 在籍の行を組み立てると検出される")
        void 在籍の迂回は赤() {
            assertThat(creatorsOf(fixtures, MEMBERSHIP_ENTITY))
                    .containsExactly(EntryGateRogueSupporterWriter.class.getName() + "#bypass");
        }

        @Test
        @DisplayName("関所を迂回して user_roles の行をラムダの中で組み立てても外側のメソッドとして検出される")
        void ロールの迂回は赤() {
            assertThat(creatorsOf(fixtures, USER_ROLE_ENTITY))
                    .containsExactly(EntryGateRogueGuestWriter.class.getName() + "#bypass");
        }

        @Test
        @DisplayName("迂回するメソッドは判定（MembersOnlyTeamEntryGuard）を呼ぶ側に数えられない")
        void 迂回は判定を呼ばない() {
            assertThat(callersOf(fixtures, GUARD)).isEmpty();
        }
    }

    // ───────── 判定ロジック（自己検査と本番で共用） ─────────

    /** {@code entity} の行を組み立てる（builder / toBuilder / コンストラクタを呼ぶ）メソッドの一覧。entity 自身と builder は除く。 */
    static Set<String> creatorsOf(JavaClasses classes, String entity) {
        Set<String> keys = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.getName().equals(entity) || javaClass.getName().startsWith(entity + "$")) {
                continue;
            }
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(entity)
                        && (call.getName().equals("builder") || call.getName().equals("toBuilder"))) {
                    keys.add(keyOf(call.getOrigin()));
                }
            }
            for (JavaConstructorCall call : javaClass.getConstructorCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(entity)) {
                    keys.add(keyOf(call.getOrigin()));
                }
            }
        }
        return keys;
    }

    /**
     * {@code owner} のメソッドを呼ぶメソッドの一覧。同じクラスの補助メソッド（private ヘルパ等）経由の呼び出しも、
     * 呼び出し元のメソッドとして数える（同一クラス内の推移閉包）。
     */
    static Set<String> callersOf(JavaClasses classes, String owner) {
        Set<String> keys = new TreeSet<>();
        for (JavaClass javaClass : classes) {
            for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                if (call.getTargetOwner().getName().equals(owner)) {
                    keys.add(keyOf(call.getOrigin()));
                }
            }
        }
        boolean grown = true;
        while (grown) {
            grown = false;
            for (JavaClass javaClass : classes) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    if (!call.getTargetOwner().equals(call.getOriginOwner())) {
                        continue;
                    }
                    String target = targetKey(call.getTargetOwner().getName(), call.getName());
                    if (keys.contains(target) && keys.add(keyOf(call.getOrigin()))) {
                        grown = true;
                    }
                }
            }
        }
        return keys;
    }

    private static String targetKey(String ownerName, String methodName) {
        int nested = ownerName.indexOf('$');
        return nested < 0 ? ownerName + "#" + methodName
                : ownerName.substring(0, nested) + "#" + ownerName.substring(nested + 1) + "." + methodName;
    }

    /** {@code 最上位クラスFQCN#メソッド}。ラムダはその外側のメソッド名に正規化する（門の番人と同じ規則）。 */
    static String keyOf(JavaCodeUnit origin) {
        return ScopeContentAccessGateCoverageTest.keyOf(origin);
    }

    private static JavaClass imported(String name) {
        return ProductionClasses.get().stream().filter(c -> c.getName().equals(name)).findFirst()
                .orElseGet(() -> fail("本番クラス集合に " + name + " が無い"));
    }

    private static Map<String, String> orderedMap(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
