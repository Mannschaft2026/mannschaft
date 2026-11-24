import { describe, expect, it } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import SearchResultCard from '~/components/search/SearchResultCard.vue'
import type { ContentType, SearchResult } from '~/types/search'

/**
 * F04.6 グローバル検索の結果カード（CMP-261116-0722 アリシゼーション指摘の回帰固定）。
 *
 *   RC-01 highlights.title の範囲を <mark> で描画する（設計 F04.6 §4「title はプレーン＋highlights.title」）
 *   RC-02 全 9 種別で RC-01 が効く
 *   RC-03 題名は v-html を使わずに描画し、題名中の HTML 風文字列を要素にしない（XSS 禁止）
 *   RC-04 範囲が不正・無いときは強調せず題名をそのまま出す（文字を欠落・重複させない）
 *   RC-05 種別バッジは縮まず折り返さない（「投/稿」の縦折れ防止）
 *   RC-06 作成者・日付の行は折り返せて、作成者名は省略表示・日付は分割しない（360px でのはみ出し防止）
 */

const ALL_TYPES: ContentType[] = [
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

function hit(partial: Partial<SearchResult> = {}): SearchResult {
  return {
    type: 'POST',
    id: 1,
    title: 'gsabc の練習日程',
    snippet: null,
    highlights: { title: [0, 5] },
    relevance: null,
    author: { id: 9, displayName: 'とても長い表示名のユーザーさんとても長い表示名', avatarUrl: null },
    scope: { type: 'TEAM', id: 5, name: 'サッカー部' },
    createdAt: '2026-11-17T09:00:00',
    url: null,
    actions: [],
    ...partial,
  }
}

async function mountCard(result: SearchResult) {
  return mountSuspended(SearchResultCard, { props: { result } })
}

describe('components/search/SearchResultCard.vue', () => {
  it('RC-01: highlights.title の [start, end) を <mark> で囲み、題名の全文はそのまま保つ', async () => {
    const wrapper = await mountCard(hit())
    const title = wrapper.find('[data-testid="global-search-result-title"]')
    expect(title.exists()).toBe(true)
    const marks = title.findAll('mark')
    expect(marks).toHaveLength(1)
    expect(marks[0]!.text()).toBe('gsabc')
    expect(title.text()).toBe('gsabc の練習日程')
  })

  it('RC-01: 題名の途中の範囲も正しく切り出す', async () => {
    const wrapper = await mountCard(hit({ title: '春のgsabc大会', highlights: { title: [2, 7] } }))
    const title = wrapper.find('[data-testid="global-search-result-title"]')
    expect(title.find('mark').text()).toBe('gsabc')
    expect(title.text()).toBe('春のgsabc大会')
  })

  it.each(ALL_TYPES)('RC-02: %s でも題名の一致箇所を強調する', async (type) => {
    const wrapper = await mountCard(hit({ type, id: 7 }))
    expect(wrapper.find('[data-testid="global-search-result-title"] mark').text()).toBe('gsabc')
  })

  it('RC-03: 題名中の HTML 風文字列は要素にならず文字のまま出る', async () => {
    const evil = '<img src=x onerror="alert(1)">gsabc<script>x</script>'
    const start = evil.indexOf('gsabc')
    const wrapper = await mountCard(hit({ title: evil, highlights: { title: [start, start + 5] } }))
    const title = wrapper.find('[data-testid="global-search-result-title"]')
    expect(title.find('img').exists()).toBe(false)
    expect(title.find('script').exists()).toBe(false)
    expect(title.text()).toBe(evil)
    expect(title.find('mark').text()).toBe('gsabc')
  })

  it.each([
    ['範囲なし', {}],
    ['逆転', { title: [5, 2] }],
    ['空範囲', { title: [3, 3] }],
    ['末尾超過', { title: [0, 999] }],
    ['負の開始', { title: [-1, 3] }],
    ['数値でない', { title: ['a', 'b'] }],
  ])('RC-04: 範囲が %s のときは強調せず題名をそのまま出す', async (_label, highlights) => {
    const wrapper = await mountCard(hit({ highlights: highlights as SearchResult['highlights'] }))
    const title = wrapper.find('[data-testid="global-search-result-title"]')
    expect(title.find('mark').exists()).toBe(false)
    expect(title.text()).toBe('gsabc の練習日程')
  })

  it('RC-05: 種別バッジは縮まず折り返さない（shrink-0 / whitespace-nowrap）', async () => {
    const wrapper = await mountCard(hit())
    const badge = wrapper.find('[data-testid="global-search-result-type-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.classes()).toEqual(expect.arrayContaining(['shrink-0', 'whitespace-nowrap']))
    // バッジと並ぶスコープ名は省略表示で縮む側になる
    const scope = wrapper.find('[data-testid="global-search-result-scope"]')
    expect(scope.classes()).toEqual(expect.arrayContaining(['min-w-0', 'truncate']))
    expect(scope.element.parentElement!.className).toContain('min-w-0')
  })

  it('RC-06: 作成者・日付の行は折り返し可・作成者は省略表示・日付は分割しない', async () => {
    const wrapper = await mountCard(hit())
    const meta = wrapper.find('[data-testid="global-search-result-meta"]')
    expect(meta.classes()).toContain('flex-wrap')
    const author = meta.find('[data-testid="global-search-result-author"]')
    expect(author.classes()).toEqual(expect.arrayContaining(['min-w-0', 'truncate']))
    expect(author.text()).toContain('とても長い表示名')
    const date = meta.find('[data-testid="global-search-result-date"]')
    expect(date.classes()).toEqual(expect.arrayContaining(['shrink-0', 'whitespace-nowrap']))
    // 日付は作成者の後ろに並び、作成者が無くても出る
    const noAuthor = await mountCard(hit({ author: null }))
    expect(noAuthor.find('[data-testid="global-search-result-date"]').exists()).toBe(true)
  })
})
