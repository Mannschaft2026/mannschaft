import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatChannelHeader from '~/components/chat/message-panel/ChatChannelHeader.vue'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261113-0110 ALIC-1: 空白の無い長い参加者名がヘッダー行を押し広げ、
 * 右側の人数表示を画面外へ出していた。名前側は縮められ、右の操作群は縮まないこと。
 */

const LONG_NAME =
  'Donaudampfschifffahrtskapitaenmwhjghwe, Hochgeschwindigkeitszugbegleitermwhjghwe, Weitereteilnehmer +2'

function makeChannel(): ChatChannelResponse {
  return {
    id: 77,
    identity: { channelType: 'GROUP_DM', teamId: null, organizationId: null },
    meta: { name: LONG_NAME, iconKey: null, description: null },
    settings: { isPrivate: true, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: null, sourceId: null },
    audit: { createdBy: null, createdAt: null, updatedAt: null },
    memberCount: 4,
    dmPartner: null,
    derivedName: null,
    viewer: { unreadCount: 0, isMuted: false, isPinned: false, category: null, role: null },
  }
}

describe('ChatChannelHeader.vue（長い名前のオーバーフロー）', () => {
  it('名前の入れ物が縮められる（min-w-0 flex-1）', async () => {
    const wrapper = await mountSuspended(ChatChannelHeader, { props: { channel: makeChannel() } })
    const container = wrapper.find('h3').element.parentElement as HTMLElement
    expect(container.classList.contains('min-w-0')).toBe(true)
    expect(container.classList.contains('flex-1')).toBe(true)
  })

  it('h3 は truncate で全文を title に持つ', async () => {
    const wrapper = await mountSuspended(ChatChannelHeader, { props: { channel: makeChannel() } })
    const h3 = wrapper.find('h3')
    expect(h3.classes()).toContain('truncate')
    expect(h3.attributes('title')).toBe(LONG_NAME)
  })

  it('右の操作群は shrink-0', async () => {
    const wrapper = await mountSuspended(ChatChannelHeader, { props: { channel: makeChannel() } })
    const right = wrapper.find('.ml-auto')
    expect(right.classes()).toContain('shrink-0')
  })
})
