import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useOrganizationNumericId } from '~/composables/useOrganizationNumericId'
import { useAdvertiserApi } from '~/composables/useAdvertiserApi'

/**
 * CMP-261022-0234（ε1 G1/G3）組織広告 billing 系 API の送信値を固定する試練（red 先行）。
 *
 * 欠陥: billingParams が組織 slug をそのままクエリ organizationId へ流していた（performance 系
 * 4関数だけが数値解決済み）。BE の organizationId は Long のため、slug は別スコープへ誤解決
 * されるか 404 になり、登録済み組織でも dashboard が「未登録」扱いになる。
 *
 * G1: 組織スコープの billing 系全11関数が、組織詳細 API の numericId（数値 ID）を
 *     クエリ organizationId に載せ、slug を送らない。解決失敗時は広告 API を呼ばない。
 * G3: TEAM スコープはパス変数のまま（組織 resolver を呼ばず、送信値は不変）。
 */

const { getOrganization, advertiserRequest } = vi.hoisted(() => ({
  getOrganization: vi.fn(),
  advertiserRequest: vi.fn(),
}))

vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ getOrganization }),
}))
vi.mock('~/composables/useApi', () => ({ useApi: () => advertiserRequest }))

// Node 限定実行でも Nuxt の auto-import と同じ依存を使う（useOrganizationNumericId.spec と同じ流儀）
beforeEach(() => {
  vi.stubGlobal('useOrganizationApi', () => ({ getOrganization }))
  vi.stubGlobal('useOrganizationNumericId', useOrganizationNumericId)
  vi.stubGlobal('useApi', () => advertiserRequest)
})
afterEach(() => vi.unstubAllGlobals())

type AdvertiserApi = ReturnType<typeof useAdvertiserApi>
type Scope = 'ORGANIZATION' | 'TEAM'

interface BillingCase {
  name: string
  call: (api: AdvertiserApi, scope: Scope, id: string) => Promise<unknown>
  resource: string
  method?: 'PATCH' | 'POST' | 'DELETE'
  extraParams?: Record<string, unknown>
  body?: Record<string, unknown>
  blob?: boolean
}

const updateBody = { companyName: '新社名', contactEmail: 'ads@example.com' }
const scheduleBody = { frequency: 'WEEKLY', recipients: ['ads@example.com'] }
const creditBody = { requestedLimit: 500000, reason: '増額' }

const billingCases: BillingCase[] = [
  { name: 'getAccount', call: (a, s, i) => a.getAccount(s, i), resource: 'account' },
  { name: 'getOverview', call: (a, s, i) => a.getOverview(s, i), resource: 'overview' },
  {
    name: 'updateAccount',
    call: (a, s, i) => a.updateAccount(s, i, updateBody),
    resource: 'account',
    method: 'PATCH',
    body: updateBody,
  },
  {
    name: 'getInvoices',
    call: (a, s, i) => a.getInvoices(s, i, { status: 'PAID', page: 0, size: 20 }),
    resource: 'invoices',
    extraParams: { status: 'PAID', page: 0, size: 20 },
  },
  { name: 'getInvoiceDetail', call: (a, s, i) => a.getInvoiceDetail(s, i, 5), resource: 'invoices/5' },
  { name: 'downloadInvoicePdf', call: (a, s, i) => a.downloadInvoicePdf(s, i, 5), resource: 'invoices/5/pdf', blob: true },
  { name: 'getReportSchedules', call: (a, s, i) => a.getReportSchedules(s, i), resource: 'report-schedules' },
  {
    name: 'createReportSchedule',
    call: (a, s, i) => a.createReportSchedule(s, i, scheduleBody as never),
    resource: 'report-schedules',
    method: 'POST',
    body: scheduleBody,
  },
  {
    name: 'deleteReportSchedule',
    call: (a, s, i) => a.deleteReportSchedule(s, i, 9),
    resource: 'report-schedules/9',
    method: 'DELETE',
  },
  {
    name: 'createCreditLimitRequest',
    call: (a, s, i) => a.createCreditLimitRequest(s, i, creditBody as never),
    resource: 'credit-limit-requests',
    method: 'POST',
    body: creditBody,
  },
  { name: 'getCreditLimitRequests', call: (a, s, i) => a.getCreditLimitRequests(s, i), resource: 'credit-limit-requests' },
]

function expectedOptions(c: BillingCase, organizationId?: string) {
  const params = organizationId === undefined
    ? c.extraParams
    : { organizationId, ...c.extraParams }
  return {
    ...(c.method ? { method: c.method } : {}),
    ...(params ? { params } : {}),
    ...(c.body ? { body: c.body } : {}),
    ...(c.blob ? { responseType: 'blob' } : {}),
  }
}

describe('CMP-261022-0234 G1 組織広告 billing 系11関数の送信 organizationId は数値 ID', () => {
  beforeEach(() => {
    getOrganization.mockReset().mockResolvedValue({ data: { id: 'org-slug', numericId: 632 } })
    advertiserRequest.mockReset().mockResolvedValue({ data: {} })
  })

  it('G1: 対象は account/overview/update/invoices×3/report-schedules×3/credit-limit×2 の11関数', () => {
    expect(billingCases).toHaveLength(11)
  })

  it.each(billingCases.map(c => [c.name, c] as const))(
    'G1: %s は組織 slug を numericId へ解決し organizationId=632 を送る（slug を送らない）',
    async (_name, c) => {
      await c.call(useAdvertiserApi(), 'ORGANIZATION', 'org-slug')

      expect(getOrganization).toHaveBeenCalledWith('org-slug')
      expect(advertiserRequest).toHaveBeenCalledTimes(1)
      const [path, options] = advertiserRequest.mock.calls[0]!
      expect(path).toBe(`/api/v1/advertiser/${c.resource}`)
      expect(options).toEqual(expectedOptions(c, '632'))
      expect(JSON.stringify(options)).not.toContain('org-slug')
    },
  )

  it.each(billingCases.map(c => [c.name, c] as const))(
    'G1: %s は組織解決失敗を伝播し、広告 API へ slug を送らない',
    async (_name, c) => {
      const error = new Error('organization lookup failed')
      getOrganization.mockRejectedValue(error)

      await expect(c.call(useAdvertiserApi(), 'ORGANIZATION', 'org-slug')).rejects.toBe(error)
      expect(advertiserRequest).not.toHaveBeenCalled()
    },
  )
})

describe('CMP-261022-0234 G3 TEAM 経路の送信値は不変', () => {
  beforeEach(() => {
    getOrganization.mockReset()
    advertiserRequest.mockReset().mockResolvedValue({ data: {} })
  })

  it.each(billingCases.map(c => [c.name, c] as const))(
    'G3: %s の TEAM は組織 resolver を呼ばず /teams/{teamId}/advertiser パスのまま',
    async (_name, c) => {
      await c.call(useAdvertiserApi(), 'TEAM', 'team-slug')

      expect(getOrganization).not.toHaveBeenCalled()
      expect(advertiserRequest).toHaveBeenCalledExactlyOnceWith(
        `/api/v1/teams/team-slug/advertiser/${c.resource}`,
        expectedOptions(c),
      )
    },
  )
})
