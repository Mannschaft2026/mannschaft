/**
 * 駐車場申請まわりの API エラーを、表示用 i18n キーへ解決する。
 *
 * BE の message は日本語固定なので、利用言語の文言を出すために errorCode / HTTP ステータスで
 * `parking.errors.*` へ引く。該当が無ければ null を返し、呼び出し側が共通の
 * useErrorHandler().handleApiError へ委ねる（握りつぶさない）。
 */

interface ApiErrorLike {
  statusCode?: number
  status?: number
  data?: { error?: { code?: string } }
}

const CODE_KEYS: Record<string, string> = {
  PARKING_001: 'parking.errors.spaceNotFound',
  PARKING_002: 'parking.errors.vehicleNotFound',
  PARKING_004: 'parking.errors.applicationNotFound',
  PARKING_011: 'parking.errors.spaceNotVacant',
  PARKING_012: 'parking.errors.maxSpacesExceeded',
  PARKING_013: 'parking.errors.notAccepting',
  PARKING_014: 'parking.errors.alreadyApplied',
  PARKING_015: 'parking.errors.invalidStatus',
  PARKING_021: 'parking.errors.plateDuplicate',
  COMMON_002: 'parking.errors.forbidden',
}

const STATUS_KEYS: Record<number, string> = {
  403: 'parking.errors.forbidden',
  404: 'parking.errors.notFound',
  409: 'parking.errors.conflict',
  422: 'parking.errors.unprocessable',
}

export function resolveParkingErrorKey(error: unknown): string | null {
  const e = error as ApiErrorLike | null
  const code = e?.data?.error?.code
  if (code && CODE_KEYS[code]) return CODE_KEYS[code]
  const status = e?.statusCode ?? e?.status
  if (status && STATUS_KEYS[status]) return STATUS_KEYS[status]
  return null
}

/** 状態が古くなった可能性のあるエラー（一覧を取り直すべきもの）。 */
export function isStaleStateError(error: unknown): boolean {
  const e = error as ApiErrorLike | null
  const status = e?.statusCode ?? e?.status
  return status === 404 || status === 409 || status === 422
}
