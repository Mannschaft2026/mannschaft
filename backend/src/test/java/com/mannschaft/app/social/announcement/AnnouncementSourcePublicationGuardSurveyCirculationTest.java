package com.mannschaft.app.social.announcement;

import com.mannschaft.app.circulation.service.CirculationPublicationStatusService;
import com.mannschaft.app.cms.service.BlogPostPublicationStatusService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.service.SurveyPublicationStatusService;
import com.mannschaft.app.timeline.service.TimelinePostPublicationStatusService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261122-1300: アンケート（SURVEY）・回覧板（CIRCULATION_DOCUMENT）の元が非公開状態ならお知らせ化しない・
 * 不在と同じ扱いにする（{@link AnnouncementSourcePublicationGuard} の単体・一括判定）。
 *
 * <p>公開状態の定義（何を許すか）は各ドメインの *PublicationStatusService が持つ（アンケート: PUBLISHED / CLOSED、
 * 回覧板: ACTIVE / COMPLETED。AC4）。本テストは Guard が両 Service に正しく委ね、判定障害を公開扱いにしないこと、
 * 一括判定が種別ごとに 1 回ずつしか照会しないこと（AC7）、他種別の挙動が変わらないこと（AC8）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementSourcePublicationGuard: SURVEY・CIRCULATION_DOCUMENT（CMP-261122-1300）")
class AnnouncementSourcePublicationGuardSurveyCirculationTest {

    private static final Long ID = 700L;

    @Mock private BlogPostPublicationStatusService blogStatus;
    @Mock private TimelinePostPublicationStatusService timelineStatus;
    @Mock private SurveyPublicationStatusService surveyStatus;
    @Mock private CirculationPublicationStatusService circulationStatus;
    @InjectMocks private AnnouncementSourcePublicationGuard guard;

