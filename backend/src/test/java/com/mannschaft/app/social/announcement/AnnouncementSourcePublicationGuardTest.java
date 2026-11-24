package com.mannschaft.app.social.announcement;

import com.mannschaft.app.cms.service.BlogPostPublicationStatusService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.service.TimelinePostPublicationStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * N9: 元コンテンツが公開状態でないものはお知らせ化できない。拒否は不在と同じ ANNOUNCE_006（存在オラクル封じ）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementSourcePublicationGuard（N9）")
class AnnouncementSourcePublicationGuardTest {

    private static final Long ID = 500L;

    @Mock private BlogPostPublicationStatusService blogStatus;
    @Mock private TimelinePostPublicationStatusService timelineStatus;
    @InjectMocks private AnnouncementSourcePublicationGuard guard;

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("ANNOUNCE_006"));
    }

    @Test
    @DisplayName("ブログ: 公開済みは通る")
    void blogPublishedOk() {
        given(blogStatus.isPublished(ID)).willReturn(true);
        assertThatCode(() -> guard.assertPublished(AnnouncementSourceType.BLOG_POST, ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ブログ: 下書き・予約公開・承認待ち・アーカイブ（公開済みでない）・不在は ANNOUNCE_006")
    void blogNotPublishedRejected() {
        given(blogStatus.isPublished(ID)).willReturn(false);
        assertNotFound(() -> guard.assertPublished(AnnouncementSourceType.BLOG_POST, ID));
    }

    @Test
    @DisplayName("タイムライン: 公開中は通る・それ以外は ANNOUNCE_006")
    void timeline() {
        given(timelineStatus.isPublished(ID)).willReturn(true, false);
        assertThatCode(() -> guard.assertPublished(AnnouncementSourceType.TIMELINE_POST, ID))
                .doesNotThrowAnyException();
        assertNotFound(() -> guard.assertPublished(AnnouncementSourceType.TIMELINE_POST, ID));
    }

    @Test
    @DisplayName("他のソース種別は判定しない（従来どおり）")
    void otherTypesUntouched() {
        assertThatCode(() -> guard.assertPublished(AnnouncementSourceType.SURVEY, ID)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ソース種別 null は公開済みではない（fail-closed）")
    void nullTypeIsNotPublished() {
        assertThat(guard.isPublished(null, ID)).isFalse();
    }

    @Test
    @DisplayName("一括判定: 種別ごとに 1 回ずつ問い合わせ、公開済みの元を持つ要素だけを順序どおり残す")
    void retainPublished() {
        record Row(AnnouncementSourceType type, Long sourceId) { }
        Row blogOk = new Row(AnnouncementSourceType.BLOG_POST, 1L);
        Row blogNg = new Row(AnnouncementSourceType.BLOG_POST, 2L);
        Row tlOk = new Row(AnnouncementSourceType.TIMELINE_POST, 1L);
        Row tlNg = new Row(AnnouncementSourceType.TIMELINE_POST, 3L);
        Row survey = new Row(AnnouncementSourceType.SURVEY, 9L);
        Row nullType = new Row(null, 1L);
        given(blogStatus.findPublishedIds(java.util.List.of(1L, 2L))).willReturn(java.util.Set.of(1L));
        given(timelineStatus.findPublishedIds(java.util.List.of(1L, 3L))).willReturn(java.util.Set.of(1L));

        assertThat(guard.retainPublished(java.util.List.of(blogOk, blogNg, tlOk, tlNg, survey, nullType),
                Row::type, Row::sourceId)).containsExactly(blogOk, tlOk, survey);
    }
}
