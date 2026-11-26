import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type DOMWrapper, type VueWrapper } from '@vue/test-utils'
import ApprovePage from './approve.vue'
import { useAuthStore } from '~/stores/useAuthStore'
import { parentalConsentRedirect } from '~/utils/parentalConsentReturn'

/**
 * CMP-261123-1124 保護者の承認画面: ログイン済みでも表示しただけでは承認しない。
 * 「同意する」「同意しない（確認つき）」を保護者が明示的に選んだときだけ、対応する API を呼ぶ。
 */
const mocks = vi.hoisted(() => ({
  approve: vi.fn(),
  reject: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  navigate: vi.fn(),
}))
const routeState = vi.hoisted(() => ({ path: '/parental-consent/approve', params: {}, query: { token: 'tok-123' } as Record<string, string> }))

mockNuxtImport('useParentalConsentApi', () => () => ({ approve: mocks.approve, reject: mocks.reject }))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('useNotification', () => () => ({ success: mocks.success, error: mocks.error }))
mockNuxtImport('navigateTo', () => mocks.navigate)
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

/**
 * CMP-261123-1124 アリシゼーション ALIC-1/ALIC-2
 * - 未ログインで開いたら token を保存し、ログイン/登録へは redirect（承認画面・token 付き）を渡す。ログイン済みなら保存を破棄する
 * - 承認・拒否のあとは行き止まりにせず、次の行き先のボタンを出す
 */
describe('parental-consent/approve.vue 復帰導線（ALIC-1）', () => {
  it('未ログインで開くと token を保存し、ログインへ redirect 付きで誘導する', async () => {
    localStorage.clear()
    useAuthStore().$patch({ user: null } as never)
    const wrapper = await mountSuspended(ApprovePage, options)
    await flushPromises()

    expect(JSON.parse(localStorage.getItem('parentalConsentReturn') ?? '{}').token).toBe('tok-123')
    await buttonByText(wrapper, 'parental_consent.login_button')!.trigger('click')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/login',
      query: { redirect: '/parental-consent/approve?token=tok-123' },
    })
  })

  it('未ログインから登録へも redirect 付きで誘導する', async () => {
    useAuthStore().$patch({ user: null } as never)
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, 'parental_consent.register_button')!.trigger('click')

    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/register',
      query: { redirect: '/parental-consent/approve?token=tok-123' },
    })
  })

  it('ログイン済みで承認画面に着いたら、保存していた token を破棄する', async () => {
    localStorage.setItem('parentalConsentReturn', JSON.stringify({ token: 'tok-123', savedAt: Date.now() }))
    await mountSuspended(ApprovePage, options)
    await flushPromises()

    expect(localStorage.getItem('parentalConsentReturn')).toBeNull()
  })
})

describe('parental-consent/approve.vue 決定後の行き先（ALIC-2）', () => {
  it('承認後は「保護者管理へ」「ダッシュボードへ」のボタンを出し、押すと遷移する', async () => {
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, 'parental_consent.approve_button')!.trigger('click')
    await flushPromises()

    await buttonByText(wrapper, 'parental_consent.go_manage')!.trigger('click')
    expect(mocks.navigate).toHaveBeenCalledWith('/parental-consent/manage')
    await buttonByText(wrapper, 'parental_consent.go_dashboard')!.trigger('click')
    expect(mocks.navigate).toHaveBeenCalledWith('/dashboard')
  })

  it('拒否後は「ダッシュボードへ」のボタンを出す', async () => {
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, 'parental_consent.reject_button')!.trigger('click')
    const confirms = wrapper.findAll('button').filter((b: DOMWrapper<Element>) => b.text() === 'parental_consent.reject_button')
    await confirms[confirms.length - 1]!.trigger('click')
    await flushPromises()

    await buttonByText(wrapper, 'parental_consent.go_dashboard')!.trigger('click')
    expect(mocks.navigate).toHaveBeenCalledWith('/dashboard')
  })
})

/**
 * CMP-261123-1124 Codex 第6回: 承認者は招待先メールのアカウントに限る。別アカウントでの承認・拒否は AUTH_089（403）。
 * 画面は「招待されたメールアドレスのアカウントでログインしてください」と案内し、ログアウトして入り直す導線を出す。
 * BE のエラー応答の業務コードは data.error.code にある（data.code ではない）。
 */
