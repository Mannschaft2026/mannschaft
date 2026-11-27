package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 戦役 Z-A PR4（試練）: {@link UserScopeRoleSnapshot} から SYSTEM_ADMIN の短絡を撤去する受け入れテスト（AC5）。
 *
 * <p>AC5: {@code forSystemAdmin()} は存在しない。snapshot の 6 メソッド（isMemberOf・hasRoleOrAbove・
 * isMemberOfParentOrg・hasParentOrgRoleOrAbove・isDescendantMemberOf・hasDescendantRoleOrAbove）は
 * {@code systemAdmin} を参照せず、所属の無い SYSTEM_ADMIN（運営アカウント）ではすべて false になる。</p>
 *
 * <p>レコードの {@code systemAdmin} 項目は残す（直書きの拒否型が混ざるため、z-a-design.md §1-4）。
 * 値は残すが、判定には使わない。</p>
 */
@DisplayName("Z-A PR4: UserScopeRoleSnapshot の SYSTEM_ADMIN 短絡撤去（AC5）")
class UserScopeRoleSnapshotSysAdminBypassRemovalTest {

    private static final ScopeKey TEAM = new ScopeKey("TEAM", 1L);
    private static final ScopeKey ORG = new ScopeKey("ORGANIZATION", 10L);
    private static final ScopeKey OTHER_TEAM = new ScopeKey("TEAM", 2L);

    /** 所属の無い運営アカウントの snapshot（PR4 後に MembershipBatchQueryService が返す形）。 */
    private static UserScopeRoleSnapshot platformAccountWithoutAffiliation() {
        return new UserScopeRoleSnapshot(
                true, Map.of(), Map.of(TEAM, Set.of(10L)), Set.of(), Set.of(), Set.of(), Map.of(), Map.of());
    }

    @Nested
    @DisplayName("AC5: forSystemAdmin() の廃止（番人）")
    class ForSystemAdminRemoved {

        @Test
        @DisplayName("UserScopeRoleSnapshot に forSystemAdmin という static メソッドが無い")
        void forSystemAdmin_doesNotExist() {
            List<String> names = Arrays.stream(UserScopeRoleSnapshot.class.getDeclaredMethods())
                    .map(Method::getName)
                    .toList();
            assertThat(names).doesNotContain("forSystemAdmin");
        }

