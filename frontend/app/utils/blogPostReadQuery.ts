export interface BlogPostReadQuery {
 teamId?: string
 organizationId?: string
 userId?: string
 previewToken?: string
}

/** FE 合成の不正 query エラー識別子。BE 起因ではないため useErrorHandler は障害報告の対象から外す。 */
export const BLOG_READ_QUERY_INVALID = 'BLOG_READ_QUERY_INVALID'

/**
 * BE の不在応答（404 CMS_001）と同形のエラー。不正 query も不在と同じ表示・トースト種別に揃える（CMP-261008-1701 C11）。
 * 文言は直書きせず、useErrorHandler が code(CMS_001) から全ロケール共通の error.CMS_001 で解決する。
 */
export function createBlogPostNotFoundError(): Error & { statusCode: number; data: { error: { code: string; message: string; fieldErrors: never[] } } } {
 return Object.assign(new Error(BLOG_READ_QUERY_INVALID), {
  statusCode: 404,
  data: { error: { code: 'CMS_001', message: '', fieldErrors: [] as never[] } },
 })
}

/** 個人ブログの userId（Long 正準文字列）。不正形なら null。 */
export function parseBlogUserId(value: unknown): string | null {
 return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n ? value : null
}

/**
 * 既slug閲覧のquery境界。teamId/organizationId は数値IDでも slug でも BE が解決するため
 * 「空でない単一文字列」なら通し、権限・実在はBEが再判定する。userId は数値のみ。
 * 不正形（複数scope・空値・配列等）は不在と同じ CMS_001 形のエラーを投げる。
 */
export function parseBlogPostReadQuery(query: Readonly<Record<string, unknown>>): BlogPostReadQuery {
 const result: BlogPostReadQuery = {}
 let selected = 0
 for (const field of ['teamId', 'organizationId', 'userId'] as const) {
  const value = query[field]
  if (value === undefined) continue
  selected += 1
  if (selected > 1 || typeof value !== 'string' || value.length === 0
      || (field === 'userId' && parseBlogUserId(value) === null)) throw createBlogPostNotFoundError()
  result[field] = value
 }
 const previewToken = query.previewToken
 if (previewToken !== undefined) {
  if (typeof previewToken !== 'string' || previewToken.length === 0) throw createBlogPostNotFoundError()
  result.previewToken = previewToken
 }
 // tracking等の未知queryをAPIのactor/scope指定へ転送しない。
 return result
}
