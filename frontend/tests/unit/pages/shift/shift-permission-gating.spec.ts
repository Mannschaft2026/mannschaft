import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed } from 'vue'
import ShiftDetailIndex from '~/pages/shift/[id]/index.vue'
import ShiftDetailRequests from '~/pages/shift/[id]/requests.vue'
import ShiftDetailWorkConstraints from '~/pages/shift/[id]/work-constraints.vue'
import type { ShiftScheduleResponse } from '~/types/shift'

/**
 * CMP-261125-2114: /shift/{id} 系の権限による出し分け。
 *   希望一覧 … 閲覧権限（ADMIN / MANAGE_SHIFTS 付き副管理者 / 閲覧切替 ON の副管理者）が無ければ詳細へ戻す
 *   勤務制約 … 管理操作の権限（ADMIN / MANAGE_SHIFTS 付き副管理者）が無ければ詳細へ戻す
 *   概要     … MANAGE_SHIFTS 無しの副管理者には操作ボタンを出さず、理由を案内する
 *   権限の取得失敗は「権限なし」に倒して黙らせず、取得失敗の表示（再試行つき）を出す
 */
const mocks = vi.hoisted(() => ({
  getSchedule: vi.fn(),
  listSlots: vi.fn(),
  listRequests: vi.fn(),
  getRequestSummary: vi.fn(),
  listConstraints: vi.fn(),
  getTeamDefault: vi.fn(),
  getAllMembers: vi.fn(),
  navigateTo: vi.fn(),
  warn: vi.fn(),
  loadPermissions: vi.fn(),
}))
const perm = vi.hoisted(() => ({ manage: false, view: false, deputyWithoutManage: false }))

vi.mock('~/composables/team/useTeamMembers', () => ({
  useTeamMembers: () => ({ getAllMembers: mocks.getAllMembers }),
}))
mockNuxtImport('useRoute', () => () => ({ params: { id: '10' }, path: '/shift/10' }))
mockNuxtImport('navigateTo', () => mocks.navigateTo)
mockNuxtImport('useTeamStore', () => () => ({
  myTeams: [{ id: 12, slug: 'team-a', name: 'A', role: 'DEPUTY_ADMIN' }],
  fetchMyTeams: async () => {},
}))
mockNuxtImport('useShiftPermission', () => () => ({
  canManageShift: computed(() => perm.manage),
  canViewShift: computed(() => perm.view),
  isDeputyWithoutManage: computed(() => perm.deputyWithoutManage),
  loadPermissions: mocks.loadPermissions,
}))
mockNuxtImport('useShiftApi', () => () => ({
  getSchedule: mocks.getSchedule,
  updateSchedule: vi.fn(),
  transitionStatus: vi.fn(),
  deleteSchedule: vi.fn(),
}))
mockNuxtImport('useShiftSlotApi', () => () => ({ listSlots: mocks.listSlots, deleteSlot: vi.fn(), bulkCreateSlots: vi.fn() }))
mockNuxtImport('useShiftRequestApi', () => () => ({
  listRequests: mocks.listRequests,
  getRequestSummary: mocks.getRequestSummary,
}))
mockNuxtImport('useMemberWorkConstraintApi', () => () => ({
  listConstraints: mocks.listConstraints,
  getTeamDefault: mocks.getTeamDefault,
  upsertConstraint: vi.fn(),
  upsertTeamDefault: vi.fn(),
  deleteConstraint: vi.fn(),
  deleteTeamDefault: vi.fn(),
}))
mockNuxtImport('useTeamApi', () => () => ({ getAllMembers: mocks.getAllMembers, getMembers: vi.fn() }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: mocks.warn }))
mockNuxtImport('useShiftPdf', () => () => ({ isDownloading: false, download: vi.fn() }))

const schedule: ShiftScheduleResponse = {
  id: 10,
  teamId: 12,
  content: { title: '10月第1週', periodType: 'WEEKLY', note: null },
  period: { startDate: '2026-10-01', endDate: '2026-10-07', requestDeadline: null },
  status: { status: 'COLLECTING', publishedAt: null, publishedBy: null },
  audit: { createdBy: 1, createdAt: '', updatedAt: '' },
}

