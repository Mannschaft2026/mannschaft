import { useAuthApi } from './useAuthApi'
import { b64urlToBuf, bufToB64url } from '~/utils/webauthn'

/** パスキーが使えない端末・ブラウザ。 */
export class PasskeyReauthUnsupportedError extends Error {
  constructor() {
    super('passkey_unsupported')
  }
}

/**
 * 認証済み本人のパスキー再認証の署名を取得し、BE の再認証材料（JSON 文字列）にして返す。
 *
 * 例: サポート閲覧の許可（passkeyAssertionJson）。BE の reauthenticate-begin でチャレンジを発行し、
 * 認証器の署名を credentialId / authenticatorData / clientDataJson / signature / signCount にまとめる。
 * 認証器のキャンセル・タイムアウトは通常の操作なので null を返す。
 */
export function usePasskeyReauthAssertion() {
  const { beginWebAuthnReauthenticate } = useAuthApi()

  const supported = () =>
    typeof window !== 'undefined'
    && window.isSecureContext
    && !!window.PublicKeyCredential
    && !!navigator.credentials?.get

  async function getAssertionJson(): Promise<string | null> {
    if (!supported()) throw new PasskeyReauthUnsupportedError()
    const { data: begin } = await beginWebAuthnReauthenticate()
    let credential: Credential | null
    try {
      credential = await navigator.credentials.get({
        publicKey: {
          challenge: b64urlToBuf(begin.challenge),
          rpId: begin.rpId,
          timeout: begin.timeout,
          userVerification: 'required',
          allowCredentials: begin.allowCredentials.map(id => ({ type: 'public-key', id: b64urlToBuf(id) })),
        },
      })
    }
    catch (error: unknown) {
      if (error instanceof DOMException && (error.name === 'NotAllowedError' || error.name === 'AbortError')) return null
      throw error
    }
    if (!credential) return null
    if (!(credential instanceof PublicKeyCredential)) throw new Error('Invalid credential response')
    const response = credential.response as AuthenticatorAssertionResponse
    return JSON.stringify({
      credentialId: bufToB64url(credential.rawId),
      authenticatorData: bufToB64url(response.authenticatorData),
      clientDataJson: bufToB64url(response.clientDataJSON),
      signature: bufToB64url(response.signature),
      signCount: new DataView(response.authenticatorData).getUint32(33, false),
    })
  }

  return { supported, getAssertionJson }
}
