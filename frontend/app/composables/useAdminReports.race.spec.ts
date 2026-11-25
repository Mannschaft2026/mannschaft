// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAdminReports } from './useAdminReports'

const { api } = vi.hoisted(() => ({
  api: { getReports: vi.fn(), getReportStats: vi.fn() },
}))
vi.mock('~/composables/useAdminReportApi', () => ({ useAdminReportApi: () => api }))
vi.mock('~/composables/useNotification', () => ({ useNotification: () => ({ success: vi.fn(), error: vi.fn() }) }))
vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))

function deferred<T>() {
  let resolve!: (v: T) => void
  const promise = new Promise<T>((r) => { resolve = r })
  return { promise, resolve }
}
const reportsOf = (id: number, total: number) => ({ data: [{ id }], meta: { total } })

beforeEach(() => {
  vi.resetAllMocks()
  api.getReportStats.mockResolvedValue({ data: { pending: 0 } })
})

describe('useAdminReports の要求番号', () => {
  it('古い応答が後から届いても、最新の絞り込みの一覧と総件数を上書きしない', async () => {
    const first = deferred<ReturnType<typeof reportsOf>>()
    const second = deferred<ReturnType<typeof reportsOf>>()
    api.getReports.mockImplementation(({ status }: { status?: string }) =>
      status === 'RESOLVED' ? second.promise : first.promise)
    const r = useAdminReports()

    r.statusFilter.value = 'PENDING'
    const p1 = r.onStatusFilterChange()
    r.statusFilter.value = 'RESOLVED'
    const p2 = r.onStatusFilterChange()

    second.resolve(reportsOf(2, 7))
    await new Promise(res => setTimeout(res, 0))
    first.resolve(reportsOf(1, 99))
    await new Promise(res => setTimeout(res, 0))
    await Promise.all([p1, p2])

    expect(r.reports.value.map(x => x.id)).toEqual([2])
    expect(r.totalRecords.value).toBe(7)
    expect(r.loading.value).toBe(false)
  })
})
