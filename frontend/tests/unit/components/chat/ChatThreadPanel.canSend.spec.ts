import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ChatThreadPanel from '~/components/chat/ChatThreadPanel.vue'

/**
 * CMP-261118-0900（設計書 F08.7.1 01_communication.md §10.5 / AC-P28・AC-P31）:
 * ChatThreadPanel に canSend（既定 true）を追加する。false のときは返信入力・送信ボタンを出さず、
 * Enter キーでも sendReply（= sendMessage）を呼ばない。未指定の既存呼び出し元は従来どおり送信できる。
 */

const mocks = vi.hoisted(() => ({
  getThread: vi.fn(),
  sendMessage: vi.fn(),
  showError: vi.fn(),
}))

mockNuxtImport('useChatApi', () => () => ({
  getThread: mocks.getThread,
  sendMessage: mocks.sendMessage,
  addReaction: vi.fn(),
  removeReaction: vi.fn(),
  togglePin: vi.fn(),
  editMessage: vi.fn(),
  deleteMessage: vi.fn(),
  bookmarkMessage: vi.fn(),
  removeBookmark: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ showError: mocks.showError, showSuccess: vi.fn() }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 }, isAuthenticated: true, loadFromStorage: vi.fn(), logout: vi.fn() }),
}))

const stubs = {
  ChatMessageBubble: true,
  ChatMessageEditDialog: true,
  LoadingBounce: true,
  InputText: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
  },
  Button: {
    props: ['label', 'disabled', 'loading', 'icon'],
    emits: ['click'],
    template: '<button :disabled="disabled" @click="$emit(\'click\')">{{ label }}</button>',
  },
}

const root = {
  id: 500, channelId: 7, body: '親メッセージ', replyCount: 0, sender: { id: 1, displayName: '代表' },
  reactionSummary: {}, myReactions: [], isPinned: false, isDeleted: false, isBookmarked: false,
}

async function mountPanel(canSend?: boolean) {
  const props: Record<string, unknown> = { rootMessageId: 500, channelId: 7 }
  if (canSend !== undefined) props.canSend = canSend
  const wrapper = await mountSuspended(ChatThreadPanel, {
    props: props as { rootMessageId: number, channelId: number },
    global: { stubs, mocks: { $t: (key: string) => key } },
  })
  await flushPromises()
  return wrapper
}

/** 入力欄があれば本文を入れて Enter を押す。入力欄が無ければ何もしない。 */
async function typeAndPressEnter(wrapper: Awaited<ReturnType<typeof mountPanel>>) {
  const inputs = wrapper.findAll('input')
  for (const input of inputs) {
    await input.setValue('送ってはならない返信')
    await input.trigger('keydown', { key: 'Enter' })
  }
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getThread.mockResolvedValue({ data: { root, messages: [], nextCursor: null, hasMore: false } })
  mocks.sendMessage.mockResolvedValue({ data: {} })
})

describe('ChatThreadPanel の送信可否（canSend）', () => {
  it('AC-P28: canSend=false では Enter を押しても sendMessage を呼ばず、返信入力・送信ボタンを出さない', async () => {
    const wrapper = await mountPanel(false)
    await typeAndPressEnter(wrapper)

    expect(mocks.sendMessage).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="thread-reply-input"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="thread-reply-send"]').exists()).toBe(false)
    // 閲覧（スレッドの取得）は従来どおり行う
    expect(mocks.getThread).toHaveBeenCalledWith(500, undefined)
    expect(wrapper.text()).toContain('親メッセージ')
  })

  it('AC-P28: canSend=true では Enter で返信を送信する', async () => {
    const wrapper = await mountPanel(true)
    expect(wrapper.find('[data-testid="thread-reply-input"]').exists()).toBe(true)
    await typeAndPressEnter(wrapper)
    expect(mocks.sendMessage).toHaveBeenCalledWith(7, '送ってはならない返信', 500)
  })

  it('AC-P31: canSend 未指定（既存の呼び出し元）は従来どおり返信入力が出て Enter で送信できる', async () => {
    const wrapper = await mountPanel()
    expect(wrapper.find('[data-testid="thread-reply-input"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="thread-reply-send"]').exists()).toBe(true)
    await typeAndPressEnter(wrapper)
    expect(mocks.sendMessage).toHaveBeenCalledTimes(1)
  })
})
