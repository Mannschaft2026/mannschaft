// CMP-261107-1833 PR3（再招待依頼の受け手側）: 招待管理画面の「再招待の依頼」欄の状態と操作の受け入れテスト（試練）。
// 陣立て書 .claude/handoffs/cmp1833-pr3-plan-20261123.md の j・i。API は
// GET /api/v1/{teams|organizations}/{slug}/invite-reissue-requests?page&size、POST …/{id}/reissue、POST …/{id}/decline。
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import type { Ref } from 'vue'

const mocks = vi.hoisted(() => ({
  api: vi.fn(),
  success: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
  error: vi.fn(),
  handleApiError: vi.fn(),
}))

vi.mock('~/composables/useApi', () => ({ useApi: () => mocks.api }))
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => ({ success: mocks.success, info: mocks.info, warn: mocks.warn, error: mocks.error }),
}))
vi.mock('~/composables/useErrorHandler', () => ({
  useErrorHandler: () => ({ handleApiError: mocks.handleApiError, getFieldErrors: () => ({}) }),
}))
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<Record<string, unknown>>()
  const t = (key: string, params?: Record<string, unknown>) => (params ? key + JSON.stringify(params) : key)
  return { ...actual, useNuxtApp: () => ({ $i18n: { t } }) }
})

// 未実装の間も import 解決エラーでファイルごと落とさず、各 AC の期待で red にする。
const loaders = import.meta.glob('./useInviteReissueRequests.ts')
const loader = loaders['./useInviteReissueRequests.ts']
type Mod = {
  useInviteReissueRequests: (scopeType: Ref<'team' | 'organization'>, slug: Ref<string>) => {
    requests: { value: Array<{ id: string; requesterDisplayName: string }> }
    loading: { value: boolean }
    error: { value: boolean }
    forbidden: { value: boolean }
    totalElements: { value: number }
    hasMore: { value: boolean }
    processingIds: { value: string[] }
    init: () => Promise<void>
    loadMore: () => Promise<void>
    retry: () => Promise<void>
    reissue: (id: string) => Promise<void>
    decline: (id: string) => Promise<void>
  }
  canManageInviteReissue: (roleName: string | null | undefined, permissions: string[]) => boolean
}
const mod = (loader ? await loader() : null) as Mod | null
function use(scope: 'team' | 'organization' = 'team', slug = 'my-team') {
  expect(mod, 'app/composables/useInviteReissueRequests.ts が未実装').not.toBeNull()
  return mod!.useInviteReissueRequests(ref(scope), ref(slug))
}

