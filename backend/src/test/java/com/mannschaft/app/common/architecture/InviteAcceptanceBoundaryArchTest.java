package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaConstructor;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * CMP-260826-1457 W1 前段「招待の承諾・辞退からトランザクションの越境を取り除く分離 PR」の構造試練（AC S2・G1・M1 の構造部）。
 *
 * <p>陣立て書 {@code .claude/campaigns/2026-11-08-cmp1457-invite-accept-split.md} §3・§11・§14 に基づく。
 * 凍結しない番人であり、凍結ストアを読み書きしない。D-3T 判定は本番番人
 * {@link CrossDomainTransactionalTransitiveArchTest#findViolations} をそのまま呼ぶ。</p>
 *
 * <p>CMP-261107-1833（招待付き新規登録の保留承認）で、register が Facade を呼ばなくなったことに合わせて
 * {@code callersUseFacade}・{@code registerReachSetIsUnchanged} を書き換え、{@link PendingInviteBoundary} を足した。</p>
 *
 * <p>新設型はまだ存在しないため {@link Class#forName} で引き、存在しなければ assertion で落とす（red）。
 * 「特性（main で緑）」と注記した試験は現行の不変条件を固定するもので、main でも緑が正しい。</p>
 */
@Tag(ArchUnitTestTag.ARCHUNIT)
@DisplayName("CMP-1457 招待承諾分離の構造番人（S2）")
class InviteAcceptanceBoundaryArchTest {

    static final String FACADE = "com.mannschaft.app.role.service.InviteAcceptanceFacade";
    static final String WRITER = "com.mannschaft.app.role.service.InviteAcceptanceWriter";
    static final String LOCATOR = "com.mannschaft.app.role.dto.InviteLocator";
    static final String CLAIM_ENTITY = "com.mannschaft.app.role.entity.InviteTokenClaimEntity";
    static final String TEAM_QUERY = "com.mannschaft.app.team.service.TeamInviteQueryService";
    static final String ORG_QUERY = "com.mannschaft.app.organization.service.OrganizationInviteQueryService";
    static final String PLACEMENT = "com.mannschaft.app.scopefolder.service.InviteFolderPlacementService";
    static final String INVITE_SERVICE = "com.mannschaft.app.role.service.InviteService";
    static final String GRANT_SERVICE = "com.mannschaft.app.role.service.MembershipGrantService";
    static final String REGISTRATION = "com.mannschaft.app.auth.service.AuthRegistrationService";
    static final String CONTROLLER = "com.mannschaft.app.role.controller.InviteController";
    static final String FOLDER_SERVICE = "com.mannschaft.app.scopefolder.service.MyScopeFolderService";
    static final String TOKEN_ENTITY = "com.mannschaft.app.role.entity.InviteTokenEntity";

    // ------------------------------------------------------------------
    // 新設型の存在と形（red）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("新設型の契約（red）")
    class NewTypes {

        @Test
        @DisplayName("S2: InviteAcceptanceFacade は承諾2種と辞退を公開し、@Transactional を一切持たない")
        void facadeHasNoTransactionAndExposesAcceptAndDecline() throws Exception {
            Class<?> facade = require(FACADE);
            assertThat(facade.getMethod("joinByInvite", String.class, Long.class).getReturnType()).isEqualTo(void.class);
            assertThat(facade.getMethod("joinByInvite", String.class, Long.class, Long.class).getReturnType())
                    .isEqualTo(void.class);
            assertThat(facade.getMethod("declineInvite", String.class, Long.class).getReturnType()).isEqualTo(void.class);
            assertNoTransactional(facade);
        }

        @Test
        @DisplayName("S2: Facade は Repository にも他ドメイン Entity にも依存しない")
        void facadeDependsOnNoRepositoryNorForeignEntity() {
            JavaClass facade = imported(FACADE);
            List<String> offending = facade.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass())
                    .filter(t -> t.getPackageName().startsWith(DomainPackages.ROOT_PACKAGE + "."))
                    .filter(t -> t.getPackageName().contains(".repository")
                            || (DomainPackages.isEntityPackage(t.getPackageName())
                                && !"role".equals(DomainPackages.domainOf(t.getPackageName()))))
                    .map(JavaClass::getName)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(offending).as("Facade の Repository / 他ドメイン Entity 依存").isEmpty();
        }

        @Test
        @DisplayName("S2: InviteAcceptanceWriter は inspectScope/claim/releaseClaim/recordJoined/decline を持ち、claim は書き込みTX")
        void writerExposesShortTransactionSteps() {
            Class<?> writer = require(WRITER);
            Set<String> names = Arrays.stream(writer.getMethods()).map(Method::getName).collect(Collectors.toSet());
            assertThat(names).contains("inspectScope", "claim", "releaseClaim", "recordJoined", "decline");
            for (String name : List.of("claim", "releaseClaim", "recordJoined", "decline")) {
                Method m = publicMethod(writer, name);
                Transactional tx = effectiveTransactional(writer, m);
                assertThat(tx).as(name + " は @Transactional（REQUIRED・書き込み）").isNotNull();
                assertThat(tx.readOnly()).as(name + " は readOnly でない").isFalse();
                assertThat(tx.propagation()).as(name + " は REQUIRED（外側TXに参加する）").isEqualTo(Propagation.REQUIRED);
            }
            // 解除と確定は claim の主キー（UUIDv7）で指す。
            assertThat(publicMethod(writer, "releaseClaim").getParameterTypes()).containsExactly(java.util.UUID.class);
            assertThat(publicMethod(writer, "recordJoined").getParameterTypes()).containsExactly(java.util.UUID.class);
        }

        @Test
        @DisplayName("S2: InviteLocator は sealed で、ByTokenHash(String)・ById(Long) のみを許可する（平文トークンを持たない）")
        void locatorIsSealedWithHashAndIdOnly() {
            Class<?> locator = require(LOCATOR);
            assertThat(locator.isSealed()).as("InviteLocator は sealed").isTrue();
            Class<?>[] permitted = locator.getPermittedSubclasses();
            assertThat(permitted).extracting(Class::getSimpleName).containsExactlyInAnyOrder("ByTokenHash", "ById");
            for (Class<?> type : permitted) {
                assertThat(type.isRecord()).as(type.getSimpleName() + " は record").isTrue();
            }
            Class<?> byHash = java.util.Arrays.stream(permitted).filter(c -> c.getSimpleName().equals("ByTokenHash")).findFirst().orElseThrow();
            Class<?> byId = java.util.Arrays.stream(permitted).filter(c -> c.getSimpleName().equals("ById")).findFirst().orElseThrow();
            assertThat(byHash.getRecordComponents()).extracting(RecordComponent::getType).containsExactly(String.class);
            assertThat(byId.getRecordComponents()).extracting(RecordComponent::getType).containsExactly(Long.class);
        }

        @Test
        @DisplayName("M1: InviteTokenClaimEntity は UuidV7Entity を継承し invite_token_claims に写像される")
        void claimEntityIsUuidV7OnClaimsTable() {
            Class<?> claim = require(CLAIM_ENTITY);
            assertThat(claim.getSuperclass().getName()).isEqualTo("com.mannschaft.app.common.entity.UuidV7Entity");
            Table table = claim.getAnnotation(Table.class);
            assertThat(table).as("@Table").isNotNull();
            assertThat(table.name()).isEqualTo("invite_token_claims");
        }

        @Test
        @DisplayName("S2: Team/Organization の InviteQueryService は readOnly で isBlocked と findName(String 返却) を持つ")
        void scopeQueryServicesAreReadOnlyAndEntityFree() {
            for (String name : List.of(TEAM_QUERY, ORG_QUERY)) {
                Class<?> query = require(name);
                Method isBlocked = method(query, "isBlocked", Long.class, Long.class);
                assertThat(isBlocked.getReturnType()).as(name + "#isBlocked").isEqualTo(boolean.class);
                Method findName = method(query, "findName", Long.class);
                assertThat(findName.getReturnType()).as(name + "#findName は Entity を外に出さない").isEqualTo(String.class);
                for (Method m : List.of(isBlocked, findName)) {
                    Transactional tx = effectiveTransactional(query, m);
                    assertThat(tx).as(name + "#" + m.getName() + " は readOnly TX").isNotNull();
                    assertThat(tx.readOnly()).isTrue();
                }
            }
        }

        @Test
        @DisplayName("S2: InviteFolderPlacementService は TX を持たず、checkInviteFolder は例外でなく結果を返す")
        void placementServiceHasNoTransaction() {
            Class<?> placement = require(PLACEMENT);
            Method check = method(placement, "checkInviteFolder", Long.class, Long.class, String.class);
            assertThat(check.getReturnType()).as("不正の種類を返す（void でない）").isNotEqualTo(void.class);
            method(placement, "place", Long.class, Long.class, String.class, Long.class);
            assertNoTransactional(placement);
        }

        @Test
        @DisplayName("S2: 新設クラスと claims 監視照会の D-3T 違反は 0 件")
        void newClassesHaveNoTransitiveCrossDomainViolation() {
            List<String> targets = List.of(FACADE, WRITER, TEAM_QUERY, ORG_QUERY, PLACEMENT);
            for (String name : targets) {
                require(name);
                assertThat(d3t(imported(name))).as(name + " の D-3T").isEmpty();
            }
            List<JavaClass> claimClasses = ProductionClasses.get().stream()
                    .filter(c -> c.getPackageName().startsWith("com.mannschaft.app.role"))
                    .filter(c -> c.getSimpleName().startsWith("InviteTokenClaim"))
                    .filter(c -> !c.getPackageName().endsWith(".entity"))
                    .toList();
            assertThat(claimClasses).as("claims 監視照会（MeterBinder 等）の実装クラス").isNotEmpty();
            for (JavaClass c : claimClasses) {
                assertThat(d3t(c)).as(c.getName() + " の D-3T").isEmpty();
            }
        }

        @Test
        @DisplayName("S2: 呼び出し側（InviteController）は Facade を呼び、InviteService の承諾・辞退を呼ばない"
                + "（AuthRegistrationService は CMP-261107-1833 で Facade を呼ばなくなった。PendingInviteBoundary を参照）")
        void callersUseFacade() {
            require(FACADE);
            for (String caller : List.of(CONTROLLER)) {
                JavaClass clazz = imported(caller);
                List<String> legacyCalls = clazz.getMethodCallsFromSelf().stream()
                        .filter(call -> call.getTargetOwner().getName().equals(INVITE_SERVICE))
                        .filter(call -> Set.of("joinByInvite", "declineInvite").contains(call.getName()))
                        .map(call -> call.getDescription())
                        .toList();
                assertThat(legacyCalls).as(caller + " の InviteService 承諾・辞退呼び出し").isEmpty();
                boolean callsFacade = clazz.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().getName().equals(FACADE)
                                && call.getName().equals("joinByInvite"));
                assertThat(callsFacade).as(caller + " は Facade#joinByInvite を呼ぶ").isTrue();
            }
        }
    }

    // ------------------------------------------------------------------
    // 既存クラスの整理（red）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("既存クラスの整理（red）")
    class ExistingClasses {

        @Test
        @DisplayName("S2: InviteService から承諾・辞退と越境 helper が撤去されている")
        void inviteServiceNoLongerAcceptsOrDeclines() {
            Class<?> service = require(INVITE_SERVICE);
            Set<String> declared = Arrays.stream(service.getDeclaredMethods()).map(Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertThat(declared).doesNotContain("joinByInvite", "declineInvite", "assignToFolder",
                    "checkNotBlocked", "recordMembershipInviteAudit", "resolveTargetName");
        }

        @Test
        @DisplayName("S2/§11: InviteService の D-3T は createInviteToken 2件・previewInvite 2件だけが残る（文言不変）")
        void inviteServiceRemainingTransitiveViolationsAreExactlyFour() {
            List<String> keys = d3t(imported(INVITE_SERVICE));
            String create = "@Transactional entry com.mannschaft.app.role.service.InviteService.createInviteToken("
                    + "java.lang.Long, java.lang.String, com.mannschaft.app.role.dto.CreateInviteTokenRequest, java.lang.Long)"
                    + " (domain 'role') reaches other-domain repository ";
            String preview = "@Transactional entry com.mannschaft.app.role.service.InviteService.previewInvite("
                    + "java.lang.String) (domain 'role') reaches other-domain repository ";
            assertThat(keys).containsExactlyInAnyOrder(
                    create + "com.mannschaft.app.organization.repository.OrganizationRepository (domain 'organization') [D-3T]",
                    create + "com.mannschaft.app.team.repository.TeamRepository (domain 'team') [D-3T]",
                    preview + "com.mannschaft.app.organization.repository.OrganizationRepository (domain 'organization') [D-3T]",
                    preview + "com.mannschaft.app.team.repository.TeamRepository (domain 'team') [D-3T]");
        }

        @Test
        @DisplayName("S2(D-3/D-5): InviteService は他ドメイン Repository を一切注入しない")
        void inviteServiceInjectsNoForeignRepository() {
            JavaClass service = imported(INVITE_SERVICE);
            List<String> foreign = service.getConstructors().stream()
                    .flatMap(c -> c.getRawParameterTypes().stream())
                    .filter(t -> t.getPackageName().contains(".repository"))
                    .filter(t -> !"role".equals(DomainPackages.domainOf(t.getPackageName())))
                    .map(JavaClass::getName)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(foreign).as("InviteService コンストラクタの他ドメイン Repository").isEmpty();
        }

        @Test
        @DisplayName("S2(D-1): InviteService は他ドメインの entity パッケージ（enum 含む）を参照しない")
        void inviteServiceReferencesNoForeignEntity() {
            JavaClass service = imported(INVITE_SERVICE);
            List<String> foreign = service.getDirectDependenciesFromSelf().stream()
                    .map(d -> d.getTargetClass())
                    .filter(t -> DomainPackages.isEntityPackage(t.getPackageName()))
                    .filter(t -> {
                        String domain = DomainPackages.domainOf(t.getPackageName());
                        return domain != null && !"role".equals(domain) && !DomainPackages.isSharedDomain(domain);
                    })
                    .map(JavaClass::getName)
                    .distinct()
                    .sorted()
                    .toList();
            assertThat(foreign).as("InviteService の他ドメイン Entity 参照").isEmpty();
        }

        @Test
        @DisplayName("§7: MembershipGrantService#grantRole は GRANTED/ALREADY_JOINED の enum を返す")
        void grantRoleReturnsOutcomeEnum() throws Exception {
            Class<?> grant = require(GRANT_SERVICE);
            Method grantRole = grant.getMethod("grantRole", String.class, Long.class, Long.class, Long.class, Long.class,
                    String.class);
            Class<?> outcome = grantRole.getReturnType();
            assertThat(outcome.isEnum()).as("grantRole の戻り値は enum（仮名 GrantOutcome）").isTrue();
            assertThat(Arrays.stream(outcome.getEnumConstants()).map(Object::toString).toList())
                    .containsExactlyInAnyOrder("GRANTED", "ALREADY_JOINED");
        }

        @Test
        @DisplayName("§8.2-B/§8.3: InviteTokenEntity は incrementUsedCount を持たず、used_count は updatable=false")
        void inviteTokenEntityNoLongerWritesUsedCount() throws Exception {
            Class<?> entity = require(TOKEN_ENTITY);
            assertThat(Arrays.stream(entity.getDeclaredMethods()).map(Method::getName))
                    .doesNotContain("incrementUsedCount");
            Column column = entity.getDeclaredField("usedCount").getAnnotation(Column.class);
            assertThat(column).as("usedCount の @Column").isNotNull();
            assertThat(column.updatable()).as("Entity 経由の全列 UPDATE が古い used_count を書き戻さない").isFalse();
        }
    }

    // ------------------------------------------------------------------
    // 変えてはいけない不変条件（特性・main で緑）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("不変条件（特性・main で緑）")
    class Invariants {

        @Test
        @DisplayName("AC11（CMP-261107-1833）: AuthRegistrationService.register の D-3T 到達集合は Beta・InviteToken の2件だけ"
                + "（Facade 経路の7件は消え、凍結ストアから削除する）")
        void registerReachSetIsUnchanged() {
            String prefix = "@Transactional entry com.mannschaft.app.auth.service.AuthRegistrationService.register("
                    + "com.mannschaft.app.auth.dto.RegisterRequest, java.lang.String) (domain 'auth') reaches other-domain repository ";
            List<String> keys = d3t(imported(REGISTRATION)).stream()
                    .filter(k -> k.startsWith(prefix))
                    .toList();
            assertThat(keys).containsExactlyInAnyOrder(
                    prefix + "com.mannschaft.app.admin.repository.BetaRestrictionConfigRepository (domain 'admin') [D-3T]",
                    prefix + "com.mannschaft.app.role.repository.InviteTokenRepository (domain 'role') [D-3T]");
        }

        @Test
        @DisplayName("§11: MyScopeFolderService のコンストラクタ署名は不変（注入を足さない）")
        void myScopeFolderServiceConstructorIsUnchanged() {
            List<JavaConstructor> constructors = List.copyOf(imported(FOLDER_SERVICE).getConstructors());
            assertThat(constructors).hasSize(1);
            assertThat(constructors.get(0).getRawParameterTypes()).extracting(JavaClass::getName).containsExactly(
                    "com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository",
                    "com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository",
                    "com.mannschaft.app.membership.repository.MembershipRepository",
                    "com.mannschaft.app.scopefolder.service.ScopeFolderAccessGuard");
        }

        @Test
        @DisplayName("§7/§11: grantRole・grantMemberRole の引数は不変、MembershipGrantService にクラス単位 @Transactional なし")
        void grantSignaturesAreUnchanged() throws Exception {
            Class<?> grant = require(GRANT_SERVICE);
            grant.getMethod("grantRole", String.class, Long.class, Long.class, Long.class, Long.class, String.class);
            grant.getMethod("grantMemberRole", String.class, Long.class, Long.class, Long.class, String.class);
            assertThat(grant.isAnnotationPresent(Transactional.class))
                    .as("クラス単位 @Transactional を付けると private ヘルパ doGrant が D-3T 入口になる").isFalse();
        }
    }

    // ------------------------------------------------------------------
    // CMP-261107-1833: 招待付き新規登録の保留承認（AC11・設計 §2・§4・§5・§9-2・§9-3）
    // ------------------------------------------------------------------

    static final String PENDING_SERVICE = "com.mannschaft.app.auth.service.PendingInviteService";
    static final String PENDING_STORE = "com.mannschaft.app.auth.service.PendingInviteStore";
    static final String PENDING_CONTROLLER = "com.mannschaft.app.auth.controller.MePendingInviteController";
    static final String ISSUER_POLICY = "com.mannschaft.app.role.service.InviteIssuerPolicy";
    static final String RECIPIENT_RESOLVER = "com.mannschaft.app.role.service.InviteReissueRecipientResolver";
    static final String REISSUE_LISTENER = "com.mannschaft.app.role.event.InviteReissueNotificationListener";
    static final String MEMBERSHIP_INVITE_SERVICE = "com.mannschaft.app.role.service.MembershipInviteService";
    static final String CLEANUP_BATCH = "com.mannschaft.app.auth.service.AuthCleanupBatchService";
    static final String PARENTAL_CONSENT = "com.mannschaft.app.auth.service.ParentalConsentService";
    static final String ERROR_CODE = "com.mannschaft.app.common.ErrorCode";
    /** D-3T（CrossDomainTransactionalTransitiveArchTest）の凍結ストア。 */
    static final String D3T_STORE = "archunit_store/296295dd-06cf-4f7b-bf82-315ba12ff501";

    @Nested
    @DisplayName("CMP-261107-1833 保留承認の構造（AC11）")
    class PendingInviteBoundary {

        @Test
        @DisplayName("AC11: AuthRegistrationService は InviteAcceptanceFacade に一切依存しない（注入も呼び出しもしない）")
        void registrationDoesNotUseFacade() {
            JavaClass registration = imported(REGISTRATION);
            List<String> facadeDeps = registration.getDirectDependenciesFromSelf().stream()
                    .filter(d -> d.getTargetClass().getName().equals(FACADE))
                    .map(d -> d.getDescription())
                    .distinct()
                    .toList();
            assertThat(facadeDeps).as("AuthRegistrationService → Facade の依存").isEmpty();
        }

        @Test
        @DisplayName("AC11: Facade は joinByInviteTokenHash(String,Long)・settleOrphanClaim(String,Long) を公開し、@Transactional を持たない")
        void facadeHasHashEntry() {
            Class<?> facade = require(FACADE);
            assertThat(method(facade, "joinByInviteTokenHash", String.class, Long.class).getReturnType()).isEqualTo(void.class);
            assertThat(method(facade, "settleOrphanClaim", String.class, Long.class).getReturnType()).isEqualTo(void.class);
            assertNoTransactional(facade);
        }

        @Test
        @DisplayName("AC11: hash 入口（joinByInviteTokenHash・settleOrphanClaim）を呼ぶ本番クラスは PendingInviteService だけ")
        void hashEntryCalledOnlyByPendingInviteService() {
            require(PENDING_SERVICE);
            for (String entry : List.of("joinByInviteTokenHash", "settleOrphanClaim")) {
                Set<String> callers = ProductionClasses.get().stream()
                        .flatMap(c -> c.getMethodCallsFromSelf().stream())
                        .filter(call -> call.getTargetOwner().getName().equals(FACADE))
                        .filter(call -> call.getName().equals(entry))
                        .map(call -> call.getOriginOwner().getName())
                        .filter(owner -> !owner.equals(FACADE))
                        .collect(Collectors.toCollection(TreeSet::new));
                assertThat(callers).as(entry + " の呼び出し元").containsExactly(PENDING_SERVICE);
            }
        }

        @Test
        @DisplayName("AC11(§9-3): Writer#claimOrResume(InviteLocator, Long, boolean, ErrorCode) は REQUIRED の書き込みTX")
        void writerHasClaimOrResume() throws Exception {
            Class<?> writer = require(WRITER);
            Method m = method(writer, "claimOrResume", require(LOCATOR), Long.class, boolean.class, require(ERROR_CODE));
            Transactional tx = effectiveTransactional(writer, m);
            assertThat(tx).as("claimOrResume は @Transactional").isNotNull();
            assertThat(tx.readOnly()).isFalse();
            assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRED);
        }

        @Test
        @DisplayName("AC11: PendingInviteService は @Transactional を持たない（Facade の各段の短TXを外側TXなしで呼ぶ）")
        void pendingServiceHasNoTransaction() {
            assertNoTransactional(require(PENDING_SERVICE));
        }

        @Test
        @DisplayName("AC11: PendingInviteStore の書き込みは auth の短TX（REQUIRED・readOnly でない）")
        void storeWritesAreShortTransactions() {
            Class<?> store = require(PENDING_STORE);
            for (String name : List.of("createPending", "tryStartAccepting", "completeAttempt", "revertAttempt",
                    "transition", "markReissueRequested", "discardOpen")) {
                Method m = publicMethod(store, name);
                Transactional tx = effectiveTransactional(store, m);
                assertThat(tx).as(name + " は @Transactional").isNotNull();
                assertThat(tx.readOnly()).as(name + " は readOnly でない").isFalse();
                assertThat(tx.propagation()).as(name + " は REQUIRED（登録の users と同TXに参加する）")
                        .isEqualTo(Propagation.REQUIRED);
            }
        }

        @Test
        @DisplayName("AC11(§9-2): 新3クラス（InviteIssuerPolicy・InviteReissueRecipientResolver・InviteReissueNotificationListener）は @Transactional を持たない")
        void newThreeClassesHaveNoTransaction() {
            for (String name : List.of(ISSUER_POLICY, RECIPIENT_RESOLVER, REISSUE_LISTENER)) {
                assertNoTransactional(require(name));
            }
        }

        @Test
        @DisplayName("AC11: 新設クラスの D-3T 違反は 0 件")
        void newClassesHaveNoTransitiveViolation() {
            for (String name : List.of(PENDING_SERVICE, PENDING_STORE, PENDING_CONTROLLER, ISSUER_POLICY,
                    RECIPIENT_RESOLVER, REISSUE_LISTENER)) {
                assertThat(d3t(imported(name))).as(name + " の D-3T").isEmpty();
            }
        }

        @Test
        @DisplayName("AC11(§9-2): MembershipInviteService は canIssueWithRole を持たず InviteIssuerPolicy に委譲する")
        void membershipInviteServiceDelegatesToPolicy() {
            require(ISSUER_POLICY);
            Class<?> service = require(MEMBERSHIP_INVITE_SERVICE);
            assertThat(Arrays.stream(service.getDeclaredMethods()).map(Method::getName)).doesNotContain("canIssueWithRole");
            boolean callsPolicy = imported(MEMBERSHIP_INVITE_SERVICE).getMethodCallsFromSelf().stream()
                    .anyMatch(call -> call.getTargetOwner().getName().equals(ISSUER_POLICY));
            assertThat(callsPolicy).as("MembershipInviteService → InviteIssuerPolicy の呼び出し").isTrue();
        }

        @Test
        @DisplayName("AC11: register・MembershipInviteService・InviteService の D-3T は凍結ストアにある行だけ（削除のみ・追記なし）")
        void transitiveKeysAreSubsetOfFrozenStore() {
            Set<String> frozen = frozenD3tKeys();
            for (String name : List.of(REGISTRATION, MEMBERSHIP_INVITE_SERVICE, INVITE_SERVICE)) {
                List<String> added = d3t(imported(name)).stream().filter(k -> !frozen.contains(k)).toList();
                assertThat(added).as(name + " の凍結ストアに無い D-3T（新たに踏んだ）").isEmpty();
            }
            assertThat(d3t(imported(MEMBERSHIP_INVITE_SERVICE)))
                    .as("canIssueWithRole は InviteIssuerPolicy へ移り D-3T 入口でなくなる")
                    .noneMatch(k -> k.contains("MembershipInviteService.canIssueWithRole("));
        }

        @Test
        @DisplayName("AC11: 凍結ストアから register の Facade 経路7行が削除され、Beta・InviteToken の2行だけが残る")
        void frozenStoreHasOnlyTwoRegisterLines() {
            String prefix = "@Transactional entry com.mannschaft.app.auth.service.AuthRegistrationService.register(";
            List<String> registerLines = frozenD3tKeys().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
            assertThat(registerLines).hasSize(2);
            assertThat(registerLines).anyMatch(k -> k.contains("BetaRestrictionConfigRepository"));
            assertThat(registerLines).anyMatch(k -> k.contains("InviteTokenRepository"));
        }

        @Test
        @DisplayName("AC3/§5: 未確認アカウント掃除と保護者全員拒否は PendingInviteStore#discardOpen で保留を DISCARDED にする")
        void discardPathsCallStore() {
            require(PENDING_STORE);
            for (String caller : List.of(CLEANUP_BATCH, PARENTAL_CONSENT)) {
                boolean discards = imported(caller).getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().getName().equals(PENDING_STORE)
                                && call.getName().equals("discardOpen"));
                assertThat(discards).as(caller + " → PendingInviteStore#discardOpen").isTrue();
            }
        }
    }

    private static Set<String> frozenD3tKeys() {
        try (java.io.InputStream in = InviteAcceptanceBoundaryArchTest.class.getClassLoader().getResourceAsStream(D3T_STORE)) {
            assertThat(in).as("D-3T 凍結ストア " + D3T_STORE).isNotNull();
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            return text.lines().map(String::strip).filter(l -> !l.isEmpty()).collect(Collectors.toCollection(TreeSet::new));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static Class<?> require(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return fail("未実装: " + name + " が存在しない（CMP-1457 陣立て書 §3）");
        }
    }

    private static JavaClass imported(String name) {
        require(name);
        Optional<JavaClass> found = ProductionClasses.get().stream().filter(c -> c.getName().equals(name)).findFirst();
        return found.orElseGet(() -> fail("本番クラス集合に " + name + " が無い"));
    }

    private static List<String> d3t(JavaClass clazz) {
        return CrossDomainTransactionalTransitiveArchTest.findViolations(clazz, ProductionClasses.get()).stream()
                .map(CrossDomainTransactionalTransitiveArchTest.TransitiveViolation::freezeKey)
                .toList();
    }

    private static Method publicMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).filter(m -> m.getName().equals(name)).findFirst()
                .orElseGet(() -> fail(type.getName() + "#" + name + " が無い"));
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        try {
            return type.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return fail(type.getName() + "#" + name + Arrays.toString(params) + " が無い");
        }
    }

    private static Transactional effectiveTransactional(Class<?> type, Method m) {
        Transactional onMethod = m.getAnnotation(Transactional.class);
        return onMethod != null ? onMethod : type.getAnnotation(Transactional.class);
    }

    private static void assertNoTransactional(Class<?> type) {
        assertThat(type.isAnnotationPresent(Transactional.class)).as(type.getSimpleName() + " のクラス単位 @Transactional").isFalse();
        List<String> annotated = Arrays.stream(type.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> m.isAnnotationPresent(Transactional.class)
                        || Arrays.stream(m.getAnnotations())
                            .anyMatch(a -> a.annotationType().isAnnotationPresent(Transactional.class)))
                .map(m -> m.getName() + (Modifier.isPrivate(m.getModifiers()) ? "(private)" : ""))
                .toList();
        assertThat(annotated).as(type.getSimpleName() + " の @Transactional メソッド").isEmpty();
    }
}
