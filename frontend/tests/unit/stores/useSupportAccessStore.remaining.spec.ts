// @vitest-environment node
// 戦役Z-B 検分差し戻し対応: 残り時間は端末の時計ではなく BE の残り秒数（remainingSeconds）を基準に数える。
// 閲覧開始の応答（生成型は全項目 optional）は、必須の値が揃っているときだけ閲覧に使う。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { toSupportAccessSession, useSupportAccessStore } from '~/stores/useSupportAccessStore'

const api = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))

const NOW = new Date('2026-11-26T12:00:00Z')
const GRANT_ID = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b'

beforeEach(() => {
  setActivePinia(createPinia())
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
  vi.stubGlobal('useApi', () => api)
  vi.stubGlobal('clearNuxtData', vi.fn())
  vi.stubGlobal('clearNuxtState', vi.fn())
})
afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('toSupportAccessSession（生成型の optional を扱う）', () => {
  it('grant・本人・期限が揃っていれば閲覧用の値にする（表示名が無ければ空文字）', () => {
    expect(toSupportAccessSession({ grantId: GRANT_ID, targetUserId: 42, expiresAt: '2026-11-27T12:00:00Z', remainingSeconds: 60 }))
      .toEqual({ grantId: GRANT_ID, targetUserId: 42, targetDisplayName: '', expiresAt: '2026-11-27T12:00:00Z', remainingSeconds: 60 })
  })

  it.each([
    [{ targetUserId: 42, expiresAt: '2026-11-27T12:00:00Z' }],
    [{ grantId: GRANT_ID, expiresAt: '2026-11-27T12:00:00Z' }],
    [{ grantId: GRANT_ID, targetUserId: 42 }],
    [undefined],
  ])('必須の値が欠けていれば null（閲覧を始めない）: %o', (res) => {
    expect(toSupportAccessSession(res)).toBeNull()
  })
})

describe('残り時間は BE の残り秒数が基準', () => {
  it('端末の時計が進んでいても（expiresAt が端末の時刻で過去でも）、BE の残り秒数の間は閲覧中', () => {
    const store = useSupportAccessStore()
    // 端末の時計が実際より 2 時間進んでいる想定: expiresAt は端末の「今」より前に見える
    store.startSession({
      grantId: GRANT_ID,
      targetUserId: 42,
      targetDisplayName: '本人',
      expiresAt: new Date(NOW.getTime() - 60 * 60 * 1000).toISOString(),
      remainingSeconds: 3600,
    })
    expect(store.isActive).toBe(true)
    expect(store.remainingSeconds).toBe(3600)

    vi.advanceTimersByTime(3599 * 1000)
    expect(store.isActive).toBe(true)
    vi.advanceTimersByTime(2000)
    expect(store.isActive).toBe(false)
  })

  it('残り秒数が無い応答（古い BE）は expiresAt で数える', () => {
    const store = useSupportAccessStore()
    store.startSession({
      grantId: GRANT_ID,
      targetUserId: 42,
      targetDisplayName: '本人',
      expiresAt: new Date(NOW.getTime() + 120 * 1000).toISOString(),
    })
    expect(store.remainingSeconds).toBe(120)
  })
})
