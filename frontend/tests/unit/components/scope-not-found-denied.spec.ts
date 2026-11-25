import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { Component } from 'vue'
import EquipmentList from '~/components/equipment/EquipmentList.vue'
import MatchRequestList from '~/components/matching/MatchRequestList.vue'
import FriendFolderList from '~/components/friends/FriendFolderList.vue'
import SupporterManagementPanel from '~/components/SupporterManagementPanel.vue'
import TeamReservationsPanel from '~/components/reservation/TeamReservationsPanel.vue'
import SlotMatrixPicker from '~/components/reservation/SlotMatrixPicker.vue'
import ReservationList from '~/components/reservation/ReservationList.vue'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 (g5): 子コンポーネントが主取得を行うチーム子ページ
 * （equipment / matching / friend-folders / supporters / reservations）。
 *
 *   g5-1 scopeNotFoundOnDenied 指定で主取得が 403/404 → notFound を立て、エラートーストを出さない
 *   g5-2 主取得が 500 → 従来どおり、notFound は立てない
 *   g5-3 prop 未指定（シェル外想定）では 403 でも notFound は立たず、従来どおり
 *   g5-4 予約タブ: 予約設定の取得は補助取得なので 403 でも notFound を立てない（主取得＝枠グリッド・
 *        チーム予約一覧で判定する）。パネルは主取得側の部品へ prop を引き渡す
 */

const getEquipmentList = vi.fn()
const getTeamRequests = vi.fn()
const listFolders = vi.fn()
const getSupporters = vi.fn()
const getReservationSettings = vi.fn()
const getSlotGrid = vi.fn()
const listReservations = vi.fn()
const notifyError = vi.fn()
const showError = vi.fn()
const handleApiError = vi.fn()

