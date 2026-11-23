import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed } from 'vue'
import BoardPage from '~/pages/teams/[slug]/shifts/[scheduleId]/board.vue'
import type { ShiftScheduleResponse } from '~/types/shift'

/**
 * シフトボード画面で、URL の slug のチームとシフト表のチーム（schedule.teamId）が食い違う場合の AC。
 *   AC-M1 食い違えば 404 相当の取得失敗表示にし、slug 側チームのメンバー一覧を取得・表示しない
 *   AC-M2 一致していれば従来どおりメンバーを取得する
 */
const mocks = await vi.hoisted(async () => {
  return {
    getShiftSchedule: vi.fn(),
    getPositions: vi.fn(),
    getShiftSlots: vi.fn(),
    getTeam: vi.fn(),
    getMembers: vi.fn(),
    loadPermissions: vi.fn(),
  }
})

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a', scheduleId: '10' } }))
mockNuxtImport('useShiftApi', () => () => ({
  getShiftSchedule: mocks.getShiftSchedule,
  getPositions: mocks.getPositions,
  getShiftSlots: mocks.getShiftSlots,
}))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam, getMembers: mocks.getMembers }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: computed(() => 'ADMIN'),
  loadPermissions: mocks.loadPermissions,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))

function schedule(teamId: number): ShiftScheduleResponse {
  return {
    id: 10,
    teamId,
    content: { title: '10月第1週', periodType: 'WEEKLY', note: null },
    period: { startDate: '2026-10-01', endDate: '2026-10-07', requestDeadline: null },
    status: { status: 'ADJUSTING', publishedAt: null, publishedBy: null },
    audit: { createdBy: 1, createdAt: '', updatedAt: '' },
  } as ShiftScheduleResponse
}

beforeEach(() => {
  document.body.innerHTML = ''
  Object.values(mocks).forEach((m) => m.mockReset())
  mocks.getPositions.mockResolvedValue({ data: [] })
  mocks.getShiftSlots.mockResolvedValue({ data: [] })
  mocks.getMembers.mockResolvedValue({ data: [{ userId: 12, displayName: '他チームの人', avatarUrl: null }] })
  mocks.loadPermissions.mockResolvedValue({ ok: true })
  mocks.getTeam.mockResolvedValue({ data: { numericId: 1 } })
})

describe('board.vue URL の slug とシフト表のチームの照合', () => {
  it('AC-M1: schedule.teamId が slug のチームと違えば取得失敗表示にし、メンバーを取得・表示しない', async () => {
    mocks.getShiftSchedule.mockResolvedValue({ data: schedule(2) })
    const wrapper = await mountSuspended(BoardPage)
    await flushPromises()
    expect(wrapper.find('[data-testid="shift-board-load-error"]').exists()).toBe(true)
    expect(mocks.getMembers).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('他チームの人')
    expect(wrapper.text()).not.toContain('10月第1週')
  })

  it('AC-M2: 一致していればメンバーを取得する', async () => {
    mocks.getShiftSchedule.mockResolvedValue({ data: schedule(1) })
    const wrapper = await mountSuspended(BoardPage)
    await flushPromises()
    expect(wrapper.find('[data-testid="shift-board-load-error"]').exists()).toBe(false)
    expect(mocks.getMembers).toHaveBeenCalledTimes(1)
  })
})
