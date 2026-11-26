package com.mannschaft.app.support.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 番人: テストコードが {@link RoleSeedSupport} を介さず {@code roles} 表へ書き込むことを禁止する（CMP-261126-0906）。
 *
 * <p>roles は commit されるとクラスをまたいで残り、誤った priority（例: MEMBER=5・ADMIN=100）を持つ行が
 * 同じ JVM の後続 IT の認可判定（{@code AccessControlService.hasRoleOrAbove}）を壊す。個々のクラスが
 * priority の数値リテラルを書く方式では漏れるため、「書き込みは必ず {@link RoleSeedSupport}（seed と同じ
 * 正準 priority で upsert し、既存の誤った行も直す）を通す」ことを機械的に強制する。</p>
 *
 * <p>検出対象は {@code INSERT INTO roles} / {@code INSERT IGNORE INTO roles} / {@code REPLACE INTO roles} /
 * {@code UPDATE roles}（Java の文字列連結・テキストブロック・複数行・バッククォートを含む）。
 * {@code *.java} / {@code *.sql} を {@code src/test} 配下で走査する。</p>
 *
 * <p>既存の直書きのうち未移行のものは {@link #KNOWN_VIOLATIONS} に理由付きで登録する。登録は負債であり、
 * 移行（{@link RoleSeedSupport#ensureCanonical} 呼び出しへの置換）したら必ず外すこと
 * （外し忘れは {@link #knownViolationsAreStillViolating()} が検出する）。</p>
 */
@DisplayName("roles 書き込み 番人（RoleSeedSupport 経由のみ許可）")
class RolesPriorityLiteralGuardTest {

    private static final Path TEST_ROOT = Paths.get("src", "test");

    /** helper 自身と、検出パターンを文字列として含むこの番人は走査から除く。 */
    private static final List<String> EXEMPT_SUFFIXES = List.of(
            "com/mannschaft/app/support/test/RoleSeedSupport.java",
            "com/mannschaft/app/support/test/RolesPriorityLiteralGuardTest.java");

    private static final Pattern ROLES_WRITE = Pattern.compile(
            "\\b(?:INSERT\\s+(?:IGNORE\\s+)?INTO|REPLACE\\s+INTO|UPDATE)\\s+`?roles`?(?![\\w`])",
            Pattern.CASE_INSENSITIVE);

    private static final String R_ROLLBACK =
            "クラス @Transactional でロールバックされ roles は残らない（未移行: priority は呼び出し側指定）";
    private static final String R_POLLUTE =
            "意図的: commit 済みの誤った priority 行（汚染）を再現する回帰テスト。後で ensureCanonical で直して検証する";

    /**
     * 未移行の roles 直書き。キーは {@code src/test} からの相対パス（区切りは /）。
     * 移行したら必ず削除する。
     */
    private static final Map<String, String> KNOWN_VIOLATIONS = knownViolations();

    private static Map<String, String> knownViolations() {
        Map<String, String> m = new TreeMap<>();
        // KNOWN_VIOLATIONS_BEGIN
        m.put("java/com/mannschaft/app/actionmemo/ActionMemoScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/advertising/operational/AdvertiserAdCreativeIdorIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/advertising/operational/OperationalAdCampaignAuditLogIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/advertising/operational/OperationalAdCampaignCrudIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/advertising/operational/OperationalAdCampaignReviewContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/advertising/operational/TeamAdvertiserAdCreativeIdorIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/advertising/operational/TeamAdvertiserScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/bulletin/BulletinScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/bulletin/visibility/BulletinThreadVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/chat/visibility/ChatMessageVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/circulation/visibility/CirculationCommentVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/circulation/visibility/CirculationDocumentVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/cms/CmsBlogPostWriteScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/cms/CmsSeriesTagScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/common/AdminOrPermissionInScopeIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/common/visibility/MembershipBatchQueryServiceIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/dashboard/ActivityFeedVisibilityIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/digest/DigestScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/directmail/DirectMailScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/event/visibility/EventVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/family/FamilyScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/filesharing/FileSharingAuthzScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/filesharing/visibility/FileAttachmentVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/gallery/GalleryScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/gallery/PhotoAlbumListPhotosScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/gallery/visibility/PhotoAlbumVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/incident/IncidentScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/jobmatching/repository/JobPostingRepositoryVisibilityInTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/jobmatching/visibility/JobPostingVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/line/LineScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/member/service/MemberSubtabVisibilityPermissionSeedIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/notification/confirmable/SendNotificationPermissionCatalogIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/notification/confirmable/controller/ConfirmableNotificationSendContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/notification/confirmable/controller/ConfirmableRecipientGroupAndSendAuthzIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/organization/OrganizationSupporterScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/organization/service/OrganizationMembersN1IT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/organization/visibility/OrganizationVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/parking/ParkingScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/payment/EscrowPaymentViewScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/payment/MemberPaymentAuthzIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/payment/PaymentBeneficiaryMemberOnlyIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/payment/PaymentW6TeamScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/property/PropertyScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/provisioning/ProvisioningAcceptanceIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/queue/QueueScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/quickmemo/QuickMemoTagScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/recruitment/visibility/RecruitmentListingVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/role/repository/UserRoleRepositoryF00ExtensionTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/schedule/visibility/ScheduleVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/signage/SignageScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/social/service/TeamFriendFollowSlugIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/succession/controller/SuccessionAuthzContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/support/test/RoleSeedSupportIT.java",
                R_POLLUTE);
        m.put("java/com/mannschaft/app/supporter/SupporterScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/survey/SurveyManageSurveysAuthzIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/survey/visibility/SurveyVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/team/batch/TeamMemberCountBackfillBatchServiceIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/team/service/TeamSearchN1IT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/team/visibility/TeamVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/ticket/TicketScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/tournament/TournamentPdfScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/tournament/TournamentScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/tournament/entry/TournamentEntryScopeContractIT.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/tournament/visibility/TournamentVisibilityResolverIntegrationTest.java",
                R_ROLLBACK);
        m.put("java/com/mannschaft/app/webhook/WebhookAuthzContractIT.java",
                R_ROLLBACK);
        // KNOWN_VIOLATIONS_END
        return m;
    }

    /** ソース全文に helper 以外の roles 書き込みが含まれるか（文字列連結・改行を畳んで判定）。 */
    static boolean writesRolesDirectly(String source) {
        String joined = source.replaceAll("\"\\s*\\+\\s*\"", "");
        String collapsed = joined.replaceAll("\\s+", " ");
        return ROLES_WRITE.matcher(collapsed).find();
    }

    private static TreeSet<String> scanViolations() throws IOException {
        TreeSet<String> violations = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            List<Path> targets = files.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".sql"))
                    .toList();
            for (Path p : targets) {
                String rel = TEST_ROOT.relativize(p).toString().replace('\\', '/');
                if (EXEMPT_SUFFIXES.stream().anyMatch(rel::endsWith)) {
                    continue;
                }
                if (writesRolesDirectly(Files.readString(p, StandardCharsets.UTF_8))) {
                    violations.add(rel);
                }
            }
        }
        return violations;
    }

    @Test
    @DisplayName("RoleSeedSupport 以外から roles へ書き込まない（登録済みの既知違反を除く）")
    void noNewDirectRolesWrites() throws IOException {
        List<String> unexpected = new ArrayList<>(scanViolations());
        unexpected.removeAll(KNOWN_VIOLATIONS.keySet());
        assertThat(unexpected)
                .as("roles へ直接書き込むテストは禁止。RoleSeedSupport.ensureCanonical(em|jdbc, name) を使うこと"
                        + "（誤った priority を commit すると後続 IT の hasRoleOrAbove が崩れる。CMP-261126-0906）")
                .isEmpty();
    }

    @Test
    @DisplayName("KNOWN_VIOLATIONS の登録は存在し今も違反している（陳腐化の防止）")
    void knownViolationsAreStillViolating() throws IOException {
        TreeSet<String> actual = scanViolations();
        List<String> stale = new ArrayList<>();
        for (String known : KNOWN_VIOLATIONS.keySet()) {
            if (!Files.isRegularFile(TEST_ROOT.resolve(known)) || !actual.contains(known)) {
                stale.add(known);
            }
        }
        assertThat(stale)
                .as("移行済み・削除済みのファイルは KNOWN_VIOLATIONS から外すこと")
                .isEmpty();
        assertThat(KNOWN_VIOLATIONS.values()).as("登録には理由が必須").allSatisfy(r -> assertThat(r).isNotBlank());
    }

    @Test
    @DisplayName("検出器の自己検証: 書き込みを検出し、読み取り・別表は検出しない")
    void detectorSelfCheck() {
        // 検出する
        assertThat(writesRolesDirectly("jdbc.update(\"INSERT INTO roles (name) VALUES ('A')\")")).isTrue();
        assertThat(writesRolesDirectly("\"INSERT IGNORE INTO roles (name) VALUES ('A')\"")).isTrue();
        assertThat(writesRolesDirectly("\"REPLACE INTO roles (name) VALUES ('A')\"")).isTrue();
        assertThat(writesRolesDirectly("\"UPDATE roles SET priority = 1\"")).isTrue();
        assertThat(writesRolesDirectly("\"update roles set priority = 1\"")).isTrue();
        assertThat(writesRolesDirectly("\"INSERT INTO `roles` (name) VALUES ('A')\"")).isTrue();
        // 文字列連結で分割されている
        assertThat(writesRolesDirectly("\"INSERT INTO \"\n + \"roles (name) VALUES ('A')\"")).isTrue();
        assertThat(writesRolesDirectly("\"INSERT INTO\" + \" roles (name)\"")).isTrue();
        assertThat(writesRolesDirectly("\"INSERT   IGNORE\"\n        + \"  INTO   roles (name)\"")).isTrue();
        // テキストブロック（複数行）
        assertThat(writesRolesDirectly("\"\"\"\n    INSERT INTO\n        roles (name)\n    VALUES ('A')\n    \"\"\"")).isTrue();
        assertThat(writesRolesDirectly("\"\"\"\n    UPDATE\n      roles\n    SET priority = 1\n    \"\"\"")).isTrue();
        // 検出しない
        assertThat(writesRolesDirectly("\"SELECT id FROM roles WHERE name = :n\"")).isFalse();
        assertThat(writesRolesDirectly("\"INSERT INTO user_roles (user_id) VALUES (1)\"")).isFalse();
        assertThat(writesRolesDirectly("\"UPDATE user_roles SET role_id = 1\"")).isFalse();
        assertThat(writesRolesDirectly("\"INSERT INTO roles_audit (id) VALUES (1)\"")).isFalse();
        assertThat(writesRolesDirectly("RoleSeedSupport.ensureCanonical(em, \"ADMIN\");")).isFalse();
    }
}
