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

  it('RS-05: A 表示後に B の検索を始め、応答前に「戻る」で A へ戻ったら、入力は A に復元され B の結果・URL 書き換えは起きない', async () => {
    const wrapper = await mountSuspended(SearchPage)
    await flushPromises()
    const replaceSpy = vi.spyOn(useRouter(), 'replace')
    replaceSpy.mockClear()
    let resolveB: (v: SearchResponse) => void = () => {}
    search.mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveB = r }))

    route.query = { q: 'サッカー' }
    await flushPromises()
    expect(search).toHaveBeenLastCalledWith({ q: 'サッカー', types: undefined, page: undefined, size: undefined })

    route.query = { q: 'チーム' }
    await flushPromises()
    expect((wrapper.find('[data-testid="global-search-input"]').element as HTMLInputElement).value).toBe('チーム')

    resolveB({ ...EMPTY, data: { ...EMPTY.data, query: 'サッカー' } })
    await flushPromises()
    expect(replaceSpy).not.toHaveBeenCalled()
    expect((wrapper.find('[data-testid="global-search-input"]').element as HTMLInputElement).value).toBe('チーム')
  })

  it('RS-06: A の応答待ち中に入力を B へ編集しても、応答後の URL 書き換えは A のままで B は自動検索されず、編集中の入力も保たれる', async () => {
    let resolveA: (v: SearchResponse) => void = () => {}
    search.mockReset()
    search.mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveA = r }))
    const replaceSpy = vi.spyOn(useRouter(), 'replace')
    replaceSpy.mockReset()
    // 実際のルーターと同様に、replace は route.query を書き換える
    replaceSpy.mockImplementation(async (to) => {
      const next = (to as { query?: Record<string, string> }).query
      if (next) route.query = next
      return undefined
    })
    const wrapper = await mountSuspended(SearchPage)
    await flushPromises()
    expect(search).toHaveBeenCalledTimes(1)

    await wrapper.find('[data-testid="global-search-input"]').setValue('サッカー')
    search.mockResolvedValue(EMPTY)
    resolveA(EMPTY)
    await flushPromises()

    expect(replaceSpy).toHaveBeenCalledWith({ query: { q: 'チーム' } })
    expect(search).toHaveBeenCalledTimes(1)
    expect((wrapper.find('[data-testid="global-search-input"]').element as HTMLInputElement).value).toBe('サッカー')
  })

  // URL 変化の全分岐（空 / 最小長未満 / 検索済み条件と一致 / 新条件）で、
  // 進行中リクエストの無効化と表示の整合が保たれる
  describe('RS-07: 進行中の B 検索中に URL が変わった（分岐別）', () => {
    const B_HIT = {
      type: 'TEAM' as const, id: 9, title: 'チームB', snippet: null, highlights: { title: [0, 2] as [number, number] },
      relevance: null, author: null, scope: null, createdAt: '2026-11-17T09:00:00', url: '/teams/b', actions: [],
    }
    const B_RES: SearchResponse = {
      ...EMPTY,
      data: { ...EMPTY.data, results: [B_HIT], typeCounts: { ...EMPTY.data.typeCounts, TEAM: 1 } },
      meta: { viewMode: 'OVERVIEW', total: 1, page: 0, size: 5, totalPages: 0 },
    }
    const cases: { name: string; next: Record<string, string>; input: string; replacedWith: string | null }[] = [
      { name: '空', next: {}, input: '', replacedWith: null },
      { name: '最小長未満(1文字)', next: { q: 'あ' }, input: 'あ', replacedWith: null },
      { name: '検索済み条件と一致', next: { q: 'チーム' }, input: 'チーム', replacedWith: null },
      { name: '新条件(C)', next: { q: 'ラグビー' }, input: 'ラグビー', replacedWith: 'ラグビー' },
    ]
    it.each(cases)('$name', async ({ next, input, replacedWith }) => {
      const wrapper = await mountSuspended(SearchPage)
      await flushPromises()
      const replaceSpy = vi.spyOn(useRouter(), 'replace')
      replaceSpy.mockClear()
      let resolveB: (v: SearchResponse) => void = () => {}
      search.mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveB = r }))
      search.mockResolvedValue(EMPTY)

      route.query = { q: 'サッカー' }
      await flushPromises()
      route.query = next
      await flushPromises()
      resolveB(B_RES)
      await flushPromises()

      expect(wrapper.find('[data-testid="global-search-result-TEAM-9"]').exists()).toBe(false)
      expect((wrapper.find('[data-testid="global-search-input"]').element as HTMLInputElement).value).toBe(input)
      const replacedQs = replaceSpy.mock.calls.map(c => (c[0] as { query: { q: string } }).query.q)
      expect(replacedQs).not.toContain('サッカー')
      if (replacedWith === null) expect(replaceSpy).not.toHaveBeenCalled()
      else expect(replacedQs).toContain(replacedWith)
      if (input.trim().length < 2) {
        // 検索できない長さの URL では件数タブ・結果が初期状態へ戻る
        expect(wrapper.find('[data-testid="global-search-tab-ALL"]').exists()).toBe(false)
      }
    })
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
