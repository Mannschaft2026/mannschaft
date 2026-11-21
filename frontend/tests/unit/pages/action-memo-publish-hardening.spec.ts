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
  getMemo: vi.fn(),
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

describe('2b: オフライン同期で作成成功・公開失敗', () => {
  it('公開のみのキュー項目を残し、残件とエラーを保持し、次回 flush は公開だけ再試行する', async () => {
    onLineSpy = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)
    const w = await mountInput()
    await submit(w, 'オフライン公開失敗')
    onLineSpy.mockReturnValue(true)
    apiMock.createMemo.mockResolvedValue({ ...created, id: 900 })
    apiMock.publishToTeam.mockRejectedValueOnce(new Error('boom'))
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(store.offlineQueueCount).toBe(1)
    expect(store.error).not.toBeNull()

    await store.flushOfflineQueue()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(2)
    expect(apiMock.publishToTeam).toHaveBeenLastCalledWith(900, expect.objectContaining({ teamId: 7 }))
    expect(store.offlineQueueCount).toBe(0)
  })
})

describe('2c: 複数項目の flush で先の公開が失敗し後が成功', () => {
  it('失敗時点の error/lastError を最後まで保持し、失敗分は公開のみ項目として残る', async () => {
    const q = useOfflineQueue()
    await q.enqueue({ content: 'a', category: 'WORK' }, -1, 7)
    await q.enqueue({ content: 'b', category: 'WORK' }, -2, 7)
    apiMock.createMemo.mockResolvedValueOnce({ ...created, id: 900 }).mockResolvedValueOnce({ ...created, id: 901 })
    apiMock.publishToTeam.mockRejectedValueOnce(new Error('boom')).mockResolvedValueOnce(undefined)
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(2)
    expect(store.error).toBe('action_memo.error.publish_failed')
    expect(store.lastError).toBe('PUBLISH_FAILED')
    expect(store.offlineQueueCount).toBe(1)
  })
})

