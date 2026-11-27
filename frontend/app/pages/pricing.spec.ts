import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import PricingPage from './pricing.vue'

/**
 * 公開価格ページ /pricing（設計書 05_billing_center.md §公開価格 / BC-10・BC-20）。
 * - landing layout・guest/auth リダイレクト無し
 * - 公開カタログ API を取得して表示（available=false は価格 null、TEAM/ORG は確定額を出さず見積り案内）
 * - /product の料金 CTA は認証状態に関わらず /pricing
 */
const mocks = vi.hoisted(() => ({ getPublicPlans: vi.fn() }))
mockNuxtImport('useBillingApi', () => () => ({ getPublicPlans: mocks.getPublicPlans }))
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, named?: Record<string, unknown>) => (named ? `${key}:${JSON.stringify(named)}` : key),
  te: () => true,
}))

const money = (incl: number) => ({
  currency: 'JPY',
  amountIncludingTax: incl,
  amountExcludingTax: Math.round(incl / 1.1),
  taxAmount: incl - Math.round(incl / 1.1),
  taxName: '消費税',
  taxRateBasisPoints: 1000,
})
const catalog = (scopeKind: 'USER' | 'TEAM' | 'ORG') => ({
  data: {
    scopeKind,
    plans: [
      { planKey: 'FREE', displayNameKey: 'billing.plans.free.name', descriptionKey: 'billing.plans.free.description', featureKeys: [], priceBands: [], quoteRequired: false, available: true, startingMonthlyTotal: money(0) },
      { planKey: 'FULL', displayNameKey: 'billing.plans.full.name', descriptionKey: 'billing.plans.full.description', featureKeys: ['x.y'], priceBands: [], quoteRequired: scopeKind !== 'USER', available: true, startingMonthlyTotal: scopeKind === 'USER' ? money(2200) : undefined },
      { planKey: 'BASIC', displayNameKey: 'billing.plans.basic.name', descriptionKey: 'billing.plans.basic.description', featureKeys: [], priceBands: [], quoteRequired: false, available: false },
    ],
    addons: [],
  },
})

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getPublicPlans.mockImplementation(async (k: 'USER' | 'TEAM' | 'ORG') => catalog(k))
})

describe('pricing.vue 公開価格ページ', () => {
  it('PR-01: USER の公開カタログを取得し税込月額を表示する', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    expect(mocks.getPublicPlans).toHaveBeenCalledWith('USER')
    expect(wrapper.text()).toContain('billing.plans.full.name')
    expect(wrapper.text()).toContain('2,200')
  })

  it('PR-02: available=false は価格を出さず準備中表示', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    const basic = wrapper.find('[data-testid="pricing-plan-BASIC"]')
    expect(basic.text()).toContain('billing.pricing.comingSoon')
    expect(basic.text()).not.toMatch(/¥/)
  })

  it('PR-03: TEAM 切替では確定額を出さず見積り案内を出す', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    await wrapper.find('[data-testid="pricing-scope-TEAM"]').trigger('click')
    await flushPromises()
    expect(mocks.getPublicPlans).toHaveBeenCalledWith('TEAM')
    const full = wrapper.find('[data-testid="pricing-plan-FULL"]')
    expect(full.text()).toContain('billing.pricing.quoteRequired')
    expect(full.text()).not.toContain('2,200')
  })

  it('PR-04: 取得失敗時はエラー表示を出す', async () => {
    mocks.getPublicPlans.mockRejectedValue(new Error('boom'))
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    expect(wrapper.find('[data-testid="pricing-error"]').exists()).toBe(true)
  })
})

function readSource(rel: string): string {
  return readFileSync(resolve(process.cwd(), rel), 'utf8').replace(/\r\n/g, '\n')
}

describe('pricing.vue ソース規約', () => {
  const src = readSource('app/pages/pricing.vue')
  it('PR-05: layout は landing で middleware（guest/auth）を置かない', () => {
    expect(src).toMatch(/layout:\s*'landing'/)
    expect(src).not.toMatch(/middleware/)
  })
  it('PR-06: SSR の useAsyncData・useSeoMeta・canonical /pricing を持つ', () => {
    expect(src).toContain('useAsyncData')
    expect(src).toContain('useSeoMeta')
    expect(src).toMatch(/canonicalPath:\s*'\/pricing'/)
  })
})

describe('product.vue 料金 CTA', () => {
  const src = readSource('app/pages/product.vue')
  it('PR-07: 認証状態に関係なく /pricing へ向く（authStore 依存なし）', () => {
    expect(src).toMatch(/pricingCtaTo\s*=\s*'\/pricing'/)
    expect(src).not.toContain('useAuthStore')
  })
})
