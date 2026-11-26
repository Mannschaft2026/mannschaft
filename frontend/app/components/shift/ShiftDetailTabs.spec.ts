import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import ShiftDetailTabs from './ShiftDetailTabs.vue'

/**
 * CMP-261010-1432 FE-a: /shift/{id} のタブ nav（4ページ共有）の AC。
 *   AC-K1 変更依頼・PDF のリンクがあり、管理者にはボードも出る。一般メンバーにはボードが出ない
 *   AC-K2 myTeams にそのチームが無い利用者には teams 配下へのリンクを出さない
 *   AC-K3 SUPPORTER には PDF を出さない
 *   CMP-261125-2114 D5 PDF は BE の発行条件に合わせる（管理者の形は未公開でも、それ以外の会員は公開済みだけ。未公開は BE が 404）
 */
const state = vi.hoisted(() => ({
  myTeams: [] as Array<{ id: number; slug: string; role: string }>,
  canManageShift: false,
  canViewShift: false,
}))

mockNuxtImport('useTeamStore', () => () => ({ get myTeams() { return state.myTeams } }))
// 出し分けの判定本体は useShiftPermission（別 spec で真理値表を検証済み）。ここでは結果に応じたタブだけを検証する。
mockNuxtImport('useShiftPermission', () => () => ({
  canManageShift: { get value() { return state.canManageShift } },
  canViewShift: { get value() { return state.canViewShift } },
}))
mockNuxtImport('useShiftPdf', () => () => ({ isDownloading: false, download: vi.fn() }))

async function mountTabs(
  role: string | null,
  teamId: number | null = 12,
  perm: { manage: boolean, view: boolean } = {
    manage: role === 'ADMIN' || role === 'DEPUTY_ADMIN',
    view: role === 'ADMIN' || role === 'DEPUTY_ADMIN',
  },
  status: { status: string, publishedAt: string | null } | null = { status: 'PUBLISHED', publishedAt: '2026-11-01T00:00:00' },
) {
  state.canManageShift = perm.manage
  state.canViewShift = perm.view
  state.myTeams = role === null ? [] : [{ id: 12, slug: 'team-a', role }]
  return mountSuspended(ShiftDetailTabs, { props: { scheduleId: 10, teamId, current: 'overview', status } as never })
}

function hrefOf(wrapper: Awaited<ReturnType<typeof mountTabs>>, testid: string): string | undefined {
  return wrapper.find(`[data-testid="${testid}"]`).attributes('href')
}

beforeEach(() => {
  state.myTeams = []
  state.canManageShift = false
  state.canViewShift = false
})

