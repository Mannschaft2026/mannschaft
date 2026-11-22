import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useActionMemoStore } from '~/stores/useActionMemoStore'
import { useOfflineQueue } from '~/composables/useOfflineQueue'

/**
 * CMP-261120-0201: 公開のみキュー項目の永続化に失敗しても、作成要求は完了扱い（再作成しない）にし、公開失敗をエラーに残す。
 */

const apiMock = {
  createMemo: vi.fn(),
  fetchMemos: vi.fn(),
  publishToTeam: vi.fn(),
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/composables/useOfflineQueue', async (importOriginal) => {
  const orig = await importOriginal<typeof import('~/composables/useOfflineQueue')>()
  return {
    ...orig,
    useOfflineQueue: () => ({
      ...orig.useOfflineQueue(),
      convertToPublishOnly: vi.fn().mockRejectedValue(new Error('indexeddb failed')),
    }),
  }
})

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42, timezone: 'Asia/Tokyo' }, isAuthenticated: true, loadFromStorage: vi.fn(), logout: vi.fn() }),
}))

beforeEach(async () => {
  setActivePinia(createPinia())
  for (const fn of Object.values(apiMock)) fn.mockReset()
  apiMock.fetchMemos.mockResolvedValue({ data: [], total: 0 })
  await useOfflineQueue().clearAll()
})

describe('公開のみ項目の永続化失敗', () => {
  it('作成は完了扱いで再作成されず、公開失敗がエラーとして残る', async () => {
    await useOfflineQueue().enqueue({ content: 'a', category: 'WORK' }, -1, 7)
    apiMock.createMemo.mockResolvedValue({ id: 900 })
    apiMock.publishToTeam.mockRejectedValue(new Error('boom'))
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    await store.flushOfflineQueue()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(store.error).toBe('action_memo.error.publish_failed')
    expect(store.lastError).toBe('PUBLISH_FAILED')
  })
})
