package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: {@link MemberSubtabVisibilityService} の SYSTEM_ADMIN 直書き素通し
 * （サブタブの外側の門の貫通・公開設定の書換）を除去する受け入れテスト。
 *
 * <p>分類表の A 行（updateSettings:120・assertViewable:162・checkUpdatePermission:283・
 * resolveViewerMinRole:194 の到達しない枝）に対応する。運営がテナントの公開設定を書き換える使い方は廃止
 * （マスター決定 2026-11-26。チーム・組織の管理者が行う）。運営アカウントは所属の無い一般利用者と同じ応答になる。</p>
 *
 * <p>B（拒否型）の取り違えに注意: updateSettings の {@code if (!isSystemAdmin) { checkMembership; checkUpdatePermission }}
 * は「囲いの if だけ」を外し、中の 2 判定は残すこと（ブロックごと消すと全員に書換を許す）。下の回帰テストで固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: MemberSubtabVisibilityService の SYSTEM_ADMIN 直書き素通しの除去")
class MemberSubtabVisibilityServiceZc1DirectBypassRemovalTest {

    @Mock private MemberSubtabRoleVisibilityRepository repository;
    @Mock private AccessControlService accessControlService;
    @Mock private NameResolverService nameResolverService;
    @Mock private MemberSubtabVisibilityWriter writer;

    private MemberSubtabVisibilityService service;

    private static final Long ORG_ID = 200L;
    private static final Long PLATFORM = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long ORG_MEMBER = 902L;

    @BeforeEach
    void setUp() {
        service = new MemberSubtabVisibilityService(repository, accessControlService, nameResolverService, writer);
        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(OUTSIDER)).willReturn(false);
        given(accessControlService.isSystemAdmin(ORG_MEMBER)).willReturn(false);
        given(accessControlService.isAdminOrAbove(any(), anyLong(), anyString())).willReturn(false);
        given(accessControlService.isAdmin(any(), anyLong(), anyString())).willReturn(false);
        // 所属の無い者（運営アカウント・部外者）は checkMembership で 403 COMMON_002。
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(PLATFORM, ORG_ID, "ORGANIZATION");
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkMembership(OUTSIDER, ORG_ID, "ORGANIZATION");
        // 一般 MEMBER は所属するが MEMBER_SUBTAB_VISIBILITY_MANAGE を持たない。
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkPermission(ORG_MEMBER, ORG_ID, "ORGANIZATION",
                        MemberSubtabVisibilityService.PERMISSION_NAME);
        given(accessControlService.getRoleName(ORG_MEMBER, ORG_ID, "ORGANIZATION")).willReturn("MEMBER");
        given(accessControlService.getRoleName(PLATFORM, ORG_ID, "ORGANIZATION")).willReturn(null);
        given(accessControlService.getRoleName(OUTSIDER, ORG_ID, "ORGANIZATION")).willReturn(null);
        given(repository.findByScopeTypeAndScopeIdAndSubtabKey(any(), anyLong(), anyString())).willReturn(Optional.empty());
        given(repository.findByScopeTypeAndScopeId(any(), anyLong())).willReturn(List.of());
        // 書き込みが走った場合も応答を組み立てられるようにする（赤の理由を NPE ではなく「書換が通った」にするため）。
        given(writer.applyUpdates(any(), any(), any(), any())).willReturn(MemberSubtabVisibilitySnapshot.of(List.of()));
    }

    private static UpdateMemberSubtabVisibilityRequest request() {
        UpdateMemberSubtabVisibilityRequest request = new UpdateMemberSubtabVisibilityRequest();
        UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem item =
                new UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem();
        item.setSubtabKey("member_profiles");
        item.setMinRole(MinRole.PUBLIC);
        request.setSubtabs(List.of(item));
        return request;
    }

    @Test
    @DisplayName("A(updateSettings): 所属の無い運営アカウントは公開設定を書き換えられず 403 COMMON_002（部外者と同じ）。writer は呼ばれない")
    void updateSettings_platformIsDenied() {
        assertThatThrownBy(() -> service.updateSettings(OUTSIDER, ScopeType.ORGANIZATION, ORG_ID, request()))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));

        assertThatThrownBy(() -> service.updateSettings(PLATFORM, ScopeType.ORGANIZATION, ORG_ID, request()))
                .as("運営アカウントがテナントのサブタブ公開設定を書き換えてはならない（マスター決定 2026-11-26）")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        verify(writer, never()).applyUpdates(any(), any(), any(), any());
    }

    @Test
    @DisplayName("A(assertViewable): 既定（MEMBER 以上）のサブタブは、ロールの無い運営アカウントに 403 COMMON_002（部外者と同じ）")
    void assertViewable_platformIsDeniedLikeOutsider() {
        assertThatThrownBy(() -> service.assertViewable(OUTSIDER, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));

        assertThatThrownBy(() -> service.assertViewable(PLATFORM, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST))
                .as("運営アカウントがメンバー一覧などのサブタブの外側の門を貫通してはならない")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
    }

    @Test
    @DisplayName("回帰（B の取り違え防止）: 所属する一般 MEMBER は権限が無ければ書換できず 403、writer は呼ばれない")
    void regression_memberWithoutPermissionIsStillDenied() {
        assertThatThrownBy(() -> service.updateSettings(ORG_MEMBER, ScopeType.ORGANIZATION, ORG_ID, request()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_002));
        verify(accessControlService).checkMembership(ORG_MEMBER, ORG_ID, "ORGANIZATION");
        verify(writer, never()).applyUpdates(any(), any(), any(), any());
    }

    @Test
    @DisplayName("回帰: 所属する一般 MEMBER は既定（MEMBER 以上）のサブタブを閲覧できる")
    void regression_memberCanView() {
        assertThatCode(() -> service.assertViewable(ORG_MEMBER, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST))
                .doesNotThrowAnyException();
    }
}
