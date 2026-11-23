import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import WidgetOrgAnnouncements from './WidgetOrgAnnouncements.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

const stubs = { DashboardWidgetCard: { props: ['to', 'title'], template: '<div :data-to="to"><slot /></div>' } }

function item(id: number, scopeType: string, scopeId: number, title: string) {
  return {
    id, scopeType, scopeId, scopeName: 'O', sourceType: 'BLOG_POST', sourceId: id, accessState: 'FULL',
    contentPreviewAvailable: true, titleCache: title, excerptCache: '', priority: 'NORMAL',
    isPinned: false, isRead: false, createdAt: '2026-11-01T00:00:00',
  }
}

async function mountWith(items: unknown[]) {
  apiMock.mockResolvedValue({ data: items, meta: { nextCursor: null, hasNext: false, unreadCount: 0 } })
  const store = useOrganizationStore()
  store.myOrganizations = [
    { id: 1, name: 'Alpha', slug: 'alpha' },
    { id: 2, name: 'Beta', slug: 'beta' },
  ] as never
  const w = await mountSuspended(WidgetOrgAnnouncements, { global: { stubs } })
  await flushPromises()
  return w
}

describe('WidgetOrgAnnouncements', () => {
  beforeEach(() => apiMock.mockReset())

  it('/announcements/me を呼ぶ（掲示板スレッド API ではない）', async () => {
    await mountWith([])
    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(String(apiMock.mock.calls[0]![0])).toContain('/api/v1/announcements/me')
  })

  it('組織スコープだけを表示し、各項目は所属組織のトップへ遷移する（/timeline ではない）', async () => {
    const w = await mountWith([
      item(10, 'ORGANIZATION', 1, 'アルファの告知'),
      item(11, 'TEAM', 1, 'チームの告知'),
      item(12, 'ORGANIZATION', 2, 'ベータの告知'),
    ])
    const text = w.text()
    expect(text).toContain('アルファの告知')
    expect(text).toContain('ベータの告知')
    expect(text).not.toContain('チームの告知')
    const hrefs = w.findAll('a').map(a => a.attributes('href'))
    expect(hrefs).toContain('/organizations/alpha')
    expect(hrefs).toContain('/organizations/beta')
    expect(hrefs).not.toContain('/timeline')
    expect(w.find('[data-to]').attributes('data-to')).not.toBe('/timeline')
  })
})
