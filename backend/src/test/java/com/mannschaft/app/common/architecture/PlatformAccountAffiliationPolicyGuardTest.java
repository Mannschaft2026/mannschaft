package com.mannschaft.app.common.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 番人 G4（戦役 Z-A PR2 / 受け入れ条件 P6）: 所属行を作る保存経路は、すべて
 * {@code PlatformAccountAffiliationPolicy} を通る（運営アカウントのテナント所属禁止）。
 *
 * <h2>何を固定するか</h2>
 * <p>{@code src/main/java} を走査し、所属行（memberships / user_roles のスコープ行 / committee_members /
 * village_memberships / サポーター申請）と、利用者宛ての招待・参加申請・移管申し出の行を作る
 * 「生成シンク」（{@link #SINK}）を数える。シンクを持つファイルは、次のどちらかでなければならない。</p>
 * <ol>
 *   <li>{@code PlatformAccountAffiliationPolicy} の判定（{@code .assertAffiliatable(} または
 *       {@code .isPlatformAccount(}）を呼んでいる。</li>
 *   <li>除外表 {@link #EXEMPTIONS_FILE} に理由付きで載っている（利用者の所属を作らない更新・整理、
 *       主体が利用者でない加盟など）。</li>
 * </ol>
 *
 * <h2>基準線方式（G1 と同じ流儀）</h2>
 * <p>シンクの件数をファイル単位で {@link #BASELINE_FILE}（「パス&lt;TAB&gt;件数」）に記録する。
 * 行番号のずれでは壊れない。件数が増えた・基準線に無いファイルにシンクが現れた場合は赤
 * （新しい保存経路を足したら、Policy を通したことを確かめてから基準線を更新する）。
 * 減ったのに基準線を下げ忘れた場合も赤（基準線を実態に合わせ続けるため）。</p>
 *
 * <p>本テストはソースを読むだけで、いかなるファイルも書き換えない。</p>
 */
class PlatformAccountAffiliationPolicyGuardTest {

    private static final Path MAIN_ROOT = Paths.get("src", "main", "java");
    private static final Path BASELINE_FILE =
            Paths.get("src", "test", "resources", "platform_account_affiliation", "g4_sink_baseline.txt");
    private static final Path EXEMPTIONS_FILE =
            Paths.get("src", "test", "resources", "platform_account_affiliation", "g4_exemptions.txt");

    /** 所属行・宛先付き申込行を作る生成シンク（コメント・文字列リテラルを伏せたソースへ適用する）。 */
    static final Pattern SINK = Pattern.compile(
            "\\b(?:UserRoleEntity|MembershipEntity|CommitteeMemberEntity|VillageMembershipEntity"
                    + "|SupporterApplicationEntity|JoinRequestEntity|VillageJoinRequestEntity"
                    + "|CommitteeInvitationEntity|VillageInvitationEntity|OwnershipTransferOfferEntity"
                    + "|VillageCreationRequestEntity|InviteTokenEntity|FollowEntity)\\s*\\.\\s*builder\\s*\\("
                    + "|\\bnew\\s+(?:VillageMembershipEntity|VillageInvitationEntity|VillageJoinRequestEntity"
                    + "|VillageCreationRequestEntity|CommitteeInvitationEntity|JoinRequestEntity"
                    + "|OwnershipTransferOfferEntity|SupporterApplicationEntity|CommitteeMemberEntity)\\s*\\("
                    + "|\\b(?:membershipRepository|userRoleRepository|committeeMemberRepository)"
                    + "\\s*\\.\\s*(?:save|saveAndFlush|saveAll)\\s*\\(");

    /** Policy の判定呼び出し。 */
    static final Pattern POLICY_CALL = Pattern.compile("\\.\\s*(?:assertAffiliatable|isPlatformAccount)\\s*\\(");

    /** Policy 本体（判定を定義する側。走査対象から外す）。 */
    private static final String POLICY_SELF = "com/mannschaft/app/role/service/PlatformAccountAffiliationPolicy.java";

    static int countSinks(String source) {
        Matcher m = SINK.matcher(JavaSourceScanningUtils.maskCommentsAndLiterals(source));
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static boolean callsPolicy(String source) {
        String masked = JavaSourceScanningUtils.maskCommentsAndLiterals(source);
        return masked.contains("PlatformAccountAffiliationPolicy") && POLICY_CALL.matcher(masked).find();
    }

    private static Map<String, Integer> scanSinks() throws IOException {
        assertTrue(Files.isDirectory(MAIN_ROOT),
                "本番ソースルートが見つからない: " + MAIN_ROOT.toAbsolutePath());
        Map<String, Integer> sinks = new TreeMap<>();
        try (Stream<Path> stream = Files.walk(MAIN_ROOT)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith(".java")).toList()) {
                String rel = MAIN_ROOT.relativize(p).toString().replace('\\', '/');
                if (rel.equals(POLICY_SELF)) {
                    continue;
                }
                int n = countSinks(Files.readString(p, StandardCharsets.UTF_8));
                if (n > 0) {
                    sinks.put(rel, n);
                }
            }
        }
        return sinks;
    }

    private static Map<String, String> readTsv(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "基準線ファイルが無い: " + file.toAbsolutePath());
        Map<String, String> out = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] cols = t.split("\t", 2);
            out.put(cols[0].strip(), cols.length > 1 ? cols[1].strip() : "");
        }
        return out;
    }

    @Test
    @DisplayName("P6: 所属行を作る保存経路を持つファイルは、すべて PlatformAccountAffiliationPolicy を呼ぶ（除外表を除く）")
    void P6_所属行の生成経路はすべてPolicyを通る() throws IOException {
        Map<String, Integer> sinks = scanSinks();
        Map<String, String> exemptions = readTsv(EXEMPTIONS_FILE);
        List<String> violations = new ArrayList<>();
        for (String rel : sinks.keySet()) {
            if (exemptions.containsKey(rel)) {
                continue;
            }
            if (!callsPolicy(Files.readString(MAIN_ROOT.resolve(rel), StandardCharsets.UTF_8))) {
                violations.add(rel + "（生成シンク " + sinks.get(rel) + " 件）");
            }
        }
        if (!violations.isEmpty()) {
            fail("所属行・宛先付き申込行を作るのに PlatformAccountAffiliationPolicy を通していないファイルがあります。"
                    + "運営アカウント（SYSTEM_ADMIN）はテナントに所属できません（戦役 Z-A PR2）。"
                    + "保存の前に assertAffiliatable（候補選出なら isPlatformAccount）を呼んでください。\n  ✗ "
                    + String.join("\n  ✗ ", violations));
        }
    }

    @Test
    @DisplayName("P6: 除外表の各行は理由付きで、実在しシンクを持ち、Policy を呼ばないファイルに限る（腐った除外を残さない）")
    void P6_除外表は理由付きで腐っていない() throws IOException {
        Map<String, Integer> sinks = scanSinks();
        Map<String, String> exemptions = readTsv(EXEMPTIONS_FILE);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : exemptions.entrySet()) {
            if (e.getValue().isBlank()) {
                problems.add(e.getKey() + ": 理由が空");
            }
            if (!sinks.containsKey(e.getKey())) {
                problems.add(e.getKey() + ": 生成シンクが無い（除外が不要になった）");
            } else if (callsPolicy(Files.readString(MAIN_ROOT.resolve(e.getKey()), StandardCharsets.UTF_8))) {
                problems.add(e.getKey() + ": Policy を呼んでいる（除外が不要になった）");
            }
        }
        assertThat(problems).as("G4 除外表の不備").isEmpty();
    }

    @Test
    @DisplayName("P6: 生成シンクの件数は基準線と一致する（増えたら Policy を確かめて上げる・減ったら下げる）")
    void P6_生成シンクの件数は基準線と一致する() throws IOException {
        Map<String, Integer> actual = scanSinks();
        Map<String, Integer> baseline = new TreeMap<>();
        readTsv(BASELINE_FILE).forEach((k, v) -> baseline.put(k, Integer.parseInt(v)));
        if (actual.equals(baseline)) {
            return;
        }
        StringBuilder sb = new StringBuilder(
                "所属行の生成シンクが基準線と一致しません。新しい保存経路を足した場合は Policy を通したことを"
                        + "確かめてから基準線を更新してください。\n差分:\n");
        for (String k : new java.util.TreeSet<String>() {{ addAll(actual.keySet()); addAll(baseline.keySet()); }}) {
            Integer a = actual.get(k);
            Integer b = baseline.get(k);
            if (!java.util.Objects.equals(a, b)) {
                sb.append("  ").append(k).append(": 基準線=").append(b).append(" 実測=").append(a).append('\n');
            }
        }
        sb.append("実測の基準線（貼り替え用）:\n");
        actual.forEach((k, v) -> sb.append(k).append('\t').append(v).append('\n'));
        fail(sb.toString());
    }

    @Test
    @DisplayName("P6 自己検証: シンクと Policy 呼び出しを検出し、コメント・文字列内や無関係な保存は誤検出しない")
    void P6_検出器の自己検証() {
        String bypass = """
                class X {
                  void join(Long uid) {
                    membershipRepository.save(MembershipEntity.builder().userId(uid).build());
                  }
                }
                """;
        assertThat(countSinks(bypass)).as("memberships の save と builder を数える").isEqualTo(2);
        assertThat(callsPolicy(bypass)).as("Policy を呼ばない経路は検出する").isFalse();

        String guarded = """
                class X {
                  private final PlatformAccountAffiliationPolicy platformAccountAffiliationPolicy;
                  void join(Long uid) {
                    platformAccountAffiliationPolicy.assertAffiliatable(uid);
                    VillageMembershipEntity m = new VillageMembershipEntity();
                    userRoleRepository.saveAndFlush(UserRoleEntity.builder().build());
                  }
                }
                """;
        assertThat(countSinks(guarded)).isEqualTo(3);
        assertThat(callsPolicy(guarded)).as("Policy を通る経路は許す").isTrue();

        String commentOnly = """
                class X {
                  // platformAccountAffiliationPolicy.assertAffiliatable(uid); PlatformAccountAffiliationPolicy
                  String s = "membershipRepository.save(";
                  void x() { committeeMemberRepository.save(e); }
                }
                """;
        assertThat(countSinks(commentOnly)).as("文字列内のシンクは数えない").isEqualTo(1);
        assertThat(callsPolicy(commentOnly)).as("コメント内の Policy 呼び出しは通過とみなさない").isFalse();

        String unrelated = """
                class X {
                  void x() {
                    chatMemberRepository.save(e);
                    TeamOrgMembershipEntity.builder().build();
                    memberRepository.saveAll(list);
                  }
                }
                """;
        assertThat(countSinks(unrelated)).as("所属行でない保存（チャット・チーム加盟）は数えない").isZero();
    }
}
