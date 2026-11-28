import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { RouteLocationNormalized } from 'vue-router'
import authMiddleware from '~/middleware/auth'
import authClientMiddleware from '~/middleware/auth.client'
import featureGateMiddleware from '~/middleware/feature-gate.global'
import { GATE_FALLBACK_PATH } from '~/constants/featureGates'

/**
 * CMP-261123-1124 試練（red 先行）: 保護者同意待ちの利用者を pending 画面へ閉じ込めるルートガード。
 *
 * - AC2: 同意待ちのまま認証必須ページ（/dashboard 等）へ直接行くと /parental-consent/pending へ戻す
 *        （middleware/auth.ts と auth.client.ts の両方）
 * - AC2: global の feature gate は同意待ちに対して公開フラグ API（BE ゲートで AUTH_070）を叩かず、
 *        未公開扱いの戻し・トーストもしない（pending 判定を先にする）
 * - AC7: 一般ユーザー（ACTIVE）と未認証の挙動は変えない
 *
 * ストアの契約: useAuthStore().isPendingParentalConsent（useAuthStore.parentalConsent.spec.ts で固定）。
 */

const PENDING = '/parental-consent/pending'

const mockNavigateTo = vi.fn()
mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigateTo(...args))

let authStub: { isAuthenticated: boolean; isPendingParentalConsent: boolean }
mockNuxtImport('useAuthStore', () => () => authStub)

let flagStore: { publicLoaded: boolean; isEnabled: ReturnType<typeof vi.fn>; loadPublicFlags: ReturnType<typeof vi.fn> }
mockNuxtImport('useFeatureFlagStore', () => () => flagStore)

function route(fullPath: string): RouteLocationNormalized {
  const path = fullPath.split('?')[0]!
  return { path, fullPath, query: {}, params: {}, meta: {} } as unknown as RouteLocationNormalized
}

/** navigateTo の第1引数（文字列 / { path }）から遷移先パスを取り出す。 */
function navigatedPaths(): string[] {
  return mockNavigateTo.mock.calls.map(([target]) => {
    if (typeof target === 'string') return target.split('?')[0]!
    return String((target as { path?: string }).path ?? '')
  })
}

const from = route('/')

beforeEach(() => {
  mockNavigateTo.mockReset()
  authStub = { isAuthenticated: true, isPendingParentalConsent: false }
  flagStore = {
    publicLoaded: false,
    isEnabled: vi.fn(() => false),
    loadPublicFlags: vi.fn(async () => {}),
  }
})

describe.each([
  ['middleware/auth.ts', authMiddleware],
  ['middleware/auth.client.ts', authClientMiddleware],
])('%s 保護者同意待ちのガード（CMP-261123-1124）', (_name, middleware) => {
  it.each(['/dashboard', '/my/profile', '/teams/abc/schedule?tab=1', '/parental-consent/manage'])(
    'AC2: 同意待ちで %s へ直接行くと pending へ戻す',
    async (path) => {
      authStub = { isAuthenticated: true, isPendingParentalConsent: true }

      await middleware(route(path), from)

      expect(navigatedPaths()).toEqual([PENDING])
    },
  )

  it('AC2: 同意待ちで pending 自体を開いたときは戻さない（無限遷移しない）', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: true }

    const result = await middleware(route(PENDING), from)

    expect(result).toBeUndefined()
    expect(mockNavigateTo).not.toHaveBeenCalled()
  })

  it('AC7: 一般ユーザー（ACTIVE）は従来どおり素通り', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: false }

    const result = await middleware(route('/dashboard'), from)

    expect(result).toBeUndefined()
    expect(mockNavigateTo).not.toHaveBeenCalled()
  })

  it('AC7: 一般ユーザーは pending を開いても pending へ閉じ込めない（従来の到達性を変えない）', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: false }

    await middleware(route(PENDING), from)

    expect(navigatedPaths()).not.toContain(PENDING)
  })

  it('AC7: 未認証は従来どおり redirect 付きで /login へ誘導する', async () => {
    authStub = { isAuthenticated: false, isPendingParentalConsent: false }

    await middleware(route('/dashboard?x=1'), from)

    expect(mockNavigateTo).toHaveBeenCalledTimes(1)
    const [target] = mockNavigateTo.mock.calls[0]!
    expect(target).toEqual({ path: '/login', query: { redirect: '/dashboard?x=1' } })
  })
})

describe('feature-gate.global と同意待ち（CMP-261123-1124 AC2/AC4）', () => {
  it('AC2/AC4: 同意待ちではガード対象パスでも公開フラグ API を叩かず、未公開扱いで戻さない', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: true }

    // 例外（503 fatal）を投げないことも含めて検証する
    await expect(Promise.resolve(featureGateMiddleware(route('/shift/12'), from))).resolves.not.toThrow()

    expect(flagStore.loadPublicFlags).not.toHaveBeenCalled()
    expect(flagStore.isEnabled).not.toHaveBeenCalled()
    expect(mockNavigateTo).not.toHaveBeenCalledWith(GATE_FALLBACK_PATH, expect.anything())
  })

  it('AC7: 一般ユーザーは従来どおり公開フラグを取得して判定する（回帰）', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: false }
    flagStore.isEnabled = vi.fn(() => true)

    await featureGateMiddleware(route('/shift/12'), from)

    expect(flagStore.loadPublicFlags).toHaveBeenCalled()
  })
})
