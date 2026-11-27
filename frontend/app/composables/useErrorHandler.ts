import { resolveApiErrorMessage } from '~/utils/resolveApiErrorMessage'

// サーバーのフィールドエラーメッセージ（英語）→ i18n バリデーションキーのマッピング
const FIELD_ERROR_PATTERNS: Array<{ pattern: RegExp; key: string }> = [
  { pattern: /required|must not be (blank|empty|null)/i, key: 'required' },
  { pattern: /email|must be a valid email/i, key: 'email' },
  { pattern: /too short|at least (\d+) char/i, key: 'min_length' },
  { pattern: /too long|at most (\d+) char|maximum (\d+)/i, key: 'max_length' },
  // AUTH_072: 郵便番号フォーマット不正（BE エラーメッセージに "postal" を含む場合に解決）
  { pattern: /postal.*format|format.*postal/i, key: 'postal_code_format' },
  // AUTH_071: 郵便番号必須（BE エラーメッセージに "postal" + "required" を含む場合）
  { pattern: /postal.*required|required.*postal/i, key: 'postal_code_required' },
  { pattern: /invalid (format|value)|not valid/i, key: 'invalid_format' },
  { pattern: /do not match|does not match/i, key: 'password_mismatch' },
]

export const useErrorHandler = () => {
  const notification = useNotification()
  const { t, te } = useI18n()
  const errorReport = useErrorReport()

  // #2426: BE（CommonErrorCode）が理由入りの具体的な message を返している場合、
  // 汎用 i18n キー（例: error.COMMON_001）で上書きして理由を握りつぶさない。
  // BE message を最優先し、無ければ従来どおり i18n キー→汎用文言にフォールバックする。
  const resolveMessage = (code: string, fallback?: string, context?: string): string =>
    resolveApiErrorMessage({ t, te }, code, fallback, context)

  const handleApiError = (error: unknown, context?: string): void => {
    const apiError = error as {
      data?: { error?: { code?: string; message?: string } }
      statusCode?: number
    }

    // バックエンドへ静かに送信（4xx含む全エラーを記録）
    const reportedError = context === 'team-loyalty'
      ? (() => {
          // QR を含み得る FetchError の message/body/cause を報告基盤へ渡さない。
          const rawCode = apiError?.data?.error?.code
          const code = typeof rawCode === 'string'
            && /^(LOYALTY_00[1-9]|LOYALTY_01[0-5]|COMMON_003|MEMBERSHIP_003)$/.test(rawCode)
            ? rawCode : 'UNKNOWN'
          const rawStatus = apiError?.statusCode
          const status = typeof rawStatus === 'number' && Number.isInteger(rawStatus)
            && rawStatus >= 100 && rawStatus <= 599 ? rawStatus : 0
          return new Error(`Team loyalty API error (${code}; HTTP ${status})`)
        })()
      : error
    errorReport.captureQuiet(reportedError, { context })

    // CMP-261125-1951: 5xx のエラートーストは useApi の onResponseError が一元的に出す（業務コードの文言を含む）。
    // ここでも出すと 1 操作で 2 枚になるため、5xx はレポートだけ残して通知しない。
    const rawApiError = error as { statusCode?: number; status?: number; response?: { status?: number } } | null
    const httpStatus = rawApiError?.statusCode ?? rawApiError?.status ?? rawApiError?.response?.status
    if (typeof httpStatus === 'number' && httpStatus >= 500) {
      return
    }

    // F20.1: ENTITLEMENT_003（402）は useApi の共通ハンドラが既にグローバルペイウォール
    // モーダルを開いている（usePaywallStore）。ここで追加のトーストを出すと二重表示になるため
    // 明示的にスキップする（設計書 04 §2）。
    if (apiError?.data?.error?.code === 'ENTITLEMENT_003') {
      return
    }

    if (apiError?.data?.error?.code) {
      const message = resolveMessage(apiError.data.error.code, apiError.data.error.message, context)
      notification.error(t('dialog.error'), message)
      return
    }

    notification.error(t('dialog.error'), t('error.unknown'))
  }

  const getFieldErrors = (error: unknown): Record<string, string> => {
    const apiError = error as {
      data?: { error?: { fieldErrors?: Array<{ field: string; message: string }> } }
    }
    const fieldErrors: Record<string, string> = {}
    if (apiError?.data?.error?.fieldErrors) {
      for (const fe of apiError.data.error.fieldErrors) {
        const matched = FIELD_ERROR_PATTERNS.find((p) => p.pattern.test(fe.message))
        fieldErrors[fe.field] = matched ? t(matched.key) : t('invalid_format')
      }
    }
    return fieldErrors
  }

  // エイリアス（handleError 形式でも使えるようにする）
  const handleError = handleApiError

  return {
    resolveMessage,
    handleApiError,
    handleError,
    getFieldErrors,
  }
}
