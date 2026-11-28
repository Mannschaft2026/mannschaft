import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import OrgAdvertiserDashboardPage from '~/pages/organizations/[slug]/advertiser/index.vue'

/**
 * CMP-261022-0234（ε1 G1/G2）組織広告 dashboard の slug→数値 ID 解決の試練（red 先行）。
 *
 * 実機で観測された欠陥: `/organizations/{slug}/advertiser` は URL の slug をそのまま
 * `GET /api/v1/advertiser/account?organizationId=<slug>` に送っていたため、BE が 404 を返し、
 * 登録済み（ACTIVE）の組織でも「広告主登録」の案内が出ていた（数値 ID なら 200/ACTIVE）。
 *
 * ここでは useAdvertiserApi を実物のまま使い、HTTP 層（useApi）だけを BE の実挙動に寄せて模擬する:
 * organizationId が数値 ID（632）のときだけアカウント/overview を返し、それ以外（slug）は 404。
 *
 * G2: 登録済み組織では account/overview が表示され、登録案内は出ない。未登録組織だけ登録案内。
 * G1: 組織 slug→数値 ID の解決は dashboard 読み込みで一度だけ（account と overview で使い回す）。
 */

const ORG_SLUG = 'org-slug'
const ORG_NUMERIC_ID = '632'

const { getOrganization, apiRequest } = vi.hoisted(() => ({
  getOrganization: vi.fn(),
  apiRequest: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: ORG_SLUG }, query: {}, path: `/organizations/${ORG_SLUG}/advertiser` }))
mockNuxtImport('useApi', () => () => apiRequest)
mockNuxtImport('useOrganizationApi', () => () => ({ getOrganization }))

const account = {
  id: 1,
  scopeType: 'ORGANIZATION',
  scopeId: ORG_NUMERIC_ID,
  organizationId: 632,
  status: 'ACTIVE',
  companyName: 'ε1 登録済み広告主',
  contactEmail: 'ads@example.com',
  billingMethod: 'STRIPE',
  creditLimit: 100000,
  approvedAt: null,
  createdAt: '2026-11-01T00:00:00',
}

const overview = {
  period: { from: '2026-11-01', to: '2026-11-25' },
  totalCampaigns: 3,
  activeCampaigns: 2,
  totalImpressions: 1000,
  totalClicks: 10,
  avgCtr: 1,
  totalCost: 500,
  monthlyBudgetUsedPct: 10,
  creditLimit: 100000,
  campaigns: [],
}

function notFound() {
  return Object.assign(new Error('Not Found'), { response: { status: 404 } })
}

/** BE の実挙動を模擬: organizationId が数値 ID のときだけ登録済みアカウントを返す。 */
function backendWithRegisteredAccount(path: string, options?: { params?: Record<string, unknown> }) {
  const organizationId = options?.params?.organizationId
  if (path === '/api/v1/advertiser/account') {
    return organizationId === ORG_NUMERIC_ID ? Promise.resolve({ data: account }) : Promise.reject(notFound())
  }
  if (path === '/api/v1/advertiser/overview') {
    return organizationId === ORG_NUMERIC_ID ? Promise.resolve({ data: overview }) : Promise.reject(notFound())
  }
  return Promise.resolve({ data: [] })
}

/** 未登録組織: 数値 ID で照会しても account は 404。 */
function backendWithoutAccount(path: string) {
  if (path === '/api/v1/advertiser/account' || path === '/api/v1/advertiser/overview') {
    return Promise.reject(notFound())
  }
  return Promise.resolve({ data: [] })
}

async function flush() {
  for (let i = 0; i < 10; i++) {
    await Promise.resolve()
  }
  await new Promise(resolve => setTimeout(resolve, 0))
}

const registerHref = `/organizations/${ORG_SLUG}/advertiser/register`

describe('pages/organizations/[slug]/advertiser/index.vue — 組織 slug→数値 ID（CMP-261022-0234）', () => {
  beforeEach(() => {
    getOrganization.mockReset().mockResolvedValue({ data: { id: ORG_SLUG, numericId: 632 } })
    apiRequest.mockReset()
  })

  it('G2: 登録済み組織では account/overview が表示され、登録案内は出ない', async () => {
    apiRequest.mockImplementation(backendWithRegisteredAccount)

    const wrapper = await mountSuspended(OrgAdvertiserDashboardPage)
    await flush()

    expect(wrapper.text()).toContain(account.companyName)
    expect(wrapper.find(`a[href="${registerHref}"]`).exists()).toBe(false)
    const overviewCall = apiRequest.mock.calls.find(([path]) => path === '/api/v1/advertiser/overview')
    expect(overviewCall?.[1]?.params?.organizationId).toBe(ORG_NUMERIC_ID)
  })

  it('G2: 未登録組織（数値 ID でも account 404）だけ登録案内を出す', async () => {
    apiRequest.mockImplementation(backendWithoutAccount)

    const wrapper = await mountSuspended(OrgAdvertiserDashboardPage)
    await flush()

    expect(wrapper.find(`a[href="${registerHref}"]`).exists()).toBe(true)
    expect(wrapper.text()).not.toContain(account.companyName)
    const accountCall = apiRequest.mock.calls.find(([path]) => path === '/api/v1/advertiser/account')
    expect(accountCall?.[1]?.params?.organizationId).toBe(ORG_NUMERIC_ID)
  })

  it('G1: dashboard 読み込みで組織 slug→数値 ID の解決は一度だけ（account と overview で使い回す）', async () => {
    apiRequest.mockImplementation(backendWithRegisteredAccount)

    await mountSuspended(OrgAdvertiserDashboardPage)
    await flush()

    expect(getOrganization).toHaveBeenCalledTimes(1)
    expect(getOrganization).toHaveBeenCalledWith(ORG_SLUG)
    const sentOrganizationIds = apiRequest.mock.calls
      .map(([, options]) => options?.params?.organizationId)
      .filter(v => v !== undefined)
    expect(sentOrganizationIds.length).toBeGreaterThanOrEqual(2)
    expect(sentOrganizationIds.every(v => v === ORG_NUMERIC_ID)).toBe(true)
  })
})
