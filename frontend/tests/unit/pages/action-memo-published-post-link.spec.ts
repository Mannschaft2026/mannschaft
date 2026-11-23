import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ActionMemoPage from '~/pages/action-memo/index.vue'
import type { ActionMemo } from '~/types/actionMemo'

/**
 * CMP-261123-1201: 行動メモ画面（index）から公開済み個人投稿へ辿れる（ページ結線）。
 *
 * AC-1 公開直後に戻った画面にリンク（公開応答の ID）/ AC-2 公開済みの日（過去日選択含む）を再訪するとリンク /
 * AC-3 未公開日はリンクなし / AC-5 選択日を URL の ?date= に同期し、戻ると同じ日を開く /
 * AC-11 チーム投稿へはリンクしない
 *
 * 契約: ActionMemoPublishedPostLink を選択日メモ一覧の見出し付近に置く
 *   （リンク data-testid="action-memo-published-post-link"、href=/timeline/{id}）。
 *   選択日が変わったら router.replace で query.date を YYYY-MM-DD に更新する。
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
  publishDaily: vi.fn(),
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

const PAST = '2026-01-10'
const OTHER_PAST = '2026-01-09'
const LINK = '[data-testid="action-memo-published-post-link"]'

function makeMemo(overrides: Partial<ActionMemo> = {}): ActionMemo {
  return {
    id: 100,
    memoDate: PAST,
    content: 'memo',
    mood: null,
    relatedTodoId: null,
    timelinePostId: null,
    teamTimelinePostId: null,
    tags: [],
    createdAt: `${PAST}T08:00:00`,
    category: 'OTHER',
    durationMinutes: null,
    progressRate: null,
    completesTodo: false,
    postedTeamId: null,
    organizationId: null,
    orgVisibility: null,
    ...overrides,
  }
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
  apiMock.fetchAvailableTeams.mockResolvedValue([])
  apiMock.fetchAvailableOrgs.mockResolvedValue([])
  apiMock.getTags.mockResolvedValue([])
  window.localStorage?.clear()
})

async function mountAt(date?: string) {
  const w = await mountSuspended(ActionMemoPage, { route: date ? `/action-memo?date=${date}` : '/action-memo' })
  await flushPromises()
  return w
}

describe('行動メモ画面: 公開済み個人投稿への導線', () => {
  it('AC-2: 公開済みの過去日を ?date= で開くと /timeline/{個人投稿ID} へのリンクが出る', async () => {
    apiMock.fetchMemos.mockImplementation(async ({ date }: { date: string }) => ({
      data: date === PAST ? [makeMemo({ id: 1, timelinePostId: 321 })] : [],
      total: 1,
    }))
    const w = await mountAt(PAST)
    expect(apiMock.fetchMemos).toHaveBeenCalledWith(expect.objectContaining({ date: PAST }))
    const link = w.find(LINK)
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toBe('/timeline/321')
  })

  it('AC-3: 未公開日（メモはあるが timelinePostId が全て null）ではリンクが出ない', async () => {
    apiMock.fetchMemos.mockResolvedValue({ data: [makeMemo({ id: 1 })], total: 1 })
    const w = await mountAt(PAST)
    expect(w.find(LINK).exists()).toBe(false)
  })

  it('AC-3: メモが0件の日ではリンクが出ない', async () => {
    const w = await mountAt(PAST)
    expect(w.find(LINK).exists()).toBe(false)
  })

  it('AC-11: チーム投稿 ID（teamTimelinePostId）だけのメモではリンクが出ない', async () => {
    apiMock.fetchMemos.mockResolvedValue({
      data: [makeMemo({ id: 1, teamTimelinePostId: 4242, postedTeamId: 7 })],
      total: 1,
    })
    const w = await mountAt(PAST)
    expect(w.find(LINK).exists()).toBe(false)
    expect(w.html()).not.toContain('/timeline/4242')
  })

  it('AC-4: 公開後に追加したメモ（null）が混在する日でも既存の個人投稿 ID へリンクする', async () => {
    apiMock.fetchMemos.mockResolvedValue({
      data: [makeMemo({ id: 1, timelinePostId: null }), makeMemo({ id: 2, timelinePostId: 555 })],
      total: 2,
    })
    const w = await mountAt(PAST)
    expect(w.get(LINK).attributes('href')).toBe('/timeline/555')
  })

  it('AC-1/13: 公開直後に戻ってきて、メモの再取得が失敗していても公開応答の ID でリンクが出る', async () => {
    apiMock.publishDaily.mockResolvedValue({ timelinePostId: 900, memoCount: 1, memoDate: PAST })
    // 公開直後の再取得も、戻った画面のマウント時取得も失敗する状況
    apiMock.fetchMemos.mockRejectedValue(Object.assign(new Error('network'), { status: 500 }))
    const store = useActionMemoStore(useNuxtApp().$pinia as never)
    await store.publishDaily({ memoDate: PAST })

    const w = await mountAt(PAST)
    expect(w.get(LINK).attributes('href')).toBe('/timeline/900')
  })

  it('AC-5: 選択日を変えると URL の ?date= が同期され、戻って開き直すと同じ日になる', async () => {
    const router = useNuxtApp().$router as ReturnType<typeof useRouter>
    const w = await mountAt(PAST)
    ;(w.vm as unknown as { selectedDate: Date }).selectedDate = new Date(`${OTHER_PAST}T12:00:00`)
    await flushPromises()
    expect(router.currentRoute.value.query.date).toBe(OTHER_PAST)
    expect(apiMock.fetchMemos).toHaveBeenLastCalledWith(expect.objectContaining({ date: OTHER_PAST }))
  })
})
