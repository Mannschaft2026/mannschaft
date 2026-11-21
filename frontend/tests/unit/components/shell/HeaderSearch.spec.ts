import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import HeaderSearch from '~/components/shell/HeaderSearch.vue'
import type { Suggestion } from '~/types/search'

/**
 * F04.6 ヘッダー検索欄（CMP-261116-0722 PR-8 / AC-E4）の FE-UT。
 *   HS-01 入力 300ms の debounce 後に BE の suggestions API を呼び、BE が返した候補だけを出す
 *   HS-02 Enter で /search?q=... へ遷移する（2 文字未満は遷移せず警告）
 *   HS-03 /search の q・type が入力欄に復元され、再検索でも type が URL に残る
 *   HS-04 候補選択: KEYWORD は検索へ、USER/TEAM/ORGANIZATION は BE の url へ遷移する
 *   HS-05 モバイル導線: アイコンのリンクが /search（q があれば q・type 付き）を指す
 *   HS-06 suggestions が失敗しても検索欄は壊れず、候補は出ない（失敗はログに残す）
 */

const suggestions = vi.fn()
const push = vi.fn()
const warn = vi.fn()
const route = { path: '/dashboard', params: {}, query: {} as Record<string, string> }

vi.mock('~/composables/useSearchApi', () => ({
  useSearchApi: () => ({ suggestions }),
}))
mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useNotification', () => () => ({
  error: vi.fn(),
  warn,
  success: vi.fn(),
  info: vi.fn(),
}))

const KEYWORD: Suggestion = { text: 'グラウンド利用', type: 'KEYWORD', userId: null, teamId: null, organizationId: null, url: null }
const USER: Suggestion = { text: '田中太郎', type: 'USER', userId: 10, teamId: null, organizationId: null, url: '/social/tanaka' }
const TEAM: Suggestion = { text: 'サッカー部', type: 'TEAM', userId: null, teamId: 5, organizationId: null, url: '/teams/soccer' }

async function mountBox() {
  const wrapper = await mountSuspended(HeaderSearch)
  await flushPromises()
  // debounce を決定的に検証するため、マウント完了後にタイマーだけ偽装する
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  return wrapper
}

async function typeAndWait(wrapper: Awaited<ReturnType<typeof mountBox>>, text: string) {
  const input = wrapper.find('[data-testid="header-search-input"]')
  await input.setValue(text)
  await vi.advanceTimersByTimeAsync(300)
  await flushPromises()
}

