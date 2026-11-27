import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftBoardCell from '~/components/shift/ShiftBoardCell.vue'

/**
 * ShiftBoardCell — CMP-261125-1336。
 * 「割当数 / 必要数」「満員」「伏せた枠は中立表示」「権限が無ければ操作を出さない」「処理中は無効」。
 * テスト環境の既定ロケールは en のため、判定は data-testid と en 文言で行う。
 */
const members = [
  { userId: 1, displayName: 'Sato', avatarUrl: null },
  { userId: 2, displayName: 'Tanaka', avatarUrl: null },
]

async function mountCell(props: Record<string, unknown> = {}) {
  return mountSuspended(ShiftBoardCell, {
    props: { slotId: 10, assignments: members, requiredCount: 3, canAssign: true, ...props },
  })
}

describe('ShiftBoardCell', () => {
  it('割当数 / 必要数を出し、満員でなければ追加できる', async () => {
    const w = await mountCell()
    expect(w.get('[data-testid="shift-cell-count"]').text()).toContain('2 / 3')
    expect(w.find('[data-testid="shift-cell-full"]').exists()).toBe(false)
    const add = w.get('[data-testid="shift-cell-add"]')
    expect(add.attributes('disabled')).toBeUndefined()
    await add.trigger('click')
    expect(w.emitted('addUser')).toHaveLength(1)
  })

  it('満員のときは「満員」と理由を出し、追加を無効化して押しても発火しない', async () => {
    const w = await mountCell({ requiredCount: 2 })
    expect(w.get('[data-testid="shift-cell-full"]').text()).toBe('Full')
    expect(w.find('[data-testid="shift-cell-full-reason"]').exists()).toBe(true)
    const add = w.get('[data-testid="shift-cell-add"]')
    expect(add.attributes('disabled')).toBeDefined()
    await add.trigger('click')
    expect(w.emitted('addUser')).toBeUndefined()
  })

  it('割当を伏せた枠は人数・名札・追加を出さず、中立表示だけ出す', async () => {
    const w = await mountCell({ masked: true, assignments: [] })
    expect(w.find('[data-testid="shift-cell-masked"]').exists()).toBe(true)
    expect(w.get('[data-testid="shift-cell-count"]').text()).not.toMatch(/\/ 3/)
    expect(w.find('[data-testid="shift-cell-add"]').exists()).toBe(false)
    expect(w.find('[data-testid="shift-chip-remove"]').exists()).toBe(false)
  })

  it('割当権限が無ければ追加・外すを出さない（閲覧のみ。人数と名札は見える）', async () => {
    const w = await mountCell({ canAssign: false })
    expect(w.find('[data-testid="shift-cell-add"]').exists()).toBe(false)
    expect(w.find('[data-testid="shift-chip-remove"]').exists()).toBe(false)
    expect(w.text()).toContain('Sato')
    expect(w.get('[data-testid="shift-cell-count"]').text()).toContain('2 / 3')
  })

  it('処理中は追加と外すを無効化する', async () => {
    const w = await mountCell({ busy: true })
    expect(w.get('[data-testid="shift-cell-add"]').attributes('disabled')).toBeDefined()
    for (const remove of w.findAll('[data-testid="shift-chip-remove"]')) {
      expect(remove.attributes('disabled')).toBeDefined()
    }
  })

  it('外すボタンは名札ごとの読み上げ名を持つ', async () => {
    const w = await mountCell()
    const labels = w.findAll('[data-testid="shift-chip-remove"]').map((b) => b.attributes('aria-label'))
    expect(labels).toEqual(['Remove Sato from this slot', 'Remove Tanaka from this slot'])
    await w.findAll('[data-testid="shift-chip-remove"]')[1]!.trigger('click')
    expect(w.emitted('removeUser')?.[0]).toEqual([2])
  })

  it('モバイルのタップ領域クラス（追加・外すとも 44px）を持つ', async () => {
    const w = await mountCell()
    expect(w.get('[data-testid="shift-cell-add"]').classes()).toEqual(expect.arrayContaining(['max-md:min-h-11', 'max-md:min-w-11']))
    expect(w.get('[data-testid="shift-chip-remove"]').classes()).toEqual(expect.arrayContaining(['max-md:min-h-11', 'max-md:min-w-11']))
  })

  it('満員・処理中・権限なしの枠にはドロップできない', async () => {
    const dataTransfer = { getData: () => '5' }
    for (const props of [{ requiredCount: 2 }, { busy: true }, { canAssign: false }]) {
      const w = await mountCell(props)
      await w.get('[data-testid="shift-cell-10"]').trigger('drop', { dataTransfer })
      expect(w.emitted('drop')).toBeUndefined()
    }
    const ok = await mountCell()
    await ok.get('[data-testid="shift-cell-10"]').trigger('drop', { dataTransfer })
    expect(ok.emitted('drop')?.[0]).toEqual([5])
  })
})
