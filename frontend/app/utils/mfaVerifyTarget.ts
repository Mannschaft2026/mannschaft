import type { MfaChallengePayload } from '~/types/auth'

/**
 * 2FA 検証画面（/2fa-verify）への遷移先 URL を組み立てる（CMP-261012-0911）。
 *
 * パスワードログイン（login.vue）と OAuth コールバック（auth/oauth/callback.vue）で同じ形にするための共通関数。
 * 方式情報（primary / methods）が欠落していても session だけで遷移でき、2fa-verify 側が TOTP にフォールバックする。
 *
 * @param challenge MFA 要求レスポンス（mfaSessionToken・primaryMethod・availableMethods）
 * @param redirect  ログイン後の遷移先指定。2FA 通過後まで保持する（文字列以外は無視）
 */
export function buildMfaVerifyTarget(challenge: MfaChallengePayload, redirect?: unknown): string {
  const query = new URLSearchParams({ session: challenge.mfaSessionToken ?? '' })
  if (challenge.primaryMethod) query.set('primary', challenge.primaryMethod)
  if (challenge.availableMethods?.length) {
    query.set('methods', challenge.availableMethods.join(','))
  }
  if (typeof redirect === 'string') query.set('redirect', redirect)
  return `/2fa-verify?${query.toString()}`
}
