// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, reactive, ref } from 'vue'
import Page from './board.vue'
import ShiftMemberPool from '~/components/shift/ShiftMemberPool.vue'
import ShiftBoardMatrix from '~/components/shift/ShiftBoardMatrix.vue'
import DashboardErrorState from '~/components/DashboardErrorState.vue'

// ページ・メンバー欄・取得失敗表示は実物。シフト表／メンバー取得の通信境界だけ差し替える。
const mocks = vi.hoisted(() => ({
  getShiftSchedule: vi.fn(),
  getPositions: vi.fn(),
  getTeam: vi.fn(),
  getMembers: vi.fn(),
  loadPermissions: vi.fn(),
  loadSlots: vi.fn(),
  fetchRuns: vi.fn(),
  captureQuiet: vi.fn(),
  showError: vi.fn(),
  handleApiError: vi.fn(),
  addUser: vi.fn(),
  removeUser: vi.fn(),
  moveUser: vi.fn(),
}))
const params = reactive({ slug: 'team-a', scheduleId: '425' })
mockNuxtImport('useRoute', () => () => ({ params }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useShiftApi', () => () => ({
  getShiftSchedule: mocks.getShiftSchedule,
  getPositions: mocks.getPositions,
}))
mockNuxtImport('useTeamApi', () => () => ({ getTeam: mocks.getTeam, getMembers: mocks.getMembers }))
const roleState = vi.hoisted(() => ({ roleName: 'MEMBER' as string, scopeAdmin: false, permissions: [] as string[] }))
mockNuxtImport('useRoleAccess', () => () => ({
  roleName: computed(() => roleState.roleName),
  isScopeAdmin: computed(() => roleState.scopeAdmin),
  can: (permission: string) => roleState.permissions.includes(permission),
  loadPermissions: mocks.loadPermissions,
}))
mockNuxtImport('useFeatureFlag', () => () => ({ enabled: ref(false) }))
mockNuxtImport('useAutoAssign', () => () => ({
  runs: ref([]),
  isRunning: ref(false),
  runAutoAssign: vi.fn(),
  confirmAutoAssign: vi.fn(),
  revokeAutoAssign: vi.fn(),
  fetchRuns: mocks.fetchRuns,
}))
mockNuxtImport('useShiftBoard', () => () => ({
  localAssignments: ref({}),
  loadSlots: mocks.loadSlots,
  moveUser: mocks.moveUser,
  addUser: mocks.addUser,
  removeUser: mocks.removeUser,
}))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.handleApiError }))
mockNuxtImport('useNotification', () => () => ({ showError: mocks.showError }))
mockNuxtImport('useErrorReport', () => () => ({ captureQuiet: mocks.captureQuiet }))

function httpError(status: number): Error & { statusCode: number } {
  return Object.assign(new Error(`HTTP ${status}`), { statusCode: status })
}

