/**
 * チーム詳細（GET /teams/{slug}）の取得失敗を、画面の出し分け用に分類する（CMP-261124-0159 PR-F1）。
 *
 * - forbidden: 403（メンバー限定のチームなど、公開範囲の外）
 * - notFound : 404（slug 移転の解決が不成立なら「見つかりません」）
 * - other    : それ以外（5xx・通信失敗）。既存のエラー表示・トーストに任せる
 */
export type TeamAccessFailure = 'forbidden' | 'notFound' | 'other'

export function classifyTeamAccessFailure(error: unknown): TeamAccessFailure {
  const e = error as { response?: { status?: number }, statusCode?: number, status?: number } | null | undefined
  const status = e?.response?.status ?? e?.statusCode ?? e?.status
  if (status === 403) return 'forbidden'
  if (status === 404) return 'notFound'
  return 'other'
}
