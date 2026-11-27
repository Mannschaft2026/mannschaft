import { describe, expect, it } from 'vitest'
import { findOverlappingSlot, slotsOverlap } from '~/utils/shiftOverlap'
import type { ShiftSlotResponse } from '~/types/shift'

function slot(
  id: number,
  date: string,
  start: string,
  end: string,
  extra: { endsNextDay?: boolean; assigned?: number[] } = {},
): ShiftSlotResponse {
  return {
    version: 0,
    id,
    scheduleId: 1,
    time: { slotDate: date, startTime: start, endTime: end, endsNextDay: extra.endsNextDay ?? false },
    position: { positionId: 1, positionName: '受付', requiredCount: 2 },
    assignedUserIds: extra.assigned ?? [],
    assignmentMasked: false,
    note: null,
  }
}

describe('shiftOverlap: 時間が一部でも重なる枠の判定', () => {
  it('一部でも重なれば重なり、端点が接するだけなら重ならない', () => {
    expect(slotsOverlap(slot(1, '2027-03-09', '09:00:00', '13:00:00'), slot(2, '2027-03-09', '12:00:00', '15:00:00'))).toBe(true)
    expect(slotsOverlap(slot(1, '2027-03-09', '09:00:00', '12:00:00'), slot(2, '2027-03-09', '12:00:00', '15:00:00'))).toBe(false)
    expect(slotsOverlap(slot(1, '2027-03-09', '09:00:00', '12:00:00'), slot(2, '2027-03-10', '09:00:00', '12:00:00'))).toBe(false)
  })

  it('日跨ぎ枠は翌日の早い時間帯と重なる', () => {
    const night = slot(1, '2027-03-09', '22:00:00', '02:00:00', { endsNextDay: true })
    expect(slotsOverlap(night, slot(2, '2027-03-10', '01:00:00', '03:00:00'))).toBe(true)
    expect(slotsOverlap(night, slot(3, '2027-03-10', '02:00:00', '03:00:00'))).toBe(false)
  })

  it('findOverlappingSlot: その人が入っている重なる枠だけを返す（自分自身・別の人の枠は無視）', () => {
    const target = slot(1, '2027-03-09', '09:00:00', '13:00:00')
    const other = slot(2, '2027-03-09', '12:00:00', '15:00:00', { assigned: [7] })
    const noOverlap = slot(3, '2027-03-09', '15:00:00', '17:00:00', { assigned: [7] })
    const slots = [target, other, noOverlap]
    expect(findOverlappingSlot(target, 7, slots, {})?.id).toBe(2)
    expect(findOverlappingSlot(target, 8, slots, {})).toBeNull()
    // 画面の現在の割当（ローカル）を優先する
    expect(findOverlappingSlot(target, 7, slots, { 2: [] })).toBeNull()
    expect(findOverlappingSlot(target, 8, slots, { 2: [8] })?.id).toBe(2)
  })
})
