import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { getCurrentInstance, ref } from 'vue'
import RosterPage from '~/pages/teams/[slug]/tournaments/[tId]/roster.vue'
import RostersPage from '~/pages/organizations/[slug]/tournaments/[tId]/rosters.vue'

/**
 * roster.vue / rosters.vue が useMatchRoster（内部で useNotification → PrimeVue useToast = inject）を
 * クリック時（setup 外）に呼び、`No PrimeVue Toast provided!` で API が送られなかった欠陥の根治テスト。
 *
 * useNotification は本物の useToast と同じく「コンポーネント instance が無い所で呼ぶと例外」の
 * 挙動にしてあり、useMatchRoster を setup 外で呼ぶ書き方を再現して検出する。
 *
 *   RS-001 テンプレ適用で apply-template API が呼ばれ、成功通知が出る
 *   RS-002 提出で PUT API が呼ばれ、成功通知が出る
 *   RS-003 提出 API 失敗時に失敗通知が出る
 *   RS-004 useNotification は setup 外（クリック時）で呼ばれない
 *   RS-005 主催者画面の締切更新でも PATCH API が呼ばれる
 *   RS-006 主催者画面の試合切替で GET が呼ばれ、setup 外の呼び出しが 0 回
 */

const state = vi.hoisted(() => ({ query: {} as Record<string, string> }))
const notify = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
const notificationCalls = vi.hoisted(() => ({ outsideSetup: 0 }))
const api = vi.fn()
const getEntryTemplates = vi.fn()
const resolveContext = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-slug', tId: '7' }, query: state.query }))
mockNuxtImport('useApi', () => () => api)
mockNuxtImport('useTournamentApi', () => () => ({ getEntryTemplates }))
mockNuxtImport('useMatchOrgContext', () => () => ({ resolveContext }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(true), loadPermissions: vi.fn(async () => {}) }))
mockNuxtImport('useNotification', () => () => {
  // 本物の useToast は inject に依存し、setup 外では "No PrimeVue Toast provided!" を投げる
  if (!getCurrentInstance()) {
    notificationCalls.outsideSetup++
    throw new Error('No PrimeVue Toast provided!')
  }
  return notify
})

const player = {
  id: 1,
  userId: 42,
  displayName: '選手A',
  isStarter: true,
  jerseyNumber: 10,
  position: null,
  registrationNumber: null,
  uniformSetId: null,
}
const rosterBody = {
  data: { matchId: 5, participantId: 1, teamId: 22, locked: false, rosterDeadline: null, players: [player], staff: [] },
}
const submittedBody = {
  data: { ...rosterBody.data, locked: true },
}

async function settle() {
  for (let i = 0; i < 5; i++) await new Promise((r) => setTimeout(r, 0))
}

interface RosterPageVm {
  selectedTemplateId: string | null
  showTemplateModal: boolean
  playerRows: Array<{ userId: number }>
  roster: { locked: boolean } | null
  applyTemplate: () => Promise<void>
  submitRoster: () => Promise<void>
}

interface RostersPageVm {
  matchIdInput: string
  selectMatch: () => Promise<void>
  updateDeadline: () => Promise<void>
}

beforeAll(async () => {
  resolveContext.mockResolvedValue({ orgId: 11, orgInvalid: false, teamId: 22, organizations: [] })
  getEntryTemplates.mockResolvedValue({ data: [] })
  api.mockResolvedValue(rosterBody)
  const warmup = await mountSuspended(RosterPage)
  warmup.unmount()
})

describe('roster.vue — useMatchRoster は setup でのみ呼ぶ', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue(rosterBody)
    Object.values(notify).forEach((f) => f.mockReset())
    notificationCalls.outsideSetup = 0
    resolveContext.mockResolvedValue({ orgId: 11, orgInvalid: false, teamId: 22, organizations: [] })
    getEntryTemplates.mockResolvedValue({ data: [] })
    state.query = { matchId: '5' }
  })

  async function mountPage() {
    const wrapper = await mountSuspended(RosterPage)
    await settle()
    return wrapper
  }

  it('RS-001: テンプレ適用で apply-template API が呼ばれ成功通知が出る', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as RosterPageVm
    vm.playerRows = []
    vm.showTemplateModal = true
    vm.selectedTemplateId = 'tpl-1'
    await vm.applyTemplate()
    await settle()

    expect(vm.playerRows.map((p) => p.userId)).toEqual([42])
    expect(vm.showTemplateModal).toBe(false)
    expect(api).toHaveBeenCalledWith(
      '/api/v1/tournaments/7/matches/5/rosters/me/apply-template',
      expect.objectContaining({ method: 'POST', body: expect.objectContaining({ templateId: 'tpl-1' }) }),
    )
    expect(notify.success).toHaveBeenCalled()
  })

  it('RS-002: 提出で PUT API が呼ばれ成功通知が出る', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as RosterPageVm
    expect(vm.roster?.locked).toBe(false)
    api.mockResolvedValueOnce(submittedBody)
    await vm.submitRoster()
    await settle()

    expect(vm.roster?.locked).toBe(true)
    expect(api).toHaveBeenCalledWith(
      '/api/v1/tournaments/7/matches/5/rosters/me',
      expect.objectContaining({ method: 'PUT' }),
    )
    expect(notify.success).toHaveBeenCalled()
  })

  it('RS-003: 提出 API 失敗時に失敗通知が出る', async () => {
    const wrapper = await mountPage()
    api.mockRejectedValueOnce(new Error('boom'))
    await (wrapper.vm as unknown as RosterPageVm).submitRoster()
    await settle()

    expect(notify.error).toHaveBeenCalled()
  })

  it('RS-004: useNotification は setup 外（クリック時）で呼ばれない', async () => {
    const wrapper = await mountPage()
    const vm = wrapper.vm as unknown as RosterPageVm
    vm.selectedTemplateId = 'tpl-1'
    await vm.applyTemplate()
    await vm.submitRoster()
    await settle()

    expect(notificationCalls.outsideSetup).toBe(0)
  })
})

describe('rosters.vue（主催者）— useMatchRoster は setup でのみ呼ぶ', () => {
  beforeEach(() => {
    api.mockReset()
    api.mockResolvedValue({ data: [] })
    Object.values(notify).forEach((f) => f.mockReset())
    notificationCalls.outsideSetup = 0
    state.query = { matchId: '5' }
  })

  it('RS-005: 締切更新で PATCH API が呼ばれる', async () => {
    const wrapper = await mountSuspended(RostersPage)
    await settle()
    await (wrapper.vm as unknown as RostersPageVm).updateDeadline()
    await settle()

    expect(notificationCalls.outsideSetup).toBe(0)
    expect(api).toHaveBeenCalledWith(
      '/api/v1/tournaments/7/matches/5',
      expect.objectContaining({ method: 'PATCH' }),
    )
  })

  it('RS-006: 試合切替(selectMatch)で /matches/6/rosters の GET が呼ばれ、setup 外の呼び出しは 0 回', async () => {
    const wrapper = await mountSuspended(RostersPage)
    await settle()
    const vm = wrapper.vm as unknown as RostersPageVm
    api.mockClear()
    vm.matchIdInput = '6'
    await vm.selectMatch()
    await settle()

    expect(api).toHaveBeenCalledWith('/api/v1/tournaments/7/matches/6/rosters')
    expect(notificationCalls.outsideSetup).toBe(0)
  })
})