describe('3b: 公開失敗後に画面を離れて戻っても重複作成しない', () => {
  it('再マウントで本文と公開待ちが復元され、再送信は公開のみ再試行する', async () => {
    apiMock.publishToTeam.mockRejectedValueOnce(new Error('boom'))
    const w1 = await mountInput()
    await submit(w1, '離脱前')
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    w1.unmount()

    const w2 = await mountInput()
    await flushPromises()
    expect((w2.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('離脱前')
    expect(w2.find('[data-testid="action-memo-input-publish-failed"]').exists()).toBe(true)
    await submit(w2)
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(2)
    expect(apiMock.publishToTeam).toHaveBeenLastCalledWith(501, expect.objectContaining({ teamId: 7 }))
    expect((w2.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('')
  })
})

describe('6: 送信中・公開待ちの入力ロック', () => {
  it('応答待ち中は textarea が readonly（編集が消えない）', async () => {
    let resolveCreate: (v: typeof created) => void = () => {}
    apiMock.createMemo.mockReturnValue(new Promise((r) => { resolveCreate = r }))
    const w = await mountInput()
    await w.get('[data-testid="action-memo-input-textarea"]').setValue('送信中')
    void w.get('[data-testid="action-memo-input-submit"]').trigger('submit')
    await flushPromises()
    expect(w.get('[data-testid="action-memo-input-textarea"]').attributes('readonly')).toBeDefined()
    resolveCreate(created)
    await flushPromises()
    expect(w.get('[data-testid="action-memo-input-textarea"]').attributes('readonly')).toBeUndefined()
  })

  it('公開待ちの間は textarea が disabled、「投稿せず閉じる」でメモは再作成せず入力と下書きをクリアする', async () => {
    apiMock.publishToTeam.mockRejectedValueOnce(new Error('boom'))
    const w = await mountInput()
    await submit(w, '公開待ち')
    expect(w.get('[data-testid="action-memo-input-textarea"]').attributes('disabled')).toBeDefined()
    expect(w.emitted('pending-change')?.at(-1)).toEqual([true])
    await w.get('[data-testid="action-memo-input-publish-giveup"]').trigger('click')
    await flushPromises()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(1)
    expect((w.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('')
    expect(w.find('[data-testid="action-memo-input-publish-failed"]').exists()).toBe(false)
    expect(window.localStorage.getItem('action-memo-draft-42')).toBeNull()
    expect(w.emitted('pending-change')?.at(-1)).toEqual([false])
  })
})

// BE の ErrorResponse は { error: { code, message } }。ofetch の FetchError と同じく data / response._data の両方に載る
function httpError(status: number, code: string) {
  const body = { error: { code, message: 'x' } }
  return Object.assign(new Error('http ' + status), { status, statusCode: status, data: body, response: { status, _data: body } })
}

describe('8: 公開のみ再試行の恒久失敗(404/403)は項目を片付けて後続を止めない', () => {
  it('404 の公開のみ項目は削除され、PUBLISH_FAILED が残り、後続の作成は処理される', async () => {
    const q = useOfflineQueue()
    await q.enqueuePublishOnly(900, 7)
    await q.enqueue({ content: '後続' }, -2)
    apiMock.publishToTeam.mockRejectedValue(httpError(404, 'ACTION_MEMO_001'))
    apiMock.createMemo.mockResolvedValue({ ...created, id: 902 })
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(store.offlineQueueCount).toBe(0)
    expect(store.error).toBe('action_memo.error.publish_failed')
    expect(store.lastError).toBe('PUBLISH_FAILED')
  })

  it('5xx は従来どおり項目を残して停止する', async () => {
    const q = useOfflineQueue()
    await q.enqueuePublishOnly(900, 7)
    await q.enqueue({ content: '後続' }, -2)
    apiMock.publishToTeam.mockRejectedValue(httpError(503, 'COMMON_503'))
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(apiMock.createMemo).not.toHaveBeenCalled()
    expect(store.offlineQueueCount).toBe(2)
    expect(store.error).toBe('action_memo.error.publish_failed')
  })
})

describe('7: 公開のみ再試行が既投稿(ACTION_MEMO_ALREADY_POSTED)で失敗', () => {
  const alreadyPosted = () => httpError(409, 'ACTION_MEMO_016')

  it('指定チームに投稿済みなら完了扱いで項目を削除し、flush が止まり続けない', async () => {
    await useOfflineQueue().enqueuePublishOnly(900, 7)
    apiMock.publishToTeam.mockRejectedValue(alreadyPosted())
    apiMock.getMemo.mockResolvedValue({ ...created, id: 900, postedTeamId: 7 })
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(store.offlineQueueCount).toBe(0)
    expect(store.error).toBeNull()
    await store.flushOfflineQueue()
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(1)
  })

  it('別チームに投稿済みならエラーを残しつつ項目は削除する（永久ブロックしない）', async () => {
    await useOfflineQueue().enqueuePublishOnly(900, 7)
    apiMock.publishToTeam.mockRejectedValue(alreadyPosted())
    apiMock.getMemo.mockResolvedValue({ ...created, id: 900, postedTeamId: 8 })
    const store = useActionMemoStore()
    await store.flushOfflineQueue()
    expect(store.offlineQueueCount).toBe(0)
    expect(store.error).toBe('action_memo.error.publish_failed')
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
    // 公開失敗は「メモの保存失敗」として扱わない（保存済みの入力欄文言と矛盾させない）
    const store = useActionMemoStore()
    expect(store.error).toBe('action_memo.error.publish_failed')
    expect(store.lastError).toBe('PUBLISH_FAILED')

    await submit(w)
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.publishToTeam).toHaveBeenCalledTimes(2)
    expect(apiMock.publishToTeam).toHaveBeenLastCalledWith(501, expect.objectContaining({ teamId: 7 }))
    expect((w.get('[data-testid="action-memo-input-textarea"]').element as HTMLTextAreaElement).value).toBe('')
    expect(w.find('[data-testid="action-memo-input-publish-failed"]').exists()).toBe(false)
    expect(w.emitted('created')).toHaveLength(1)
  })
})
