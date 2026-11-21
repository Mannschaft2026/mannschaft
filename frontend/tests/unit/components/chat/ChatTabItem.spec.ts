import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatTabItem from '~/components/chat/ChatTabItem.vue'
import type { ChatChannelResponse, ChatTab } from '~/types/chat'

/**
 * CMP-261113-0110: GROUP_DM の表示名は 相手名 → 明示名 → 導出名 → 未命名文言 の順で決める。
 * 名前未設定でも "#<id>" や "null" を出さない。
 */

function makeTab(over: { name: string | null; derivedName: string | null }): ChatTab {
  const channel: ChatChannelResponse = {
    id: 77,
    identity: { channelType: 'GROUP_DM', teamId: null, organizationId: null },
    meta: { name: over.name, iconKey: null, description: null },
    settings: { isPrivate: true, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: null, createdAt: null, updatedAt: null },
    memberCount: 3,
    dmPartner: null,
    derivedName: over.derivedName,
    viewer: { unreadCount: 0, isMuted: false, isPinned: false, category: null, role: null },
  }
  return { id: 'tab-1', channelId: 77, channel, createdAt: Date.now() }
}

describe('ChatTabItem.vue（表示名）', () => {
  it('名前が null のとき導出名を表示する', async () => {
    const wrapper = await mountSuspended(ChatTabItem, {
      props: { tab: makeTab({ name: null, derivedName: '花子、太郎' }), isActive: false },
    })
    expect(wrapper.text()).toContain('花子、太郎')
    expect(wrapper.text()).not.toContain('#77')
  })

  it('名前が空白のみのとき導出名を表示する', async () => {
    const wrapper = await mountSuspended(ChatTabItem, {
      props: { tab: makeTab({ name: '  ', derivedName: '花子、太郎' }), isActive: false },
    })
    expect(wrapper.text()).toContain('花子、太郎')
  })

  it('明示名があれば導出名より優先する', async () => {
    const wrapper = await mountSuspended(ChatTabItem, {
      props: { tab: makeTab({ name: '作戦会議', derivedName: '花子、太郎' }), isActive: false },
    })
    expect(wrapper.text()).toContain('作戦会議')
    expect(wrapper.text()).not.toContain('花子、太郎')
  })

  it('名前も導出名も無いとき未命名文言を表示し、#id や null を出さない', async () => {
    const wrapper = await mountSuspended(ChatTabItem, {
      props: { tab: makeTab({ name: null, derivedName: null }), isActive: false },
    })
    const unnamed = useNuxtApp().$i18n.t('chat.unnamedChannel')
    expect(unnamed).not.toBe('chat.unnamedChannel')
    expect(wrapper.text()).toContain(unnamed)
    expect(wrapper.text()).not.toContain('#77')
    expect(wrapper.text()).not.toContain('null')
  })
})
