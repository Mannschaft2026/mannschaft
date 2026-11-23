import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import ChatMessagePanel from '~/components/chat/ChatMessagePanel.vue'
import type { ChatChannelResponse } from '~/types/chat'

/**
 * CMP-261118-0900（設計書 F08.7.1 01_communication.md §10.5 / AC-P28・AC-P31）:
 * ChatMessagePanel に canSend（既定 true）を追加する。false のときは送信入力を出さず（または無効にし）、
 * Enter キーでも sendMessage を呼ばない。開いたスレッド（ChatThreadPanel）にも canSend を伝える。
 * 未指定の既存呼び出し元（チーム・組織チャット、主催側連絡スペース等）は従来どおり送信できる。
 */

const mocks = vi.hoisted(() => ({
  getMessages: vi.fn(),
  sendMessage: vi.fn(),
}))

mockNuxtImport('useChatApi', () => () => ({
  getMessages: mocks.getMessages,
  sendMessage: mocks.sendMessage,
  sendTyping: vi.fn(),
  addReaction: vi.fn(),
  removeReaction: vi.fn(),
  togglePin: vi.fn(),
  editMessage: vi.fn(),
  deleteMessage: vi.fn(),
  bookmarkMessage: vi.fn(),
  removeBookmark: vi.fn(),
  getActiveThreads: vi.fn().mockResolvedValue({ data: [], meta: { nextCursor: null, hasMore: false } }),
  subscribeChannel: vi.fn(),
  unsubscribeChannel: vi.fn(),
  wsConnectionFailed: ref(false),
  wsUnauthenticated: ref(false),
  isChannelDenied: () => false,
}))
mockNuxtImport('useChatChannelEventListener', () => () => undefined)
vi.mock('~/composables/chat/useChatReadSync', () => ({
  useChatReadSync: () => ({ markChannelRead: vi.fn().mockResolvedValue(undefined), onChannelRead: () => () => undefined }),
}))
vi.mock('~/composables/chat/useChatMembershipInvite', () => ({
  useChatMembershipInvite: () => ({ joinInvite: vi.fn(), declineInvite: vi.fn() }),
}))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: false, loadFromStorage: vi.fn(), logout: vi.fn() }),
}))

/** 送信入力は実物（ChatMessageInput）を描画し、その中の PrimeVue 部品だけ素朴な要素に差し替える */
const stubs = {
  ChatMessageInput: false,
  Textarea: {
    props: ['modelValue', 'disabled'],
    emits: ['update:modelValue'],
    template: '<textarea :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  },
  Button: {
    props: ['disabled', 'loading', 'icon', 'label'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

function makeChannel(): ChatChannelResponse {
  return {
    id: 201,
    identity: { channelType: 'TOURNAMENT', teamId: null, organizationId: null },
    meta: { name: '大会全体チャット', iconKey: null, description: null },
    settings: { isPrivate: true, isInquiryChannel: false, isArchived: false, version: 1 },
    lastMessage: { lastMessageAt: null, lastMessagePreview: null },
    source: { sourceType: 'TOURNAMENT', sourceId: 5 },
    audit: { createdBy: 1, createdAt: null, updatedAt: null },
    memberCount: 0,
    dmPartner: null,
    derivedName: null,
    viewer: null,
  } as unknown as ChatChannelResponse
}

async function mountPanel(canSend?: boolean) {
  const props: Record<string, unknown> = { channel: makeChannel() }
  if (canSend !== undefined) props.canSend = canSend
  const wrapper = await mountSuspended(ChatMessagePanel, {
    props: props as { channel: ChatChannelResponse },
    shallow: true,
    global: { stubs },
  })
  await flushPromises()
  return wrapper
}

/** 送信入力があれば本文を入れて Enter を押す。無ければ何もしない。 */
async function typeAndPressEnter(wrapper: Awaited<ReturnType<typeof mountPanel>>) {
  for (const textarea of wrapper.findAll('textarea')) {
    if ((textarea.element as HTMLTextAreaElement).disabled) continue
    await textarea.setValue('送ってはならない本文')
    await textarea.trigger('keydown', { key: 'Enter' })
  }
  await flushPromises()
}

/** メッセージ一覧の「返信」からスレッドパネルを開く */
async function openThread(wrapper: Awaited<ReturnType<typeof mountPanel>>) {
  wrapper.findComponent({ name: 'ChatMessageList' }).vm.$emit('reply', 900)
  await flushPromises()
  return wrapper.findComponent({ name: 'ChatThreadPanel' })
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getMessages.mockResolvedValue({ data: [], meta: { nextCursor: null, hasMore: false } })
  mocks.sendMessage.mockResolvedValue({ data: {} })
})

describe('ChatMessagePanel の送信可否（canSend）', () => {
  it('AC-P28: canSend=false では Enter を押しても sendMessage を呼ばず、有効な送信入力を出さない', async () => {
    const wrapper = await mountPanel(false)
    await typeAndPressEnter(wrapper)

    expect(mocks.sendMessage).not.toHaveBeenCalled()
    const input = wrapper.find('[data-testid="team-chat-input"]')
    const inputUsable = input.exists() && !(input.element as HTMLTextAreaElement).disabled
    expect(inputUsable).toBe(false)
    // 閲覧（メッセージ取得）は従来どおり行う
    expect(mocks.getMessages).toHaveBeenCalled()
  })

  it('AC-P28: canSend=false はスレッドパネル（ChatThreadPanel）にも canSend=false を伝える', async () => {
    const wrapper = await mountPanel(false)
    const threadPanel = await openThread(wrapper)
    expect(threadPanel.exists()).toBe(true)
    expect(threadPanel.props('canSend')).toBe(false)
  })

  it('AC-P28: canSend=true では Enter で送信でき、スレッドパネルにも送信可を伝える', async () => {
    const wrapper = await mountPanel(true)
    await typeAndPressEnter(wrapper)
    expect(mocks.sendMessage).toHaveBeenCalledWith(201, '送ってはならない本文', undefined)
    const threadPanel = await openThread(wrapper)
    expect(threadPanel.props('canSend')).not.toBe(false)
  })

  it('AC-P31: canSend 未指定（既存の呼び出し元）は従来どおり送信入力が出て Enter で送信でき、スレッドも送信可のまま', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="team-chat-input"]').exists()).toBe(true)
    await typeAndPressEnter(wrapper)
    expect(mocks.sendMessage).toHaveBeenCalledTimes(1)
    const threadPanel = await openThread(wrapper)
    expect(threadPanel.props('canSend')).not.toBe(false)
  })
})
