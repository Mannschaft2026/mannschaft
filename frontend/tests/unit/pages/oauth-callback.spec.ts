// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick } from 'vue'

/**
 * CMP-261012-0911 試練: pages/auth/oauth/callback.vue の応答三択（トークン / MFA 要求 / 連携競合）の分岐 UT。
 *
 * 背景:
 *   OAuth ログインが 2FA を迂回していた欠陥の是正で、BE は 2FA 有効ユーザーに MfaRequiredResponse
 *   （mfaRequired / mfaSessionToken / primaryMethod / availableMethods）を 200 で返す。
 *   旧 callback.vue は res.data.accessToken 決め打ちで setTokens → /dashboard していたため、
 *   MFA 要求や連携競合（OAuthConflictResponse・200）を受けると未定義トークンで「ログイン済み」扱いになっていた。
 *
 * 検証観点:
 *   AC-8:  MFA 要求 → setTokens を呼ばず /2fa-verify?session&primary&methods へ（パスワードログインと同じ形）
 *   AC-8:  トークン応答 → setTokens して /dashboard へ（回帰）
 *   AC-13: 連携競合応答 → setTokens を呼ばず /login?oauthConflict=true へ
 *   AC-17: loginWithOAuth を google と code で呼ぶ（送り方の契約は useAuthApi.spec.ts で固定）
 *
 * 遷移は router.push / navigateTo のどちらで行ってもよいように、両方の呼び出しから遷移先を取り出して検証する。
 */

const mocks = vi.hoisted(() => ({
  loginWithOAuth: vi.fn(),
  setTokens: vi.fn(),
  setUser: vi.fn(),
  syncAccountSettings: vi.fn(),
  routerPush: vi.fn(),
  navigateTo: vi.fn(),
  api: vi.fn(),
  armProactiveRefresh: vi.fn(),
  notifySuccess: vi.fn(),
  notifyError: vi.fn(),
  applyAccountLocale: vi.fn(),
}))
const routeState = vi.hoisted(() => ({ path: '/auth/oauth/callback', params: {}, query: {} as Record<string, string> }))

mockNuxtImport('useAuthApi', () => () => ({ loginWithOAuth: mocks.loginWithOAuth }))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useRouter', () => () => ({ push: mocks.routerPush, replace: mocks.routerPush }))
mockNuxtImport('navigateTo', () => mocks.navigateTo)
mockNuxtImport('useRuntimeConfig', () => () => ({ public: {} }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

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
    isSystemAdmin: false,
    isAuthenticated: false,
    loadFromStorage: vi.fn(),
  }),
}))

const CallbackPage = (await import('~/pages/auth/oauth/callback.vue')).default

async function flush(times = 10): Promise<void> {
  for (let i = 0; i < times; i++) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}

function mountCallback() {
  return mount(CallbackPage, {
    global: {
      plugins: [PrimeVue],
      mocks: { $t: (key: string) => key },
      stubs: { ProgressSpinner: true, Button: true },
    },
  })
}

/** router.push / navigateTo のどちらかで渡された遷移先（文字列）を集める。 */
function navigatedTargets(): string[] {
  return [...mocks.routerPush.mock.calls, ...mocks.navigateTo.mock.calls]
    .map(call => call[0])
    .map(target => (typeof target === 'string' ? target : JSON.stringify(target)))
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(mocks).forEach(m => m.mockReset())
  routeState.query = { code: 'google-auth-code' }
  mocks.api.mockResolvedValue({
    data: {
      id: 1, email: 'u@example.com', lastName: '山田', firstName: '太郎',
      avatarUrl: null, systemRole: null, locale: 'ja', timezone: 'Asia/Tokyo',
    },
  })
})

describe('pages/auth/oauth/callback.vue 応答三択の分岐（CMP-261012-0911）', () => {
  it('AC-8: MFA 要求なら setTokens を呼ばず /2fa-verify へ session・primary・methods を引き継ぐ', async () => {
    mocks.loginWithOAuth.mockResolvedValue({
      data: {
        mfaRequired: true,
        mfaSessionToken: 'mfa-session-xyz',
        primaryMethod: 'EMAIL',
        availableMethods: ['EMAIL', 'BACKUP_CODE'],
      },
    })

    mountCallback()
    await flush()

    expect(mocks.setTokens).not.toHaveBeenCalled()
    const target = navigatedTargets().find(t => t.startsWith('/2fa-verify'))
    expect(target, `遷移先: ${JSON.stringify(navigatedTargets())}`).toBeDefined()
    const url = new URL(target!, 'http://localhost')
    expect(url.pathname).toBe('/2fa-verify')
    expect(url.searchParams.get('session')).toBe('mfa-session-xyz')
    expect(url.searchParams.get('primary')).toBe('EMAIL')
    expect(url.searchParams.get('methods')).toBe('EMAIL,BACKUP_CODE')
    expect(navigatedTargets()).not.toContain('/dashboard')
  })

  it('AC-8: MFA 要求で方式情報が欠落していても session だけで /2fa-verify へ（2fa-verify 側が TOTP にフォールバック）', async () => {
    mocks.loginWithOAuth.mockResolvedValue({ data: { mfaRequired: true, mfaSessionToken: 'mfa-only' } })

    mountCallback()
    await flush()

    expect(mocks.setTokens).not.toHaveBeenCalled()
    const target = navigatedTargets().find(t => t.startsWith('/2fa-verify'))
    expect(target).toBeDefined()
    expect(new URL(target!, 'http://localhost').searchParams.get('session')).toBe('mfa-only')
  })

  it('AC-8: トークン応答なら従来どおり setTokens して /dashboard へ', async () => {
    mocks.loginWithOAuth.mockResolvedValue({
      data: { accessToken: 'at-1', refreshToken: 'rt-1', tokenType: 'Bearer', expiresIn: 900 },
    })

    mountCallback()
    await flush()

    expect(mocks.setTokens).toHaveBeenCalledWith('at-1', 'rt-1')
    expect(navigatedTargets()).toContain('/dashboard')
  })

  it('AC-13: 連携競合応答（200・OAuthConflictResponse）なら setTokens を呼ばず /login?oauthConflict=true へ', async () => {
    mocks.loginWithOAuth.mockResolvedValue({
      data: {
        message: 'このメールアドレスに既存のアカウントが存在します。連携確認メールを送信しました。',
        email: 'u@example.com',
        provider: 'google',
      },
    })

    mountCallback()
    await flush()

    expect(mocks.setTokens).not.toHaveBeenCalled()
    expect(navigatedTargets()).toContain('/login?oauthConflict=true')
    expect(navigatedTargets()).not.toContain('/dashboard')
  })

  it('AC-17: loginWithOAuth を provider=google・認可コードで呼ぶ', async () => {
    mocks.loginWithOAuth.mockResolvedValue({ data: { mfaRequired: true, mfaSessionToken: 's' } })

    mountCallback()
    await flush()

    expect(mocks.loginWithOAuth).toHaveBeenCalledWith('google', { code: 'google-auth-code' })
  })
})
