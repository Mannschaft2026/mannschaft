import type { ShiftSlotResponse } from '~/types/shift'

/**
 * シフトボードの「同じ人が時間の重なる別枠に入っていないか」の判定（CMP-261125-1336）。
 *
 * 同一シフト表の枠データだけから FE で判定する。BE の最終判定の代替ではなく、
 * 選べない理由を操作前に示すためのもの。端点が接するだけ（9:00-12:00 と 12:00-15:00）は重ならない。
 */

function toMinutes(hhmmss: string): number {
  const [h, m] = hhmmss.split(':').map(Number)
  return (h ?? 0) * 60 + (m ?? 0)
}

/** 枠の開始・終了を、日付を含む通し分に直す。日跨ぎ枠は終了に 24 時間足す。 */
export function slotInterval(slot: ShiftSlotResponse): { start: number; end: number } {
  const [y, m, d] = slot.time.slotDate.split('-').map(Number)
  const dayMinutes = Date.UTC(y ?? 1970, (m ?? 1) - 1, d ?? 1) / 60_000
  const start = dayMinutes + toMinutes(slot.time.startTime)
  let end = dayMinutes + toMinutes(slot.time.endTime)
  if (slot.time.endsNextDay || end <= start) end += 24 * 60
  return { start, end }
}

/** 2 つの枠の時間が一部でも重なるか。 */
export function slotsOverlap(a: ShiftSlotResponse, b: ShiftSlotResponse): boolean {
  const ia = slotInterval(a)
  const ib = slotInterval(b)
  return ia.start < ib.end && ib.start < ia.end
}

/**
 * ユーザーが対象枠へ入る場合に、時間が重なる別の枠（そのユーザーが既に入っている枠）を返す。
 *
 * @param assignments 画面の現在の割当（枠ID → ユーザーID 配列）。無い枠は slot.assignedUserIds を使う。
 * @returns 最初に重なった枠。無ければ null
 */
export function findOverlappingSlot(
  target: ShiftSlotResponse,
  userId: number,
  slots: ShiftSlotResponse[],
  assignments: Record<number, number[]>,
): ShiftSlotResponse | null {
  for (const other of slots) {
    if (other.id === target.id) continue
    const assigned = assignments[other.id] ?? other.assignedUserIds ?? []
    if (!assigned.includes(userId)) continue
    if (slotsOverlap(target, other)) return other
  }
  return null
}
