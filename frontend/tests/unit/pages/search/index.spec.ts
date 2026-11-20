import { describe, expect, it, vi, beforeEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import SearchPage from '~/pages/search/index.vue'
import type { SearchResponse } from '~/types/search'

/**
 * F04.6 グローバル検索 /search 画面（CMP-261116-0722 PR-1）の FE-UT。
 *
 * BE の実応答形（camelCase・page 0 始まり・meta.viewMode/totalPages・results は配列）を fixture に使う。
 *   GS-01 (AC-E1) 概要ビューの応答で結果カードが出て、失敗通知は出ない
 *   GS-02 (AC-E2) 種別タブに typeCounts が出て、概要ビューでは Paginator を出さない
 *   GS-03 (AC-E2) タブ選択で type 単一の詳細ビューになり、Paginator が出てページ送りできる
 *   GS-04 (AC-E3) 0 件は空状態と zeroResultsHelp、タイムアウト種別は警告を i18n 文言で出す
 *   GS-05 BE が失敗したら失敗通知を出す（握りつぶさない）
 */

const search = vi.fn()
const notifyError = vi.fn()
const route = { params: {}, query: {} as Record<string, string> }

vi.mock('~/composables/useSearchApi', () => ({
  useSearchApi: () => ({ search }),
}))
mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useNotification', () => () => ({
  error: notifyError,
  warn: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
}))

function counts(
  partial: Partial<SearchResponse['data']['typeCounts']>,
): SearchResponse['data']['typeCounts'] {
  return {
    POST: 0,
    MESSAGE: 0,
    THREAD: 0,
    ARTICLE: 0,
    FILE: 0,
    USER: 0,
    TEAM: 0,
    ORGANIZATION: 0,
    ACTIVITY: 0,
    ...partial,
  }
}

function teamHit(id: number): SearchResponse['data']['results'][number] {
  return {
    type: 'TEAM',
    id,
    title: `チーム${id}`,
    snippet: null,
    highlights: { title: [0, 2] },
    relevance: null,
    author: null,
    scope: null,
    createdAt: '2026-11-17T09:00:00',
    url: `/teams/team-${id}`,
    actions: [],
  }
}

const OVERVIEW: SearchResponse = {
  data: {
    results: [teamHit(1), teamHit(2)],
    typeCounts: counts({ TEAM: 7, USER: 2 }),
    query: 'チーム',
    timedOutTypes: [],
    zeroResultsHelp: null,
  },
  meta: { viewMode: 'OVERVIEW', total: 9, page: 0, size: 5, totalPages: 0 },
}

const DETAIL: SearchResponse = {
  data: {
    results: [teamHit(3)],
    typeCounts: counts({ TEAM: 45 }),
    query: 'チーム',
    timedOutTypes: [],
    zeroResultsHelp: null,
  },
  meta: { viewMode: 'DETAIL', total: 45, page: 0, size: 20, totalPages: 3 },
}

async function mountPage() {
  const wrapper = await mountSuspended(SearchPage)
  await flushPromises()
  return wrapper
}

