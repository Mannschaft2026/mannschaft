// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 戦役A「パスキー再認証（CMP-261021-1339 → 1338）」FE 試練。
 *
 * 検証観点:
 *   A6: 提示モードの再認証（useBiometricGate）は userVerification='required' で navigator.credentials.get を呼ぶ
 *   A7: 提示モードの再認証開始は用途 POINTCARD_PRESENT を BE に送る（POST /reauthenticate-begin 本文 { purpose }）
 *   B8: パスキー登録（useWebAuthnRegister）は authenticatorSelection.userVerification='required'
 *
 * 送信の契約は useApi（HTTP 層）をモックして URL と本文で観測する（useAuthApi の関数シグネチャに依存しない）。
 */

const mockApi = vi.fn()

vi.mock('~/composables/useApi', () => ({
  useApi: () => mockApi,
}))

// eslint-disable-next-line import/first
import { useBiometricGate } from '~/composables/wallet-group-show/useBiometricGate'
// eslint-disable-next-line import/first
import { usePasskeyReauth } from '~/composables/usePasskeyReauth'
// eslint-disable-next-line import/first
import { useWebAuthnRegister } from '~/composables/useWebAuthnRegister'

const b64url = (bytes: number[]) =>
  btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')

class FakePublicKeyCredential {
  rawId: ArrayBuffer
  response: unknown
  constructor(response: unknown) {
    this.rawId = new Uint8Array([1, 2, 3]).buffer
    this.response = response
  }
}

const credentialsGet = vi.fn()
const credentialsCreate = vi.fn()

