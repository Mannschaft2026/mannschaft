import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import RegisterPage from './register.vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  navigate: vi.fn(),
  query: {} as Record<string, string>,
  error: vi.fn(),
}))
const values = {
  email: 'invite@example.test',
  password: 'Password123!',
  lastName: '招待',
  firstName: 'テスト',
  displayName: '招待',
  postalCode: '1000001',
  birthDate: '2000-01-01',
  privacyPolicyAccepted: true,
  termsAccepted: true,
}
vi.mock('vee-validate', () => ({
  useForm: () => ({
    errors: {},
    defineField: (name: keyof typeof values) => [ref(values[name]), {}],
    handleSubmit: (submit: (value: typeof values) => Promise<void>) => () => submit(values),
  }),
}))
mockNuxtImport('useRoute', () => () => ({ query: mocks.query }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useAuthApi', () => () => ({ getGoogleAuthUrl: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: mocks.error }))
mockNuxtImport('useHydrated', () => () => ref(true))
mockNuxtImport('useDatetime', () => () => ({ userTimezone: ref('Asia/Tokyo') }))
mockNuxtImport('usePostalCodeValidation', () => () => ({
  ensureLoaded: vi.fn().mockResolvedValue(undefined),
  isSupported: () => true,
  validateFormat: () => true,
}))
// register.vue は送信 locale と郵便番号の実効国に表示言語（locale）を使うため、t に加えて locale も要る
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, locale: ref('ja') }))
const options = {
  global: {
    stubs: {
      Button: true,
      InputText: true,
      Password: true,
      Checkbox: true,
      DatePicker: true,
      Dialog: true,
      TermsOfServiceModal: true,
      PrivacyPolicyModal: true,
      NuxtLink: {
        name: 'NuxtLink',
        props: ['to'],
        template: '<a><slot /></a>',
      },
    },
  },
}

describe('招待からの登録と既存β招待の分離', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.api.mockResolvedValue({})
    mocks.query = { redirect: '/invite/team-token?folder=7' }
  })
  it('チーム招待をβinviteTokenにせず登録案内とログインに復帰先を渡す', async () => {
    const wrapper = await mountSuspended(RegisterPage, options)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocks.api.mock.calls[0]![1].body).not.toHaveProperty('inviteToken')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/verify-email',
      query: { email: values.email, redirect: '/invite/team-token?folder=7' },
    })
    expect(wrapper.findAllComponents({ name: 'NuxtLink' }).at(-1)!.props('to')).toEqual({
      path: '/login',
      query: { redirect: '/invite/team-token?folder=7' },
    })
  })
  it('明示された既存βinviteは従来どおり登録payloadに渡す', async () => {
    mocks.query = { invite: 'beta-token' }
    const wrapper = await mountSuspended(RegisterPage, options)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocks.api.mock.calls[0]![1].body.inviteToken).toBe('beta-token')
    expect(mocks.navigate).toHaveBeenCalledWith({
      path: '/verify-email',
      query: { email: values.email },
    })
  })
})

describe('招待付き新規登録の拒否表示（CMP-261107-1833 AC4/AC13）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.query = { invite: 'targeted-token' }
  })
  it('AUTH_083（宛先付き招待で登録不可）は専用の案内を出し、メール確認へ進まない', async () => {
    mocks.api.mockRejectedValue({
      statusCode: 403,
      data: { error: { code: 'AUTH_083', message: 'server message' } },
    })
    const wrapper = await mountSuspended(RegisterPage, options)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocks.api.mock.calls[0]![1].body.inviteToken).toBe('targeted-token')
    expect(mocks.error).toHaveBeenCalledWith(
      'auth.register.invite_targeted_title',
      'auth.register.invite_targeted_desc',
    )
    expect(mocks.navigate).not.toHaveBeenCalled()
  })
  it('AUTH_083 の案内は汎用の登録失敗文言にしない', async () => {
    mocks.api.mockRejectedValue({ statusCode: 403, data: { error: { code: 'AUTH_083' } } })
    const wrapper = await mountSuspended(RegisterPage, options)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(mocks.error).not.toHaveBeenCalledWith('auth.register.failed_title', expect.anything())
  })
})
