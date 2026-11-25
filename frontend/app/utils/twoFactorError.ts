// 2FA・本人確認系の API エラーを画面向けの i18n キー（settings.security.two_fa.error.*）へ写像する。

interface ApiErrorShape {
  statusCode?: number
  status?: number
  response?: { status?: number; headers?: { get?: (name: string) => string | null } }
  data?: { error?: { code?: string } }
}

/** エラーを i18n キー（settings.security.two_fa.error.*）と補間値へ写像した結果。 */
export interface TwoFactorErrorInfo {
  key: string
  params?: Record<string, unknown>
  code?: string
}

const ERROR_KEY_BY_CODE: Record<string, string> = {
  // TOTP 設定確定・ログイン検証で使用済みのコードを再提示した（誤ったコードは AUTH_074）
  AUTH_018: 'code_already_used',
  AUTH_074: 'invalid_code',
  AUTH_076: 'resend_wait',
  AUTH_077: 'send_limit',
  AUTH_078: 'already_enabled',
  AUTH_079: 'not_enabled',
  AUTH_080: 'reauth_failed',
  AUTH_081: 'email_missing',
  AUTH_082: 'email_unavailable',
}

/**
 * API エラーを表示用の i18n キーへ写像する。429 は Retry-After の秒数を補間値に含める。
 * 未知のエラーは握りつぶさず generic に倒す（呼び出し側が必ず利用者へ見せる）。
 */
export function describeTwoFactorError(e: unknown): TwoFactorErrorInfo {
  const err = (e ?? {}) as ApiErrorShape
  const status = err.response?.status ?? err.statusCode ?? err.status
  const code = err.data?.error?.code
  if (status === 429) {
    const raw = err.response?.headers?.get?.('Retry-After') ?? null
    const seconds = raw === null ? Number.NaN : Number.parseInt(raw, 10)
    return Number.isFinite(seconds) && seconds > 0
      ? { key: 'rate_limited', params: { seconds }, code }
      : { key: 'rate_limited_unknown', code }
  }
  const mapped = code ? ERROR_KEY_BY_CODE[code] : undefined
  return { key: mapped ?? 'generic', code }
}