function apiError(code: string) {
  return Object.assign(new Error('forbidden'), { data: { error: { code } } })
}

describe('parental-consent/approve.vue 別アカウントでの承認（AUTH_089）', () => {
  it.each([
    ['approve', 'parental_consent.approve_button'],
    ['reject', 'parental_consent.reject_button'],
  ])('%s が AUTH_089 になったら、アカウント違いの案内とログアウトして入り直すボタンを出す', async (kind, label) => {
    mocks.approve.mockRejectedValue(apiError('AUTH_089'))
    mocks.reject.mockRejectedValue(apiError('AUTH_089'))
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, label)!.trigger('click')
    if (kind === 'reject') {
      const confirms = wrapper.findAll('button').filter((b: DOMWrapper<Element>) => b.text() === 'parental_consent.reject_button')
      await confirms[confirms.length - 1]!.trigger('click')
    }
    await flushPromises()

    expect(wrapper.text()).toContain('parental_consent.error_wrong_account')
    expect(wrapper.text()).not.toContain('parental_consent.invalid_token')
    expect(wrapper.text()).not.toContain('parental_consent.approved_message')
    expect(buttonByText(wrapper, 'parental_consent.relogin_button')).toBeDefined()
  })

  it('「別のアカウントでログイン」を押すとログアウトし、承認画面（token 付き）へ戻る redirect 付きでログインへ進む', async () => {
    mocks.approve.mockRejectedValue(apiError('AUTH_089'))
    const logout = vi.spyOn(useAuthStore(), 'serverLogout').mockResolvedValue(undefined)
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, 'parental_consent.approve_button')!.trigger('click')
    await flushPromises()

    await buttonByText(wrapper, 'parental_consent.relogin_button')!.trigger('click')
    await flushPromises()

    // 遷移は logout 側の 1 回だけ（画面側で navigateTo を重ねない）。遷移先は許可リストに通る token 付き承認画面
    expect(logout).toHaveBeenCalledOnce()
    const { redirectTo } = logout.mock.calls[0]![0] as { redirectTo: string }
    expect(redirectTo).toBe('/login?redirect=' + encodeURIComponent('/parental-consent/approve?token=tok-123'))
    const redirect = new URLSearchParams(redirectTo.split('?')[1]).get('redirect')
    expect(parentalConsentRedirect(redirect)).toBe('/parental-consent/approve?token=tok-123')
    expect(mocks.navigate).not.toHaveBeenCalled()
  })

  it('logout の redirectTo 指定時はその URL へ 1 回だけ遷移し、保存 token は破棄する。既定は /login', async () => {
    const store = useAuthStore()
    localStorage.setItem('parentalConsentReturn', JSON.stringify({ token: 'tok-123', savedAt: Date.now() }))
    const target = '/login?redirect=' + encodeURIComponent('/parental-consent/approve?token=tok-123')
    await store.logout({ redirectTo: target })
    expect(mocks.navigate).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).toHaveBeenCalledWith(target)
    expect(localStorage.getItem('parentalConsentReturn')).toBeNull()

    mocks.navigate.mockClear()
    await store.logout()
    expect(mocks.navigate).toHaveBeenCalledTimes(1)
    expect(mocks.navigate).toHaveBeenCalledWith('/login')
  })

  it.each(['//evil.example/x', 'https://evil.example', 'javascript:alert(1)'])('logout の redirectTo は相対パス以外（%s）を採用せず /login へ', async (bad) => {
    await useAuthStore().logout({ redirectTo: bad })
    expect(mocks.navigate).toHaveBeenCalledWith('/login')
  })

  it('AUTH_062 / AUTH_063 は従来どおり専用のトースト（data.error.code を読む）', async () => {
    mocks.approve.mockRejectedValue(apiError('AUTH_063'))
    const wrapper = await mountSuspended(ApprovePage, options)
    await buttonByText(wrapper, 'parental_consent.approve_button')!.trigger('click')
    await flushPromises()

    expect(mocks.error).toHaveBeenCalledWith('parental_consent.error_auth_063')
  })
})
