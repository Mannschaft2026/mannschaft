// @vitest-environment node
// 戦役Z-B 試練 S16: useApi はサポート閲覧中だけ X-Support-Access-Grant-Id を付け、
// /api/v1/system-admin/** と /api/v1/auth/** には付けない（トークン更新が壊れない）。旧変身ヘッダーは二度と送らない。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

type OnRequest = (context: { request: string; options: { headers?: Headers } }) => void

const captured = vi.hoisted(() => ({
  onRequest: undefined as undefined | OnRequest,
  auth: { accessToken: null as string | null, isAuthenticated: true },
  desk: {
    isPinned: false,
    pinnedSubjectUserId: 90245,
    pinnedConsentId: 2,
    inputSource: 'PAPER_FORM',
    originalStorageLocation: '',
  },
  guardian: { isActingAs: false, activeChild: { childUserId: 17 } },
  support: {
    isActive: false,
    grantId: '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b' as string | null,
    targetUserId: 42 as number | null,
    expiresAt: null as string | null,
  },
  // 旧ストアが仮に残っていても旧ヘッダーを付けないことを確かめるための影
  legacyImpersonation: { isImpersonating: true, targetUserId: 19 },
  runtimeConfig: () => ({ public: {} }),
  nuxtApp: () => ({
    $i18n: { t: (key: string) => key },
    vueApp: { onUnmount: vi.fn() },
    hook: vi.fn(),
  }),
}))
vi.mock('ofetch', () => ({
  ofetch: {
    create: vi.fn((options) => {
      captured.onRequest = options.onRequest
      return vi.fn()
    }),
  },
}))
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://localhost:8081' }))
vi.mock('~/stores/useAuthStore', () => ({ useAuthStore: () => captured.auth }))
vi.mock('~/stores/useProxyDeskStore', () => ({ useProxyDeskStore: () => captured.desk }))
vi.mock('~/stores/useGuardianshipSwitchStore', () => ({
  useGuardianshipSwitchStore: () => captured.guardian,
}))
vi.mock('~/stores/useSupportAccessStore', () => ({
  useSupportAccessStore: () => captured.support,
}))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({}) }))
vi.mock('#app/nuxt', () => ({
  useRuntimeConfig: captured.runtimeConfig,
  useNuxtApp: captured.nuxtApp,
}))
const { useApi } = await import('./useApi')

const GRANT_HEADER = 'X-Support-Access-Grant-Id'
const LEGACY_HEADER = 'X-Admin-Impersonate-User-Id'

describe('戦役Z-B S16 useApi のサポート閲覧ヘッダー', () => {
  const { auth, desk, guardian, support } = captured

  beforeEach(() => {
    // 旧ストアが残っている間（試練段階）も実体化できるように Pinia を用意する（実装後は旧ストアごと消える）
    setActivePinia(createPinia())
    auth.accessToken = null
    auth.isAuthenticated = true
    desk.isPinned = false
    guardian.isActingAs = false
    support.isActive = true
    support.expiresAt = new Date(Date.now() + 60 * 60 * 1000).toISOString()
    vi.stubGlobal('useRuntimeConfig', captured.runtimeConfig)
    vi.stubGlobal('useAuthStore', () => auth)
    vi.stubGlobal('useProxyDeskStore', () => desk)
    vi.stubGlobal('useGuardianshipSwitchStore', () => guardian)
    vi.stubGlobal('useSupportAccessStore', () => support)
    vi.stubGlobal('useAdminImpersonationStore', () => captured.legacyImpersonation)
    vi.stubGlobal('useNuxtApp', captured.nuxtApp)
    vi.stubGlobal('useErrorReport', () => ({}))
    captured.onRequest = undefined
  })
  afterEach(() => vi.unstubAllGlobals())

  function generate(request: string) {
    useApi()
    const options: { headers?: Headers } = {}
    captured.onRequest!({ request, options })
    return new Headers(options.headers)
  }

  it('S16 Cookie 認証の運営者でも閲覧中は本人向け API に grant ID を付ける', () => {
    const headers = generate('/api/v1/users/me')
    expect(headers.get(GRANT_HEADER)).toBe(support.grantId)
  })

  it('S16 Bearer 認証でも閲覧中は grant ID を付ける', () => {
    auth.accessToken = 'unit-test-token'
    const headers = generate('/api/v1/teams/3/schedules')
    expect(headers.get('Authorization')).toBe('Bearer unit-test-token')
    expect(headers.get(GRANT_HEADER)).toBe(support.grantId)
  })

  it('S13/S16 旧変身ヘッダー X-Admin-Impersonate-User-Id は一切付けない', () => {
    auth.accessToken = 'unit-test-token'
    for (const path of ['/api/v1/users/me', '/api/v1/system-admin/feedbacks', '/api/v1/auth/refresh']) {
      expect(generate(path).has(LEGACY_HEADER)).toBe(false)
    }
  })

  it.each([
    '/api/v1/system-admin/support-access/grants/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b/sessions/end',
    '/api/v1/system-admin/feedbacks',
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
    'http://localhost:8081/api/v1/auth/refresh',
    'http://localhost:8081/api/v1/system-admin/support-access/grants',
  ])('S16 %s には grant ID を付けない（運営操作とトークン更新は運営者本人として行う）', (path) => {
    auth.accessToken = 'unit-test-token'
    expect(generate(path).has(GRANT_HEADER)).toBe(false)
  })

  it('S16 閲覧中でなければ付けない', () => {
    support.isActive = false
    expect(generate('/api/v1/users/me').has(GRANT_HEADER)).toBe(false)
  })

  it('S16 期限（expiresAt）を過ぎたら isActive の更新を待たずに付けない', () => {
    support.expiresAt = new Date(Date.now() - 1000).toISOString()
    expect(generate('/api/v1/users/me').has(GRANT_HEADER)).toBe(false)
  })

  it('S16 ログアウト後（未認証）は付けない', () => {
    auth.isAuthenticated = false
    expect(generate('/api/v1/users/me').has(GRANT_HEADER)).toBe(false)
  })

  it('009 代理入力・後見のヘッダーと同時には送らない（BE は併用を 400 にする）', () => {
    auth.accessToken = 'unit-test-token'
    guardian.isActingAs = true
    const headers = generate('/api/v1/users/me')
    expect(headers.has(GRANT_HEADER) && headers.has('X-Proxy-For-User-Id')).toBe(false)
  })
})
