import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h, type Component } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { TEAM_ACCESS_LEDGER } from './teamAccessLedger'

// CMP-261124-0159 PR-F2a（試練）: 系統 2 のうち F2a 担当 16 セグメント・34 画面を表駆動で検証する。
// 2026-11-27 マスター裁定（CMP-261125-1108 に統一）: チーム詳細が 403 でも 404 でも同じ中立な「見つかりません」（ScopeNotFound）。
// 理由表示（AC-0159-86・87）は取り下げ。各画面は useTeamAccessGate を先に通し、403/404/判定中/通信失敗の間は本文 API を一切呼ばない。
// 本文 API の観測: useApi()（全 API 合成関数の共通入口）と $fetch / fetch をスパイに差し替え、呼び出し 0 回を確かめる。
const F2A_SEGMENTS = new Set([
  'activity-templates', 'admin', 'advertiser', 'analytics', 'anniversaries', 'announcements', 'annual-plan', 'audit-logs',
  'billing', 'budget', 'charts', 'coin-toss', 'direct-mail', 'duties', 'equipment', 'events',
])
const segmentOf = (file: string) => (file.includes('/') ? file.split('/')[0]! : file.replace(/\.vue$/, ''))
const F2A_FILES = TEAM_ACCESS_LEDGER.filter(e => e.system === 2 && F2A_SEGMENTS.has(segmentOf(e.file))).map(e => e.file)

const mocks = vi.hoisted(() => ({
  getTeam: vi.fn(),
  resolve: vi.fn(),
  navigate: vi.fn(),
  handleApiError: vi.fn(),
  api: vi.fn(),
  rawFetch: vi.fn(),
  route: {
    path: '/teams/a/x',
    query: {},
    hash: '',
    params: new Proxy({} as Record<string, string>, { get: (_t, k) => (k === 'slug' ? 'a' : '1') }),
  },
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, tn: (key: string) => key, locale: { value: 'ja' } }))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError, getFieldErrors: () => ({}) }))
mockNuxtImport('useSlugRedirect', () => () => ({ resolveSlug: mocks.resolve }))
mockNuxtImport('navigateTo', () => mocks.navigate)
vi.mock('primevue/usetoast', () => ({ useToast: () => ({ add: vi.fn() }) }))
vi.mock('primevue/useconfirm', () => ({ useConfirm: () => ({ require: vi.fn() }) }))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn(), showSuccess: vi.fn(), showError: vi.fn(), showInfo: vi.fn(), showWarn: vi.fn(),
}))
mockNuxtImport('useApi', () => () => Object.assign(mocks.api, { raw: mocks.api }))

const pageModules = import.meta.glob<{ default: Component }>('../../../app/pages/teams/*/**/*.vue')
const loaderOf = (file: string) => {
  const key = Object.keys(pageModules).find(k => k.endsWith('/pages/teams/[slug]/' + file))
  if (!key) throw new Error('page not found: ' + file)
  return pageModules[key]!
}

const NotFound = defineComponent({ setup: () => () => h('div', { 'data-testid': 'scope-not-found' }) })

async function mountPage(file: string) {
  const page = (await loaderOf(file)()).default
  return mountSuspended(page, { shallow: true, global: { stubs: { ScopeNotFound: NotFound } } })
}

function bodyCalls(): number {
  return mocks.api.mock.calls.length + mocks.rawFetch.mock.calls.length
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.api.mockResolvedValue({ data: [] })
  mocks.resolve.mockResolvedValue({ status: 'NOT_FOUND' })
  vi.stubGlobal('$fetch', mocks.rawFetch)
  vi.stubGlobal('fetch', mocks.rawFetch)
})

describe('F2a の対象画面が台帳どおり 34 件ある', () => {
  it('台帳から 34 件を拾える', () => {
    expect(F2A_FILES).toHaveLength(34)
  })
})

describe.each(F2A_FILES)('%s（CMP-261124-0159 PR-F2a）', (file) => {
  it('403: 「見つかりません」を出し、本文 API を呼ばない（404 と同一表示）', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 403 } })
    const wrapper = await mountPage(file)
    await flushPromises()
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
    expect(bodyCalls()).toBe(0)
  })

  it('404: 「見つかりません」を出し、本文 API を呼ばない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 404 } })
    const wrapper = await mountPage(file)
    await flushPromises()
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
    expect(bodyCalls()).toBe(0)
  })

  it('判定中: チーム詳細の応答が返るまで本文 API を呼ばない', async () => {
    mocks.getTeam.mockReturnValue(new Promise(() => {}))
    await mountPage(file)
    await flushPromises()
    expect(mocks.getTeam).toHaveBeenCalledWith('a')
    expect(bodyCalls()).toBe(0)
  })

  it('通信失敗（500）: 「見つかりません」にせず、本文 API も呼ばない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 500 } })
    const wrapper = await mountPage(file)
    await flushPromises()
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
    expect(mocks.handleApiError).toHaveBeenCalledTimes(1)
    expect(bodyCalls()).toBe(0)
  })

  it('200: 従来どおり本文を描き、「見つかりません」は出さない', async () => {
    mocks.getTeam.mockResolvedValue({ data: { id: 'a', numericId: 42 } })
    const wrapper = await mountPage(file)
    await flushPromises()
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
    expect(mocks.getTeam).toHaveBeenCalledWith('a')
  })
})
