import { useAuthApi } from './useAuthApi'
import type { ReauthPurpose } from './useAuthApi'
import { b64urlToBuf, bufToB64url } from '~/utils/webauthn'
import { parseApiError } from '~/utils/apiError'

/**
 * パスキー再認証（WebAuthn assertion）の結果。
 *
 * - ok: 成功（BE に用途付きの再認証済みフラグが5分間立つ）
 * - cancelled: 利用者が認証ダイアログを閉じた、またはタイムアウト（NotAllowedError は常にこちら）
 * - uv_unavailable: UV を満たせないと確定できた（ConstraintError・NotSupportedError・BE の AUTH_024・WebAuthn API が無い）
 * - failed: サーバー検証の失敗など上記以外
 */
export type PasskeyReauthResult = 'ok' | 'cancelled' | 'uv_unavailable' | 'failed'

/** BE: UV 不足の assertion（AUTH_024）。 */
const ERROR_UV_REQUIRED = 'AUTH_024'

/**
 * authenticatorData（固定オフセット）から signCount を抽出する。
 * WebAuthn 仕様 §6.1: rpIdHash(32) | flags(1) | signCount(4, big-endian) | ...
 */
function extractSignCount(authenticatorData: ArrayBuffer): number {
  return new DataView(authenticatorData).getUint32(33, false)
}

/**
 * この端末では UV を満たせないと確定できるエラーか。
 *
 * <p>NotAllowedError は「利用者のキャンセル」「タイムアウト」と同じ名前で返り、UV 不可とは区別できない。
 * UV 対応の外付けセキュリティキー（PIN 付き USB キー等）を使う利用者が、Windows Hello の無い PC でキャンセルしただけでも
 * 返るため、プラットフォーム認証器の有無で UV 不可と推測してはならない（キャンセルを「この端末ではできません」と誤案内する）。
 * よって NotAllowedError は常にキャンセル扱いとし、UV 不可は明示的なエラー（ConstraintError・NotSupportedError）に限る。</p>
 */
function isUvUnsupported(e: unknown): boolean {
  return e instanceof DOMException && (e.name === 'ConstraintError' || e.name === 'NotSupportedError')
}

/**
 * パスキーによる再認証（reauthenticate-begin → navigator.credentials.get → reauthenticate-complete）。
 *
 * <p>UV（userVerification='required'）を要求する。用途（purpose）は challenge に束ねられ、
 * F18 提示モード（POINTCARD_PRESENT）の再認証でパスキー登録・削除はできず、逆も不可。</p>
 */
export function usePasskeyReauth() {
  const authApi = useAuthApi()

  async function reauthenticateWithPasskey(purpose: ReauthPurpose): Promise<PasskeyReauthResult> {
    // WebAuthn API 自体が無い環境（古いブラウザ・SSR）は UV も満たせない
    if (typeof window === 'undefined' || !window.PublicKeyCredential || !navigator.credentials) {
      return 'uv_unavailable'
    }
    try {
      const begin = (await authApi.beginWebAuthnReauthenticate(purpose)).data

      const publicKey: PublicKeyCredentialRequestOptions = {
        challenge: b64urlToBuf(begin.challenge),
        rpId: begin.rpId,
        timeout: begin.timeout,
        userVerification: 'required',
        allowCredentials: begin.allowCredentials.map(id => ({
          type: 'public-key' as const,
          id: b64urlToBuf(id),
        })),
      }

      const cred = await navigator.credentials.get({ publicKey })
      if (!cred) return 'cancelled'

      const pkCred = cred as PublicKeyCredential
      const assertion = pkCred.response as AuthenticatorAssertionResponse

      await authApi.completeWebAuthnReauthenticate({
        credentialId: bufToB64url(pkCred.rawId),
        authenticatorData: bufToB64url(assertion.authenticatorData),
        clientDataJson: bufToB64url(assertion.clientDataJSON),
        signature: bufToB64url(assertion.signature),
        signCount: extractSignCount(assertion.authenticatorData),
      })
      return 'ok'
    }
    catch (e) {
      if (import.meta.dev) console.warn('[passkey-reauth] failed', e)
      if (parseApiError(e).code === ERROR_UV_REQUIRED) return 'uv_unavailable'
      if (isUvUnsupported(e)) return 'uv_unavailable'
      if (e instanceof DOMException && e.name === 'NotAllowedError') return 'cancelled'
      return 'failed'
    }
  }

  return { reauthenticateWithPasskey }
}
