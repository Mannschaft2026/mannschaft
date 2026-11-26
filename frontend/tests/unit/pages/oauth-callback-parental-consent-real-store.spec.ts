// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useAuthStore } from '~/stores/useAuthStore'
import authMiddleware from '~/middleware/auth.client'

/**
 * CMP-261123-1124 AC1（失敗経路・実ストア＋ガード）: OAuth callback でプロフィール取得に失敗した同意待ち（ppc=true）。
 * 実 useAuthStore と実 auth.client ミドルウェアを通し、user が空のまま pending へ遷移して /login へ差し戻される
 * 状態にならず、ログアウト済み（未認証）の状態で /login へ向かうことを確かめる。
 */
const mocks = vi.hoisted(() => ({
  loginWithOAuth: vi.fn(),
  api: vi.fn(),
  navigateTo: vi.fn(),
  routerPush: vi.fn(),
}))
const routeState = vi.hoisted(() => ({ path: '/auth/oauth/callback', params: {}, query: { code: 'c' } as Record<string, string> }))

vi.mock('../../../app/stores/useTeamStore', () => ({ useTeamStore: () => ({ clear: vi.fn() }) }))
vi.mock('../../../app/stores/useOrganizationStore', () => ({ useOrganizationStore: () => ({ clear: vi.fn() }) }))
vi.mock('../../../app/composables/useScopeFoldersStore', () => ({ useScopeFoldersStore: () => ({ clear: vi.fn() }) }))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: vi.fn(), error: vi.fn(), info: vi.fn(), warn: vi.fn() }),
}))
vi.mock('~/composables/useLocale', () => ({ useLocale: () => ({ applyAccountLocale: vi.fn() }) }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useAuthApi', () => () => ({ loginWithOAuth: mocks.loginWithOAuth }))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useRouter', () => () => ({ push: mocks.routerPush, replace: mocks.routerPush }))
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mocks.navigateTo(...args))
mockNuxtImport('useRuntimeConfig', () => () => ({ public: {} }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useChatTabsStore', () => () => ({ clearAll: vi.fn() }))
mockNuxtImport('disarmProactiveRefresh', () => () => {})
mockNuxtImport('armProactiveRefresh', () => () => {})
mockNuxtImport('useActionMemoStore', () => () => ({ clear: vi.fn() }))

const CallbackPage = (await import('~/pages/auth/oauth/callback.vue')).default

function jwtWith(payload: Record<string, unknown>): string {
  const b64url = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
  return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`
}

async function flush(times = 12): Promise<void> {
  for (let i = 0; i < times; i++) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  localStorage.clear()
  Object.values(mocks).forEach(m => m.mockReset())
})

describe('OAuth callback 失敗経路（実ストア＋ガード）', () => {
  it('ppc=true でプロフィール取得に失敗: 未認証のまま /login へ向かい、pending へ送って差し戻されることはない', async () => {
    const ppcToken = jwtWith({ sub: '1', ppc: true, exp: Math.floor(Date.now() / 1000) + 900 })
    mocks.loginWithOAuth.mockResolvedValue({ data: { accessToken: ppcToken, refreshToken: 'r' } })
    mocks.api.mockImplementation(async (url: string) => {
      if (url === '/api/v1/auth/logout') return {}
      throw new Error('profile')
    })

    mount(CallbackPage, {
      global: { plugins: [PrimeVue], mocks: { $t: (k: string) => k }, stubs: { ProgressSpinner: true, Button: true } },
    })
    await flush()

    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
    expect(store.accessToken).toBeNull()
    expect(localStorage.getItem('currentUser')).toBeNull()
    const targets = [...mocks.navigateTo.mock.calls, ...mocks.routerPush.mock.calls].map(([t]) => String(typeof t === 'string' ? t : (t as { path?: string }).path))
    expect(targets.some(t => t.includes('/login'))).toBe(true)
    expect(targets.some(t => t.includes('/parental-consent/pending'))).toBe(false)

    // この状態で pending を開いても、実ガードは /login へ誘導する（ログイン失敗として一貫）
    mocks.navigateTo.mockReset()
    await authMiddleware({ path: '/parental-consent/pending', fullPath: '/parental-consent/pending', query: {}, params: {}, meta: {} } as never, {} as never)
    expect(mocks.navigateTo.mock.calls[0]![0]).toEqual(expect.objectContaining({ path: '/login' }))
  })
})
