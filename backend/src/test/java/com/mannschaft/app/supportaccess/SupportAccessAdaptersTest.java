package com.mannschaft.app.supportaccess;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.guardianship.AgeStageResolution;
import com.mannschaft.app.auth.guardianship.GuardianshipAgePolicy;
import com.mannschaft.app.auth.guardianship.GuardianshipAgePolicyRegistry;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService;
import com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.SupportAccessActingAsAdapter;
import com.mannschaft.app.auth.service.SupportAccessUserDirectoryAdapter;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.notification.MandatoryNotificationTypes;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.role.service.PlatformAccountAffiliationPolicy;
import com.mannschaft.app.supportaccess.adapter.SupportAccessOperatorAffiliationAdapter;
import com.mannschaft.app.supportaccess.port.SupportAccessGuardianRequirement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 検分差し戻し対応: サポート閲覧の port 実装（所属判定の委譲・保護者の要否の fail-closed・後見切替の判定）と、
 * 本人通知の強制配信の判定。
 */
@DisplayName("戦役Z-B 検分差し戻し対応: port 実装と通知の強制配信")
class SupportAccessAdaptersTest {

    private static final Long OPERATOR_ID = 9001L;
    private static final Long CHILD_ID = 42L;
    private static final Long PARENT_A = 55L;
    private static final Long PARENT_B = 56L;

    @Nested
    @DisplayName("運営アカウントの所属判定（#4939 PlatformAccountAffiliationPolicy へ委譲）")
    class Affiliation {

        @Test
        @DisplayName("Adapter は PlatformAccountAffiliationPolicy#hasAnyTenantAffiliation に委譲する（村・サポーター・フォロー・委員会を含む判定は Policy の正本）")
        void Policyへ委譲する() {
            PlatformAccountAffiliationPolicy policy = mock(PlatformAccountAffiliationPolicy.class);
            given(policy.hasAnyTenantAffiliation(OPERATOR_ID)).willReturn(true);

            assertThat(new SupportAccessOperatorAffiliationAdapter(policy).isAffiliatedWithAnyTenant(OPERATOR_ID)).isTrue();
            verify(policy).hasAnyTenantAffiliation(OPERATOR_ID);
        }

        @Test
        @DisplayName("Adapter は Policy 以外の所属の窓口を持たない（独自に数えない＝範囲のずれを作らない）")
        void Policy以外に依存しない() {
            List<Class<?>> fieldTypes = Arrays.stream(SupportAccessOperatorAffiliationAdapter.class.getDeclaredFields())
                    .map(Field::getType).<Class<?>>map(c -> c).toList();
            assertThat(fieldTypes).containsExactly(PlatformAccountAffiliationPolicy.class);
        }

        @Test
        @DisplayName("Policy の判定はアカウントの状態を問わず（停止中の運営アカウントも）所属行を数える")
        void 停止中の運営アカウントも対象() {
            com.mannschaft.app.role.repository.UserRoleRepository repo =
                    mock(com.mannschaft.app.role.repository.UserRoleRepository.class);
            given(repo.countTenantAffiliationKindsByUserId(OPERATOR_ID)).willReturn(1L);
            PlatformAccountAffiliationPolicy policy = new PlatformAccountAffiliationPolicy(repo);

            assertThat(policy.hasAnyTenantAffiliation(OPERATOR_ID)).isTrue();
            assertThat(policy.hasAnyTenantAffiliation(null)).isFalse();
            // existsSystemAdminByUserId（ACTIVE のみ）には頼らない
            verify(repo, org.mockito.Mockito.never()).existsSystemAdminByUserId(any());
        }
    }

    @Nested
    @DisplayName("保護者の要否（fail-closed）")
    class Guardian {

        private final UserRepository userRepository = mock(UserRepository.class);
        private final ParentalConsentLinkRepository linkRepository = mock(ParentalConsentLinkRepository.class);
        private final GuardianshipSwitchService switchService = mock(GuardianshipSwitchService.class);
        private final GuardianshipAgePolicyRegistry registry = mock(GuardianshipAgePolicyRegistry.class);
        private final GuardianshipAgePolicy policy = mock(GuardianshipAgePolicy.class);
        private final SupportAccessUserDirectoryAdapter adapter = new SupportAccessUserDirectoryAdapter(
                userRepository, linkRepository, switchService, registry, mock(AccessControlService.class),
                Clock.fixed(Instant.parse("2026-11-27T00:00:00Z"), ZoneOffset.UTC));

