/** ブログ記事の公開・削除・承認後の遷移先を決めるための、記事のスコープ情報。 */
export interface BlogPublishRedirectScope {
  /** 'TEAM' | 'ORGANIZATION'、個人記事は null。 */
  scopeType: string | null
  /** 記事応答の scope.teamSlug（BE が記事のスコープから付与する）。 */
  teamSlug: string | null
  /** 記事応答の scope.organizationSlug（BE が記事のスコープから付与する）。 */
  organizationSlug: string | null
}

const FALLBACK_PATH = '/dashboard'

/**
 * ブログ記事の公開・削除・承認後の遷移先を返す（純粋関数）。
 *
 * チーム・組織ページは `/teams/{slug}` / `/organizations/{slug}` が前提で、数値 ID の URL は
 * 404（チームが見つかりません）になる。slug は記事応答の `scope.teamSlug` / `organizationSlug` を受け取る。
 * BE は記事のスコープそのものの slug を返すため、チーム・組織の記事で slug が無いのは BE の契約違反
 * （またはスコープが論理削除済み）であり、存在しない URL を作らずに痕跡を残して /dashboard へ戻す。
 * 個人記事は /dashboard。
 */
export function blogPublishRedirectPath(scope: BlogPublishRedirectScope): string {
  const { scopeType, teamSlug, organizationSlug } = scope
  if (scopeType === null) return FALLBACK_PATH
  if (scopeType === 'TEAM') {
    if (teamSlug) return `/teams/${encodeURIComponent(teamSlug)}/blog`
    console.error('[blog] チーム記事の応答に scope.teamSlug がありません（BE の契約違反）。/dashboard へ遷移します', scope)
    return FALLBACK_PATH
  }
  if (scopeType === 'ORGANIZATION') {
    if (organizationSlug) return `/organizations/${encodeURIComponent(organizationSlug)}`
    console.error('[blog] 組織記事の応答に scope.organizationSlug がありません（BE の契約違反）。/dashboard へ遷移します', scope)
    return FALLBACK_PATH
  }
  console.error('[blog] 不明な記事スコープです。/dashboard へ遷移します', scope)
  return FALLBACK_PATH
}