mockNuxtImport('useEquipmentApi', () => () => ({ getEquipmentList }))
mockNuxtImport('useMatchingApi', () => () => ({
  searchRequests: vi.fn(),
  getTeamRequests,
  getPrefectures: vi.fn().mockResolvedValue({ data: [] }),
  getCities: vi.fn().mockResolvedValue({ data: [] }),
}))
mockNuxtImport('useFriendFoldersApi', () => () => ({ listFolders }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError }))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
  showError,
}))
mockNuxtImport('useTeamApi', () => () => ({
  getSupporterSettings: vi.fn().mockResolvedValue({ data: { autoApprove: false } }),
  getSupporters,
  getSupporterApplications: vi.fn().mockResolvedValue({ data: [] }),
}))
mockNuxtImport('useOrganizationApi', () => () => ({}))
mockNuxtImport('useReservationApi', () => () => ({
  getReservationSettings,
  getSlotGrid,
  listReservations,
  getLines: vi.fn().mockResolvedValue({ data: [] }),
  getMenus: vi.fn().mockResolvedValue({ data: [] }),
  listMyWaitlist: vi.fn().mockResolvedValue({ data: [] }),
  listMyReservations: vi.fn().mockResolvedValue({ data: [] }),
}))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: computed(() => false),
  isAdminOrDeputy: computed(() => false),
  isMember: computed(() => false),
  roleName: computed(() => null),
  can: () => false,
  loadPermissions: vi.fn().mockResolvedValue({ ok: true }),
}))
mockNuxtImport('useResourceName', () => () => ({
  resourceName: computed(() => ''),
  load: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useTeamShellContext', () => () => ({ team: ref(null) }))

const err = (status: number) => ({ statusCode: status, response: { status } })

type Case = {
  name: string
  component: Component
  props: Record<string, unknown>
  fail: (status: number) => void
  /** 500 / シェル外の 403 で従来の通知が 1 回出ること（通知経路を持たない取得は null） */
  legacyToast: (() => number) | null
}

const cases: Case[] = [
  {
    name: 'EquipmentList（備品一覧）',
    component: EquipmentList,
    props: { scopeType: 'team', scopeId: 'team-a' },
    fail: (s) => getEquipmentList.mockRejectedValue(err(s)),
    legacyToast: () => showError.mock.calls.length,
  },
  {
    name: 'MatchRequestList（募集一覧）',
    component: MatchRequestList,
    props: { teamId: 'team-a' },
    fail: (s) => getTeamRequests.mockRejectedValue(err(s)),
    legacyToast: () => showError.mock.calls.length,
  },
  {
    name: 'FriendFolderList（フォルダ一覧）',
    component: FriendFolderList,
    props: { teamId: 'team-a', canEdit: false },
    fail: (s) => listFolders.mockRejectedValue(err(s)),
    legacyToast: () => handleApiError.mock.calls.length,
  },
  {
    name: 'SupporterManagementPanel（サポーター一覧）',
    component: SupporterManagementPanel,
    props: { scopeType: 'team', scopeId: 'team-a' },
    fail: (s) => getSupporters.mockRejectedValue(err(s)),
    legacyToast: null,
  },
  {
    name: 'SlotMatrixPicker（予約タブの枠グリッド）',
    component: SlotMatrixPicker,
    props: { teamId: 'team-a', isAdmin: false },
    fail: (s) => getSlotGrid.mockRejectedValue(err(s)),
    legacyToast: null,
  },
  {
    name: 'ReservationList（予約タブのチーム予約一覧）',
    component: ReservationList,
    props: { teamId: 'team-a', canManage: true, mode: 'team' },
    fail: (s) => listReservations.mockRejectedValue(err(s)),
    legacyToast: () => notifyError.mock.calls.length,
  },
]

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await nextTick()
}

async function mountCase(c: Case, withProp: boolean) {
  return mountSuspended(c.component, {
    props: withProp ? { ...c.props, scopeNotFoundOnDenied: true } : c.props,
  })
}

beforeEach(() => {
  for (const f of [getEquipmentList, getTeamRequests, listFolders, getSupporters, getReservationSettings, getSlotGrid, listReservations, notifyError, showError, handleApiError]) {
    f.mockReset()
  }
  getSupporters.mockResolvedValue({ data: [] })
  getReservationSettings.mockResolvedValue({ data: {} })
  getSlotGrid.mockResolvedValue({ data: { days: [] } })
  listReservations.mockResolvedValue({ data: [], meta: { total: 0 } })
})
afterEach(() => {
  useScopeNotFound().reset()
})

describe('子コンポーネントの主取得 — 非所属・権限なし（CMP-261125-1108 g5）', () => {
  for (const c of cases) {
    describe(c.name, () => {
      for (const status of [403, 404]) {
        it(`g5-1: prop 指定で ${status} → notFound を立て、トーストを出さない`, async () => {
          c.fail(status)
          const w = await mountCase(c, true)
          await flush()
          expect(useScopeNotFound().notFound.value).toBe(true)
          expect(showError).not.toHaveBeenCalled()
          expect(handleApiError).not.toHaveBeenCalled()
          w.unmount()
        })
      }

      it('g5-2: prop 指定でも 500 → notFound は立てない（従来の通知）', async () => {
        c.fail(500)
        const w = await mountCase(c, true)
        await flush()
        expect(useScopeNotFound().notFound.value).toBe(false)
        if (c.legacyToast) expect(c.legacyToast()).toBe(1)
        w.unmount()
      })

      it('g5-3: prop 未指定（シェル外想定）は 403 でも notFound を立てず従来どおり', async () => {
        c.fail(403)
        const w = await mountCase(c, false)
        await flush()
        expect(useScopeNotFound().notFound.value).toBe(false)
        if (c.legacyToast) expect(c.legacyToast()).toBe(1)
        w.unmount()
      })
    })
  }
})

describe('TeamReservationsPanel — 予約設定は補助取得（CMP-261125-1108 g5-4）', () => {
  const SlotMatrixPickerStub = {
    name: 'SlotMatrixPicker',
    props: ['teamId', 'teamTimezone', 'isAdmin', 'scopeNotFoundOnDenied'],
    template: '<div data-testid="slot-matrix-stub" />',
  }

  for (const status of [403, 404]) {
    it(`g5-4: prop 指定でも予約設定の取得が ${status} なら notFound を立てない（ページ全体を置き換えない）`, async () => {
      getReservationSettings.mockRejectedValue(err(status))
      const w = await mountSuspended(TeamReservationsPanel, {
        props: { teamId: 'team-a', scopeNotFoundOnDenied: true },
        global: { stubs: { SlotMatrixPicker: SlotMatrixPickerStub, ReservationList: true, ReservationMyWaitlistList: true } },
      })
      await flush()
      expect(useScopeNotFound().notFound.value).toBe(false)
      expect(showError).not.toHaveBeenCalled()
      expect(handleApiError).not.toHaveBeenCalled()
      w.unmount()
    })
  }

  it('g5-4: prop 指定時は主取得の枠グリッドへ scopeNotFoundOnDenied を引き渡す', async () => {
    getReservationSettings.mockResolvedValue({ data: { allowPublicReservation: true } })
    const w = await mountSuspended(TeamReservationsPanel, {
      props: { teamId: 'team-a', scopeNotFoundOnDenied: true },
      global: { stubs: { SlotMatrixPicker: SlotMatrixPickerStub, ReservationList: true, ReservationMyWaitlistList: true } },
    })
    await flush()
    const picker = w.findComponent({ name: 'SlotMatrixPicker' })
    expect(picker.exists()).toBe(true)
    expect(picker.props('scopeNotFoundOnDenied')).toBe(true)
    w.unmount()
  })
})
