// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAuthApi } from './useAuthApi'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('./useApi', () => ({ useApi: () => (mocks.api) }))

beforeEach(() => vi.clearAllMocks())

describe('useAuthApi OAuth ログインのHTTP契約（CMP-261012-0911 AC-17）', () => {
  it('認可コードは BE の @RequestParam 契約どおりクエリ（?code=）で送り、本文では送らない', async () => {
    await useAuthApi().loginWithOAuth('google', { code: 'auth-code/with+chars' })

    expect(mocks.api).toHaveBeenCalledWith('/api/v1/auth/oauth/google', {
      method: 'POST', query: { code: 'auth-code/with+chars' },
    })
  })
})

describe('useAuthApi パスキー開始のHTTP契約', () => {
  it('POSTのqueryへemailを渡す', async () => {
    await useAuthApi().beginWebAuthnLogin('member+passkey@example.com')

    expect(mocks.api).toHaveBeenCalledWith('/api/v1/auth/webauthn/login/begin', {
      method: 'POST', query: { email: 'member+passkey@example.com' },
    })
  })
})
