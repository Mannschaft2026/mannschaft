package com.mannschaft.app.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.duplicatename.DuplicateNameGuardService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.common.util.SlugValidator;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.organization.dto.CreateOrganizationRequest;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.repository.OrganizationSlugHistoryRepository;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
import com.mannschaft.app.support.test.SlugOccupancyAnswer;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CMP-261021-0838 試練（横展開）: 組織 slug が論理削除済み・slug 履歴・フォールバックと衝突しても 500 にならないこと。
 *
 * <p>DB の {@code uq_organizations_slug} は論理削除行も含めて一意。マスター決定（2026-11-25）により
 * 論理削除済み slug は永久予約（再利用不可）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261021-0838 OrganizationService slug 衝突 単体テスト")
class OrganizationSlugCollisionServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ROLE_ID = 100L;

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationSlugHistoryRepository organizationSlugHistoryRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private AdminRoleMutationLockService adminRoleMutationLockService;
    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private OrganizationMembershipService organizationMembershipService;
    @Mock private OrganizationHierarchyService organizationHierarchyService;
    @Mock private MembershipService membershipService;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @Mock private DuplicateNameGuardService duplicateNameGuardService;
    @InjectMocks private OrganizationService organizationService;

    private final SlugOccupancyAnswer slugs = new SlugOccupancyAnswer();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(organizationService, "organizationRepository",
                mock(OrganizationRepository.class, slugs));
        ReflectionTestUtils.setField(organizationService, "organizationSlugHistoryRepository",
                mock(OrganizationSlugHistoryRepository.class, slugs));
        lenient().when(duplicateNameGuardService.checkForCreateAndRun(
                        any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> createAction = inv.getArgument(6);
                    return createAction.get();
                });
        lenient().when(adminRoleMutationLockService.lockAdminRoleIdForCreation(USER_ID))
                .thenReturn(Optional.of(ADMIN_ROLE_ID));
        lenient().when(userRoleRepository.save(any(UserRoleEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void assertFree(String slug) {
        assertThat(slug).isNotNull();
        assertThat(SlugValidator.isValidFormat(slug)).as("採番 slug は形式が正しいこと: %s", slug).isTrue();
        assertThat(slugs.active).as("現役 slug と衝突しない").doesNotContain(slug);
        assertThat(slugs.deleted).as("論理削除済み slug（永久予約）と衝突しない").doesNotContain(slug);
        assertThat(slugs.retired).as("slug 履歴の予約と衝突しない").doesNotContain(slug);
    }

    @Nested
    @DisplayName("E1 自動採番は論理削除済み slug を避ける")
    class E1 {

        @Test
        void E1_組織自動採番_論理削除済み組織と同じ英字名_削除済みslugを避けて採番される() {
            slugs.deleted.add("tokyo-league");

            String slug = organizationService.createUniqueSlug("Tokyo League");

            assertFree(slug);
        }

        @Test
        void E1_組織自動採番_日本語名で基底slugが論理削除済み_削除済みslugを避けて採番される() {
            String base = SlugGenerator.generate("東京連盟");
            slugs.deleted.add(base);
            slugs.active.add(SlugGenerator.withSuffix(base, 1));

            String slug = organizationService.createUniqueSlug("東京連盟");

            assertFree(slug);
        }

        @Test
        void E1_組織作成_slug未指定で論理削除済み組織と同名_削除済みslugを避けて作成される() {
            slugs.deleted.add("tokyo-league");
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "Tokyo League", "SCHOOL", null, null, null, null, null);

            var result = organizationService.createOrganization(USER_ID, req);

            assertFree(result.getData().getSlug());
        }
    }

    @Nested
    @DisplayName("E2 ユーザー指定 slug が論理削除済みと衝突したら ORG_062")
    class E2 {

        @Test
        void E2_組織作成_指定slugが論理削除済み組織のslug_ORG_062() {
            slugs.deleted.add("gone-org");
            CreateOrganizationRequest req = new CreateOrganizationRequest(
                    "新組織", "SCHOOL", null, null, null, null, "gone-org");

            assertThatThrownBy(() -> organizationService.createOrganization(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ORG_062"));
        }
    }

    @Nested
    @DisplayName("E3 slug 可用性は論理削除済み slug を使用中と返す")
    class E3 {

        @Test
        void E3_組織slug可用性_論理削除済み組織のslug_availableはfalseでSLUG_ALREADY_TAKEN() {
            slugs.deleted.add("gone-org");

            var res = organizationService.checkSlugAvailability("gone-org");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_ALREADY_TAKEN");
        }
    }

    @Nested
    @DisplayName("E4 自動採番は organization_slug_history の予約を避ける")
    class E4 {

        @Test
        void E4_組織自動採番_基底slugが他組織の旧slugとして履歴予約済み_履歴予約を避けて採番される() {
            slugs.retired.add("tokyo-league");

            String slug = organizationService.createUniqueSlug("Tokyo League");

            assertFree(slug);
        }
    }

    @Nested
    @DisplayName("E6 フォールバックが衝突しても使用中 slug を返さない")
    class E6 {

        @Test
        void E6_組織自動採番_100回の候補とフォールバック域が全て使用中_使用中slugを返さず採番か4xx() {
            String base = "fallback-org";
            slugs.active.add(base);
            for (int i = 0; i < 10000; i++) {
                slugs.active.add(SlugGenerator.withSuffix(base, i));
            }

            String[] holder = new String[1];
            Throwable thrown = catchThrowable(() -> holder[0] = organizationService.createUniqueSlug("Fallback Org"));

            if (thrown != null) {
                assertThat(thrown).isInstanceOf(BusinessException.class);
            } else {
                assertFree(holder[0]);
            }
        }
    }
}
