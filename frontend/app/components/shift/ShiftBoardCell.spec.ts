import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftBoardCell from './ShiftBoardCell.vue'

/**
 * CMP-261125-2114 実機E2E で見つかった欠陥の回帰。
 * シフト管理の権限が無い人（MANAGE_SHIFTS の無い副管理者・一般メンバー）のボードに、割当の追加・解除・ドロップの導線が出ていた
 * （操作すると BE が 403 を返すだけで、導線が出ること自体が欠陥）。canAssign=false のときは導線を出さず、ドロップも無視する。
 */
const assignments = [{ userId: 7, displayName: 'テスト太郎', avatarUrl: null }]

describe('ShiftBoardCell canAssign', () => {
  it('通常は「+ 作成」と解除ボタンが出て、ドロップで drop を発火する', async () => {
    const wrapper = await mountSuspended(ShiftBoardCell, { props: { slotId: 1, assignments, requiredCount: 2, canAssign: true } })
    expect(wrapper.find('[data-testid="shift-cell-add"]').exists()).toBe(true)
    expect(wrapper.find('button[aria-label]').exists()).toBe(true)
    await wrapper.trigger('drop', { dataTransfer: { getData: () => '9' } })
    expect(wrapper.emitted('drop')).toEqual([[9]])
  })

  it('canAssign=false では追加・解除の導線が出ず、ドロップも無視する（割当の表示は残る）', async () => {
    const wrapper = await mountSuspended(ShiftBoardCell, { props: { slotId: 1, assignments, requiredCount: 2, canAssign: false } })
    expect(wrapper.text()).toContain('テスト太郎')
    expect(wrapper.find('[data-testid="shift-cell-add"]').exists()).toBe(false)
    expect(wrapper.find('button[aria-label]').exists()).toBe(false)
    await wrapper.trigger('drop', { dataTransfer: { getData: () => '9' } })
    expect(wrapper.emitted('drop')).toBeUndefined()
  })
})
