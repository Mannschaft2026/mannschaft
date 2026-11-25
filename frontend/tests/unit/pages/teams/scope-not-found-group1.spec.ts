import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import AnalyticsPage from '~/pages/teams/[slug]/analytics.vue'
import AuditLogsPage from '~/pages/teams/[slug]/audit-logs.vue'
import VotingPage from '~/pages/teams/[slug]/voting.vue'
import ServiceRecordsPage from '~/pages/teams/[slug]/service-records.vue'
import GamificationPage from '~/pages/teams/[slug]/gamification.vue'
import MemberInfoPage from '~/pages/teams/[slug]/member-info.vue'
import SchedulePage from '~/pages/teams/[slug]/schedule.vue'

/**
 * CMP-261125-1108 グループ1: チーム子ページの主 API が 403/404 のとき、
 * markIfDenied 経由で notFound を立て、エラートーストを出さない。500 は従来どおりトースト。
 */

const getAnalytics = vi.fn()
const listByTeam = vi.fn()
const getSessions = vi.fn()
const getRecords = vi.fn()
const getMyPoints = vi.fn()
const getMyBadges = vi.fn()
const getRankings = vi.fn()
const getMyResponses = vi.fn()
const listSchedules = vi.fn()
const notifyError = vi.fn()

vi.mock('~/composables/useTeamShellContext', () => ({
  useTeamShellContext: () => ({ team: { value: null } }),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-a' }, query: {}, path: '/teams/team-a/x' }))
mockNuxtImport('useAnalyticsApi', () => () => ({ getAnalytics }))
mockNuxtImport('useAuditLogApi', () => () => ({ listByTeam }))
mockNuxtImport('useVotingApi', () => () => ({ getSessions, createSession: vi.fn() }))
mockNuxtImport('useServiceRecordApi', () => () => ({ getRecords, createRecord: vi.fn() }))
mockNuxtImport('useGamificationApi', () => () => ({ getMyPoints, getMyBadges, getRankings }))
mockNuxtImport('useMemberInfoApi', () => () => ({ getMyResponses, upsertMyResponses: vi.fn() }))
mockNuxtImport('useScheduleApi', () => () => ({ listSchedules, getSchedule: vi.fn(), deleteSchedule: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: computed(() => false),
  isAdminOrDeputy: computed(() => false),
  roleName: computed(() => 'MEMBER'),
  can: () => false,
  loadPermissions: vi.fn().mockResolvedValue(undefined),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))

type ScopeNotFoundApi = { notFound: { value: boolean }, reset: () => void }
async function scopeNotFound(): Promise<ScopeNotFoundApi> {
  const mod = await import('~/composables/useScopeNotFound')
  return mod.useScopeNotFound() as unknown as ScopeNotFoundApi
}

afterEach(async () => {
  ;(await scopeNotFound()).reset()
})

async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve()
  await nextTick()
}

beforeEach(() => {
  for (const m of [getAnalytics, listByTeam, getSessions, getRecords, getMyPoints, getMyBadges, getRankings, getMyResponses, listSchedules, notifyError]) {
    m.mockReset()
  }
  getMyBadges.mockResolvedValue([])
  getRankings.mockResolvedValue([])
  getMyPoints.mockResolvedValue(null)
})

const err = (status: number) => ({ statusCode: status, response: { status } })

type Case = { name: string, page: object, fail: (status: number) => void, toastOn500: boolean }
const cases: Case[] = [
  { name: 'analytics', page: AnalyticsPage, fail: (s) => getAnalytics.mockRejectedValue(err(s)), toastOn500: true },
  { name: 'audit-logs', page: AuditLogsPage, fail: (s) => listByTeam.mockRejectedValue(err(s)), toastOn500: true },
  { name: 'voting', page: VotingPage, fail: (s) => getSessions.mockRejectedValue(err(s)), toastOn500: true },
  { name: 'service-records', page: ServiceRecordsPage, fail: (s) => getRecords.mockRejectedValue(err(s)), toastOn500: true },
  { name: 'gamification', page: GamificationPage, fail: (s) => getMyPoints.mockRejectedValue(err(s)), toastOn500: true },
  { name: 'member-info', page: MemberInfoPage, fail: (s) => getMyResponses.mockRejectedValue(err(s)), toastOn500: true },
  // schedule は従来から一覧失敗でトーストを出さない（空表示）。notFound の立ち下がりのみ検証する
  { name: 'schedule', page: SchedulePage, fail: (s) => listSchedules.mockRejectedValue(err(s)), toastOn500: false },
]

describe('チーム子ページ グループ1 — 非所属・権限なし（CMP-261125-1108）', () => {
  for (const c of cases) {
    for (const status of [403, 404]) {
      it(`${c.name}: 主 API が ${status} なら notFound を立て、エラートーストを出さない`, async () => {
        c.fail(status)
        const wrapper = await mountSuspended(c.page)
        await flush()
        expect(notifyError).not.toHaveBeenCalled()
        expect((await scopeNotFound()).notFound.value).toBe(true)
        wrapper.unmount()
      })
    }

    it(`${c.name}: 主 API が 500 なら notFound は立てない${c.toastOn500 ? '（従来どおりトースト）' : ''}`, async () => {
      c.fail(500)
      const wrapper = await mountSuspended(c.page)
      await flush()
      if (c.toastOn500) expect(notifyError).toHaveBeenCalled()
      expect((await scopeNotFound()).notFound.value).toBe(false)
      wrapper.unmount()
    })
  }
})
