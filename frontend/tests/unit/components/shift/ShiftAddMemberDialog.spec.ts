import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftAddMemberDialog from '~/components/shift/ShiftAddMemberDialog.vue'
import type { ShiftSlotResponse } from '~/types/shift'

/** ShiftAddMemberDialog — CMP-261125-1336。既にいる人は出さない／時間が重なる人は理由付きで選べない／検索／満員。 */
function slot(id: number, start: string, end: string, assigned: number[] = [], required = 3, positionName: string | null = 'Reception'): ShiftSlotResponse {
  return {
    version: 0,
    id,
    scheduleId: 1,
    time: { slotDate: '2027-03-09', startTime: start, endTime: end, endsNextDay: false },
    position: { positionId: positionName ? 1 : null, positionName, requiredCount: required },
    assignedUserIds: assigned,
    assignmentMasked: false,
    note: null,
  }
}

const members = [
  { userId: 1, displayName: 'Sato Hanako', avatarUrl: null },
  { userId: 2, displayName: 'Suzuki Ichiro', avatarUrl: null },
  { userId: 3, displayName: 'Ito Sakura', avatarUrl: null },
  { userId: 4, displayName: 'Takahashi Yu', avatarUrl: null },
]

const target = slot(10, '09:00:00', '13:00:00', [1])
// 4 さんは 12:00-15:00 の別枠（重なる）、3 さんは 13:00-15:00（端点が接するだけ）
const overlapping = slot(11, '12:00:00', '15:00:00', [4], 3, 'Register')
const touching = slot(12, '13:00:00', '15:00:00', [3])
const all = [target, overlapping, touching]

async function mountDialog(props: Record<string, unknown> = {}) {
  return mountSuspended(ShiftAddMemberDialog, {
    props: { visible: true, targetSlot: target, slots: all, members, assignments: {}, ...props },
    global: { stubs: { Dialog: { template: '<div><slot /></div>' } } },
  })
}

describe('ShiftAddMemberDialog', () => {
  it('この枠に既にいる人は出さない', async () => {
    const w = await mountDialog()
    expect(w.find('[data-testid="shift-add-member-option-1"]').exists()).toBe(false)
    expect(w.find('[data-testid="shift-add-member-option-2"]').exists()).toBe(true)
  })

  it('時間が重なる別の枠に入っている人は、理由を添えて選べなくする', async () => {
    const w = await mountDialog()
    const option = w.get('[data-testid="shift-add-member-option-4"]')
    expect(option.attributes('disabled')).toBeDefined()
    const reason = option.get('[data-testid="shift-add-member-overlap-reason"]').text()
    expect(reason).toContain('Register')
    expect(reason).toMatch(/12:00/)
    await option.trigger('click')
    expect(w.emitted('select')).toBeUndefined()
  })

  it('端点が接するだけの人・重ならない人は選べる', async () => {
    const w = await mountDialog()
    for (const id of [2, 3]) {
      const option = w.get(`[data-testid="shift-add-member-option-${id}"]`)
      expect(option.attributes('disabled')).toBeUndefined()
      expect(option.find('[data-testid="shift-add-member-overlap-reason"]').exists()).toBe(false)
    }
    await w.get('[data-testid="shift-add-member-option-2"]').trigger('click')
    expect(w.emitted('select')?.[0]).toEqual([2])
  })

  it('検索で絞り込める（一致なしは 0 件）', async () => {
    const w = await mountDialog()
    await w.get('[data-testid="shift-add-member-search"]').setValue('suzuki')
    expect(w.findAll('[data-testid^="shift-add-member-option-"]')).toHaveLength(1)
    await w.get('[data-testid="shift-add-member-search"]').setValue('zzz')
    expect(w.findAll('[data-testid^="shift-add-member-option-"]')).toHaveLength(0)
  })

  it('処理中は全員選べない（二度押し防止）', async () => {
    const w = await mountDialog({ busy: true })
    for (const option of w.findAll('[data-testid^="shift-add-member-option-"]')) {
      expect(option.attributes('disabled')).toBeDefined()
    }
  })

  it('満員の枠では理由を出し、全員選べない', async () => {
    const full = slot(10, '09:00:00', '13:00:00', [1], 1)
    const w = await mountDialog({ targetSlot: full, slots: [full] })
    expect(w.find('[data-testid="shift-add-member-full"]').exists()).toBe(true)
    for (const option of w.findAll('[data-testid^="shift-add-member-option-"]')) {
      expect(option.attributes('disabled')).toBeDefined()
    }
  })

  it('選択肢はモバイルで 44px 以上のタップ領域クラスを持つ', async () => {
    const w = await mountDialog()
    expect(w.get('[data-testid="shift-add-member-option-2"]').classes()).toContain('max-md:min-h-11')
  })
})
