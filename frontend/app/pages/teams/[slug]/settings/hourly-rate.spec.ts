import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import Page from './hourly-rate.vue'

/**
 * チーム設定 > 時給設定の権限による出し分け（CMP-261125-2114）。
 * 閲覧は ADMIN・MANAGE_SHIFTS 付き DEPUTY_ADMIN・時給閲覧切替が ON の DEPUTY_ADMIN。
 * 登録・変更（編集ボタン）は ADMIN と MANAGE_SHIFTS 付き DEPUTY_ADMIN だけ。
 */
const mocks = vi.hoisted(() => ({
  loadPermissions: vi.fn(),
  loadAll: vi.fn(),
  getTeam: vi.fn(),
  showError: vi.fn(),
}))
const perm = { canViewHourlyRate: ref(true), canManageShift: ref(true) }

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {} }))
mockNuxtImport('useNotification', () => () => ({ error: vi.fn(), success: vi.fn() }))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam }))
mockNuxtImport('showError', () => mocks.showError)
mockNuxtImport('useShiftPermission', () => () => ({ ...perm, loadPermissions: mocks.loadPermissions }))
vi.mock('~/composables/shift/useHourlyRateMemberPage', async (importOriginal) => {
  const original = await importOriginal<typeof import('~/composables/shift/useHourlyRateMemberPage')>()
  return { ...original, useHourlyRateMemberPage: () => ({ loadAll: mocks.loadAll }) }
})

async function mountPage() {
  const wrapper = await mountSuspended(Page, {
    global: { stubs: { PageHeader: true, PageLoading: true, Dialog: true, HourlyRateAmountInput: true, DashboardEmptyState: true } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  perm.canViewHourlyRate.value = true
  perm.canManageShift.value = true
  mocks.loadPermissions.mockResolvedValue({ ok: true })
  mocks.getTeam.mockResolvedValue({ data: { numericId: 10 } })
  mocks.loadAll.mockResolvedValue({
    rows: [{ member: { userId: 7, displayName: '山田太郎' }, rate: { hourlyRate: '1200', effectiveFrom: '2026-11-01' } }],
    totalElements: 1,
  })
})

describe('時給設定画面の権限による出し分け', () => {
  it('管理操作ができる人には編集ボタンを出し、閲覧専用の案内は出さない', async () => {
    const wrapper = await mountPage()
    expect(wrapper.find('[data-testid="hourly-rate-edit-7"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="hourly-rate-readonly-notice"]').exists()).toBe(false)
  })

  it('時給閲覧切替が ON の副管理者（MANAGE_SHIFTS 無し）には一覧だけを見せ、編集ボタンを出さない', async () => {
    perm.canManageShift.value = false
    const wrapper = await mountPage()
    expect(mocks.loadAll).toHaveBeenCalled()
    expect(wrapper.text()).toContain('山田太郎')
    expect(wrapper.find('[data-testid="hourly-rate-edit-7"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="hourly-rate-readonly-notice"]').exists()).toBe(true)
  })

  it('閲覧できない人（切替 OFF の副管理者など）は URL 直打ちでも一覧を取得せず 403 を出す', async () => {
    perm.canViewHourlyRate.value = false
    perm.canManageShift.value = false
    await mountPage()
    expect(mocks.loadAll).not.toHaveBeenCalled()
    expect(mocks.showError).toHaveBeenCalledWith(expect.objectContaining({ statusCode: 403 }))
  })

  it('権限の取得に失敗したら一覧を取得しない（権限なしに倒して握りつぶさず通知する）', async () => {
    mocks.loadPermissions.mockResolvedValue({ ok: false, error: new Error('x') })
    await mountPage()
    expect(mocks.loadAll).not.toHaveBeenCalled()
  })
})
