import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import { useActionMemoStore } from '~/stores/useActionMemoStore'
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

describe('下書きに選択値も保存・復元する', () => {
  const KEY = 'action-memo-draft-42'

  it('store: 本文+選択値を保存し、本文だけ読む loadDraft と選択値を読む loadDraftExtras で復元できる', () => {
    const store = useActionMemoStore()
    store.saveDraft(42, '仕事のメモ', { category: 'WORK', teamId: 7, durationMinutes: 30 })
    expect(store.loadDraft(42)).toBe('仕事のメモ')
    expect(store.loadDraftExtras(42)).toEqual(expect.objectContaining({ category: 'WORK', teamId: 7, durationMinutes: 30 }))
    store.saveDraft(42, '')
    expect(store.loadDraft(42)).toBe('')
    expect(store.loadDraftExtras(42)).toBeNull()
  })

  it('store: 既存の文字列だけの下書きは本文のみとして読む（後方互換）', () => {
    window.localStorage.setItem(KEY, '旧形式の下書き')
    const store = useActionMemoStore()
    expect(store.loadDraft(42)).toBe('旧形式の下書き')
    expect(store.loadDraftExtras(42)).toBeNull()
  })

  it('ページ: 復元した選択値（仕事・チーム・所要時間）で送信される', async () => {
    window.localStorage.setItem(
      KEY,
      JSON.stringify({ v: 1, content: '復元メモ', extras: { category: 'WORK', teamId: 7, durationMinutes: 30 } }),
    )
    const w = await mountPage()
    expect((w.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('復元メモ')
    await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.createMemo).toHaveBeenCalledWith(
      expect.objectContaining({ content: '復元メモ', category: 'WORK', durationMinutes: 30 }),
    )
    expect(apiMock.publishToTeam).toHaveBeenCalledWith(501, expect.objectContaining({ teamId: 7 }))
  })

  it('ページ: 下書きの「投稿なし」(teamId=null) は設定の既定チームで上書きされない', async () => {
    apiMock.getSettings.mockResolvedValue({
      moodEnabled: false,
      defaultPostTeamId: 7,
      defaultCategory: 'OTHER',
      reminderEnabled: false,
      reminderTime: null,
    })
    window.localStorage.setItem(
      KEY,
      JSON.stringify({ v: 1, content: '非公開のつもり', extras: { category: 'WORK', teamId: null } }),
    )
    const w = await mountPage()
    await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).not.toHaveBeenCalled()
  })

  it('ページ: 現在の投稿先候補に無い teamId は捨てる（カテゴリ等は復元）', async () => {
    window.localStorage.setItem(
      KEY,
      JSON.stringify({ v: 1, content: '別チーム', extras: { category: 'WORK', teamId: 99 } }),
    )
    const w = await mountPage()
    await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    expect(apiMock.createMemo).toHaveBeenCalledWith(expect.objectContaining({ category: 'WORK' }))
    expect(apiMock.publishToTeam).not.toHaveBeenCalled()
  })

  it('ページ: 選択してから入力すると、下書きに選択値が保存される', async () => {
    const w = await mountPage()
    await w.get('[data-testid="category-selector-work"]').trigger('click')
    await w.get('[data-testid="duration-quick-30"]').trigger('click')
    await w.get('[data-testid="action-memo-input-textarea"]').setValue('下書き保存確認')
    await new Promise((r) => setTimeout(r, 1300))
    const store = useActionMemoStore()
    expect(store.loadDraft(42)).toBe('下書き保存確認')
    expect(store.loadDraftExtras(42)).toEqual(expect.objectContaining({ category: 'WORK', durationMinutes: 30 }))
  })

  it('ページ: 送信成功で下書きがクリアされる', async () => {
    window.localStorage.setItem(KEY, JSON.stringify({ v: 1, content: '消える', extras: { category: 'WORK' } }))
    const w = await mountPage()
    await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    expect(window.localStorage.getItem(KEY)).toBeNull()
  })
})
