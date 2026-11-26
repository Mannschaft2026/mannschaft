package com.mannschaft.app.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.duplicatename.DuplicateNameGuardService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.common.util.SlugValidator;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.AdminRoleMutationLockService;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.support.test.SlugOccupancyAnswer;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamSlugHistoryRepository;
import com.mannschaft.app.team.service.TeamService;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import java.sql.SQLIntegrityConstraintViolationException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * CMP-261021-0838 試練: チーム slug が論理削除済み・slug 履歴・フォールバックと衝突しても 500 にならないこと。
 *
 * <p>マスター決定（2026-11-25）: 論理削除済みチームの slug は永久予約（再利用不可）。DB の
 * {@code uq_teams_slug} は論理削除行も含めて一意のため、存在確認も論理削除済みを含めて行う必要がある。</p>
 *
 * <p>{@link TeamRepository}・{@link TeamSlugHistoryRepository} は {@link SlugOccupancyAnswer} を既定 Answer
 * とするモックへ差し替え、照会メソッド名に依存せず「どの slug が占有されているか」だけを与える。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261021-0838 TeamService slug 衝突 単体テスト")
class TeamSlugCollisionServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private TeamSlugHistoryRepository teamSlugHistoryRepository;
    @Mock private TeamBlockRepository teamBlockRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private AdminRoleMutationLockService adminRoleMutationLockService;
    @Mock private UserRepository userRepository;
    @Mock private TeamFriendRepository teamFriendRepository;
    @Mock private TeamShiftSettingsService teamShiftSettingsService;
    @Mock private MembershipService membershipService;
    @Mock private MembershipRepository membershipRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @Mock private DuplicateNameGuardService duplicateNameGuardService;
    @InjectMocks private TeamService service;

    private static final Long USER_ID = 1L;

    private final SlugOccupancyAnswer slugs = new SlugOccupancyAnswer();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "teamRepository", mock(TeamRepository.class, slugs));
        ReflectionTestUtils.setField(service, "teamSlugHistoryRepository",
                mock(TeamSlugHistoryRepository.class, slugs));
        lenient().when(duplicateNameGuardService.checkForCreateAndRun(
                        any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.function.Supplier<?> createAction = inv.getArgument(6);
                    return createAction.get();
                });
        lenient().when(adminRoleMutationLockService.lockAdminRoleIdForCreation(USER_ID))
                .thenReturn(Optional.of(1L));
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
        void E1_自動採番_論理削除済みチームと同じ英字名_削除済みslugを避けて採番される() {
            slugs.deleted.add("my-club");

            String slug = service.createUniqueSlug("My Club");

            assertFree(slug);
        }

        @Test
        void E1_自動採番_日本語名で基底slugが論理削除済み_削除済みslugを避けて採番される() {
            // SlugGenerator は非 ASCII を落とすため、日本語のみの名前は固定の "team" に落ちる
            String base = SlugGenerator.generate("日本語チーム");
            slugs.deleted.add(base);
            slugs.deleted.add(SlugGenerator.withSuffix(base, 1));
            slugs.active.add(SlugGenerator.withSuffix(base, 2));

            String slug = service.createUniqueSlug("日本語チーム");

            assertFree(slug);
        }

        @Test
        void E1_チーム作成_slug未指定で論理削除済みチームと同名_削除済みslugを避けて作成される() {
            slugs.deleted.add("my-club");
            CreateTeamRequest req = new CreateTeamRequest(
                    "My Club", null, null, null, null, null, null, null, false, null);

            var result = service.createTeam(USER_ID, req);

            assertFree(result.getData().getSlug());
        }
    }

    @Nested
    @DisplayName("E2 ユーザー指定 slug が論理削除済みと衝突したら TEAM_062")
    class E2 {

        @Test
        void E2_チーム作成_指定slugが論理削除済みチームのslug_TEAM_062() {
            slugs.deleted.add("gone-team");
            CreateTeamRequest req = new CreateTeamRequest(
                    "新チーム", null, null, null, null, null, null, "gone-team", false, null);

            assertThatThrownBy(() -> service.createTeam(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TEAM_062"));
        }
    }

    @Nested
    @DisplayName("E3 slug 可用性は論理削除済み slug を使用中と返す")
    class E3 {

        @Test
        void E3_slug可用性_論理削除済みチームのslug_availableはfalseでSLUG_ALREADY_TAKEN() {
            slugs.deleted.add("gone-team");

            var res = service.checkSlugAvailability("gone-team");

            assertThat(res.available()).isFalse();
            assertThat(res.reason()).isEqualTo("SLUG_ALREADY_TAKEN");
        }
    }

    @Nested
    @DisplayName("E4 自動採番は team_slug_history の予約を避ける")
    class E4 {

        @Test
        void E4_自動採番_基底slugが他チームの旧slugとして履歴予約済み_履歴予約を避けて採番される() {
            slugs.retired.add("my-club");

            String slug = service.createUniqueSlug("My Club");

            assertFree(slug);
        }

        @Test
        void E4_自動採番_基底とサフィックス1が履歴予約済み_どちらも避けて採番される() {
            slugs.retired.add("my-club");
            slugs.retired.add("my-club-1");
            slugs.active.add("my-club-2");

            String slug = service.createUniqueSlug("My Club");

            assertFree(slug);
        }
    }

    @Nested
    @DisplayName("E6 フォールバックが衝突しても使用中 slug を返さない")
    class E6 {

        @Test
        void E6_自動採番_100回の候補とフォールバック域が全て使用中_使用中slugを返さず採番か4xx() {
            String base = "fallback-club";
            slugs.active.add(base);
            for (int i = 0; i < 10000; i++) {
                // -1..-100 の候補と、currentTimeMillis%10000 によるフォールバック域 -0..-9999 を全て埋める
                slugs.active.add(SlugGenerator.withSuffix(base, i));
            }

            // ラムダ外へ結果を出すため配列で受ける
            String[] holder = new String[1];
            Throwable thrown = catchThrowable(() -> holder[0] = service.createUniqueSlug("Fallback Club"));
            String slug = holder[0];

            if (thrown != null) {
                // 採番を諦める場合は 500 ではなく業務例外（4xx）であること
                assertThat(thrown).isInstanceOf(BusinessException.class);
            } else {
                assertFree(slug);
            }
        }
    }

    @Nested
    @DisplayName("E5 並行作成で採番直後に一意制約違反になっても 500 にしない")
    class E5 {

        private void saveViolatesUniqueIndex() {
            TeamRepository repo = (TeamRepository) ReflectionTestUtils.getField(service, "teamRepository");
            doThrow(new DataIntegrityViolationException("could not execute statement",
                    new SQLIntegrityConstraintViolationException(
                            "Duplicate entry 'my-club' for key 'teams.uq_teams_slug'", "23000", 1062)))
                    .when(repo).save(any());
        }

        @Test
        void E5_slug未指定の自動採番で保存時に一意制約違反_TEAM_073で500にしない() {
            saveViolatesUniqueIndex();
            CreateTeamRequest req = new CreateTeamRequest(
                    "My Club", null, null, null, null, null, null, null, false, null);

            Throwable thrown = catchThrowable(() -> service.createTeam(USER_ID, req));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo("TEAM_073");
        }

        @Test
        void E5_ユーザー指定slugで保存時に一意制約違反_TEAM_062() {
            saveViolatesUniqueIndex();
            CreateTeamRequest req = new CreateTeamRequest(
                    "My Club", null, null, null, null, null, null, "my-club", false, null);

            Throwable thrown = catchThrowable(() -> service.createTeam(USER_ID, req));

            assertThat(thrown).isInstanceOf(BusinessException.class);
            assertThat(((BusinessException) thrown).getErrorCode().getCode()).isEqualTo("TEAM_062");
        }

        @Test
        void E5_無関係な一意制約違反は素通しして握りつぶさない() {
            TeamRepository repo = (TeamRepository) ReflectionTestUtils.getField(service, "teamRepository");
            doThrow(new DataIntegrityViolationException("x",
                    new SQLIntegrityConstraintViolationException("Duplicate entry 'a' for key 'teams.uq_other", "23000", 1062)))
                    .when(repo).save(any());
            CreateTeamRequest req = new CreateTeamRequest(
                    "My Club", null, null, null, null, null, null, null, false, null);

            assertThatThrownBy(() -> service.createTeam(USER_ID, req))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }
}
