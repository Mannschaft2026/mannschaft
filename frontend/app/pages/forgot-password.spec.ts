import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ForgotPasswordPage from './forgot-password.vue'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)

const options = {
  global: {
    stubs: {
      Button: { props: ['label', 'disabled'], template: '<button type="submit">{{ label }}</button>' },
      NuxtLink: { name: 'NuxtLink', props: ['to'], template: '<a><slot /></a>' },
    },
  },
}

const JAPANESE = /[぀-ヿ㐀-鿿]/
async function setLocale(locale: 'ja' | 'en' | 'de') {
  await useNuxtApp().$i18n.setLocale(locale)
}
function tr(key: string): string {
  return useNuxtApp().$i18n.t(key)
}
// vee-validate の検証は非同期のため、送信後は画面に期待する文言が出るまで待つ。
async function submitWith(wrapper: Awaited<ReturnType<typeof mountSuspended>>, value: string, until: string) {
  await wrapper.find('#email').setValue(value)
  await wrapper.find('form').trigger('submit')
  await vi.waitFor(() => expect(wrapper.text()).toContain(until), { timeout: 10000 })
  await flushPromises()
}

describe('CMP-261117-2159 forgot-password の多言語化', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({})
  })

  it.each(['en', 'de'] as const)('AC-1: %s で全文言が日本語を含まず $t 経由で出る', async (locale) => {
    await setLocale(locale)
    const wrapper = await mountSuspended(ForgotPasswordPage, options)
    const text = wrapper.text()
    expect(text).not.toMatch(JAPANESE)
    expect(text).toContain(tr('auth.password_reset.request_description'))
    expect(text).toContain(tr('auth.register.email_label'))
    expect(text).toContain(tr('auth.password_reset.request_submit'))
    expect(text).toContain(tr('auth.password_reset.back_to_login'))
    expect(text).not.toContain('auth.password_reset.')
    await submitWith(wrapper, 'user@example.com', tr('auth.password_reset.request_done'))
    const done = wrapper.text()
    expect(done).not.toMatch(JAPANESE)
    expect(done).toContain(tr('auth.password_reset.request_done'))
    expect(done).toContain(tr('auth.password_reset.back_to_login'))
  })

  it.each(['en', 'de'] as const)('AC-2: %s で空は必須エラー・"a" は形式エラーが表示言語で出る', async (locale) => {
    await setLocale(locale)
    const wrapper = await mountSuspended(ForgotPasswordPage, options)
    await submitWith(wrapper, '', tr('auth.register.err_email_required'))
    expect(wrapper.text()).not.toMatch(JAPANESE)
    await submitWith(wrapper, 'a', tr('auth.register.err_email_invalid'))
    expect(wrapper.text()).toContain(tr('auth.register.err_email_invalid'))
    expect(wrapper.text()).not.toContain(tr('auth.register.err_email_required'))
    expect(wrapper.text()).not.toMatch(JAPANESE)
    expect(mocks.api).not.toHaveBeenCalled()
  })

  it.each([
    ['4xx', { statusCode: 404, data: { error: { code: 'AUTH_001', message: 'No such user (BE)' } } }],
    ['5xx', { statusCode: 500, data: { error: { message: 'Internal secret (BE)' } } }],
    ['通信断', new Error('network down')],
  ])('AC-3: API が %s で失敗しても成功と同じ完了画面でBE messageは出さない', async (_n, err) => {
    await setLocale('en')
    mocks.api.mockRejectedValue(err)
    const wrapper = await mountSuspended(ForgotPasswordPage, options)
    await submitWith(wrapper, 'user@example.com', tr('auth.password_reset.request_done'))
    expect(mocks.api).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain(tr('auth.password_reset.request_done'))
    expect(wrapper.text()).not.toContain('(BE)')
  })
})