        private void child(String birthDate, boolean switchAllowed) {
            UserEntity child = mock(UserEntity.class);
            when(child.getBirthDate()).thenReturn(birthDate);
            when(child.getCountryCode()).thenReturn("JP");
            given(userRepository.findById(CHILD_ID)).willReturn(Optional.of(child));
            given(registry.forCountry(any())).willReturn(policy);
            given(policy.resolve(any(), any())).willReturn(new AgeStageResolution(switchAllowed, "elementary"));
        }

        private void parent(Long id, UserEntity.UserStatus status) {
            UserEntity parent = mock(UserEntity.class);
            when(parent.getStatus()).thenReturn(status);
            given(userRepository.findById(id)).willReturn(Optional.of(parent));
        }

        private void links(Long... parents) {
            List<ParentalConsentLinkEntity> rows = Arrays.stream(parents).map(p -> {
                ParentalConsentLinkEntity link = mock(ParentalConsentLinkEntity.class);
                when(link.getParentUserId()).thenReturn(p);
                return link;
            }).toList();
            given(linkRepository.findByChildUserIdAndStatus(CHILD_ID, ParentalConsentLinkStatus.APPROVED)).willReturn(rows);
        }

        @Test
        @DisplayName("後見が及ぶ年齢の子で保護者リンクが無ければ、要る（required）のに承認できる保護者がいない＝fail-closed")
        void リンク無しの子は解決不能() {
            child("2018-05-01", true);
            links();

            SupportAccessGuardianRequirement r = adapter.resolveGuardianRequirement(CHILD_ID);

            assertThat(r.required()).isTrue();
            assertThat(r.unresolvable()).isTrue();
        }

        @Test
        @DisplayName("保護者が停止中（ACTIVE でない）なら承認できる保護者に含めない（他にいなければ fail-closed）")
        void 停止中の保護者は除く() {
            child("2018-05-01", true);
            links(PARENT_A);
            parent(PARENT_A, UserEntity.UserStatus.FROZEN);
            given(switchService.evaluateSwitch(anyLong(), anyLong())).willReturn(SwitchVerdict.ALLOWED);

            assertThat(adapter.resolveGuardianRequirement(CHILD_ID).unresolvable()).isTrue();
        }

        @Test
        @DisplayName("複数の保護者のうち承認できる人を userId 昇順で返す（既存の保護者同意と同じく、いずれか1人の承認で足りる）")
        void 複数保護者() {
            child("2018-05-01", true);
            links(PARENT_B, PARENT_A);
            parent(PARENT_A, UserEntity.UserStatus.ACTIVE);
            parent(PARENT_B, UserEntity.UserStatus.ACTIVE);
            given(switchService.evaluateSwitch(anyLong(), anyLong())).willReturn(SwitchVerdict.ALLOWED);

            SupportAccessGuardianRequirement r = adapter.resolveGuardianRequirement(CHILD_ID);

            assertThat(r.required()).isTrue();
            assertThat(r.eligibleGuardianUserIds()).containsExactly(PARENT_A, PARENT_B);
        }

        @Test
        @DisplayName("成人（18歳以上）で後見の封印段階の本人は、リンクがあっても保護者の承認は要らない")
        void 成人は不要() {
            child("2006-05-01", false);
            links(PARENT_A);

            assertThat(adapter.resolveGuardianRequirement(CHILD_ID).required()).isFalse();
        }

        @Test
        @DisplayName("マスター決定: 後見の封印段階（自立扱い）の未成年（16歳）でも保護者の承認が要り、承認できる保護者を返す")
        void 封印段階の未成年も保護者の承認が要る() {
            child("2010-05-01", false);
            links(PARENT_A);
            parent(PARENT_A, UserEntity.UserStatus.ACTIVE);
            given(switchService.evaluateSwitch(PARENT_A, CHILD_ID)).willReturn(SwitchVerdict.ALLOWED);

            SupportAccessGuardianRequirement r = adapter.resolveGuardianRequirement(CHILD_ID);

            assertThat(r.required()).isTrue();
            assertThat(r.eligibleGuardianUserIds()).containsExactly(PARENT_A);
        }

        @Test
        @DisplayName("封印段階の未成年で保護者が解決できなければ unresolvable（依頼は SUPPORT_ACCESS_005 で拒否される）")
        void 封印段階の未成年で保護者不在は解決不能() {
            child("2010-05-01", false);
            links();

            SupportAccessGuardianRequirement r = adapter.resolveGuardianRequirement(CHILD_ID);

            assertThat(r.required()).isTrue();
            assertThat(r.unresolvable()).isTrue();
        }

