import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 特商法リンクのモーダル化の配線ガード（マウントが重い課金系ページ向けの静的検査）。
 *   CDW-001: plans / subscribe は /commerce-disclosure への NuxtLink を持たず、button[type=button] で
 *            CommerceDisclosureModal を開く
 *   CDW-002: 公開ページ commerce-disclosure.vue は残り、Content を使う（URL 直アクセス・SEO 用）
 */

const here = dirname(fileURLToPath(import.meta.url))
const read = (rel: string) => readFileSync(resolve(here, '../../../app', rel), 'utf-8')

describe('特商法モーダルの配線', () => {
  for (const rel of ['pages/billing/plans.vue', 'pages/payments/subscribe/[itemId].vue']) {
    it(`CDW-001: ${rel}`, () => {
      const src = read(rel)
      expect(src).not.toMatch(/to="\/commerce-disclosure/)
      expect(src).toMatch(/<CommerceDisclosureModal\s+v-model:visible=/)
      expect(src).toMatch(/type="button"[\s\S]{0,300}footer_commerce/)
    })
  }

  it('CDW-002: 公開ページは残る', () => {
    const src = read('pages/commerce-disclosure.vue')
    expect(src).toContain('<CommerceDisclosureContent')
  })
})
