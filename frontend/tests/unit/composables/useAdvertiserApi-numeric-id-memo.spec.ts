import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrganizationNumericId } from '~/composables/useOrganizationNumericId'
import { useAdvertiserApi } from '~/composables/useAdvertiserApi'

/**
 * CMP-261022-0234: 同一 slug の組織 numericId 解決は composable 内で 1 回だけ行う（ページ内の
 * 複数 API 呼び出しで組織詳細 API を重複して引かない）。失敗は保持せず、再試行で再解決できる。
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
  advertiserRequest.mockReset().mockResolvedValue({ data: [] })
})
afterEach(() => vi.unstubAllGlobals())

describe('組織 numericId 解決のメモ化', () => {
  it('同一 slug で複数 API を呼んでも組織詳細 API は 1 回だけ', async () => {
    const api = useAdvertiserApi()
    await api.getCreditLimitRequests('ORGANIZATION', 'org-slug')
    await api.createCreditLimitRequest('ORGANIZATION', 'org-slug', { requestedLimit: 1, reason: 'x' } as never)
    await api.getCreditLimitRequests('ORGANIZATION', 'org-slug')

    expect(getOrganization).toHaveBeenCalledTimes(1)
    expect(advertiserRequest).toHaveBeenCalledTimes(3)
    for (const [, options] of advertiserRequest.mock.calls) {
      expect(options.params.organizationId).toBe('632')
    }
  })

  it('並行して呼んでも同一 slug の解決は 1 回', async () => {
    const api = useAdvertiserApi()
    await Promise.all([
      api.getCreditLimitRequests('ORGANIZATION', 'org-slug'),
      api.getAccount('ORGANIZATION', 'org-slug'),
    ])
    expect(getOrganization).toHaveBeenCalledTimes(1)
  })

  it('別の slug は別々に解決する', async () => {
    const api = useAdvertiserApi()
    await api.getAccount('ORGANIZATION', 'org-a')
    await api.getAccount('ORGANIZATION', 'org-b')
    expect(getOrganization).toHaveBeenCalledTimes(2)
  })

  it('解決に失敗した結果は保持せず、再試行で再解決する', async () => {
    const api = useAdvertiserApi()
    getOrganization.mockRejectedValueOnce(new Error('boom'))
    await expect(api.getAccount('ORGANIZATION', 'org-slug')).rejects.toThrow('boom')

    await api.getAccount('ORGANIZATION', 'org-slug')
    expect(getOrganization).toHaveBeenCalledTimes(2)
    expect(advertiserRequest).toHaveBeenCalledTimes(1)
  })

  it('数値 ID は解決を呼ばない', async () => {
    const api = useAdvertiserApi()
    await api.getAccount('ORGANIZATION', '632')
    expect(getOrganization).not.toHaveBeenCalled()
  })
})