beforeEach(() => {
  mockApi.mockReset()
  credentialsGet.mockReset()
  credentialsCreate.mockReset()
  vi.stubGlobal('PublicKeyCredential', FakePublicKeyCredential)
  Object.defineProperty(window, 'PublicKeyCredential', { value: FakePublicKeyCredential, configurable: true })
  Object.defineProperty(navigator, 'credentials', {
    value: { get: credentialsGet, create: credentialsCreate },
    configurable: true,
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('useBiometricGate（F18 提示モードの再認証）', () => {
  beforeEach(() => {
    mockApi.mockImplementation(async (url: string) => {
      if (url.endsWith('/reauthenticate-begin')) {
        return { data: { challenge: b64url([9, 9, 9]), rpId: 'mannschaft.app', allowCredentials: [b64url([1, 2, 3])], userId: 1, timeout: 60000 } }
      }
      if (url.endsWith('/reauthenticate-complete')) {
        return { data: { verifiedUntil: '2026-11-25T03:05:00Z' } }
      }
      throw new Error(`unexpected ${url}`)
    })
    credentialsGet.mockResolvedValue(new FakePublicKeyCredential({
      authenticatorData: new Uint8Array(37).buffer,
      clientDataJSON: new Uint8Array([123, 125]).buffer,
      signature: new Uint8Array([4, 5]).buffer,
    }))
  })

  it('A6: 再認証 options は userVerification=required で要求する', async () => {
    await useBiometricGate().verifyBiometric()

    expect(credentialsGet).toHaveBeenCalledTimes(1)
    const options = credentialsGet.mock.calls[0]![0] as CredentialRequestOptions
    expect(options.publicKey?.userVerification).toBe('required')
  })

  it('A7: 再認証開始に用途 POINTCARD_PRESENT を送る', async () => {
    await useBiometricGate().verifyBiometric()

    const begin = mockApi.mock.calls.find(([url]) => String(url).endsWith('/api/v1/auth/webauthn/reauthenticate-begin'))
    expect(begin).toBeDefined()
    expect(begin![1]).toMatchObject({ method: 'POST', body: { purpose: 'POINTCARD_PRESENT' } })
  })
})

describe('usePasskeyReauth のエラー分類（A6: キャンセルを UV 不可と誤案内しない）', () => {
  beforeEach(() => {
    mockApi.mockImplementation(async (url: string) => {
      if (url.endsWith('/reauthenticate-begin')) {
        return { data: { challenge: b64url([9, 9, 9]), rpId: 'mannschaft.app', allowCredentials: [b64url([1, 2, 3])], userId: 1, timeout: 60000 } }
      }
      throw new Error(`unexpected ${url}`)
    })
  })

  it('Windows Hello の無い PC + USB セキュリティキー（PIN 付き）でキャンセル → cancelled（UV 不可にしない）', async () => {
    // プラットフォーム認証器は無いが、UV 対応の外付けキーは使える端末
    Object.defineProperty(FakePublicKeyCredential, 'isUserVerifyingPlatformAuthenticatorAvailable', {
      value: vi.fn().mockResolvedValue(false),
      configurable: true,
    })
    credentialsGet.mockRejectedValue(new DOMException('cancelled', 'NotAllowedError'))

    expect(await usePasskeyReauth().reauthenticateWithPasskey('PASSKEY_MANAGE')).toBe('cancelled')
  })

  it('NotAllowedError は常に cancelled（タイムアウト・キャンセルと UV 不可は区別できない）', async () => {
    credentialsGet.mockRejectedValue(new DOMException('timeout', 'NotAllowedError'))

    expect(await usePasskeyReauth().reauthenticateWithPasskey('POINTCARD_PRESENT')).toBe('cancelled')
  })

  it.each(['ConstraintError', 'NotSupportedError'])('%s は UV 不可と確定できるので uv_unavailable', async (name) => {
    credentialsGet.mockRejectedValue(new DOMException('uv', name))

    expect(await usePasskeyReauth().reauthenticateWithPasskey('PASSKEY_MANAGE')).toBe('uv_unavailable')
  })

  it('BE の AUTH_024（UV 不足の assertion）は uv_unavailable', async () => {
    credentialsGet.mockResolvedValue(new FakePublicKeyCredential({
      authenticatorData: new Uint8Array(37).buffer,
      clientDataJSON: new Uint8Array([123, 125]).buffer,
      signature: new Uint8Array([4, 5]).buffer,
    }))
    mockApi.mockImplementation(async (url: string) => {
      if (url.endsWith('/reauthenticate-begin')) {
        return { data: { challenge: b64url([9]), rpId: 'mannschaft.app', allowCredentials: [b64url([1])], userId: 1, timeout: 60000 } }
      }
      throw { response: { status: 400, headers: { get: () => null } }, data: { error: { code: 'AUTH_024' } } }
    })

    expect(await usePasskeyReauth().reauthenticateWithPasskey('PASSKEY_MANAGE')).toBe('uv_unavailable')
  })

  it('WebAuthn API 自体が無い環境は uv_unavailable', async () => {
    Object.defineProperty(window, 'PublicKeyCredential', { value: undefined, configurable: true })

    expect(await usePasskeyReauth().reauthenticateWithPasskey('PASSKEY_MANAGE')).toBe('uv_unavailable')
  })
})

describe('useWebAuthnRegister（パスキー登録）', () => {
  it('B8: 登録 options は authenticatorSelection.userVerification=required で要求する', async () => {
    mockApi.mockImplementation(async (url: string) => {
      if (url.endsWith('/register/begin')) {
        return { data: { challenge: b64url([7, 7]), rpId: 'mannschaft.app', rpName: 'Mannschaft', userId: 1, userDisplayName: '試練 太郎' } }
      }
      return { data: { message: 'ok' } }
    })
    credentialsCreate.mockResolvedValue(new FakePublicKeyCredential({
      attestationObject: new Uint8Array([1]).buffer,
      clientDataJSON: new Uint8Array([123, 125]).buffer,
      getPublicKey: () => new Uint8Array([2]).buffer,
    }))

    await useWebAuthnRegister().registerPasskey('試験端末')

    expect(credentialsCreate).toHaveBeenCalledTimes(1)
    const options = credentialsCreate.mock.calls[0]![0] as CredentialCreationOptions
    expect(options.publicKey?.authenticatorSelection?.userVerification).toBe('required')
  })
})
