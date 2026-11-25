// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'
import DashboardPage from './dashboard.vue'

// 実行時は app/plugins/dayjs.ts が拡張する。単体テストでは自前で拡張する
dayjs.extend(utc)
dayjs.extend(timezone)

/**
 * CMP-261021-0657: 月次締めは終了した月（JST 基準で対象月 < 当月）だけ実行できる。
 * 当月・未来月はダイアログで実行ボタンを無効にし理由を出す。BE の 409 SHIFT_BUDGET_032 はダイアログ内に出す。
 */
const api = vi.hoisted(() => ({ listAllocations: vi.fn(), executeMonthlyClose: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), info: vi.fn() }))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, p?: Record<string, unknown>) => (p?.month ? `${key}:${p.month}` : key),
  }),
}))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget: ref(true), ensureLoaded: vi.fn() }),
}))
mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: '10' } }))
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useShiftBudgetApi', () => () => api)
// ユーザー TZ が JST 以外でも既定月・判定は JST 基準であることを確認するため、あえて UTC にする
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('UTC') }))

const DialogStub = defineComponent({
  name: 'Dialog',
  props: { visible: Boolean },
  setup(p, { slots }) {
    return () =>
      p.visible ? h('div', { 'data-test': 'dialog' }, [slots.default?.(), slots.footer?.()]) : null
  },
})
const ButtonStub = defineComponent({
  name: 'Button',
  props: { label: String, disabled: Boolean, loading: Boolean },
  emits: ['click'],
  setup(p, { emit }) {
    return () =>
      h('button', { 'data-label': p.label, disabled: p.disabled, onClick: () => emit('click') }, p.label)
  },
})
const MessageStub = defineComponent({
  name: 'Message',
  props: { severity: String },
  setup(p, { slots }) {
    return () => h('div', { 'data-severity': p.severity }, slots.default?.())
  },
})
const InputTextStub = defineComponent({
  name: 'InputText',
  props: { modelValue: String },
  emits: ['update:modelValue'],
  setup(p, { emit }) {
    return () =>
      h('input', {
        'data-test': 'ym',
        value: p.modelValue,
        onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value),
      })
  },
})

function mountPage() {
  return mount(DashboardPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        Column: true,
        NuxtLink: true,
        ConsumptionRateBadge: true,
        DashboardErrorState: true,
        Card: true,
        DataTable: true,
        Dialog: DialogStub,
        Button: ButtonStub,
        Message: MessageStub,
        InputText: InputTextStub,
      },
    },
  })
}

type Wrapper = ReturnType<typeof mountPage>

async function openDialog(wrapper: Wrapper) {
  await wrapper.find('[data-label="shiftBudget.monthlyClose.button"]').trigger('click')
  await flushPromises()
}
async function setMonth(wrapper: Wrapper, ym: string) {
  await wrapper.find('[data-test="ym"]').setValue(ym)
  await flushPromises()
}
const execButton = (w: Wrapper) => w.find('[data-label="shiftBudget.monthlyClose.execute"]')

