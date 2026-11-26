import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import AnalyticsPage from '~/pages/organizations/[slug]/analytics.vue'
import AuditLogsPage from '~/pages/organizations/[slug]/audit-logs.vue'
import VotingPage from '~/pages/organizations/[slug]/voting.vue'
import AnnualPlanPage from '~/pages/organizations/[slug]/annual-plan.vue'
import TournamentsPage from '~/pages/organizations/[slug]/tournaments/index.vue'
import { useScopeNotFound } from '~/composables/useScopeNotFound'

/**
 * CMP-261125-1108 第3陣: 組織の子ページ（主 API の 403/404 → ScopeNotFound）。
 *
 *   SNF-G3-1 主 API が 403 → notFound を立て、エラートーストを出さない
 *   SNF-G3-2 主 API が 404 → 同上
 *   SNF-G3-3 主 API が 500 → 従来どおりエラートーストを出し、notFound は立てない
 */

const mocks = vi.hoisted(() => ({
  getAnalytics: vi.fn(),
  listByOrg: vi.fn(),
  getSessions: vi.fn(),
  getAnnualView: vi.fn(),
  getTournaments: vi.fn(),
  notifyError: vi.fn(),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-a' }, query: {}, path: '/organizations/org-a/x' }))
mockNuxtImport('useAnalyticsApi', () => () => ({ getAnalytics: mocks.getAnalytics }))
mockNuxtImport('useAuditLogApi', () => () => ({ listByOrg: mocks.listByOrg }))
mockNuxtImport('useVotingApi', () => () => ({ getSessions: mocks.getSessions, createSession: vi.fn() }))
mockNuxtImport('useAnnualPlanApi', () => () => ({
  getAnnualView: mocks.getAnnualView,
  getCopyPreview: vi.fn(),
  executeCopy: vi.fn(),
}))
mockNuxtImport('useTournamentApi', () => () => ({ getTournaments: mocks.getTournaments, createTournament: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({
  isAdmin: ref(false),
  isAdminOrDeputy: ref(false),
  loadPermissions: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({
  success: vi.fn(),
  error: mocks.notifyError,
  warn: vi.fn(),
  info: vi.fn(),
}))

afterEach(() => {
  useScopeNotFound().reset()
})

async function flush() {
  for (let i = 0; i < 6; i++) await Promise.resolve()
  await nextTick()
}

const CASES = [
  { label: 'analytics', page: AnalyticsPage, api: () => mocks.getAnalytics },
  { label: 'audit-logs', page: AuditLogsPage, api: () => mocks.listByOrg },
  { label: 'voting', page: VotingPage, api: () => mocks.getSessions },
  { label: 'annual-plan', page: AnnualPlanPage, api: () => mocks.getAnnualView },
  { label: 'tournaments/index', page: TournamentsPage, api: () => mocks.getTournaments },
] as const

beforeEach(() => {
  for (const fn of Object.values(mocks)) fn.mockReset()
})

for (const { label, page, api } of CASES) {
  describe(`pages/organizations/[slug]/${label}.vue — 非所属・権限なし（CMP-261125-1108）`, () => {
    for (const status of [403, 404] as const) {
      it(`SNF-G3-${status === 403 ? 1 : 2}: 主 API が ${status} なら notFound を立て、エラートーストを出さない`, async () => {
        api().mockRejectedValueOnce({ statusCode: status, response: { status } })

        const wrapper = await mountSuspended(page, { global: { stubs: { ClientOnly: true } } })
        await flush()

        expect(mocks.notifyError).not.toHaveBeenCalled()
        expect(useScopeNotFound().notFound.value).toBe(true)
        wrapper.unmount()
      })
    }

    it('SNF-G3-3: 主 API が 500 なら従来どおりエラートーストを出し、notFound は立てない', async () => {
      api().mockRejectedValueOnce({ statusCode: 500, response: { status: 500 } })

      const wrapper = await mountSuspended(page, { global: { stubs: { ClientOnly: true } } })
      await flush()

      expect(mocks.notifyError).toHaveBeenCalledTimes(1)
      expect(useScopeNotFound().notFound.value).toBe(false)
      wrapper.unmount()
    })
  })
}
