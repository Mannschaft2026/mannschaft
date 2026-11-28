package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogSeriesResponse;
import com.mannschaft.app.cms.dto.CreateSeriesRequest;
import com.mannschaft.app.cms.dto.UpdateSeriesRequest;
import com.mannschaft.app.cms.entity.BlogPostSeriesEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostSeriesRepository;
import com.mannschaft.app.cms.service.BlogSeriesService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ScopeContentAccessGate;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlogSeriesService 単体テスト")
class BlogSeriesServiceTest {

    @Mock
    private BlogPostSeriesRepository seriesRepository;
    @Mock
    private BlogPostRepository postRepository;
    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ScopeContentAccessGate scopeContentAccessGate;
    @InjectMocks
    private BlogSeriesService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long SERIES_ID = 10L;

    @Nested
    @DisplayName("createSeries")
    class CreateSeries {
        @Test
        @DisplayName("正常系: シリーズが作成される（checkMembershipのみ・ADMIN不要）")
        void 作成_正常_シリーズ保存() {
            CreateSeriesRequest request = new CreateSeriesRequest(TEAM_ID, null, "連載シリーズ", null);
            BlogPostSeriesEntity saved = BlogPostSeriesEntity.builder()
                    .teamId(TEAM_ID).name("連載シリーズ").createdBy(USER_ID).build();
            given(seriesRepository.save(any())).willReturn(saved);

            BlogSeriesResponse result = service.createSeries(USER_ID, request);
            assertThat(result).isNotNull();
            verify(scopeContentAccessGate).checkMembershipForWrite(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非メンバーの作成は403(COMMON_002)")
        void 作成_非メンバー_例外() {
            CreateSeriesRequest request = new CreateSeriesRequest(TEAM_ID, null, "連載シリーズ", null);
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scopeContentAccessGate).checkMembershipForWrite(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.createSeries(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(seriesRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateSeries")
    class UpdateSeries {
        @Test
        @DisplayName("異常系: シリーズ不在でCMS_003例外")
        void 更新_シリーズ不在_例外() {
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateSeries(SERIES_ID, USER_ID, new UpdateSeriesRequest(null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_003"));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非ADMINの更新は403(COMMON_002)")
        void 更新_非ADMIN_例外() {
            BlogPostSeriesEntity entity = BlogPostSeriesEntity.builder().teamId(TEAM_ID).name("更新前").build();
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.of(entity));
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.updateSeries(SERIES_ID, USER_ID, new UpdateSeriesRequest("改題", null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(seriesRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteSeries")
    class DeleteSeries {
        @Test
        @DisplayName("正常系: シリーズが物理削除される")
        void 削除_正常_物理削除() {
            BlogPostSeriesEntity entity = BlogPostSeriesEntity.builder().teamId(TEAM_ID).name("削除用").build();
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.of(entity));
            service.deleteSeries(SERIES_ID, USER_ID);
            verify(seriesRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: シリーズ不在でCMS_003例外")
        void 削除_シリーズ不在_例外() {
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteSeries(SERIES_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_003"));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非ADMINの削除は403(COMMON_002)")
        void 削除_非ADMIN_例外() {
            BlogPostSeriesEntity entity = BlogPostSeriesEntity.builder().teamId(TEAM_ID).name("削除対象").build();
            given(seriesRepository.findById(SERIES_ID)).willReturn(Optional.of(entity));
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.deleteSeries(SERIES_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(seriesRepository, org.mockito.Mockito.never()).delete(any());
        }
    }
    @Nested
    @DisplayName("listSeries（CMP-261124-0159 AC-0159-15: チーム公開範囲の上限）")
    class ListSeriesUnderMembersOnlyCap {

        @Test
        @DisplayName("AC-0159-15: 非公開チームで上限を越える在籍者（門が拒否）は403(COMMON_002)で、連載を読まない")
        void 一覧_上限超過は門が拒否して連載を読まない() {
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scopeContentAccessGate).checkScopeContentAccess(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.listSeries(USER_ID, TEAM_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(seriesRepository, org.mockito.Mockito.never()).findByTeamIdOrderByCreatedAtDesc(any());
        }

        @Test
        @DisplayName("AC-0159-15: 門を通った利用者には連載を返す（読み取りは checkMembership を直接呼ばず門で判定）")
        void 一覧_門を通れば連載を返す() {
            given(seriesRepository.findByTeamIdOrderByCreatedAtDesc(TEAM_ID)).willReturn(java.util.List.of());

            assertThat(service.listSeries(USER_ID, TEAM_ID, null)).isEmpty();

            verify(scopeContentAccessGate).checkScopeContentAccess(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, org.mockito.Mockito.never()).checkMembership(any(), any(), any());
        }
    }

}