let wrapper: VueWrapper | undefined
function renderPage(): VueWrapper {
  wrapper = mount(Page, {
    global: {
      components: { ShiftMemberPool, DashboardErrorState },
      mocks: { $t: (key: string) => key },
      directives: { tooltip: {} },
      stubs: {
        PageLoading: { template: '<div data-testid="page-loading" />' },
        Button: {
          props: ['label'],
          emits: ['click'],
          template: '<button v-bind="$attrs" @click="$emit(\'click\')">{{ label }}</button>',
        },
        NuxtLink: { props: ['to'], template: '<a :href="to"><slot /></a>' },
        BackButton: { props: ['to'], template: '<a :href="to"><i class="pi pi-arrow-left" /></a>' },
        ShiftBoardMatrix: true,
        ShiftAddMemberDialog: true,
        ShiftPdfExportButton: true,
        ShiftAutoAssignModal: true,
        ShiftAutoAssignRunHistory: true,
        Dialog: true,
        Tag: true,
        Avatar: true,
        InputText: true,
        DashboardEmptyState: { props: ['message'], template: '<p>{{ message }}</p>' },
      },
    },
  })
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  // 既定は割当権限のあるチーム ADMIN（権限別の出し分けは下の describe で切り替える）
  roleState.roleName = 'ADMIN'
  roleState.scopeAdmin = true
  roleState.permissions = []
  mocks.getShiftSchedule.mockResolvedValue({
    data: { id: 425, teamId: 9, content: { title: '11月シフト' }, status: { status: 'DRAFT' } },
  })
  mocks.getPositions.mockResolvedValue({ data: [] })
  // URL の slug のチーム（numericId）はシフト表の teamId と同じ（board.vue の所属照合を通る前提のデータ）
  mocks.getTeam.mockResolvedValue({ data: { numericId: 9 } })
  mocks.getMembers.mockResolvedValue({ data: [{ userId: 2, displayName: 'メンバーB', avatarUrl: null }] })
  mocks.loadPermissions.mockResolvedValue({ ok: true })
  mocks.loadSlots.mockResolvedValue(undefined)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

describe('シフトボード: シフト表の取得失敗（所属外の直打ち）', () => {
  it('シフト表取得が 404 のとき、メンバー欄のスピナーが残らず、取得失敗の表示が出る', async () => {
    mocks.getShiftSchedule.mockRejectedValue(httpError(404))
    renderPage()
    await flushPromises()

    // 読み込み中のまま残らない（ページ・メンバー欄どちらのスピナーも無い）
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
    // ボード本体（メンバー欄）は描画しない
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(false)
    expect(mocks.getMembers).not.toHaveBeenCalled()
    // 取得失敗の表示と戻る導線が出る。404 は再試行を出さない
    const errorState = wrapper!.get('[data-testid="load-error-state"]')
    expect(errorState.text()).toContain('loadErrorState.states.notFoundOrForbidden.title')
    expect(wrapper!.find('[data-testid="load-error-state-retry"]').exists()).toBe(false)
    // 戻る導線はヘッダーの1つだけ（取得失敗表示内に二重に出さない）
    const backLinks = wrapper!.findAll('a[href="/teams/team-a/shifts"]')
    expect(backLinks).toHaveLength(1)
    expect(wrapper!.find('[data-testid="shift-board-load-error"] a').exists()).toBe(false)
    // 操作群（変更依頼・PDF 等）は出さない
    expect(wrapper!.text()).not.toContain('shift.changeRequest.title')
    // 握りつぶさず記録する
    expect(mocks.captureQuiet).toHaveBeenCalledWith(expect.objectContaining({ statusCode: 404 }), expect.anything())
  })

  it('403 でも 404 と同じ表示にし、シフト表の実在を示唆しない', async () => {
    mocks.getShiftSchedule.mockRejectedValue(httpError(404))
    renderPage()
    await flushPromises()
    const notFoundText = wrapper!.get('[data-testid="shift-board-load-error"]').text()
    wrapper!.unmount()

    mocks.getShiftSchedule.mockRejectedValue(httpError(403))
    renderPage()
    await flushPromises()
    expect(wrapper!.get('[data-testid="shift-board-load-error"]').text()).toBe(notFoundText)
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
  })

  it('通信・サーバー障害では再試行を出し、再試行で成功すればボードを描画する', async () => {
    mocks.getShiftSchedule.mockRejectedValueOnce(httpError(500))
    renderPage()
    await flushPromises()
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)

    await wrapper!.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()

    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(false)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(true)
    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
    expect(wrapper!.text()).toContain('メンバーB')
  })
})

describe('シフトボード: シフト表は取れたが枠・ポジション・権限の取得に失敗', () => {
  it('枠の取得が 500 のとき、枠が無いボードに見せず取得失敗の表示と再試行を出す', async () => {
    mocks.loadSlots.mockRejectedValueOnce(httpError(500))
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="page-loading"]').exists()).toBe(false)
    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(true)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(false)
    expect(mocks.captureQuiet).toHaveBeenCalledWith(expect.objectContaining({ statusCode: 500 }), expect.anything())

    // 再試行で成功すればボードを描画する
    await wrapper!.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(false)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(true)
  })

  it('ポジションの取得が通信断のとき、取得失敗の表示を出す', async () => {
    mocks.getPositions.mockRejectedValueOnce(new Error('network'))
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(true)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(false)
  })

  it('権限の取得が失敗（例外を投げず ok:false）のとき、権限不明のままボードを出さず取得失敗の表示と再試行を出す', async () => {
    mocks.loadPermissions.mockResolvedValueOnce({ ok: false, error: httpError(500) })
    renderPage()
    await flushPromises()

    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(true)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(false)
    expect(mocks.captureQuiet).toHaveBeenCalledWith(expect.objectContaining({ statusCode: 500 }), expect.anything())

    await wrapper!.get('[data-testid="load-error-state-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper!.find('[data-testid="shift-board-load-error"]').exists()).toBe(false)
    expect(wrapper!.findComponent(ShiftMemberPool).exists()).toBe(true)
  })
})

describe('シフトボード: 戻る導線', () => {
  it('正常表示でも戻る導線は一覧への1つだけ', async () => {
    renderPage()
    await flushPromises()
    expect(wrapper!.findAll('a[href="/teams/team-a/shifts"]')).toHaveLength(1)
    expect(wrapper!.findAll('.pi-arrow-left')).toHaveLength(1)
  })
})

