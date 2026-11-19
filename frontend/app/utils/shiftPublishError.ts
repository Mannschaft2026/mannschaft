/**
 * シフト公開（PUBLISHED への遷移）が拒否されたときのエラー解釈（CMP-261115-1821）。
 *
 * - 400 / SHIFT_047: 予算連動の勤務日に、有効な正数時給を持たない割当者がいる。
 *   `error.details.missingHourlyRates` に（userId, slotDate）の一覧が入る。
 * - 409 / COMMON_003: 公開の検証から確定までの間に割当・勤務日が変わった。読み込み直して再操作する。
 *
 * 上記以外は null を返し、呼び出し側が通常のエラー処理（handleApiError）へ回す。
 */

/** 時給を確認できなかった割当者と勤務日。 */
export interface MissingHourlyRate {
  userId: number
  slotDate: string
}

export type ShiftPublishErrorInfo =
  | { kind: 'hourlyRateMissing'; missing: MissingHourlyRate[] }
  | { kind: 'conflict' }

interface ApiErrorShape {
  statusCode?: number
  data?: {
    error?: {
      code?: string
      details?: { missingHourlyRates?: unknown }
    }
  }
}

function isMissingHourlyRate(value: unknown): value is MissingHourlyRate {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  return typeof v.userId === 'number' && typeof v.slotDate === 'string'
}

export function parseShiftPublishError(error: unknown): ShiftPublishErrorInfo | null {
  const apiError = error as ApiErrorShape | null | undefined
  const code = apiError?.data?.error?.code
  if (apiError?.statusCode === 400 && code === 'SHIFT_047') {
    const raw = apiError.data?.error?.details?.missingHourlyRates
    const missing = Array.isArray(raw) ? raw.filter(isMissingHourlyRate) : []
    // 一覧が読めない応答でも握りつぶさず、空一覧のまま時給ガードとして扱う（画面側で理由文を出す）。
    return { kind: 'hourlyRateMissing', missing }
  }
  if (apiError?.statusCode === 409 && code === 'COMMON_003') {
    return { kind: 'conflict' }
  }
  return null
}

/** 対象者ごとに勤務日を重複なく昇順でまとめる（対象者は userId 昇順）。 */
export function groupMissingByUser(
  missing: MissingHourlyRate[],
): Array<{ userId: number; dates: string[] }> {
  const map = new Map<number, Set<string>>()
  for (const m of missing) {
    const set = map.get(m.userId) ?? new Set<string>()
    set.add(m.slotDate)
    map.set(m.userId, set)
  }
  return [...map.entries()]
    .sort(([a], [b]) => a - b)
    .map(([userId, dates]) => ({ userId, dates: [...dates].sort() }))
}
