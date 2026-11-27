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
  locale: { value: 'ja' },
}))

const money = (incl: number) => ({
  currency: 'JPY',
  amountIncludingTax: incl,
  amountExcludingTax: Math.round(incl / 1.1),
  taxAmount: incl - Math.round(incl / 1.1),
  taxName: 'TAXNAME',
  taxRateBasisPoints: 1000,
})
const catalog = (scopeKind: 'USER' | 'TEAM' | 'ORG') => ({
  data: {
    scopeKind,
    plans: [
      { planKey: 'FREE', displayNameKey: 'billing.plans.free.name', descriptionKey: 'billing.plans.free.description', featureKeys: [], priceBands: [], quoteRequired: false, available: true, startingMonthlyTotal: money(0) },
      { planKey: 'FULL', displayNameKey: 'billing.plans.full.name', descriptionKey: 'billing.plans.full.description', featureKeys: ['x.y'], priceBands: [{ minMembers: 1, maxMembers: 10 }, { minMembers: 11 }], quoteRequired: scopeKind !== 'USER', available: true, startingMonthlyTotal: scopeKind === 'USER' ? money(2200) : undefined },
      { planKey: 'BASIC', displayNameKey: 'billing.plans.basic.name', descriptionKey: 'billing.plans.basic.description', featureKeys: [], priceBands: [], quoteRequired: false, available: false },
    ],
    addons: [
      { featureKey: 'x.y', displayNameKey: 'billing.features.x.name', descriptionKey: 'billing.features.x.description', quoteRequired: false, available: true, startingMonthlyTotal: money(330), priceBands: [] },
      { featureKey: 'free.a', displayNameKey: 'billing.features.free.name', descriptionKey: 'billing.features.free.description', quoteRequired: false, available: true, startingMonthlyTotal: money(0), priceBands: [] },
      { featureKey: 'off.b', displayNameKey: 'billing.features.off.name', descriptionKey: 'billing.features.off.description', quoteRequired: false, available: false, priceBands: [] },
    ],
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
    expect(basic.text()).not.toMatch(/[¥￥]/)
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
    // 人数帯（priceBands）は TEAM/ORG のみ表示する
    expect(full.text()).toContain('billing.plans.memberBandRange')
    expect(full.text()).toContain('billing.plans.memberBandUnlimited')
  })

  it('PR-08: スコープ切替の再取得中は旧スコープ(USER)の価格を表示しない（PageLoading）', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    expect(wrapper.text()).toContain('2,200')
    let resolveTeam: (v: ReturnType<typeof catalog>) => void = () => {}
    mocks.getPublicPlans.mockImplementationOnce(() => new Promise((r) => { resolveTeam = r }))
    await wrapper.find('[data-testid="pricing-scope-TEAM"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('2,200')
    expect(wrapper.find('[data-testid="pricing-plan-FULL"]').exists()).toBe(false)
    resolveTeam(catalog('TEAM'))
    await flushPromises()
    expect(wrapper.find('[data-testid="pricing-plan-FULL"]').text()).toContain('billing.pricing.quoteRequired')
  })

  it('PR-09: 税表示は BE の taxName を使い、金額は Intl（ロケール・通貨）で整形する', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    const text = wrapper.find('[data-testid="pricing-plan-FULL"]').text()
    expect(text).toContain('"taxName":"TAXNAME"')
    expect(text).toMatch(/[¥￥]2,200/)
  })

  it('PR-10: 無料プランは「無料」表示で金額を出さない', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    const free = wrapper.find('[data-testid="pricing-plan-FREE"]')
    expect(free.text()).toContain('billing.pricing.free')
    expect(free.text()).not.toMatch(/[¥￥]/)
  })

  it('PR-11: featureKeys は addons の displayNameKey 経由で解決される（解決できないものは出さない）', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    expect(wrapper.find('[data-testid="pricing-plan-FULL"]').text()).toContain('billing.features.x.name')
  })

  it('PR-12: アドオンは有償（税込）・無料・準備中を出し分ける', async () => {
    const wrapper = await mountSuspended(PricingPage)
    await flushPromises()
    expect(wrapper.find('[data-testid="pricing-addon-x.y"]').text()).toMatch(/[¥￥]330/)
    expect(wrapper.find('[data-testid="pricing-addon-free.a"]').text()).toContain('billing.pricing.free')
    const off = wrapper.find('[data-testid="pricing-addon-off.b"]')
    expect(off.text()).toContain('billing.pricing.comingSoon')
    expect(off.text()).not.toMatch(/[¥￥]/)
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
  it('PR-06: SSR の useAsyncData（キーは scopeKind 依存）・useSeoMeta・canonical /pricing を持つ', () => {
    expect(src).toContain('useAsyncData')
    expect(src).toMatch(/\(\) => `public-billing-plans-\$\{scopeKind\.value\}`/)
    expect(src).not.toMatch(/¥|ja-JP/)
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
