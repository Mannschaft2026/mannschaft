package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityStatsResponse;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateFieldRepository;
import com.mannschaft.app.activity.repository.ActivityTemplateRepository;
import com.mannschaft.app.activity.service.ActivityStatsService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import org.mockito.Spy;
import org.springframework.dao.DataAccessResourceFailureException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityStatsService 単体テスト")
class ActivityStatsServiceTest {

    @Mock private ActivityResultRepository resultRepository;
    @Mock private ActivityTemplateFieldRepository fieldRepository;
    @Mock private ActivityTemplateRepository templateRepository;
    @Mock private ActivityParticipantRepository participantRepository;

    @InjectMocks
    private ActivityStatsService service;

    @Mock private NameResolverService nameResolverService;
    @Mock private TeamTimezoneResolver teamTimezoneResolver;
    @Spy private Clock clock = Clock.fixed(Instant.parse("2026-11-24T15:30:00Z"), ZoneOffset.UTC);

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    @Nested
    @DisplayName("getStats の既定期間（AC-0159-75。固定クロック）")
    class DefaultRange {

        @Test
        @DisplayName("期間省略時、dateTo はチームのタイムゾーンでの今日（UTC 15:30 は東京で翌日 0:30）、dateFrom は 11 か月前の月初")
        void チームのタイムゾーンの今日() {
            given(teamTimezoneResolver.resolveZone(1L)).willReturn(ZoneId.of("Asia/Tokyo"));
            stubEmpty();

            ActivityStatsResponse result = service.getStats(ActivityScopeType.TEAM, 1L, null, null, null, null, false);

            assertThat(result.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 25));
            assertThat(result.getDateFrom()).isEqualTo(LocalDate.of(2025, 12, 1));
            assertThat(result.getGranularity()).isEqualTo("MONTH");
        }

        @Test
        @DisplayName("同じ瞬間でも UTC より遅いタイムゾーンのチームでは dateTo が前日のまま（日付境界をまたぐ）")
        void 西のタイムゾーンでは前日() {
            given(teamTimezoneResolver.resolveZone(1L)).willReturn(ZoneId.of("America/Los_Angeles"));
            stubEmpty();

            ActivityStatsResponse result = service.getStats(ActivityScopeType.TEAM, 1L, null, null, null, null, false);

            assertThat(result.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(result.getDateFrom()).isEqualTo(LocalDate.of(2025, 12, 1));
        }

        @Test
        @DisplayName("組織スコープは Asia/Tokyo で今日を決める（チームのタイムゾーンを引かない）")
        void 組織は東京() {
            stubEmpty();

            ActivityStatsResponse result = service.getStats(ActivityScopeType.ORGANIZATION, 1L, null, null, null, null, false);

            assertThat(result.getDateTo()).isEqualTo(LocalDate.of(2026, 11, 25));
            org.mockito.Mockito.verifyNoInteractions(teamTimezoneResolver);
        }
    }

    @Nested
    @DisplayName("集計途中の失敗（AC-0159-104）")
    class PartialFailure {

