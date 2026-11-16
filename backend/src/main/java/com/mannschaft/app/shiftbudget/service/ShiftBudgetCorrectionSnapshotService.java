package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionCommand.ClosedLine;
import com.mannschaft.app.budget.dto.ShiftBudgetCorrectionCommand.Snapshot;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetConsumptionStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.UncountedShiftResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetConsumptionEntity;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetMonthlyCloseEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetAllocationRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetConsumptionRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetMonthlyCloseEventRepository;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * CMP-260909-2152 月次締め後訂正で shiftbudget が受け持つ TX（自ドメインの Repository だけを使う）。
 *
 * <p>段取り役が budget の訂正 TX へ渡す「締め時点の事実」を値で返す読取、締めイベントの補完（旧締め用）、
 * ADD の勤務枠検証、「締め後に未計上の勤務」候補の算出を担う。budget の表には触れない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftBudgetCorrectionSnapshotService {

    private final ShiftBudgetAllocationRepository allocationRepository;
    private final ShiftBudgetConsumptionRepository consumptionRepository;
    private final ShiftBudgetMonthlyCloseEventRepository closeEventRepository;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final ObjectMapper objectMapper;

    /**
     * 割当と締め時点の事実。
     *
     * @param closeEventPresent 締めイベント (c) があるか（無ければ旧締めまたは未締め）
     */
    public record Facts(Long allocationId, Long organizationId, Long teamId, LocalDate periodStart, LocalDate periodEnd,
                        boolean closeEventPresent, Snapshot snapshot) {
    }

    /** 割当の現在のキャッシュ（訂正後の消化額の計算用）。 */
    public record AllocationAmounts(Long organizationId, Long teamId, BigDecimal allocated, BigDecimal consumed,
                                    BigDecimal confirmed) {
    }

    /**
     * 割当・締め対象月の事実を値で返す。他組織の割当・組織に加盟していないチームの割当は 404。
     */
    public Facts snapshot(Long organizationId, Long allocationId, YearMonth month) {
        ShiftBudgetAllocationEntity allocation = requireAllocation(organizationId, allocationId);
        ShiftBudgetMonthlyCloseEventEntity event = closeEventRepository
                .findByAllocationIdAndTargetMonth(allocationId, month.toString()).orElse(null);
        List<ClosedLine> lines = consumptionRepository
                .findByAllocationIdAndStatusInAndDeletedAtIsNull(allocationId,
                        List.of(ShiftBudgetConsumptionStatus.CONFIRMED)).stream()
                .map(c -> new ClosedLine(c.getId(), c.getUserId(), c.getSlotId(), c.getShiftId(), c.getHours(),
                        c.getHourlyRateSnapshot(), c.getAmount()))
                .toList();
        Set<String> planned = new HashSet<>();
        for (ShiftBudgetConsumptionEntity c : consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                allocationId, List.of(ShiftBudgetConsumptionStatus.PLANNED))) {
            planned.add(c.getSlotId() + ":" + c.getUserId());
        }
        Snapshot snapshot = new Snapshot(event == null ? null : event.getActorKind(),
                event == null ? null : event.getActorUserId(), event == null ? null : event.getClosedAt(),
                lines, planned);
        return new Facts(allocation.getId(), allocation.getOrganizationId(), allocation.getTeamId(),
                allocation.getPeriodStart(), allocation.getPeriodEnd(), event != null, snapshot);
    }

    /** 割当のキャッシュ金額（訂正後の消化額を出す土台）。 */
    public AllocationAmounts amounts(Long organizationId, Long allocationId) {
        ShiftBudgetAllocationEntity a = requireAllocation(organizationId, allocationId);
        return new AllocationAmounts(a.getOrganizationId(), a.getTeamId(), a.getAllocatedAmount(),
                a.getConsumedAmount(), a.getConfirmedAmount());
    }

    /**
     * 旧締め（締めイベント導入前）の月に、操作者 UNKNOWN の締めイベントを補完する。
     * 割当行を {@code FOR UPDATE} で握るので、月次締め・再公開・割当削除と直列化される。
     * 段取り役が budget の原仕訳の存在を確認した後にだけ呼ぶ。
     */
    @Transactional
    public void ensureCloseEvent(Long allocationId, String targetMonth, LocalDateTime closedAt) {
        ShiftBudgetAllocationEntity allocation = allocationRepository.findByIdForUpdate(allocationId).orElse(null);
        if (allocation == null || allocation.getDeletedAt() != null) {
            throw new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }
        if (closeEventRepository.existsByAllocationIdAndTargetMonth(allocationId, targetMonth)) {
            return;
        }
        closeEventRepository.save(ShiftBudgetMonthlyCloseEventEntity.builder()
                .allocationId(allocationId)
                .targetMonth(targetMonth)
                .actorKind(ShiftBudgetMonthlyCloseEventEntity.KIND_UNKNOWN)
                .closedAt(closedAt != null ? closedAt : LocalDateTime.now())
                .build());
        log.info("月次締めイベントを旧締めから補完: allocId={}, month={}", allocationId, targetMonth);
    }

    /** ADD の勤務枠が、この割当の勤務か（他チーム・他割当の枠、シフト不一致は 404）。 */
    public void requireSlotOfAllocation(Long organizationId, Long allocationId, Long slotId, Long shiftId) {
        ShiftBudgetAllocationEntity allocation = requireAllocation(organizationId, allocationId);
        List<Object[]> owner = rateQueryRepository.findSlotOwner(slotId);
        if (owner.isEmpty()) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_SLOT_NOT_FOUND);
        }
        Long slotTeam = ((Number) owner.get(0)[0]).longValue();
        Long slotShift = ((Number) owner.get(0)[1]).longValue();
        if (shiftId != null && !shiftId.equals(slotShift)) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_SLOT_NOT_FOUND);
        }
        boolean mine = allocation.getTeamId() != null
                ? allocation.getTeamId().equals(slotTeam)
                : rateQueryRepository.findOrgWideTeamIds(organizationId, allocation.getPeriodStart(),
                        allocation.getPeriodEnd()).contains(slotTeam);
        if (!mine) {
            throw new BusinessException(ShiftBudgetErrorCode.CORRECTION_SLOT_NOT_FOUND);
        }
    }

    /**
     * 締め後に未計上の勤務の候補（再公開でスキップされた分を含む）。確定済みの明細がある（枠×人）は除く。
     * 追加済み（生存 ADD）の除外は呼出側（budget の値を持つ段取り役）が行う。
     * 予定（PLANNED）が残る勤務は候補に出すが追加は不可にする。時給は勤務日に有効なものを初期値にする。
     */
    public List<UncountedShiftResponse> uncountedCandidates(Long organizationId, Long allocationId, YearMonth month) {
        ShiftBudgetAllocationEntity allocation = requireAllocation(organizationId, allocationId);
        LocalDate from = month.atDay(1).isBefore(allocation.getPeriodStart()) ? allocation.getPeriodStart() : month.atDay(1);
        LocalDate to = month.atEndOfMonth().isAfter(allocation.getPeriodEnd()) ? allocation.getPeriodEnd() : month.atEndOfMonth();
        if (from.isAfter(to)) {
            return List.of();
        }
        List<Long> teams = allocation.getTeamId() != null
                ? List.of(allocation.getTeamId())
                : rateQueryRepository.findOrgWideTeamIds(organizationId, from, to);

        Set<String> confirmed = new HashSet<>();
        for (ShiftBudgetConsumptionEntity c : consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                allocationId, List.of(ShiftBudgetConsumptionStatus.CONFIRMED))) {
            confirmed.add(c.getSlotId() + ":" + c.getUserId());
        }
        Set<String> planned = new HashSet<>();
        for (ShiftBudgetConsumptionEntity c : consumptionRepository.findByAllocationIdAndStatusInAndDeletedAtIsNull(
                allocationId, List.of(ShiftBudgetConsumptionStatus.PLANNED))) {
            planned.add(c.getSlotId() + ":" + c.getUserId());
        }

        List<UncountedShiftResponse> result = new ArrayList<>();
        for (Long teamId : teams) {
            List<Object[]> slots = rateQueryRepository.findPublishedSlotsOfTeam(teamId, from, to);
            Set<Long> userIds = new TreeSet<>();
            Map<Object[], List<Long>> assigned = new java.util.IdentityHashMap<>();
            for (Object[] slot : slots) {
                List<Long> users = parseUserIds((String) slot[5]);
                assigned.put(slot, users);
                userIds.addAll(users);
            }
            Map<Long, List<Object[]>> ratesByUser = new HashMap<>();
            if (!userIds.isEmpty()) {
                for (Object[] rate : rateQueryRepository.findRatesOfUsers(teamId, userIds)) {
                    ratesByUser.computeIfAbsent(((Number) rate[0]).longValue(), k -> new ArrayList<>()).add(rate);
                }
            }
            for (Object[] slot : slots) {
                Long slotId = ((Number) slot[0]).longValue();
                Long shiftId = ((Number) slot[1]).longValue();
                LocalDate workDate = LocalDate.parse((String) slot[2]);
                BigDecimal hours = ShiftBudgetConsumptionService.calculateHours(
                        LocalTime.parse((String) slot[3]), LocalTime.parse((String) slot[4]));
                for (Long userId : assigned.get(slot)) {
                    String key = slotId + ":" + userId;
                    if (confirmed.contains(key)) {
                        continue;
                    }
                    BigDecimal rate = effectiveRate(ratesByUser.get(userId), workDate);
                    boolean hasPlanned = planned.contains(key);
                    boolean available = rate != null && rate.signum() > 0 && !hasPlanned;
                    String reason = hasPlanned ? "PLANNED_EXISTS"
                            : (rate == null || rate.signum() <= 0) ? "HOURLY_RATE_MISSING" : null;
                    result.add(new UncountedShiftResponse(slotId, shiftId, userId, workDate, hours, rate, available,
                            reason));
                }
            }
        }
        return result;
    }

    private static BigDecimal effectiveRate(List<Object[]> rates, LocalDate date) {
        if (rates == null) {
            return null;
        }
        BigDecimal found = null;
        for (Object[] r : rates) {
            if (!LocalDate.parse((String) r[2]).isAfter(date)) {
                found = new BigDecimal(r[1].toString());
            }
        }
        return found;
    }

    private List<Long> parseUserIds(String json) {
        List<Long> ids = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return ids;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node.isArray()) {
                node.forEach(n -> ids.add(n.asLong()));
            }
        } catch (Exception e) {
            log.warn("勤務枠の担当者 JSON を読めない（候補から除外）: {}", json, e);
        }
        return ids;
    }

    private ShiftBudgetAllocationEntity requireAllocation(Long organizationId, Long allocationId) {
        ShiftBudgetAllocationEntity allocation = allocationRepository
                .findByIdAndOrganizationIdAndDeletedAtIsNull(allocationId, organizationId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND));
        // 組織に加盟していないチームの割当は存在を伏せる（既存の割当 API と同じ 404）
        if (allocation.getTeamId() != null
                && rateQueryRepository.countTeamInOrganization(allocation.getTeamId(), organizationId) == 0) {
            throw new BusinessException(ShiftBudgetErrorCode.ALLOCATION_NOT_FOUND);
        }
        return allocation;
    }
}
