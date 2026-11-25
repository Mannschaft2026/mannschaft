/**
 * 予算警告の確認（acknowledge）が「確認済み」で拒否されたときのエラー解釈（CMP-260910-1607 ③）。
 *
 * - 409 / SHIFT_BUDGET_032: 既に誰かが確認済み。`error.details` に既存の確認者（ID・氏名）と確認時刻が入る。
 *
 * 上記以外は null を返し、呼び出し側が通常のエラー処理へ回す（握りつぶさない）。
 */

export interface AlertAlreadyAcknowledgedInfo {
  acknowledgedBy: number | null
  /** 確認者の氏名。BE が引けなかった場合は null（画面側で代替表示する）。 */
  acknowledgedByName: string | null
  /** 確認時刻（ISO のローカル日時文字列）。 */
  acknowledgedAt: string | null
}

interface ApiErrorShape {
  statusCode?: number
  status?: number
  data?: {
    error?: {
      code?: string
      details?: {
        acknowledgedBy?: unknown
        acknowledgedByName?: unknown
        acknowledgedAt?: unknown
      }
    }
  }
}

export const ALERT_ALREADY_ACKNOWLEDGED_CODE = 'SHIFT_BUDGET_032'

export function parseAlertAlreadyAcknowledged(error: unknown): AlertAlreadyAcknowledgedInfo | null {
  const apiError = error as ApiErrorShape | null | undefined
  const status = apiError?.statusCode ?? apiError?.status
  const code = apiError?.data?.error?.code
  if (status !== 409 || code !== ALERT_ALREADY_ACKNOWLEDGED_CODE) return null
  const d = apiError?.data?.error?.details
  return {
    acknowledgedBy: typeof d?.acknowledgedBy === 'number' ? d.acknowledgedBy : null,
    acknowledgedByName:
      typeof d?.acknowledgedByName === 'string' && d.acknowledgedByName ? d.acknowledgedByName : null,
    acknowledgedAt: typeof d?.acknowledgedAt === 'string' ? d.acknowledgedAt : null,
  }
}
