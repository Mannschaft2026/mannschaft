import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { usePasskeyManageReauth } from './usePasskeyManageReauth'

/**
 * CMP-261021-1338 B5/B9: パスキー登録・削除前の再認証（手段は BE の reauthenticate-method で自動選択）。
 * 送信の契約は useApi（HTTP 層）をモックして URL と本文で観測する。
 */
const apiMock = vi.fn()
const passkeyReauthMock = vi.fn()
const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn() }

vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))
vi.mock('~/composables/usePasskeyReauth', () => ({
  usePasskeyReauth: () => ({ reauthenticateWithPasskey: passkeyReauthMock }),
}))
mockNuxtImport('useNotification', () => () => notificationMock)
mockNuxtImport('useI18n', () => () => ({
  t: (key: string, params?: Record<string, unknown>) => (params && Object.keys(params).length ? `${key}:${JSON.stringify(params)}` : key),
}))

const METHOD_URL = '/api/v1/auth/webauthn/reauthenticate-method'
const FACTOR_URL = '/api/v1/auth/webauthn/reauthenticate-factor'

function apiError(status: number, code?: string) {
  return { response: { status, headers: { get: () => null } }, data: code ? { error: { code } } : undefined }
}

function respondWithMethod(method: string) {
  apiMock.mockImplementation(async (url: string) => {
    if (url === METHOD_URL) return { data: { method } }
    return { data: { verifiedUntil: '2026-11-25T03:05:00Z' } }
  })
}

beforeEach(() => {
  apiMock.mockReset()
  passkeyReauthMock.mockReset()
  notificationMock.success.mockReset()
  notificationMock.error.mockReset()
  notificationMock.warn.mockReset()
})

