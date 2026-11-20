import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import CategorySelector from '~/components/action-memo/CategorySelector.vue'
import pageSource from '~/pages/action-memo/index.vue?raw'

/**
 * 行動メモ カテゴリ行の表示崩れ回帰（CMP-261119-2031 追補）。
 * - de で 390/360px 横はみ出し（sw 405）: 行・ボタン群コンテナが折り返せること
 * - ja で「仕/事」のようにボタン内で語が折れる: ボタンが whitespace-nowrap であること
 */
describe('action-memo カテゴリ行のレイアウト', () => {
  it('index.vue のカテゴリ行コンテナに flex-wrap が付く', () => {
    const idx = pageSource.indexOf('Phase 3: カテゴリ選択（常時表示）')
    expect(idx).toBeGreaterThan(-1)
    const rowTag = pageSource.slice(idx).match(/<div class="([^"]*)"/)
    expect(rowTag?.[1]).toContain('flex-wrap')
  })

  it('CategorySelector のボタン群コンテナに flex-wrap が付く', async () => {
    const w = await mountSuspended(CategorySelector, { props: { modelValue: 'WORK' } })
    expect(w.get('[data-testid="category-selector"]').classes()).toContain('flex-wrap')
  })

  it('CategorySelector の各ボタンに whitespace-nowrap が付く', async () => {
    const w = await mountSuspended(CategorySelector, { props: { modelValue: 'WORK' } })
    const buttons = w.findAll('button')
    expect(buttons).toHaveLength(3)
    for (const b of buttons) expect(b.classes()).toContain('whitespace-nowrap')
  })
})
