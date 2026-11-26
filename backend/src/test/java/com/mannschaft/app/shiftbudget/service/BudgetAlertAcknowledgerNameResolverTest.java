package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.NameResolverService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BudgetAlertAcknowledgerNameResolver 単体テスト")
class BudgetAlertAcknowledgerNameResolverTest {

    private static final Long ORG_ID = 100L;
    private static final Long USER_ID = 7L;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private BudgetAlertAcknowledgerNameResolver resolver;

    @Test
    @DisplayName("resolve: この組織のメンバーなら氏名を返す")
    void resolve_メンバーは氏名() {
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(nameResolverService.resolveUserFullNames(List.of(USER_ID))).willReturn(Map.of(USER_ID, "山田 太郎"));

        assertThat(resolver.resolve(USER_ID, ORG_ID)).isEqualTo("山田 太郎");
    }

    @Test
    @DisplayName("resolve: 組織外の確認者（組織外 SYSTEM_ADMIN 等）は null で、氏名を引かない")
    void resolve_組織外はnullで氏名を引かない() {
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

        assertThat(resolver.resolve(USER_ID, ORG_ID)).isNull();
        verify(nameResolverService, never()).resolveUserFullNames(any());
    }

    @Test
    @DisplayName("resolve: 確認者が不明（null）なら null")
    void resolve_確認者不明はnull() {
        assertThat(resolver.resolve(null, ORG_ID)).isNull();
        verify(accessControlService, never()).isMember(any(), any(), any());
    }
}
