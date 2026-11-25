import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrganizationNumericId } from '~/composables/useOrganizationNumericId'
import { useAdvertiserApi } from '~/composables/useAdvertiserApi'

/**
 * CMP-261022-0234（ε1 G1）組織の広告主登録は slug ではなく数値 ID を organizationId に載せる。
 * BE の organizationId は Long。slug を送ると URI に /organizations/ を含まないため
 * 別スコープへ誤解決されうる（CMP-261125-2124）。数値 ID はそのまま使い、解決は行わない。
 */

const { getOrganization, advertiserRequest } = vi.hoisted(() => ({
  getOrganization: vi.fn(),
  advertiserRequest: vi.fn(),
}))

vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ getOrganization }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => advertiserRequest }))

beforeEach(() => {
  vi.stubGlobal('useOrganizationApi', () => ({ getOrganization }))
  vi.stubGlobal('useOrganizationNumericId', useOrganizationNumericId)
  vi.stubGlobal('useApi', () => advertiserRequest)
  getOrganization.mockReset().mockResolvedValue({ data: { id: 'org-slug', numericId: 632 } })
  advertiserRequest.mockReset().mockResolvedValue({ data: {} })
})
afterEach(() => vi.unstubAllGlobals())

const body = { companyName: '会社', contactEmail: 'ads@example.com', billingMethod: 'STRIPE' as const }

describe('useAdvertiserApi.register の organizationId は数値 ID', () => {
  it('slug を numericId へ解決して送る（slug を送らない）', async () => {
    await useAdvertiserApi().register('org-slug', body)

    expect(getOrganization).toHaveBeenCalledExactlyOnceWith('org-slug')
    expect(advertiserRequest).toHaveBeenCalledExactlyOnceWith('/api/v1/advertiser/register', {
      method: 'POST',
      params: { organizationId: '632' },
      body,
    })
  })

  it('数値 ID はそのまま送り、組織解決 API を呼ばない', async () => {
    await useAdvertiserApi().register('632', body)

    expect(getOrganization).not.toHaveBeenCalled()
    expect(advertiserRequest.mock.calls[0]![1].params).toEqual({ organizationId: '632' })
  })

  it('組織解決に失敗したら広告 API を呼ばず伝播する', async () => {
    const error = new Error('organization lookup failed')
    getOrganization.mockRejectedValue(error)

    await expect(useAdvertiserApi().register('org-slug', body)).rejects.toBe(error)
    expect(advertiserRequest).not.toHaveBeenCalled()
  })
})
