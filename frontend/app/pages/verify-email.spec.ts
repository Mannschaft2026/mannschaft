import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import VerifyEmailPage from './verify-email.vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
  query: {} as Record<string, string>,
  success: vi.fn(),
  error: vi.fn(),
}))
mockNuxtImport('useRoute', () => () => ({ query: mocks.query }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useNotification', () => () => ({ success: mocks.success, error: mocks.error }))
const options = {
  global: {
    stubs: {
      Button: { props: ['label'], template: '<button>{{ label }}</button>' },
      NuxtLink: {
        name: 'NuxtLink',
        props: ['to'],
        template: '<a><slot /></a>',
      },
      LoadingBounce: true,
    },
  },
}

const JAPANESE = /[぀-ヿ㐀-鿿]/
type Locale = 'ja' | 'en' | 'de'
async function setLocale(locale: Locale) {
  await useNuxtApp().$i18n.setLocale(locale)
}
function tr(key: string): string {
  return useNuxtApp().$i18n.t(key)
}
function expectTranslated(text: string) {
  expect(text).not.toMatch(JAPANESE)
  expect(text).not.toContain('auth.verify_email')
}

describe('登録確認後の招待への復帰', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({})
    mocks.query = { redirect: '/invite/abc?folder=7', email: 'invite@example.test' }
  })
  it('登録案内のログイン導線へ招待URLを渡す', async () => {
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    expect(wrapper.findComponent({ name: 'NuxtLink' }).props('to')).toEqual({
      path: '/login',
      query: { redirect: '/invite/abc?folder=7' },
    })
  })
  it('認証成功のログインボタンでもcomputedの復帰先を渡す', async () => {
    mocks.query.token = 'verification-token'
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    await wrapper.find('button').trigger('click')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/login',
      query: { redirect: '/invite/abc?folder=7' },
    })
  })
  it('外部復帰先はログイン導線へ渡さない', async () => {
    mocks.query.redirect = '//evil.example'
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    expect(wrapper.findComponent({ name: 'NuxtLink' }).props('to')).toEqual({
      path: '/login',
      query: {},
    })
  })
})

describe('CMP-261117-2159 verify-email の多言語化とBEメッセージ', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({})
    mocks.query = { email: 'a@example.test' }
    await setLocale('en')
  })

  it('AC-4: 検証失敗時、codeが無くてもBEのmessageを失敗画面の本文とトーストに出す', async () => {
    mocks.query.token = 'tok'
    mocks.api.mockRejectedValue({ data: { error: { message: 'Token already used (BE)' } } })
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    expect(wrapper.text()).toContain('Token already used (BE)')
    expect(mocks.error).toHaveBeenCalledWith(expect.any(String), 'Token already used (BE)')
  })

  it('AC-4: codeがあってもBEのmessageを出す', async () => {
    mocks.query.token = 'tok'
    mocks.api.mockRejectedValue({ data: { error: { code: 'AUTH_011', message: 'Expired (BE)' } } })
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    expect(wrapper.text()).toContain('Expired (BE)')
    expect(mocks.error).toHaveBeenCalledWith(expect.any(String), 'Expired (BE)')
  })

  it('AC-5: messageが無い失敗（通信断・空本文）は表示言語の既定文言にフォールバックする', async () => {
    mocks.query.token = 'tok'
    mocks.api.mockRejectedValue(new Error('network'))
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    const fallback = tr('auth.verify_email.failed_description')
    expect(fallback).not.toBe('auth.verify_email.failed_description')
    expectTranslated(fallback)
    expect(wrapper.text()).toContain(fallback)
    expect(mocks.error).toHaveBeenCalledWith(expect.any(String), fallback)
    mocks.api.mockRejectedValue({ data: {} })
    const wrapper2 = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    expect(wrapper2.text()).toContain(fallback)
  })

  it.each([undefined, ''])('AC-6: tokenが %j のとき案内画面を表示言語で出す', async (token) => {
    if (token !== undefined) mocks.query.token = token
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    expect(mocks.api).not.toHaveBeenCalled()
    expectTranslated(wrapper.text())
    expect(wrapper.text()).toContain(tr('auth.verify_email.sent_title'))
    expect(wrapper.text()).toContain(tr('auth.verify_email.back_to_login'))
    expect(wrapper.text()).toContain(tr('auth.verify_email.resend_button'))
  })

  it.each(['en', 'de'] as const)('AC-7: %s で全状態（検証中・成功・失敗・再送・ログイン）が翻訳される', async (locale) => {
    await setLocale(locale)
    mocks.query.token = 'tok'
    // 検証中
    let release: () => void = () => {}
    mocks.api.mockReturnValueOnce(new Promise<void>((r) => { release = r }))
    const w1 = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    expect(w1.text()).toContain(tr('auth.verify_email.verifying'))
    expectTranslated(w1.text())
    release()
    await flushPromises()
    // 成功
    expect(w1.text()).toContain(tr('auth.verify_email.success_title'))
    expect(w1.text()).toContain(tr('auth.verify_email.to_login'))
    expectTranslated(w1.text())
    // 失敗
    mocks.api.mockRejectedValueOnce(new Error('x'))
    const w2 = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    expect(w2.text()).toContain(tr('auth.verify_email.failed_title'))
    expect(w2.text()).toContain(tr('auth.verify_email.resend_button'))
    expectTranslated(w2.text())
    if (locale === 'en') expect(tr('auth.verify_email.failed_title')).toBe('Verification failed')
    else expect(tr('auth.verify_email.failed_title')).not.toBe('Verification failed')
  })

  it('AC-8a: 再送失敗はBEのmessageを優先し、無ければ既定文言', async () => {
    mocks.api.mockRejectedValueOnce({ data: { error: { message: 'Too many requests (BE)' } } })
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    expect(mocks.error).toHaveBeenLastCalledWith(expect.any(String), 'Too many requests (BE)')
    mocks.api.mockRejectedValueOnce(new Error('network'))
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const fallback = tr('auth.verify_email.resend_failed_message')
    expectTranslated(fallback)
    expect(mocks.error).toHaveBeenLastCalledWith(tr('auth.verify_email.resend_failed_title'), fallback)
  })

  it('AC-8b: 再送成功のトーストがi18nで出る', async () => {
    const wrapper = await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    await wrapper.find('button').trigger('click')
    await flushPromises()
    const msg = tr('auth.verify_email.resend_success')
    expectTranslated(msg)
    expect(mocks.success).toHaveBeenCalledWith(msg)
  })

  it('AC-8c: 検証成功のトーストがi18nで出る', async () => {
    mocks.query.token = 'tok'
    await mountSuspended(VerifyEmailPage, options)
    await flushPromises()
    const msg = tr('auth.verify_email.success_toast')
    expectTranslated(msg)
    expect(mocks.success).toHaveBeenCalledWith(msg)
  })
})