function row(id: string, name = '依頼者' + id) {
  return { id, requesterDisplayName: name, requesterAvatarUrl: null, requestedAt: '2026-11-20T00:00:00Z', roleName: 'MEMBER' }
}
function page(content: ReturnType<typeof row>[], totalElements = content.length) {
  return { data: { content, totalElements, totalPages: Math.ceil(totalElements / 20), number: 0, size: 20 } }
}
function apiError(code: string, statusCode: number) {
  return Object.assign(new Error(code), { statusCode, status: statusCode, data: { error: { code } } })
}
const LIST = '/api/v1/teams/my-team/invite-reissue-requests'
function notified(key: string) {
  const all = [...mocks.success.mock.calls, ...mocks.info.mock.calls, ...mocks.warn.mock.calls, ...mocks.error.mock.calls]
  return all.some(args => args.some(a => typeof a === 'string' && a.includes(key)))
}
function posts(suffix: string) {
  return mocks.api.mock.calls.filter(([url, opts]) => String(url).endsWith(suffix) && (opts as { method?: string } | undefined)?.method === 'POST')
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('useInviteReissueRequests: 一覧（L1・L2）', () => {
  it('IRR-01: init は古い順の 1 ページ目（page=0&size=20）を取得する（TEAM）', async () => {
    mocks.api.mockResolvedValueOnce(page([row('a'), row('b')]))
    const m = use()
    await m.init()
    expect(mocks.api).toHaveBeenCalledWith(LIST + '?page=0&size=20')
    expect(m.requests.value.map(r => r.id)).toEqual(['a', 'b'])
    expect(m.totalElements.value).toBe(2)
    expect(m.loading.value).toBe(false)
  })

  it('IRR-02: ORGANIZATION は /organizations/{slug} のパスを使う', async () => {
    mocks.api.mockResolvedValueOnce(page([]))
    const m = use('organization', 'my-org')
    await m.init()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/organizations/my-org/invite-reissue-requests?page=0&size=20')
  })

  it('IRR-03 L1: 0 件なら空・hasMore=false・エラーでない', async () => {
    mocks.api.mockResolvedValueOnce(page([]))
    const m = use()
    await m.init()
    expect(m.requests.value).toEqual([])
    expect(m.totalElements.value).toBe(0)
    expect(m.hasMore.value).toBe(false)
    expect(m.error.value).toBe(false)
  })

  it('IRR-04 L2: 21 件なら hasMore=true、loadMore で 2 ページ目を追記（重複は除く）', async () => {
    mocks.api.mockResolvedValueOnce(page(Array.from({ length: 20 }, (_, i) => row('r' + i)), 21))
    const m = use()
    await m.init()
    expect(m.hasMore.value).toBe(true)
    mocks.api.mockResolvedValueOnce({ data: { content: [row('r19'), row('r20')], totalElements: 21, totalPages: 2, number: 1, size: 20 } })
    await m.loadMore()
    expect(mocks.api).toHaveBeenLastCalledWith(LIST + '?page=1&size=20')
    expect(m.requests.value).toHaveLength(21)
    expect(m.hasMore.value).toBe(false)
  })

  it('IRR-05: 取得失敗は error=true（空と区別）、retry で取り直す', async () => {
    mocks.api.mockRejectedValueOnce(apiError('COMMON_999', 500))
    const m = use()
    await m.init()
    expect(m.error.value).toBe(true)
    mocks.api.mockResolvedValueOnce(page([row('a')]))
    await m.retry()
    expect(m.error.value).toBe(false)
    expect(m.requests.value).toHaveLength(1)
  })
})

describe('useInviteReissueRequests: 再招待・見送り（I・D・C3・エラー）', () => {
  async function loaded() {
    mocks.api.mockResolvedValueOnce(page([row('a', '山田'), row('b')]))
    const m = use()
    await m.init()
    return m
  }

  it('IRR-10: 再招待は POST …/{id}/reissue、成功（REISSUED）で表示名入りの完了通知を出し、一覧を取り直す', async () => {
    const m = await loaded()
    mocks.api.mockResolvedValueOnce({ data: { status: 'REISSUED', expiresAt: '2026-11-30T00:00:00Z' } })
    mocks.api.mockResolvedValueOnce(page([row('b')]))
    await m.reissue('a')
    expect(mocks.api).toHaveBeenCalledWith(LIST + '/a/reissue', expect.objectContaining({ method: 'POST' }))
    expect(notified('inviteReissue.reissued')).toBe(true)
    expect(notified('山田')).toBe(true)
    expect(m.requests.value.map(r => r.id)).toEqual(['b'])
  })

  it('IRR-11: ALREADY_MEMBER は「既に参加済み」を知らせる', async () => {
    const m = await loaded()
    mocks.api.mockResolvedValueOnce({ data: { status: 'ALREADY_MEMBER' } })
    mocks.api.mockResolvedValueOnce(page([row('b')]))
    await m.reissue('a')
    expect(notified('inviteReissue.already_member')).toBe(true)
  })

  it('IRR-12: 見送りは POST …/{id}/decline、成功で通知して取り直す', async () => {
    const m = await loaded()
    mocks.api.mockResolvedValueOnce({ data: { status: 'REISSUE_DECLINED' } })
    mocks.api.mockResolvedValueOnce(page([row('b')]))
    await m.decline('a')
    expect(mocks.api).toHaveBeenCalledWith(LIST + '/a/decline', expect.objectContaining({ method: 'POST' }))
    expect(notified('inviteReissue.declined')).toBe(true)
    expect(m.requests.value.map(r => r.id)).toEqual(['b'])
  })

  it('IRR-13 C3: 処理中の行への二重押しは送らない（再招待も見送りも 1 回だけ）', async () => {
    const m = await loaded()
    let resolve!: (v: unknown) => void
    mocks.api.mockImplementationOnce(() => new Promise(r => { resolve = r }))
    const first = m.reissue('a')
    expect(m.processingIds.value).toContain('a')
    await m.reissue('a')
    await m.decline('a')
    mocks.api.mockResolvedValueOnce(page([row('b')]))
    resolve({ data: { status: 'REISSUED' } })
    await first
    expect(posts('/a/reissue')).toHaveLength(1)
    expect(posts('/a/decline')).toHaveLength(0)
    expect(m.processingIds.value).not.toContain('a')
  })

  it('IRR-14: 409 ROLE_017 は「対応済み」を知らせて一覧を取り直す（汎用エラー処理に回さない）', async () => {
    const m = await loaded()
    mocks.api.mockRejectedValueOnce(apiError('ROLE_017', 409))
    mocks.api.mockResolvedValueOnce(page([row('b')]))
    await m.reissue('a')
    expect(notified('inviteReissue.already_handled')).toBe(true)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
    expect(m.requests.value.map(r => r.id)).toEqual(['b'])
  })

  it('IRR-15: 409 ROLE_018 は依頼者が参加できない旨と見送りの案内を出す', async () => {
    const m = await loaded()
    mocks.api.mockRejectedValueOnce(apiError('ROLE_018', 409))
    await m.reissue('a')
    expect(notified('inviteReissue.requester_unavailable')).toBe(true)
    expect(mocks.handleApiError).not.toHaveBeenCalled()
  })

  it('IRR-16: 500 ROLE_019 は有効化の失敗をトーストで知らせる', async () => {
    const m = await loaded()
    mocks.api.mockRejectedValueOnce(apiError('ROLE_019', 500))
    mocks.api.mockResolvedValue(page([row('a')]))
    await m.reissue('a')
    expect(notified('inviteReissue.activation_failed')).toBe(true)
  })

  it('IRR-17 R4: 403 なら欄を閉じる（forbidden=true・一覧を空に）', async () => {
    const m = await loaded()
    mocks.api.mockRejectedValueOnce(apiError('COMMON_002', 403))
    await m.reissue('a')
    expect(m.forbidden.value).toBe(true)
    expect(m.requests.value).toEqual([])
    expect(notified('inviteReissue.forbidden')).toBe(true)
  })

  it('IRR-18 R4: 一覧の取得が 403 でも欄を閉じる', async () => {
    mocks.api.mockRejectedValueOnce(apiError('COMMON_002', 403))
    const m = use()
    await m.init()
    expect(m.forbidden.value).toBe(true)
    expect(m.error.value).toBe(false)
    // P2-5: 欄を黙って消さず、理由を表示する
    expect(notified('inviteReissue.forbidden')).toBe(true)
  })
})

describe('canManageInviteReissue（R4: 欄を出すのは招待権限者だけ）', () => {
  it('IRR-20: ADMIN は可、INVITE_MEMBERS を持つ DEPUTY_ADMIN は可、持たない DEPUTY・MEMBER・未確定は不可', () => {
    expect(mod, 'app/composables/useInviteReissueRequests.ts が未実装').not.toBeNull()
    const can = mod!.canManageInviteReissue
    expect(can('ADMIN', [])).toBe(true)
    expect(can('DEPUTY_ADMIN', ['INVITE_MEMBERS'])).toBe(true)
    expect(can('DEPUTY_ADMIN', ['MANAGE_SCHEDULES'])).toBe(false)
    expect(can('MEMBER', ['INVITE_MEMBERS'])).toBe(false)
    expect(can('SUPPORTER', [])).toBe(false)
    expect(can(null, [])).toBe(false)
  })
})