describe('シフトボード: 割当操作の出し分け（CMP-261125-1336）', () => {
  async function renderAs(role: string, scopeAdmin: boolean, permissions: string[] = []): Promise<VueWrapper> {
    roleState.roleName = role
    roleState.scopeAdmin = scopeAdmin
    roleState.permissions = permissions
    const w = renderPage()
    await flushPromises()
    return w
  }

  it('ADMIN には未割当プール・自動割当・プール開閉ボタンを出す', async () => {
    const w = await renderAs('ADMIN', true)
    expect(w.find('[data-testid="member-pool-aside"]').exists()).toBe(true)
    expect(w.find('[data-testid="auto-assign-button"]').exists()).toBe(true)
    expect(w.find('[data-testid="member-pool-open"]').exists()).toBe(true)
    expect(w.findComponent(ShiftBoardMatrix).props('canAssign')).toBe(true)
  })

  it('MANAGE_SHIFTS を持つ DEPUTY_ADMIN には出す', async () => {
    const w = await renderAs('DEPUTY_ADMIN', false, ['MANAGE_SHIFTS'])
    expect(w.find('[data-testid="member-pool-aside"]').exists()).toBe(true)
    expect(w.find('[data-testid="auto-assign-button"]').exists()).toBe(true)
  })

  it('MANAGE_SHIFTS を持たない DEPUTY_ADMIN には出さず、閲覧のみ', async () => {
    const w = await renderAs('DEPUTY_ADMIN', false, ['MANAGE_TEAM'])
    expect(w.find('[data-testid="member-pool-aside"]').exists()).toBe(false)
    expect(w.find('[data-testid="auto-assign-button"]').exists()).toBe(false)
    expect(w.find('[data-testid="member-pool-open"]').exists()).toBe(false)
    expect(w.findComponent(ShiftBoardMatrix).props('canAssign')).toBe(false)
    // 権限の無い人へ実行履歴 API を呼ばない
    expect(mocks.fetchRuns).not.toHaveBeenCalled()
  })

  it('一般 MEMBER には出さない。MANAGE_SHIFTS 権限があっても DEPUTY_ADMIN 以外には出さない', async () => {
    const w = await renderAs('MEMBER', false, ['MANAGE_SHIFTS'])
    expect(w.find('[data-testid="member-pool-aside"]').exists()).toBe(false)
    expect(w.find('[data-testid="auto-assign-button"]').exists()).toBe(false)
    expect(w.findComponent(ShiftBoardMatrix).props('canAssign')).toBe(false)
  })

  it('SYSTEM_ADMIN は（チーム ADMIN と兼任でなければ）既存方針どおり出さない', async () => {
    const w = await renderAs('SYSTEM_ADMIN', false)
    expect(w.find('[data-testid="member-pool-aside"]').exists()).toBe(false)
  })

  it('SYSTEM_ADMIN がチーム ADMIN と兼任（isScopeAdmin=true）でも、roleName 等値の既存方針どおり出さない', async () => {
    const w = await renderAs('SYSTEM_ADMIN', true, ['MANAGE_SHIFTS'])
    expect(w.find('[data-testid="member-pool-aside"]').exists()).toBe(false)
    expect(w.find('[data-testid="auto-assign-button"]').exists()).toBe(false)
    expect(w.findComponent(ShiftBoardMatrix).props('canAssign')).toBe(false)
  })
})

describe('シフトボード: 二度押し防止と失敗後の復帰（CMP-261125-1336）', () => {
  const payload = { slotId: 7, userId: 2 }

  it('処理中の枠への同じ操作は無視され、PATCH は 1 回。完了後は次の操作ができる', async () => {
    let finish!: () => void
    mocks.removeUser.mockImplementationOnce(() => new Promise<void>((resolve) => { finish = resolve }))
    const w = renderPage()
    await flushPromises()
    const matrix = w.findComponent(ShiftBoardMatrix)

    matrix.vm.$emit('removeUser', payload)
    matrix.vm.$emit('removeUser', payload)
    await flushPromises()
    expect(mocks.removeUser).toHaveBeenCalledTimes(1)
    expect(matrix.props('busySlotIds')).toEqual([7])

    finish()
    await flushPromises()
    expect(matrix.props('busySlotIds')).toEqual([])

    mocks.removeUser.mockResolvedValueOnce(undefined)
    matrix.vm.$emit('removeUser', payload)
    await flushPromises()
    expect(mocks.removeUser).toHaveBeenCalledTimes(2)
  })

  it('失敗（409 等）しても枠の処理中表示が残らず、通知され、次の操作が実行できる', async () => {
    mocks.removeUser.mockRejectedValueOnce(Object.assign(new Error('conflict'), { statusCode: 409 }))
    const w = renderPage()
    await flushPromises()
    const matrix = w.findComponent(ShiftBoardMatrix)

    matrix.vm.$emit('removeUser', payload)
    await flushPromises()
    expect(mocks.showError).toHaveBeenCalledTimes(1)
    expect(matrix.props('busySlotIds')).toEqual([])

    mocks.removeUser.mockResolvedValueOnce(undefined)
    matrix.vm.$emit('removeUser', payload)
    await flushPromises()
    expect(mocks.removeUser).toHaveBeenCalledTimes(2)
  })

  it('割当権限が無い人からの操作イベントは実行しない', async () => {
    roleState.roleName = 'MEMBER'
    roleState.scopeAdmin = false
    const w = renderPage()
    await flushPromises()
    w.findComponent(ShiftBoardMatrix).vm.$emit('removeUser', payload)
    await flushPromises()
    expect(mocks.removeUser).not.toHaveBeenCalled()
  })
})
