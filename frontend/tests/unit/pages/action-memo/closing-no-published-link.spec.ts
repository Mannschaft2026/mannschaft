import { describe, it, expect, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ActionMemoClosingPage from '~/pages/action-memo/closing.vue'

/**
 * CMP-261123-1201 AC-14: 「今日を締める」画面（closing.vue）には公開済み投稿へのリンクを置かない。
 * 公開後は /action-memo（index）へ戻る導線なので、リンクは index 側だけに出す
 * （締め画面に置くと、未送信の入力を持ったまま別ページへ出る経路が増える）。
 * 公開済みメモ（timelinePostId あり）がある日でも出さないことを固定する（回帰ガード。実装前から green になる）。
 */

vi.mock('~/composables/useTodoApi', () => ({
  useTodoApi: () => ({ getMyTodos: vi.fn(async () => ({ data: [] })) }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => vi.fn() }))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }),
}))

const publishedMemo = {
  id: 1,
  memoDate: '2026-11-20',
  content: '公開済み',
  mood: null,
  relatedTodoId: null,
  timelinePostId: 321,
  teamTimelinePostId: null,
  tags: [],
  createdAt: '2026-11-20T08:00:00',
  category: 'OTHER',
  durationMinutes: null,
  progressRate: null,
  completesTodo: false,
  postedTeamId: null,
  organizationId: null,
  orgVisibility: null,
}

const actionMemoStoreStub = {
  currentDayMemos: () => [publishedMemo],
  publishedPostId: () => 321,
  error: null,
  loading: false,
  settings: { defaultPostTeamId: null },
  fetchSettings: vi.fn(async () => {}),
  fetchMemosForDate: vi.fn(async () => {}),
  fetchAvailableTeams: vi.fn(async () => {}),
}
mockNuxtImport('useActionMemoStore', () => () => actionMemoStoreStub)
mockNuxtImport('useAuthStore', () => () => ({ user: { timezone: 'Asia/Tokyo' } }))

describe('pages/action-memo/closing.vue — 公開済み投稿リンクを置かない', () => {
  it('AC-14: 公開済みメモがあっても /timeline/{id} へのリンクと公開済み注記を出さない', async () => {
    const wrapper = await mountSuspended(ActionMemoClosingPage)
    for (let i = 0; i < 5; i++) await Promise.resolve()
    expect(wrapper.find('[data-testid="action-memo-published-post-link"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="action-memo-published-post"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('/timeline/321')
  })
})
