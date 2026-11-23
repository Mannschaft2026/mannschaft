// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AnnouncementItem from './AnnouncementItem.vue'
import type { AnnouncementFeedItem } from '~/types/announcement'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('~/composables/useDatetime', () => ({ useDatetime: () => ({ formatDate: () => '日付', fromNow: () => '5時間前' }) }))
mockNuxtImport('useRouter', () => () => ({ push: vi.fn() }))

let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

function metaText(author: unknown): string {
  wrapper = mount(AnnouncementItem, {
    props: { item: {
      id: 1, scopeType: 'TEAM', scopeId: '2', sourceType: 'BLOG_POST', sourceId: 3,
      title: 't', excerpt: null, priority: 'NORMAL', isPinned: false, isRead: true,
      createdAt: '2026-10-05T12:00:00', author,
    } as AnnouncementFeedItem },
    global: { stubs: { Button: true, Menu: true, AdLabelBadge: true, AdReportModal: true } },
  })
  return wrapper.get('[data-testid="announcement-meta"]').findAll('span').map((s) => s.text()).join(' ')
}

describe('メタ行の区切り', () => {
  it('作者名が空でも区切りの点が連続しない', () => {
    const text = metaText({ id: 1, displayName: '' })
    expect(text).not.toMatch(/•\s*•/)
    expect(text).toBe('announcement.source_type.BLOG_POST • 5時間前')
  })
  it('作者がいれば種別 • 作者 • 時刻', () => {
    expect(metaText({ id: 1, displayName: '太郎' })).toBe('announcement.source_type.BLOG_POST • 太郎 • 5時間前')
  })
})
