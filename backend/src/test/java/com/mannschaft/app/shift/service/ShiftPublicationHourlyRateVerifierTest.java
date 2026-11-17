package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftAssignedUserIds;
import com.mannschaft.app.shift.ShiftHourlyRateMissingException;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261115-1821 公開 TX 内の時給検証（{@link ShiftPublicationHourlyRateVerifier}）の判定表。
 *
 * <p>G07（context 範囲外・空→非空は 409、範囲内の別日・割当変更は再読込で判定）、G03（effectiveFrom 境界）、
 * G04（空・非予算・破損 JSON・非正数）、G05（候補の採否）、G12（時給 SELECT は 1 回）を値で固定する。
 * 実 DB・HTTP 経路は {@code ShiftPublicationHourlyRateContractIT} が受け持つ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261115-1821 公開時給検証の判定表")
class ShiftPublicationHourlyRateVerifierTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 100L;
    private static final Long ORG_2 = 200L;
    private static final LocalDate JULY_1 = LocalDate.of(2026, 7, 1);
    private static final LocalDate JULY_31 = LocalDate.of(2026, 7, 31);
    private static final LocalDate D15 = LocalDate.of(2026, 7, 15);
    private static final LocalDate D16 = LocalDate.of(2026, 7, 16);

    @Mock
    private ShiftHourlyRateRepository rateRepository;

    @InjectMocks
    private ShiftPublicationHourlyRateVerifier verifier;

    private static ShiftPublicationBudgetContext linkedJuly() {
        return new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(new ShiftPublicationBudgetContext.AllocationCandidate(ORG_ID, true, JULY_1, JULY_31)),
                Map.of(ORG_ID, true));
    }

    private static ShiftSlotEntity slot(LocalDate date, Long... userIds) {
        return ShiftSlotEntity.builder().scheduleId(1L).slotDate(date)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1)
                .assignedUserIds(ShiftAssignedUserIds.serialize(List.of(userIds))).build();
    }

    private static ShiftHourlyRateEntity rate(Long userId, String amount, LocalDate from) {
        return ShiftHourlyRateEntity.builder().userId(userId).teamId(TEAM_ID)
                .hourlyRate(amount == null ? null : new BigDecimal(amount)).effectiveFrom(from).build();
    }

    private static List<ShiftHourlyRateMissingException.Missing> missingOf(Throwable thrown) {
        assertThat(thrown).isInstanceOf(ShiftHourlyRateMissingException.class);
        return ((ShiftHourlyRateMissingException) thrown).getMissingHourlyRates();
    }

    @Test
    @DisplayName("G07: 事前に枠が無かった context で枠が現れていれば 409 COMMON_003（時給を引かない）")
    void 空から非空は409() {
        Throwable thrown = catchThrowable(() -> verifier.verify(TEAM_ID, List.of(slot(D15, 1L)),
                ShiftPublicationBudgetContext.noSlots()));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_003);
        verifyNoInteractions(rateRepository);
    }

    @Test
    @DisplayName("G07: context の範囲外の枠日付があれば 409 COMMON_003（親期間内と仮定しない）")
    void 範囲外は409() {
        Throwable thrown = catchThrowable(() -> verifier.verify(TEAM_ID,
                List.of(slot(D15, 1L), slot(LocalDate.of(2026, 8, 1), 1L)), linkedJuly()));

        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(CommonErrorCode.COMMON_003);
        verifyNoInteractions(rateRepository);
    }

    @Test
    @DisplayName("G07: 範囲内の別日・割当変更は、読み直した枠で判定する（取得時と違う日・人でも範囲内なら判定可）")
    void 範囲内の別日と割当変更は読み直しで判定() {
        given(rateRepository.findRateHistoryForPeriod(TEAM_ID, List.of(2L), D16, D16))
                .willReturn(List.of());

        List<ShiftHourlyRateMissingException.Missing> missing = missingOf(catchThrowable(() ->
                verifier.verify(TEAM_ID, List.of(slot(D16, 2L)), linkedJuly())));

        assertThat(missing).containsExactly(new ShiftHourlyRateMissingException.Missing(2L, D16));
    }

    @Test
    @DisplayName("G04: 枠なし・割当なし・非予算は検証せず通す")
    void 空と非予算は通す() {
        assertThatCode(() -> verifier.verify(TEAM_ID, List.of(), ShiftPublicationBudgetContext.noSlots()))
                .doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify(TEAM_ID, List.of(slot(D15)), linkedJuly()))
                .doesNotThrowAnyException();
        ShiftPublicationBudgetContext featureOff = new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(new ShiftPublicationBudgetContext.AllocationCandidate(ORG_ID, true, JULY_1, JULY_31)),
                Map.of(ORG_ID, false));
        assertThatCode(() -> verifier.verify(TEAM_ID, List.of(slot(D15, 1L)), featureOff))
                .doesNotThrowAnyException();
        ShiftPublicationBudgetContext noCandidate = new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(), Map.of());
        assertThatCode(() -> verifier.verify(TEAM_ID, List.of(slot(D15, 1L)), noCandidate))
                .doesNotThrowAnyException();
        verifyNoInteractions(rateRepository);
    }

    @Test
    @DisplayName("G04: 予算連動シフトで割当 JSON が壊れていれば割当なしとして通さない")
    void 破損JSONは通さない() {
        ShiftSlotEntity corrupt = ShiftSlotEntity.builder().scheduleId(1L).slotDate(D15)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(17, 0)).requiredCount(1)
                .assignedUserIds("[1,").build();

        assertThat(catchThrowable(() -> verifier.verify(TEAM_ID, List.of(corrupt), linkedJuly())))
                .isInstanceOf(IllegalStateException.class);
        verify(rateRepository, never()).findRateHistoryForPeriod(anyLong(), anyList(), any(), any());
    }

    @Test
    @DisplayName("G02/G12: 1 日でも連動すれば全割当者×勤務日を照合し、欠落を重複なく昇順で全件返す。時給 SELECT は 1 回")
    void 全割当者を一括照合する() {
        // 7/16 だけ連動、7/15 は非連動。それでもスケジュール全体を照合する。
        ShiftPublicationBudgetContext partial = new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(new ShiftPublicationBudgetContext.AllocationCandidate(ORG_ID, true, D16, D16)),
                Map.of(ORG_ID, true));
        given(rateRepository.findRateHistoryForPeriod(TEAM_ID, List.of(1L, 2L), D15, D16))
                .willReturn(List.of(rate(1L, "1200", JULY_1)));

        List<ShiftHourlyRateMissingException.Missing> missing = missingOf(catchThrowable(() ->
                verifier.verify(TEAM_ID, List.of(slot(D15, 2L), slot(D15, 2L, 1L), slot(D16, 2L)), partial)));

        assertThat(missing).containsExactly(
                new ShiftHourlyRateMissingException.Missing(2L, D15),
                new ShiftHourlyRateMissingException.Missing(2L, D16));
        verify(rateRepository, times(1)).findRateHistoryForPeriod(anyLong(), anyList(), any(), any());
    }

    @Test
    @DisplayName("G03: effectiveFrom 当日は有効・前日は無効。改定前日は旧額、0・負数・null は欠落")
    void 適用開始日の境界と非正数() {
        given(rateRepository.findRateHistoryForPeriod(TEAM_ID, List.of(1L, 2L, 3L), D15, D16))
                .willReturn(List.of(
                        rate(1L, "1000", D16),           // 7/15 は開始前、7/16 は当日有効
                        rate(2L, "1000", JULY_1),
                        rate(2L, "0", D16),               // 7/15 は旧額で有効、7/16 は 0 で欠落
                        rate(3L, "-1", JULY_1)));         // 負数は常に欠落

        List<ShiftHourlyRateMissingException.Missing> missing = missingOf(catchThrowable(() ->
                verifier.verify(TEAM_ID, List.of(slot(D15, 1L, 2L, 3L), slot(D16, 1L, 2L, 3L)), linkedJuly())));

        assertThat(missing).containsExactly(
                new ShiftHourlyRateMissingException.Missing(1L, D15),
                new ShiftHourlyRateMissingException.Missing(3L, D15),
                new ShiftHourlyRateMissingException.Missing(2L, D16),
                new ShiftHourlyRateMissingException.Missing(3L, D16));
    }

    @Test
    @DisplayName("G03: 全勤務日に正数の有効時給があれば通す")
    void 全員正数なら通す() {
        given(rateRepository.findRateHistoryForPeriod(TEAM_ID, List.of(1L), D15, D16))
                .willReturn(List.of(rate(1L, "1000", JULY_1), rate(1L, "1100", D16)));

        assertThatCode(() -> verifier.verify(TEAM_ID, List.of(slot(D15, 1L), slot(D16, 1L)), linkedJuly()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("G05: チーム個別候補が優先。組織全体候補だけ・複数候補でも、いずれかが有効なら連動として扱う")
    void 候補の採否() {
        ShiftPublicationBudgetContext teamOverridesOrgWide = new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(new ShiftPublicationBudgetContext.AllocationCandidate(ORG_ID, true, JULY_1, JULY_31),
                        new ShiftPublicationBudgetContext.AllocationCandidate(ORG_2, false, JULY_1, JULY_31)),
                Map.of(ORG_ID, false, ORG_2, true));
        assertThat(teamOverridesOrgWide.isBudgetLinked(D15)).as("チーム個別候補の組織が OFF なら組織全体へ落ちない")
                .isFalse();

        ShiftPublicationBudgetContext orgWideOnly = new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(new ShiftPublicationBudgetContext.AllocationCandidate(ORG_2, false, D15, D15)),
                Map.of(ORG_2, true));
        assertThat(orgWideOnly.isBudgetLinked(D15)).isTrue();
        assertThat(orgWideOnly.isBudgetLinked(D16)).as("割当期間の外は非連動").isFalse();

        ShiftPublicationBudgetContext ambiguous = new ShiftPublicationBudgetContext(JULY_1, JULY_31,
                List.of(new ShiftPublicationBudgetContext.AllocationCandidate(ORG_ID, false, JULY_1, JULY_31),
                        new ShiftPublicationBudgetContext.AllocationCandidate(ORG_2, false, JULY_1, JULY_31)),
                Map.of(ORG_ID, true, ORG_2, true));
        assertThat(ambiguous.isBudgetLinked(D15)).as("計上先が一意でなくても予算なしへ化けない").isTrue();
    }
}
