// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { toAnnouncementItem } from '~/utils/announcementAdapter'
import { useAnnouncementFeed } from './useAnnouncementFeed'
const { api } = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('~/composables/useApi', () => ({ useApi: () => api }))
vi.mock('~/composables/useErrorReport', () => ({ useErrorReport: () => ({ captureQuiet: vi.fn() }) }))
vi.mock('nuxt/app', () => ({ useNuxtApp: () => ({ $i18n: { t: (key: string) => key }, $toast: { add: vi.fn() } }) }))

const make = (id: number, createdAt: string, isPinned = false) => ({
  ...toAnnouncementItem({ id, scopeType: 'TEAM', scopeId: 8, sourceType: 'BLOG_POST', titleCache: `t${id}`, isRead: false }),
  createdAt,
  isPinned,
})
beforeEach(() => vi.resetAllMocks())

describe('togglePin の並び順（N7）', () => {
  it('ピン留めした項目が先頭に来る', async () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([make(3, '2026-10-03T00:00:00'), make(2, '2026-10-02T00:00:00'), make(1, '2026-10-01T00:00:00')])
    api.mockResolvedValue({ data: { isPinned: true, pinnedAt: '2026-10-05T00:00:00' } })
    await feed.togglePin(1)
    expect(feed.feed.value.map(i => i.id)).toEqual([1, 3, 2])
  })

  it('解除すると新着順の位置へ戻り、同時刻は id の大きい順', async () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([make(1, '2026-10-01T00:00:00', true), make(5, '2026-10-03T00:00:00'), make(4, '2026-10-03T00:00:00'), make(2, '2026-10-02T00:00:00')])
    api.mockResolvedValue({ data: { isPinned: false, pinnedAt: null } })
    await feed.togglePin(1)
    expect(feed.feed.value.map(i => i.id)).toEqual([5, 4, 2, 1])
  })

  it('失敗時は並びも状態も変えない', async () => {
    const feed = useAnnouncementFeed('TEAM', '8')
    feed.setFeedItemsLocally([make(2, '2026-10-02T00:00:00'), make(1, '2026-10-01T00:00:00')])
    api.mockRejectedValue(new Error('x'))
    await expect(feed.togglePin(1)).rejects.toThrow()
    expect(feed.feed.value.map(i => [i.id, i.isPinned])).toEqual([[2, false], [1, false]])
  })
})
