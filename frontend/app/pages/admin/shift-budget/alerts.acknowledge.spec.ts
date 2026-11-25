// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AlertsPage from './alerts.vue'

/**
 * CMP-260910-1607 ③: 予算警告の確認。
 * - 送信中は確認ボタンを無効化し、二重送信しない。
 * - 409（SHIFT_BUDGET_032）を受けたら確認者・時刻つきの上書き確認を出し、
 *   上書きを選んだときだけ overwrite: true で再送する。取り消したら一覧を取り直す。
 */
const api = vi.hoisted(() => ({ listAlerts: vi.fn(), acknowledgeAlert: vi.fn() }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({
    t: (key: string, params?: Record<string, unknown>) => (params ? `${key}:${JSON.stringify(params)}` : key),
  }),
}))
vi.mock('~/composables/shift/useShiftBudgetAdminAccess', () => ({
  useShiftBudgetAdminAccess: () => ({ canManageBudget: ref(true), ensureLoaded: vi.fn() }),
}))
mockNuxtImport('useScopeStore', () => () => ({ current: { type: 'organization', id: '10' } }))
mockNuxtImport('useNotification', () => () => notify)
mockNuxtImport('useShiftBudgetApi', () => () => api)
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (v: string) => `<${v}>` }))

const ALERT = { id: 5, allocation_id: 1, threshold_percent: 80 }

const ListStub = defineComponent({
  name: 'ThresholdAlertList',
  props: { alerts: { type: Array, default: () => [] }, canAcknowledge: Boolean },
  emits: ['acknowledge'],
  setup(_p, { emit }) {
    return () => h('button', { 'data-test': 'open-ack', onClick: () => emit('acknowledge', ALERT) })
  },
})
const DialogStub = defineComponent({
  props: { visible: Boolean },
  setup(p, { slots }) {
    return () => (p.visible ? h('div', { 'data-test': 'dialog' }, [slots.default?.(), slots.footer?.()]) : null)
  },
})
const ButtonStub = defineComponent({
  props: { label: String, disabled: Boolean, loading: Boolean },
  emits: ['click'],
  setup(p, { emit, attrs }) {
    return () => h('button', { ...attrs, disabled: p.disabled || p.loading, onClick: () => emit('click') }, p.label)
  },
})
const MessageStub = defineComponent({
  setup(_p, { slots, attrs }) {
    return () => h('div', attrs, slots.default?.())
  },
})

function mountPage() {
  return mount(AlertsPage, {
    global: {
      stubs: {
        PageHeader: true,
        PageLoading: true,
        Message: MessageStub,
        Dialog: DialogStub,
        Button: ButtonStub,
        Textarea: true,
        ThresholdAlertList: ListStub,
        DashboardErrorState: true,
      },
    },
  })
}

function conflict(details: Record<string, unknown>) {
  return { statusCode: 409, data: { error: { code: 'SHIFT_BUDGET_032', details } } }
}

async function openAck(wrapper: ReturnType<typeof mountPage>) {
  await wrapper.find('[data-test="open-ack"]').trigger('click')
  await flushPromises()
}

describe('警告確認 409 と上書き', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listAlerts.mockResolvedValue([])
  })

  it('409 を受けたら確認者名・時刻つきの上書き確認が出る。上書きすると overwrite: true で再送する', async () => {
    api.acknowledgeAlert
      .mockRejectedValueOnce(conflict({ acknowledgedBy: 7, acknowledgedByName: '山田 太郎', acknowledgedAt: '2026-11-25T10:00:00' }))
      .mockResolvedValueOnce({})
    const wrapper = mountPage()
    await flushPromises()
    await openAck(wrapper)

    await wrapper.find('[data-testid="alert-acknowledge-submit"]').trigger('click')
    await flushPromises()

    const message = wrapper.find('[data-testid="alert-overwrite-message"]')
    expect(message.exists()).toBe(true)
    expect(message.text()).toContain('山田 太郎')
    expect(message.text()).toContain('<2026-11-25T10:00:00>')
    expect(notify.error).not.toHaveBeenCalled()
    expect(api.acknowledgeAlert).toHaveBeenCalledTimes(1)
    expect(api.acknowledgeAlert.mock.calls[0]![2]).not.toHaveProperty('overwrite')

    await wrapper.find('[data-testid="alert-overwrite-submit"]').trigger('click')
    await flushPromises()

    expect(api.acknowledgeAlert).toHaveBeenCalledTimes(2)
    expect(api.acknowledgeAlert.mock.calls[1]![2]).toMatchObject({ overwrite: true })
    expect(notify.success).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="alert-overwrite-message"]').exists()).toBe(false)
  })

  it('確認者名が応答に無いときは ID ではなく代替の表示名を使う', async () => {
    api.acknowledgeAlert.mockRejectedValueOnce(conflict({ acknowledgedBy: 7, acknowledgedAt: '2026-11-25T10:00:00' }))
    const wrapper = mountPage()
    await flushPromises()
    await openAck(wrapper)
    await wrapper.find('[data-testid="alert-acknowledge-submit"]').trigger('click')
    await flushPromises()

    const text = wrapper.find('[data-testid="alert-overwrite-message"]').text()
    expect(text).toContain('shiftBudget.alert.unknownAcknowledger')
    expect(text).not.toContain('"name":"7"')
  })

  it('上書きを取り消したら再送せず、一覧を取り直す', async () => {
    api.acknowledgeAlert.mockRejectedValueOnce(conflict({ acknowledgedBy: 7, acknowledgedByName: '山田 太郎', acknowledgedAt: '2026-11-25T10:00:00' }))
    const wrapper = mountPage()
    await flushPromises()
    await openAck(wrapper)
    await wrapper.find('[data-testid="alert-acknowledge-submit"]').trigger('click')
    await flushPromises()
    expect(api.listAlerts).toHaveBeenCalledTimes(1)

    await wrapper.find('[data-testid="alert-overwrite-cancel"]').trigger('click')
    await flushPromises()

    expect(api.acknowledgeAlert).toHaveBeenCalledTimes(1)
    expect(api.listAlerts).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="alert-overwrite-message"]').exists()).toBe(false)
  })

  it('409 以外の失敗は握りつぶさずエラー通知し、上書き確認は出さない', async () => {
    api.acknowledgeAlert.mockRejectedValueOnce({ statusCode: 500 })
    const wrapper = mountPage()
    await flushPromises()
    await openAck(wrapper)
    await wrapper.find('[data-testid="alert-acknowledge-submit"]').trigger('click')
    await flushPromises()

    expect(notify.error).toHaveBeenCalledWith('shiftBudget.alert.acknowledgeError')
    expect(wrapper.find('[data-testid="alert-overwrite-message"]').exists()).toBe(false)
  })

  it('送信中は確認ボタンが無効で、連打しても 1 回しか送らない', async () => {
    let resolveAck: (v: unknown) => void = () => {}
    api.acknowledgeAlert.mockReturnValueOnce(new Promise((r) => { resolveAck = r }))
    const wrapper = mountPage()
    await flushPromises()
    await openAck(wrapper)

    const submit = wrapper.find('[data-testid="alert-acknowledge-submit"]')
    await submit.trigger('click')
    await submit.trigger('click')
    await flushPromises()

    expect(api.acknowledgeAlert).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="alert-acknowledge-submit"]').attributes('disabled')).toBeDefined()

    resolveAck({})
    await flushPromises()
    expect(notify.success).toHaveBeenCalledTimes(1)
  })
})
