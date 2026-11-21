import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import SearchPage from '~/pages/search/index.vue'
import type { SearchResponse } from '~/types/search'

/**
 * F04.6 /search の URL 同期（CMP-261116-0722 PR-8 / AC-E4）。
 *   RS-01 /search 表示中にヘッダー検索欄が q・type を変えても、画面がその URL の内容で再検索する
 *   RS-02 画面自身の router.replace による URL 更新では二重検索しない
 */

const search = vi.fn()
const route = reactive({ params: {}, query: {} as Record<string, string> })

vi.mock('~/composables/useSearchApi', () => ({ useSearchApi: () => ({ search }) }))
mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useNotification', () => () => ({
  error: vi.fn(),
  warn: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
}))

const EMPTY: SearchResponse = {
  data: {
    results: [],
    typeCounts: { POST: 0, MESSAGE: 0, THREAD: 0, ARTICLE: 0, FILE: 0, USER: 0, TEAM: 0, ORGANIZATION: 0, ACTIVITY: 0 },
    query: 'x',
    timedOutTypes: [],
    zeroResultsHelp: null,
  },
  meta: { viewMode: 'OVERVIEW', total: 0, page: 0, size: 5, totalPages: 0 },
}

describe('pages/search/index.vue — URL 同期', () => {
  beforeEach(() => {
    search.mockReset()
    search.mockResolvedValue(EMPTY)
    vi.spyOn(useRouter(), 'replace').mockResolvedValue(undefined)
    route.query = { q: 'チーム' }
  })

  it('RS-01: URL の q が変わると入力欄が追従し、その語で再検索する', async () => {
    const wrapper = await mountSuspended(SearchPage)
    await flushPromises()
    expect(search).toHaveBeenCalledTimes(1)

    route.query = { q: 'サッカー', type: 'TEAM' }
    await flushPromises()
    expect((wrapper.find('[data-testid="global-search-input"]').element as HTMLInputElement).value).toBe('サッカー')
    // 検索語が変わった詳細ビューは、詳細取得に加えて件数用の概要取得も走る（既存仕様）
    expect(search).toHaveBeenCalledWith({ q: 'サッカー', types: ['TEAM'], page: 0, size: 20 })
  })

  it('RS-02: 画面自身が書き戻した URL（現在の状態と同じ）では再検索しない', async () => {
    await mountSuspended(SearchPage)
    await flushPromises()
    search.mockClear()
    route.query = { q: 'チーム' }
    await flushPromises()
    expect(search).not.toHaveBeenCalled()
  })

  it('RS-03: ページ内入力を未送信のまま B に編集し、ヘッダーから B を検索しても再検索する（比較は実際に検索した条件）', async () => {
    const wrapper = await mountSuspended(SearchPage)
    await flushPromises()
    search.mockClear()
    await wrapper.find('[data-testid="global-search-input"]').setValue('サッカー')
    route.query = { q: 'サッカー' }
    await flushPromises()
    expect(search).toHaveBeenCalledWith({ q: 'サッカー', types: undefined, page: undefined, size: undefined })
  })

  it('RS-04: 空の /search へ戻ると、前回の結果・件数タブ・入力が初期状態へ戻る', async () => {
    search.mockResolvedValue({
      ...EMPTY,
      data: {
        ...EMPTY.data,
        results: [{
          type: 'TEAM', id: 1, title: 'チーム1', snippet: null, highlights: { title: [0, 2] }, relevance: null,
          author: null, scope: null, createdAt: '2026-11-17T09:00:00', url: '/teams/t1', actions: [],
        }],
        typeCounts: { ...EMPTY.data.typeCounts, TEAM: 1 },
      },
      meta: { viewMode: 'OVERVIEW', total: 1, page: 0, size: 5, totalPages: 0 },
    })
    const wrapper = await mountSuspended(SearchPage)
    await flushPromises()
    expect(wrapper.find('[data-testid="global-search-result-TEAM-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="global-search-tab-ALL"]').exists()).toBe(true)

    route.query = {}
    await flushPromises()
    expect(wrapper.find('[data-testid="global-search-result-TEAM-1"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="global-search-tab-ALL"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="global-search-empty"]').exists()).toBe(false)
    expect((wrapper.find('[data-testid="global-search-input"]').element as HTMLInputElement).value).toBe('')
  })
})
