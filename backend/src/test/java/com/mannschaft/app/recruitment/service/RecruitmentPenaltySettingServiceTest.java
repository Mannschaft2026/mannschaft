package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.recruitment.PenaltyApplyScope;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261005-2103 I4: ペナルティ設定（penalty-settings 画面の取得・保存）は、
 * 他テナントのスコープ（自分が ADMIN/DEPUTY_ADMIN でないチーム・組織）を指定されたら拒否し、
 * 設定を読みも書きもしないことを固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentPenaltySettingService 他テナント拒否（CMP-261005-2103 I4）")
class RecruitmentPenaltySettingServiceTest {

    @Mock
    private RecruitmentPenaltySettingRepository settingRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private RecruitmentPenaltySettingService service;

    private static final Long OUTSIDER_USER_ID = 900L;
    private static final Long OTHER_TENANT_SCOPE_ID = 77L;

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RecruitmentScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @DisplayName("I4: 他テナントのスコープの設定取得は403で拒否し、設定を読まない")
    void 他テナントの設定取得は拒否(RecruitmentScopeType scopeType) {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService)
                .checkAdminOrAbove(OUTSIDER_USER_ID, OTHER_TENANT_SCOPE_ID, scopeType.name());

        assertThatThrownBy(() -> service.getSetting(scopeType, OTHER_TENANT_SCOPE_ID, OUTSIDER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
        verify(accessControlService).checkAdminOrAbove(OUTSIDER_USER_ID, OTHER_TENANT_SCOPE_ID, scopeType.name());
        verifyNoInteractions(settingRepository);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = RecruitmentScopeType.class, names = {"TEAM", "ORGANIZATION"})
    @DisplayName("I4: 他テナントのスコープの設定保存は403で拒否し、設定を作りも書き換えもしない")
    void 他テナントの設定保存は拒否(RecruitmentScopeType scopeType) {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService)
                .checkAdminOrAbove(OUTSIDER_USER_ID, OTHER_TENANT_SCOPE_ID, scopeType.name());

        assertThatThrownBy(() -> service.upsertSetting(scopeType, OTHER_TENANT_SCOPE_ID, OUTSIDER_USER_ID,
                true, 3, 90, 30, PenaltyApplyScope.values()[0], false, 7))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
        verifyNoInteractions(settingRepository);
    }
}