describe('ShiftDetailTabs', () => {
  it('AC-K1: 管理者にはボード・変更依頼のリンクと PDF が出て、4つの既存タブも出る', async () => {
    const wrapper = await mountTabs('ADMIN')
    expect(hrefOf(wrapper, 'shift-tab-board')).toBe('/teams/team-a/shifts/10/board')
    expect(hrefOf(wrapper, 'shift-tab-change-requests')).toBe('/teams/team-a/shifts/10/change-requests')
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(true)
    // 現在地（overview）はリンクでなく、ほかの3タブはリンク
    expect(hrefOf(wrapper, 'shift-tab-edit')).toBe('/shift/10/edit')
    expect(hrefOf(wrapper, 'shift-tab-requests')).toBe('/shift/10/requests')
    expect(hrefOf(wrapper, 'shift-tab-constraints')).toBe('/shift/10/work-constraints')
    expect(wrapper.find('[data-testid="shift-tab-overview"]').attributes('href')).toBeUndefined()
  })

  it('AC-K1: 一般メンバーにはボードを出さず、変更依頼・PDF は出す。希望一覧・勤務制約も出さない', async () => {
    const wrapper = await mountTabs('MEMBER')
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(false)
    expect(hrefOf(wrapper, 'shift-tab-change-requests')).toBe('/teams/team-a/shifts/10/change-requests')
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="shift-tab-requests"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-constraints"]').exists()).toBe(false)
  })

  it('AC-K1: MANAGE_SHIFTS を持つ DEPUTY_ADMIN にもボードが出る', async () => {
    const wrapper = await mountTabs('DEPUTY_ADMIN', 12, { manage: true, view: true })
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="shift-tab-constraints"]').exists()).toBe(true)
  })

  it('CMP-261125-2114: MANAGE_SHIFTS 無しで閲覧切替 ON の DEPUTY_ADMIN には希望一覧だけが出る（勤務制約・ボードは出さない）', async () => {
    const wrapper = await mountTabs('DEPUTY_ADMIN', 12, { manage: false, view: true })
    expect(hrefOf(wrapper, 'shift-tab-requests')).toBe('/shift/10/requests')
    expect(wrapper.find('[data-testid="shift-tab-constraints"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(false)
  })

  it('CMP-261125-2114: MANAGE_SHIFTS 無しで閲覧切替 OFF の DEPUTY_ADMIN は一般メンバーと同じ（管理系タブなし）', async () => {
    const wrapper = await mountTabs('DEPUTY_ADMIN', 12, { manage: false, view: false })
    expect(wrapper.find('[data-testid="shift-tab-requests"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-constraints"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(false)
    expect(hrefOf(wrapper, 'shift-tab-change-requests')).toBe('/teams/team-a/shifts/10/change-requests')
  })

  it('AC-K2: myTeams にそのチームが無い利用者には teams 配下へのリンクを出さない', async () => {
    const wrapper = await mountTabs(null)
    expect(wrapper.find('[data-testid="shift-tab-board"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="shift-tab-change-requests"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('/teams/')
  })

  it('AC-K2: シフト表が未取得（teamId=null）のときも teams 配下へのリンクを出さない', async () => {
    const wrapper = await mountTabs('ADMIN', null)
    expect(wrapper.html()).not.toContain('/teams/')
  })

  it('AC-K3: SUPPORTER には PDF を出さない', async () => {
    const wrapper = await mountTabs('SUPPORTER')
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(false)
  })

  it.each([
    { label: '一般メンバー・公開済み', role: 'MEMBER', perm: { manage: false, view: false }, status: { status: 'PUBLISHED', publishedAt: '2026-11-01T00:00:00' }, visible: true },
    { label: '一般メンバー・公開日時ありのアーカイブ', role: 'MEMBER', perm: { manage: false, view: false }, status: { status: 'ARCHIVED', publishedAt: '2026-11-01T00:00:00' }, visible: true },
    { label: '一般メンバー・公開日時なしのアーカイブ', role: 'MEMBER', perm: { manage: false, view: false }, status: { status: 'ARCHIVED', publishedAt: null }, visible: false },
    { label: '一般メンバー・調整中（BE は 404）', role: 'MEMBER', perm: { manage: false, view: false }, status: { status: 'ADJUSTING', publishedAt: null }, visible: false },
    { label: '一般メンバー・下書き（BE は 404）', role: 'MEMBER', perm: { manage: false, view: false }, status: { status: 'DRAFT', publishedAt: null }, visible: false },
    { label: '一般メンバー・状態未取得', role: 'MEMBER', perm: { manage: false, view: false }, status: null, visible: false },
    { label: '閲覧切替 OFF の副管理者・調整中', role: 'DEPUTY_ADMIN', perm: { manage: false, view: false }, status: { status: 'ADJUSTING', publishedAt: null }, visible: false },
    { label: '閲覧切替 ON の副管理者・調整中（管理者の形は未公開でも発行できる）', role: 'DEPUTY_ADMIN', perm: { manage: false, view: true }, status: { status: 'ADJUSTING', publishedAt: null }, visible: true },
    { label: '管理者・下書き', role: 'ADMIN', perm: { manage: true, view: true }, status: { status: 'DRAFT', publishedAt: null }, visible: true },
  ])('D5: PDF は $label のとき $visible', async ({ role, perm, status, visible }) => {
    const wrapper = await mountTabs(role, 12, perm, status)
    expect(wrapper.find('[data-testid="shift-tab-pdf"]').exists()).toBe(visible)
  })
})
