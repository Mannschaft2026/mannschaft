/**
 * F04.6 グローバル検索の型（BE `GET /api/v1/search` の応答形。camelCase・page は 0 始まり）。
 */
export type ContentType =
  | 'POST'
  | 'MESSAGE'
  | 'THREAD'
  | 'ARTICLE'
  | 'FILE'
  | 'USER'
  | 'TEAM'
  | 'ORGANIZATION'
  | 'ACTIVITY'
export type SearchAction = 'LIKE' | 'BOOKMARK' | 'MARK_READ' | 'DOWNLOAD' | 'SEND_DM'
export type SearchViewMode = 'OVERVIEW' | 'DETAIL'

/** タブ表示順（BE の SearchType 宣言順）。 */
export const CONTENT_TYPES: readonly ContentType[] = [
  'POST',
  'MESSAGE',
  'THREAD',
  'ARTICLE',
  'FILE',
  'USER',
  'TEAM',
  'ORGANIZATION',
  'ACTIVITY',
]

export interface SearchResult {
  type: ContentType
  id: number
  /** プレーンテキスト（`<mark>` なし）。題名を持たない種別は null */
  title: string | null
  /** HTML エスケープ済みで一致箇所だけ `<mark>` 付き。無い種別は null */
  snippet: string | null
  /** 例 `{ title: [start, end] }`（プレーンテキスト基準） */
  highlights: Record<string, [number, number]>
  relevance: number | null
  author: { id: number; displayName: string; avatarUrl: string | null } | null
  scope: { type: string; id: number; name: string } | null
  createdAt: string | null
  /** 遷移先の FE ルート。リンク不可は null */
  url: string | null
  actions: SearchAction[]
}

export interface SearchResponse {
  data: {
    results: SearchResult[]
    typeCounts: Record<ContentType, number>
    query: string
    timedOutTypes: ContentType[]
    zeroResultsHelp: {
      didYouMean: string | null
      broaderQuery: string | null
    } | null
  }
  meta: {
    viewMode: SearchViewMode
    total: number
    page: number
    size: number
    totalPages: number
  }
}

export interface Suggestion {
  text: string
  type: 'KEYWORD' | 'USER' | 'TEAM' | 'ORGANIZATION'
  userId: number | null
  teamId: number | null
  organizationId: number | null
  url: string | null
}

export interface SavedSearch {
  id: number
  name: string
  /** `GET /search` のパラメータを JSON 文字列で保存したもの */
  queryParams: string
  createdAt: string
}

export interface RecentSearch {
  id: number
  query: string
  searchedAt: string
}
