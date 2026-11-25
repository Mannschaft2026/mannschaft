// @vitest-environment nuxt
// CMP-261008-1701 戦役B「ブログ記事詳細の存在オラクル」FE 受け入れ試練（C10/C11）。
// 実ページ・実 useBlogApi・実 parseBlogPostReadQuery を通し、外部 useApi transport と useErrorHandler だけを合成する。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import BlogPostPage from '~/pages/blog/posts/[slug].vue'
import UserBlogPostPage from '~/pages/users/[userId]/blog/posts/[slug].vue'
import { parseBlogPostReadQuery } from '~/utils/blogPostReadQuery'
import type { BlogPostResponse } from '~/types/cms'
import jaCommon from '~/locales/ja/common.json'
import enCommon from '~/locales/en/common.json'
import zhCommon from '~/locales/zh/common.json'
import koCommon from '~/locales/ko/common.json'
import esCommon from '~/locales/es/common.json'
import deCommon from '~/locales/de/common.json'

const external = vi.hoisted(() => ({
 api: vi.fn<(path: string, options?: Record<string, unknown>) => Promise<unknown>>(),
 error: vi.fn(),
 report: vi.fn(),
}))
mockNuxtImport('useApi', () => () => external.api)
mockNuxtImport('useErrorHandler', () => () => ({ handleError: external.error }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))

const slug = 'synthetic-oracle-post'
const endpoint = `/api/v1/blog/posts/${slug}`
const userEndpoint = `/api/v1/users/43/blog/posts/${slug}`
const post: BlogPostResponse = {
 id: 11,
 content: { title: 'Synthetic oracle article', slug, body: null, excerpt: null, coverImageUrl: null },
 author: { id: 12, displayName: 'Synthetic author', avatarUrl: null },
 tags: [], seriesId: null, seriesName: null, seriesOrder: null, scheduledAt: null,
}

/** BE の不在応答（ofetch の FetchError と同形）。閲覧不可も CMS_001 に畳まれて同じ形で届く。 */
function cms001(): Error & { statusCode: number; data: unknown } {
 return Object.assign(new Error('[GET] 404'), {
  statusCode: 404,
  status: 404,
  data: { error: { code: 'CMS_001', message: '記事が見つかりません', fieldErrors: [] } },
 })
}

const wrappers: VueWrapper[] = []
const stubs = {
 BackButton: true,
 PageLoading: true,
 // 不在表示の文言を比較するため、message を描画する stub にする。
 DashboardEmptyState: { props: ['message', 'icon'], template: '<p data-testid="empty-state">{{ message }}</p>' },
 TimelineMitayoButton: true,
 BlogSeriesNav: true,
 BlogRelatedPosts: true,
 BlogPostDetail: { props: ['post'], template: '<article>{{ post.content?.title }}</article>' },
 PaywallLock: { template: '<div><slot /></div>' },
}

function calls(pathname: string) {
 return external.api.mock.calls.filter(([path]) => new URL(path, 'http://synthetic.invalid').pathname === pathname)
}
function sentQuery() {
 const request = calls(endpoint)[0]
 if (!request) throw new Error('Expected synthetic blog request')
 const [path, options] = request
 const url = new URL(path, 'http://synthetic.invalid')
 const query = options?.query
 if (query !== null && typeof query === 'object' && !Array.isArray(query)) {
  for (const [key, value] of Object.entries(query)) {
   if (value !== undefined && value !== null) url.searchParams.set(key, String(value))
  }
 }
 return Object.fromEntries(url.searchParams.entries())
}

/**
 * handleError に渡されたエラーの「トースト種別」。code を持たないエラーは useErrorHandler が
 * 汎用の error.unknown トーストにするため 'UNKNOWN' とする。
 */
function toastKinds(): string[] {
 return external.error.mock.calls.map(([error]) => {
  const code = (error as { data?: { error?: { code?: unknown } } } | undefined)?.data?.error?.code
  return typeof code === 'string' ? code : 'UNKNOWN'
 })
}

type Mode = 'found' | 'cms001'
let mode: Mode = 'found'