describe('components/shell/HeaderSearch.vue — ヘッダー検索欄', () => {
  beforeEach(() => {
    suggestions.mockReset()
    push.mockReset()
    push.mockResolvedValue(undefined)
    vi.spyOn(useRouter(), 'push').mockImplementation(push)
    warn.mockReset()
    route.path = '/dashboard'
    route.query = {}
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('HS-01: 300ms の debounce 後に suggestions を 1 回呼び、BE が返した候補だけを出す', async () => {
    suggestions.mockResolvedValue([KEYWORD, USER])
    const wrapper = await mountBox()
    const input = wrapper.find('[data-testid="header-search-input"]')
    await input.setValue('グラ')
    await vi.advanceTimersByTimeAsync(299)
    expect(suggestions).not.toHaveBeenCalled()
    await input.setValue('グラウ')
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
    expect(suggestions).toHaveBeenCalledTimes(1)
    expect(suggestions).toHaveBeenCalledWith('グラウ', 5)
    const items = wrapper.findAll('[data-testid^="header-search-suggestion-"]')
    expect(items).toHaveLength(2)
    expect(items[0]!.text()).toContain('グラウンド利用')
    expect(items[1]!.text()).toContain('田中太郎')
  })

  it('HS-02: Enter で /search?q= へ遷移し、2 文字未満は遷移せず警告する', async () => {
    suggestions.mockResolvedValue([])
    const wrapper = await mountBox()
    await wrapper.find('[data-testid="header-search-input"]').setValue('サッカー')
    await wrapper.find('[data-testid="header-search-form"]').trigger('submit')
    expect(push).toHaveBeenCalledWith({ path: '/search', query: { q: 'サッカー' } })

    push.mockReset()
    await wrapper.find('[data-testid="header-search-input"]').setValue('あ')
    await wrapper.find('[data-testid="header-search-form"]').trigger('submit')
    expect(push).not.toHaveBeenCalled()
    expect(warn).toHaveBeenCalled()
  })

  it('HS-03: /search の q・type が入力欄に復元され、再検索でも type が URL に残る', async () => {
    suggestions.mockResolvedValue([])
    route.path = '/search'
    route.query = { q: 'チーム', type: 'TEAM' }
    const wrapper = await mountBox()
    const input = wrapper.find('[data-testid="header-search-input"]')
    expect((input.element as HTMLInputElement).value).toBe('チーム')
    await input.setValue('サッカー')
    await wrapper.find('[data-testid="header-search-form"]').trigger('submit')
    expect(push).toHaveBeenCalledWith({ path: '/search', query: { q: 'サッカー', type: 'TEAM' } })
  })

  it('HS-04: KEYWORD は検索へ、USER/TEAM は BE の url へ遷移する', async () => {
    suggestions.mockResolvedValue([KEYWORD, USER, TEAM])
    const wrapper = await mountBox()
    await typeAndWait(wrapper, 'サッ')
    await wrapper.findAll('[data-testid^="header-search-suggestion-"]')[2]!.trigger('click')
    expect(push).toHaveBeenLastCalledWith('/teams/soccer')

    await typeAndWait(wrapper, 'グラ')
    await wrapper.findAll('[data-testid^="header-search-suggestion-"]')[0]!.trigger('click')
    expect(push).toHaveBeenLastCalledWith({ path: '/search', query: { q: 'グラウンド利用' } })
  })

  it('HS-05: モバイル導線のリンクが /search を指し、q・type があれば引き継ぐ', async () => {
    suggestions.mockResolvedValue([])
    const plain = await mountBox()
    expect(plain.find('[data-testid="header-search-mobile-link"]').attributes('href')).toBe('/search')

    route.path = '/search'
    route.query = { q: 'チーム', type: 'TEAM' }
    const withQuery = await mountBox()
    const href = withQuery.find('[data-testid="header-search-mobile-link"]').attributes('href') ?? ''
    expect(href.startsWith('/search?')).toBe(true)
    expect(decodeURIComponent(href)).toContain('q=チーム')
    expect(href).toContain('type=TEAM')
  })

  it('HS-07: 入力が変わったら保留中の古い候補リクエストは無効になり、後から解決しても出ない', async () => {
    let resolveFirst: (v: Suggestion[]) => void = () => {}
    suggestions.mockImplementationOnce(() => new Promise<Suggestion[]>((r) => { resolveFirst = r }))
    const wrapper = await mountBox()
    await typeAndWait(wrapper, 'グラ')
    expect(suggestions).toHaveBeenCalledTimes(1)
    await wrapper.find('[data-testid="header-search-input"]').setValue('サッカー')
    resolveFirst([KEYWORD])
    await flushPromises()
    expect(wrapper.findAll('[data-testid^="header-search-suggestion-"]')).toHaveLength(0)
  })

  it('HS-08: Enter で検索を確定したら保留中の debounce は解除され、遷移後に候補は再取得されない', async () => {
    suggestions.mockResolvedValue([KEYWORD])
    const wrapper = await mountBox()
    await wrapper.find('[data-testid="header-search-input"]').setValue('サッカー')
    await wrapper.find('[data-testid="header-search-form"]').trigger('submit')
    await vi.advanceTimersByTimeAsync(300)
    await flushPromises()
    expect(suggestions).not.toHaveBeenCalled()
    expect(wrapper.findAll('[data-testid^="header-search-suggestion-"]')).toHaveLength(0)
  })

  it('HS-09: 矢印キーで候補を選び Enter で確定、Esc で閉じる。未選択の Enter は通常検索（横取りしない）', async () => {
    suggestions.mockResolvedValue([KEYWORD, USER, TEAM])
    const wrapper = await mountBox()
    await typeAndWait(wrapper, 'サッ')
    const input = wrapper.find('[data-testid="header-search-input"]')
    expect(input.attributes('role')).toBe('combobox')
    expect(input.attributes('aria-expanded')).toBe('true')

    await input.trigger('keydown', { key: 'Enter' })
    expect(push).not.toHaveBeenCalled()

    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'ArrowDown' })
    const options = wrapper.findAll('[data-testid^="header-search-suggestion-"]')
    expect(options[1]!.attributes('aria-selected')).toBe('true')
    expect(options[0]!.attributes('aria-selected')).toBe('false')
    expect(input.attributes('aria-activedescendant')).toBe(options[1]!.attributes('id'))

    await input.trigger('keydown', { key: 'ArrowUp' })
    expect(wrapper.findAll('[data-testid^="header-search-suggestion-"]')[0]!.attributes('aria-selected')).toBe('true')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Enter' })
    expect(push).toHaveBeenLastCalledWith('/social/tanaka')

    await typeAndWait(wrapper, 'サッカ')
    await input.trigger('keydown', { key: 'ArrowDown' })
    await input.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[data-testid="header-search-suggestions"]').exists()).toBe(false)
    expect(input.attributes('aria-expanded')).toBe('false')
  })

  it('HS-06: suggestions が失敗しても候補は出ず、失敗はログに残す', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    suggestions.mockRejectedValue(new Error('boom'))
    const wrapper = await mountBox()
    await typeAndWait(wrapper, 'グラ')
    expect(wrapper.findAll('[data-testid^="header-search-suggestion-"]')).toHaveLength(0)
    expect(spy).toHaveBeenCalled()
    spy.mockRestore()
  })
})
