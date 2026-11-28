// @vitest-environment happy-dom
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { mount } from '@vue/test-utils'
import PrimeVue from 'primevue/config'
import { setActivePinia, createPinia } from 'pinia'
import { nextTick, ref } from 'vue'

/**
 * CMP-261123-1124 導線漏れ: pages/2fa-recovery.vue（2FA リカバリーログイン）。
 * トークン保存だけで '/' へ進むと本人・status が復元されない。BE はこの経路でも ppc=true を発行しうるため、
 * 共通のログイン完了処理（completeLogin）に任せ、その遷移先（同意待ちなら pending、ACTIVE なら通常）へ進む。
 */
const mocks = vi.hoisted(() => ({
  confirmMfaRecovery: vi.fn(),
  completeLogin: vi.fn(),
  navigateTo: vi.fn(),
  notifyError: vi.fn(),
}))
const routeState = vi.hoisted(() => ({ path: '/2fa-recovery', params: {}, query: {} as Record<string, string> }))

mockNuxtImport('useAuthApi', () => () => ({ requestMfaRecovery: vi.fn(), confirmMfaRecovery: mocks.confirmMfaRecovery }))
mockNuxtImport('useLoginSession', () => () => ({ completeLogin: mocks.completeLogin }))
mockNuxtImport('useRoute', () => () => routeState)
mockNuxtImport('navigateTo', () => mocks.navigateTo)
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useHydrated', () => () => ref(true))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: vi.fn(), error: mocks.notifyError, info: vi.fn(), warn: vi.fn() }),
}))

const RecoveryPage = (await import('~/pages/2fa-recovery.vue')).default

async function flush(times = 8): Promise<void> {
  for (let i = 0; i < times; i++) {
    await nextTick()
    await new Promise(resolve => setTimeout(resolve, 0))
  }
}

async function confirm() {
  const wrapper = mount(RecoveryPage, {
    global: {
      plugins: [PrimeVue],
      stubs: {
        NuxtLink: { template: '<a><slot /></a>' },
        Message: true,
        InputText: true,
        Button: { template: '<button v-bind="$attrs"><slot /></button>' },
      },
    },
  })
  await flush()
  await wrapper.find('[data-testid="mfa-recovery-confirm"]').trigger('click')
  await flush()
}

beforeEach(() => {
  setActivePinia(createPinia())
  Object.values(mocks).forEach(m => m.mockReset())
  routeState.query = { token: 'recovery-token' }
  mocks.confirmMfaRecovery.mockResolvedValue({ data: { accessToken: 'access', refreshToken: 'refresh' } })
})

describe('pages/2fa-recovery.vue 保護者同意待ち（CMP-261123-1124）', () => {
  it('同意待ち: completeLogin にトークンを渡し、返った pending へ遷移する（\'/\' へは進まない）', async () => {
    mocks.completeLogin.mockResolvedValue('/parental-consent/pending')

    await confirm()

    expect(mocks.completeLogin).toHaveBeenCalledWith({ accessToken: 'access', refreshToken: 'refresh' })
    expect(mocks.navigateTo).toHaveBeenCalledWith('/parental-consent/pending')
    expect(mocks.navigateTo).not.toHaveBeenCalledWith('/')
  })

  it('ACTIVE: completeLogin が返した通常の遷移先へ進む（回帰）', async () => {
    mocks.completeLogin.mockResolvedValue('/dashboard')

    await confirm()

    expect(mocks.navigateTo).toHaveBeenCalledWith('/dashboard')
  })

  it('本人復元に失敗（completeLogin が例外）したら遷移せずエラー扱いにする', async () => {
    mocks.completeLogin.mockRejectedValue(new Error('profile'))

    await confirm()

    expect(mocks.navigateTo).not.toHaveBeenCalled()
    expect(mocks.notifyError).toHaveBeenCalled()
  })
})