beforeEach(() => {
 mode = 'found'
 external.api.mockReset()
 external.error.mockReset()
 external.report.mockReset()
 external.api.mockImplementation(async path => {
  const pathname = new URL(path, 'http://synthetic.invalid').pathname
  if (pathname === endpoint || pathname === userEndpoint || /^\/api\/v1\/users\/[^/]+\/blog\/posts\//.test(pathname)) {
   if (mode === 'cms001') throw cms001()
   return { data: post }
  }
  if (pathname === '/api/v1/content-gates/check') return { data: { accessible: true, titleHidden: false, requiredItems: [] } }
  // Nuxt の既存 background 同期は試練対象の Blog 要求へ数えない。
  return { data: [] }
 })
})
afterEach(async () => {
 for (const wrapper of wrappers.splice(0)) wrapper.unmount()
 await flushPromises()
})

async function mountBlog(query: string) {
 const wrapper = await mountSuspended(BlogPostPage, { route: `/blog/posts/${slug}${query}`, global: { stubs } })
 wrappers.push(wrapper)
 await flushPromises()
 return wrapper
}
async function mountUserBlog(userId: string) {
 const wrapper = await mountSuspended(UserBlogPostPage, {
  route: `/users/${userId}/blog/posts/${slug}`, global: { stubs },
 })
 wrappers.push(wrapper)
 await flushPromises()
 return wrapper
}

/** 不在表示の観測値（表示文言・記事本文の有無・トースト種別）。 */
async function observe(wrapper: VueWrapper) {
 let message = ''
 await vi.waitFor(() => {
  const empty = wrapper.find('[data-testid="empty-state"]')
  expect(empty.exists()).toBe(true)
  message = empty.text()
 })
 return { message, showsArticle: wrapper.text().includes(post.content?.title ?? '\u0000'), toasts: toastKinds() }
}

const LOCALES = { ja: jaCommon, en: enCommon, zh: zhCommon, ko: koCommon, es: esCommon, de: deCommon }
function lookup(dict: unknown, key: string): unknown {
 return key.split('.').reduce<unknown>((node, part) =>
  node !== null && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined, dict)
}
function localeValues(key: string): unknown[] {
 return Object.values(LOCALES).map(dict => lookup(dict, key)).filter(value => typeof value === 'string')
}

// ═══════════════════════════════════════════════════════════════════════
// C10: slug 形の teamId / organizationId は FE で弾かず BE へ渡す
// ═══════════════════════════════════════════════════════════════════════

describe('C10 parseBlogPostReadQuery: slug 形のスコープは BE へ渡し、不正形だけを弾く（CMP-261008-1701）', () => {
 it.each([
  ['C10: slug 形の teamId', { teamId: 'my-team' }],
  ['C10: slug 形の organizationId', { organizationId: 'my-org' }],
  ['C10: 数字始まりの slug 形 teamId', { teamId: 'team-2026' }],
  ['C10: 英字だけの slug 形 organizationId', { organizationId: 'acme' }],
  ['C10: 数値の teamId（従来どおり）', { teamId: '41' }],
 ])('%s はそのまま返す（throw しない）', (_name, query) => {
  expect(parseBlogPostReadQuery(query)).toEqual(query)
 })

 it.each([
  ['C10: チームと組織の同時指定', { teamId: 'my-team', organizationId: 'my-org' }],
  ['C10: チームと個人の同時指定', { teamId: 'my-team', userId: '43' }],
  ['C10: 空の teamId', { teamId: '' }],
  ['C10: 空の previewToken', { teamId: 'my-team', previewToken: '' }],
  ['C10: 繰り返しの teamId（配列）', { teamId: ['a-team', 'b-team'] }],
 ])('%s は不正形として拒否する', (_name, query) => {
  expect(() => parseBlogPostReadQuery(query)).toThrow()
 })
})

describe('C10 ブログ記事詳細ページ: slug 形の teamId/organizationId を BE へ渡し、閲覧できれば表示する', () => {
 it.each([
  ['slug 形 teamId', '?teamId=my-team', { teamId: 'my-team' }],
  ['slug 形 organizationId', '?organizationId=my-org', { organizationId: 'my-org' }],
 ])('C10: %s で BE を1回呼び、200 なら記事を表示する', async (_name, query, expected) => {
  const wrapper = await mountBlog(query)
  await vi.waitFor(() => {
   expect(calls(endpoint)).toHaveLength(1)
   expect(sentQuery()).toEqual(expected)
   expect(wrapper.text()).toContain(post.content?.title)
  })
  expect(external.error).not.toHaveBeenCalled()
 })
})

// ═══════════════════════════════════════════════════════════════════════
// C11: CMS_001 と不正 query は同じ不在表示・汎用トーストにならない
// ═══════════════════════════════════════════════════════════════════════

