import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ActiveIncidentBanner from '~/components/ActiveIncidentBanner.vue'

/**
 * CMP-261123-1124 AC4: app.vue 直下の ActiveIncidentBanner は layout に依らず描画される。
 * 同意待ちでは BE ゲート（AUTH_070）で塞がれる /api/v1/active-incidents を初回・ポーリングとも呼ばない。
 */
const api = vi.fn()
mockNuxtImport('useApi', () => () => api)
let authStub: { isAuthenticated: boolean; isPendingParentalConsent: boolean }
mockNuxtImport('useAuthStore', () => () => authStub)

beforeEach(() => {
  vi.useFakeTimers()
  api.mockReset()
  api.mockResolvedValue({ incidents: [] })
})
afterEach(() => {
  vi.useRealTimers()
})

describe('ActiveIncidentBanner と同意待ち（CMP-261123-1124 AC4）', () => {
  it('同意待ちでは初回も 60 秒ポーリングも API を呼ばない', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: true }
    await mountSuspended(ActiveIncidentBanner)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(120000)

    expect(api).not.toHaveBeenCalled()
  })

  it('AC7: 一般ユーザーは従来どおり取得し、60 秒ごとに再取得する', async () => {
    authStub = { isAuthenticated: true, isPendingParentalConsent: false }
    await mountSuspended(ActiveIncidentBanner)
    await flushPromises()
    expect(api).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(60000)
    expect(api).toHaveBeenCalledTimes(2)
  })
})
