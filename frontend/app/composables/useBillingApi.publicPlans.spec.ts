import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * 公開価格ページ（/pricing）用 getPublicPlans の呼び先（設計書 05_billing_center.md §公開価格）。
 * 認証不要の `GET /api/v1/public/billing/plans?scopeKind=...` を叩くこと。
 */
const mockApi = vi.fn()
vi.mock('~/composables/useApi', () => ({ useApi: () => mockApi }))

beforeEach(() => { mockApi.mockReset() })

describe('useBillingApi.getPublicPlans', () => {
  it.each(['USER', 'TEAM', 'ORG'] as const)('%s を scopeKind クエリで公開エンドポイントへ送る', async (scopeKind) => {
    mockApi.mockResolvedValueOnce({ data: { scopeKind, plans: [], addons: [] } })
    const { useBillingApi } = await import('./useBillingApi')

    const res = await useBillingApi().getPublicPlans(scopeKind)

    expect(mockApi).toHaveBeenCalledTimes(1)
    expect(mockApi.mock.calls[0]![0]).toBe(`/api/v1/public/billing/plans?scopeKind=${scopeKind}`)
    expect(res.data.scopeKind).toBe(scopeKind)
  })
})
