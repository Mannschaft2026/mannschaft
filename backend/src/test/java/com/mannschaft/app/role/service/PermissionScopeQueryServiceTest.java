package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionScopeQueryService 単体テスト")
class PermissionScopeQueryServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private PermissionScopeQueryService service;

    @Test
    @DisplayName("existsByUserIdAndTeamId: UserRoleRepository の判定をそのまま委譲する")
    void existsByUserIdAndTeamId_delegates() {
        given(userRoleRepository.existsByUserIdAndTeamId(1L, 2L)).willReturn(true);
        given(userRoleRepository.existsByUserIdAndTeamId(1L, 3L)).willReturn(false);

        assertThat(service.existsByUserIdAndTeamId(1L, 2L)).isTrue();
        assertThat(service.existsByUserIdAndTeamId(1L, 3L)).isFalse();
    }
}
