import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import featureFlagsPlugin from '~/plugins/feature-flags.client'
import navSettingsPlugin from '~/plugins/nav-settings.client'
import scopeDashboardPlugin from '~/plugins/scope-dashboard.client'

/**
 * CMP-261123-1124 試練（red 先行）AC4: pending 画面の表示中に、BE ゲート（ParentalConsentGateFilter）で
 * 403 AUTH_070 になる API を背景処理から呼ばない。
 *
 * - pending.vue は default レイアウト（受信箱ポーリング・通知 WebSocket・参加待ち招待ダイアログ・AppShell を持つ）
 *   を使わず、背景処理を持たない簡素なレイアウトで描画する。
 * - 起動時プラグイン（公開フィーチャーフラグ・ナビ設定・スコープダッシュボード）は、
 *   同意待ちではサーバー取得をしない。一般ユーザー（ACTIVE）は従来どおり取得する（AC7）。
 */

const read = (path: string) => readFileSync(resolve(process.cwd(), path), 'utf8')

describe('pending.vue のレイアウト（AC4）', () => {
  const page = read('app/pages/parental-consent/pending.vue')
  const meta = page.match(/definePageMeta\(\{([\s\S]*?)\}\)/)?.[1] ?? ''
  const layout = meta.match(/layout:\s*['"]([\w-]+)['"]/)?.[1]

  it('AC4-1: default 以外のレイアウトを明示する（未指定は default になり背景処理が走る）', () => {
    expect(layout, 'definePageMeta に layout が無い').toBeDefined()
    expect(layout).not.toBe('default')
    expect(existsSync(resolve(process.cwd(), `app/layouts/${layout}.vue`))).toBe(true)
  })

  it('AC4-2: そのレイアウトは BE ゲートで塞がれる背景処理を持たない', () => {
    const layoutSource = layout ? read(`app/layouts/${layout}.vue`) : ''
    for (const forbidden of ['useInboxStore', 'useUserNotificationSocket', 'PendingInviteDialog', 'AppShell', 'usePendingInvite']) {
      expect(layoutSource, `レイアウトが ${forbidden} を含む`).not.toContain(forbidden)
    }
  })

  it('AC4-3: 認証必須のまま（auth middleware は外さない）', () => {
    expect(meta).toMatch(/middleware:\s*['"[]?.*auth/)
  })
})

let authStub: { isAuthenticated: boolean; isPendingParentalConsent: boolean }
mockNuxtImport('useAuthStore', () => () => authStub)

const loadPublicFlags = vi.fn(async () => {})
mockNuxtImport('useFeatureFlagStore', () => () => ({ loadPublicFlags }))

const navLoadFromServer = vi.fn(async () => {})
mockNuxtImport('useNavSettingsStore', () => () => ({ loadFromStorage: vi.fn(), loadFromServer: navLoadFromServer }))

const loadTabs = vi.fn(async () => {})
mockNuxtImport('useScopeDashboardStore', () => () => ({ loadFromStorage: vi.fn(), loadTabs, teamTabPage: 0 }))

type PluginFn = (nuxtApp: unknown) => unknown
function run(plugin: unknown) {
  return (plugin as PluginFn)(useNuxtApp())
}

describe('起動時プラグインと同意待ち（AC4 / AC7）', () => {
  beforeEach(() => {
    loadPublicFlags.mockClear()
    navLoadFromServer.mockClear()
    loadTabs.mockClear()
  })

  it('AC4-4: 同意待ちでは公開フィーチャーフラグ・ナビ設定・スコープタグをサーバーから取らない', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: true }

    await run(featureFlagsPlugin)
    await run(navSettingsPlugin)
    await run(scopeDashboardPlugin)

    expect(loadPublicFlags).not.toHaveBeenCalled()
    expect(navLoadFromServer).not.toHaveBeenCalled()
    expect(loadTabs).not.toHaveBeenCalled()
  })

  it('AC7: 一般ユーザー（ACTIVE）は従来どおり取得する', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: false }

    await run(featureFlagsPlugin)
    await run(navSettingsPlugin)
    await run(scopeDashboardPlugin)

    expect(loadPublicFlags).toHaveBeenCalledOnce()
    expect(navLoadFromServer).toHaveBeenCalledOnce()
    expect(loadTabs).toHaveBeenCalledOnce()
  })
})
