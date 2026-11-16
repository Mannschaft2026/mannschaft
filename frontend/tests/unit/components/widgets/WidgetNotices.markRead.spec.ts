// @vitest-environment happy-dom
/**
 * CMP-261030-0652: WidgetNotices の既読化（実 mount）。
 * 既読の通知で POST /read を送らない・失敗しても未処理の reject を出さない・
 * 応答前にタブを切り替えても押した通知が既読になる、をコンポーネントの実装そのもので検証する。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { computed, onMounted, ref, watch } from 'vue'
import { createI18n } from 'vue-i18n'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import WidgetNotices from '~/components/widgets/WidgetNotices.vue'

const mocks = vi.hoisted(() => {
  const m = {
  getNotices: vi.fn(),
  markNoticeRead: vi.fn(),
  markAllNoticesRead: vi.fn(),
  getPlatformAnnouncements: vi.fn(),
  api: vi.fn(),
  captureQuiet: vi.fn(),
  success: vi.fn(),
  fetchActionRequired: vi.fn(),
  store: {
    foldersFor: vi.fn(),
    unreadCountOf: vi.fn(),
    refreshNotificationSummary: vi.fn(),
    fetchAll: vi.fn(),
  },
  }
  return { ...m, dashboardApi: () => ({
    getNotices: m.getNotices,
    markNoticeRead: m.markNoticeRead,
    markAllNoticesRead: m.markAllNoticesRead,
    getPlatformAnnouncements: m.getPlatformAnnouncements,
  }) }
})


vi.mock('~/composables/useDashboardApi', () => ({ useDashboardApi: mocks.dashboardApi }))
vi.mock('~/composables/useApi', () => ({ useApi: () => mocks.api }))
vi.mock('~/composables/useErrorReport', () => ({
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: mocks.success }),
}))
vi.mock('~/composables/usePersonalActionRequired', () => ({
  usePersonalActionRequired: () => ({ fetchActionRequired: mocks.fetchActionRequired }),
}))
vi.mock('~/composables/useScopeFoldersStore', () => ({ useScopeFoldersStore: () => mocks.store }))

vi.mock('#imports', () => ({
  ref,
  computed,
  watch,
  onMounted,
  useDashboardApi: mocks.dashboardApi,
  useApi: () => mocks.api,
  useErrorReport: () => ({ captureQuiet: mocks.captureQuiet }),
  useNotification: () => ({ success: mocks.success }),
  usePersonalActionRequired: () => ({ fetchActionRequired: mocks.fetchActionRequired }),
  useScopeFoldersStore: () => mocks.store,
  useI18n: () => ({ t: (key: string) => key }),
}))

const i18n = createI18n({ legacy: false, locale: 'ja', missingWarn: false, fallbackWarn: false, messages: { ja: {}, en: {}, zh: {}, ko: {}, es: {}, de: {} } })

const stubs = {
  DashboardWidgetCard: { template: '<div><slot /></div>' },
  DashboardEmptyState: { template: '<div />' },
  CirculationConfirmModal: { template: '<div />' },
  SurveyAnswerModal: { template: '<div />' },
  AttendanceQuickModal: { template: '<div />' },
  Badge: { template: '<span />' },
  Button: { template: '<button type="button" />' },
  NuxtLink: RouterLink,
}

const apiNotice = (id: number, isRead: boolean, actionUrl: string | null) => ({
  id,
  type: 'SYSTEM',
  title: `通知${id}`,
  body: null,
  is_read: isRead,
  created_at: '2026-11-10T09:00:00',
  action_url: actionUrl,
})

const page = { template: '<div />' }
const makeRouter = () => createRouter({
  history: createMemoryHistory(),
  routes: [
    { path: '/', component: page },
    { path: '/schedules/1', component: page },
    { path: '/notifications', component: page },
  ],
})

async function mountWidget(items: ReturnType<typeof apiNotice>[]) {
  mocks.getNotices.mockResolvedValue({ data: { items } })
  const router = makeRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(WidgetNotices, { global: { stubs, plugins: [i18n, router], mocks: { $t: (k: string) => k } } })
  await flushPromises()
  return Object.assign(wrapper, { router })
}

const dotOf = (wrapper: ReturnType<typeof mount>, id: number) => {
  const row = wrapper.findAll('.divide-y > div').find(r => r.text().includes(`通知${id}`))
  return row!.get('div.h-2.w-2')
}

describe('WidgetNotices 既読化（CMP-261030-0652）', () => {
  const unhandled: unknown[] = []
  const onUnhandled = (reason: unknown) => {
    unhandled.push(reason)
  }

  beforeEach(() => {
    vi.clearAllMocks()
    unhandled.length = 0
    process.on('unhandledRejection', onUnhandled)
    mocks.markNoticeRead.mockResolvedValue(undefined)
    mocks.getPlatformAnnouncements.mockResolvedValue({ data: [] })
    mocks.fetchActionRequired.mockResolvedValue({ items: [] })
    mocks.store.foldersFor.mockImplementation((scope: string) =>
      scope === 'TEAM' ? [{ id: 11, name: '部活', isDefault: false }] : [])
    mocks.store.unreadCountOf.mockReturnValue(0)
    mocks.store.refreshNotificationSummary.mockResolvedValue(undefined)
    mocks.store.fetchAll.mockResolvedValue(undefined)
    mocks.api.mockResolvedValue({
      data: [{
        id: 2, notificationType: 'SYSTEM', title: '通知2', body: null, isRead: false,
        actionUrl: null, createdAt: '2026-11-10T09:00:00',
      }],
    })
  })

  afterEach(() => {
    process.off('unhandledRejection', onUnhandled)
  })

  it('AC-7 既読の通知を押しても markNoticeRead は呼ばれない（リンク・テキストの両方）', async () => {
    const wrapper = await mountWidget([apiNotice(1, true, '/schedules/1'), apiNotice(3, true, null)])

    await wrapper.get('a[href="/schedules/1"]').trigger('click')
    await wrapper.findAll('p').find(p => p.text() === '通知3')!.trigger('click')
    await flushPromises()

    expect(mocks.markNoticeRead).not.toHaveBeenCalled()
    // NuxtLink の既読通知は POST せずに遷移先へ移る
    expect(wrapper.router.currentRoute.value.fullPath).toBe('/schedules/1')
    wrapper.unmount()
  })

  it('AC-8 未読の通知を押すと markNoticeRead が1回だけ呼ばれ、表示が既読になる', async () => {
    const wrapper = await mountWidget([apiNotice(1, false, null)])
    expect(dotOf(wrapper, 1).classes()).toContain('bg-primary')

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await flushPromises()

    expect(mocks.markNoticeRead).toHaveBeenCalledTimes(1)
    expect(mocks.markNoticeRead).toHaveBeenCalledWith(1)
    expect(dotOf(wrapper, 1).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })

  it('AC-9 markNoticeRead が reject しても未処理の reject を出さず、captureQuiet に記録して未読のまま', async () => {
    mocks.markNoticeRead.mockRejectedValue(new Error('409'))
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await flushPromises()
    await new Promise(resolve => setTimeout(resolve, 0))

    expect(unhandled).toHaveLength(0)
    expect(mocks.captureQuiet).toHaveBeenCalled()
    expect(dotOf(wrapper, 1).classes()).toContain('bg-primary')
    wrapper.unmount()
  })

  it('AC-14 応答前に別タブへ切り替えても、成功後に元のタブへ戻ると押した通知は既読になっている', async () => {
    let resolveRead: () => void = () => {}
    mocks.markNoticeRead.mockImplementation(() => new Promise<void>((resolve) => {
      resolveRead = resolve
    }))
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await wrapper.get('[data-testid="widget-notices-tab-TEAM-11"]').trigger('click')
    await flushPromises()
    resolveRead()
    await flushPromises()
    await wrapper.get('[data-testid="widget-notices-tab-all"]').trigger('click')
    await flushPromises()

    expect(dotOf(wrapper, 1).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })
  /** フォルダ通知の応答を後から返すための遅延 Promise。 */
  function deferFolderResponse() {
    let resolveFolder: (value: { data: unknown[] }) => void = () => {}
    mocks.api.mockImplementation(() => new Promise((resolve) => {
      resolveFolder = resolve
    }))
    return (items: Array<{ id: number, isRead: boolean }>) => resolveFolder({
      data: items.map(({ id, isRead }) => ({
        id, notificationType: 'SYSTEM', title: `通知${id}`, body: null, isRead,
        actionUrl: null, createdAt: '2026-11-10T09:00:00',
      })),
    })
  }

  it('フォルダ取得の応答待ち中に「すべて」で既読にした通知は、後着のフォルダ応答でも既読のまま', async () => {
    const resolveFolder = deferFolderResponse()
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.findAll('p').find(p => p.text() === '通知1')!.trigger('click')
    await flushPromises()
    expect(mocks.markNoticeRead).toHaveBeenCalledWith(1)
    // 既読化の成功後に、既読化前の状態（未読）を持つフォルダ応答が届く
    resolveFolder([{ id: 1, isRead: false }, { id: 2, isRead: false }])
    await flushPromises()
    await wrapper.get('[data-testid="widget-notices-tab-TEAM-11"]').trigger('click')
    await flushPromises()

    expect(dotOf(wrapper, 1).classes()).toContain('bg-surface-300')
    // 既読化していない通知は未読のまま
    expect(dotOf(wrapper, 2).classes()).toContain('bg-primary')
    wrapper.unmount()
  })

  it('フォルダ取得の応答待ち中に「全て既読」が成功すると、後着の応答は破棄して取り直し、取り直した状態を表示する', async () => {
    mocks.markAllNoticesRead.mockResolvedValue(undefined)
    const resolveFolder = deferFolderResponse()
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.get('button:not([role="tab"])').trigger('click')
    await flushPromises()
    expect(mocks.markAllNoticesRead).toHaveBeenCalledTimes(1)
    expect(mocks.api).toHaveBeenCalledTimes(1)
    // 既読化前の状態を持つ後着応答は採用しない
    resolveFolder([{ id: 2, isRead: false }])
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledTimes(2)
    resolveFolder([{ id: 2, isRead: true }])
    await flushPromises()
    // 取り直しは1回で止まる
    expect(mocks.api).toHaveBeenCalledTimes(2)
    await wrapper.get('[data-testid="widget-notices-tab-TEAM-11"]').trigger('click')
    await flushPromises()

    expect(dotOf(wrapper, 2).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })

  it('取得中に「全て既読」が成功し、後着応答に既読化後の新着（未読）が含まれる場合、新着は未読のまま表示され、クリックで POST される', async () => {
    mocks.markAllNoticesRead.mockResolvedValue(undefined)
    const resolveFolder = deferFolderResponse()
    const wrapper = await mountWidget([apiNotice(1, false, null)])

    await wrapper.get('button:not([role="tab"])').trigger('click')
    await flushPromises()
    // 既読化後に作られた新着 3 を含む応答（BE の全件既読は実行時点の通知だけを既読にする）
    resolveFolder([{ id: 2, isRead: true }, { id: 3, isRead: false }])
    await flushPromises()
    resolveFolder([{ id: 2, isRead: true }, { id: 3, isRead: false }])
    await flushPromises()
    await wrapper.get('[data-testid="widget-notices-tab-TEAM-11"]').trigger('click')
    await flushPromises()

    expect(dotOf(wrapper, 3).classes()).toContain('bg-primary')
    await wrapper.findAll('p').find(p => p.text() === '通知3')!.trigger('click')
    await flushPromises()
    expect(mocks.markNoticeRead).toHaveBeenCalledWith(3)
    expect(dotOf(wrapper, 3).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })

  it('「全て既読」は取得済みの他タブを推測で既読にせず取り直し、新着の未読を保つ', async () => {
    mocks.markAllNoticesRead.mockResolvedValue(undefined)
    const wrapper = await mountWidget([apiNotice(1, false, null)])
    expect(mocks.api).toHaveBeenCalledTimes(1)
    mocks.api.mockResolvedValue({
      data: [2, 3].map(id => ({
        id, notificationType: 'SYSTEM', title: `通知${id}`, body: null, isRead: id === 2,
        actionUrl: null, createdAt: '2026-11-10T09:00:00',
      })),
    })

    await wrapper.get('button:not([role="tab"])').trigger('click')
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledTimes(2)
    await wrapper.get('[data-testid="widget-notices-tab-TEAM-11"]').trigger('click')
    await flushPromises()

    expect(dotOf(wrapper, 2).classes()).toContain('bg-surface-300')
    expect(dotOf(wrapper, 3).classes()).toContain('bg-primary')
    // 表示中だった「すべて」は既読化の対象だったため既読表示のまま
    await wrapper.get('[data-testid="widget-notices-tab-all"]').trigger('click')
    await flushPromises()
    expect(dotOf(wrapper, 1).classes()).toContain('bg-surface-300')
    wrapper.unmount()
  })
})
