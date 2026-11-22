interface SlugHolder {
  id: number
  slug: string
}

/**
 * ブログ記事の公開・削除・承認後の遷移先を返す。
 *
 * チーム・組織ページは `/teams/{slug}` / `/organizations/{slug}` が前提で、数値 ID の URL は
 * 404（チームが見つかりません）になる。記事のスコープは数値 ID なので、マイチーム・マイ組織の
 * 一覧から slug を引く。slug を解決できない場合は、存在しない URL を作らず /dashboard に戻す。
 */
export function blogPublishRedirectPath(
  scopeType: string | null,
  scopeId: string | null,
  scopes: { teams: readonly SlugHolder[]; organizations: readonly SlugHolder[] },
): string {
  if (!scopeId) return '/dashboard'
  const id = Number(scopeId)
  if (scopeType === 'TEAM') {
    const team = scopes.teams.find(t => t.id === id)
    if (team?.slug) return `/teams/${team.slug}/blog`
  }
  if (scopeType === 'ORGANIZATION') {
    const org = scopes.organizations.find(o => o.id === id)
    if (org?.slug) return `/organizations/${org.slug}`
  }
  return '/dashboard'
}
