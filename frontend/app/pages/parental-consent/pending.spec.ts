// CMP-261107-1833 設計 §9-1: 保護者同意待ちの利用者は、承認済み保護者を検出したらトークンを更新してから遷移する。
// 更新前のトークンは同意待ち（ppc）の印を持ったままで、参加待ち招待の GET が AUTH_070 になるため。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import PendingPage from './pending.vue'

const mocks = vi.hoisted(() => ({
  getInvitations: vi.fn(),
  getParents: vi.fn(),
  navigate: vi.fn(),
  refresh: vi.fn(),
}))
mockNuxtImport('useParentalConsentApi', () => () => ({
  sendInvitation: vi.fn(),
  getInvitations: mocks.getInvitations,
  cancelInvitation: vi.fn(),
  getParents: mocks.getParents,
}))
mockNuxtImport('performTokenRefresh', () => mocks.refresh)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn() }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (v: string) => v }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const options = { global: { mocks: { $t: (k: string) => k } } }

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getInvitations.mockResolvedValue([])
  mocks.refresh.mockResolvedValue('refreshed')
})

describe('parental-consent/pending.vue の承認検出とトークン更新', () => {
  it('PPC-01: 承認済み保護者がいれば performTokenRefresh を済ませてから保護者管理へ遷移する', async () => {
    mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
    await mountSuspended(PendingPage, options)
    await flushPromises()
    expect(mocks.refresh).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).toHaveBeenCalledWith('/parental-consent/manage')
    expect(mocks.refresh.mock.invocationCallOrder[0]!).toBeLessThan(
      mocks.navigate.mock.invocationCallOrder[0]!,
    )
  })
  it('PPC-02: 更新の完了を待ってから遷移する（更新中は遷移しない）', async () => {
    mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
    let finish: (v: string) => void = () => {}
    mocks.refresh.mockImplementation(() => new Promise((r) => (finish = r)))
    await mountSuspended(PendingPage, options)
    await flushPromises()
    expect(mocks.refresh).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).not.toHaveBeenCalled()
    finish('refreshed')
    await flushPromises()
    expect(mocks.navigate).toHaveBeenCalledWith('/parental-consent/manage')
  })
  it('PPC-03: 承認済み保護者がいなければトークンを更新せず遷移もしない', async () => {
    mocks.getParents.mockResolvedValue([])
    await mountSuspended(PendingPage, options)
    await flushPromises()
    expect(mocks.refresh).not.toHaveBeenCalled()
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
})