describe('pages/search/index.vue — グローバル検索', () => {
  beforeEach(() => {
    search.mockReset()
    notifyError.mockReset()
    vi.spyOn(useRouter(), 'replace').mockResolvedValue(undefined)
    route.query = { q: 'チーム' }
  })

  it('GS-01: 概要ビューの応答で結果カードが出て、失敗通知は出ない', async () => {
    search.mockResolvedValue(OVERVIEW)
    const wrapper = await mountPage()
    expect(search).toHaveBeenCalledWith({ q: 'チーム', types: undefined, page: undefined, size: undefined })
    expect(wrapper.find('[data-testid="global-search-result-TEAM-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="global-search-result-TEAM-2"]').exists()).toBe(true)
    expect(notifyError).not.toHaveBeenCalled()
  })

  it('GS-02: 種別タブに typeCounts が出て、概要ビューでは Paginator を出さない', async () => {
    search.mockResolvedValue(OVERVIEW)
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="global-search-tab-ALL"]').text()).toContain('(9)')
    expect(wrapper.find('[data-testid="global-search-tab-TEAM"]').text()).toContain('(7)')
    expect(wrapper.find('[data-testid="global-search-tab-USER"]').text()).toContain('(2)')
    expect(wrapper.find('[data-testid="global-search-tab-POST"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="global-search-paginator"]').exists()).toBe(false)
  })

  it('GS-03: タブ選択で type 単一の詳細ビューになり、Paginator でページ送りできる', async () => {
    search.mockResolvedValueOnce(OVERVIEW).mockResolvedValueOnce(DETAIL)
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="global-search-tab-TEAM"]').trigger('click')
    await flushPromises()
    expect(search).toHaveBeenLastCalledWith({ q: 'チーム', types: ['TEAM'], page: 0, size: 20 })
    expect(wrapper.find('[data-testid="global-search-paginator"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="global-search-result-TEAM-3"]').exists()).toBe(true)

    search.mockResolvedValueOnce({ ...DETAIL, meta: { ...DETAIL.meta, page: 1 } })
    const paginator = wrapper.findComponent({ name: 'Paginator' })
    paginator.vm.$emit('page', { page: 1 })
    await flushPromises()
    expect(search).toHaveBeenLastCalledWith({ q: 'チーム', types: ['TEAM'], page: 1, size: 20 })
  })

  it('GS-04: 0 件は空状態と zeroResultsHelp、タイムアウト種別の警告を出す', async () => {
    const zero: SearchResponse = {
      data: {
        results: [],
        typeCounts: counts({}),
        query: 'チーム 練習',
        timedOutTypes: ['USER'],
        zeroResultsHelp: { didYouMean: 'チームA', broaderQuery: 'チーム' },
      },
      meta: { viewMode: 'OVERVIEW', total: 0, page: 0, size: 5, totalPages: 0 },
    }
    search.mockResolvedValue(zero)
    const wrapper = await mountPage()
    const empty = wrapper.find('[data-testid="global-search-empty"]')
    expect(empty.exists()).toBe(true)
    expect(empty.text()).toContain('チームA')
    const timeout = wrapper.find('[data-testid="global-search-timeout"]')
    expect(timeout.exists()).toBe(true)
    expect(timeout.text()).not.toContain('globalSearch.')
  })

  it('GS-06: 種別タブ選択後に検索語を変えて再検索すると、他種別の件数は新しい検索語のものになる', async () => {
    const emptyOverview: SearchResponse = {
      data: { results: [], typeCounts: counts({}), query: '新語', timedOutTypes: [], zeroResultsHelp: null },
      meta: { viewMode: 'OVERVIEW', total: 0, page: 0, size: 5, totalPages: 0 },
    }
    const emptyDetail: SearchResponse = {
      data: { results: [], typeCounts: counts({}), query: '新語', timedOutTypes: [], zeroResultsHelp: null },
      meta: { viewMode: 'DETAIL', total: 0, page: 0, size: 20, totalPages: 0 },
    }
    search.mockImplementation(async (params: { q: string, types?: string[] }) => {
      if (params.q === '新語') return params.types ? emptyDetail : emptyOverview
      return params.types ? DETAIL : OVERVIEW
    })
    const wrapper = await mountPage()
    await wrapper.find('[data-testid="global-search-tab-TEAM"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="global-search-tab-USER"]').text()).toContain('(2)')

    await wrapper.find('[data-testid="global-search-input"]').setValue('新語')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-testid="global-search-tab-USER"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="global-search-tab-ALL"]').text()).not.toContain('(2)')
    expect(wrapper.find('[data-testid="global-search-tab-ALL"]').text()).not.toContain('(9)')
  })

  it('GS-05: BE が失敗したら失敗通知を出す', async () => {
    search.mockRejectedValue(new Error('500'))
    await mountPage()
    expect(notifyError).toHaveBeenCalledTimes(1)
  })
})
