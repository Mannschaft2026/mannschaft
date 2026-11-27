import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import WorkerJobDetailPage from '~/pages/jobs/[id]/index.vue'

/**
 * CMP-261127-1118: Worker 向け求人詳細 `pages/jobs/[id]/index.vue` の「見つかりません」表示。
 *
 * BE は見えない求人を不在と同一の 404 `JOB_NOT_FOUND` で返す（存在秘匿）。画面は 403/404 を
 * 「見つかりません」に寄せ、エラートーストを出さない（トーストは 5xx・通信断の通知。FE 規約 §12）。
 * 本ページはチームシェル外のため ScopeNotFound ではなく、ページ自身の not-found 表示を使う。
 *
 *   WJD-001 404 のとき not-found 表示を出し、エラートーストを出さない
 *   WJD-002 403 でも同じ（BE の旧応答・他経路の 403 でも存在を示唆しない）
 *   WJD-003（対照）5xx はエラートーストで通知する
 */

const getJob = vi.fn()

vi.mock('~/composables/jobs/useJobPostingApi', () => ({
  useJobPostingApi: () => ({ getJob }),
}))
vi.mock('~/composables/jobs/useJobApplicationApi', () => ({
  useJobApplicationApi: () => ({ applyToJob: vi.fn() }),
}))

const notificationMock = { success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }
vi.mock('~/composables/useNotification', () => ({ useNotification: () => notificationMock }))

mockNuxtImport('useRoute', () => () => ({ params: { id: '10' } }))

describe('pages/jobs/[id]/index.vue — 見えない求人・不存在の表示', () => {
  beforeEach(() => {
    getJob.mockReset()
    notificationMock.error.mockClear()
  })

  it.each([404, 403])('WJD-001/002: %i のとき not-found 表示を出し、エラートーストを出さない', async (statusCode) => {
    getJob.mockRejectedValue(Object.assign(new Error('denied'), { statusCode }))
    const wrapper = await mountSuspended(WorkerJobDetailPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="job-detail-not-found"]').exists()).toBe(true)
    expect(notificationMock.error).not.toHaveBeenCalled()
  })

  it('WJD-003（対照）: 5xx はエラートーストで通知する', async () => {
    getJob.mockRejectedValue(Object.assign(new Error('server error'), { statusCode: 500 }))
    await mountSuspended(WorkerJobDetailPage)
    await flushMicrotasks()

    expect(notificationMock.error).toHaveBeenCalled()
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
