import { describe, it, expect, beforeEach, vi } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { useActionMemoStore } from '~/stores/useActionMemoStore'
import type { ActionMemo } from '~/types/actionMemo'

/**
 * CMP-261123-1201 P2-2 / AC-13: 公開応答の個人投稿 ID を store が対象日について保持する。
 *
 * 契約: getter `store.publishedPostId(date: string): number | null`
 *   = 公開応答で得た ID（publishedPostIdOverrides[date]）が最優先、無ければ当日メモの非 null な timelinePostId の最初の値、無ければ null。
 * publishDaily 後の再取得が失敗しても（fetchMemosForDate は例外を投げず旧メモを保持する）、公開応答の ID でリンクできること。
 */

const apiMock = {
  fetchMemos: vi.fn(),
  publishDaily: vi.fn(),
  getSettings: vi.fn(),
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 } }),
}))

const DATE = '2026-11-20'

function makeMemo(overrides: Partial<ActionMemo> = {}): ActionMemo {
  return {
    id: 100,
    memoDate: DATE,
    content: 'memo',
    mood: null,
    relatedTodoId: null,
    timelinePostId: null,
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
    ...overrides,
  }
}

describe('useActionMemoStore: 公開した個人投稿 ID（CMP-261123-1201）', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    apiMock.fetchMemos.mockReset()
    apiMock.publishDaily.mockReset()
    window.localStorage?.clear()
  })

  it('メモが無い日は null', () => {
    const store = useActionMemoStore()
    expect(store.publishedPostId(DATE)).toBeNull()
  })

  it('AC-2: 取得したメモの非 null な timelinePostId を返す（null 混在は既存 ID）', async () => {
    const store = useActionMemoStore()
    apiMock.fetchMemos.mockResolvedValueOnce({
      data: [makeMemo({ id: 1 }), makeMemo({ id: 2, timelinePostId: 555 })],
      total: 2,
    })
    await store.fetchMemosForDate(DATE)
    expect(store.publishedPostId(DATE)).toBe(555)
  })

  it('AC-3: 未公開日・別の日のメモだけなら null（日付で絞る）', async () => {
    const store = useActionMemoStore()
    apiMock.fetchMemos.mockResolvedValueOnce({
      data: [makeMemo({ id: 1, memoDate: '2026-11-19', timelinePostId: 555 }), makeMemo({ id: 2 })],
      total: 2,
    })
    await store.fetchMemosForDate(DATE)
    expect(store.publishedPostId(DATE)).toBeNull()
  })

  it('AC-1: 公開成功 → 再取得成功なら、再取得したメモの新 ID を返す', async () => {
    const store = useActionMemoStore()
    apiMock.publishDaily.mockResolvedValueOnce({ timelinePostId: 900, memoCount: 1, memoDate: DATE })
    apiMock.fetchMemos.mockResolvedValueOnce({ data: [makeMemo({ id: 1, timelinePostId: 900 })], total: 1 })
    const res = await store.publishDaily({ memoDate: DATE })
    expect(res.timelinePostId).toBe(900)
    expect(store.publishedPostId(DATE)).toBe(900)
  })

  it('AC-13: 公開成功後の再取得が失敗しても、公開応答の ID でリンクできる（旧メモ保持・例外は出さない）', async () => {
    const store = useActionMemoStore()
    store.memos = [makeMemo({ id: 1 })]
    apiMock.publishDaily.mockResolvedValueOnce({ timelinePostId: 900, memoCount: 1, memoDate: DATE })
    apiMock.fetchMemos.mockRejectedValueOnce(Object.assign(new Error('network'), { status: 500 }))

    const res = await store.publishDaily({ memoDate: DATE })

    expect(res.timelinePostId).toBe(900)
    expect(store.memos[0]!.timelinePostId).toBeNull() // 再取得できていない（旧メモのまま）
    expect(store.publishedPostId(DATE)).toBe(900)
  })

  it('AC-4/13: 再公開後の再取得が失敗したら、旧メモの旧 ID ではなく公開応答の新 ID を返す', async () => {
    const store = useActionMemoStore()
    store.memos = [makeMemo({ id: 1, timelinePostId: 800 })]
    apiMock.publishDaily.mockResolvedValueOnce({ timelinePostId: 900, memoCount: 1, memoDate: DATE })
    apiMock.fetchMemos.mockRejectedValueOnce(Object.assign(new Error('network'), { status: 500 }))

    await store.publishDaily({ memoDate: DATE })

    expect(store.publishedPostId(DATE)).toBe(900)
  })

  it('AC-13: 公開応答の ID は対象日だけに効く（別の日には出ない）', async () => {
    const store = useActionMemoStore()
    apiMock.publishDaily.mockResolvedValueOnce({ timelinePostId: 900, memoCount: 1, memoDate: DATE })
    apiMock.fetchMemos.mockRejectedValueOnce(Object.assign(new Error('network'), { status: 500 }))
    await store.publishDaily({ memoDate: DATE })
    expect(store.publishedPostId('2026-11-19')).toBeNull()
  })

  it('P2: clear() で公開応答の ID とメモを破棄し、別の利用者に前の利用者の投稿リンクが出ない', async () => {
    const store = useActionMemoStore()
    store.memos = [makeMemo({ id: 1 })]
    apiMock.publishDaily.mockResolvedValueOnce({ timelinePostId: 900, memoCount: 1, memoDate: DATE })
    apiMock.fetchMemos.mockRejectedValueOnce(Object.assign(new Error('network'), { status: 500 }))
    await store.publishDaily({ memoDate: DATE })
    expect(store.publishedPostId(DATE)).toBe(900)

    store.clear()

    expect(store.publishedPostId(DATE)).toBeNull()
    expect(store.memos).toEqual([])
  })

  it('公開 API 自体が失敗したら ID を保持せず、例外は呼び出し側へ再送出される', async () => {
    const store = useActionMemoStore()
    apiMock.publishDaily.mockRejectedValueOnce(Object.assign(new Error('boom'), { status: 500 }))
    await expect(store.publishDaily({ memoDate: DATE })).rejects.toThrow()
    expect(store.publishedPostId(DATE)).toBeNull()
  })
})
