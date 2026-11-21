import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ActionMemoInput from '~/components/action-memo/ActionMemoInput.vue'
import ActionMemoPage from '~/pages/action-memo/index.vue'

/**
 * CMP-261120-0201: 行動メモ画面で選んだ値が作成リクエストに載ること（ページ → Input → store → API の結線）。
 *
 * AC-1 カテゴリ / AC-2 所要時間 / AC-3 進捗率 / AC-4 TODO 完了 / AC-5 チーム投稿先（作成 API に項目が無いため
 * 作成成功後に publish-to-team を呼ぶ。WORK のみ）/ AC-6 送信成功後に所要時間・進捗率・TODO 完了をリセット。
 */

const apiMock = {
  createMemo: vi.fn(),
  fetchMemos: vi.fn(),
  getMemo: vi.fn(),
  updateMemo: vi.fn(),
  deleteMemo: vi.fn(),
  linkTodo: vi.fn(),
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  fetchAvailableTeams: vi.fn(),
  fetchAvailableOrgs: vi.fn(),
  publishToTeam: vi.fn(),
  getTags: vi.fn(),
  getMoodStats: vi.fn(),
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/composables/useOfflineQueue', () => ({
  useOfflineQueue: () => ({ enqueue: vi.fn(), count: async () => 0, flushQueue: vi.fn(async () => []) }),
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42, timezone: 'Asia/Tokyo' }, isAuthenticated: true, loadFromStorage: vi.fn(), logout: vi.fn() }),
}))

const created = {
  id: 501,
  memoDate: '2026-11-20',
  content: 'x',
  mood: null,
  relatedTodoId: null,
  timelinePostId: null,
  teamTimelinePostId: null,
  tags: [],
  createdAt: '2026-11-20T08:00:00',
  category: 'WORK',
  durationMinutes: 30,
  progressRate: null,
  completesTodo: false,
  postedTeamId: null,
  organizationId: null,
  orgVisibility: null,
}

beforeEach(() => {
  setActivePinia(createPinia())
  for (const fn of Object.values(apiMock)) fn.mockReset()
  apiMock.getSettings.mockResolvedValue({
    moodEnabled: false,
    defaultPostTeamId: null,
    defaultCategory: 'OTHER',
    reminderEnabled: false,
    reminderTime: null,
  })
  apiMock.fetchMemos.mockResolvedValue({ data: [], total: 0 })
  apiMock.fetchAvailableTeams.mockResolvedValue([{ id: 7, name: 'チームA', isDefault: false }])
  apiMock.fetchAvailableOrgs.mockResolvedValue([])
  apiMock.getTags.mockResolvedValue([])
  apiMock.createMemo.mockResolvedValue(created)
  apiMock.publishToTeam.mockResolvedValue(undefined)
  window.localStorage?.clear()
})

async function mountPage() {
  const w = await mountSuspended(ActionMemoPage)
  await flushPromises()
  return w
}

async function submit(w: Awaited<ReturnType<typeof mountPage>>, text: string) {
  await w.get('[data-testid="action-memo-input-textarea"]').setValue(text)
  await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
  await flushPromises()
}

describe('行動メモ画面: 選択値の作成リクエスト反映（ページ結線）', () => {
  it('AC-1/2: 選んだカテゴリと所要時間が createMemo に載る', async () => {
    const w = await mountPage()
    await w.get('[data-testid="category-selector-private"]').trigger('click')
    await w.get('[data-testid="duration-quick-30"]').trigger('click')
    await submit(w, '散歩')
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.createMemo).toHaveBeenCalledWith(
      expect.objectContaining({ content: '散歩', category: 'PRIVATE', durationMinutes: 30 }),
    )
  })

  it('AC-5: WORK + チーム選択で作成成功後に publishToTeam(メモID, teamId) が呼ばれる', async () => {
    const w = await mountPage()
    await w.get('[data-testid="category-selector-work"]').trigger('click')
    await flushPromises()
    await submit(w, 'レビュー')
    expect(apiMock.createMemo).toHaveBeenCalledWith(expect.objectContaining({ category: 'WORK' }))
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledWith(501, expect.objectContaining({ teamId: 7 }))
  })

  it('AC-5: WORK 以外ではチーム投稿しない', async () => {
    const w = await mountPage()
    await w.get('[data-testid="category-selector-private"]').trigger('click')
    await submit(w, '私用')
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).not.toHaveBeenCalled()
  })

  it('AC-6: 送信成功後に所要時間がリセットされ、次の投稿に持ち越されない', async () => {
    const w = await mountPage()
    await w.get('[data-testid="duration-quick-60"]').trigger('click')
    await submit(w, '1件目')
    await submit(w, '2件目')
    expect(apiMock.createMemo).toHaveBeenCalledTimes(2)
    const second = apiMock.createMemo.mock.calls[1]![0] as { durationMinutes?: number | null }
    expect(second.durationMinutes ?? null).toBeNull()
  })
})

describe('ActionMemoInput: extras の作成リクエスト反映', () => {
  it('AC-3/4: 進捗率・TODO 完了・カテゴリ・所要時間が createMemo に載る', async () => {
    const w = await mountSuspended(ActionMemoInput, {
      props: {
        extras: { category: 'WORK', durationMinutes: 45, progressRate: 60, completesTodo: true, teamId: null },
      },
    })
    await w.get('[data-testid="action-memo-input-textarea"]').setValue('実装')
    await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.createMemo).toHaveBeenCalledWith(
      expect.objectContaining({
        category: 'WORK',
        durationMinutes: 45,
        progressRate: 60,
        completesTodo: true,
      }),
    )
    expect(apiMock.publishToTeam).not.toHaveBeenCalled()
  })
})
