package com.mannschaft.app.survey.service;

import com.mannschaft.app.survey.repository.SurveyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261122-1300: アンケートの公開状態判定 Service の入力境界（AC5・AC7）。
 *
 * <p>状態ごとの可否（実 DB）は {@code AnnouncementSurveyCirculationSourcePublicationIT} が固定する。
 * ここでは空入力・null で照会しない（fail-closed かつ件数比例の照会を作らない）ことだけを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyPublicationStatusService（CMP-261122-1300）")
class SurveyPublicationStatusServiceTest {

    @Mock private SurveyRepository repository;
    @InjectMocks private SurveyPublicationStatusService service;

    @Test
    @DisplayName("AC7: 空入力の一括判定は照会せず空集合を返す")
    void emptyInput_noQuery() {
        assertThat(service.findPublishedIds(List.of())).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("AC7: null 入力の一括判定は照会せず空集合を返す")
    void nullInput_noQuery() {
        assertThat(service.findPublishedIds(null)).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("AC5: ID が null の単体判定は照会せず公開済みではない（fail-closed）")
    void nullId_notPublished() {
        assertThat(service.isPublished(null)).isFalse();
        verifyNoInteractions(repository);
    }
}
