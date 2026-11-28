// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick, ref } from 'vue'

/**
 * CMP-261123-1124 試練（red 先行）: pages/2fa-verify.vue（ログイン時 2FA 検証）経由の同意待ち。
 *
 * - AC1: 2FA 検証を通過した同意待ちの未成年は、redirect クエリ指定でも /parental-consent/pending に着く。
 *        本人は status 付きで保存し、設定同期（BE ゲートで AUTH_070）はしない。
 * - AC7: 一般ユーザー（ACTIVE）は従来どおり redirect 先へ遷移し設定同期する。
 */

const mocks = vi.hoisted(() => ({
  validateMfaLogin: vi.fn(),
  sendLoginEmailOtp: vi.fn(),
  setTokens: vi.fn(),
  setUser: vi.fn(),
  syncAccountSettings: vi.fn(),
  serverLogout: vi.fn(),
  navigateTo: vi.fn(),
  routerPush: vi.fn(),
  api: vi.fn(),
  armProactiveRefresh: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  applyAccountLocale: vi.fn(),
}))
const routeState = vi.hoisted(() => ({
  path: '/2fa-verify',
  params: {},
  query: {} as Record<string, string>,
}))

mockNuxtImport('useAuthApi', () => () => ({
  validateMfaLogin: mocks.validateMfaLogin,
  sendLoginEmailOtp: mocks.sendLoginEmailOtp,
}))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useRouter', () => () => ({ push: mocks.routerPush, replace: mocks.routerPush }))
mockNuxtImport('navigateTo', () => mocks.navigateTo)
mockNuxtImport('useRuntimeConfig', () => () => ({ public: {} }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useHydrated', () => () => ref(true))
mockNuxtImport('readPrefilledInputGroupValue', () => () => '123456')

vi.mock('~/composables/useApi', () => ({
  useApi: () => mocks.api,
  armProactiveRefresh: mocks.armProactiveRefresh,
}))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({
    success: mocks.notifySuccess,
    error: mocks.notifyError,
    info: vi.fn(),
    warn: vi.fn(),
    showError: mocks.notifyError,
    showSuccess: mocks.notifySuccess,
    showInfo: vi.fn(),
    showWarn: vi.fn(),
  }),
}))
vi.mock('~/composables/useLocale', () => ({
  useLocale: () => ({ applyAccountLocale: mocks.applyAccountLocale }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({
    setTokens: mocks.setTokens,
    setUser: mocks.setUser,
    syncAccountSettings: mocks.syncAccountSettings,
    serverLogout: mocks.serverLogout,
    isSystemAdmin: false,
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
  }),
}))

const VerifyPage = (await import('~/pages/2fa-verify.vue')).default

async function flush(times = 10): Promise<void> {
  for (let i = 0; i < times; i++) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}

function mountVerify() {
  return mount(VerifyPage, {
    global: {
      plugins: [PrimeVue],
      mocks: { $t: (key: string) => key },
      stubs: {
        NuxtLink: { template: '<a><slot /></a>' },
        InputOtp: true,
        SelectButton: true,
        Message: true,
        InputText: true,
        Button: { template: '<button type="submit"><slot /></button>' },
      },
    },
  })
}

/** navigateTo / router.push のどちらで遷移してもよいよう、遷移先パスを集める。 */
function navigatedPaths(): string[] {
  return [...mocks.navigateTo.mock.calls, ...mocks.routerPush.mock.calls]
    .map(([target]) => (typeof target === 'string' ? target : String((target as { path?: string }).path ?? '')))
    .map(target => target.split('?')[0]!)
}

const baseProfile = {
  id: 42, email: 'child@example.com', lastName: 'Test', firstName: 'Child',
  avatarUrl: null, systemRole: null, locale: 'ja', timezone: 'Asia/Tokyo',
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(mocks).forEach(m => m.mockReset())
  routeState.query = { session: 'mfa-session', methods: 'TOTP', primary: 'TOTP' }
  mocks.validateMfaLogin.mockResolvedValue({ data: { accessToken: 'access', refreshToken: 'refresh' } })
})

describe('pages/2fa-verify.vue 保護者同意待ち（CMP-261123-1124）', () => {
  it.each([undefined, '/dashboard', '/my/profile'])(
    'AC1: 2FA 通過後に本人が同意待ちなら redirect=%s でも pending へ着く',
    async (redirect) => {
      if (redirect) routeState.query.redirect = redirect
      mocks.api.mockResolvedValue({ data: { ...baseProfile, status: 'PENDING_PARENTAL_CONSENT' } })
      const wrapper = mountVerify()
      await flush()

      await wrapper.find('form').trigger('submit')
      await flush()

      expect(mocks.validateMfaLogin).toHaveBeenCalledOnce()
      expect(mocks.setUser).toHaveBeenCalledWith(expect.objectContaining({ id: 42, status: 'PENDING_PARENTAL_CONSENT' }))
      expect(mocks.syncAccountSettings).not.toHaveBeenCalled()
      expect(navigatedPaths()).toContain('/parental-consent/pending')
      expect(navigatedPaths()).not.toContain('/dashboard')
      expect(navigatedPaths()).not.toContain('/my/profile')
      expect(navigatedPaths()).not.toContain('/')
    },
  )

  it('AC7: 本人が ACTIVE なら従来どおり redirect 先へ遷移し設定同期する（回帰）', async () => {
    routeState.query.redirect = '/my/profile'
    mocks.api.mockResolvedValue({ data: { ...baseProfile, status: 'ACTIVE' } })
    const wrapper = mountVerify()
    await flush()

    await wrapper.find('form').trigger('submit')
    await flush()

    expect(mocks.syncAccountSettings).toHaveBeenCalledOnce()
    expect(navigatedPaths()).toContain('/my/profile')
    expect(navigatedPaths()).not.toContain('/parental-consent/pending')
  })
})

describe('pages/2fa-verify.vue プロフィール取得失敗と同意待ち（CMP-261123-1124 AC1 失敗経路）', () => {
  function jwtWith(payload: Record<string, unknown>): string {
    const b64url = (o: unknown) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')
    return `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url(payload)}.signature`
  }

  it('ppc=true でプロフィール取得に失敗したらサーバーログアウトし、pending にも他へも遷移しない', async () => {
    const ppcToken = jwtWith({ sub: '42', ppc: true, exp: Math.floor(Date.now() / 1000) + 900 })
    mocks.validateMfaLogin.mockResolvedValue({ data: { accessToken: ppcToken, refreshToken: 'refresh' } })
    mocks.api.mockRejectedValue(new Error('profile'))
    const wrapper = mountVerify()
    await flush()

    await wrapper.find('form').trigger('submit')
    await flush()

    expect(mocks.serverLogout).toHaveBeenCalledOnce()
    expect(mocks.syncAccountSettings).not.toHaveBeenCalled()
    expect(navigatedPaths()).toEqual([])
  })
})
