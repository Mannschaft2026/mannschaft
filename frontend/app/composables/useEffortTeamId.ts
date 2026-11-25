interface MyTeamItem {
  id: number
  slug: string
}

/**
 * チームの slug から数値 teamId を解く（F06.4.1 §2.3）。
 *
 * <p>既存の useActivityScopeId と違い、取得の失敗（通信障害・5xx）を null に潰さず例外として伝える。
 * null を返すのは、取得に成功し、かつ該当チームが所属一覧に無いときだけ（呼び出し側は 404 を出す）。</p>
 */
export function useEffortTeamId() {
  const api = useApi()

  async function resolveTeamId(slug: string): Promise<number | null> {
    const numeric = Number(slug)
    if (Number.isInteger(numeric) && numeric > 0 && String(numeric) === slug) return numeric
    const res = await api<{ data: MyTeamItem[] }>('/api/v1/me/teams')
    const team = (res.data ?? []).find((tm) => tm.slug === slug)
    return team ? team.id : null
  }

  return { resolveTeamId }
}
