import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import WidgetTeamAnnouncements from './WidgetTeamAnnouncements.vue'

const apiMock = vi.hoisted(() => vi.fn())
vi.mock('~/composables/useApi', () => ({ useApi: () => apiMock }))

const stubs = { DashboardWidgetCard: { props: ['to', 'title'], template: '<div :data-to="to"><slot /></div>' } }

function item(id: number, scopeType: string, scopeId: number, title: string) {
  return {
    id, scopeType, scopeId, scopeName: 'T', sourceType: 'BLOG_POST', sourceId: id, accessState: 'FULL',
    contentPreviewAvailable: true, titleCache: title, excerptCache: '', priority: 'NORMAL',
    isPinned: false, isRead: false, createdAt: '2026-11-01T00:00:00',
  }
}

async function mountWith(items: unknown[]) {
  apiMock.mockResolvedValue({ data: items, meta: { nextCursor: null, hasNext: false, unreadCount: 0 } })
  const store = useTeamStore()
  store.myTeams = [
    { id: 1, name: 'Alpha', slug: 'alpha' },
    { id: 2, name: 'Beta', slug: 'beta' },
  ] as never
  const w = await mountSuspended(WidgetTeamAnnouncements, { global: { stubs } })
  await flushPromises()
  return w
}

describe('WidgetTeamAnnouncements', () => {
  beforeEach(() => apiMock.mockReset())

  it('/announcements/me を呼ぶ（掲示板スレッド API ではない）', async () => {
    await mountWith([])
    expect(apiMock).toHaveBeenCalledTimes(1)
    expect(String(apiMock.mock.calls[0]![0])).toContain('/api/v1/announcements/me')
  })

  it('チームスコープのお知らせだけを表示し、各項目は所属チームのお知らせ一覧へ遷移する', async () => {
    const w = await mountWith([
      item(10, 'TEAM', 1, 'アルファの告知'),
      item(11, 'ORGANIZATION', 1, '組織の告知'),
      item(12, 'TEAM', 2, 'ベータの告知'),
    ])
    const text = w.text()
    expect(text).toContain('アルファの告知')
    expect(text).toContain('ベータの告知')
    expect(text).not.toContain('組織の告知')
    const hrefs = w.findAll('a').map(a => a.attributes('href'))
    expect(hrefs).toContain('/teams/alpha/announcements')
    expect(hrefs).toContain('/teams/beta/announcements')
    expect(hrefs).not.toContain('/timeline')
    expect(w.find('[data-to]').attributes('data-to')).not.toBe('/timeline')
  })

  it('お知らせが無いときは空状態を出す', async () => {
    const w = await mountWith([])
    expect(w.text()).toContain('No announcements from teams')
  })
})