describe('月次締めダイアログ 未終了月の制御', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.useFakeTimers({ toFake: ['Date'] })
    // JST 2026-11-15
    vi.setSystemTime(new Date('2026-11-15T03:00:00Z'))
    api.listAllocations.mockResolvedValue({ items: [], total: 0 })
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('既定月は JST の前月（ユーザー TZ ではない）', async () => {
    // UTC 2026-11-30T16:00Z = JST 12/01。UTC 基準なら前月は 10 月、JST なら 11 月
    vi.setSystemTime(new Date('2026-11-30T16:00:00Z'))
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    expect((wrapper.find('[data-test="ym"]').element as HTMLInputElement).value).toBe('2026-11')
  })

  it.each([
    ['当月', '2026-11'],
    ['未来月', '2026-12'],
    ['遠い未来', '2027-03'],
  ])('%s は実行ボタンが無効で理由が出て、API は呼ばれない', async (_l, ym) => {
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, ym)
    expect(execButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('shiftBudget.monthlyClose.notEnded')
    await execButton(wrapper).trigger('click')
    expect(api.executeMonthlyClose).not.toHaveBeenCalled()
  })

  it('過去月は実行ボタンが有効で理由は出ず、API が呼ばれる', async () => {
    api.executeMonthlyClose.mockResolvedValue({
      closed_allocations: 1,
      already_closed_allocations: 0,
      closed_consumptions: 3,
    })
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-10')
    expect(execButton(wrapper).attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.notEnded')
    await execButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.executeMonthlyClose).toHaveBeenCalledTimes(1)
    expect(api.executeMonthlyClose).toHaveBeenCalledWith(
      expect.objectContaining({ organization_id: 10, year_month: '2026-10' }),
    )
    expect(notify.success).toHaveBeenCalled()
    // 成功時はダイアログが閉じる
    expect(wrapper.find('[data-test="dialog"]').exists()).toBe(false)
  })

  it('JST 境界: UTC 2026-11-30T15:00Z は JST 12/01 なので 2026-11 は可、2026-12 は不可', async () => {
    vi.setSystemTime(new Date('2026-11-30T15:00:00Z'))
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-11')
    expect(execButton(wrapper).attributes('disabled')).toBeUndefined()
    await setMonth(wrapper, '2026-12')
    expect(execButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('JST 境界: UTC 2026-11-30T14:59Z はまだ JST 11/30 なので 2026-11 は不可', async () => {
    vi.setSystemTime(new Date('2026-11-30T14:59:00Z'))
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-11')
    expect(execButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it.each([
    ['空欄', ''],
    ['月が13', '2026-13'],
    ['月が1桁', '2026-1'],
    ['全角', '２０２６-１０'],
    ['スラッシュ区切り', '2026/10'],
    ['区切りなし', '202610'],
    ['文字列', 'invalid'],
  ])('形式不正(%s)は実行ボタンが無効で、形式エラーを出し確認文は出さず、API も通知も呼ばれない', async (_l, ym) => {
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, ym)
    expect(execButton(wrapper).attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('shiftBudget.monthlyClose.invalidFormat')
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.confirm')
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.notEnded')
    await execButton(wrapper).trigger('click')
    expect(notify.error).not.toHaveBeenCalled()
    expect(api.executeMonthlyClose).not.toHaveBeenCalled()
  })

  it('前後空白は trim して判定・送信する', async () => {
    api.executeMonthlyClose.mockResolvedValue({
      closed_allocations: 1,
      already_closed_allocations: 0,
      closed_consumptions: 3,
    })
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, ' 2026-10 ')
    expect(execButton(wrapper).attributes('disabled')).toBeUndefined()
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.invalidFormat')
    await execButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.executeMonthlyClose).toHaveBeenCalledWith(
      expect.objectContaining({ year_month: '2026-10' }),
    )
  })

  it('未終了月では確認文を出さない（警告のみ）', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-11')
    expect(wrapper.text()).toContain('shiftBudget.monthlyClose.notEnded')
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.confirm')
  })

  it('有効な過去月では確認文を出す', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-10')
    expect(wrapper.text()).toContain('shiftBudget.monthlyClose.confirm:2026-10')
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.invalidFormat')
  })

  it('409 で拒否された月は再送できない（ボタン無効・API 再呼び出しなし）', async () => {
    api.executeMonthlyClose.mockRejectedValue({
      status: 409,
      data: { error: { code: 'SHIFT_BUDGET_032', message: 'x' } },
    })
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-10')
    await execButton(wrapper).trigger('click')
    await flushPromises()
    await execButton(wrapper).trigger('click')
    await flushPromises()
    expect(api.executeMonthlyClose).toHaveBeenCalledTimes(1)
  })

  it('BE が 409 SHIFT_BUDGET_032 を返したら notEnded をダイアログ内に出し、閉じない', async () => {
    api.executeMonthlyClose.mockRejectedValue({
      status: 409,
      data: { error: { code: 'SHIFT_BUDGET_032', message: 'x' } },
    })
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-10')
    await execButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-test="dialog"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="dialog"]').text()).toContain(
      'shiftBudget.monthlyClose.notEnded:2026-10',
    )
    expect(notify.error).toHaveBeenCalledWith('shiftBudget.monthlyClose.notEnded:2026-10')
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.confirm')
    expect(execButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it.each([
    ['403', { status: 403, data: { error: { code: 'SHIFT_BUDGET_027', message: 'x' } } }],
    ['409 SHIFT_BUDGET_028', { status: 409, data: { error: { code: 'SHIFT_BUDGET_028', message: 'x' } } }],
  ])('032 以外のエラー(%s)は汎用エラー通知で、notEnded は出さない', async (_l, err) => {
    api.executeMonthlyClose.mockRejectedValue(err)
    const wrapper = mountPage()
    await flushPromises()
    await openDialog(wrapper)
    await setMonth(wrapper, '2026-10')
    await execButton(wrapper).trigger('click')
    await flushPromises()
    expect(notify.error).toHaveBeenCalledWith('shiftBudget.monthlyClose.error')
    expect(notify.error).not.toHaveBeenCalledWith(expect.stringContaining('notEnded'))
    expect(wrapper.text()).not.toContain('shiftBudget.monthlyClose.notEnded')
  })
})
