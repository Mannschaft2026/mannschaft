// CMP-261107-1833 設計 §9-1: 保護者同意待ちの利用者は、承認済み保護者を検出したらトークンを更新してから遷移する。
// 更新前のトークンは同意待ち（ppc）の印を持ったままで、参加待ち招待の GET が AUTH_070 になるため。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import PendingPage from './pending.vue'
import { useAuthStore } from '~/stores/useAuthStore'

const mocks = vi.hoisted(() => ({
  getInvitations: vi.fn(),
  getParents: vi.fn(),
  navigate: vi.fn(),
  refresh: vi.fn(),
  logout: vi.fn(),
  error: vi.fn(),
  sendInvitation: vi.fn(),
  api: vi.fn(),
}))
mockNuxtImport('useParentalConsentApi', () => () => ({
  sendInvitation: mocks.sendInvitation,
  getInvitations: mocks.getInvitations,
  cancelInvitation: vi.fn(),
  getParents: mocks.getParents,
}))
mockNuxtImport('performTokenRefresh', () => mocks.refresh)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('handleAuthFailureLogout', () => mocks.logout)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: mocks.error }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (v: string) => v }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
// CMP-261123-1124 AC6: 承認後の本人（/users/me）取り直しを観測する
mockNuxtImport('useApi', () => () => mocks.api)

const options = { global: { mocks: { $t: (k: string) => k } } }

beforeEach(() => {
  vi.clearAllMocks()
  // 既定は同意待ちの本人（pending 画面は同意待ち以外には開かない。CMP-261123-1124 D-2）
  useAuthStore().$patch({
    user: { id: 7, email: 'child@example.com', fullName: '山田 太郎', profileImageUrl: null, status: 'PENDING_PARENTAL_CONSENT' },
  } as never)
  mocks.getInvitations.mockResolvedValue([])
  mocks.refresh.mockResolvedValue('refreshed')
  // CMP-261123-1124 AC6: 承認後の /users/me 取り直しの既定応答（ACTIVE に戻った本人）
  mocks.api.mockResolvedValue({
    data: {
      id: 7, email: 'child@example.com', lastName: '山田', firstName: '太郎',
      avatarUrl: null, systemRole: null, locale: 'ja', timezone: 'Asia/Tokyo', status: 'ACTIVE',
    },
  })
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
  it.each(['transient', 'session_changed'])('PPC-04: 更新結果が %s なら遷移しない', async (result) => {
    mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
    mocks.refresh.mockResolvedValue(result)
    await mountSuspended(PendingPage, options)
    await flushPromises()
    expect(mocks.navigate).not.toHaveBeenCalled()
    expect(mocks.logout).not.toHaveBeenCalled()
  })
  it('PPC-05: 一時的な更新失敗は利用者に伝える（握りつぶさない）', async () => {
    mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
    mocks.refresh.mockResolvedValue('transient')
    await mountSuspended(PendingPage, options)
    await flushPromises()
    expect(mocks.error).toHaveBeenCalledTimes(1)
  })
  it('PPC-06: 更新が auth_failed なら既存の共通処理でログアウトし、遷移しない', async () => {
    mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
    mocks.refresh.mockResolvedValue('auth_failed')
    await mountSuspended(PendingPage, options)
    await flushPromises()
    expect(mocks.logout).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
})

/**
 * CMP-261123-1124 試練（red 先行）
 * - AC6: 承認検知 → トークン refresh 成功 → /users/me を取り直して同意待ちの印を外す → /parental-consent/manage。
 *        refresh 失敗時は印を外さない（/users/me も取り直さない）。
 * - AC5: 招待送信後に一覧へ反映する（送信値は入力したメールアドレス）。
 */
const pendingUser = {
  id: 7, email: 'child@example.com', fullName: '山田 太郎', profileImageUrl: null, status: 'PENDING_PARENTAL_CONSENT',
}
const activeProfile = {
  id: 7, email: 'child@example.com', lastName: '山田', firstName: '太郎',
  avatarUrl: null, systemRole: null, locale: 'ja', timezone: 'Asia/Tokyo', status: 'ACTIVE',
}

function pendingFlag(): unknown {
  return (useAuthStore() as unknown as { isPendingParentalConsent?: unknown }).isPendingParentalConsent
}

describe('parental-consent/pending.vue 承認後の印の解除（CMP-261123-1124 AC6）', () => {
  beforeEach(() => {
    useAuthStore().$patch({ user: pendingUser } as never)
    mocks.api.mockImplementation(async (url: string) => {
      if (url === '/api/v1/users/me') return { data: activeProfile }
      throw new Error(`unexpected api call: ${url}`)
    })
  })

  it('AC6-1: refresh 成功後に /users/me を取り直して印を外し、その後に manage へ遷移する', async () => {
    expect(pendingFlag()).toBe(true)
    mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
    await mountSuspended(PendingPage, options)
    await flushPromises()

    expect(mocks.api).toHaveBeenCalledWith('/api/v1/users/me')
    expect(pendingFlag()).toBe(false)
    expect(mocks.navigate).toHaveBeenCalledWith('/parental-consent/manage')
    const meCall = mocks.api.mock.calls.findIndex(([url]) => url === '/api/v1/users/me')
    expect(mocks.refresh.mock.invocationCallOrder[0]!).toBeLessThan(mocks.api.mock.invocationCallOrder[meCall]!)
    expect(mocks.api.mock.invocationCallOrder[meCall]!).toBeLessThan(mocks.navigate.mock.invocationCallOrder[0]!)
  })

  it.each(['transient', 'auth_failed', 'session_changed'])(
    'AC6-2: refresh が %s のときは印を外さず /users/me も取り直さない',
    async (result) => {
      mocks.getParents.mockResolvedValue([{ parentUserId: 9 }])
      mocks.refresh.mockResolvedValue(result)
      await mountSuspended(PendingPage, options)
      await flushPromises()

      expect(mocks.api).not.toHaveBeenCalledWith('/api/v1/users/me')
      if (result !== 'auth_failed') expect(pendingFlag()).toBe(true)
      expect(mocks.navigate).not.toHaveBeenCalledWith('/parental-consent/manage')
    },
  )
})

describe('parental-consent/pending.vue 招待送信（CMP-261123-1124/1125 AC5）', () => {
  it('AC5-UI: 送信すると入力値で招待し、取り直した一覧に反映する', async () => {
    mocks.getParents.mockResolvedValue([])
    mocks.sendInvitation.mockResolvedValue(undefined)
    mocks.getInvitations
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { linkId: 'l1', parentEmail: 'parent@example.com', status: 'PENDING', expiresAt: '2026-12-01T00:00:00' },
      ])
    const wrapper = await mountSuspended(PendingPage, options)
    await flushPromises()

    await wrapper.find('input[type="email"]').setValue('parent@example.com')
    await wrapper.find('button').trigger('click')
    await flushPromises()

    expect(mocks.sendInvitation).toHaveBeenCalledWith('parent@example.com')
    expect(wrapper.text()).toContain('parent@example.com')
  })
})

