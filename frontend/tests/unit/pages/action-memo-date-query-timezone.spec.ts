import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ActionMemoPage from '~/pages/action-memo/index.vue'

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
  useAuthStore: () => ({ user: { id: 42, timezone: 'America/New_York' }, isAuthenticated: true, loadFromStorage: vi.fn(), logout: vi.fn() }),
}))

const PAST = '2026-01-10'
const OTHER_PAST = '2026-01-09'

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

/**
 * CMP-261123-1201 P2: ?date= の保存と復元は利用者タイムゾーンの壁時計日付で揃える。
 * 端末TZ（CI は JST/UTC 等）と利用者TZ（America/New_York）が異なっても、?date=YYYY-MM-DD で開いた日と
 * 取得・URL 同期される日付が一致する（端末の 0 時として復元すると利用者時間で前日にずれる）。
 */
describe('行動メモ画面: ?date= のタイムゾーン往復', () => {
  it('利用者TZ（America/New_York）でも ?date=2026-01-10 で開くとその日を取得し、URL も同じ日のまま', async () => {
    const router = useNuxtApp().$router as ReturnType<typeof useRouter>
    await mountAt(PAST)
    await vi.waitFor(() => expect(apiMock.fetchMemos).toHaveBeenCalled())
    const dates = apiMock.fetchMemos.mock.calls.map((c) => (c[0] as { date: string }).date)
    expect(new Set(dates)).toEqual(new Set([PAST]))
    expect(router.currentRoute.value.query.date).toBe(PAST)
  })

  it('選んだ日を ?date= に保存し、その値で開き直しても同じ日になる（往復）', async () => {
    const router = useNuxtApp().$router as ReturnType<typeof useRouter>
    const w = await mountAt(PAST)
    ;(w.vm as unknown as { selectedDate: Date }).selectedDate = new Date(`${OTHER_PAST}T12:00:00`)
    await flushPromises()
    await vi.waitFor(() => expect(router.currentRoute.value.query.date).toBe(OTHER_PAST), { timeout: 10000 })
    const saved = router.currentRoute.value.query.date as string
    apiMock.fetchMemos.mockClear()
    await mountAt(saved)
    const dates = apiMock.fetchMemos.mock.calls.map((c) => (c[0] as { date: string }).date)
    expect(new Set(dates)).toEqual(new Set([saved]))
  })
})
