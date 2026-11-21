import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setActivePinia, createPinia } from 'pinia'
import ActionMemoInput from '~/components/action-memo/ActionMemoInput.vue'
import { useActionMemoStore } from '~/stores/useActionMemoStore'
import { useOfflineQueue } from '~/composables/useOfflineQueue'

/**
 * CMP-261120-0201 差し戻し: 送信値の固定 / オフライン時の公開要求保持 / 公開失敗時の再試行状態。
 */

const apiMock = {
  createMemo: vi.fn(),
  fetchMemos: vi.fn(),
  publishToTeam: vi.fn(),
  getSettings: vi.fn(),
  getTags: vi.fn(),
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
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
  durationMinutes: null,
  progressRate: null,
  completesTodo: false,
  postedTeamId: null,
  organizationId: null,
  orgVisibility: null,
}

let onLineSpy: ReturnType<typeof vi.spyOn> | null = null

beforeEach(async () => {
  setActivePinia(createPinia())
  for (const fn of Object.values(apiMock)) fn.mockReset()
  apiMock.getSettings.mockResolvedValue({ moodEnabled: false, defaultPostTeamId: null, defaultCategory: 'OTHER', reminderEnabled: false, reminderTime: null })
  apiMock.fetchMemos.mockResolvedValue({ data: [], total: 0 })
  apiMock.getTags.mockResolvedValue([])
  apiMock.createMemo.mockResolvedValue(created)
  apiMock.publishToTeam.mockResolvedValue(undefined)
  window.localStorage?.clear()
  await useOfflineQueue().clearAll()
})

afterEach(() => {
  onLineSpy?.mockRestore()
  onLineSpy = null
})

const WORK = { category: 'WORK' as const, teamId: 7 }

async function mountInput() {
  return mountSuspended(ActionMemoInput, { props: { extras: WORK } })
}

async function submit(w: Awaited<ReturnType<typeof mountInput>>, text?: string) {
  if (text !== undefined) await w.get('[data-testid="action-memo-input-textarea"]').setValue(text)
  await w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
  await flushPromises()
}

describe('1: 送信開始時に値を固定する', () => {
  it('応答待ち中に投稿先・カテゴリを変えても、公開は送信時の teamId で行われる', async () => {
    let resolveCreate: (v: typeof created) => void = () => {}
    apiMock.createMemo.mockReturnValue(new Promise((r) => { resolveCreate = r }))
    const w = await mountInput()
    await w.get('[data-testid="action-memo-input-textarea"]').setValue('固定')
    void w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    await w.setProps({ extras: { category: 'PRIVATE', teamId: 8 } })
    resolveCreate(created)
    await flushPromises()
    expect(apiMock.createMemo).toHaveBeenCalledWith(expect.objectContaining({ category: 'WORK' }))
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledWith(501, expect.objectContaining({ teamId: 7 }))
  })
})

describe('2: オフライン時の公開要求の保持', () => {
  it('オフラインで WORK+投稿先を選ぶと、復帰後の flush で実 ID に対して publish-to-team が呼ばれる', async () => {
    onLineSpy = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const w = await mountInput()
    await submit(w, 'オフライン')
    expect(apiMock.createMemo).not.toHaveBeenCalled()
    expect(apiMock.publishToTeam).not.toHaveBeenCalled()

    onLineSpy.mockReturnValue(true)
    apiMock.createMemo.mockResolvedValue({ ...created, id: 900 })
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    const sent = apiMock.createMemo.mock.calls[0]![0] as Record<string, unknown>
    expect(Object.keys(sent).some((k) => /publish|team/i.test(k))).toBe(false)
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledWith(900, expect.objectContaining({ teamId: 7 }))
  })

  it('後方互換: 公開要求を持たない既存キュー項目は作成のみで publish しない', async () => {
    await useOfflineQueue().enqueue({ content: '旧', category: 'WORK' }, -5)
    apiMock.createMemo.mockResolvedValue({ ...created, id: 901 })
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).not.toHaveBeenCalled()
  })
})

describe('3: 公開失敗時は成功扱いにしない', () => {
  it('publishToTeam が false のとき入力を保持し、失敗文言を出し、再送信は公開だけ再試行する', async () => {
    apiMock.publishToTeam.mockRejectedValueOnce(new Error('boom'))
    const w = await mountInput()
    await submit(w, '公開失敗')
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect((w.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('公開失敗')
    expect(w.find('[data-testid="action-memo-input-publish-failed"]').exists()).toBe(true)
    expect(w.emitted('created')).toBeUndefined()

    await submit(w)
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(2)
    expect(apiMock.publishToTeam).toHaveBeenLastCalledWith(501, expect.objectContaining({ teamId: 7 }))
    expect((w.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('')
    expect(w.find('[data-testid="action-memo-input-publish-failed"]').exists()).toBe(false)
    expect(w.emitted('created')).toHaveLength(1)
  })
})
