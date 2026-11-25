import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import WidgetPersonalEffort from './WidgetPersonalEffort.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

const stubs = { DashboardWidgetCard: { props: ['to', 'title'], template: '<div :data-to="to"><slot /></div>' } }

function rec(id: string, name: string, recordedOn: string) {
  return { id, recordedOn, definition: { templateName: name } }
}

async function mountWith(handler: (query: Record<string, unknown>) => unknown) {
  apiMock.mockImplementation(async (url: string, opts?: { query?: Record<string, unknown> }) => {
    // エラー報告（captureQuiet）が同じ useApi を使うため、頑張り記録 API の URL だけに応答を返す
    if (url !== '/api/v1/effort-records/me') return {}
    return handler(opts?.query ?? {})
  })
  const w = await mountSuspended(WidgetPersonalEffort, { global: { stubs } })
  await flushPromises()
  return w
}

describe('WidgetPersonalEffort', () => {
  beforeEach(() => apiMock.mockReset())

  it('個人 API（/api/v1/effort-records/me）だけを呼ぶ', async () => {
    await mountWith(() => ({ data: [] }))
    const urls = apiMock.mock.calls.map(c => String(c[0]))
    expect(urls.filter(u => u === '/api/v1/effort-records/me')).toHaveLength(2)
  })

  it('今日の件数と最近の記録を表示する', async () => {
    const w = await mountWith(q => (q.date_from ? { data: [rec('1', '勉強', '2026-11-25'), rec('2', '運動', '2026-11-25')] } : { data: [rec('1', '勉強', '2026-11-25'), rec('3', '体重', '2026-11-24')] }))
    expect(w.find('[data-testid="effort-widget-today"]').text()).toContain('2')
    const recent = w.find('[data-testid="effort-widget-recent"]').text()
    expect(recent).toContain('勉強')
    expect(recent).toContain('体重')
  })

  it('0件のときは空表示と導線を出す', async () => {
    const w = await mountWith(() => ({ data: [] }))
    expect(w.find('[data-testid="effort-widget-recent"]').exists()).toBe(false)
    expect(w.find('[data-testid="effort-widget-today"]').text()).toContain('0')
    expect(w.find('[data-testid="effort-widget-record"]').exists()).toBe(true)
  })

  it('取得に失敗したらエラー表示にし、0件の空表示とは区別する', async () => {
    const w = await mountWith(() => { throw new Error('boom') })
    expect(w.find('[data-testid="effort-widget-error"]').exists()).toBe(true)
    expect(w.find('[data-testid="effort-widget-today"]').exists()).toBe(false)
  })

  it('「記録する」「振り返る」と見出しの遷移先は /my/effort-records', async () => {
    const w = await mountWith(() => ({ data: [] }))
    expect(w.find('[data-testid="effort-widget-record"]').attributes('href')).toBe('/my/effort-records')
    expect(w.find('[data-testid="effort-widget-review"]').attributes('href')).toBe('/my/effort-records')
    expect(w.find('[data-to]').attributes('data-to')).toBe('/my/effort-records')
  })
})
