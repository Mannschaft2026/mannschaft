import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, reactive, ref } from 'vue'
import Allocations from './allocations.vue'

/**
 * CMP-260909-2152 試練40（FE）: 締め後に未計上の勤務のワンクリック追加（AC21）。
 *
 * 割当一覧の各行から「締め後に未計上の勤務」を開き、各行の「締め後追加」ボタンで、
 * slot・人・勤務日・時間・その勤務日に有効な時給を初期値に埋めた確認ダイアログを出す。
 * 理由を入力して1操作で ADD API を呼び（時給はリクエストに明示）、成功後は一覧から消える。
 * 時給未設定の勤務はボタンを無効化し理由を表示する。予算管理者でなければ導線を出さない。
 * 実装前は導線（data-testid）が無いため red。
 */

const scope = reactive({
  current: { type: 'organization', id: '10' },
  loadFromStorage: vi.fn(),
})
const canManageBudget = ref(true)
const ensureLoaded = vi.fn(async () => {})
const listAllocations = vi.fn()
const listUncountedShifts = vi.fn()
const createMonthlyCloseCorrection = vi.fn()
const notifySuccess = vi.fn()

mockNuxtImport('useScopeStore', () => () => scope)
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: notifySuccess, info: vi.fn() }))
mockNuxtImport('useShiftBudgetApi', () => () => ({
  listAllocations,
  createAllocation: vi.fn(),
  updateAllocation: vi.fn(),
  deleteAllocation: vi.fn(),
  listUncountedShifts,
  createMonthlyCloseCorrection,
}))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget, ensureLoaded }),
}))

const InlineDialog = defineComponent({
  props: { visible: Boolean },
  emits: ['update:visible'],
  template: '<div v-if="visible"><slot /><slot name="footer" /></div>',
})

function candidates() {
  return {
    items: [
      { slot_id: 501, shift_id: 91, user_id: 6, work_date: '2026-06-06', hours: 8, hourly_rate: 1200,
        add_available: true, unavailable_reason: null },
      { slot_id: 502, shift_id: 91, user_id: 7, work_date: '2026-06-08', hours: 4, hourly_rate: null,
        add_available: false, unavailable_reason: 'HOURLY_RATE_MISSING' },
    ],
  }
}

async function mountPage() {
  const wrapper = await mountSuspended(Allocations, {
    global: { stubs: { Dialog: InlineDialog, AllocationFormModal: true } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(async () => {
  await useNuxtApp().$i18n.setLocale('ja')
  canManageBudget.value = true
  listAllocations.mockReset().mockResolvedValue({
    items: [{
      id: 1, organization_id: 10, team_id: 3, project_id: null, fiscal_year_id: 1, budget_category_id: 1,
      period_start: '2026-06-01', period_end: '2026-06-30', allocated_amount: 50000, consumed_amount: 10000,
      confirmed_amount: 10000, correction_delta_total: 0, currency: 'JPY', note: null, version: 0,
    }],
    total: 1, page: 0, size: 20,
  })
  listUncountedShifts.mockReset().mockResolvedValue(candidates())
  createMonthlyCloseCorrection.mockReset().mockResolvedValue({ id: 'c1', kind: 'ADD', line_kind: 'ADDED' })
  notifySuccess.mockReset()
})

describe('締め後に未計上の勤務のワンクリック追加', () => {
  it('割当の行から未計上の勤務を開き、時給未設定の勤務はボタン無効で理由を出す', async () => {
    const wrapper = await mountPage()
    const open = wrapper.find('[data-testid="uncounted-shifts-open-1"]')
    expect(open.exists()).toBe(true)
    await open.trigger('click')
    await flushPromises()

    expect(listUncountedShifts).toHaveBeenCalledWith('10', 1, '2026-06')
    expect(wrapper.find('[data-testid="uncounted-shift-row-501-6"]').exists()).toBe(true)
    const disabled = wrapper.find('[data-testid="uncounted-shift-add-502-7"]')
    expect(disabled.exists()).toBe(true)
    expect(disabled.attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="uncounted-shift-unavailable-502-7"]').text()).not.toBe('')
  })

  it('初期値を埋めた確認ダイアログから理由を入れて1操作で ADD し、成功後は一覧から消える', async () => {
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="uncounted-shifts-open-1"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-testid="uncounted-shift-add-501-6"]').trigger('click')
    await flushPromises()

    const rate = wrapper.find('[data-testid="uncounted-shift-add-rate"]')
    expect(rate.exists()).toBe(true)
    expect((rate.element as HTMLInputElement).value.replace(/[^0-9]/g, '')).toBe('1200')
    await wrapper.find('[data-testid="uncounted-shift-add-reason"]').setValue('再公開でスキップされた勤務')
    listUncountedShifts.mockResolvedValue({ items: [candidates().items[1]] })
    await wrapper.find('[data-testid="uncounted-shift-add-confirm"]').trigger('click')
    await flushPromises()

    expect(createMonthlyCloseCorrection).toHaveBeenCalledTimes(1)
    expect(createMonthlyCloseCorrection).toHaveBeenCalledWith('10', 1, '2026-06', expect.objectContaining({
      kind: 'ADD', slot_id: 501, shift_id: 91, user_id: 6, work_date: '2026-06-06', hours: 8, hourly_rate: 1200,
      reason: '再公開でスキップされた勤務',
    }))
    expect(wrapper.find('[data-testid="uncounted-shift-row-501-6"]').exists()).toBe(false)
  })

  it('予算管理者でなければ未計上の勤務への導線を出さない', async () => {
    canManageBudget.value = false
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="uncounted-shifts-open-1"]').exists()).toBe(false)
    expect(listUncountedShifts).not.toHaveBeenCalled()
  })
})
