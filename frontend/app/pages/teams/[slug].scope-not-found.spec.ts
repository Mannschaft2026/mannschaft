// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick, reactive, ref } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import OrganizationShell from '../organizations/[slug].vue'
import TeamShell from './[slug].vue'

/**
 * CMP-261125-1108 非所属・権限なしのチーム／組織ページを共通の「見つかりません」表示にする（シェル側）。
 *
 *   SNF-S1 シェルの詳細取得が 403 → ScopeNotFound を表示。トースト（handleApiError）なし・再試行なし
 *   SNF-S2 シェルの詳細取得が 404（MOVED でない）→ 403 と同じ
 *   SNF-S3 シェルの詳細取得が 500 → 従来の汎用エラー（load_error_*）のまま（チームシェルは従来どおり再試行あり）
 *   SNF-S4 子ページが markIfDenied で notFound を立てたら、本文（NuxtPage）を ScopeNotFound に置き換える。
 *          シェルのヘッダ（タブ）は残す
 *   SNF-S5 非シェルルート（timetable 等・bare NuxtPage）でも、子が notFound を立てたら本文を置き換える
 *   SNF-S6（AC-8）slug が変わったら notFound を reset し、新しいスコープの本文を表示する
 *   SNF-S7 非所属 X の子ページで応答待ちのまま Y へ遷移 → X の遅延 403 が届いても Y は置き換えない
 *   SNF-S8 応答待ちのままシェル外へ遷移 → 遅延 403 が届いても、後続のシェル初回描画は置き換えない
 *   SNF-S9 クエリだけの変更（ブラウザバック含む fullPath 変更）でも notFound を reset する
 *   SNF-S10 子が notFound を立てたら、機能可視性のエラー表示（DashboardErrorState）と二重に出さない
 *
 * マウント流儀は同階層の [slug].moved.spec.ts に合わせる。
 */
