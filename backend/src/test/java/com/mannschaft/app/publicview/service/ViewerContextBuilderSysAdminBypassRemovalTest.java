package com.mannschaft.app.publicview.service;

import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.publicview.visibility.ViewerStatus;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 戦役 Z-A PR4（試練）: 公開ページの {@link ViewerContextBuilder} から SYSTEM_ADMIN 分岐を撤去する受け入れテスト（AC7）。
 *
 * <p>運営アカウント（所属なし）は {@link ViewerStatus#NON_MEMBER}（nonMember）として扱う。
 * 所属があれば（移行前の兼任）実ロールどおり MEMBER / SUPPORTER になる。一般利用者は撤去前と同じ。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: ViewerContextBuilder の SYSTEM_ADMIN 分岐撤去（AC7）")
class ViewerContextBuilderSysAdminBypassRemovalTest {

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private RoleRepository roleRepository;
    @InjectMocks private ViewerContextBuilder builder;

    private static final Long SYSADMIN = 900L;
    private static final Long USER = 2L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 300L;

    @BeforeEach
    void setUp() {
        given(userRoleRepository.existsSystemAdminByUserId(SYSADMIN)).willReturn(1L);
        given(userRoleRepository.existsSystemAdminByUserId(USER)).willReturn(0L);
        given(userRoleRepository.findByUserIdAndTeamId(SYSADMIN, TEAM_ID)).willReturn(Optional.empty());
        given(userRoleRepository.findByUserIdAndOrganizationId(SYSADMIN, ORG_ID)).willReturn(Optional.empty());
        given(roleRepository.findById(4L)).willReturn(Optional.of(role(4L, "MEMBER")));
        given(roleRepository.findById(6L)).willReturn(Optional.of(role(6L, "SUPPORTER")));
        given(roleRepository.findById(2L)).willReturn(Optional.of(role(2L, "ADMIN")));
    }

    private static RoleEntity role(Long id, String name) {
        return RoleEntity.builder().id(id).name(name).displayName(name).priority(1).isSystem(false).build();
    }

    private static UserRoleEntity userRole(Long userId, Long roleId) {
        return UserRoleEntity.builder().userId(userId).roleId(roleId).build();
    }

    @Test
    @DisplayName("チーム: 所属の無い運営アカウントは NON_MEMBER")
    void team_platformAccount_nonMember() {
        ViewerContext ctx = builder.buildForTeamByUserId(SYSADMIN, TEAM_ID);
        assertThat(ctx.status()).isEqualTo(ViewerStatus.NON_MEMBER);
        assertThat(ctx.userId()).isEqualTo(SYSADMIN);
        assertThat(ctx.memberScopeIds()).isEmpty();
    }

    @Test
    @DisplayName("組織: 所属の無い運営アカウントは NON_MEMBER")
    void organization_platformAccount_nonMember() {
        ViewerContext ctx = builder.buildForOrganizationByUserId(SYSADMIN, ORG_ID);
        assertThat(ctx.status()).isEqualTo(ViewerStatus.NON_MEMBER);
    }

    @Test
    @DisplayName("チーム: SUPPORTER の所属がある SYSTEM_ADMIN（移行前の兼任）は実ロールどおり SUPPORTER")
    void team_sysAdminWithSupporterRole_supporter() {
        given(userRoleRepository.findByUserIdAndTeamId(SYSADMIN, TEAM_ID))
                .willReturn(Optional.of(userRole(SYSADMIN, 6L)));
        ViewerContext ctx = builder.buildForTeamByUserId(SYSADMIN, TEAM_ID);
        assertThat(ctx.status()).isEqualTo(ViewerStatus.SUPPORTER);
    }

    @Test
    @DisplayName("どの経路でも SYSTEM_ADMIN の立場は作られない")
    void neverProducesSystemAdminStatus() {
        // 列挙値 SYSTEM_ADMIN が将来削除されてもコンパイルできるよう名前で比較する
        assertThat(builder.buildForTeamByUserId(SYSADMIN, TEAM_ID).status().name()).isNotEqualTo("SYSTEM_ADMIN");
        assertThat(builder.buildForOrganizationByUserId(SYSADMIN, ORG_ID).status().name())
                .isNotEqualTo("SYSTEM_ADMIN");
    }

    @Test
    @DisplayName("回帰: 一般利用者の ADMIN は MEMBER、SUPPORTER は SUPPORTER、未所属は NON_MEMBER、未ログインは ANONYMOUS")
    void generalUser_regression() {
        given(userRoleRepository.findByUserIdAndTeamId(USER, TEAM_ID)).willReturn(Optional.of(userRole(USER, 2L)));
        assertThat(builder.buildForTeamByUserId(USER, TEAM_ID).status()).isEqualTo(ViewerStatus.MEMBER);

        given(userRoleRepository.findByUserIdAndOrganizationId(USER, ORG_ID))
                .willReturn(Optional.of(userRole(USER, 6L)));
        assertThat(builder.buildForOrganizationByUserId(USER, ORG_ID).status()).isEqualTo(ViewerStatus.SUPPORTER);

        given(userRoleRepository.findByUserIdAndTeamId(USER, 999L)).willReturn(Optional.empty());
        assertThat(builder.buildForTeamByUserId(USER, 999L).status()).isEqualTo(ViewerStatus.NON_MEMBER);

        assertThat(builder.buildForTeamByUserId(null, TEAM_ID).status()).isEqualTo(ViewerStatus.ANONYMOUS);
    }
}
