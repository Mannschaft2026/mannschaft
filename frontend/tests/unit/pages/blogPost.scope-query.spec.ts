// @vitest-environment nuxt
// 実閲覧page・実useBlogApi・実paywall APIのqueryを確認する。外部useApi transportだけを合成する。
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import BlogPostPage from '~/pages/blog/posts/[slug].vue'
import type { BlogPostResponse } from '~/types/cms'

const external = vi.hoisted(() => ({
 api: vi.fn<(path: string, options?: Record<string, unknown>) => Promise<unknown>>(),
 error: vi.fn(),
 report: vi.fn(),
}))
mockNuxtImport('useApi', () => () => external.api)
mockNuxtImport('useErrorHandler', () => () => ({ handleError: external.error }))
mockNuxtImport('useErrorReport', () => () => ({ capture: external.report, captureQuiet: external.report }))

const slug = 'synthetic-source-link'
const endpoint = `/api/v1/blog/posts/${slug}`
const post: BlogPostResponse = {
 id: 11,
 content: { title: 'Synthetic scoped article', slug, body: null, excerpt: null, coverImageUrl: null },
 author: { id: 12, displayName: 'Synthetic author', avatarUrl: null },
 tags: [], seriesId: null, seriesName: null, seriesOrder: null, scheduledAt: null,
}
const wrappers: VueWrapper[] = []
const stubs = {
 BackButton: true,
 PageLoading: true,
 DashboardEmptyState: true,
 TimelineMitayoButton: true,
 BlogSeriesNav: true,
 BlogRelatedPosts: true,
 BlogPostDetail: { props: ['post'], template: '<article>{{ post.content?.title }}</article>' },
 // 本試練はURL/query伝達だけ。F00/PaymentGate認可や本文マスクの成功とは数えない。
 PaywallLock: { template: '<div><slot /></div>' },
}
function calls(pathname: string) {
 return external.api.mock.calls.filter(([path]) => new URL(path, 'http://synthetic.invalid').pathname === pathname)
}
function postQuery() {
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
beforeEach(() => {
 external.api.mockReset()
 external.error.mockReset()
 external.report.mockReset()
 external.api.mockImplementation(async path => {
  const pathname = new URL(path, 'http://synthetic.invalid').pathname
  if (pathname === endpoint) return { data: post }
  if (pathname === '/api/v1/content-gates/check') return { data: { accessible: true, titleHidden: false, requiredItems: [] } }
  // Nuxtの既存background同期は試練対象のBlog要求へ数えない。
  return { data: [] }
 })
})
afterEach(async () => {
 for (const wrapper of wrappers.splice(0)) wrapper.unmount()
 await flushPromises()
})
async function mounted(query: string) {
 const wrapper = await mountSuspended(BlogPostPage, { route: `/blog/posts/${slug}${query}`, global: { stubs } })
 wrappers.push(wrapper)
 await flushPromises()
 return wrapper
}

describe('Blog source linkの正規scope query（先行試練、未実測）', () => {
 it.each([
  ['slug形teamId', '?teamId=synthetic-team', { teamId: 'synthetic-team' }],
  ['slug形organizationId', '?organizationId=synthetic-org', { organizationId: 'synthetic-org' }],
 ])('C10: %sを弾かずBEへ渡す（CMP-261008-1701）', async (_name, query, expected) => {
  const wrapper = await mounted(query)
  await vi.waitFor(() => {
   expect(calls(endpoint)).toHaveLength(1)
   expect(postQuery()).toEqual(expected)
   expect(wrapper.text()).toContain(post.content?.title)
  })
  expect(external.error).not.toHaveBeenCalled()
 })

 it.each([
  { name: '既GLOBAL slug', query: '', expected: {} },
  { name: 'TEAM currentmeta', query: '?teamId=41', expected: { teamId: '41' } },
  { name: 'ORGANIZATION currentmeta', query: '?organizationId=42', expected: { organizationId: '42' } },
  { name: '既user scopeとopaque preview', query: '?userId=43&previewToken=opaque%2Bsynthetic%2Ftoken', expected: { userId: '43', previewToken: 'opaque+synthetic/token' } },
  { name: 'Long最大IDをNumberへ変換しない', query: '?teamId=9223372036854775807', expected: { teamId: '9223372036854775807' } },
  { name: '無関係queryをAPIへ転送しない', query: '?utm_source=synthetic&actorId=99', expected: {} },
 ])('$nameを正規APIへ一回渡し既paywall確認を保持する', async ({ query, expected }) => {
  const wrapper = await mounted(query)
  await vi.waitFor(() => {
   expect(calls(endpoint)).toHaveLength(1)
   expect(postQuery()).toEqual(expected)
   expect(calls('/api/v1/content-gates/check')).toHaveLength(1)
   expect(wrapper.text()).toContain(post.content?.title)
  })
  expect(external.error).not.toHaveBeenCalled()
 })

 it.each([
  ['TEAMとORGの併記', '?teamId=41&organizationId=42'],
  ['TEAMとuserの併記', '?teamId=41&userId=43'],
  ['ORGとuserの併記', '?organizationId=42&userId=43'],
  // CMP-261008-1701 C10: teamId/organizationId は slug・数値の双方を BE が解決する（BlogScopeAccessGuard）。
  // FE は数値以外を弾かない（0・負数・指数・小数・Long 範囲超過の判定は BE が CMS_001 に畳む）。
  ['先行0', '?userId=01'],
  ['空', '?teamId='],
  ['空白', '?userId=%20'],
  ['scope繰返しarray', '?teamId=41&teamId=42'],
  ['preview繰返しarray', '?previewToken=one&previewToken=two'],
 ])('%sはHTTP前に拒否しGLOBALへ読み替えない', async (_name, query) => {
  const wrapper = await mounted(query)
  await vi.waitFor(() => {
   expect(calls(endpoint)).toHaveLength(0)
   expect(calls('/api/v1/content-gates/check')).toHaveLength(0)
   // CMP-261008-1701 C11: 不正 query は CMS_001 と同じ不在表示にし、code を持たない汎用（error.unknown）トーストにしない。
   for (const [error] of external.error.mock.calls) {
    expect((error as { data?: { error?: { code?: unknown } } } | undefined)?.data?.error?.code).toBe('CMS_001')
   }
   expect(wrapper.text()).not.toContain(post.content?.title)
  })
 })
})