        @Test
        @DisplayName("ちょうど18歳の誕生日を迎えた本人は未成年ではない（境界）")
        void 十八歳の境界() {
            child("2008-11-27", false);
            links(PARENT_A);

            assertThat(adapter.resolveGuardianRequirement(CHILD_ID).required()).isFalse();
        }

        @Test
        @DisplayName("生年月日が読めない本人は、承認済みの保護者リンクがあっても成人と確認できないため解決不能")
        void 生年月日不明でリンクありは解決不能() {
            child(null, false);
            links(PARENT_A);
            parent(PARENT_A, UserEntity.UserStatus.ACTIVE);
            given(switchService.evaluateSwitch(PARENT_A, CHILD_ID)).willReturn(SwitchVerdict.ALLOWED);

            SupportAccessGuardianRequirement r = adapter.resolveGuardianRequirement(CHILD_ID);

            assertThat(r.required()).isTrue();
            assertThat(r.unresolvable()).isTrue();
        }

        @Test
        @DisplayName("第2回検分: 生年月日なし・保護者リンクなしでも成人扱いにせず解決不能（依頼は SUPPORT_ACCESS_005）")
        void 生年月日なしリンクなしは解決不能() {
            child(null, false);
            links();

            SupportAccessGuardianRequirement r = adapter.resolveGuardianRequirement(CHILD_ID);

            assertThat(r.required()).isTrue();
            assertThat(r.unresolvable()).isTrue();
        }

        @Test
        @DisplayName("生年月日が不正な形式（空白・壊れた日付）も解決不能")
        void 生年月日不正は解決不能() {
            child("2008-13-40", false);
            links();

            assertThat(adapter.resolveGuardianRequirement(CHILD_ID).unresolvable()).isTrue();
        }

        @Test
        @DisplayName("境界日は JST: Clock のゾーン（ロサンゼルス）では 11/26 でも JST で 11/27 なら 2008-11-27 生まれは18歳＝成人")
        void 境界日はJST_成人() {
            SupportAccessUserDirectoryAdapter utcAdapter = new SupportAccessUserDirectoryAdapter(
                    userRepository, linkRepository, switchService, registry, mock(AccessControlService.class),
                    Clock.fixed(Instant.parse("2026-11-26T16:00:00Z"), java.time.ZoneId.of("America/Los_Angeles")));
            child("2008-11-27", false);
            links();

            assertThat(utcAdapter.resolveGuardianRequirement(CHILD_ID).required()).isFalse();
        }

        @Test
        @DisplayName("境界日は JST: Clock のゾーン（キリティマティ UTC+14）では 11/27 でも JST で 11/26 なら未成年")
        void 境界日はJST_未成年() {
            SupportAccessUserDirectoryAdapter utcAdapter = new SupportAccessUserDirectoryAdapter(
                    userRepository, linkRepository, switchService, registry, mock(AccessControlService.class),
                    Clock.fixed(Instant.parse("2026-11-26T11:00:00Z"), java.time.ZoneId.of("Pacific/Kiritimati")));
            child("2008-11-27", false);
            links();

            assertThat(utcAdapter.resolveGuardianRequirement(CHILD_ID).unresolvable()).isTrue();
        }
    }

    @Nested
    @DisplayName("後見切替・代理入力の判定")
    class ActingAs {

        @AfterEach
        void tearDown() {
            RequestContextHolder.resetRequestAttributes();
        }

        @Test
        @DisplayName("リクエスト中に ProxyInputContext が代理（後見切替・代理入力）なら true、リクエストの外では false")
        void 代理の判定() {
            ProxyInputContext context = new ProxyInputContext();
            context.activate(CHILD_ID, null, "GUARDIANSHIP_SWITCH", null);
            SupportAccessActingAsAdapter adapter = new SupportAccessActingAsAdapter(context);

            assertThat(adapter.isActingAsAnotherUser()).as("リクエストの外").isFalse();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
            assertThat(adapter.isActingAsAnotherUser()).isTrue();
        }
    }

    @Nested
    @DisplayName("本人・保護者へのメール通知（AC12）")
    class Email {

        private com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer composer() {
            var localeCache = mock(com.mannschaft.app.common.i18n.UserLocaleCache.class);
            when(localeCache.getLocale(any())).thenReturn("ja");
            var messages = new org.springframework.context.support.StaticMessageSource();
            messages.addMessage("notification.supportaccess.requested.to.target.body", java.util.Locale.JAPANESE,
                    "運営担当者「{0}」から依頼。理由: {1}");
            var directory = mock(com.mannschaft.app.supportaccess.port.SupportAccessUserDirectory.class);
            when(directory.findUser(any())).thenReturn(Optional.empty());
            when(directory.findDeliverableEmail(CHILD_ID)).thenReturn(Optional.of("child@example.invalid"));
            return new com.mannschaft.app.supportaccess.service.SupportAccessNoticeComposer(
                    localeCache, messages, directory, "https://mannschaft.app");
        }

