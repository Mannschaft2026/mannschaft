import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import Dashboard from './dashboard.vue'

const scope = reactive({
  current: { type: 'organization', id: '10' },
  loadFromStorage: vi.fn(),
})
const canManageBudget = ref(true)
const getConsumptionSummary = vi.fn()
const listAllocations = vi.fn()
const ensureLoaded = vi.fn(async () => {})

mockNuxtImport('useScopeStore', () => () => scope)
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn(), info: vi.fn() }))
mockNuxtImport('useShiftBudgetApi', () => () => ({
  listAllocations, getConsumptionSummary, executeMonthlyClose: vi.fn(),
}))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget, ensureLoaded }),
}))

const InlineDialog = defineComponent({
  props: { visible: Boolean },
  emits: ['update:visible'],
  template: '<div v-if="visible"><slot /><slot name="footer" /></div>',
})

function summary(userId = 6) {
  return {
    allocation_id: 1, allocated_amount: 30000, consumed_amount: 9600, confirmed_amount: 0,
    planned_amount: 9600, remaining_amount: 20400, consumption_rate: 0.32, status: 'OK',
    alerts: [], flags: [],
    by_user: [{
      user_id: userId, amount: 9600, hours: 16,
      rate_breakdown: [
        { hourly_rate_snapshot: 0, hours: 8, amount: 0 },
        { hourly_rate_snapshot: 1200, hours: 8, amount: 9600 },
      ],
    }],
  }
}

async function mountPage() {
  const wrapper = await mountSuspended(Dashboard, {
    global: { stubs: { Dialog: InlineDialog } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(async () => {
  await useNuxtApp().$i18n.setLocale('ja')
  scope.current = { type: 'organization', id: '10' }
  canManageBudget.value = true
  getConsumptionSummary.mockReset().mockResolvedValue(summary())
  listAllocations.mockReset().mockResolvedValue({
    items: [1, 2].map(id => ({
      id, organization_id: 10, team_id: null, project_id: null, fiscal_year_id: 1,
      budget_category_id: 1, period_start: '2026-06-01', period_end: '2026-06-30',
      allocated_amount: 30000, consumed_amount: 9600, confirmed_amount: 0,
      currency: 'JPY', note: null, version: 0,
    })),
    total: 2, page: 0, size: 100,
  })
  ensureLoaded.mockClear()
})

describe('記録時給別の消化内訳', () => {
  it('初期表示では取得せず選択した割当だけを取得する', async () => {
    const wrapper = await mountPage()
    expect(getConsumptionSummary).not.toHaveBeenCalled()
    const action = wrapper.find('[data-testid="consumption-summary-open-1"]')
    expect(action.exists()).toBe(true)
    await action.trigger('click')
    await flushPromises()
    expect(getConsumptionSummary).toHaveBeenCalledTimes(1)
    expect(getConsumptionSummary).toHaveBeenCalledWith('10', 1)
  })

  it('合計と記録時給別内訳を表示し0円記録の由来を断定しない', async () => {
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    await flushPromises()
    const total = wrapper.get('[data-testid="consumption-summary-total-6"]').text()
    expect(total).toContain('9,600')
    expect(total).toContain('16')
    const history = wrapper.get('[data-testid="consumption-summary-zero-history"]').text()
    expect(history).toBe('0円で記録された履歴（時給要確認）')
    expect(history).not.toContain('未設定')
    expect(wrapper.get('[data-testid="consumption-summary-user-6"]').text()).toContain('1,200')
  })

  it('取得失敗を0円の内訳として表示しない', async () => {
    getConsumptionSummary.mockRejectedValueOnce(new Error('load failed'))
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="consumption-summary-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="consumption-summary-user-6"]').exists()).toBe(false)
  })

  it('組織切替前に始めた古い取得結果を新しい内訳へ出さない', async () => {
    let finishOld!: (value: ReturnType<typeof summary>) => void
    getConsumptionSummary.mockReturnValueOnce(new Promise<ReturnType<typeof summary>>(resolve => { finishOld = resolve }))
    getConsumptionSummary.mockResolvedValueOnce(summary(99))
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    scope.current = { type: 'organization', id: '20' }
    await flushPromises()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    await flushPromises()
    finishOld(summary(6))
    await flushPromises()
    expect(wrapper.find('[data-testid="consumption-summary-user-99"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="consumption-summary-user-6"]').exists()).toBe(false)
  })

  it('予算管理権限がない利用者へ内訳の操作を出さない', async () => {
    canManageBudget.value = false
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="consumption-summary-open-1"]').exists()).toBe(false)
    expect(getConsumptionSummary).not.toHaveBeenCalled()
  })

  it('空の内訳は取得失敗と区別して表示する', async () => {
    getConsumptionSummary.mockResolvedValueOnce({ ...summary(), by_user: [] })
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('記録された消化内訳はありません')
    expect(wrapper.find('[data-testid="consumption-summary-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="consumption-summary-user-6"]').exists()).toBe(false)
  })

  it('取得失敗後の再試行で同じ割当の内訳を表示する', async () => {
    getConsumptionSummary.mockRejectedValueOnce(new Error('load failed'))
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="consumption-summary-error-retry"]').trigger('click')
    await flushPromises()
    expect(getConsumptionSummary.mock.calls).toEqual([['10', 1], ['10', 1]])
    expect(wrapper.find('[data-testid="consumption-summary-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="consumption-summary-user-6"]').exists()).toBe(true)
  })

  it('権限を失った後の応答を破棄し権限復帰だけでは再取得しない', async () => {
    let finish!: (value: ReturnType<typeof summary>) => void
    getConsumptionSummary.mockReturnValueOnce(new Promise<ReturnType<typeof summary>>(resolve => { finish = resolve }))
    const wrapper = await mountPage()
    await wrapper.get('[data-testid="consumption-summary-open-1"]').trigger('click')
    canManageBudget.value = false
    await flushPromises()
    finish(summary())
    await flushPromises()
    expect(wrapper.find('[data-testid="consumption-summary-user-6"]').exists()).toBe(false)
    canManageBudget.value = true
    await flushPromises()
    expect(getConsumptionSummary).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="consumption-summary-user-6"]').exists()).toBe(false)
  })
})
