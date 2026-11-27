package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.service.RecruitmentListingService.ListingAccessScope;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 戦役 Z-C 第1陣（試練・CMP-261127-1709）: {@link RecruitmentListingFacade#getListing} の下書き閲覧判定
 * （requireDraftViewer）で、運営アカウントだけに「存在を知り得る者」の 403 R020 を返す特別扱いを除去する受け入れテスト。
 *
 * <p>分類表の A 行（requireDraftViewer:339・348。分類 A の中でも「存在の開示」型）に対応する。
 * 運営アカウントは下書きの中身には届かないが、403 R020 と 404 R001 の差で<b>他人の下書き募集（PERSONAL を含む）の存在</b>が
 * わかる。除去後は部外者と同じ不在の 404 R001。権限は広がらない（拒否のコードが 403→404 に変わるだけ）。
 * IT 側は RecruitmentListingTemplateScopeContractIT#PERSONAL_DRAFT の「SYSTEM_ADMIN は是正前どおり403 R020」を反転する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Z-C1: 募集の下書き閲覧判定の SYSTEM_ADMIN 特別扱いの除去")
class RecruitmentListingFacadeZc1DraftViewerTest {

    @Mock private RecruitmentListingService listingService;
    @Mock private RecruitmentParticipantService participantService;
    @Mock private RecruitmentTemplateService templateService;
    @Mock private AccessControlService accessControlService;
    @Mock private ContentVisibilityChecker visibilityChecker;

    private RecruitmentListingFacade facade;

    private static final Long LISTING_ID = 100L;
    private static final Long TEAM_ID = 10L;
    private static final Long CREATOR = 2L;
    private static final Long TEAM_MEMBER = 3L;
    private static final Long PLATFORM = 900L;
    private static final Long OUTSIDER = 901L;
    private static final Long PERSONAL_OWNER = 7L;

    @BeforeEach
    void setUp() {
        facade = new RecruitmentListingFacade(listingService, participantService, templateService,
                accessControlService, visibilityChecker);
        given(accessControlService.isSystemAdmin(PLATFORM)).willReturn(true);
        given(accessControlService.isSystemAdmin(OUTSIDER)).willReturn(false);
        given(accessControlService.isSystemAdmin(TEAM_MEMBER)).willReturn(false);
        given(accessControlService.isAdminOrAbove(any(), anyLong(), anyString())).willReturn(false);
        given(accessControlService.isMember(any(), anyLong(), anyString()))
                .willAnswer(inv -> TEAM_MEMBER.equals(inv.getArgument(0)));
        given(listingService.findAuthorizedDraftListing(LISTING_ID))
                .willReturn(Optional.of(mock(RecruitmentListingResponse.class)));
    }

    private static Object outcome(ThrowingCallable call) {
        try {
            call.call();
            return "許可";
        } catch (BusinessException e) {
            return e.getErrorCode();
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    @Test
    @DisplayName("A(requireDraftViewer・PERSONAL): 他人の個人の下書き募集は、運営アカウントに不在と同一の 404 R001（部外者と同じ）")
    void personalDraft_platformIsConcealed() {
        given(listingService.resolveListingScope(LISTING_ID)).willReturn(new ListingAccessScope(
                RecruitmentScopeType.PERSONAL, PERSONAL_OWNER, PERSONAL_OWNER,
                RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY));

        assertThat(outcome(() -> facade.getListing(LISTING_ID, OUTSIDER))).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        assertThat(outcome(() -> facade.getListing(LISTING_ID, PLATFORM)))
                .as("運営アカウントにだけ 403 R020 を返すと、個人の下書きの存在が漏れる")
                .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        assertThat(outcome(() -> facade.getListing(LISTING_ID, PERSONAL_OWNER))).as("回帰: 本人は閲覧可").isEqualTo("許可");
    }

    @Test
    @DisplayName("A(requireDraftViewer・TEAM): チームの下書き募集は、所属の無い運営アカウントに 404 R001（部外者と同じ）。在籍メンバーは従来どおり 403 R020")
    void teamDraft_platformIsConcealed() {
        given(listingService.resolveListingScope(LISTING_ID)).willReturn(new ListingAccessScope(
                RecruitmentScopeType.TEAM, TEAM_ID, CREATOR,
                RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY));

        assertThat(outcome(() -> facade.getListing(LISTING_ID, OUTSIDER))).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        assertThat(outcome(() -> facade.getListing(LISTING_ID, PLATFORM)))
                .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        assertThat(outcome(() -> facade.getListing(LISTING_ID, TEAM_MEMBER)))
                .as("回帰: 在籍メンバーは存在を知り得るので 403 R020 のまま")
                .isEqualTo(RecruitmentErrorCode.DRAFT_VIEW_DENIED);
        assertThat(outcome(() -> facade.getListing(LISTING_ID, CREATOR))).as("回帰: 作成者は閲覧可").isEqualTo("許可");
    }
}
