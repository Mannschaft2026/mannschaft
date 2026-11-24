// CMP-261107-1833: 未ログインの公開エンドポイントが返す 403 の業務エラー（例: 登録の AUTH_083）で、
// 共通処理がログインへ遷移させて入力内容・招待の戻り先を失わせないことを、実 interceptor で確かめる。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
const external = vi.hoisted(() => ({ fetch: vi.fn<typeof fetch>(), navigate: vi.fn() }))
vi.mock('ofetch', async importOriginal => {
  const original = await importOriginal<typeof import('ofetch')>()
  return { ...original, ofetch: original.ofetch.create({}, { fetch: external.fetch }) }
})
vi.mock('~/composables/useApiBaseUrl', () => ({ resolveApiBaseUrl: () => 'http://synthetic.invalid' }))
mockNuxtImport('navigateTo', () => external.navigate)
mockNuxtImport('useProxyDeskStore', () => () => ({ isPinned: false }))
mockNuxtImport('useGuardianshipSwitchStore', () => () => ({ isActingAs: false }))
mockNuxtImport('useAdminImpersonationStore', () => () => ({ isImpersonating: false }))
const { useApi } = await import('~/composables/useApi')
const json = (data: unknown, status: number) =>
  new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })

beforeEach(() => {
  setActivePinia(createPinia())
  external.fetch.mockReset()
  external.navigate.mockReset()
})
afterEach(() => vi.restoreAllMocks())

describe('未ログインでの 403', () => {
  it('業務エラーコード付き（AUTH_083）はログインへ遷移させず、呼び出し元へ例外を返す', async () => {
    external.fetch.mockResolvedValue(json({ error: { code: 'AUTH_083' } }, 403))
    await expect(useApi()('/api/v1/auth/register', { method: 'POST', body: {} })).rejects.toMatchObject({
      statusCode: 403,
    })
    expect(external.navigate).not.toHaveBeenCalled()
  })
  it('業務エラーコードの無い 403 は従来どおりログインへ誘導する', async () => {
    external.fetch.mockResolvedValue(json({}, 403))
    await expect(useApi()('/api/v1/me/ranch')).rejects.toThrow()
    expect(external.navigate).toHaveBeenCalledWith('/login')
  })
})
