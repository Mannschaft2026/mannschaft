// CMP-261107-1833 PR3 N2: お知らせ・承認待ちの内訳から …/invites#reissue-requests で来たとき、欄までスクロールする。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, reactive } from 'vue'
import Panel from './InviteReissueRequestPanel.vue'

const mocks = vi.hoisted(() => ({ api: vi.fn() }))
const route = reactive({ path: '/teams/my-team/invites', query: {}, hash: '' })
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useRoute', () => () => route)
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), info: vi.fn(), warn: vi.fn(), error: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useConfirm', () => () => ({ require: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key, te: () => true }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (v: string) => v, formatDateTime: (v: string) => v }))

const stubs = {
  SectionCard: defineComponent({ template: '<section v-bind="$attrs"><slot /></section>' }),
  Button: true,
  LoadingBounce: true,
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.api.mockResolvedValue({ data: { content: [], totalElements: 0, totalPages: 0, number: 0, size: 20 } })
})

describe('InviteReissueRequestPanel のアンカー', () => {
  it('IRPA-01: #reissue-requests なら取得後に欄までスクロールする', async () => {
    route.hash = '#reissue-requests'
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    await mountSuspended(Panel, { props: { scopeType: 'team', slug: 'my-team' }, global: { stubs }, attachTo: document.body })
    await flushPromises()
    expect(scroll).toHaveBeenCalledTimes(1)
  })

  it('IRPA-02: ハッシュが無ければスクロールしない', async () => {
    route.hash = ''
    const scroll = vi.fn()
    Element.prototype.scrollIntoView = scroll
    await mountSuspended(Panel, { props: { scopeType: 'team', slug: 'my-team' }, global: { stubs }, attachTo: document.body })
    await flushPromises()
    expect(scroll).not.toHaveBeenCalled()
  })
})
