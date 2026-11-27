package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.MemberErrorCode;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.PageStatus;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.PageVisibility;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: {@link TeamPageService} の SYSTEM_ADMIN 直書き素通し
 * （組織の下書きページ・会員限定ページ・非表示プロフィール）を除去する受け入れテスト。
 *
 * <p>分類表の A 行（listPages:92・checkPageViewableOrNotFound:345・isPageAdmin:410）に対応する。
 * 外側の門（{@link MemberSubtabVisibilityService#assertViewable}）はモックで「通過」させている
 * （サブタブが PUBLIC 設定で非会員も入口は通れる状況）。そのうえで運営アカウントは、同じ門を通った
 * <b>非会員と同じ</b>応答（下書き・会員限定は不在と同一の 404 MEMBER_001、一覧は公開済み・PUBLIC のみ）になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: TeamPageService の SYSTEM_ADMIN 直書き素通しの除去")
class TeamPageServiceZc1DirectBypassRemovalTest {

    @Mock private TeamPageRepository pageRepository;
    @Mock private TeamPageSectionRepository sectionRepository;
    @Mock private MemberProfileRepository profileRepository;
    @Mock private MemberMapper memberMapper;
    @Mock private AccessControlService accessControlService;
    @Mock private MemberSubtabVisibilityService memberSubtabVisibilityService;
    @InjectMocks private TeamPageService service;

    private static final Long ORG_ID = 700L;
    private static final Long PLATFORM = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long ORG_ADMIN = 902L;

    @BeforeEach
    void setUp() {
        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(OUTSIDER)).willReturn(false);
        given(accessControlService.isSystemAdmin(ORG_ADMIN)).willReturn(false);
        given(accessControlService.isAdminOrAbove(any(), anyLong(), anyString()))
                .willAnswer(inv -> ORG_ADMIN.equals(inv.getArgument(0)));
        given(accessControlService.hasRoleOrAbove(any(), anyLong(), anyString(), anyString()))
                .willAnswer(inv -> ORG_ADMIN.equals(inv.getArgument(0)));
        given(accessControlService.isMember(any(), anyLong(), anyString()))
                .willAnswer(inv -> ORG_ADMIN.equals(inv.getArgument(0)));
    }

    private static TeamPageEntity orgPage(PageStatus status, PageVisibility visibility) {
        return TeamPageEntity.builder()
                .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                .status(status).visibility(visibility).build();
    }

    @Test
    @DisplayName("A(checkPageViewableOrNotFound): 組織の下書きページは運営アカウントに不在と同一の 404 MEMBER_001（非会員と同じ）")
    void draftPage_platformIsConcealed() {
        TeamPageEntity draft = orgPage(PageStatus.DRAFT, PageVisibility.PUBLIC);

        assertThatThrownBy(() -> service.checkPageViewableOrNotFound(OUTSIDER, draft))
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(MemberErrorCode.PAGE_NOT_FOUND));
        assertThatThrownBy(() -> service.checkPageViewableOrNotFound(PLATFORM, draft))
                .as("運営アカウントが組織の下書きページを読めてはならない")
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(MemberErrorCode.PAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("A(checkPageViewableOrNotFound): 公開済みの会員限定ページも運営アカウントに 404 MEMBER_001")
    void membersOnlyPage_platformIsConcealed() {
        TeamPageEntity membersOnly = orgPage(PageStatus.PUBLISHED, PageVisibility.MEMBERS_ONLY);

        assertThatThrownBy(() -> service.checkPageViewableOrNotFound(PLATFORM, membersOnly))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(MemberErrorCode.PAGE_NOT_FOUND));
    }

    @Test
    @DisplayName("A(isPageAdmin): 所属の無い運営アカウントはページ管理者扱いにならない（非表示プロフィールが見えない）")
    void isPageAdmin_platformIsFalse() {
        TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);

        assertThat(service.isPageAdmin(OUTSIDER, page)).isFalse();
        assertThat(service.isPageAdmin(PLATFORM, page)).as("運営アカウントは非会員と同じ").isFalse();
    }

    @Test
    @DisplayName("A(listPages): 組織のページ一覧は運営アカウントに公開済み・PUBLIC のものだけ（非会員と同じ枝）。下書き込みの全件取得をしない")
    void listPages_platformGetsNonMemberBranch() {
        Pageable pageable = PageRequest.of(0, 10);
        given(pageRepository.findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(
                ORG_ID, PageStatus.PUBLISHED, PageVisibility.PUBLIC, pageable)).willReturn(new PageImpl<>(List.of()));
        given(pageRepository.findByOrganizationIdOrderBySortOrder(ORG_ID, pageable)).willReturn(new PageImpl<>(List.of()));

        service.listPages(PLATFORM, null, ORG_ID, pageable);

        verify(pageRepository, never()).findByOrganizationIdOrderBySortOrder(anyLong(), any());
        verify(pageRepository, never()).findByOrganizationIdAndStatusOrderBySortOrder(anyLong(), any(), any());
        verify(pageRepository).findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(
                ORG_ID, PageStatus.PUBLISHED, PageVisibility.PUBLIC, pageable);
    }

    @Test
    @DisplayName("回帰: 組織 ADMIN は下書きも閲覧でき、isPageAdmin=true、一覧は全件")
    void regression_orgAdmin() {
        Pageable pageable = PageRequest.of(0, 10);
        given(pageRepository.findByOrganizationIdOrderBySortOrder(ORG_ID, pageable)).willReturn(new PageImpl<>(List.of()));

        assertThatCode(() -> service.checkPageViewableOrNotFound(ORG_ADMIN, orgPage(PageStatus.DRAFT, PageVisibility.MEMBERS_ONLY)))
                .doesNotThrowAnyException();
        assertThat(service.isPageAdmin(ORG_ADMIN, orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC))).isTrue();
        service.listPages(ORG_ADMIN, null, ORG_ID, pageable);
        verify(pageRepository).findByOrganizationIdOrderBySortOrder(ORG_ID, pageable);
    }

    @Test
    @DisplayName("回帰: 公開済み・PUBLIC のページは、外側の門を通った非会員と運営アカウントの双方が閲覧できる（不在にはならない）")
    void regression_publicPage_visibleToNonMembers() {
        TeamPageEntity published = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);

        assertThatCode(() -> service.checkPageViewableOrNotFound(OUTSIDER, published)).doesNotThrowAnyException();
        assertThatCode(() -> service.checkPageViewableOrNotFound(PLATFORM, published)).doesNotThrowAnyException();
    }
}
