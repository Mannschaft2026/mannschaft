import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { Component } from 'vue'
import EquipmentList from '~/components/equipment/EquipmentList.vue'
import MatchRequestList from '~/components/matching/MatchRequestList.vue'
import FriendFolderList from '~/components/friends/FriendFolderList.vue'
import SupporterManagementPanel from '~/components/SupporterManagementPanel.vue'
import TeamReservationsPanel from '~/components/reservation/TeamReservationsPanel.vue'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 (g5): 子コンポーネントが主取得を行うチーム子ページ
 * （equipment / matching / friend-folders / supporters / reservations）。
 *
 *   g5-1 scopeNotFoundOnDenied 指定で主取得が 403/404 → notFound を立て、エラートーストを出さない
 *   g5-2 主取得が 500 → 従来どおり、notFound は立てない
 *   g5-3 prop 未指定（シェル外想定）では 403 でも notFound は立たず、従来どおり
 */

const getEquipmentList = vi.fn()
const getTeamRequests = vi.fn()
const listFolders = vi.fn()
const getSupporters = vi.fn()
const getReservationSettings = vi.fn()
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
  error: vi.fn(),
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
mockNuxtImport('useReservationApi', () => () => ({ getReservationSettings }))
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
    name: 'TeamReservationsPanel（予約設定）',
    component: TeamReservationsPanel,
    props: { teamId: 'team-a' },
    fail: (s) => getReservationSettings.mockRejectedValue(err(s)),
    legacyToast: null,
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
  for (const f of [getEquipmentList, getTeamRequests, listFolders, getSupporters, getReservationSettings, showError, handleApiError]) {
    f.mockReset()
  }
  getSupporters.mockResolvedValue({ data: [] })
  getReservationSettings.mockResolvedValue({ data: {} })
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