        @Test
        @DisplayName("backend/src/main のどこからも forSystemAdmin を参照しない")
        void noReferenceInMainSources() throws IOException {
            List<String> hits = new ArrayList<>();
            try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String src = com.mannschaft.app.common.architecture.JavaSourceScanningUtils
                            .maskCommentsAndLiterals(Files.readString(f, StandardCharsets.UTF_8));
                    if (src.contains("forSystemAdmin")) {
                        hits.add(f.toString());
                    }
                }
            }
            assertThat(hits).as("forSystemAdmin の参照が残っている").isEmpty();
        }

        @Test
        @DisplayName("UserScopeRoleSnapshot.java の本体に `if (systemAdmin)` の短絡が無い")
        void noSystemAdminShortCircuitInSnapshotSource() throws IOException {
            String src = com.mannschaft.app.common.architecture.JavaSourceScanningUtils.maskCommentsAndLiterals(
                    Files.readString(Path.of(
                            "src/main/java/com/mannschaft/app/common/visibility/UserScopeRoleSnapshot.java"),
                            StandardCharsets.UTF_8));
            assertThat(src.replaceAll("\\s+", "")).doesNotContain("if(systemAdmin)");
        }

        @Test
        @DisplayName("レコードの systemAdmin 項目は残す（直書きの拒否型のため・値は正しく計算し続ける）")
        void systemAdminComponentIsKept() {
            List<String> components = Arrays.stream(UserScopeRoleSnapshot.class.getRecordComponents())
                    .map(RecordComponent::getName)
                    .toList();
            assertThat(components).contains("systemAdmin");
            assertThat(platformAccountWithoutAffiliation().isSystemAdmin()).isTrue();
        }
    }

    @Nested
    @DisplayName("AC5: 所属の無い SYSTEM_ADMIN の snapshot で 6 メソッドがすべて false")
    class PlatformAccountWithoutAffiliation {

        @Test
        @DisplayName("isMemberOf は false")
        void isMemberOf_false() {
            assertThat(platformAccountWithoutAffiliation().isMemberOf(TEAM)).isFalse();
        }

        @Test
        @DisplayName("hasRoleOrAbove は SUPPORTER でも false")
        void hasRoleOrAbove_false() {
            assertThat(platformAccountWithoutAffiliation().hasRoleOrAbove(TEAM, "SUPPORTER")).isFalse();
        }

        @Test
        @DisplayName("hasRoleOrAbove は scope=null でも false")
        void hasRoleOrAbove_nullScope_false() {
            assertThat(platformAccountWithoutAffiliation().hasRoleOrAbove(null, "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("isMemberOfParentOrg は false")
        void isMemberOfParentOrg_false() {
            assertThat(platformAccountWithoutAffiliation().isMemberOfParentOrg(TEAM)).isFalse();
        }

        @Test
        @DisplayName("hasParentOrgRoleOrAbove は false")
        void hasParentOrgRoleOrAbove_false() {
            assertThat(platformAccountWithoutAffiliation().hasParentOrgRoleOrAbove(TEAM, "SUPPORTER")).isFalse();
        }

        @Test
        @DisplayName("isDescendantMemberOf は false")
        void isDescendantMemberOf_false() {
            assertThat(platformAccountWithoutAffiliation().isDescendantMemberOf(ORG)).isFalse();
        }

        @Test
        @DisplayName("hasDescendantRoleOrAbove は false")
        void hasDescendantRoleOrAbove_false() {
            assertThat(platformAccountWithoutAffiliation().hasDescendantRoleOrAbove(ORG, "SUPPORTER")).isFalse();
        }

        @Test
        @DisplayName("resolveVisibleLevels は PUBLIC だけ（SQL 述語で他テナントの行を取れない）")
        void resolveVisibleLevels_publicOnly() {
            MembershipBatchQueryService service = new MembershipBatchQueryService(
                    null, null, null, null, null);
            assertThat(service.resolveVisibleLevels(TEAM, platformAccountWithoutAffiliation()))
                    .containsExactly(StandardVisibility.PUBLIC);
        }
    }

    @Nested
    @DisplayName("systemAdmin=true でも実ロールだけで判定する（値は判定に使わない）")
    class RealRoleOnly {

        private final UserScopeRoleSnapshot sysAdminWithMemberRole = new UserScopeRoleSnapshot(
                true, Map.of(TEAM, "MEMBER"), Map.of(TEAM, Set.of(10L)),
                Set.of(new ScopeKey("ORGANIZATION", 10L)), Set.of(),
                Set.of(10L), Map.of(new ScopeKey("ORGANIZATION", 10L), "SUPPORTER"), Map.of(10L, "SUPPORTER"));

        @Test
        @DisplayName("MEMBER の所属があるスコープでは MEMBER として判定し、ADMIN 以上は false")
        void hasRoleOrAbove_usesRealRole() {
            assertThat(sysAdminWithMemberRole.hasRoleOrAbove(TEAM, "MEMBER")).isTrue();
            assertThat(sysAdminWithMemberRole.hasRoleOrAbove(TEAM, "DEPUTY_ADMIN")).isFalse();
            assertThat(sysAdminWithMemberRole.hasRoleOrAbove(TEAM, "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("所属の無い別スコープでは isMemberOf が false")
        void isMemberOf_otherScope_false() {
            assertThat(sysAdminWithMemberRole.isMemberOf(TEAM)).isTrue();
            assertThat(sysAdminWithMemberRole.isMemberOf(OTHER_TEAM)).isFalse();
        }

        @Test
        @DisplayName("親 ORG・配下ツリーのロールも実ロール（SUPPORTER）で評価する")
        void parentAndDescendant_useRealRole() {
            assertThat(sysAdminWithMemberRole.isMemberOfParentOrg(TEAM)).isTrue();
            assertThat(sysAdminWithMemberRole.hasParentOrgRoleOrAbove(TEAM, "SUPPORTER")).isTrue();
            assertThat(sysAdminWithMemberRole.hasParentOrgRoleOrAbove(TEAM, "MEMBER")).isFalse();
            assertThat(sysAdminWithMemberRole.isDescendantMemberOf(ORG)).isTrue();
            assertThat(sysAdminWithMemberRole.hasDescendantRoleOrAbove(ORG, "SUPPORTER")).isTrue();
            assertThat(sysAdminWithMemberRole.hasDescendantRoleOrAbove(ORG, "MEMBER")).isFalse();
        }
    }

    @Nested
    @DisplayName("回帰: 一般利用者の判定は撤去前と同じ")
    class GeneralUserRegression {

        @Test
        @DisplayName("ADMIN は全段 true、他スコープは false")
        void admin() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false, Map.of(TEAM, "ADMIN"), Map.of(), Set.of(), Set.of());
            assertThat(s.isMemberOf(TEAM)).isTrue();
            assertThat(s.hasRoleOrAbove(TEAM, "ADMIN")).isTrue();
            assertThat(s.hasRoleOrAbove(OTHER_TEAM, "SUPPORTER")).isFalse();
        }

        @Test
        @DisplayName("SUPPORTER は SUPPORTER 段まで")
        void supporter() {
            UserScopeRoleSnapshot s = new UserScopeRoleSnapshot(
                    false, Map.of(TEAM, "SUPPORTER"), Map.of(), Set.of(), Set.of());
            assertThat(s.hasRoleOrAbove(TEAM, "SUPPORTER")).isTrue();
            assertThat(s.hasRoleOrAbove(TEAM, "MEMBER")).isFalse();
        }

        @Test
        @DisplayName("匿名（empty）は全段 false")
        void anonymous() {
            UserScopeRoleSnapshot s = UserScopeRoleSnapshot.empty();
            assertThat(s.isMemberOf(TEAM)).isFalse();
            assertThat(s.hasRoleOrAbove(TEAM, "SUPPORTER")).isFalse();
            assertThat(s.isMemberOfParentOrg(TEAM)).isFalse();
            assertThat(s.isDescendantMemberOf(ORG)).isFalse();
        }
    }
}
