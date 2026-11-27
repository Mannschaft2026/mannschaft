import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import TeamTimetablePage from '~/pages/teams/[slug]/timetable.vue'

/**
 * CMP-261125-1108 代表子ページ: チームの時間割（pages/teams/[slug]/timetable.vue）。
 *
 *   SNF-C1 主 API（時間割一覧）が 403 → markIfDenied 経由で notFound を立て、エラートーストを出さない
 *   SNF-C2 主 API が 404 → 同上
 *   SNF-C3 主 API が 500 → 従来どおり timetable.load_error のトーストを出し、notFound は立てない
 */

const list = vi.fn()
const notifyError = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {}, path: '/teams/team-a/timetable' }))
mockNuxtImport('useTimetableApi', () => () => ({
  list,
  listTerms: vi.fn().mockResolvedValue([]),
  remove: vi.fn(),
  getWeekly: vi.fn().mockResolvedValue(null),
  listChanges: vi.fn().mockResolvedValue([]),
  createTeamTerm: vi.fn(),
  createOrgTerm: vi.fn(),
  deleteTerm: vi.fn(),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: computed(() => false),
  can: () => false,
  loadPermissions: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))

type ScopeNotFoundApi = {
  notFound: { value: boolean }
  markIfDenied: (err: unknown) => boolean
  reset: () => void
}
/** 新規 composable は試練時点で未実装のため、ページ側の red 理由が見えるよう動的 import で取り込む。 */
const SCOPE_NOT_FOUND_MODULE = '~/composables/useScopeNotFound'
async function scopeNotFound(): Promise<ScopeNotFoundApi> {
  const mod = (await import(/* @vite-ignore */ SCOPE_NOT_FOUND_MODULE)) as {
    useScopeNotFound: () => ScopeNotFoundApi
  }
  return mod.useScopeNotFound()
}

// 子ページが立てた状態を次のケースへ持ち越さない
afterEach(async () => {
  ;(await scopeNotFound()).reset()
})

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  list.mockReset()
  notifyError.mockReset()
})

describe('pages/teams/[slug]/timetable.vue — 非所属・権限なし（CMP-261125-1108）', () => {
  for (const status of [403, 404] as const) {
    it(`SNF-C${status === 403 ? 1 : 2}: 時間割一覧が ${status} なら notFound を立て、エラートーストを出さない`, async () => {
      list.mockRejectedValueOnce({ statusCode: status, response: { status } })

      const wrapper = await mountSuspended(TeamTimetablePage)
      await flush()

      expect(notifyError).not.toHaveBeenCalled()
      expect((await scopeNotFound()).notFound.value).toBe(true)
      wrapper.unmount()
    })
  }

  it('SNF-C3: 時間割一覧が 500 なら従来どおりエラートーストを出し、notFound は立てない', async () => {
    list.mockRejectedValueOnce({ statusCode: 500, response: { status: 500 } })

    const wrapper = await mountSuspended(TeamTimetablePage)
    await flush()

    expect(notifyError).toHaveBeenCalledTimes(1)
    expect((await scopeNotFound()).notFound.value).toBe(false)
    wrapper.unmount()
  })
})
