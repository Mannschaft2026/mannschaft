package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 戦役 Z-A PR4（試練）: {@link ScheduleKeepVisibilityResolver} の SYSTEM_ADMIN 高速パス（F00 §15 D-13 の写し）撤去。
 *
 * <p>運営アカウント（所属なし）はチーム・組織のキープ（MEMBER 以上）も、他人の個人キープ（本人のみ）も見えない。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-A PR4: ScheduleKeepVisibilityResolver の SYSTEM_ADMIN 短絡撤去")
class ScheduleKeepVisibilityResolverSysAdminBypassRemovalTest {

    @Mock private ScheduleKeepRepository scheduleKeepRepository;
    @Mock private MembershipBatchQueryService membershipBatchQueryService;
    @InjectMocks private ScheduleKeepVisibilityResolver resolver;

    private static final UUID KEEP_ID = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final Long TEAM_ID = 100L;
    private static final Long OWNER = 1L;
    private static final Long SYSADMIN = 900L;

    private record Keep(Long getTeamId, Long getOrganizationId, Long getUserId) implements ScheduleKeepVisibilityProjection {
        @Override public UUID getId() { return KEEP_ID; }
        @Override public Long getCreatedBy() { return OWNER; }
        @Override public ScheduleKeepStatus getStatus() { return ScheduleKeepStatus.KEPT; }
    }

    private void keep(Keep k) {
        when(scheduleKeepRepository.findVisibilityProjectionsByIdIn(any())).thenReturn(List.of(k));
    }

    private void snapshot(UserScopeRoleSnapshot s) {
        when(membershipBatchQueryService.snapshotForUser(any(), any(), any())).thenReturn(s);
    }

    private static UserScopeRoleSnapshot platformAccount() {
        return new UserScopeRoleSnapshot(true, Map.of(), Map.of(), Set.of(), Set.of());
    }

    @Test
    @DisplayName("チームのキープ: 運営アカウント（所属なし）は見えない")
    void teamKeep_platformAccount_denied() {
        keep(new Keep(TEAM_ID, null, null));
        snapshot(platformAccount());
        assertThat(resolver.canViewUuid(KEEP_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("他人の個人キープ: 運営アカウントは見えない")
    void personalKeep_platformAccount_denied() {
        keep(new Keep(null, null, OWNER));
        snapshot(platformAccount());
        assertThat(resolver.canViewUuid(KEEP_ID, SYSADMIN)).isFalse();
    }

    @Test
    @DisplayName("回帰: チームの MEMBER は見え、SUPPORTER は見えない")
    void teamKeep_memberOnly_regression() {
        keep(new Keep(TEAM_ID, null, null));
        snapshot(new UserScopeRoleSnapshot(false, Map.of(new ScopeKey("TEAM", TEAM_ID), "MEMBER"),
                Map.of(), Set.of(), Set.of()));
        assertThat(resolver.canViewUuid(KEEP_ID, 2L)).isTrue();
        snapshot(new UserScopeRoleSnapshot(false, Map.of(new ScopeKey("TEAM", TEAM_ID), "SUPPORTER"),
                Map.of(), Set.of(), Set.of()));
        assertThat(resolver.canViewUuid(KEEP_ID, 2L)).isFalse();
    }

    @Test
    @DisplayName("回帰: 個人キープは所有者本人だけが見える")
    void personalKeep_ownerOnly_regression() {
        keep(new Keep(null, null, OWNER));
        snapshot(UserScopeRoleSnapshot.empty());
        assertThat(resolver.canViewUuid(KEEP_ID, OWNER)).isTrue();
        assertThat(resolver.canViewUuid(KEEP_ID, 2L)).isFalse();
    }
}
