// @vitest-environment node
// 戦役Z-B 試練 S16: サポート閲覧ストア。閲覧開始で有効になり、期限で自動的に無効になり、
// 終了時は BE の sessions/end を呼んで本人データのキャッシュ（useAsyncData・useState）を消す。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useSupportAccessStore } from '~/stores/useSupportAccessStore'

const api = vi.fn()
const clearNuxtData = vi.fn()
const clearNuxtState = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))

const NOW = new Date('2026-11-26T12:00:00Z')
const SESSION = {
  grantId: '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b',
  targetUserId: 42,
  targetDisplayName: '本人',
  expiresAt: new Date(NOW.getTime() + 5 * 60 * 60 * 1000).toISOString(),
}

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  api.mockReset()
  api.mockResolvedValue({})
  clearNuxtData.mockReset()
  clearNuxtState.mockReset()
  vi.stubGlobal('useApi', () => api)
  vi.stubGlobal('clearNuxtData', clearNuxtData)
  vi.stubGlobal('clearNuxtState', clearNuxtState)
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('戦役Z-B S16 useSupportAccessStore', () => {
  it('S16 初期状態は閲覧中でない', () => {
    const store = useSupportAccessStore()
    expect(store.isActive).toBe(false)
    expect(store.grantId).toBeNull()
  })

  it('S16 閲覧開始の応答で閲覧中になり、対象者・期限・残り秒数を持つ', () => {
    const store = useSupportAccessStore()
    store.startSession(SESSION)
    expect(store.isActive).toBe(true)
    expect(store.grantId).toBe(SESSION.grantId)
    expect(store.targetUserId).toBe(42)
    expect(store.targetDisplayName).toBe('本人')
    expect(store.remainingSeconds).toBe(5 * 60 * 60)
  })

  it('S16 期限を過ぎたら閲覧中でなくなる（24時間を超えて続かない）', () => {
    const store = useSupportAccessStore()
    store.startSession(SESSION)
    vi.setSystemTime(new Date(NOW.getTime() + 5 * 60 * 60 * 1000 + 1000))
    expect(store.isActive).toBe(false)
    expect(store.remainingSeconds).toBe(0)
  })

  it('S16 終了で BE の sessions/end を POST し、状態と本人データのキャッシュを消す', async () => {
    const store = useSupportAccessStore()
    store.startSession(SESSION)
    await store.endSession()
    expect(api).toHaveBeenCalledWith(
      `/api/v1/system-admin/support-access/grants/${SESSION.grantId}/sessions/end`,
      expect.objectContaining({ method: 'POST' }),
    )
    expect(store.isActive).toBe(false)
    expect(store.grantId).toBeNull()
    expect(clearNuxtData).toHaveBeenCalled()
    expect(clearNuxtState).toHaveBeenCalled()
  })

  it('S16 BE の終了が失敗しても手元の閲覧状態とキャッシュは必ず消す（本人データを運営画面に残さない）', async () => {
    api.mockRejectedValueOnce(new Error('network'))
    const store = useSupportAccessStore()
    store.startSession(SESSION)
    // BE 失敗を呼び出し側へ伝えるか（reject）は実装に委ね、手元の後始末だけを検査する
    const outcome = await store.endSession().then(() => 'resolved', (e: unknown) => e)
    expect(outcome === 'resolved' || outcome instanceof Error).toBe(true)
    expect(store.isActive).toBe(false)
    expect(clearNuxtData).toHaveBeenCalled()
  })
})
