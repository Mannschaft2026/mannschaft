import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftBoardMatrix from '~/components/shift/ShiftBoardMatrix.vue'
import type { ShiftPositionResponse, ShiftSlotResponse } from '~/types/shift'

/** ShiftBoardMatrix — CMP-261125-1336。ポジションなし列・同じ時間帯の行まとめ・伏せた枠・出し分け。 */
const positions = [
  { id: 1, teamId: 1, name: 'Reception', color: null, sortOrder: 0 },
  { id: 2, teamId: 1, name: 'Register', color: null, sortOrder: 1 },
] as unknown as ShiftPositionResponse[]

function slot(id: number, positionId: number | null, extra: Partial<ShiftSlotResponse> = {}, start = '09:00:00'): ShiftSlotResponse {
  return {
    version: 0,
    id,
    scheduleId: 1,
    time: { slotDate: '2027-03-09', startTime: start, endTime: '18:00:00', endsNextDay: false },
    position: { positionId, positionName: positionId ? 'P' : null, requiredCount: 3 },
    assignedUserIds: [],
    assignmentMasked: false,
    note: null,
    ...extra,
  }
}

const memberMap = { 1: { userId: 1, displayName: 'Sato', avatarUrl: null } }

async function mountMatrix(slots: ShiftSlotResponse[], props: Record<string, unknown> = {}) {
  return mountSuspended(ShiftBoardMatrix, {
    props: { slots, positions, localAssignments: {}, memberMap, pendingRun: null, canAssign: true, ...props },
  })
}

describe('ShiftBoardMatrix', () => {
  it('各枠に「割当数 / 必要数」を出す', async () => {
    const w = await mountMatrix([slot(1, 1, { assignedUserIds: [1] })], { localAssignments: { 1: [1] } })
    expect(w.get('[data-testid="shift-cell-count"]').text()).toContain('1 / 3')
  })

  it('ポジション未設定の枠を「ポジションなし」の列に表示する（黙って落とさない）', async () => {
    const w = await mountMatrix([slot(1, 1), slot(2, null)])
    expect(w.find('[data-testid="shift-board-col-none"]').exists()).toBe(true)
    expect(w.find('[data-testid="shift-cell-2"]').exists()).toBe(true)
    expect(w.get('[data-testid="shift-board-col-none"]').text()).toBe('No position')
  })

  it('ポジションなしの枠が無ければその列を出さない', async () => {
    const w = await mountMatrix([slot(1, 1)])
    expect(w.find('[data-testid="shift-board-col-none"]').exists()).toBe(false)
  })

  it('同じ日付・時間帯の枠は 1 行にまとめ、時間帯が違えば別の行にする', async () => {
    const w = await mountMatrix([slot(1, 1), slot(2, 2), slot(3, 1, {}, '14:00:00')])
    expect(w.findAll('tbody tr')).toHaveLength(2)
  })

  it('割当を伏せた枠は人数を出さず中立表示にする', async () => {
    const w = await mountMatrix([slot(1, 1, { assignmentMasked: true })])
    expect(w.find('[data-testid="shift-cell-masked"]').exists()).toBe(true)
    expect(w.get('[data-testid="shift-cell-1"]').text()).not.toContain('/ 3')
  })

  it('権限が無ければ追加・外すを一切出さない', async () => {
    const w = await mountMatrix([slot(1, 1, { assignedUserIds: [1] })], { canAssign: false, localAssignments: { 1: [1] } })
    expect(w.find('[data-testid="shift-cell-add"]').exists()).toBe(false)
    expect(w.find('[data-testid="shift-chip-remove"]').exists()).toBe(false)
  })

  it('処理中の枠だけ無効にし、追加の押下を親へ通知する', async () => {
    const w = await mountMatrix([slot(1, 1), slot(2, 2)], { busySlotIds: [1] })
    const adds = w.findAll('[data-testid="shift-cell-add"]')
    expect(adds[0]!.attributes('disabled')).toBeDefined()
    expect(adds[1]!.attributes('disabled')).toBeUndefined()
    await adds[1]!.trigger('click')
    expect(w.emitted('addUser')?.[0]).toEqual([2])
  })

  it('日付・時間帯の見出しは i18n（直書きの日本語でない）', async () => {
    const w = await mountMatrix([slot(1, 1)])
    expect(w.get('thead th').text()).toBe('Date / time')
  })
})