const stubs = {
  ShiftPdfExportButton: true,
  DashboardErrorState: { template: '<div data-testid="load-error-stub" />' },
  ShiftManageNotice: { template: '<div data-testid="notice-stub" />' },
}

beforeEach(() => {
  vi.clearAllMocks()
  perm.manage = false
  perm.view = false
  perm.deputyWithoutManage = false
  mocks.getSchedule.mockResolvedValue(schedule)
  mocks.listSlots.mockResolvedValue([])
  mocks.listRequests.mockResolvedValue([])
  mocks.getRequestSummary.mockResolvedValue({ scheduleId: 10, totalMembers: 0, submittedCount: 0, pendingCount: 0 })
  mocks.listConstraints.mockResolvedValue([])
  mocks.getTeamDefault.mockResolvedValue(null)
  mocks.getAllMembers.mockResolvedValue({ data: [] })
  mocks.loadPermissions.mockResolvedValue({ ok: true })
})

describe('希望一覧ページ（閲覧権限）', () => {
  it('閲覧権限が無ければ希望一覧を取得せず、詳細ページへ戻す', async () => {
    await mountSuspended(ShiftDetailRequests, { global: { stubs } })
    await flushPromises()
    expect(mocks.listRequests).not.toHaveBeenCalled()
    expect(mocks.navigateTo).toHaveBeenCalledWith('/shift/10')
    expect(mocks.warn).toHaveBeenCalled()
  })

  it('閲覧切替が ON の副管理者（MANAGE_SHIFTS 無し）は希望一覧を取得できる', async () => {
    perm.view = true
    await mountSuspended(ShiftDetailRequests, { global: { stubs } })
    await flushPromises()
    expect(mocks.listRequests).toHaveBeenCalled()
    expect(mocks.navigateTo).not.toHaveBeenCalled()
  })

  it('権限の取得に失敗したら、権限なしに倒して黙らせず取得失敗を表示する（詳細へは戻さない）', async () => {
    mocks.loadPermissions.mockResolvedValue({ ok: false, error: new Error('permissions unavailable') })
    const wrapper = await mountSuspended(ShiftDetailRequests, { global: { stubs } })
    await flushPromises()
    expect(mocks.listRequests).not.toHaveBeenCalled()
    expect(mocks.navigateTo).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="load-error-stub"]').exists()).toBe(true)
  })
})

describe('勤務制約ページ（管理操作の権限）', () => {
  it('閲覧切替が ON でも管理操作の権限が無ければ、勤務制約を取得せず詳細へ戻す', async () => {
    perm.view = true
    await mountSuspended(ShiftDetailWorkConstraints, { global: { stubs } })
    await flushPromises()
    expect(mocks.listConstraints).not.toHaveBeenCalled()
    expect(mocks.navigateTo).toHaveBeenCalledWith('/shift/10')
  })

  it('管理操作の権限があれば勤務制約を取得する', async () => {
    perm.manage = true
    perm.view = true
    await mountSuspended(ShiftDetailWorkConstraints, { global: { stubs } })
    await flushPromises()
    expect(mocks.listConstraints).toHaveBeenCalled()
    expect(mocks.navigateTo).not.toHaveBeenCalled()
  })
})

describe('概要ページ（操作ボタンと案内）', () => {
  it('MANAGE_SHIFTS 無しの副管理者には操作ボタン群を出さず、操作できない理由を案内する', async () => {
    perm.deputyWithoutManage = true
    const wrapper = await mountSuspended(ShiftDetailIndex, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-testid="shift-detail-actions"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="notice-stub"]').exists()).toBe(true)
  })

  it('管理操作の権限があれば操作ボタン群を出し、案内は出さない', async () => {
    perm.manage = true
    const wrapper = await mountSuspended(ShiftDetailIndex, { global: { stubs } })
    await flushPromises()
    expect(wrapper.find('[data-testid="shift-detail-actions"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="notice-stub"]').exists()).toBe(false)
  })
})