describe('usePasskeyManageReauth', () => {
  it('PASSKEY: ダイアログを出さず、用途 PASSKEY_MANAGE のパスキー再認証を行う', async () => {
    respondWithMethod('PASSKEY')
    passkeyReauthMock.mockResolvedValue('ok')
    const reauth = usePasskeyManageReauth()

    expect(await reauth.ensureReauthenticated()).toBe(true)

    expect(passkeyReauthMock).toHaveBeenCalledWith('PASSKEY_MANAGE')
    expect(reauth.dialogVisible.value).toBe(false)
  })

  it('PASSKEY: UV 不可の端末では専用の文言を出し、続行しない', async () => {
    respondWithMethod('PASSKEY')
    passkeyReauthMock.mockResolvedValue('uv_unavailable')
    const reauth = usePasskeyManageReauth()

    expect(await reauth.ensureReauthenticated()).toBe(false)

    expect(notificationMock.error).toHaveBeenCalledWith('settings.security.webauthn.reauth.uv_unavailable')
  })

  it('PASSKEY: 利用者のキャンセルは黙って中断する', async () => {
    respondWithMethod('PASSKEY')
    passkeyReauthMock.mockResolvedValue('cancelled')
    const reauth = usePasskeyManageReauth()

    expect(await reauth.ensureReauthenticated()).toBe(false)

    expect(notificationMock.error).not.toHaveBeenCalled()
  })

  it('PASSWORD: ダイアログを開き、送信の成功で再認証が完了する（ダイアログは閉じる）', async () => {
    respondWithMethod('PASSWORD')
    const reauth = usePasskeyManageReauth()

    const done = reauth.ensureReauthenticated()
    await flushPromises()
    expect(reauth.dialogVisible.value).toBe(true)
    expect(reauth.dialogMethod.value).toBe('PASSWORD')

    await reauth.submit({ currentPassword: 'pw' })

    expect(await done).toBe(true)
    expect(apiMock).toHaveBeenCalledWith(FACTOR_URL, { method: 'POST', body: { currentPassword: 'pw' } })
    expect(reauth.dialogVisible.value).toBe(false)
  })

  it('AUTH_080: 失敗はダイアログ内に理由を出し、開いたまま再入力できる', async () => {
    respondWithMethod('PASSWORD')
    const reauth = usePasskeyManageReauth()
    const done = reauth.ensureReauthenticated()
    await flushPromises()

    apiMock.mockImplementation(async (url: string) => {
      if (url === FACTOR_URL) throw apiError(400, 'AUTH_080')
      return { data: {} }
    })
    await reauth.submit({ currentPassword: 'wrong' })

    expect(reauth.errorMessage.value).toBe('settings.security.webauthn.reauth.failed')
    expect(reauth.dialogVisible.value).toBe(true)

    respondWithMethod('PASSWORD')
    await reauth.submit({ currentPassword: 'right' })
    expect(await done).toBe(true)
    expect(reauth.errorMessage.value).toBe('')
  })

  it('AUTH_044（429）: レート制限は 2FA 設定と同じ文言で見せる', async () => {
    respondWithMethod('PASSWORD')
    const reauth = usePasskeyManageReauth()
    void reauth.ensureReauthenticated()
    await flushPromises()

    apiMock.mockImplementation(async () => {
      throw apiError(429, 'AUTH_044')
    })
    await reauth.submit({ currentPassword: 'pw' })

    expect(reauth.errorMessage.value).toContain('settings.security.two_fa.error.rate_limited')
  })

  it('キャンセル（ダイアログを閉じる）は false で終わる', async () => {
    respondWithMethod('PASSWORD_AND_SECOND_FACTOR')
    const reauth = usePasskeyManageReauth()
    const done = reauth.ensureReauthenticated()
    await flushPromises()
    expect(reauth.dialogMethod.value).toBe('PASSWORD_AND_SECOND_FACTOR')

    reauth.onDialogVisibleChange(false)

    expect(await done).toBe(false)
    expect(reauth.dialogVisible.value).toBe(false)
  })

  it('EMAIL_OTP: メールOTP は専用 API で送り、送信結果を保持する', async () => {
    respondWithMethod('EMAIL_OTP')
    const reauth = usePasskeyManageReauth()
    void reauth.ensureReauthenticated()
    await flushPromises()
    apiMock.mockResolvedValue({ data: { maskedEmail: 'a***@example.com', expiresInSeconds: 600, resendAfterSeconds: 60 } })

    await reauth.sendEmail()

    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/auth/webauthn/reauthenticate-email-otp', { method: 'POST' })
    expect(reauth.reauthEmail.value?.maskedEmail).toBe('a***@example.com')
  })

  it('PASSWORD_AND_SECOND_FACTOR: 2FA 要素としてのメールは 2FA 用の送信 API を使う', async () => {
    respondWithMethod('PASSWORD_AND_SECOND_FACTOR')
    const reauth = usePasskeyManageReauth()
    void reauth.ensureReauthenticated()
    await flushPromises()
    apiMock.mockResolvedValue({ data: { maskedEmail: 'a***@example.com' } })

    await reauth.sendEmail()

    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/auth/2fa/reauth/email/send', { method: 'POST' })
  })

  it('SECOND_FACTOR: 2FA 状態を取得して 2FA 方式をダイアログへ渡し、メールは 2FA 用の送信 API を使う', async () => {
    apiMock.mockImplementation(async (url: string) => {
      if (url === METHOD_URL) return { data: { method: 'SECOND_FACTOR' } }
      if (url === '/api/v1/auth/2fa/status') return { data: { enabled: true, method: 'EMAIL' } }
      return { data: { maskedEmail: 'a***@example.com' } }
    })
    const reauth = usePasskeyManageReauth()
    void reauth.ensureReauthenticated()
    await flushPromises()

    expect(reauth.dialogVisible.value).toBe(true)
    expect(reauth.dialogMethod.value).toBe('SECOND_FACTOR')
    expect(reauth.twoFaMethod.value).toBe('EMAIL')

    await reauth.sendEmail()
    expect(apiMock).toHaveBeenLastCalledWith('/api/v1/auth/2fa/reauth/email/send', { method: 'POST' })
    expect(apiMock).not.toHaveBeenCalledWith('/api/v1/auth/webauthn/reauthenticate-email-otp', expect.anything())
  })

  it('再認証手段の取得に失敗したら握りつぶさず通知し、操作を実行しない', async () => {
    apiMock.mockRejectedValue(apiError(500))
    const reauth = usePasskeyManageReauth()
    const action = vi.fn()

    expect(await reauth.runWithReauth(action)).toBe('cancelled')

    expect(notificationMock.error).toHaveBeenCalled()
    expect(action).not.toHaveBeenCalled()
  })

  describe('runWithReauth', () => {
    it('再認証してから操作を 1 回実行する', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('ok')
      const reauth = usePasskeyManageReauth()
      const action = vi.fn().mockResolvedValue('done')

      expect(await reauth.runWithReauth(action)).toBe('done')

      expect(action).toHaveBeenCalledTimes(1)
    })

    it('AUTH_087（期限切れ等）+ パスキー: 自動で get を呼ばず、「やり直す」押下を起点に再認証して再試行する', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('ok')
      const reauth = usePasskeyManageReauth()
      const action = vi.fn().mockResolvedValueOnce('reauth_required').mockResolvedValueOnce('done')

      const run = reauth.runWithReauth(action)
      await flushPromises()

      // 期限切れ → やり直しダイアログで待機。ボタン押下までは 2 回目の get を呼ばない
      expect(reauth.retryVisible.value).toBe(true)
      expect(reauth.retryLeftoverNote.value).toBe(false)
      expect(passkeyReauthMock).toHaveBeenCalledTimes(1)

      reauth.confirmRetry()
      expect(await run).toBe('done')

      expect(reauth.retryVisible.value).toBe(false)
      expect(passkeyReauthMock).toHaveBeenCalledTimes(2)
      expect(action).toHaveBeenCalledTimes(2)
    })

    it('AUTH_087 + パスキー: やり直しダイアログを閉じたら再認証せず cancelled', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('ok')
      const reauth = usePasskeyManageReauth()
      const action = vi.fn().mockResolvedValue('reauth_required')

      const run = reauth.runWithReauth(action)
      await flushPromises()
      reauth.onRetryVisibleChange(false)

      expect(await run).toBe('cancelled')
      expect(passkeyReauthMock).toHaveBeenCalledTimes(1)
    })

    it('AUTH_087 + パスワード手段: WebAuthn を使わないので通知して再認証ダイアログへ進む', async () => {
      respondWithMethod('PASSWORD')
      const reauth = usePasskeyManageReauth()
      const action = vi.fn().mockResolvedValueOnce('reauth_required').mockResolvedValueOnce('done')

      const run = reauth.runWithReauth(action)
      await flushPromises()
      await reauth.submit({ currentPassword: 'pw' })
      await flushPromises()
      expect(reauth.dialogVisible.value).toBe(true)
      await reauth.submit({ currentPassword: 'pw' })

      expect(await run).toBe('done')
      expect(reauth.retryVisible.value).toBe(false)
      expect(notificationMock.warn).toHaveBeenCalledWith('settings.security.webauthn.reauth.expired')
    })

    it('登録の AUTH_087: 端末側に残ったパスキーの案内をやり直しダイアログに出す', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('ok')
      const reauth = usePasskeyManageReauth()

      const retry = reauth.reauthenticateAfterExpiry(true)
      await flushPromises()
      expect(reauth.retryVisible.value).toBe(true)
      expect(reauth.retryLeftoverNote.value).toBe(true)
      reauth.confirmRetry()

      expect(await retry).toBe(true)
    })

    it('先読み: 手段を先に取得しておけば、押下時の通信は begin だけ（手段の再取得をしない）', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('ok')
      const reauth = usePasskeyManageReauth()

      await reauth.prefetchMethod()
      apiMock.mockClear()
      expect(await reauth.ensureReauthenticated()).toBe(true)

      expect(apiMock).not.toHaveBeenCalledWith(METHOD_URL, expect.anything())
      expect(passkeyReauthMock).toHaveBeenCalledWith('PASSKEY_MANAGE')
    })

    it('AUTH_087 が続くときは無限に繰り返さず、利用者へ知らせて終える', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('ok')
      const reauth = usePasskeyManageReauth()
      const action = vi.fn().mockResolvedValue('reauth_required')

      const run = reauth.runWithReauth(action)
      await flushPromises()
      reauth.confirmRetry()

      expect(await run).toBe('reauth_required')

      expect(action).toHaveBeenCalledTimes(2)
      expect(notificationMock.error).toHaveBeenCalledWith('settings.security.webauthn.reauth.expired')
    })

    it('再認証がキャンセルされたら操作を実行しない', async () => {
      respondWithMethod('PASSKEY')
      passkeyReauthMock.mockResolvedValue('cancelled')
      const reauth = usePasskeyManageReauth()
      const action = vi.fn()

      expect(await reauth.runWithReauth(action)).toBe('cancelled')

      expect(action).not.toHaveBeenCalled()
    })
  })
})