        @Test
        @DisplayName("総数・テンプレート別の問い合わせの失敗は伝播し、部分集計を返さない")
        void 総数の失敗() {
            given(resultRepository.countStatsByTemplate(any(), any(), any(), any(), any()))
                    .willThrow(new DataAccessResourceFailureException("注入した失敗"));
            assertThatThrownBy(() -> service.getStats(ActivityScopeType.TEAM, 1L, null, FROM, TO, null, true))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        @DisplayName("バケット別の問い合わせの失敗は伝播する")
        void バケットの失敗() {
            given(resultRepository.countStatsByTemplate(any(), any(), any(), any(), any())).willReturn(List.of());
            given(resultRepository.countStatsByActivityDate(any(), any(), any(), any(), any()))
                    .willThrow(new DataAccessResourceFailureException("注入した失敗"));
            assertThatThrownBy(() -> service.getStats(ActivityScopeType.TEAM, 1L, null, FROM, TO, null, true))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        @DisplayName("参加者の問い合わせの失敗は空集合に変換せず伝播する（旧実装の握りつぶしの撤去）")
        void 参加者の失敗() {
            given(resultRepository.countStatsByTemplate(any(), any(), any(), any(), any())).willReturn(List.of());
            given(resultRepository.countStatsByActivityDate(any(), any(), any(), any(), any())).willReturn(List.of());
            given(participantRepository.countStatsParticipants(any(), any(), any(), any(), any()))
                    .willThrow(new DataAccessResourceFailureException("注入した失敗"));
            assertThatThrownBy(() -> service.getStats(ActivityScopeType.TEAM, 1L, null, FROM, TO, null, true))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        @DisplayName("上位参加者の問い合わせの失敗は伝播する")
        void 上位の失敗() {
            stubEmpty();
            given(participantRepository.findStatsTopParticipants(any(), any(), any(), any(), any(), any()))
                    .willThrow(new DataAccessResourceFailureException("注入した失敗"));
            assertThatThrownBy(() -> service.getStats(ActivityScopeType.TEAM, 1L, null, FROM, TO, null, true))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }

        @Test
        @DisplayName("名前解決の失敗は伝播する")
        void 名前解決の失敗() {
            stubEmpty();
            java.util.List<Object[]> top = new java.util.ArrayList<>();
            top.add(new Object[]{10L, 3L});
            given(participantRepository.findStatsTopParticipants(any(), any(), any(), any(), any(), any())).willReturn(top);
            given(nameResolverService.resolveUserFullNames(any())).willThrow(new IllegalStateException("注入した失敗"));
            assertThatThrownBy(() -> service.getStats(ActivityScopeType.TEAM, 1L, null, FROM, TO, null, true))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("期間の検証（AC-0159-76・105）")
    class RangeValidation {

        @Test
        @DisplayName("日数 = date_to − date_from + 1。1,096 日は可、1,097 日は ACTIVITY_025、逆転は ACTIVITY_024、同日は可")
        void 期間() {
            LocalDate from = LocalDate.of(2027, 3, 1);
            ActivityStatsService.validateRange(from, from);
            ActivityStatsService.validateRange(from, from.plusDays(1095));
            assertThatThrownBy(() -> ActivityStatsService.validateRange(from, from.plusDays(1096)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("ACTIVITY_025"));
            assertThatThrownBy(() -> ActivityStatsService.validateRange(from, from.minusDays(1)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("ACTIVITY_024"));
        }

        @Test
        @DisplayName("granularity の不正値は COMMON_001")
        void 集計単位の不正値() {
            assertThatThrownBy(() -> service.getStats(ActivityScopeType.TEAM, 1L, null, FROM, TO, "DAY", false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("COMMON_001"));
        }
    }

    private void stubEmpty() {
        given(resultRepository.countStatsByTemplate(any(), any(), any(), any(), any())).willReturn(List.of());
        given(resultRepository.countStatsByActivityDate(any(), any(), any(), any(), any())).willReturn(List.of());
        given(participantRepository.countStatsParticipants(any(), any(), any(), any(), any())).willReturn(List.of());
    }

    @Nested
    @DisplayName("getFieldStats")
    class GetFieldStats {
        @Test
        @DisplayName("異常系: 不正なfield_keyで例外")
        void フィールド統計_不正キー_例外() {
            assertThatThrownBy(() -> service.getFieldStats(ActivityScopeType.TEAM, 1L, 10L, "drop;table", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("異常系: フィールド不在でACTIVITY_002例外")
        void フィールド統計_フィールド不在_例外() {
            given(templateRepository.findById(10L)).willReturn(Optional.of(template(ActivityScopeType.TEAM, 1L)));
            given(fieldRepository.findByTemplateIdAndFieldKey(10L, "valid_key")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFieldStats(ActivityScopeType.TEAM, 1L, 10L, "valid_key", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_002"));
        }

        @Test
        @DisplayName("AC-0159-80: 他スコープのテンプレートは不在と同じ ACTIVITY_002 で、フィールド定義を読まない")
        void フィールド統計_他スコープのテンプレート_不在と同一() {
            given(templateRepository.findById(10L)).willReturn(Optional.of(template(ActivityScopeType.TEAM, 2L)));

            assertThatThrownBy(() -> service.getFieldStats(ActivityScopeType.TEAM, 1L, 10L, "valid_key", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_002"));
            org.mockito.Mockito.verifyNoInteractions(fieldRepository);
        }

        @Test
        @DisplayName("AC-0159-80: 存在しないテンプレートも ACTIVITY_002 で、フィールド定義を読まない")
        void フィールド統計_不在のテンプレート() {
            given(templateRepository.findById(10L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFieldStats(ActivityScopeType.TEAM, 1L, 10L, "valid_key", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_002"));
            org.mockito.Mockito.verifyNoInteractions(fieldRepository);
        }

        private com.mannschaft.app.activity.entity.ActivityTemplateEntity template(ActivityScopeType type, Long scopeId) {
            return com.mannschaft.app.activity.entity.ActivityTemplateEntity.builder().scopeType(type).scopeId(scopeId)
                    .name("型").createdBy(1L).build();
        }
    }
}
