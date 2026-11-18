package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftAssignedUserIds;
import com.mannschaft.app.shift.ShiftHourlyRateMissingException;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * シフト公開 TX の中で、予算連動の勤務日に割当者の有効な正数時給があるかを検証する（CMP-261115-1821）。
 *
 * <p>呼び出し元は {@link ShiftScheduleService#transitionStatus} の公開分岐だけで、親スケジュールを FOR UPDATE した
 * 後に読み直した枠を受け取る。予算連動の判定は TX の外で固定した {@link ShiftPublicationBudgetContext}（値）だけで行い、
 * 本クラスから到達する Repository は shift ドメインの {@link ShiftHourlyRateRepository} だけである
 * （TX 内から shiftbudget・budget・feature・ACL の Repository へ到達しない。設計書「Root採用・軍議検分反映」）。</p>
 *
 * <ol>
 *   <li>枠が無ければ検証不要（従来どおり公開可）。</li>
 *   <li>事前に枠が無かった（context が空）のに枠がある、または枠日付が context の範囲外なら、判定の前提が
 *       崩れているので {@code COMMON_003}（409）で公開を拒否し再操作を促す（更新 0・イベント 0）。</li>
 *   <li>予算連動の勤務日が 1 日も無ければ検証不要（非予算シフトの従来挙動を変えない）。</li>
 *   <li>1 日でも連動すれば、スケジュール内の<b>全</b>割当者 × 勤務日を照合する（予算対象枠だけに狭めない）。
 *       割当 JSON は厳密に読み、壊れた JSON を割当なしとして通さない。</li>
 *   <li>時給は 1 クエリで取得し、各勤務日に floor（{@code effectiveFrom <= 勤務日} の最新）を適用する。
 *       行が無い・{@code null}・0 以下は欠落とし、重複を除いて日付・ユーザー昇順で 400 として返す。</li>
 * </ol>
 *
 * <p>予算設定変更との原子性、および公開時と非同期の実計上時の時給の一致は保証しない（設計書 第3案）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftPublicationHourlyRateVerifier {

    private final ShiftHourlyRateRepository hourlyRateRepository;

    /**
     * 公開前の時給検証。
     *
     * @param teamId  公開対象スケジュールのチームID
     * @param slots   親ロック後に読み直した生存枠
     * @param context 公開 TX の外で固定した予算連動判定の値
     * @throws BusinessException                {@code COMMON_003}（409）: 枠日付が context の範囲外、または空から非空へ変化
     * @throws ShiftHourlyRateMissingException 予算連動シフトで有効な正数時給の無い割当者・勤務日がある（400）
     */
    public void verify(Long teamId, List<ShiftSlotEntity> slots, ShiftPublicationBudgetContext context) {
        if (slots.isEmpty()) {
            return;
        }
        SortedSet<LocalDate> slotDates = new TreeSet<>();
        slots.forEach(slot -> slotDates.add(slot.getSlotDate()));
        if (context.coversNoDates() || !slotDates.stream().allMatch(context::covers)) {
            log.info("公開時給ガード: 予算判定の取得後に枠日付が範囲外へ変わったため再操作を求める: teamId={}, "
                            + "covered=[{}, {}], slotDates=[{}, {}]",
                    teamId, context.coveredFrom(), context.coveredTo(), slotDates.first(), slotDates.last());
            throw new BusinessException(CommonErrorCode.COMMON_003);
        }
        if (slotDates.stream().noneMatch(context::isBudgetLinked)) {
            return;
        }

        // 勤務日 → 割当者（重複除去・昇順）
        NavigableMap<LocalDate, SortedSet<Long>> assignedByDate = new TreeMap<>();
        SortedSet<Long> userIds = new TreeSet<>();
        for (ShiftSlotEntity slot : slots) {
            List<Long> assigned = ShiftAssignedUserIds.parseStrict(slot.getAssignedUserIds());
            if (assigned.isEmpty()) {
                continue;
            }
            assignedByDate.computeIfAbsent(slot.getSlotDate(), d -> new TreeSet<>()).addAll(assigned);
            userIds.addAll(assigned);
        }
        if (userIds.isEmpty()) {
            return;
        }

        Map<Long, NavigableMap<LocalDate, BigDecimal>> historyByUser = new HashMap<>();
        for (ShiftHourlyRateEntity rate : hourlyRateRepository.findRateHistoryForPeriod(
                teamId, List.copyOf(userIds), assignedByDate.firstKey(), assignedByDate.lastKey())) {
            historyByUser.computeIfAbsent(rate.getUserId(), u -> new TreeMap<>())
                    .put(rate.getEffectiveFrom(), rate.getHourlyRate());
        }

        List<ShiftHourlyRateMissingException.Missing> missing = new ArrayList<>();
        assignedByDate.forEach((date, users) -> users.forEach(userId -> {
            NavigableMap<LocalDate, BigDecimal> history = historyByUser.get(userId);
            Map.Entry<LocalDate, BigDecimal> effective = history == null ? null : history.floorEntry(date);
            if (effective == null || effective.getValue() == null || effective.getValue().signum() <= 0) {
                missing.add(new ShiftHourlyRateMissingException.Missing(userId, date));
            }
        }));
        if (!missing.isEmpty()) {
            throw new ShiftHourlyRateMissingException(missing);
        }
    }
}
