// @vitest-environment happy-dom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import AnnouncementItem from './AnnouncementItem.vue'
import type { AnnouncementFeedItem } from '~/types/announcement'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string, params?: { title?: string }) => (params?.title ? `${key}|${params.title}` : key) }) }))
vi.mock('~/composables/useDatetime', () => ({ useDatetime: () => ({ formatDate: () => '日付', fromNow: () => '現在' }) }))
mockNuxtImport('useRouter', () => () => ({ push: vi.fn() }))

const Button = defineComponent({
  props: { icon: { type: String, default: '' } },
  template: '<button type="button" :class="icon"><slot /></button>',
})
let wrapper: VueWrapper | undefined
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

function render(props: { itemTitle?: string, isPinned?: boolean, showPinControl?: boolean, canRemove?: boolean }) {
  const { isPinned = false, itemTitle = '', ...controls } = props
  wrapper = mount(AnnouncementItem, {
    props: {
      item: {
        id: 1, scopeType: 'TEAM', scopeId: '2', sourceType: 'BLOG_POST', sourceId: 3,
        title: itemTitle, excerpt: null, priority: 'NORMAL', isPinned, isRead: false,
        createdAt: '2026-10-05T12:00:00',
      } as AnnouncementFeedItem,
      ...controls,
    },
    global: { stubs: { Button, Menu: true, AdLabelBadge: true, AdReportModal: true } },
  })
  return wrapper
}

describe('管理者・著者アクション（D-2/D-3）', () => {
  it('アクション部はホバーに加えフォーカス内でも不透明になる', () => {
    const actions = render({ showPinControl: true }).get('[data-testid="announcement-actions"]')
    expect(actions.classes()).toContain('group-hover:opacity-100')
    expect(actions.classes()).toContain('group-focus-within:opacity-100')
  })

  it('各ボタンは focus-visible で不透明になり aria-label を持つ', () => {
    const view = render({ showPinControl: true })
    const pin = view.get('[data-testid="announcement-pin"]')
    const remove = view.get('[data-testid="announcement-remove"]')
    expect(pin.classes()).toContain('focus-visible:opacity-100')
    expect(remove.classes()).toContain('focus-visible:opacity-100')
    expect(pin.attributes('aria-label')).toBe('announcement.pin')
    expect(remove.attributes('aria-label')).toBe('announcement.remove_from_announcements')
  })

  it('ピン済みと未ピンでアイコンと aria-label が異なる', () => {
    const unpinned = render({ showPinControl: true, isPinned: false }).get('[data-testid="announcement-pin"]')
    const unpinnedClass = unpinned.classes().join(' ')
    const unpinnedLabel = unpinned.attributes('aria-label')
    wrapper?.unmount()
    const pinned = render({ showPinControl: true, isPinned: true }).get('[data-testid="announcement-pin"]')
    expect(pinned.classes()).toContain('pi-bookmark-fill')
    expect(unpinnedClass).not.toContain('pi-bookmark-fill')
    expect(pinned.attributes('aria-label')).toBe('announcement.unpin')
    expect(unpinnedLabel).toBe('announcement.pin')
  })

  it('タイトルがあれば aria-label にタイトルを含める（N6）', () => {
    const view = render({ showPinControl: true, itemTitle: '定例会' })
    expect(view.get('[data-testid="announcement-pin"]').attributes('aria-label')).toBe('announcement.pin_titled|定例会')
    expect(view.get('[data-testid="announcement-remove"]').attributes('aria-label')).toBe('announcement.remove_titled|定例会')
    wrapper?.unmount()
    const pinned = render({ showPinControl: true, isPinned: true, itemTitle: '定例会' })
    expect(pinned.get('[data-testid="announcement-pin"]').attributes('aria-label')).toBe('announcement.unpin_titled|定例会')
  })

  it('ピン留め時だけ sr-only の「ピン留め中」を出す（N10）', () => {
    const pinned = render({ isPinned: true }).get('[data-testid="announcement-pinned-status"]')
    expect(pinned.classes()).toContain('sr-only')
    expect(pinned.text()).toBe('announcement.pinned_status')
    wrapper?.unmount()
    expect(render({ isPinned: false }).find('[data-testid="announcement-pinned-status"]').exists()).toBe(false)
  })

  it('canRemove のみ true なら解除(×)だけ表示しピン留めは出ない', () => {
    const view = render({ canRemove: true })
    expect(view.find('[data-testid="announcement-remove"]').exists()).toBe(true)
    expect(view.find('[data-testid="announcement-pin"]').exists()).toBe(false)
  })

  it('showPinControl が true ならピン留めと解除の両方を表示する', () => {
    const view = render({ showPinControl: true })
    expect(view.find('[data-testid="announcement-remove"]').exists()).toBe(true)
    expect(view.find('[data-testid="announcement-pin"]').exists()).toBe(true)
  })

  it('どちらも false なら何も表示しない', () => {
    const view = render({})
    expect(view.find('[data-testid="announcement-actions"]').exists()).toBe(false)
  })
})
