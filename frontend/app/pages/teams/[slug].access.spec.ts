// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import TeamShell from './[slug].vue'

// CMP-261124-0159 PR-F1: 親シェルの 403/404 出し分け（AC-0159-86・87）と遷移時の再判定（AC-0159-98）。
const mocks = vi.hoisted(() => ({
  route: { current: {} as Record<string, unknown> },
  getTeam: vi.fn(),
  resolve: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
  followFetch: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
vi.mock('~/components/TeamSidebar.vue', () => ({ default: { template: '<div />' } }))
mockNuxtImport('useRoute', () => () => mocks.route.current)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useSlugRedirect', () => () => ({ resolveSlug: mocks.resolve }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useScopeLabels', () => () => ({ templateLabel: {}, visibilityLabel: {} }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: ref('MEMBER'),
  roleResolved: ref(true),
  roleLoadFailed: ref(false),
  permissions: ref([]),
  loading: ref(false),
  isAdmin: ref(false),
  isScopeAdmin: ref(false),
  canGrantAdminOnlyPermissions: ref(false),
  isAdminOrDeputy: ref(false),
  loadPermissions: async () => ({ ok: true }),
}))
mockNuxtImport('useDashboardWidgetVisibility', () => () => ({ settings: ref([]), fetch: async () => {} }))
mockNuxtImport('useScopeFeatureVisibility', () => () => ({ error: ref(null), isVisible: () => true }))
mockNuxtImport('useScopeFollowWiring', () => () => ({
  followStatus: ref('NONE'),
  followLoading: ref(false),
  followPermissionSyncError: ref(false),
  showCancelSupporterConfirm: ref(false),
  fetchFollowStatus: mocks.followFetch,
}))
mockNuxtImport('useJoinRequestSelfStatus', () => () => ({
  joinRequestStatus: ref('NONE'),
  joinRequestLoading: ref(false),
}))
mockNuxtImport('useModuleApi', () => () => ({ getTeamModules: async () => ({ data: [] }) }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const Shell = defineComponent({
  setup: (_, { slots }) => () => h('section', [slots.header?.(), slots.default?.()]),
})
const Denied = defineComponent({
  props: ['kind'],
  setup: props => () => h('div', { 'data-testid': 'denied', 'data-kind': props.kind }),
})
const Page = defineComponent({ setup: () => () => h('div', { 'data-testid': 'child-page' }) })

function setRoute(path: string, slug = 'a') {
  Object.assign(mocks.route.current, { path, params: { slug }, query: {}, hash: '' })
}
async function settle() {
  await flushPromises()
  await nextTick()
}
function mountShell() {
  return mount(TeamShell, {
    global: {
      stubs: {
        ScopePageShell: Shell,
        TeamPageHeader: true,
        TeamAccessDeniedState: Denied,
        NuxtPage: Page,
        NuxtLink: true,
        LoadingBounce: true,
        Button: true,
        Dialog: true,
        MemberPermissionSetupDialog: true,
        DashboardErrorState: true,
      },
    },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.route.current = reactive({})
  setRoute('/teams/a/bulletin')
  mocks.resolve.mockResolvedValue({ status: 'NOT_FOUND' })
  mocks.getTeam.mockResolvedValue({ data: { slug: 'a', id: 'a', numericId: 1, basicInfo: { name: 'A' } } })
})

describe('親シェル [slug].vue の取得失敗の出し分け（AC-0159-86・87）', () => {
  it('403 は TeamAccessDeniedState(forbidden) を出し、トーストなし・子ページ非描画', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 403 } })
    const wrapper = mountShell()
    await settle()
    expect(wrapper.find('[data-testid="denied"]').attributes('data-kind')).toBe('forbidden')
    expect(mocks.error).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('common.scopeShell.load_error_title')
    wrapper.unmount()
  })

  it('403 のあとフォロー状態など本文系の取得をしない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 403 } })
    const wrapper = mountShell()
    await settle()
    expect(mocks.followFetch).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('404（slug 移転なし）は TeamAccessDeniedState(notFound) を出し、トーストなし', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountShell()
    await settle()
    expect(mocks.resolve).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="denied"]').attributes('data-kind')).toBe('notFound')
    expect(mocks.error).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('5xx は従来の再試行表示とトーストで、理由表示部品は出さない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 503 } })
    const wrapper = mountShell()
    await settle()
    expect(wrapper.find('[data-testid="denied"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('common.scopeShell.load_error_title')
    expect(mocks.error).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('MEMBER（200）は従来どおり子ページを描画する', async () => {
    const wrapper = mountShell()
    await settle()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="denied"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('シェル内遷移時の再判定（AC-0159-98）', () => {
  it('同一 slug のシェル内遷移でチーム詳細を再取得し、判定が終わるまで子ページを描画しない', async () => {
    const wrapper = mountShell()
    await settle()
    expect(mocks.getTeam).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)

    const pending = deferred<{ data: unknown }>()
    mocks.getTeam.mockReturnValueOnce(pending.promise)
    setRoute('/teams/a/schedule')
    await settle()
    expect(mocks.getTeam).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)

    pending.resolve({ data: { slug: 'a', id: 'a', numericId: 1, basicInfo: { name: 'A' } } })
    await settle()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)
    expect(mocks.getTeam).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  it('非公開化後の遷移は 403 になり、理由表示に切り替わり子ページは出ない（トーストなし）', async () => {
    const wrapper = mountShell()
    await settle()
    mocks.getTeam.mockRejectedValueOnce({ response: { status: 403 } })
    setRoute('/teams/a/schedule')
    await settle()
    expect(wrapper.find('[data-testid="denied"]').attributes('data-kind')).toBe('forbidden')
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
    expect(mocks.error).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('連続遷移では最新の再判定が終わるまで子ページを描かない', async () => {
    const wrapper = mountShell()
    await settle()
    const first = deferred<{ data: unknown }>()
    const second = deferred<{ data: unknown }>()
    mocks.getTeam.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    setRoute('/teams/a/schedule')
    await settle()
    setRoute('/teams/a/todos')
    await settle()
    first.resolve({ data: { slug: 'a', id: 'a', numericId: 1, basicInfo: { name: 'A' } } })
    await settle()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
    second.reject({ response: { status: 403 } })
    await settle()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="denied"]').attributes('data-kind')).toBe('forbidden')
    wrapper.unmount()
  })

  it('再判定が 5xx・通信失敗なら古いチームで本文を出さず、再試行表示にする', async () => {
    const wrapper = mountShell()
    await settle()
    mocks.getTeam.mockRejectedValueOnce({ response: { status: 503 } })
    setRoute('/teams/a/schedule')
    await settle()
    expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="denied"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('common.scopeShell.load_error_title')
    expect(mocks.error).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('別 slug への遷移は従来の slug 変更処理 1 回だけで、再判定を二重に走らせない', async () => {
    const wrapper = mountShell()
    await settle()
    mocks.getTeam.mockClear()
    setRoute('/teams/b/bulletin', 'b')
    await settle()
    expect(mocks.getTeam).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