const mocks = vi.hoisted(() => ({
  route: { current: {} as Record<string, unknown> },
  getDetail: vi.fn(),
  resolve: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
  notifyError: vi.fn(),
  featureError: { value: null as unknown },
  /** useState の代替（happy-dom 環境は Nuxt アプリが無い）。キー単位で同じ ref を返す */
  states: new Map<string, unknown>(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
vi.mock('~/components/OrganizationSidebar.vue', () => ({ default: { template: '<div />' } }))
vi.mock('~/components/TeamSidebar.vue', () => ({ default: { template: '<div />' } }))
mockNuxtImport('useRoute', () => () => mocks.route.current)
mockNuxtImport('useState', () => <T>(key: string, init?: () => T) => {
  if (!mocks.states.has(key)) mocks.states.set(key, ref(init?.()))
  return mocks.states.get(key)
})
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useSlugRedirect', () => () => ({ resolveSlug: mocks.resolve }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getDetail }))
mockNuxtImport('useOrganizationApi', () => () => ({
  getOrganization: mocks.getDetail,
  getTeamsInOrg: async () => ({ data: [] }),
  getPermissionGroups: async () => ({ data: [] }),
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: mocks.notifyError }))
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
mockNuxtImport('useDashboardWidgetVisibility', () => () => ({
  settings: ref([]),
  fetch: async () => {},
}))
mockNuxtImport('useScopeFeatureVisibility', () => () => ({
  error: mocks.featureError,
  isVisible: () => true,
}))
mockNuxtImport('useScopeFollowWiring', () => () => ({
  followStatus: ref('NONE'),
  followLoading: ref(false),
  followPermissionSyncError: ref(false),
  showCancelSupporterConfirm: ref(false),
  fetchFollowStatus: async () => {},
}))
mockNuxtImport('useJoinRequestSelfStatus', () => () => ({
  joinRequestStatus: ref('NONE'),
  joinRequestLoading: ref(false),
}))
mockNuxtImport('useOrgHierarchy', () => () => ({
  ancestors: ref([]),
  children: ref([]),
  loading: ref(false),
  childrenHasNext: ref(false),
  fetchAncestors: async () => {},
  fetchChildren: async () => {},
}))
mockNuxtImport('useModuleApi', () => () => ({ getTeamModules: async () => ({ data: [] }) }))

type ScopeNotFoundApi = {
  notFound: { value: boolean }
  markIfDenied: (err: unknown) => boolean
  reset: () => void
}

/**
 * 新規 composable（frontend/app/composables/useScopeNotFound.ts）は試練時点で未実装。
 * 静的 import にするとファイル全体が解決失敗で落ち、他のケースの red 理由が見えなくなるため、
 * 子ページ側の操作を再現するケースだけ動的 import で取り込む。
 */
const SCOPE_NOT_FOUND_MODULE = '~/composables/useScopeNotFound'
async function scopeNotFound(): Promise<ScopeNotFoundApi> {
  const mod = (await import(/* @vite-ignore */ SCOPE_NOT_FOUND_MODULE)) as {
    useScopeNotFound: () => ScopeNotFoundApi
  }
  return mod.useScopeNotFound()
}

const Header = defineComponent({
  props: ['displayName', 'org'],
  setup: (props) => () => h('div', { 'data-testid': 'shell-header' }, props.displayName ?? props.org?.basicInfo?.name),
})
const Shell = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('section', { 'data-testid': 'shell-tabs' }, [slots.header?.(), slots.default?.()]),
})
const ChildPage = { template: '<div data-testid="child-page" />' }
const NotFoundStub = { template: '<div data-testid="scope-not-found" />' }
const ButtonStub = {
  props: ['label'],
  template: '<button type="button" data-testid="button">{{ label }}</button>',
}

function setRoute(scope: string, slug: string, tail = 'info', search = '') {
  Object.assign(mocks.route.current, {
    path: `/${scope}/${slug}/${tail}`,
    fullPath: `/${scope}/${slug}/${tail}${search}`,
    params: { slug },
    query: {},
    hash: '',
  })
}
async function settle() {
  await flushPromises()
  await nextTick()
  await flushPromises()
}
function mountShell(scope: 'organizations' | 'teams') {
  return mount(scope === 'organizations' ? OrganizationShell : TeamShell, {
    global: {
      stubs: {
        ScopePageShell: Shell,
        OrgPageHeader: Header,
        TeamPageHeader: Header,
        NuxtPage: ChildPage,
        NuxtLink: true,
        LoadingBounce: true,
        Button: ButtonStub,
        OrganizationSidebar: true,
        TeamSidebar: true,
        Dialog: true,
        MemberPermissionSetupDialog: true,
        DashboardErrorState: { template: '<div data-testid="dashboard-error" />' },
        ScopeNotFound: NotFoundStub,
      },
    },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.states.clear()
  mocks.featureError = ref(null)
  mocks.route.current = reactive({})
  mocks.getDetail.mockImplementation(async (slug: string) => ({
    data: { slug, id: slug, numericId: 1, basicInfo: { name: `成功:${slug}` } },
  }))
  mocks.resolve.mockResolvedValue({ status: 'NOT_FOUND' })
})

for (const scope of ['organizations', 'teams'] as const) {
  describe(`${scope} シェル: 403/404 は共通の「見つかりません」（CMP-261125-1108）`, () => {
    for (const status of [403, 404] as const) {
      it(`SNF-S${status === 403 ? 1 : 2}: 詳細取得が ${status} なら ScopeNotFound を出し、トースト・再試行・汎用エラーを出さない`, async () => {
        setRoute(scope, 'secret')
        mocks.getDetail.mockRejectedValueOnce({ response: { status }, statusCode: status })
        const wrapper = mountShell(scope)
        await settle()

        expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
        expect(mocks.error).not.toHaveBeenCalled()
        expect(mocks.notifyError).not.toHaveBeenCalled()
        expect(wrapper.text()).not.toContain('common.scopeShell.retry')
        expect(wrapper.text()).not.toContain('common.scopeShell.load_error_title')
        expect(mocks.navigate).not.toHaveBeenCalled()
        wrapper.unmount()
      })
    }

    it('SNF-S3: 詳細取得が 500 なら従来どおり汎用エラー（チームは再試行も）で、ScopeNotFound は出さない', async () => {
      setRoute(scope, 'broken')
      mocks.getDetail.mockRejectedValueOnce({ response: { status: 500 }, statusCode: 500 })
      const wrapper = mountShell(scope)
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
      expect(wrapper.text()).toContain('common.scopeShell.load_error_title')
      // 再試行はチームシェルの既存挙動。組織シェルのフォールバックは従来から再試行を持たない（本戦役の対象外）
      if (scope === 'teams') expect(wrapper.text()).toContain('common.scopeShell.retry')
      expect(mocks.error).toHaveBeenCalled()
      wrapper.unmount()
    })

    it('SNF-S4: 子ページが notFound を立てたら本文を ScopeNotFound に置き換え、ヘッダ（タブ）は残す', async () => {
      setRoute(scope, 'a')
      const wrapper = mountShell(scope)
      await settle()
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)

      const state = await scopeNotFound()
      expect(state.markIfDenied({ statusCode: 403 })).toBe(true)
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="shell-tabs"]').exists()).toBe(true)
      expect(wrapper.text()).toContain('成功:a')
      expect(mocks.error).not.toHaveBeenCalled()
      expect(mocks.notifyError).not.toHaveBeenCalled()
      state.reset()
      wrapper.unmount()
    })

    it('SNF-S5: 非シェルルート（timeline-digest）でも子が notFound を立てたら本文を置き換える', async () => {
      setRoute(scope, 'a', 'timeline-digest')
      const wrapper = mountShell(scope)
      await settle()
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)

      const state = await scopeNotFound()
      state.markIfDenied({ response: { status: 404 } })
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
      state.reset()
      wrapper.unmount()
    })

    it('SNF-S6（AC-8）: slug が変わると notFound が reset され、新しいスコープの本文を表示する', async () => {
      setRoute(scope, 'a')
      const wrapper = mountShell(scope)
      await settle()

      const state = await scopeNotFound()
      state.markIfDenied({ statusCode: 403 })
      await settle()
      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)

      setRoute(scope, 'b')
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)
      expect(wrapper.text()).toContain('成功:b')
      const after = await scopeNotFound()
      expect(after.notFound.value).toBe(false)
      wrapper.unmount()
    })

    it('SNF-S7: 非所属 X の子ページで応答待ちのまま Y へ遷移し、reset 後に X の 403 が届いても Y は置き換えない', async () => {
      setRoute(scope, 'x')
      const wrapper = mountShell(scope)
      await settle()
      // X の子ページの setup（応答待ち）
      const xChild = await scopeNotFound()

      setRoute(scope, 'y')
      await settle()
      // Y の子ページの setup
      const yChild = await scopeNotFound()

      // X の遅延 403。古い画面の応答なので黙って捨てる（true を返しトーストは出させない）
      expect(xChild.markIfDenied({ statusCode: 403 })).toBe(true)
      await settle()

      expect(yChild.notFound.value).toBe(false)
      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)
      expect(wrapper.text()).toContain('成功:y')
      expect(mocks.error).not.toHaveBeenCalled()
      expect(mocks.notifyError).not.toHaveBeenCalled()

      // Y 自身の 403 は従来どおり本文を置き換える
      expect(yChild.markIfDenied({ statusCode: 403 })).toBe(true)
      await settle()
      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
      wrapper.unmount()
    })

    it('SNF-S8: 応答待ちのままシェル外へ遷移し、後で 403 が届いても後続のシェル初回描画は置き換えない', async () => {
      setRoute(scope, 'x')
      const first = mountShell(scope)
      await settle()
      const xChild = await scopeNotFound()

      // シェル外へ遷移（シェルのアンマウント）
      first.unmount()
      // 遷移後に X の遅延 403 が届く
      expect(xChild.markIfDenied({ statusCode: 403 })).toBe(true)

      setRoute(scope, 'z')
      const second = mountShell(scope)
      await settle()

      expect(second.find('[data-testid="scope-not-found"]').exists()).toBe(false)
      expect(second.find('[data-testid="child-page"]').exists()).toBe(true)
      expect(second.text()).toContain('成功:z')
      second.unmount()
    })

    it('SNF-S8b: 前回シェルの破棄後に notFound が残っていても、シェルのマウント（setup）で下ろす', async () => {
      const stale = await scopeNotFound()
      stale.markIfDenied({ statusCode: 404 })
      expect(stale.notFound.value).toBe(true)

      setRoute(scope, 'z')
      const wrapper = mountShell(scope)
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)
      wrapper.unmount()
    })

    it('SNF-S9: クエリだけの変更（ブラウザバック等の fullPath 変更）でも notFound を reset する', async () => {
      setRoute(scope, 'a', 'info', '?tab=1')
      const wrapper = mountShell(scope)
      await settle()
      const state = await scopeNotFound()
      state.markIfDenied({ statusCode: 403 })
      await settle()
      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)

      setRoute(scope, 'a', 'info', '?tab=2')
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)
      wrapper.unmount()
    })

    it('SNF-S10: 子が notFound を立てたら、機能可視性のエラー表示と二重に出さず ScopeNotFound だけを出す', async () => {
      mocks.featureError.value = new Error('feature visibility failed')
      setRoute(scope, 'a')
      const wrapper = mountShell(scope)
      await settle()
      // notFound でなければ従来どおりエラー表示と本文を出す
      expect(wrapper.find('[data-testid="dashboard-error"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(true)

      const state = await scopeNotFound()
      state.markIfDenied({ statusCode: 403 })
      await settle()

      expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="dashboard-error"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="child-page"]').exists()).toBe(false)
      wrapper.unmount()
    })
  })
}