/**
 * CMP-261123-1124 実機 D-2: 同意待ちでない利用者（一般成人 ACTIVE など）が /parental-consent/pending を開いても
 * 「18歳未満のため…」を見せず、通常の遷移先へ戻す。招待 API や一覧取得も呼ばない。
 */
describe('parental-consent/pending.vue 同意待ち以外の利用者（CMP-261123-1124 D-2）', () => {
  const activeUser = { id: 8, email: 'adult@example.com', fullName: '大人 太郎', profileImageUrl: null, status: 'ACTIVE' }

  it('ACTIVE の利用者は /dashboard へ戻され、API を呼ばない', async () => {
    useAuthStore().$patch({ user: activeUser } as never)
    const wrapper = await mountSuspended(PendingPage, options)
    await flushPromises()

    expect(mocks.navigate).toHaveBeenCalledWith('/dashboard', { replace: true })
    expect(mocks.getInvitations).not.toHaveBeenCalled()
    expect(mocks.getParents).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('parental_consent.pending_description')
  })

  it('SYSTEM_ADMIN は /system-admin へ戻される', async () => {
    useAuthStore().$patch({ user: { ...activeUser, systemRole: 'SYSTEM_ADMIN' } } as never)
    await mountSuspended(PendingPage, options)
    await flushPromises()

    expect(mocks.navigate).toHaveBeenCalledWith('/system-admin', { replace: true })
  })

  it('同意待ちの利用者は戻されない（回帰）', async () => {
    mocks.getParents.mockResolvedValue([])
    await mountSuspended(PendingPage, options)
    await flushPromises()

    expect(mocks.navigate).not.toHaveBeenCalledWith('/dashboard')
    expect(mocks.getInvitations).toHaveBeenCalled()
  })
})