    private static void assertNotFound(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode().getCode()).isEqualTo("ANNOUNCE_006"));
    }

    record Row(AnnouncementSourceType type, Long sourceId) { }

    @Nested
    @DisplayName("単体判定（お知らせ化の入口・ID 指定の経路）")
    class Single {

        @Test
        @DisplayName("AC1/AC3: アンケートが公開状態でない（DRAFT・ARCHIVED・論理削除・不在）なら ANNOUNCE_006")
        void surveyNotPublished_rejected() {
            given(surveyStatus.isPublished(ID)).willReturn(false);
            assertNotFound(() -> guard.assertPublished(AnnouncementSourceType.SURVEY, ID));
            verify(surveyStatus).isPublished(ID);
        }

        @Test
        @DisplayName("AC4: アンケートが公開状態（PUBLISHED・CLOSED）なら通る（Survey 側 Service に委ねる）")
        void surveyPublished_ok() {
            given(surveyStatus.isPublished(ID)).willReturn(true);
            assertThatCode(() -> guard.assertPublished(AnnouncementSourceType.SURVEY, ID)).doesNotThrowAnyException();
            verify(surveyStatus).isPublished(ID);
        }

        @Test
        @DisplayName("AC2/AC3: 回覧板が公開状態でない（DRAFT・CANCELLED・論理削除・不在）なら ANNOUNCE_006")
        void circulationNotPublished_rejected() {
            given(circulationStatus.isPublished(ID)).willReturn(false);
            assertNotFound(() -> guard.assertPublished(AnnouncementSourceType.CIRCULATION_DOCUMENT, ID));
            verify(circulationStatus).isPublished(ID);
        }

        @Test
        @DisplayName("AC4: 回覧板が公開状態（ACTIVE・COMPLETED）なら通る（Circulation 側 Service に委ねる）")
        void circulationPublished_ok() {
            given(circulationStatus.isPublished(ID)).willReturn(true);
            assertThatCode(() -> guard.assertPublished(AnnouncementSourceType.CIRCULATION_DOCUMENT, ID))
                    .doesNotThrowAnyException();
            verify(circulationStatus).isPublished(ID);
        }

        @Test
        @DisplayName("AC5: sourceId が null のアンケート・回覧板は公開済みではない（fail-closed）")
        void nullSourceId_notPublished() {
            assertThat(guard.isPublished(AnnouncementSourceType.SURVEY, null)).isFalse();
            assertThat(guard.isPublished(AnnouncementSourceType.CIRCULATION_DOCUMENT, null)).isFalse();
        }

        @Test
        @DisplayName("AC7: 判定障害（Service の例外）は公開扱いにせず、そのまま伝播する")
        void failureIsNotTreatedAsPublished() {
            given(surveyStatus.isPublished(ID)).willThrow(new IllegalStateException("DB 障害"));
            given(circulationStatus.isPublished(ID)).willThrow(new IllegalStateException("DB 障害"));
            assertThatThrownBy(() -> guard.isPublished(AnnouncementSourceType.SURVEY, ID))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> guard.assertPublished(AnnouncementSourceType.CIRCULATION_DOCUMENT, ID))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("AC8: BLOG_POST・TIMELINE_POST は従来どおり各 Service に委ね、アンケート・回覧板には問わない")
        void blogAndTimelineUnchanged() {
            given(blogStatus.isPublished(ID)).willReturn(true);
            given(timelineStatus.isPublished(ID)).willReturn(false);
            assertThat(guard.isPublished(AnnouncementSourceType.BLOG_POST, ID)).isTrue();
            assertThat(guard.isPublished(AnnouncementSourceType.TIMELINE_POST, ID)).isFalse();
            verifyNoInteractions(surveyStatus, circulationStatus);
        }

        @Test
        @DisplayName("AC8: 掲示板・委員会など他のソース種別は従来どおり判定しない（公開済み扱い）")
        void otherTypesUnchanged() {
            for (AnnouncementSourceType type : List.of(AnnouncementSourceType.BULLETIN_THREAD,
                    AnnouncementSourceType.COMMITTEE_DECISION, AnnouncementSourceType.COMMITTEE_MINUTES)) {
                assertThatCode(() -> guard.assertPublished(type, ID)).as(type.name()).doesNotThrowAnyException();
            }
            verifyNoInteractions(blogStatus, timelineStatus, surveyStatus, circulationStatus);
        }
    }

    @Nested
    @DisplayName("一括判定 retainPublished（一覧後段・read-batch）")
    class Batch {

        @Test
        @DisplayName("AC7: 空入力では一切照会しない")
        void emptyInput_noQuery() {
            assertThat(guard.retainPublished(List.<Row>of(), Row::type, Row::sourceId)).isEmpty();
            verifyNoInteractions(blogStatus, timelineStatus, surveyStatus, circulationStatus);
        }

        @Test
        @DisplayName("AC7: アンケート・回覧板を含まない入力では両 Service に照会しない")
        void noSurveyNorCirculation_noQuery() {
            Row bulletin = new Row(AnnouncementSourceType.BULLETIN_THREAD, 1L);
            assertThat(guard.retainPublished(List.of(bulletin), Row::type, Row::sourceId)).containsExactly(bulletin);
            verifyNoInteractions(surveyStatus, circulationStatus);
        }

        @Test
        @DisplayName("AC5/AC7/AC8: 混在・重複 ID でも種別ごとに 1 回ずつ一括照会し、公開状態の元を持つ要素だけを順序どおり残す")
        @SuppressWarnings("unchecked")
        void mixedAndDuplicated_oneQueryPerType() {
            Row surveyOk = new Row(AnnouncementSourceType.SURVEY, 1L);
            Row surveyNg = new Row(AnnouncementSourceType.SURVEY, 2L);
            Row surveyOkDup = new Row(AnnouncementSourceType.SURVEY, 1L);
            Row circOk = new Row(AnnouncementSourceType.CIRCULATION_DOCUMENT, 5L);
            Row circNg = new Row(AnnouncementSourceType.CIRCULATION_DOCUMENT, 6L);
            Row circNgDup = new Row(AnnouncementSourceType.CIRCULATION_DOCUMENT, 6L);
            Row surveyNullId = new Row(AnnouncementSourceType.SURVEY, null);
            Row blogOk = new Row(AnnouncementSourceType.BLOG_POST, 1L);
            Row bulletin = new Row(AnnouncementSourceType.BULLETIN_THREAD, 9L);
            Row nullType = new Row(null, 1L);
            given(surveyStatus.findPublishedIds(any())).willReturn(Set.of(1L));
            given(circulationStatus.findPublishedIds(any())).willReturn(Set.of(5L));
            given(blogStatus.findPublishedIds(any())).willReturn(Set.of(1L));

            List<Row> kept = guard.retainPublished(List.of(surveyOk, surveyNg, surveyOkDup, circOk, circNg, circNgDup,
                    surveyNullId, blogOk, bulletin, nullType), Row::type, Row::sourceId);

            assertThat(kept).containsExactly(surveyOk, surveyOkDup, circOk, blogOk, bulletin);
            ArgumentCaptor<Collection<Long>> surveyIds = ArgumentCaptor.forClass(Collection.class);
            ArgumentCaptor<Collection<Long>> circIds = ArgumentCaptor.forClass(Collection.class);
            verify(surveyStatus, times(1)).findPublishedIds(surveyIds.capture());
            verify(circulationStatus, times(1)).findPublishedIds(circIds.capture());
            assertThat(surveyIds.getValue()).as("重複・null を除いた ID で 1 回だけ").containsExactlyInAnyOrder(1L, 2L);
            assertThat(circIds.getValue()).as("重複を除いた ID で 1 回だけ").containsExactlyInAnyOrder(5L, 6L);
        }

        @Test
        @DisplayName("AC7: 一括判定の障害は公開扱いにせず伝播する（呼び出し側が部分書き込みをしないための前提）")
        void batchFailurePropagates() {
            given(surveyStatus.findPublishedIds(any())).willThrow(new IllegalStateException("DB 障害"));
            List<Row> rows = List.of(new Row(AnnouncementSourceType.SURVEY, 1L));
            assertThatThrownBy(() -> guard.retainPublished(rows, Row::type, Row::sourceId))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("AC7: 回覧板の一括判定の障害も伝播する")
        void circulationBatchFailurePropagates() {
            given(circulationStatus.findPublishedIds(any())).willThrow(new IllegalStateException("DB 障害"));
            List<Row> rows = List.of(new Row(AnnouncementSourceType.CIRCULATION_DOCUMENT, 1L));
            assertThatThrownBy(() -> guard.retainPublished(rows, Row::type, Row::sourceId))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