        @Test
        @DisplayName("依頼時・閲覧開始時は本人へメールも送る。理由は HTML エスケープしたプレーンテキストで、リンクは設定画面だけ（ワンクリック許可なし）")
        void 依頼と開始はメールも送る() {
            var c = composer();
            var requested = c.prepareEmail(CHILD_ID, SupportAccessNotificationKind.REQUESTED_TO_TARGET, OPERATOR_ID,
                    CHILD_ID, "<script>alert(1)</script> 表示崩れの確認").orElseThrow();
            var started = c.prepareEmail(CHILD_ID, SupportAccessNotificationKind.SESSION_STARTED_TO_TARGET, OPERATOR_ID,
                    CHILD_ID, "理由");

            java.util.UUID grantId = java.util.UUID.randomUUID();
            var first = requested.toRequest(grantId, null);
            assertThat(started).isPresent();
            assertThat(first.templateKind()).isEqualTo("SUPPORT_ACCESS_NOTICE");
            assertThat(first.toAddress()).isEqualTo("child@example.invalid");
            assertThat(first.userId()).isEqualTo(CHILD_ID);
            assertThat(first.sourceEventId()).isEqualTo("support-access:REQUESTED_TO_TARGET:" + grantId + ":" + CHILD_ID);
            String body = first.payloadVars().get("body");
            assertThat(body).contains("&lt;script&gt;").doesNotContain("<script>");
            assertThat(body).contains("https://mannschaft.app/settings/support-access");
            assertThat(body).doesNotContain("approve").doesNotContain("token=");
        }

        @Test
        @DisplayName("運営者宛（許可・拒否・取消）はメールを送らない（アプリ内通知のみ）")
        void 運営者宛はメールなし() {
            assertThat(composer().prepareEmail(OPERATOR_ID, SupportAccessNotificationKind.APPROVED_TO_OPERATOR,
                    CHILD_ID, CHILD_ID, "理由")).isEmpty();
        }

        @Test
        @DisplayName("送れる宛先が無い本人へはメールを組まない（アプリ内通知の outbox は別に必ず残る）")
        void 宛先なしはメールなし() {
            assertThat(composer().prepareEmail(999L, SupportAccessNotificationKind.REQUESTED_TO_TARGET,
                    OPERATOR_ID, 999L, "理由")).isEmpty();
        }
    }

    @Nested
    @DisplayName("本人通知の強制配信")
    class Mandatory {

        @Test
        @DisplayName("サポート閲覧の本人・保護者への通知は、通知種別を OFF にしていても配信する種別に含まれる（運営者宛は含まない）")
        void 本人通知は強制配信() {
            assertThat(MandatoryNotificationTypes.isMandatory("SUPPORT_ACCESS_REQUESTED_TO_TARGET")).isTrue();
            assertThat(MandatoryNotificationTypes.isMandatory("SUPPORT_ACCESS_REQUESTED_TO_GUARDIAN")).isTrue();
            assertThat(MandatoryNotificationTypes.isMandatory("SUPPORT_ACCESS_SESSION_STARTED_TO_TARGET")).isTrue();
            assertThat(MandatoryNotificationTypes.isMandatory("SUPPORT_ACCESS_APPROVED_TO_OPERATOR")).isFalse();
            assertThat(MandatoryNotificationTypes.isMandatory("CONFIRMABLE_NOTIFICATION_REMINDER_1")).isTrue();
            assertThat(MandatoryNotificationTypes.isMandatory("SCHEDULE_REMINDER")).isFalse();
        }

        @Test
        @DisplayName("本人側の通知種別（SupportAccessNotificationKind の *_TO_TARGET / *_TO_GUARDIAN）はすべて強制配信に登録されている")
        void 本人側の種別の漏れなし() {
            for (SupportAccessNotificationKind kind : SupportAccessNotificationKind.values()) {
                boolean toUser = kind.name().endsWith("_TO_TARGET") || kind.name().endsWith("_TO_GUARDIAN");
                assertThat(MandatoryNotificationTypes.isMandatory("SUPPORT_ACCESS_" + kind.name()))
                        .as(kind.name()).isEqualTo(toUser);
            }
        }
    }
}
