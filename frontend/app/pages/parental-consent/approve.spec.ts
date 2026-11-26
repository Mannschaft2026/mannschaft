import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import ApprovePage from './approve.vue'
import { useAuthStore } from '~/stores/useAuthStore'

/**
 * CMP-261123-1124 保護者の承認画面: ログイン済みでも表示しただけでは承認しない。
 * 「同意する」「同意しない（確認つき）」を保護者が明示的に選んだときだけ、対応する API を呼ぶ。
 */
const mocks = vi.hoisted(() => ({
  approve: vi.fn(),
  reject: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
}))
const routeState = vi.hoisted(() => ({ path: '/parental-consent/approve', params: {}, query: { token: 'tok-123' } as Record<string, string> }))

mockNuxtImport('useParentalConsentApi', () => () => ({ approve: mocks.approve, reject: mocks.reject }))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useNotification', () => () => ({ success: mocks.success, error: mocks.error }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const options = { global: { mocks: { $t: (k: string) => k } } }

function buttonByText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find((b: DOMWrapper<Element>) => b.text() === text)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.approve.mockResolvedValue(undefined)
  mocks.reject.mockResolvedValue(undefined)
  useAuthStore().$patch({
    user: { id: 9, email: 'parent@example.com', fullName: '保護者 花子', profileImageUrl: null, status: 'ACTIVE' },
  } as never)
})

describe('parental-consent/approve.vue（ログイン済みの保護者）', () => {
  it('表示しただけでは承認も拒否もしない（自動承認しない）', async () => {
    const wrapper = await mountSuspended(ApprovePage, options)
    await flushPromises()

    expect(mocks.approve).not.toHaveBeenCalled()
    expect(mocks.reject).not.toHaveBeenCalled()
    expect(buttonByText(wrapper, 'parental_consent.approve_button')).toBeDefined()
    expect(buttonByText(wrapper, 'parental_consent.reject_button')).toBeDefined()
  })

  it('「同意する」を押すと approve をトークン付きで呼び、承認済み表示になる', async () => {
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, 'parental_consent.approve_button')!.trigger('click')
    await flushPromises()

    expect(mocks.approve).toHaveBeenCalledWith('tok-123')
    expect(mocks.reject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('parental_consent.approved_message')
  })

  it('「同意しない」は確認を挟み、確認ボタンを押したときだけ reject を呼ぶ', async () => {
    const wrapper = await mountSuspended(ApprovePage, options)

    await buttonByText(wrapper, 'parental_consent.reject_button')!.trigger('click')
    await flushPromises()
    expect(mocks.reject).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('parental_consent.reject_confirm')

    // 確認エリアの拒否ボタン（同名のボタンが 2 つ目として現れる）
    const confirmButtons = wrapper.findAll('button').filter((b: DOMWrapper<Element>) => b.text() === 'parental_consent.reject_button')
    await confirmButtons[confirmButtons.length - 1]!.trigger('click')
    await flushPromises()

    expect(mocks.reject).toHaveBeenCalledWith('tok-123')
    expect(mocks.approve).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('parental_consent.rejected_message')
  })
})
