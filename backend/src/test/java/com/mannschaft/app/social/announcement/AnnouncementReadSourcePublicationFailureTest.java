package com.mannschaft.app.social.announcement;

import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.payment.service.PaymentGateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261122-1300 AC7: 元（アンケート・回覧板）の公開状態の判定障害時、既読の書き込みを 1 行もしない
 * （read-batch・単件既読）。判定を公開扱いに倒して書き込みへ進まないことを固定する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("既読: 元の公開状態の判定障害では書き込まない（CMP-261122-1300 AC7）")
class AnnouncementReadSourcePublicationFailureTest {

    private static final Long ORG_ID = 31L;
    private static final Long TEAM_ID = 32L;
    private static final Long USER_ID = 9L;

    @Mock private AnnouncementFeedRepository feedRepository;
    @Mock private AnnouncementFeedQueryRepository feedQueryRepository;
    @Mock private AnnouncementFeedService feedService;
    @Mock private AnnouncementReadService readService;
    @Mock private OrgAnnouncementAudienceAccess audienceAccess;
    @Mock private RoleResolver roleResolver;
    @Mock private PaymentGateService paymentGateService;
    @Mock private AnnouncementReadAllCursorStore cursorStore;
    @Mock private Clock clock;
    @Mock private AnnouncementSourcePublicationGuard sourcePublication;
    @InjectMocks private AnnouncementReadCommandService commandService;

    private static AnnouncementFeedEntity feed(AnnouncementScopeType scopeType, Long scopeId,
                                               AnnouncementSourceType type, Long sourceId) {
        return AnnouncementFeedEntity.builder().scopeType(scopeType).scopeId(scopeId).sourceType(type)
                .sourceId(sourceId).authorId(USER_ID).titleCache("t")
                .visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE).build();
    }

    @Test
    @DisplayName("AC7: read-batch（組織）で一括判定が失敗したら伝播し、既読を部分書き込みしない")
    void readBatch_failure_writesNothing() {
        given(feedRepository.findAllById(any())).willReturn(List.of(
                feed(AnnouncementScopeType.ORGANIZATION, ORG_ID, AnnouncementSourceType.SURVEY, 1L),
                feed(AnnouncementScopeType.ORGANIZATION, ORG_ID, AnnouncementSourceType.CIRCULATION_DOCUMENT, 2L)));
        given(sourcePublication.retainPublished(any(), any(), any()))
                .willThrow(new IllegalStateException("判定障害"));

        assertThatThrownBy(() -> commandService.markBatch(ORG_ID, List.of(10L, 11L), USER_ID))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(readService, feedService);
    }

    @Test
    @DisplayName("AC7: 単件既読で判定が失敗したら伝播し、既読を書き込まない")
    void singleRead_failure_writesNothing() {
        given(feedRepository.findById(10L)).willReturn(Optional.of(
                feed(AnnouncementScopeType.TEAM, TEAM_ID, AnnouncementSourceType.SURVEY, 1L)));
        given(sourcePublication.isPublished(AnnouncementSourceType.SURVEY, 1L))
                .willThrow(new IllegalStateException("判定障害"));

        assertThatThrownBy(() -> commandService.markAsRead(AnnouncementScopeType.TEAM, TEAM_ID, 10L, USER_ID))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(readService, feedService);
    }
}
