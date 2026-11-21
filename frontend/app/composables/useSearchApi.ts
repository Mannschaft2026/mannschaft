import type { SearchResponse, Suggestion, SavedSearch, RecentSearch, ContentType } from '~/types/search'

export interface GlobalSearchParams {
  q: string
  /** 単一指定で詳細ビュー、複数指定・未指定で概要ビュー */
  types?: ContentType[]
  scopeType?: 'TEAM' | 'ORGANIZATION'
  scopeId?: number
  dateFrom?: string
  dateTo?: string
  authorId?: number
  /** 0 始まり（詳細ビューのみ有効） */
  page?: number
  /** 1〜50（詳細ビューのみ有効） */
  size?: number
}

/** `GET /api/v1/search` のクエリ文字列を組み立てる（BE の camelCase パラメータ名に合わせる）。 */
export function buildSearchQuery(params: GlobalSearchParams): string {
  const query = new URLSearchParams()
  query.set('q', params.q)
  if (params.types && params.types.length > 0) query.set('type', params.types.join(','))
  if (params.scopeType) query.set('scopeType', params.scopeType)
  if (params.scopeId != null) query.set('scopeId', String(params.scopeId))
  if (params.dateFrom) query.set('dateFrom', params.dateFrom)
  if (params.dateTo) query.set('dateTo', params.dateTo)
  if (params.authorId != null) query.set('authorId', String(params.authorId))
  if (params.page != null) query.set('page', String(params.page))
  if (params.size != null) query.set('size', String(params.size))
  return query.toString()
}

export function useSearchApi() {
  const api = useApi()

  async function search(params: GlobalSearchParams) {
    return await api<SearchResponse>(`/api/v1/search?${buildSearchQuery(params)}`)
  }

  async function suggestions(q: string, limit?: number) {
    const query = new URLSearchParams({ q })
    if (limit != null) query.set('limit', String(limit))
    const res = await api<{ data: { suggestions: Suggestion[] } }>(
      `/api/v1/search/suggestions?${query.toString()}`,
    )
    return res.data.suggestions
  }

  async function listRecent() {
    const res = await api<{ data: RecentSearch[] }>('/api/v1/search/recent')
    return res.data
  }

  async function clearRecent() {
    await api('/api/v1/search/recent', { method: 'DELETE' })
  }

  async function deleteRecent(id: number) {
    await api(`/api/v1/search/recent/${id}`, { method: 'DELETE' })
  }

  async function listSaved() {
    const res = await api<{ data: SavedSearch[] }>('/api/v1/search/saved')
    return res.data
  }

  async function saveSearch(name: string, params: GlobalSearchParams) {
    const res = await api<{ data: SavedSearch }>('/api/v1/search/saved', {
      method: 'POST',
      body: { name, queryParams: JSON.stringify(params) },
    })
    return res.data
  }

  async function deleteSaved(id: number) {
    await api(`/api/v1/search/saved/${id}`, { method: 'DELETE' })
  }

  return { search, suggestions, listRecent, clearRecent, deleteRecent, listSaved, saveSearch, deleteSaved }
}
