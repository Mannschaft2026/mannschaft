import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamJobEditPage from '~/pages/teams/[slug]/jobs/[jobId]/edit.vue'

/**
 * CMP-261127-1118: チーム配下の求人編集 `pages/teams/[slug]/jobs/[jobId]/edit.vue` の主 API（求人取得）が
 * 403/404（見えない求人・不存在）のとき、シェルの ScopeNotFound へ寄せてトーストを出さない（FE 規約 §12）。
 *
 *   JE-001 404 なら markIfDenied へ渡し、エラートーストを出さない
 *   JE-002（対照）5xx はエラートーストで通知する
 */

const getJob = vi.fn()
vi.mock('~/composables/jobs/useJobPostingApi', () => ({
  useJobPostingApi: () => ({ getJob, updateJob: vi.fn() }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { slug: '1', jobId: '10' } }))

const markIfDenied = vi.hoisted(() => vi.fn((e: unknown) => {
  const status = (e as { statusCode?: number } | null)?.statusCode
  return status === 403 || status === 404
}))
mockNuxtImport('useScopeNotFound', () => () => ({ markIfDenied, markDenied: vi.fn() }))

describe('pages/teams/[slug]/jobs/[jobId]/edit.vue — 見えない求人・不存在', () => {
  beforeEach(() => {
    getJob.mockReset()
    notificationMock.error.mockClear()
    markIfDenied.mockClear()
  })

  it('JE-001: 404 なら ScopeNotFound へ寄せ、トーストを出さない', async () => {
    const notFound = Object.assign(new Error('Not Found'), { statusCode: 404 })
    getJob.mockRejectedValue(notFound)
    await mountSuspended(TeamJobEditPage)
    await flushMicrotasks()

    expect(markIfDenied).toHaveBeenCalledWith(notFound)
    expect(notificationMock.error).not.toHaveBeenCalled()
  })

  it('JE-002（対照）: 5xx はエラートーストで通知する', async () => {
    getJob.mockRejectedValue(Object.assign(new Error('server error'), { statusCode: 500 }))
    await mountSuspended(TeamJobEditPage)
    await flushMicrotasks()

    expect(notificationMock.error).toHaveBeenCalled()
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
