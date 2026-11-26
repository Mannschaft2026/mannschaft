// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import FilesPage from './files.vue'

// CMP-261124-0159 PR-F1: チーム詳細が 403/404 のとき空表示に化けず、共通の「見つかりません」を出し、ファイル共有 API を呼ばない。
// 403 の理由表示（AC-0159-86・87）は 2026-11-27 マスター裁定で取り下げ（CMP-261125-1108 に統一）。
const mocks = vi.hoisted(() => ({
  getTeam: vi.fn(),
  resolve: vi.fn(),
  navigate: vi.fn(),
  error: vi.fn(),
  route: { path: '/teams/a/files', params: { slug: 'a' }, query: {}, hash: '' },
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useRoute', () => () => mocks.route)
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
mockNuxtImport('useSlugRedirect', () => () => ({ resolveSlug: mocks.resolve }))
mockNuxtImport('navigateTo', () => mocks.navigate)
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))

const NotFound = defineComponent({ setup: () => () => h('div', { 'data-testid': 'scope-not-found' }) })
const Browser = defineComponent({
  props: ['scopeType', 'scopeId'],
  setup: props => () => h('div', { 'data-testid': 'file-browser', 'data-scope-id': props.scopeId }),
})

function mountPage() {
  return mount(FilesPage, {
    global: { stubs: { PageHeader: true, LoadingBounce: true, ScopeNotFound: NotFound, FileBrowser: Browser } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.resolve.mockResolvedValue({ status: 'NOT_FOUND' })
})

describe('files.vue のチーム詳細 403/404（同じ「見つかりません」）', () => {
  it('200 は従来どおり数値 ID で FileBrowser を出す', async () => {
    mocks.getTeam.mockResolvedValue({ data: { id: 'a', numericId: 42 } })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-testid="file-browser"]').attributes('data-scope-id')).toBe('42')
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(false)
  })

  it('403 は「見つかりません」を出し、FileBrowser（ファイル共有 API）を描画しない。トーストなし', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 403 } })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="file-browser"]').exists()).toBe(false)
    expect(mocks.error).not.toHaveBeenCalled()
  })

  it('404 も同じ「見つかりません」を出し、FileBrowser を描画しない', async () => {
    mocks.getTeam.mockRejectedValue({ response: { status: 404 } })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('[data-testid="scope-not-found"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="file-browser"]').exists()).toBe(false)
  })
})