describe('C11 不在表示の i18n キー（6言語）', () => {
 it('C11: blog.post.notFound が 6言語の common.json に、その言語の訳で入っている', () => {
  for (const [locale, dict] of Object.entries(LOCALES)) {
   const value = lookup(dict, 'blog.post.notFound')
   expect(typeof value, `${locale}: blog.post.notFound`).toBe('string')
   expect((value as string).length, `${locale}: blog.post.notFound`).toBeGreaterThan(0)
  }
  // 「記事がありません（noPost）」は一覧の空表示の文言で、不在とは意味が違う。
  expect(lookup(jaCommon, 'blog.post.notFound')).not.toBe(lookup(jaCommon, 'blog.post.noPost'))
 })
})

describe('C11 ブログ記事詳細ページ（blog/posts/[slug]）: CMS_001 と不正 query が同じ不在表示', () => {
 it.each([
  ['チームと組織の同時指定', '?teamId=my-team&organizationId=my-org'],
  ['空の teamId', '?teamId='],
  ['空の previewToken', '?teamId=my-team&previewToken='],
  ['繰り返しの teamId', '?teamId=a-team&teamId=b-team'],
 ])('C11: 不正 query（%s）は BE の CMS_001 と同じ不在表示で、error.unknown トーストにならない', async (_name, query) => {
  mode = 'cms001'
  const absent = await observe(await mountBlog('?teamId=my-team'))
  external.error.mockReset()

  mode = 'found'
  external.api.mockClear()
  const invalid = await observe(await mountBlog(query))

  // 不正形は HTTP 前に不在扱いにする（GLOBAL 等へ読み替えて BE へ送らない）。
  expect(calls(endpoint)).toHaveLength(0)
  expect(absent.showsArticle).toBe(false)
  expect(invalid.showsArticle).toBe(false)
  expect(absent.toasts).not.toContain('UNKNOWN')
  expect(invalid.toasts).not.toContain('UNKNOWN')
  expect(invalid.message).toBe(absent.message)
  expect(invalid.toasts).toEqual(absent.toasts)
 })

 it('C11: CMS_001 の不在表示は blog.post.notFound の文言（一覧の noPost ではない）', async () => {
  mode = 'cms001'
  const absent = await observe(await mountBlog('?teamId=my-team'))

  expect(absent.message).not.toBe('')
  // 試験環境の表示ロケールに依らず判定する（どの言語でも noPost ではなく notFound の訳であること）。
  expect(localeValues('blog.post.noPost')).not.toContain(absent.message)
  expect(localeValues('blog.post.notFound')).toContain(absent.message)
 })
})

describe('C11 個人ブログ記事詳細ページ（users/[userId]/blog/posts/[slug]）: CMS_001 と不正 userId が同じ不在表示', () => {
 it.each([
  ['数値でない userId', 'not-a-number'],
  ['小数の userId', '1.5'],
 ])('C11: 不正形（%s）は BE の CMS_001 と同じ不在表示で、error.unknown トーストにならない', async (_name, userId) => {
  mode = 'cms001'
  const absent = await observe(await mountUserBlog('43'))
  external.error.mockReset()

  mode = 'found'
  external.api.mockClear()
  const invalid = await observe(await mountUserBlog(userId))

  // 不正形の userId を NaN 等へ化けさせて BE へ送らない（送ったうえで記事を表示しない）。
  expect(external.api.mock.calls.some(([path]) => /\/api\/v1\/users\/NaN\//.test(path))).toBe(false)
  expect(absent.showsArticle).toBe(false)
  expect(invalid.showsArticle).toBe(false)
  expect(absent.toasts).not.toContain('UNKNOWN')
  expect(invalid.toasts).not.toContain('UNKNOWN')
  expect(invalid.message).toBe(absent.message)
  expect(invalid.toasts).toEqual(absent.toasts)
 })

 it('C11: CMS_001 の不在表示は blog.post.notFound の文言（一覧の noPost ではない）', async () => {
  mode = 'cms001'
  const absent = await observe(await mountUserBlog('43'))

  expect(absent.message).not.toBe('')
  // 試験環境の表示ロケールに依らず判定する（どの言語でも noPost ではなく notFound の訳であること）。
  expect(localeValues('blog.post.noPost')).not.toContain(absent.message)
  expect(localeValues('blog.post.notFound')).toContain(absent.message)
 })
})
